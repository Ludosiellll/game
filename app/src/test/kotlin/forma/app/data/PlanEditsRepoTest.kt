package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Catalog
import forma.core.model.Equipment
import forma.core.model.Exercise
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LoadKind
import forma.core.model.Muscle
import forma.core.model.Profile
import forma.core.model.RunLevel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The plan the way the user wants it: an exercise added for good, its own rep range and rest. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PlanEditsRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private var clock = 1_760_000_000_000L

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "plan-edits")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 3, 5), bodyWeightKg = 80.0),
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun plan(id: Long) = repo.planOf(db.workouts().get(id)!!)!!
    private suspend fun template() = repo.store.programNow()!!.templates.first()
    private suspend fun day() = LocalDate.ofEpochDay(repo.store.programNow()!!.startEpochDay)
    private fun notIn(vararg ids: String, timed: Boolean = false) =
        Catalog.all.first { e -> e.id !in ids && (e.load == LoadKind.TIMED) == timed && !e.custom && !e.archived }.id

    @Test fun anExerciseAddedForGoodComesInEverySessionOfThatDay() = runBlocking {
        val t = template()
        val extra = notIn(*t.slots.map { it.exerciseId }.toTypedArray())
        val id = repo.startLift(t.id, day())
        assertTrue(repo.addToWorkout(id, extra, forever = true))
        val item = plan(id).items.last()
        assertEquals(extra, item.exerciseId)
        assertTrue(item.addedForGood)
        assertTrue(repo.inPlan(id, item.position))
        assertEquals(listOf(extra), repo.store.settingsNow().added[t.id])

        // The next session of the same day of the plan has it after the plan's own; another day does not.
        val next = repo.liftPlan(t.id, day().plusDays(7))
        assertEquals(extra, next.items.last().exerciseId)
        assertEquals(t.slots.size, next.items.last().position)
        assertTrue(next.items.dropLast(1).none { it.addedForGood })
        repo.store.programNow()!!.templates.drop(1).forEach { other ->
            assertTrue(repo.liftPlan(other.id, day()).items.none { it.addedForGood })
        }
    }

    @Test fun todayOnlyStaysOutOfThePlanEvenAfterAnAdditionForGood() = runBlocking {
        val t = template()
        val a = notIn(*t.slots.map { it.exerciseId }.toTypedArray())
        val b = notIn(a, *t.slots.map { it.exerciseId }.toTypedArray())
        val id = repo.startLift(t.id, day())
        assertTrue(repo.addToWorkout(id, b, forever = false))
        assertTrue(repo.addToWorkout(id, a, forever = true))
        val today = plan(id).items.first { it.exerciseId == b }
        assertFalse(repo.inPlan(id, today.position))
        // Taking the today-only one out "for good" leaves the added one in the plan.
        assertTrue(repo.removeFromWorkout(id, today.position, forever = true))
        assertEquals(listOf(a), repo.store.settingsNow().added[t.id])
        assertTrue(repo.liftPlan(t.id, day().plusDays(7)).items.none { it.exerciseId == b })
    }

    @Test fun anAddedExerciseIsTakenOutOrReplacedForGood() = runBlocking {
        val t = template()
        val a = notIn(*t.slots.map { it.exerciseId }.toTypedArray())
        repo.addToPlan(t.id, a)
        assertFalse(repo.addToPlan(t.id, a), "the same exercise twice is one")
        val id = repo.startLift(t.id, day())
        val item = plan(id).items.last()
        assertTrue(item.addedForGood)
        // Replaced for good: the plan has the new one in its place, the session too.
        val alt = repo.alternativesFor(a).first { it.id != a && !it.custom }.id
        assertTrue(repo.swapExercise(id, item.position, alt, forever = true))
        assertEquals(listOf(alt), repo.store.settingsNow().added[t.id])
        assertNull(repo.store.settingsNow().swaps[a], "a swap of an added exercise does not touch the plan's own")
        val swapped = plan(id).items.first { it.position == item.position }
        assertEquals(alt, swapped.exerciseId)
        assertTrue(swapped.addedForGood)
        // Taken out for good: gone from the plan, the plan's own exercises stay.
        assertTrue(repo.removeFromWorkout(id, item.position, forever = true))
        assertNull(repo.store.settingsNow().added[t.id])
        assertTrue(repo.store.settingsNow().removed.isEmpty())
        assertEquals(t.slots.size, repo.liftPlan(t.id, day().plusDays(7)).items.size)
    }

    @Test fun repRangeAndRestForGood() = runBlocking {
        val t = template()
        val slot = t.slots.first()
        repo.tunePlan(slot.exerciseId, slot, slot.sets, 8 to 12, 150)
        val s = repo.liftPlan(t.id, day()).items.first { it.position == 0 }
        assertEquals(8, s.slot.repMin)
        assertEquals(12, s.slot.repMax)
        assertEquals(150, s.slot.restSec)
        assertTrue(s.prescription.reps in 8..12, "reps ${s.prescription.reps}")
        // The session's rest is the one set: the set button starts it.
        val id = repo.startLift(t.id, day())
        assertEquals(150, plan(id).items.first { it.position == 0 }.slot.restSec)
        // Back to the plan's own: no override left behind.
        repo.tunePlan(slot.exerciseId, slot, slot.sets, slot.repMin to slot.repMax, slot.restSec)
        val st = repo.store.settingsNow()
        assertNull(st.reps[slot.exerciseId])
        assertNull(st.rest[slot.exerciseId])
        assertNull(st.sets[slot.exerciseId])
    }

    @Test fun aNewRepRangeOfAKnownExerciseStartsFromItsEstimate() = runBlocking {
        val t = template()
        val main = t.slots.first { Catalog[it.exerciseId].load == LoadKind.EXTERNAL }
        val id = repo.startLift(t.id, day())
        val item = plan(id).items.first { it.exerciseId == main.exerciseId }
        repeat(item.prescription.sets) { repo.logSet(id, item, 60.0, 8, 2) }
        repo.finishLift(id, 7)
        clock += 2 * 86_400_000L
        repo.tunePlan(main.exerciseId, main, main.sets, 12 to 20, main.restSec)
        val next = repo.liftPlan(t.id, day().plusDays(2)).items.first { it.exerciseId == main.exerciseId }
        val w = assertNotNull(next.prescription.weight, "the weight comes from the estimate, no new calibration")
        assertFalse(next.prescription.calibrate)
        // Lighter for 12–20 than the 60 kg done for 8.
        assertTrue(w < 60.0, "weight $w")
    }

    @Test fun anArchivedOwnExerciseLeavesThePlan() = runBlocking {
        val t = template()
        val mine = repo.addCustom(Exercise("own_cable_thing", "Моё упражнение", Equipment.CABLE, listOf(Muscle.CHEST)))
        repo.addToPlan(t.id, mine.id)
        assertTrue(repo.liftPlan(t.id, day()).items.any { it.exerciseId == mine.id })
        repo.archiveCustom(mine.id)
        assertTrue(repo.liftPlan(t.id, day()).items.none { it.exerciseId == mine.id })
    }

    @Test fun everyPlanExerciseOutButOneAddedStillLeavesASession() = runBlocking {
        val t = template()
        val a = notIn(*t.slots.map { it.exerciseId }.toTypedArray())
        repo.addToPlan(t.id, a)
        t.slots.forEach { repo.setRemoved(it.exerciseId, true) }
        val p = repo.liftPlan(t.id, day())
        assertEquals(listOf(a), p.items.map { it.exerciseId })
        // Nothing at all left: the plan's own come back rather than an empty session.
        repo.removeAdded(t.id, a)
        assertEquals(t.slots.size, repo.liftPlan(t.id, day()).items.size)
    }
}
