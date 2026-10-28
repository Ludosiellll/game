package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.AppJson
import forma.app.data.FormaDatabase
import forma.app.data.LiftSummary
import forma.app.data.SettingsStore
import forma.app.data.SlotStateEntity
import forma.app.data.TrainingRepository
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.strength.HoldReason
import forma.core.strength.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Denis: «после того как я подтягивался 25 на 7 с запасом, приложение все равно рекомендовало 22.5 на 7». The rule is
 * fixed for sessions from now on, but his phone keeps what the older version decided after 27.10: pull-ups 22.5 × 7
 * after 25 × 8 (3 left), 30 × 7, 30 × 6. Once, the latest session of each exercise is evaluated again by the current
 * rules — only upward, and not where the weight was changed since.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class OldWeightsReviewedTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private var clock = 0L
    private val d27 = LocalDate.of(2026, 10, 27)
    private val next = LocalDate.of(2026, 10, 29)

    private fun at(d: LocalDate, hour: Int) { clock = d.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli() }

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "old-weights")
        store.clear()
        at(LocalDate.of(2026, 10, 12), 9)
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(
                name = "Денис", birthYear = 2008, goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE,
                trainingDays = setOf(1, 2, 4, 6), bodyWeightKg = 71.0, lapMeters = 230, runGoal = RunGoal(1500, 330),
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), doubleDays = true,
            ),
        )
        repo.updateSettings { it.copy(swaps = mapOf("row" to "pullup")) }
    }

    @After fun tearDown() = db.close()

    /** His 27.10 pull-ups on a plan of 22.5 × 7, then what the older version stored: 22.5 × 7, a near miss. */
    private suspend fun oldVersionAfter27(): String {
        at(d27, 9)
        val key = repo.liftPlan("upper_a", d27).items.first { it.exerciseId == "pullup" }.slot.key
        // His 22.10 session left it (no break before 27.10).
        db.state().upsertSlot(SlotStateEntity(key, "pullup", 22.5, 7, 0, 0, 3, clock - 5 * 86_400_000L, linear = false))
        at(d27, 11)
        val id = repo.startLift("upper_a", d27)
        val item = repo.planOf(db.workouts().get(id)!!)!!.items.first { it.exerciseId == "pullup" }
        assertEquals(22.5 to 7, item.prescription.weight to item.prescription.reps)
        for ((w, reps, rir) in listOf(Triple(25.0, 8, 3), Triple(30.0, 7, 0), Triple(30.0, 6, 0))) {
            clock += 4 * 60 * 1000
            repo.logSet(id, item, w, reps, rir)
        }
        clock += 4 * 60 * 1000
        repo.finishLift(id, 7)
        // What the older version left: the weight not credited, and the session's report saying so.
        db.state().upsertSlot(db.state().slot(key)!!.copy(weight = 22.5, reps = 7, stalls = 1, steady = 0))
        val w = db.workouts().get(id)!!
        val s = AppJson.decodeFromString(LiftSummary.serializer(), w.summaryJson!!)
        val old = s.copy(reports = s.reports.map { if (it.exerciseId == "pullup") it.copy(outcome = Outcome.Hold(22.5, 7, HoldReason.NEAR_MISS)) else it })
        db.workouts().update(w.copy(summaryJson = AppJson.encodeToString(LiftSummary.serializer(), old)))
        return key
    }

    @Test fun weightsAnOlderVersionLeftAreReviewed() = runBlocking<Unit> {
        val key = oldVersionAfter27()
        assertEquals(22.5, repo.liftPlan("upper_a", next).items.first { it.exerciseId == "pullup" }.prescription.weight)
        at(LocalDate.of(2026, 10, 28), 20)
        repo.reviewWeights()
        val p = repo.liftPlan("upper_a", next).items.first { it.exerciseId == "pullup" }.prescription
        assertTrue(p.weight!! >= 25.0, "after 25 × 8 (3 left), 30 × 7, 30 × 6: ${p.weight} × ${p.reps}; state ${db.state().slot(key)}")
        // Once: a later weight is not reviewed again.
        db.state().upsertSlot(db.state().slot(key)!!.copy(weight = 22.5))
        repo.reviewWeights()
        assertEquals(22.5, db.state().slot(key)!!.weight)
    }

    @Test fun aWeightChangedSinceStays() = runBlocking<Unit> {
        val key = oldVersionAfter27()
        // The coach set 20 kg since (a sore shoulder): not the session's outcome, left alone.
        db.state().upsertSlot(db.state().slot(key)!!.copy(weight = 20.0))
        repo.reviewWeights()
        assertEquals(20.0, db.state().slot(key)!!.weight)
    }
}
