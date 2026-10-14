package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Catalog
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LoadKind
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.strength.SlotRole
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

/** Denis in the gym (Tue/Thu/Sat, often off to the stadium after): session time, own rest after a swap, swaps that change kind. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class GymFlowRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private var clock = 1_760_000_000_000L

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "gym-flow")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), bodyWeightKg = 72.0, birthYear = 2008),
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun plan(id: Long) = repo.planOf(db.workouts().get(id)!!)!!
    private suspend fun day() = LocalDate.ofEpochDay(repo.store.programNow()!!.startEpochDay)
    private val minute = 60_000L

    private suspend fun logAll(id: Long, item: LiftItem) {
        repeat(item.prescription.sets) {
            clock += 3 * minute
            repo.logSet(id, item, item.prescription.weight ?: item.prescription.startHint ?: 20.0, item.prescription.reps, 2)
        }
    }

    /** "Завершить" pressed after the run at the stadium: the gym counts to its last set and 5 minutes, not to now. */
    @Test fun aSessionLeftOpenEndsWithItsLastSet() = runBlocking {
        val t = repo.store.programNow()!!.templates.first()
        val start = clock
        val id = repo.startLift(t.id, day())
        val item = plan(id).items.first()
        logAll(id, item)
        val lastSet = clock
        clock += 115 * minute
        val summary = repo.finishLift(id, 7)
        val expected = ((lastSet + 5 * minute - start) / minute).toInt()
        assertEquals(expected, summary.minutes)
        assertEquals(7.0 * expected, summary.load, 1e-6)
        val w = db.workouts().get(id)!!
        assertEquals(expected * 60, w.durationSec)
        assertEquals(lastSet + 5 * minute, w.finishedAt)
    }

    @Test fun aSessionFinishedRightAwayCountsToNow() = runBlocking {
        val t = repo.store.programNow()!!.templates.first()
        val start = clock
        val id = repo.startLift(t.id, day())
        logAll(id, plan(id).items.first())
        clock += 12 * minute
        val summary = repo.finishLift(id, 6)
        assertEquals(((clock - start) / minute).toInt(), summary.minutes)
    }

    @Test fun theEndOfASessionLeftOpen() {
        val start = 0L
        assertEquals(40 * minute, TrainingRepository.liftEnd(start, null, 40 * minute))
        assertEquals(50 * minute, TrainingRepository.liftEnd(start, 30 * minute, 50 * minute), "20 min after the last set: still going")
        assertEquals(35 * minute, TrainingRepository.liftEnd(start, 30 * minute, 600 * minute))
    }

    /** "Свой отдых не меняется": the rest Denis set for a place in the plan stays when another exercise is done there. */
    @Test fun ownRestStaysWithThePlaceAfterASwap() = runBlocking {
        val t = repo.store.programNow()!!.templates.first()
        val slot = t.slots.first()
        repo.tunePlan(slot.exerciseId, slot, slot.sets, slot.repMin to slot.repMax, 200)
        val alt = repo.alternativesFor(slot.exerciseId).first { it.id != slot.exerciseId && !it.custom && it.compound == Catalog[slot.exerciseId].compound }.id

        val id = repo.startLift(t.id, day())
        val before = plan(id).items.first { it.position == 0 }
        assertTrue(before.restOwn)
        assertTrue(repo.swapExercise(id, 0, alt, forever = false))
        val today = plan(id).items.first { it.position == 0 }
        assertEquals(alt, today.exerciseId)
        assertEquals(200, today.slot.restSec)
        assertTrue(today.restOwn, "own rest kept after a swap for today")
        assertEquals(0, today.restShift)

        repo.setSwap(slot.exerciseId, alt)
        val next = repo.liftPlan(t.id, day().plusDays(7)).items.first { it.position == 0 }
        assertEquals(alt, next.exerciseId)
        assertTrue(next.restOwn, "own rest kept after a swap for good")
        assertEquals(200, next.slot.restSec)
    }

    /** Own rest of an exercise added to one day for good is that day's: the same exercise on another day is not "own". */
    @Test fun ownRestOfAnAddedExerciseStaysOnItsDay() = runBlocking {
        val templates = repo.store.programNow()!!.templates
        val a = templates[0]
        val b = templates.first { it.id != a.id }
        val x = Catalog.all.first { e -> e.load == LoadKind.EXTERNAL && !e.custom && !e.archived && a.slots.none { it.exerciseId == e.id } && b.slots.none { it.exerciseId == e.id } }.id
        repo.addToPlan(a.id, x)
        val key = TrainingRepository.addedKey(a.id, x)
        repo.tunePlan(key, repo.addedPlanned(x), 3, 10 to 15, 150)
        assertTrue(repo.liftPlan(a.id, day()).items.first { it.exerciseId == x }.restOwn)

        val id = repo.startLift(b.id, day())
        assertTrue(repo.addToWorkout(id, x, forever = false))
        val other = plan(id).items.first { it.exerciseId == x }
        assertFalse(other.restOwn, "an added-for-a-day key must not make it own elsewhere")
    }

    /** A curl in the place of the bench press is done as a curl: 3 × 10–15, not the bench's sets of 5. */
    @Test fun aSingleJointSwapForAHeavyLiftGetsItsOwnNumbers() = runBlocking {
        val t = repo.store.programNow()!!.templates.first()
        val main = t.slots.withIndex().first { it.value.role == SlotRole.MAIN && Catalog[it.value.exerciseId].compound }
        val iso = Catalog.all.first { !it.compound && it.load == LoadKind.EXTERNAL && !it.custom && !it.archived }.id
        val id = repo.startLift(t.id, day())

        val change = assertNotNull(repo.swapChange(id, main.index, iso))
        assertEquals(main.value.repMin, change.first.repMin)
        assertEquals(10, change.second.repMin)
        assertEquals(15, change.second.repMax)
        assertNull(repo.swapChange(id, main.index, main.value.exerciseId))

        assertTrue(repo.swapExercise(id, main.index, iso, forever = true))
        val today = plan(id).items.first { it.position == main.index }
        assertEquals(10, today.slot.repMin)
        assertEquals(SlotRole.ACCESSORY, today.slot.role)
        assertTrue(today.warmups.isEmpty())
        val next = repo.liftPlan(t.id, day().plusDays(7)).items.first { it.position == main.index }
        assertEquals(iso, next.exerciseId)
        assertEquals(10 to 15, next.slot.repMin to next.slot.repMax)
        assertEquals(TrainingRepository.plannedFor(main.value, repo.store.settingsNow()), repo.addedPlanned(iso))

        // Back to the bench: its own numbers again.
        repo.setSwap(main.value.exerciseId, null)
        val back = repo.liftPlan(t.id, day().plusDays(14)).items.first { it.position == main.index }
        assertEquals(main.value.repMin to main.value.repMax, back.slot.repMin to back.slot.repMax)
    }
}
