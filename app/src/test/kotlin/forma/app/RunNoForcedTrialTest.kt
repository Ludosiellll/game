package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.CoachActions
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.sync.CoachCall
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
import forma.core.plan.PlannedSession
import forma.core.plan.SessionKind
import forma.core.plan.WeekPlan
import forma.core.run.RunPlan
import forma.core.run.RunType
import forma.core.run.SegmentKind
import forma.core.run.TempoShape
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis, Monday 23 November: «слишком сильная нагрузка в беге … зачем ставить контрольный? почему нельзя просто
 * продолжать бегать чуть в более легком режиме но почти на пределе?» His state that day: no trial counted, a try on
 * 29 October, and a trial due on every fresh hard day (Thursday), paced at the goal plus 8 %; Monday's intervals had no
 * pace at all.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RunNoForcedTrialTest {
    private lateinit var db: FormaDatabase
    private lateinit var store: SettingsStore
    private lateinit var repo: TrainingRepository
    private val monday = LocalDate.of(2026, 11, 23)
    private val thursday = monday.plusDays(3)
    private val clock = monday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        store = SettingsStore(ctx, "run-no-forced-trial")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 73.0, runGoal = RunGoal(1500, 330), lapMeters = 205,
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
        // His week: hard runs on Monday and Thursday, an easy one on Saturday after the legs.
        val p = store.programNow()!!
        val lift = p.templates.first().id
        store.setProgram(
            p.copy(
                createdEpochDay = monday.minusWeeks(6).toEpochDay(),
                week = WeekPlan(
                    listOf(
                        DayPlan(1, listOf(PlannedSession(SessionKind.RUN_QUALITY))),
                        DayPlan(2, listOf(PlannedSession(SessionKind.LIFT, lift))),
                        DayPlan(4, listOf(PlannedSession(SessionKind.RUN_QUALITY), PlannedSession(SessionKind.LIFT, lift))),
                        DayPlan(6, listOf(PlannedSession(SessionKind.LIFT, lift), PlannedSession(SessionKind.RUN_EASY))),
                    ),
                    p.week.notes,
                ),
            ),
        )
        // His running as the cloud had it on 23 November.
        store.setRunState(
            store.runStateNow().copy(
                walkStage = -1, runs = 12, vdot = null, easyMinutes = 30, qualityCount = 3, qualityFails = 1, lastTrialTryDay = 20755,
                lastRunDay = 20773, tempoOwn = TempoShape(4, 150, 60),
            ),
        )
    }

    @After fun tearDown() = db.close()

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) } + ("why" to JsonPrimitive("проверка"))),
    )

    private fun workOf(w: forma.core.run.RunWorkout) = w.segments.filter { it.kind == SegmentKind.WORK }

    @Test fun noTrialHangsOverTheHardDaysAndTheyHaveAPace() = runBlocking<Unit> {
        assertNull(repo.trialDay(monday), "no trial is due just because none was counted")
        val thu = repo.runFor(SessionKind.RUN_QUALITY, thursday)
        assertNotEquals(RunType.TEST, thu.type)
        val mon = repo.runFor(SessionKind.RUN_QUALITY, monday)
        assertEquals(RunType.INTERVALS, mon.type)
        assertNotNull(workOf(mon).first().pace, "intervals are paced from the estimate")
        assertTrue("примерн" in mon.note, mon.note)
        val brief = coachContext(repo, monday)
        assertTrue("Форма бега (VDOT) ≈ 40,0" in brief, brief.lineSequence().filter { "Форма" in it }.joinToString("\n"))
        assertTrue("Контрольный забег: не назначен" in brief)
    }

    @Test fun theCoachPutsATrialOnAndOff() = runBlocking<Unit> {
        val on = assertIs<CoachActions.Ready>(CoachActions.prepare(repo, call(CoachActions.TRIAL, "when" to "next"), monday))
        val entry = CoachActions.make(repo, on)
        assertTrue(store.runStateNow().testSoon)
        val day = assertNotNull(repo.trialDay(monday))
        val trial = repo.runFor(SessionKind.RUN_QUALITY, day)
        assertEquals(RunType.TEST, trial.type)
        // Paced from the estimate (about 6:25 for 1500 m, 52–53 s a lap), not from the goal plus 8 % (49 s).
        val lap = assertNotNull(workOf(trial).first().lapSec)
        assertTrue(lap > 51.0, "lap $lap s")
        assertTrue(CoachActions.undo(repo, entry))
        assertTrue(!store.runStateNow().testSoon)

        store.setRunState(store.runStateNow().copy(testSoon = true))
        val off = assertIs<CoachActions.Ready>(CoachActions.prepare(repo, call(CoachActions.TRIAL, "when" to "skip"), monday))
        assertEquals(CoachActions.Mode.AUTO, off.mode)
        CoachActions.make(repo, off)
        assertNull(repo.trialDay(monday))
        assertEquals(monday.plusWeeks(4).toEpochDay(), store.runStateNow().noTrialUntil)
        assertNotEquals(RunType.TEST, repo.runFor(SessionKind.RUN_QUALITY, thursday).type)
    }

    @Test fun theCoachMakesThePacesEasierAtOnceAndFasterOnlyWithAYes() = runBlocking<Unit> {
        val before = workOf(repo.runFor(SessionKind.RUN_QUALITY, monday)).first().pace!!
        val easier = assertIs<CoachActions.Ready>(CoachActions.prepare(repo, call(CoachActions.RUN_PACE, "change" to "easier"), monday))
        assertEquals(CoachActions.Mode.AUTO, easier.mode)
        assertTrue("спокойнее" in easier.text, easier.text)
        val entry = CoachActions.make(repo, easier)
        assertEquals(39.0, store.runStateNow().estVdot)
        val after = workOf(repo.runFor(SessionKind.RUN_QUALITY, monday)).first().pace!!
        assertTrue(after.fast > before.fast, "slower: ${after.fast} s/km after ${before.fast}")
        assertTrue(CoachActions.undo(repo, entry))
        assertEquals(RunPlan.estimated(store.runStateNow().copy(estVdot = null), RunGoal(1500, 330)).estVdot, store.runStateNow().estVdot)
        val harder = assertIs<CoachActions.Ready>(CoachActions.prepare(repo, call(CoachActions.RUN_PACE, "change" to "harder"), monday))
        assertEquals(CoachActions.Mode.CONFIRM, harder.mode)
    }
}
