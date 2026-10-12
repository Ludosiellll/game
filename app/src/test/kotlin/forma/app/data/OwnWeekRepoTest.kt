package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.DayPlan
import forma.core.plan.PlannedSession
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The week laid out by the person, and plans made by an older version of the app laid out again. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class OwnWeekRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "own-week")
        store.clear()
        repo = TrainingRepository(db, store)
    }

    @After fun tearDown() = db.close()

    private val denis = Profile(
        name = "Денис", aim = Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
        runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), bodyWeightKg = 80.0, birthYear = 2008, doubleDays = true,
        runGoal = RunGoal(1500, 330), lapMeters = 230,
    )

    @Test fun mondayAsARunningDay() = runBlocking {
        repo.completeOnboarding(denis)
        repo.updateProfile(repo.store.profileNow()!!.copy(gymDays = setOf(2, 4, 6), runDays = setOf(1, 2, 4, 6), trainingDays = setOf(1, 2, 4, 6)))
        val week = repo.store.programNow()!!.week
        assertTrue(week.on(1).any { it.isRun }, week.toString())
        assertTrue(week.on(1).none { it.kind == SessionKind.LIFT })
        assertEquals(setOf(2, 4, 6), week.days.filter { d -> d.sessions.any { it.kind == SessionKind.LIFT } }.map { it.day }.toSet())
    }

    @Test fun aPlanFromAnOlderVersionIsLaidOutAgain() = runBlocking {
        repo.completeOnboarding(denis)
        val now = repo.store.programNow()!!
        // As an older version left it: a long run on Saturday, no planner version, the cycle started weeks ago.
        val old = now.copy(
            planner = 0, startEpochDay = now.startEpochDay - 21,
            week = now.week.copy(days = now.week.days.map { d -> if (d.day == 6) DayPlan(6, d.sessions.map { if (it.isRun) PlannedSession(SessionKind.RUN_LONG) else it }) else d }),
        )
        repo.store.setProgram(old)
        repo.refreshPlan()
        val fresh = repo.store.programNow()!!
        assertEquals(ProgramGenerator.PLANNER, fresh.planner)
        assertTrue(fresh.week.days.flatMap { it.sessions }.none { it.kind == SessionKind.RUN_LONG }, "1500 m: no long run")
        assertEquals(old.startEpochDay, fresh.startEpochDay, "the cycle goes on")
        // A copied program stays as the friend made it.
        val copied = fresh.copy(planner = 0, copiedFrom = "Аня")
        repo.store.setProgram(copied)
        repo.refreshPlan()
        assertEquals(copied, repo.store.programNow())
    }

    /** A friend's program copied: their week is the week now, the own days chosen before go (the plan line, the grid). */
    @Test fun aCopiedProgramTakesTheOwnDaysAway() = runBlocking {
        repo.completeOnboarding(denis)
        repo.updateProfile(denis.copy(gymDays = setOf(2, 4, 6), runDays = setOf(1, 2, 4, 6), trainingDays = setOf(1, 2, 4, 6)))
        val friend = ProgramGenerator.generate(denis.copy(trainingDays = setOf(1, 3, 5), doubleDays = false), java.time.LocalDate.now())
        assertTrue(repo.copyProgram(friend, "Аня"))
        val p = repo.store.profileNow()!!
        assertEquals(null, p.gymDays)
        assertEquals(null, p.runDays)
        assertEquals(setOf(1, 3, 5), p.trainingDays)
    }

    /** A run-only own week switched to the gym only, no gym day ticked: nothing to save (the run row is hidden). */
    @Test fun hiddenDaysDoNotCount() {
        val base = denis.copy(aim = Aim(forma.core.model.LiftFocus.NONE, RunFocus.SPEED, Emphasis.RUN), gymDays = emptySet(), runDays = setOf(2, 4), trainingDays = setOf(2, 4), doubleDays = false)
        val d = forma.app.ui.screens.ProfileDraft(base)
        assertTrue(d.anyDay)
        d.lift = LiftFocus.MUSCLE
        d.run = RunFocus.NONE
        assertTrue(!d.anyDay, "no gym day ticked")
        d.gymDays = setOf(3)
        assertTrue(d.anyDay)
    }
}
