package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.CoachActions
import forma.app.data.ExerciseStatEntity
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.SlotStateEntity
import forma.app.data.TrainingRepository
import forma.app.sync.CoachCall
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Review probe for 4ac6d0c at the app level: the coach's lever set_working_weight on pull-ups. The person asks the coach
 * for help on the bar (a sore shoulder, a band for easier volume); the coach confirms «→ −7,5 кг, цель N повторов со
 * следующей тренировки» and writes the slot — and the next plan shows the body alone again, because the person did five
 * within the last weeks. An accepted change that never reaches the plan.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AssistedReviewRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private val day = LocalDate.of(2026, 10, 29)
    private var clock = day.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "assisted-probe")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock++ })
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

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) } + ("why" to JsonPrimitive("плечо болит"))),
    )

    @Test fun theCoachsHelpOnPullUpsReachesTheNextPlan() = runBlocking<Unit> {
        // Pull-ups with the body alone × 5; the estimate says six to failure with the body (he does five).
        val item = repo.liftPlan("upper_a", day).items.first { it.exerciseId == "pullup" }
        db.state().upsertSlot(SlotStateEntity(item.slot.key, "pullup", 0.0, 5, 0, 0, 3, clock, linear = false, steady = 0))
        db.state().upsertStat(ExerciseStatEntity("pullup", 66.0, 66.0, clock))
        assertEquals(0.0, repo.liftPlan("upper_a", day).items.first { it.exerciseId == "pullup" }.prescription.weight)

        // The coach: help 7.5 kg (a band) from the next session — allowed, confirmed, written.
        val checked = CoachActions.prepare(repo, call(CoachActions.WEIGHT, "exercise" to "pullup", "kg" to -7.5), day)
        val ready = assertIs<CoachActions.Ready>(checked, (checked as? CoachActions.Refused)?.why ?: checked.toString())
        assertTrue("−7,5 кг" in ready.text || "-7,5 кг" in ready.text, ready.text)
        CoachActions.make(repo, ready)
        assertEquals(-7.5, db.state().slot(item.slot.key)!!.weight)

        // The next plan: what the coach promised.
        val next = repo.liftPlan("upper_a", day).items.first { it.exerciseId == "pullup" }.prescription
        assertEquals(-7.5, next.weight, "the coach confirmed «${ready.text}», the plan gives: $next")
    }
}
