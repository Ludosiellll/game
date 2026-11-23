package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.CoachActState
import forma.app.data.CoachActions
import forma.app.data.CoachTalk
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
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
import forma.core.plan.Focus
import forma.core.plan.PlannedSession
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.plan.WeekPlan
import forma.core.run.RunResult
import forma.core.run.RunType
import forma.core.run.SegmentKind
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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis, 23 November: «иишка может не все (она не имеет некоторых функций) а я хочу чтобы она буквально любой запрос
 * понимала, как будто имеет полное управление над приложением». The coach gets the levers that were missing — a run of
 * its own making, the lighter week, the order of exercises, a hard run day made easy, the ladders' steps, the app's
 * settings, the body weight — a button to the screen where a thing is done by hand, a list of wishes for what it cannot
 * do, and a second go when the app refuses a change.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CoachWideTest {
    private lateinit var db: FormaDatabase
    private lateinit var store: SettingsStore
    private lateinit var repo: TrainingRepository
    private val monday = LocalDate.of(2026, 11, 23)
    private val wednesday = monday.plusDays(2)
    private val thursday = monday.plusDays(3)
    private var clock = monday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        store = SettingsStore(ctx, "coach-wide")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock++ })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 73.0, runGoal = RunGoal(1500, 330), lapMeters = 205,
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
        // His week: hard runs on Monday and Thursday (with the upper body), an easy one on Saturday after the legs.
        val p = store.programNow()!!
        val t = p.templates.first()
        val templates = listOf(t.copy(id = "upper_a", title = "Верх", focus = Focus.UPPER), t.copy(id = "lower_a", title = "Низ", focus = Focus.LOWER))
        val upper = "upper_a"
        val legs = "lower_a"
        store.setProgram(
            p.copy(
                templates = templates,
                // The second week of a block: not a lighter one.
                startEpochDay = monday.minusWeeks(1).toEpochDay(), createdEpochDay = monday.minusWeeks(6).toEpochDay(),
                week = WeekPlan(
                    listOf(
                        DayPlan(1, listOf(PlannedSession(SessionKind.RUN_QUALITY))),
                        DayPlan(2, listOf(PlannedSession(SessionKind.LIFT, upper))),
                        DayPlan(4, listOf(PlannedSession(SessionKind.RUN_QUALITY), PlannedSession(SessionKind.LIFT, upper))),
                        DayPlan(6, listOf(PlannedSession(SessionKind.LIFT, legs), PlannedSession(SessionKind.RUN_EASY))),
                    ),
                    p.week.notes,
                ),
            ),
        )
        // The last run two days ago: no break before this week's runs.
        store.setRunState(store.runStateNow().copy(walkStage = -1, runs = 12, vdot = null, easyMinutes = 30, qualityCount = 3, lastRunDay = monday.toEpochDay() - 2))
    }

    @After fun tearDown() = db.close()

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) } + ("why" to JsonPrimitive("проверка"))),
    )

    private suspend fun ready(day: LocalDate, name: String, vararg a: Pair<String, Any>) =
        assertIs<CoachActions.Ready>(CoachActions.prepare(repo, call(name, *a), day), "$name ${a.toList()}")

    private suspend fun refused(day: LocalDate, name: String, vararg a: Pair<String, Any>) =
        assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call(name, *a), day), "$name ${a.toList()}")

    @Test fun aRunOfTheCoachsOwnMakingForToday() = runBlocking<Unit> {
        // Monday holds a hard run: «8 по 200 быстро через минуту» takes its place, with a yes.
        val r = ready(monday, CoachActions.RUN_CUSTOM, "reps" to 8, "work_m" to 200, "pace" to "fast", "rest_sec" to 60)
        assertEquals(CoachActions.Mode.CONFIRM, r.mode)
        assertTrue("Вместо «" in r.text && "8 × 1 круг" in r.text, r.text)
        val entry = CoachActions.make(repo, r)
        val run = assertNotNull(store.settingsNow().coachRun)
        assertEquals(monday.toEpochDay(), run.day)
        assertTrue(run.workout.custom)
        assertEquals(8, run.workout.segments.count { it.kind == SegmentKind.WORK })
        assertNotNull(run.workout.segments.first { it.kind == SegmentKind.WORK }.pace, "paced from the estimate")
        assertTrue("run_custom" in coachContext(repo, monday))
        // Run and saved, it counts as a run and moves no ladder.
        val before = store.runStateNow()
        repo.saveRun(run.workout, RunResult(1.0, 8, run.workout.totalSeconds), clock, emptyList(), monday)
        val after = store.runStateNow()
        assertEquals(before.runs + 1, after.runs)
        assertEquals(before.qualityCount to before.intervalStage, after.qualityCount to after.intervalStage)
        assertTrue(!CoachActions.undo(repo, entry) || store.settingsNow().coachRun == null)

        // Wednesday: Thursday's hard run is next door — a hard one is refused, an easy one made at once.
        clock = wednesday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val no = refused(wednesday, CoachActions.RUN_CUSTOM, "reps" to 6, "work_sec" to 120, "pace" to "interval")
        assertTrue("подряд" in no.why, no.why)
        val easy = ready(wednesday, CoachActions.RUN_CUSTOM, "reps" to 1, "work_sec" to 1200, "pace" to "easy")
        assertEquals(CoachActions.Mode.AUTO, easy.mode)
        // Too much hard running in one go is refused with the limit.
        assertTrue("не больше" in refused(thursday, CoachActions.RUN_CUSTOM, "reps" to 10, "work_sec" to 300, "pace" to "tempo").why)
    }

    @Test fun aHardRunDayMadeEasyForAWhile() = runBlocking<Unit> {
        val r = ready(monday, CoachActions.EASE_DAY, "day" to "чт", "weeks" to 2)
        assertEquals(CoachActions.Mode.AUTO, r.mode)
        val entry = CoachActions.make(repo, r)
        val thu = repo.runFor(SessionKind.RUN_QUALITY, thursday)
        assertEquals(RunType.EASY, thu.type)
        assertTrue("вместо тяжёлого" in thu.note, thu.note)
        // Monday stays hard; Thursday is easy for the two weeks, then hard again.
        assertNotEquals(RunType.EASY, repo.runFor(SessionKind.RUN_QUALITY, monday).type)
        assertNotNull(repo.easedUntil(store.settingsNow(), thursday.plusWeeks(1)))
        assertNull(repo.easedUntil(store.settingsNow(), thursday.plusWeeks(2)))
        assertTrue("ease_run_day" in coachContext(repo, monday))
        assertTrue(CoachActions.undo(repo, entry))
        assertNotEquals(RunType.EASY, repo.runFor(SessionKind.RUN_QUALITY, thursday).type)
        refused(monday, CoachActions.EASE_DAY, "day" to "вт", "weeks" to 2)
    }

    @Test fun theLighterWeekWhenAskedFor() = runBlocking<Unit> {
        assertTrue(!ProgramGenerator.isDeloadWeek(store.programNow()!!, monday))
        val r = ready(monday, CoachActions.LIGHTER, "when" to "this")
        assertEquals(CoachActions.Mode.CONFIRM, r.mode)
        val entry = CoachActions.make(repo, r)
        assertTrue(ProgramGenerator.isDeloadWeek(store.programNow()!!, monday))
        assertTrue(!ProgramGenerator.isDeloadWeek(store.programNow()!!, monday.plusWeeks(1)), "a new block follows")
        refused(monday, CoachActions.LIGHTER, "when" to "this")
        // «Не хочу разгрузку»: the block starts again with this week.
        CoachActions.make(repo, ready(monday, CoachActions.LIGHTER, "when" to "skip"))
        assertTrue(!ProgramGenerator.isDeloadWeek(store.programNow()!!, monday))
        assertTrue(!CoachActions.undo(repo, entry), "the block moved on since: the first change cannot be taken back")
    }

    @Test fun theOrderOfExercises() = runBlocking<Unit> {
        val t = store.programNow()!!.templates.first { !it.legs }
        val before = repo.liftPlan(t.id, monday).items
        val last = before.last()
        val r = ready(monday, CoachActions.ORDER, "exercise" to last.exerciseId, "place" to "first", "session" to t.id)
        assertEquals(CoachActions.Mode.CONFIRM, r.mode)
        val entry = CoachActions.make(repo, r)
        val now = repo.liftPlan(t.id, monday).items
        assertEquals(last.exerciseId, now.first().exerciseId)
        assertEquals(before.map { it.exerciseId }.toSet(), now.map { it.exerciseId }.toSet())
        assertTrue(CoachActions.undo(repo, entry))
        assertEquals(before.map { it.exerciseId }, repo.liftPlan(t.id, monday).items.map { it.exerciseId })
    }

    @Test fun theLaddersStepsTheAppSettingsTheBodyWeightAndTheScreens() = runBlocking<Unit> {
        // First steps cannot get easier; a harder one waits for a yes.
        assertTrue("первая ступень" in refused(monday, CoachActions.RUN_STEP, "kind" to "intervals", "change" to "easier").why)
        assertEquals(CoachActions.Mode.CONFIRM, ready(monday, CoachActions.RUN_STEP, "kind" to "race_pace", "change" to "harder").mode)
        store.setRunState(store.runStateNow().copy(intervalStage = 2))
        val easier = ready(monday, CoachActions.RUN_STEP, "kind" to "intervals", "change" to "easier")
        assertEquals(CoachActions.Mode.AUTO, easier.mode)
        val step = CoachActions.make(repo, easier)
        assertEquals(1, store.runStateNow().intervalStage)
        assertTrue(CoachActions.undo(repo, step))
        assertEquals(2, store.runStateNow().intervalStage)

        val voice = CoachActions.make(repo, ready(monday, CoachActions.APP, "key" to "voice", "value" to "off"))
        assertTrue(!store.settingsNow().voice)
        assertTrue(CoachActions.undo(repo, voice))
        assertTrue(store.settingsNow().voice)
        CoachActions.make(repo, ready(monday, CoachActions.APP, "key" to "look", "value" to "light"))
        assertEquals("light", store.settingsNow().look)
        refused(monday, CoachActions.APP, "key" to "music", "value" to "on")

        val weight = ready(monday, CoachActions.BODY_WEIGHT, "kg" to 74)
        assertEquals(CoachActions.Mode.AUTO, weight.mode)
        CoachActions.make(repo, weight)
        assertEquals(74.0, store.profileNow()!!.bodyWeightKg)
        assertEquals(CoachActions.Mode.CONFIRM, ready(monday, CoachActions.BODY_WEIGHT, "kg" to 93).mode)

        assertEquals("«История»", ready(monday, CoachActions.OPEN, "screen" to "history").text)
        refused(monday, CoachActions.OPEN, "screen" to "segments_setup")
    }

    @Test fun aWishIsKeptForTheDeveloper() = runBlocking<Unit> {
        val w = CoachActions.make(repo, ready(monday, CoachActions.WISH, "text" to "Музыка во время пробежки"))
        assertEquals(listOf("2026-11-23: Музыка во время пробежки"), store.settingsNow().coachWishes)
        refused(monday, CoachActions.WISH, "text" to "музыка во время пробежки")
        assertTrue("Музыка во время пробежки" in coachContext(repo, monday))
        assertTrue(CoachActions.undo(repo, w))
        assertTrue(store.settingsNow().coachWishes.isEmpty())
        // The map of the app goes with every question: the coach names no screen that is not there.
        assertTrue("Что где в приложении" in coachContext(repo, monday))
    }

    /** The app refuses a change: the coach is told why and puts it right, and the person sees the change, not the failure. */
    @Test fun aRefusedChangeGetsASecondGo() = runBlocking<Unit> {
        store.setRunState(store.runStateNow().copy(qualityCount = 1))
        val asked = mutableListOf<List<CoachTurn>>()
        val api = object : CoachApi {
            override suspend fun askCoach(turns: List<CoachTurn>, context: String, tools: JsonArray): CoachReply {
                asked += turns
                return if (asked.size == 1) CoachReply("Ставлю 3 по 10.", listOf(call(CoachActions.TEMPO, "reps" to 3, "work_min" to 10, "rest_min" to 1)))
                else CoachReply("Столько сразу нельзя, предлагаю 6 по 2,5 минуты.", listOf(call(CoachActions.TEMPO, "reps" to 6, "work_min" to 2.5, "rest_min" to 1)))
            }
        }
        val msg = CoachTalk.ask(repo, api, "сделай темповый 3 по 10") { "сводка" }
        assertEquals(2, asked.size)
        val told = asked[1].last()
        assertEquals(CoachTurn.USER, told.role)
        assertTrue(told.text.startsWith("[Приложение] Не сделано: set_tempo"), told.text)
        assertEquals(listOf(CoachActState.PENDING), msg.acts.map { it.state })
        assertTrue("6 × 2,5" in msg.acts.single().text, msg.acts.single().text)
        assertEquals("Столько сразу нельзя, предлагаю 6 по 2,5 минуты.", msg.text)
        // The talk holds one question and one answer.
        assertEquals(2, store.coachChatNow().size)
    }
}
