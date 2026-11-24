package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.BackupManager
import forma.app.data.CoachActState
import forma.app.data.CoachActions
import forma.app.data.CoachTalk
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.SlotStateEntity
import forma.app.data.TrainingReport
import forma.app.data.TrainingRepository
import forma.app.sync.CoachApi
import forma.app.sync.CoachCall
import forma.app.sync.CoachReply
import forma.app.sync.CoachTurn
import forma.app.ui.screens.COACH_MAP
import forma.app.ui.screens.COACH_RESEARCH
import forma.app.ui.screens.COACH_RULES
import forma.app.ui.screens.coachContext
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.LoadKind
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.DayPlan
import forma.core.plan.Focus
import forma.core.plan.PlannedSession
import forma.core.plan.SessionKind
import forma.core.plan.WeekPlan
import forma.core.run.RunLog
import forma.core.run.RunPlan
import forma.core.text.Ru
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
 * The audit of 24 November, group F-coach: what the coach is told and what its levers do, against what the screens show
 * and what the person decided. Denis's week: hard runs on Monday and Thursday (the upper body on Thursday too), the upper
 * body on Tuesday, the legs and an easy run on Saturday; 1500 m in 5:30, a 205 m lap; the second week of a 4-week block.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AuditFixCoachTest {
    private lateinit var db: FormaDatabase
    private lateinit var store: SettingsStore
    private lateinit var repo: TrainingRepository
    private val monday = LocalDate.of(2026, 11, 23)
    private val wednesday = monday.plusDays(2)
    private val saturday = monday.plusDays(5)
    private var clock = at(monday, 9)

    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        store = SettingsStore(ctx, "audit-fix-coach")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock++ })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 73.0, runGoal = RunGoal(1500, 330), lapMeters = 205,
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
        val p = store.programNow()!!
        val t = p.templates.first()
        store.setProgram(
            p.copy(
                templates = listOf(t.copy(id = "upper_a", title = "Верх", focus = Focus.UPPER), t.copy(id = "lower_a", title = "Низ", focus = Focus.LOWER)),
                // The second week of a block (a lighter one on 7–13 December).
                startEpochDay = monday.minusWeeks(1).toEpochDay(), createdEpochDay = monday.minusWeeks(6).toEpochDay(),
                week = WeekPlan(
                    listOf(
                        DayPlan(1, listOf(PlannedSession(SessionKind.RUN_QUALITY))),
                        DayPlan(2, listOf(PlannedSession(SessionKind.LIFT, "upper_a"))),
                        DayPlan(4, listOf(PlannedSession(SessionKind.RUN_QUALITY), PlannedSession(SessionKind.LIFT, "upper_a"))),
                        DayPlan(6, listOf(PlannedSession(SessionKind.LIFT, "lower_a"), PlannedSession(SessionKind.RUN_EASY))),
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

    private fun line(brief: String, start: String) =
        brief.lines().firstOrNull { it.startsWith(start) } ?: kotlin.test.fail("no line «$start…» in:\n${brief.take(4000)}")

    /** brief-trial-line-from-flag: the brief names the trial the plan places, or why there is none, not the flag. */
    @Test fun theTrialLineIsTheDayThePlanPutsItOn() = runBlocking<Unit> {
        // Asked for, two days after a try that did not count: no trial in the plan for two weeks.
        store.setRunState(store.runStateNow().copy(testSoon = true, lastTrialTryDay = monday.toEpochDay() - 2))
        assertNull(repo.trialDay(monday))
        val waiting = line(coachContext(repo, monday), "- Контрольный забег:")
        assertFalse("следующий тяжёлый день" in waiting, waiting)
        assertTrue("5 декабря" in waiting, "the earliest day, two weeks after the try: $waiting")
        val again = refused(monday, CoachActions.TRIAL, "when" to "next")
        assertFalse("следующий тяжёлый день" in again.why, again.why)
        assertTrue("5 декабря" in again.why, again.why)
        // 40 days after a counted trial with the 1500 m goal: the plan puts the next one on a hard day without the flag.
        store.setRunState(store.runStateNow().copy(testSoon = false, lastTrialTryDay = null, vdot = 40.0, lastTestDay = monday.toEpochDay() - 40))
        val day = assertNotNull(repo.trialDay(monday))
        val due = line(coachContext(repo, monday), "- Контрольный забег:")
        assertFalse("не назначен" in due, due)
        assertTrue(Ru.date(day) in due, "${Ru.date(day)}: $due")
        val twice = refused(monday, CoachActions.TRIAL, "when" to "next")
        assertTrue(Ru.date(day) in twice.why, twice.why)
    }

    /** easy-minutes-promised-vs-capped: the easy run the card and the brief name is the one «Сегодня» will start. */
    @Test fun theEasyRunsLengthIsTheOneTheWeekAllows() = runBlocking<Unit> {
        // Thin weeks (15 min of running a week, in hard sessions of 45): the week's cap holds the runs to its +10 %.
        val thin = listOf(25L, 18L, 11L, 4L).map { RunLog(monday.toEpochDay() - it, 45, 15) }
        store.setRunState(store.runStateNow().copy(easyMinutes = 40, recent = thin))
        val sat = repo.runFor(SessionKind.RUN_EASY, saturday)
        assertTrue(sat.title.startsWith("Лёгкий бег · 16 мин"), sat.title)
        val easy = ready(monday, CoachActions.EASY, "minutes" to 45)
        assertTrue("40 → 45" in easy.text, easy.text)
        assertTrue("16 мин" in easy.text, "the card says what Saturday will run: ${easy.text}")
        val brief = coachContext(repo, monday)
        assertTrue("16 мин" in line(brief, "- Ближайший лёгкий бег"), brief.lines().filter { "ёгкий бег" in it }.joinToString("\n"))
        // The coach's run of today: as long as the week allows, said so.
        val real = repo.runFor(SessionKind.RUN_EASY, wednesday, minutes = 40).workSeconds / 60
        assertTrue(real < 40, "the week's cap cuts Wednesday's run: $real")
        val today = ready(wednesday, CoachActions.RUN_TODAY, "minutes" to 40)
        assertTrue("$real мин" in today.text, today.text)
    }

    /** step-from-raises-weight: a first weight above the working one is a rise, judged as a working weight's rise is. */
    @Test fun aFirstWeightAboveTheWorkingOneIsARise() = runBlocking<Unit> {
        val p = CoachActions.places(repo).first { !it.added && Catalog[it.exerciseId].load == LoadKind.EXTERNAL }
        working(p, 30.0)
        // From 45 the weight would go 30 → 45 at once: half again, past the 25 % a weight may move.
        val no = refused(monday, CoachActions.STEP, "exercise" to p.exerciseId, "step_kg" to 5, "from_kg" to 45)
        assertTrue("45" in no.why, no.why)
        assertEquals(30.0, db.state().slot(p.tuned.key)!!.weight)
        // 40 → 45 is an eighth: the person says, and the card says the rise.
        working(p, 40.0)
        val r = ready(monday, CoachActions.STEP, "exercise" to p.exerciseId, "step_kg" to 5, "from_kg" to 45)
        assertEquals(CoachActions.Mode.CONFIRM, r.mode, r.text)
        assertTrue("вырастет" in r.text && "40" in r.text, r.text)
    }

    private suspend fun working(p: CoachActions.Place, kg: Double) =
        db.state().upsertSlot(SlotStateEntity(p.tuned.key, p.tuned.exerciseId, kg, p.tuned.repMin, 0, 0, 3, 0L, linear = false))

    /** undo-body-weight-adds-entry: «Вернуть» takes the coach's weigh-in back on its own day and adds none. */
    @Test fun takingBackAWeighInLeavesTheWeightsAsTheyWere() = runBlocking<Unit> {
        val before = db.state().bodyWeights()
        clock = at(monday.plusDays(1), 9)
        val entry = CoachActions.make(repo, ready(monday.plusDays(1), CoachActions.BODY_WEIGHT, "kg" to 76))
        assertEquals(76.0, db.state().bodyWeights().last().kg)
        // Taken back the next day.
        clock = at(monday.plusDays(2), 9)
        assertTrue(CoachActions.undo(repo, entry))
        assertEquals(before, db.state().bodyWeights())
        assertEquals(73.0, store.profileNow()!!.bodyWeightKg)
        // Over the person's own weigh-in of the same day: that one comes back.
        repo.logBodyWeight(72.0)
        val own = db.state().bodyWeights()
        val second = CoachActions.make(repo, ready(monday.plusDays(2), CoachActions.BODY_WEIGHT, "kg" to 74))
        clock = at(monday.plusDays(3), 9)
        assertTrue(CoachActions.undo(repo, second))
        assertEquals(own, db.state().bodyWeights())
        assertEquals(72.0, store.profileNow()!!.bodyWeightKg)
    }

    /** coach-export-ignore-run-break: after 15 days without running the brief and the export give the runs as they will be. */
    @Test fun afterABreakTheBriefAndTheExportGoByTheShorterRuns() = runBlocking<Unit> {
        store.setRunState(store.runStateNow().copy(lastRunDay = monday.toEpochDay() - 15, estVdot = 40.0, easyMinutes = 40))
        val brief = coachContext(repo, monday)
        val easy = line(brief, "- Лёгкий бег ")
        assertTrue(easy.startsWith("- Лёгкий бег 30 мин"), easy)
        assertTrue("≈ 39,0" in line(brief, "- Форма бега"), line(brief, "- Форма бега"))
        assertTrue("перерыв" in brief && Ru.days(15) in brief, brief.lines().filter { "ерерыв" in it }.joinToString("\n"))
        val report = TrainingReport.build(BackupManager(repo).snapshot(points = false), monday, "test")
        assertTrue("- Лёгкий бег — 30 мин" in report, report.lines().filter { "Лёгкий бег" in it }.joinToString("\n"))
        assertTrue("Форма (VDOT) ≈ 39,0" in report, report.lines().filter { "VDOT" in it }.joinToString("\n"))
    }

    /** easier-pace-stacks: one «easier» of a kind in an answer, its second go included. */
    @Test fun oneEasierOfAKindInAnAnswer() = runBlocking<Unit> {
        store.setRunState(store.runStateNow().copy(intervalStage = 2, tempoStage = 2))
        val from = assertNotNull(RunPlan.estimated(store.runStateNow(), RunGoal(1500, 330)).estVdot)
        val replies = ArrayDeque(
            listOf(
                CoachReply(
                    "Сделаю бег спокойнее.",
                    listOf(
                        call(CoachActions.RUN_PACE, "change" to "easier"), call(CoachActions.RUN_PACE, "change" to "easier", "steps" to 1),
                        call(CoachActions.RUN_STEP, "kind" to "intervals", "change" to "easier"),
                        call(CoachActions.RUN_STEP, "kind" to "intervals", "change" to "easier"),
                        call(CoachActions.RUN_STEP, "kind" to "tempo", "change" to "easier"),
                    ),
                ),
                // The second go sends the pace again, with steps added.
                CoachReply("Темпы спокойнее на шаг.", listOf(call(CoachActions.RUN_PACE, "change" to "easier", "steps" to 2))),
            ),
        )
        val asked = mutableListOf<List<CoachTurn>>()
        val api = object : CoachApi {
            override suspend fun askCoach(turns: List<CoachTurn>, context: String, tools: JsonArray): CoachReply {
                asked += turns
                return replies.removeFirst()
            }
        }
        val msg = CoachTalk.ask(repo, api, "бег слишком тяжёлый") { "сводка" }
        val st = store.runStateNow()
        assertEquals(from - 1, st.estVdot, "one step of the paces, not three")
        assertEquals(1, st.intervalStage)
        assertEquals(1, st.tempoStage, "another ladder is another kind")
        assertEquals(2, asked.size)
        assertTrue("set_run_pace" in asked[1].last().text, asked[1].last().text)
        assertEquals(3, msg.acts.count { it.state == CoachActState.DONE }, msg.acts.joinToString("\n") { "${it.state} ${it.text}" })
    }

    /** ease-run-day-auto-no-goal-check, ease-day-silent: hard runs eased for long, or none left with a running goal, wait for «Принять». */
    @Test fun easingTheHardRunsForLongOrAllOfThemWaitsForAYes() = runBlocking<Unit> {
        // Two weeks of one hard day of two: made at once, as before.
        val short = ready(monday, CoachActions.EASE_DAY, "day" to "чт", "weeks" to 2)
        assertEquals(CoachActions.Mode.AUTO, short.mode)
        assertFalse("Важно" in short.text, short.text)
        // Longer: the person says, and the card says what it costs.
        for (w in listOf(3, 8)) {
            val long = ready(monday, CoachActions.EASE_DAY, "day" to "пн", "weeks" to w)
            assertEquals(CoachActions.Mode.CONFIRM, long.mode, long.text)
            assertTrue("Важно" in long.text, long.text)
        }
        // Thursday eased: a week of Monday too leaves no hard run with the 1500 m goal.
        CoachActions.make(repo, short)
        val none = ready(monday, CoachActions.EASE_DAY, "day" to "пн", "weeks" to 1)
        assertEquals(CoachActions.Mode.CONFIRM, none.mode, none.text)
        assertTrue("Важно" in none.text, none.text)
        // Without a running goal a short ease is made at once.
        store.setProfile(store.profileNow()!!.copy(runGoal = null))
        assertEquals(CoachActions.Mode.AUTO, ready(monday, CoachActions.EASE_DAY, "day" to "пн", "weeks" to 1).mode)
    }

    /** run-rules-conflict: no calf-raise advice (Denis declined calf work twice), and the fitness moves with training until a trial counts. */
    @Test fun theRulesAgreeWithTheEstimateAndGiveNoCalfAdvice() {
        assertFalse("на носки" in COACH_RESEARCH, COACH_RESEARCH)
        assertFalse("Икры" in COACH_RESEARCH, COACH_RESEARCH)
        assertFalse("Контрольный забег раз в несколько недель" in COACH_RULES)
        val ladder = COACH_RULES.lines().first { it.startsWith("- Тяжёлые") }
        assertTrue("После засчитанного контрольного" in ladder, ladder)
    }

    /** cycle-week: the block's week and the next lighter week, as «План» shows them. */
    @Test fun theCoachKnowsTheWeekOfTheBlock() = runBlocking<Unit> {
        val cycle = line(coachContext(repo, monday), "Цикл зала")
        assertTrue("неделя 2 из 4" in cycle, cycle)
        assertTrue("7–13 декабря" in cycle, cycle)
    }

    /** move-session: a session done ahead is said so, and the coach knows the buttons and the rule. */
    @Test fun aSessionDoneAheadCountsForItsDayAndTheCoachKnowsHow() = runBlocking<Unit> {
        // Saturday's «Низ» on Friday (the «Силовая» button).
        val friday = saturday.minusDays(1)
        clock = at(friday, 18)
        val id = repo.startLift("lower_a", friday)
        val item = repo.planOf(db.workouts().get(id)!!)!!.items.first()
        repo.logSet(id, item, item.prescription.weight ?: 20.0, item.prescription.reps, 2)
        repo.finishLift(id, 6)
        clock = at(saturday, 9)
        val legs = line(coachContext(repo, saturday), "- Силовая «Низ")
        assertFalse("ещё не начата" in legs, legs)
        assertTrue("сделано заранее" in legs, legs)
        assertTrue("«Свободный бег»" in COACH_MAP && "«Силовая»" in COACH_MAP, COACH_MAP)
        assertTrue("засчитывается за свой день" in COACH_MAP, COACH_MAP)
    }
}
