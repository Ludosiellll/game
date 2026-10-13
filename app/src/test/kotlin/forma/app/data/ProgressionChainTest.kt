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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The chain in the gym, the way Denis asked: 82 kg × 10 on the bench the first time — the app works out that he can lift
 * more for the plan's reps and says how much; after each session the reps climb the range, then the weight goes up.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ProgressionChainTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private var clock = LocalDate.of(2026, 10, 13).atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "progression-chain")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(
                name = "Денис", aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
                runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6), bodyWeightKg = 80.0, birthYear = 2008, doubleDays = true,
                runGoal = RunGoal(1500, 330), lapMeters = 230, gymDays = setOf(2, 4, 6), runDays = setOf(1, 2, 4, 6),
            ),
        )
    }

    @After fun tearDown() = db.close()

    private fun today() = repo.today()

    @Test fun moreDoneMoreNextTime() = runBlocking {
        val seen = mutableListOf<Pair<String, forma.core.strength.Prescription>>()
        val t = repo.store.programNow()!!.templates.first { tt -> tt.slots.any { it.exerciseId == "bench" } }
        repeat(5) { session ->
            val id = repo.startLift(t.id, today())
            val plan = repo.planOf(db.workouts().get(id)!!)!!
            for (item in plan.items.filter { it.exerciseId == "bench" || it.exerciseId == "lat_pulldown" }) {
                val p = item.prescription
                seen += item.exerciseId to p
            }
            for (item in plan.items) {
                val p = item.prescription
                val w = p.weight ?: if (item.exerciseId == "bench") 82.0 else (p.startHint ?: 40.0)
                repeat(p.sets) { i ->
                    // Every set as prescribed; the last "+" set two more; the first time 82 kg × 10.
                    val reps = when {
                        p.calibrate && item.exerciseId == "bench" -> 10
                        p.calibrate -> 10
                        p.top && i == p.sets - 1 -> p.reps + 2
                        else -> p.reps
                    }
                    repo.logSet(id, item, w, reps, 2)
                }
            }
            repo.finishLift(id, 7)
            clock += 7 * 86_400_000L
        }
        val bench = seen.filter { it.first == "bench" }.map { it.second }
        val lat = seen.filter { it.first == "lat_pulldown" }.map { it.second }
        // First time: find the weight. 82 × 10 with reps to spare is more than 82 for the plan's 5.
        kotlin.test.assertTrue(bench[0].calibrate)
        kotlin.test.assertTrue(bench[1].weight!! > 82.0, "bench after 82 × 10: ${bench[1]}")
        // Done with reps to spare: the reps go up at the same weight, then the weight.
        kotlin.test.assertTrue(bench[2].reps > bench[1].reps && bench[2].weight == bench[1].weight, "${bench[1]} -> ${bench[2]}")
        kotlin.test.assertEquals(forma.core.strength.Phase.DELOAD, bench[3].phase)
        kotlin.test.assertTrue(lat[4].weight!! > lat[2].weight!!, "lat pulldown ${lat.map { it.weight to it.reps }}")
    }
}
