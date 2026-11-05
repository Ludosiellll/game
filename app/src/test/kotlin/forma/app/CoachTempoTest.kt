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
import forma.core.plan.SessionKind
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis, Thursday 5 November: the tempo run 3 × 5 min far too hard, run with no pace (no trial counted yet); «все таки
 * нехватает тренеру функций» — the coach could not change the tempo run, and said a setting existed that does not.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CoachTempoTest {
    private lateinit var db: FormaDatabase
    private lateinit var store: SettingsStore
    private lateinit var repo: TrainingRepository
    private val thursday = LocalDate.of(2026, 11, 5)
    private val clock = thursday.atTime(21, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        store = SettingsStore(ctx, "coach-tempo")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 71.0, runGoal = RunGoal(1500, 330), lapMeters = 205,
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
        // Running for weeks, no trial counted: the next hard run is a tempo run.
        store.setRunState(store.runStateNow().copy(walkStage = -1, easyMinutes = 25, runs = 20, qualityCount = 1, vdot = null))
    }

    @After fun tearDown() = db.close()

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) } + ("why" to JsonPrimitive("проверка"))),
    )

    @Test fun theTempoRunBeforeATrialHasAnApproximatePace() = runBlocking<Unit> {
        val w = repo.runFor(SessionKind.RUN_QUALITY, thursday, trial = false)
        assertEquals(RunType.TEMPO, w.type)
        val rep = w.segments.first { it.kind == SegmentKind.WORK }
        assertNotNull(rep.pace, "a pace for the reps")
        // About 61 s a 205 m lap.
        val lap = assertNotNull(rep.lapSec)
        assertTrue(lap in 59.5..62.5, "lap $lap s")
        assertTrue("примерн" in w.note, w.note)
    }

    @Test fun theCoachSetsTheTempoRunAndItCanBeUndone() = runBlocking<Unit> {
        val ready = assertIs<CoachActions.Ready>(
            CoachActions.prepare(repo, call(CoachActions.TEMPO, "reps" to 6, "work_min" to 2.5, "rest_min" to 1), thursday),
        )
        assertEquals(CoachActions.Mode.CONFIRM, ready.mode, "the person accepts it")
        assertTrue("3 × 5 мин, отдых 1 мин → 6 × 2,5 мин, отдых 1 мин" in ready.text, ready.text)
        val entry = CoachActions.make(repo, ready)
        assertEquals(TempoShape(6, 150, 60), store.runStateNow().tempoOwn)
        assertEquals("Темповый бег · 6 × 2,5 мин", repo.runFor(SessionKind.RUN_QUALITY, thursday, road = true, trial = false).title)
        // On his 205 m loop at about 61 s a lap: whole laps from the line, the nearest count.
        assertEquals("Темповый бег · 6 × 2 круга", repo.runFor(SessionKind.RUN_QUALITY, thursday, trial = false).title)
        assertTrue("Темповый сейчас: 6 × 2,5 мин, отдых 1 мин (свой, шаг плана — 3 × 5 мин, отдых 1 мин)" in coachContext(repo, thursday))
        assertTrue(CoachActions.undo(repo, entry))
        assertNull(store.runStateNow().tempoOwn)
    }

    @Test fun theCoachKeepsToTheLimits() = runBlocking<Unit> {
        // 3 × 10 is 30 min at tempo, the plan's 15 + 15 % at most.
        val more = assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call(CoachActions.TEMPO, "reps" to 3, "work_min" to 10, "rest_min" to 1), thursday))
        assertTrue("до 17" in more.why, more.why)
        assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call(CoachActions.TEMPO, "reps" to 2, "work_min" to 2, "rest_min" to 1), thursday))
        assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call(CoachActions.TEMPO, "reps" to 6, "work_min" to 2.5, "rest_min" to 5), thursday))
        assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call(CoachActions.TEMPO, "reset" to true), thursday))
    }
}
