package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.CoachActState
import forma.app.data.CoachActions
import forma.app.data.CoachTalk
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.MovedRun
import forma.app.data.SettingsStore
import forma.app.data.Status
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.app.sync.CoachApi
import forma.app.sync.CoachCall
import forma.app.sync.CoachReply
import forma.app.sync.CoachTurn
import forma.app.ui.screens.coachContext
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.DayPlan
import forma.core.plan.DaySwap
import forma.core.plan.Focus
import forma.core.plan.PlannedSession
import forma.core.plan.SessionKind
import forma.core.plan.WeekPlan
import forma.core.run.RunType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis, 26 November: «например я пропустил день ног и он не скоро, смогу ли я попросить тренера поставить мне сегодня
 * день ног с бегом, а сегодняшний верх перенести на субботу». The coach changes the places of two days of this week
 * (swap_days, through «Принять»): the whole day moves, the week, the day, the runs and the coach's summary follow, and
 * from the next week the days are as planned. His week: a hard run on Monday and Thursday, «Верх · Б» on Tuesday,
 * «Верх · А» on Thursday, «Низ» and an easy run on Saturday; asked on Thursday the 26th.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SwapDaysCoachTest {
    private lateinit var db: FormaDatabase
    private lateinit var store: SettingsStore
    private lateinit var repo: TrainingRepository
    private val monday = LocalDate.of(2026, 11, 23)
    private val thursday = monday.plusDays(3)
    private val friday = monday.plusDays(4)
    private val saturday = monday.plusDays(5)
    private var clock = thursday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val hard = PlannedSession(SessionKind.RUN_QUALITY)
    private val easy = PlannedSession(SessionKind.RUN_EASY)
    private val upperA = PlannedSession(SessionKind.LIFT, "upper_a")
    private val lower = PlannedSession(SessionKind.LIFT, "lower_a")
    private val thuSat = DaySwap(monday.toEpochDay(), 4, 6)
    private val denis = Profile(
        goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
        bodyWeightKg = 73.0, runGoal = RunGoal(1500, 330), lapMeters = 205,
        aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
    )

    @Before fun setUp() = runBlocking<Unit> {
        forma.app.run.RunSession.clear()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        store = SettingsStore(ctx, "swap-days-coach")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock++ })
        repo.completeOnboarding(denis)
        val p = store.programNow()!!
        val t = p.templates.first()
        store.setProgram(
            p.copy(
                // «Низ» is the shorter session here: the sets planned of a day say which session the day holds.
                templates = listOf(
                    t.copy(id = "upper_b", title = "Верх · Б", focus = Focus.UPPER), t.copy(id = "upper_a", title = "Верх · А", focus = Focus.UPPER),
                    t.copy(id = "lower_a", title = "Низ", focus = Focus.LOWER, slots = t.slots.take(2)),
                ),
                // The second week of a block: not a lighter one.
                startEpochDay = monday.minusWeeks(1).toEpochDay(), createdEpochDay = monday.minusWeeks(6).toEpochDay(),
                week = WeekPlan(
                    listOf(
                        DayPlan(1, listOf(hard)),
                        DayPlan(2, listOf(PlannedSession(SessionKind.LIFT, "upper_b"))),
                        DayPlan(4, listOf(hard, upperA)),
                        DayPlan(6, listOf(lower, easy)),
                    ),
                    p.week.notes,
                ),
            ),
        )
        // Running for a while, the last run on Monday: no break before this week's runs.
        store.setRunState(store.runStateNow().copy(walkStage = -1, runs = 12, vdot = null, easyMinutes = 30, qualityCount = 3, lastRunDay = monday.toEpochDay()))
        add(monday, Kind.RUN, "INTERVALS")
        add(monday.plusDays(1), Kind.LIFT, "upper_b")
    }

    @After fun tearDown() {
        forma.app.run.RunSession.clear()
        db.close()
    }

    private var seq = 0L

    private suspend fun add(d: LocalDate, kind: String, template: String): Long {
        val at = d.atTime(16, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 60_000 * seq++
        return db.workouts().insert(
            WorkoutEntity(
                epochDay = d.toEpochDay(), kind = kind, templateId = template, title = template, startedAt = at, finishedAt = at + 1_800_000,
                status = Status.DONE, rpe = 6, durationSec = 1800, load = 120.0,
            ),
        )
    }

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) } + ("why" to JsonPrimitive("ноги не скоро"))),
    )

    private suspend fun ready(day: LocalDate, name: String, vararg a: Pair<String, Any>) =
        assertIs<CoachActions.Ready>(CoachActions.prepare(repo, call(name, *a), day), "$name ${a.toList()}")

    private suspend fun refused(day: LocalDate, name: String, vararg a: Pair<String, Any>) =
        assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call(name, *a), day), "$name ${a.toList()}")

    private suspend fun swap(a: String = "чт", b: String = "сб", day: LocalDate = thursday) = ready(day, CoachActions.SWAP_DAYS, "day_a" to a, "day_b" to b)

    private suspend fun planned(date: LocalDate) = repo.weekFlow(date.with(java.time.DayOfWeek.MONDAY)).first().first { it.date == date }.planned

    private suspend fun sets(date: LocalDate) = repo.plannedSets(date, date).values.sum()

    @Test fun thursdayAndSaturdayChangePlacesThisWeek() = runBlocking<Unit> {
        val r = swap()
        assertEquals(CoachActions.Mode.CONFIRM, r.mode, "always waits for «Принять»")
        assertEquals(
            "Поменять местами четверг и субботу на этой неделе. Четверг: «Низ» и лёгкий бег. Суббота: тяжёлый бег и «Верх · А». Со следующей недели как в плане.",
            r.text,
        )
        // Named the other way round: the same card, the days in the week's order.
        assertEquals(r.text, swap("суббота", "четверг").text)
        // Nothing changes before «Принять».
        assertEquals(listOf(hard, upperA), planned(thursday))
        CoachActions.make(repo, r)
        assertEquals(listOf(thuSat), store.settingsNow().daySwaps)

        // The week: the whole day moves, the hard run with the upper body.
        assertEquals(listOf(lower, easy), planned(thursday))
        assertEquals(listOf(hard, upperA), planned(saturday))
        assertEquals(listOf(hard), planned(monday))
        assertTrue(planned(friday).isEmpty())
        // From the next week everything is as in the plan.
        assertEquals(listOf(hard, upperA), planned(thursday.plusWeeks(1)))
        assertEquals(listOf(lower, easy), planned(saturday.plusWeeks(1)))

        // Done on its new day, a session is that day's own in the week's count.
        add(thursday, Kind.LIFT, "lower_a")
        val week = repo.weekFlow(monday).first()
        assertTrue(week.first { it.date == thursday }.isDone(lower))
        assertNotNull(week.first { it.date == thursday }.doneFor(lower))
        assertFalse(week.first { it.date == saturday }.isDone(upperA))
    }

    @Test fun theDaysOrderFollows() = runBlocking<Unit> {
        assertTrue(assertNotNull(repo.dayOrder(thursday)).advice.runFirst, "the hard run before the upper body")
        CoachActions.make(repo, swap())
        // The gym first on the leg day (the easy run after it), the hard run first on Saturday.
        val thu = assertNotNull(repo.dayOrder(thursday))
        assertFalse(thu.advice.runFirst)
        assertEquals(listOf(lower, easy), thu.ordered(planned(thursday)))
        val sat = assertNotNull(repo.dayOrder(saturday))
        assertTrue(sat.advice.runFirst)
        assertEquals(listOf(hard, upperA), sat.ordered(planned(saturday)))
    }

    @Test fun theRunsFollow() = runBlocking<Unit> {
        // The coach's long easy run: the week's last easy run is the long one, wherever the week has it.
        CoachActions.make(repo, ready(thursday, CoachActions.LONG, "weeks" to 2))
        val before = repo.runFor(SessionKind.RUN_EASY, saturday)
        assertEquals(RunType.LONG, before.type)
        CoachActions.make(repo, swap())
        // Each run by its day's session: Saturday's easy (long) one on Thursday, the hard one on Saturday.
        val thu = planned(thursday).filter { it.isRun }.map { repo.runFor(it.kind, thursday) }
        assertEquals(listOf(RunType.LONG), thu.map { it.type })
        assertEquals(before.title, thu.single().title, "the same run, on its new day")
        val sat = planned(saturday).filter { it.isRun }.map { repo.runFor(it.kind, saturday).type }
        assertTrue(sat.size == 1 && sat.single() !in setOf(RunType.EASY, RunType.LONG), "$sat")
        assertEquals(RunType.EASY, repo.runFor(SessionKind.RUN_EASY, saturday).type, "no long run on Saturday this week")
        // Without the coach's long run: the week's easy run is Thursday's.
        store.editSettings { it.copy(coachLong = null) }
        assertEquals(thursday, CoachActions.nextEasy(repo, thursday)?.day)
        assertEquals(saturday.plusWeeks(1), CoachActions.nextEasy(repo, saturday.plusDays(1))?.day, "next week as planned")
    }

    @Test fun theSetsPlannedOfADayFollow() = runBlocking<Unit> {
        val upperSets = sets(thursday)
        val lowerSets = sets(saturday)
        assertTrue(lowerSets > 0 && lowerSets < upperSets, "$lowerSets of «Низ», $upperSets of «Верх · А»")
        CoachActions.make(repo, swap())
        assertEquals(lowerSets, sets(thursday))
        assertEquals(upperSets, sets(saturday))
        assertEquals(upperSets, sets(thursday.plusWeeks(1)))
    }

    @Test fun aTrialDueOrTheRaceKeepsItsDayAndTheTrialGoesByTheWeekAsItStands() = runBlocking<Unit> {
        // A trial asked for falls on Thursday's hard run: the day is not moved.
        store.setRunState(store.runStateNow().copy(testSoon = true))
        assertEquals(thursday, repo.trialDay(thursday))
        assertTrue("контрольный забег" in swap0().why, swap0().why)
        // With the days changed all the same (set before the trial was asked for): the trial is on a hard day of the week
        // as it stands, three days from the legs — Monday, not Saturday two days after Thursday's «Низ».
        store.editSettings { it.copy(daySwaps = listOf(thuSat)) }
        assertEquals(monday.plusWeeks(1), repo.trialDay(thursday))
        store.editSettings { it.copy(daySwaps = emptyList()) }
        store.setRunState(store.runStateNow().copy(testSoon = false))
        // The race on Saturday: its day stays.
        store.setProfile(denis.copy(runGoal = RunGoal(1500, 330, saturday.toEpochDay())))
        val race = swap0().why
        assertTrue(race.startsWith("В субботу забег"), race)
    }

    @Test fun aSwapAgainstTheRulesIsRefusedWithTheReason() = runBlocking<Unit> {
        // A day already over.
        assertTrue(refused(thursday, CoachActions.SWAP_DAYS, "day_a" to "вт", "day_b" to "сб").why.startsWith("Вторник этой недели уже позади"))
        // Legs next to a hard run: «Низ» on Sunday, the hard run on Monday.
        val legs = refused(thursday, CoachActions.SWAP_DAYS, "day_a" to "сб", "day_b" to "вс").why
        assertTrue("ноги в воскресенье" in legs && "тяжёлый бег в понедельник" in legs, legs)
        // The same plan on both days.
        assertTrue("одно и то же" in refused(thursday, CoachActions.SWAP_DAYS, "day_a" to "пт", "day_b" to "вс").why)
        // Not two days.
        refused(thursday, CoachActions.SWAP_DAYS, "day_a" to "чт", "day_b" to "чт")
        refused(thursday, CoachActions.SWAP_DAYS, "day_a" to "чт")
        refused(thursday, CoachActions.SWAP_DAYS, "day_a" to "чт", "day_b" to "когда-нибудь")
        // The days as numbers and as full names are days too, and so is «сегодня».
        ready(thursday, CoachActions.SWAP_DAYS, "day_a" to 4, "day_b" to "суббота")
        assertEquals(swap().text, ready(thursday, CoachActions.SWAP_DAYS, "day_a" to "сегодня", "day_b" to "сб").text)
        // «Перенеси сегодняшнее на завтра» on Thursday: the hard run would stand before Saturday's legs.
        assertTrue("тяжёлый бег в пятницу" in refused(thursday, CoachActions.SWAP_DAYS, "day_a" to "сегодня", "day_b" to "завтра").why)

        // A paused day.
        repo.pause(saturday, saturday)
        assertTrue("пауза" in swap0().why)
        store.editSettings { it.copy(pauseFrom = null, pauseUntil = null, pastPauses = emptyList()) }
        swap()

        // A session started today: the day stays.
        val going = repo.startLift("upper_a", thursday)
        assertTrue("уже" in swap0().why)
        repo.cancelWorkout(going)
        swap()
        // A run going on now is today's too, saved or not yet.
        forma.app.run.RunSession.tracker = forma.core.run.RunTracker(repo.runFor(SessionKind.RUN_QUALITY, thursday)).also { it.start(0) }
        assertTrue("уже" in swap0().why)
        forma.app.run.RunSession.clear()
        swap()
        // A session done today.
        add(thursday, Kind.RUN, "TEMPO")
        val done = swap0().why
        assertTrue(done.startsWith("Сегодня") && "уже" in done, done)
    }

    private suspend fun swap0() = refused(thursday, CoachActions.SWAP_DAYS, "day_a" to "чт", "day_b" to "сб")

    @Test fun aRunTheCoachPutOnADayOrAMovedHardRunKeepsTheDay() = runBlocking<Unit> {
        // The coach's own run of today (run_custom) is today's: the day is not moved under it.
        CoachActions.make(repo, ready(thursday, CoachActions.RUN_CUSTOM, "reps" to 1, "work_sec" to 1200, "pace" to "easy"))
        assertTrue("пробежка от тренера" in swap0().why, swap0().why)
        store.editSettings { it.copy(coachRun = null) }
        swap()
        // A hard run moved a day later on the missed «Низ» card: neither of its days changes places.
        store.editSettings { it.copy(movedRun = MovedRun(thursday.toEpochDay(), friday.toEpochDay())) }
        assertTrue("перенес" in swap0().why, swap0().why)
    }

    @Test fun acceptedMadeAndTakenBack() = runBlocking<Unit> {
        val api = object : CoachApi {
            override suspend fun askCoach(turns: List<CoachTurn>, context: String, tools: JsonArray): CoachReply =
                CoachReply("Меняю четверг и субботу.", listOf(call(CoachActions.SWAP_DAYS, "day_a" to "чт", "day_b" to "сб")))
        }
        val msg = CoachTalk.ask(repo, api, "поменяй четверг и субботу") { "сводка" }
        assertEquals(listOf(CoachActState.PENDING), msg.acts.map { it.state })
        assertTrue(store.settingsNow().daySwaps.isEmpty(), "waits for «Принять»")
        assertEquals(CoachActState.DONE, CoachTalk.accept(repo, msg.id, 0)?.state)
        assertEquals(listOf(thuSat), store.settingsNow().daySwaps)
        assertEquals(listOf(lower, easy), planned(thursday))
        // «Вернуть»: the week is as planned again.
        assertTrue(CoachTalk.undo(repo, msg.id, 0))
        assertTrue(store.settingsNow().daySwaps.isEmpty())
        assertEquals(listOf(hard, upperA), planned(thursday))
        assertEquals(listOf(lower, easy), planned(saturday))
    }

    @Test fun theSameTwoDaysChangedAgainLeaveTheWeekAsPlanned() = runBlocking<Unit> {
        val first = CoachActions.make(repo, swap())
        // Asked again, the days go back: the card says what each becomes.
        val r = swap()
        assertTrue("Четверг: тяжёлый бег и «Верх · А». Суббота: «Низ» и лёгкий бег." in r.text, r.text)
        val second = CoachActions.make(repo, r)
        assertTrue(store.settingsNow().daySwaps.isEmpty())
        assertEquals(listOf(hard, upperA), planned(thursday))
        // Nothing is left to take back: neither change has a way back that would do anything.
        assertNull(second.undo)
        assertFalse(CoachActions.undo(repo, first))
        assertFalse("поменяны местами" in coachContext(repo, thursday).substringBefore("## Как решает приложение"))
    }

    @Test fun notTakenBackOnceASessionIsDoneOnOneOfTheDays() = runBlocking<Unit> {
        val entry = CoachActions.make(repo, swap())
        add(thursday, Kind.LIFT, "lower_a")
        assertFalse(CoachActions.undo(repo, entry), "Thursday's «Низ» is done: the days stay")
        assertEquals(listOf(thuSat), store.settingsNow().daySwaps)
        assertFalse(repo.canSwapBack(thuSat, thursday))
        // The same two days asked again: refused too, the day is begun.
        assertTrue("уже" in swap0().why)
    }

    @Test fun anEasedWeekdayFollowsItsSessionToTheNewDate() = runBlocking<Unit> {
        // Thursday's hard run easy for a week (ease_run_day), then Thursday and Saturday change places.
        CoachActions.make(repo, ready(thursday, CoachActions.EASE_DAY, "day" to "чт", "weeks" to 1))
        assertEquals(RunType.EASY, repo.runFor(SessionKind.RUN_QUALITY, thursday).type)
        CoachActions.make(repo, swap())
        val s = store.settingsNow()
        assertNotNull(repo.easedUntil(s, saturday), "the eased hard run is Saturday's this week")
        assertNull(repo.easedUntil(s, thursday), "Thursday holds Saturday's plan")
        val sat = repo.runFor(SessionKind.RUN_QUALITY, saturday)
        assertEquals(RunType.EASY, sat.type)
        assertTrue(sat.note.startsWith("По твоей просьбе в этот день лёгкий бег"), sat.note)
        // Asked for Saturday while the hard run is there: the same hard run (the plan's Thursday), not «no hard run on Saturday».
        store.editSettings { it.copy(easyRunDays = emptyMap()) }
        val r = ready(thursday, CoachActions.EASE_DAY, "day" to "сб", "weeks" to 1)
        assertTrue("четверг" in r.text && "суббот" in r.text, r.text)
        CoachActions.make(repo, r)
        assertEquals(setOf(4), store.settingsNow().easyRunDays.keys)
        assertEquals(RunType.EASY, repo.runFor(SessionKind.RUN_QUALITY, saturday).type)
    }

    @Test fun theCoachIsToldOfTheSwap() = runBlocking<Unit> {
        assertFalse("поменяны местами" in coachContext(repo, thursday).substringBefore("## Как решает приложение"))
        CoachActions.make(repo, swap())
        val brief = coachContext(repo, thursday)
        val line = brief.lines().firstOrNull { it.startsWith("На этой неделе поменяны местами четверг и суббота") }
        assertNotNull(line, brief.take(1500))
        assertTrue("Четверг: «Низ» и лёгкий бег" in line && "Суббота: тяжёлый бег и «Верх · А»" in line, line)
        // Today as «Сегодня» shows it, and the runs ahead on their days.
        val today = brief.substringAfter("По плану сегодня (четверг):\n").lines().takeWhile { it.startsWith("- ") }.joinToString("\n")
        assertTrue("Силовая «Низ»" in today && "Верх · А" !in today, today)
        val ahead = brief.substringAfter("- Ближайшие пробежки:").substringBefore("\n## ")
        assertTrue(ahead.lines().any { it.trim().startsWith("- 26 ноября: Лёгкий") }, ahead)
        assertTrue(ahead.lines().any { it.trim().startsWith("- 28 ноября:") && "Лёгкий" !in it }, ahead)
        // What it can do is in its map of the app and among its functions.
        assertTrue("swap_days" in forma.app.ui.screens.COACH_MAP)
        assertTrue(CoachActions.declarations.any { ((it as JsonObject)["name"] as JsonPrimitive).content == CoachActions.SWAP_DAYS })
    }

    @Test fun aWeekLaidOutAnewKeepsNoSwap() = runBlocking<Unit> {
        CoachActions.make(repo, swap())
        assertEquals(listOf(thuSat), store.settingsNow().daySwaps)
        // Other days in the plan: the days changed places were of the old layout.
        repo.updateProfile(denis.copy(trainingDays = setOf(1, 3, 5)))
        assertTrue(store.settingsNow().daySwaps.isEmpty())
    }
}
