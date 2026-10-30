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
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Someone's program with the gym and running: taken whole, their gym with one's own running, or one's own gym with their
 * running (Denis, 30.10: «его зал + мой бег») — and the week put together checked for load.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CopyPartTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository

    /** Denis's kind of week: the person whose program is copied (gym tue, thu, sat; runs mon, thu, sat). */
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
        repo.completeOnboarding(
            Profile(
                aim = Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE,
                trainingDays = setOf(1, 3, 5, 7), gymDays = setOf(1, 3, 5), runDays = setOf(3, 5, 7), doubleDays = true, bodyWeightKg = 60.0,
            ),
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun program() = repo.store.programNow()!!
    private suspend fun days(run: Boolean) = program().week.days.filter { d -> d.sessions.any { it.isRun == run } }.map { it.day }.toSet()

    @Test fun theirGymWithMyRunning() = runBlocking<Unit> {
        val myRuns = program().week.days.associate { d -> d.day to d.sessions.filter { it.isRun } }.filterValues { it.isNotEmpty() }
        assertTrue(repo.copyProgram(friend, "Денис", part = CopyPart.GYM))
        val p = program()
        // Their workouts on their gym days, one's own runs as they were.
        assertEquals(friend.templates.map { it.id }.toSet(), p.templates.map { it.id }.toSet())
        assertEquals(setOf(2, 4, 6), days(run = false))
        assertEquals(myRuns, p.week.days.associate { d -> d.day to d.sessions.filter { it.isRun } }.filterValues { it.isNotEmpty() })
        assertEquals(LiftFocus.POWERBUILDING, p.aim.lift)
        assertEquals(RunFocus.ENDURANCE, p.aim.run)
        val profile = repo.store.profileNow()!!
        assertEquals(setOf(2, 4, 6), profile.gymDays)
        assertEquals(myRuns.keys, profile.runDays)
        // One's own program comes back whole.
        repo.restoreOwnProgram()
        assertEquals(LiftFocus.MUSCLE, repo.store.profileNow()!!.aim.lift)
        assertEquals(setOf(1, 3, 5), days(run = false))
    }

    @Test fun myGymWithTheirRunning() = runBlocking<Unit> {
        val mine = program().templates.map { it.id }.toSet()
        val slot = program().templates.first().slots.first()
        val like = forma.core.model.Catalog.all.first { it.id != slot.exerciseId && it.primary.firstOrNull() == forma.core.model.Catalog[slot.exerciseId].primary.firstOrNull() }
        repo.setSwap(slot.exerciseId, like.id)
        val swaps = repo.store.settingsNow().swaps
        assertTrue(swaps.isNotEmpty())
        assertTrue(repo.copyProgram(friend, "Денис", part = CopyPart.RUN))
        val p = program()
        assertEquals(mine, p.templates.map { it.id }.toSet())
        assertEquals(setOf(1, 4, 6), days(run = true))
        assertEquals(setOf(1, 3, 5), days(run = false))
        assertEquals(RunFocus.ENDURANCE, p.aim.run)
        assertEquals(LiftFocus.MUSCLE, p.aim.lift)
        // One's own gym keeps its own edits.
        assertEquals(swaps, repo.store.settingsNow().swaps)
        repo.restoreOwnProgram()
        assertEquals(swaps, repo.store.settingsNow().swaps)
    }

    @Test fun allOfItHasBoth() = runBlocking {
        assertTrue(repo.copyProgram(friend, "Денис"))
        val s = program().week.days.flatMap { it.sessions }
        assertTrue(s.any { it.kind == SessionKind.LIFT } && s.any { it.isRun })
        assertEquals(friend.templates.map { it.id }.toSet(), program().templates.map { it.id }.toSet())
    }

    /** The week put together is checked before «Принять», and made lighter when asked. */
    @Test fun theMixedWeekIsCheckedAndCanBeEased() = runBlocking<Unit> {
        val preview = assertNotNull(repo.copyPreview(friend, CopyPart.GYM))
        val load = forma.core.plan.WeekLoad.of(preview.week, preview.templates)
        val eased = assertNotNull(repo.copyPreview(friend, CopyPart.GYM, setOf(Ease.RUN, Ease.LIFT)))
        val after = forma.core.plan.WeekLoad.of(eased.week, eased.templates)
        assertTrue(after.sessions < load.sessions, "${load.sessions} → ${after.sessions}")
        assertTrue(after.hardDays <= load.hardDays)
        // Nothing changes by a preview.
        assertEquals(setOf(1, 3, 5), days(run = false))
        assertTrue(repo.copyProgram(friend, "Денис", part = CopyPart.GYM, ease = setOf(Ease.LIFT)))
        assertEquals(2, days(run = false).size)
    }
}
