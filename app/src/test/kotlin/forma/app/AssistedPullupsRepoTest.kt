package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.ExerciseStatEntity
import forma.app.data.FormaDatabase
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

/**
 * A friend's pull-ups (55 kg) on his phone: the first time 5, 5, 3 with the body alone; an older version then gave
 * «помощь 7.5 кг» and kept it while he did 5 kg × 5, 5 kg × 5, 0 × 6, then 5 kg × 4, 5 kg × 5, 0 × 4, then 0 × 5 (4 left),
 * 0 × 5 (2 left), 0 × 5. His phone still holds 7.5 kg of help: the next plan must be his own body weight.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AssistedPullupsRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private var clock = 0L

    private fun at(d: LocalDate, hour: Int) { clock = d.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli() }

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "assisted-pullups")
        store.clear()
        at(LocalDate.of(2026, 10, 12), 9)
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 55.0, heightCm = 180, birthYear = 2007, lapMeters = 230, runGoal = RunGoal(1500, 330),
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), doubleDays = true,
            ),
        )
        repo.updateSettings { it.copy(swaps = mapOf("row" to "pullup")) }
    }

    @After fun tearDown() = db.close()

    private val sessions = listOf(
        LocalDate.of(2026, 10, 13) to listOf(Triple(0.0, 5, 1), Triple(0.0, 5, 0), Triple(0.0, 3, 0)),
        LocalDate.of(2026, 10, 15) to listOf(Triple(5.0, 5, 0), Triple(5.0, 5, 0), Triple(0.0, 6, 0)),
        LocalDate.of(2026, 10, 22) to listOf(Triple(5.0, 4, 0), Triple(5.0, 5, 1), Triple(0.0, 4, 0)),
        LocalDate.of(2026, 10, 27) to listOf(Triple(0.0, 5, 4), Triple(0.0, 5, 2), Triple(0.0, 5, 0)),
    )

    @Test fun helpLeftByAnOlderVersionGives() = runBlocking<Unit> {
        for ((day, sets) in sessions) {
            at(day, 12)
            val id = repo.startLift("upper_a", day)
            val item = repo.planOf(db.workouts().get(id)!!)!!.items.first { it.exerciseId == "pullup" }
            for ((w, reps, rir) in sets) {
                clock += 3 * 60 * 1000
                repo.logSet(id, item, w, reps, rir)
            }
            clock += 3 * 60 * 1000
            repo.finishLift(id, 7)
        }
        // What his phone holds (synced from the older version): 7.5 kg of help, the estimate lagging behind.
        val key = repo.liftPlan("upper_a", LocalDate.of(2026, 10, 29)).items.first { it.exerciseId == "pullup" }.slot.key
        db.state().upsertSlot(SlotStateEntity(key, "pullup", -7.5, 5, 0, 0, 4, clock, linear = true, steady = 1))
        db.state().upsertStat(ExerciseStatEntity("pullup", 64.14, 72.18, clock))
        val next = repo.liftPlan("upper_a", LocalDate.of(2026, 10, 29)).items.first { it.exerciseId == "pullup" }.prescription
        assertEquals(0.0 to 5, next.weight to next.reps, "his own body weight × 5, not help: $next")
    }
}
