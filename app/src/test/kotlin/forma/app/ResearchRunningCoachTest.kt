package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.CoachActions
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.sync.CoachCall
import forma.app.ui.screens.COACH_RESEARCH
import forma.app.ui.screens.COACH_RULES
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
import forma.core.plan.SessionKind
import forma.core.plan.WeekPlan
import forma.core.run.RunLog
import forma.core.run.RunPlan
import forma.core.run.RunType
import forma.core.run.SegmentKind
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The research of 24 November as the app and the coach use it (approved by Denis on 25 November): every hard training
 * session, the coach's own hard run too, no longer as a whole than 110 % of the longest run of the last 30 days — a trial
 * asked for is never cut — and the coach told the 110 % rule is linked with fewer overuse injuries, not that it prevents
 * them. Denis's week: hard runs on Monday and Thursday on a 205 m loop, the legs on Saturday.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ResearchRunningCoachTest {
    private lateinit var db: FormaDatabase
    private lateinit var store: SettingsStore
    private lateinit var repo: TrainingRepository
    private val monday = LocalDate.of(2026, 11, 30)
    private val thursday = monday.plusDays(3)
    private var clock = monday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        store = SettingsStore(ctx, "research-running-coach")
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
                // The second week of a block: not a lighter one.
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
        // No trial counted (the estimate: 41), intervals due by the cycle far from the goal, the last run two days ago.
        store.setRunState(store.runStateNow().copy(walkStage = -1, runs = 12, vdot = null, easyMinutes = 30, qualityCount = 3, lastRunDay = monday.toEpochDay() - 2))
    }

    @After fun tearDown() = db.close()

    /** The runs of the last 30 days, the longest of them [longest] minutes. */
    private suspend fun ranUpTo(longest: Int, testSoon: Boolean = false) {
        val d = monday.toEpochDay()
        val recent = listOf(
            RunLog(d - 20, longest), RunLog(d - 16, longest - 3), RunLog(d - 13, longest), RunLog(d - 9, longest - 5),
            RunLog(d - 6, longest), RunLog(d - 2, longest - 1),
        )
        store.setRunState(store.runStateNow().copy(recent = recent, testSoon = testSoon))
    }

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) } + ("why" to JsonPrimitive("проверка"))),
    )

    @Test fun thePlansHardDayIsCutToTheCapOfOneRun() = runBlocking<Unit> {
        ranUpTo(25)
        val w = repo.runFor(SessionKind.RUN_QUALITY, monday)
        assertEquals(RunType.INTERVALS, w.type, w.title)
        assertTrue(w.totalSeconds <= 27 * 60, "${w.title}: ${w.totalSeconds / 60.0} min")
        assertEquals("Интервалы · 4 × 2 круга", w.title)
        assertTrue("27\u00A0мин на всю пробежку" in w.note, w.note)
        // A month of longer runs: the session as the ladder has it.
        ranUpTo(40)
        assertEquals("Интервалы · 5 × 2 круга", repo.runFor(SessionKind.RUN_QUALITY, monday).title)
    }

    @Test fun aTrialAskedForIsNotCut() = runBlocking<Unit> {
        ranUpTo(15, testSoon = true)
        val day = assertNotNull(repo.trialDay(monday))
        val trial = repo.runFor(SessionKind.RUN_QUALITY, day)
        assertEquals(RunType.TEST, trial.type, trial.title)
        assertEquals(12 * 60, trial.segments.first { it.kind == SegmentKind.WARMUP }.seconds)
        assertEquals(8 * 60, trial.segments.first { it.kind == SegmentKind.COOLDOWN }.seconds)
        assertTrue(trial.totalSeconds > 16 * 60, "longer than the cap of 16 minutes, and run as it is")
        // The hard day that is not the trial's: even two reps are over 16 minutes, so it is the easy run.
        val other = repo.runFor(SessionKind.RUN_QUALITY, if (day == monday) thursday else monday, trial = false)
        assertEquals(RunType.EASY, other.type, other.title)
        assertTrue("лёгкий бег вместо тяжёлого" in other.note, other.note)
    }

    @Test fun theCoachsHardRunIsCutToTheCapOfOneRun() = runBlocking<Unit> {
        ranUpTo(25)
        // «5 по 3 минуты, отдых 2»: 10 + 15 + 8 + 5 = 38 minutes — 3 reps, the warm-up 9, the cool-down 5.
        val r = assertIs<CoachActions.Ready>(
            CoachActions.prepare(repo, call(CoachActions.RUN_CUSTOM, "reps" to 5, "work_sec" to 180, "pace" to "interval", "rest_sec" to 120), monday),
        )
        assertEquals(CoachActions.Mode.CONFIRM, r.mode)
        assertTrue("«3 × 3 круга»" in r.text, r.text)
        assertTrue("разминка 9 мин, заминка 5 мин" in r.text, r.text)
        assertTrue("27\u00A0мин на всю пробежку, это 110\u00A0% самой длинной за месяц, поэтому она короче." in r.text, r.text)
        CoachActions.make(repo, r)
        val run = assertNotNull(store.settingsNow().coachRun).workout
        assertEquals(3, run.reps)
        assertTrue(RunPlan.onTrack(run, 205).totalSeconds <= 27 * 60)
        // Even two reps over the cap: refused, with the easy run offered.
        ranUpTo(15)
        val no = assertIs<CoachActions.Refused>(
            CoachActions.prepare(repo, call(CoachActions.RUN_CUSTOM, "reps" to 6, "work_sec" to 120, "pace" to "interval"), monday),
        )
        assertTrue("pace easy" in no.why, no.why)
    }

    @Test fun theCoachIsToldTheRuleAsAnAssociationAndTheOrderOfHardRuns() {
        val rule = COACH_RESEARCH.lines().first { "Frandsen" in it }
        assertTrue("связано с меньшим числом травм" in rule, rule)
        assertTrue("— меньше травм" !in rule, rule)
        assertTrue(COACH_RULES.lines().any { it.startsWith("- Порядок тяжёлых") && "интервалы через раз" in it })
        assertTrue(COACH_RULES.lines().any { it.startsWith("- Тяжёлая пробежка целиком") && "Контрольный и забег не урезаются" in it })
    }
}
