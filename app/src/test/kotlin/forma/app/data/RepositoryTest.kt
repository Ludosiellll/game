package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.RunResult
import forma.core.run.RunType
import forma.core.strength.Outcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RepositoryTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private var clock = 1_760_000_000_000L

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx)
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
    }

    @After fun tearDown() = db.close()

    private val profile = Profile(
        goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE,
        trainingDays = setOf(1, 3, 5, 6), bodyWeightKg = 80.0,
    )

    @Test fun liftSessionAdaptsNextPrescription() = runBlocking {
        repo.completeOnboarding(profile)
        val program = repo.store.programNow()!!
        val tpl = program.templates.first().id
        val day = LocalDate.of(2026, 10, 12)

        // First session: calibration from what was lifted.
        val id = repo.startLift(tpl, day)
        val plan = repo.planOf(db.workouts().get(id)!!)!!
        val squat = plan.items.first { it.exerciseId == "squat" }
        assertTrue(squat.prescription.calibrate)
        repeat(3) { repo.logSet(id, squat, 60.0, 8, null) }
        clock += 50 * 60_000
        val summary = repo.finishLift(id, 7)
        val rep = summary.reports.first { it.exerciseId == "squat" }
        assertIs<Outcome.Calibrated>(rep.outcome)
        assertEquals(50 * 7.0, summary.load)

        // Second session uses the calibrated weight; doing every rep adds a rep.
        val id2 = repo.startLift(tpl, day.plusDays(7))
        val sq2 = repo.planOf(db.workouts().get(id2)!!)!!.items.first { it.exerciseId == "squat" }
        val w = assertNotNull(sq2.prescription.weight)
        repeat(sq2.prescription.sets) { repo.logSet(id2, sq2, w, sq2.prescription.reps, null) }
        val rep2 = repo.finishLift(id2, 7).reports.first { it.exerciseId == "squat" }
        assertIs<Outcome.AddReps>(rep2.outcome)
        val sq3 = repo.liftPlan(tpl, day.plusDays(14)).items.first { it.exerciseId == "squat" }
        assertEquals(sq2.prescription.reps + 1, sq3.prescription.reps)

        val week = repo.weekFlow(day.plusDays(7).with(java.time.DayOfWeek.MONDAY)).first()
        assertTrue(week.any { d -> d.done.isNotEmpty() })
    }

    @Test fun runIsSavedAndMovesTheLadder() = runBlocking {
        repo.completeOnboarding(profile.copy(runLevel = RunLevel.NONE))
        val w = repo.runFor(SessionKind.RUN_EASY)
        assertEquals(RunType.RUN_WALK, w.type)
        val (id, summary) = repo.saveRun(w, RunResult(1.0, 6, w.totalSeconds, distanceM = 3200.0), clock, emptyList())
        assertTrue(id > 0)
        assertTrue(summary.outcomes.isNotEmpty())
        assertEquals(1, repo.store.runStateNow().walkStage)
        val saved = db.runs().get(id)!!
        assertTrue(saved.avgPace!! > 0)
    }
}
