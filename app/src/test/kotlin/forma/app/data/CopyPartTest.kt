package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A friend's program with the gym and running: taken whole, the gym alone (no running) or running alone (no gym). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CopyPartTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository

    /** Denis's kind of week: the friend whose program is copied. */
    private val friend = ProgramGenerator.generate(
        Profile(
            aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED, runLevel = RunLevel.BASE,
            trainingDays = setOf(1, 2, 4, 6), gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6), doubleDays = true, bodyWeightKg = 70.0,
        ),
        LocalDate.of(2026, 10, 12),
    )

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "copy-part")
        store.clear()
        repo = TrainingRepository(db, store)
        repo.completeOnboarding(Profile(aim = Aim(LiftFocus.MUSCLE, RunFocus.HEALTH, Emphasis.GYM), experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 3, 5), bodyWeightKg = 60.0))
    }

    @After fun tearDown() = db.close()

    private suspend fun sessions() = repo.store.programNow()!!.week.days.flatMap { it.sessions }

    @Test fun theGymAloneHasNoRunning() = runBlocking {
        assertTrue(repo.copyProgram(friend, "Денис", part = CopyPart.GYM))
        val s = sessions()
        assertTrue(s.isNotEmpty() && s.all { it.kind == SessionKind.LIFT }, "$s")
        val aim = repo.store.programNow()!!.aim
        assertFalse(aim.runs)
        assertEquals(LiftFocus.POWERBUILDING, aim.lift)
        assertFalse(repo.store.profileNow()!!.aim.runs)
        // The friend's workouts as they are.
        assertEquals(friend.templates.map { it.id }.toSet(), repo.store.programNow()!!.templates.map { it.id }.toSet())
        // One's own program comes back whole.
        repo.restoreOwnProgram()
        assertTrue(repo.store.profileNow()!!.aim.runs)
    }

    @Test fun runningAloneHasNoGym() = runBlocking {
        assertTrue(repo.copyProgram(friend, "Денис", part = CopyPart.RUN))
        val s = sessions()
        assertTrue(s.isNotEmpty() && s.all { it.isRun }, "$s")
        val p = repo.store.programNow()!!
        assertFalse(p.aim.lifts)
        assertEquals(RunFocus.ENDURANCE, p.aim.run)
        assertTrue(p.templates.isEmpty())
        assertEquals(setOf(1, 4, 6), repo.store.profileNow()!!.trainingDays)
    }

    @Test fun allOfItHasBoth() = runBlocking {
        assertTrue(repo.copyProgram(friend, "Денис"))
        val s = sessions()
        assertTrue(s.any { it.kind == SessionKind.LIFT } && s.any { it.isRun })
    }
}
