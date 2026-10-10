package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.ui.screens.ProfileDraft
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
import forma.core.plan.SessionKind
import forma.core.run.RunResult
import forma.core.run.RunType
import forma.core.strength.SlotRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the app and data review of 2026-10-10 found, fixed: a test per finding. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ReviewFixesRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private var clock = 1_760_000_000_000L

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "review-fixes")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
    }

    @After fun tearDown() = db.close()

    private val profile = Profile(
        goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE,
        trainingDays = setOf(1, 3, 5, 6), bodyWeightKg = 80.0,
    )

    private suspend fun plan(id: Long) = repo.planOf(db.workouts().get(id)!!)!!

    @Test fun anAddedExerciseNeverPassesForAPlanExercise() = runBlocking {
        repo.completeOnboarding(profile)
        val program = repo.store.programNow()!!
        val t = program.templates.first { it.slots.size >= 3 }
        val id = repo.startLift(t.id, LocalDate.ofEpochDay(program.startEpochDay))
        // The last plan exercise out for today, then another one added: it must not take the free position.
        val last = plan(id).items.last()
        assertTrue(repo.removeFromWorkout(id, last.position, forever = false))
        val extra = Catalog.all.first { e -> t.slots.none { it.exerciseId == e.id } && e.load != LoadKind.TIMED && !e.custom }.id
        assertTrue(repo.addToWorkout(id, extra))
        val added = plan(id).items.last()
        assertTrue(added.position >= t.slots.size, "position ${added.position}")
        assertNull(repo.planSlot(t.id, added.position))
        // Removing it "for good" leaves the plan alone.
        assertTrue(repo.removeFromWorkout(id, added.position, forever = true))
        assertTrue(t.slots[last.position].exerciseId !in repo.store.settingsNow().removed)
    }

    @Test fun oneExerciseTwiceInASessionIsJudgedOnce() = runBlocking {
        repo.completeOnboarding(
            Profile(aim = Aim(LiftFocus.MUSCLE, RunFocus.NONE, Emphasis.GYM), experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE,
                trainingDays = setOf(1, 3, 5), bodyWeightKg = 80.0),
        )
        val program = repo.store.programNow()!!
        val t = program.templates.first { tt -> tt.slots.any { it.role == SlotRole.MAIN && it.repMin == 6 && it.repMax == 10 && Catalog[it.exerciseId].compound } }
        val main = t.slots.first { it.role == SlotRole.MAIN && it.repMin == 6 && it.repMax == 10 && Catalog[it.exerciseId].compound }
        val day = LocalDate.ofEpochDay(program.startEpochDay).plusWeeks(1)
        val s1 = repo.startLift(t.id, day)
        val it1 = plan(s1).items.first { it.exerciseId == main.exerciseId }
        repeat(it1.prescription.sets) { clock += 60_000; repo.logSet(s1, it1, 60.0, 10, 2) }
        clock += 3_600_000
        repo.finishLift(s1, 7)
        // The planned sets well, then the same lift added for three tired back-off sets.
        clock += 2 * 86_400_000L
        val s2 = repo.startLift(t.id, day.plusDays(2))
        val it2 = plan(s2).items.first { it.exerciseId == main.exerciseId }
        assertTrue(repo.addToWorkout(s2, main.exerciseId))
        val extra = plan(s2).items.last()
        val w = it2.prescription.weight ?: 60.0
        repeat(it2.prescription.sets) { clock += 60_000; repo.logSet(s2, it2, w, it2.prescription.reps + 2, 2) }
        repeat(3) { clock += 60_000; repo.logSet(s2, extra, w, 5, 0) }
        clock += 3_600_000
        val summary = repo.finishLift(s2, 8)
        val mine = summary.reports.filter { it.exerciseId == main.exerciseId }
        assertEquals(1, mine.size, mine.toString())
        assertTrue(db.state().slot(main.key)!!.weight!! >= w, "the back-off sets cut the working weight")
    }

    @Test fun aSwapInTheSessionKeepsTheSetsSetForThePlan() = runBlocking {
        repo.completeOnboarding(profile)
        val program = repo.store.programNow()!!
        val t = program.templates.first { it.slots.size >= 3 }
        val day = LocalDate.ofEpochDay(program.startEpochDay)
        val first = t.slots[0]
        repo.setPlanSets(first.exerciseId, 5, first.sets)
        val id = repo.startLift(t.id, day)
        val alt = repo.alternativesFor(first.exerciseId).first().id
        assertTrue(repo.swapExercise(id, 0, alt, forever = false))
        assertEquals(5, plan(id).items.first { it.position == 0 }.slot.sets)
        assertTrue(repo.swapExercise(id, 0, first.exerciseId, forever = false))
        assertEquals(5, plan(id).items.first { it.position == 0 }.slot.sets)
    }

    @Test fun savingTheProfileKeepsTheBlockAndTheRunning() = runBlocking {
        repo.completeOnboarding(profile.copy(experience = Experience.INTERMEDIATE, raceMeters = 5000.0, raceSeconds = 1500.0))
        val start = repo.store.programNow()!!.startEpochDay
        // Three weeks later: a test said VDOT 48.
        clock += 21 * 86_400_000L
        repo.store.setRunState(repo.store.runStateNow().copy(vdot = 48.0, lastTestDay = repo.today().toEpochDay() - 2, runs = 9))
        // Saved unchanged: the block goes on (no restart at week 1, the lighter week stays where it was due).
        repo.updateProfile(repo.store.profileNow()!!)
        assertEquals(start, repo.store.programNow()!!.startEpochDay)
        // Another running level: the test result and the run count stay, the old typed race does not come back.
        repo.updateProfile(repo.store.profileNow()!!.copy(runLevel = RunLevel.TRAINED))
        val st = repo.store.runStateNow()
        assertEquals(48.0, st.vdot)
        assertEquals(9, st.runs)
        assertEquals(start, repo.store.programNow()!!.startEpochDay)
    }

    @Test fun aGoalAFewSecondsOffKeepsItsLadder() = runBlocking {
        val g = RunGoal(1500, 335)
        repo.completeOnboarding(profile.copy(aim = Aim(LiftFocus.MUSCLE, RunFocus.SPEED, Emphasis.BALANCED), runGoal = g, lapMeters = 230))
        repo.store.setRunState(repo.store.runStateNow().copy(goalStage = 4))
        // 5:35 → 5:30: the same goal, the steps stay.
        repo.updateProfile(repo.store.profileNow()!!.copy(runGoal = RunGoal(1500, 330)))
        assertEquals(4, repo.store.runStateNow().goalStage)
        // Another distance: the ladder starts again.
        repo.updateProfile(repo.store.profileNow()!!.copy(runGoal = RunGoal(800, 150)))
        assertEquals(0, repo.store.runStateNow().goalStage)
    }

    @Test fun aRaceTimeTypoGivesNoPaces() = runBlocking {
        // "2530" for 5 km (the colon forgotten): 42 hours; "5:52" for 5 km: world record pace.
        for (typo in listOf("2530", "5:52")) {
            val d = ProfileDraft(profile)
            d.raceKm = 5.0
            d.raceTime = typo
            assertTrue(d.raceTimeBad, typo)
            assertTrue(!d.fieldsOk, typo)
            assertNull(d.toProfile(profile).raceSeconds, typo)
        }
        // And a profile that came with one anyway (an older version, the cloud) gives no fitness from it.
        repo.completeOnboarding(profile.copy(raceMeters = 5000.0, raceSeconds = 151_800.0))
        assertNull(repo.store.runStateNow().vdot)
        // A real one is a test as of today: the next one comes on the usual cadence.
        val d = ProfileDraft(profile)
        d.raceKm = 5.0
        d.raceTime = "25:30"
        assertTrue(d.fieldsOk)
        repo.completeOnboarding(d.toProfile(profile))
        val st = repo.store.runStateNow()
        assertTrue(st.vdot != null && st.lastTestDay == repo.today().toEpochDay(), st.toString())
    }

    @Test fun aGoalTimeTypoBlocksSavingInsteadOfDroppingTheGoal() {
        val d = ProfileDraft(profile.copy(aim = Aim(LiftFocus.MUSCLE, RunFocus.SPEED, Emphasis.BALANCED), runGoal = RunGoal(1500, 330)))
        d.goalTime = "5.35"
        assertTrue(d.goalBad)
        assertTrue(!d.fieldsOk)
    }

    @Test fun anUncountedTrialIsNotRepeatedTheNextHardDay() = runBlocking {
        val g = RunGoal(1500, 335)
        repo.completeOnboarding(profile.copy(aim = Aim(LiftFocus.MUSCLE, RunFocus.SPEED, Emphasis.BALANCED), runGoal = g, lapMeters = 230))
        repo.store.setRunState(repo.store.runStateNow().copy(runs = 3))
        val trial = repo.runFor(SessionKind.RUN_QUALITY)
        assertEquals(RunType.TEST, trial.type)
        // No time: not counted.
        repo.saveRun(trial, RunResult(1.0, 9, trial.totalSeconds), clock, emptyList(), repo.today())
        clock += 7 * 86_400_000L
        assertTrue(repo.runFor(SessionKind.RUN_QUALITY).type != RunType.TEST)
        // Two weeks on it comes back.
        clock += 7 * 86_400_000L
        assertEquals(RunType.TEST, repo.runFor(SessionKind.RUN_QUALITY).type)
    }

    @Test fun aWeightStepForTheGymChangesTheWeights() = runBlocking {
        repo.completeOnboarding(profile.copy(experience = Experience.INTERMEDIATE))
        val program = repo.store.programNow()!!
        val t = program.templates.first { tt -> tt.slots.any { Catalog[it.exerciseId].bar != null } }
        val slot = t.slots.first { Catalog[it.exerciseId].bar != null }
        db.state().upsertSlot(SlotStateEntity(slot.key, slot.exerciseId, 72.5, slot.repMin, 0, 0, 3, clock, false, 0, null, null))
        val day = LocalDate.ofEpochDay(program.startEpochDay).plusWeeks(1)
        assertEquals(72.5, repo.liftPlan(t.id, day).items.first { it.exerciseId == slot.exerciseId }.prescription.weight)
        // Only 2.5 kg plates in this gym: 5 kg steps, and 72.5 becomes 70.
        repo.setStep(slot.exerciseId, 5.0, Catalog[slot.exerciseId].step)
        assertEquals(70.0, repo.liftPlan(t.id, day).items.first { it.exerciseId == slot.exerciseId }.prescription.weight)
        // Back to the exercise's own step: the override is gone.
        repo.setStep(slot.exerciseId, Catalog[slot.exerciseId].step, Catalog[slot.exerciseId].step)
        assertTrue(slot.exerciseId !in repo.store.settingsNow().steps)
    }
}
