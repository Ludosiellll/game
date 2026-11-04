package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.CoachActions
import forma.app.sync.CoachCall
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.SlotStateEntity
import forma.app.data.TrainingRepository
import forma.app.ui.screens.coachContext
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Pattern
import forma.core.model.Profile
import forma.core.model.RunFocus
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
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Denis, 4 November: «пишу тренеру что у меня в махах в тренажере шаг по 5, но начиная с 9 кг ... а он ставит шаг 5, но
 * получается 15 20 25 кг, а нужно 9 14 19 24 29». The coach's step now goes from a first weight.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class StepFromRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var store: SettingsStore
    private lateinit var repo: TrainingRepository
    private val day = LocalDate.of(2026, 11, 4)
    private var clock = day.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val stack = (0..20).map { 9.0 + 5 * it }

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        store = SettingsStore(ctx, "step-from")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock++ })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 71.0, aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
    }

    @After fun tearDown() = db.close()

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) } + ("why" to JsonPrimitive("проверка"))),
    )

    private suspend fun make(c: CoachCall) = CoachActions.make(repo, assertIs<CoachActions.Ready>(CoachActions.prepare(repo, c), c.toString()))

    @Test fun theCoachSetsAStackFromNine() = runBlocking<Unit> {
        // His lateral raises on the machine, at 15 kg the old way (multiples of 5).
        val place = CoachActions.places(repo).first { Catalog[it.exerciseId].pattern == Pattern.LATERAL_RAISE }
        make(call(CoachActions.SWAP, "exercise" to place.exerciseId, "new_exercise" to "machine_lateral"))
        val swapped = CoachActions.places(repo).first { it.exerciseId == "machine_lateral" }
        val item = repo.liftPlan(swapped.templateId, day).items.first { it.exerciseId == "machine_lateral" }
        db.state().upsertSlot(SlotStateEntity(item.slot.key, "machine_lateral", 15.0, item.slot.repMin, 0, 0, 3, 0L, false, 0, null, null))

        val entry = make(call(CoachActions.STEP, "exercise" to "machine_lateral", "step_kg" to 5, "from_kg" to 9))
        assertEquals(9.0, store.settingsNow().stepsFrom["machine_lateral"])
        val now = repo.liftPlan(swapped.templateId, day).items.first { it.exerciseId == "machine_lateral" }
        assertEquals(14.0, now.prescription.weight, "15 the old way lands on the stack, down")
        assertEquals(9.0, now.stepFrom)
        now.warmups.forEach { assertTrue(it.weight in stack, "warm-up ${it.weight}") }
        // The coach sees it.
        assertTrue("шаг 5 кг от 9 кг" in coachContext(repo, day), "the brief says the stack")
        // A weight it is asked to set lands on the stack too: at 24, «поставь 20» is 19.
        db.state().upsertSlot(SlotStateEntity(item.slot.key, "machine_lateral", 24.0, item.slot.repMin, 0, 0, 3, 0L, false, 0, null, null))
        make(call(CoachActions.WEIGHT, "exercise" to "machine_lateral", "kg" to 20))
        assertEquals(19.0, db.state().slot(item.slot.key)?.weight, "the working weight")
        // Today's (lighter after a break here) is on the stack as well.
        assertTrue(repo.liftPlan(swapped.templateId, day).items.first { it.exerciseId == "machine_lateral" }.prescription.weight!! in stack)
        // Undone: back to the plain step.
        assertTrue(CoachActions.undo(repo, entry))
        assertEquals(null, store.settingsNow().stepsFrom["machine_lateral"])
    }

    @Test fun aStepChangedOnThePlanKeepsTheFirstWeight() = runBlocking<Unit> {
        repo.setStep("machine_lateral", 5.0, Catalog["machine_lateral"].step, 9.0)
        repo.setStep("machine_lateral", 4.0, Catalog["machine_lateral"].step)
        assertEquals(9.0, store.settingsNow().stepsFrom["machine_lateral"])
        assertEquals(4.0, store.settingsNow().steps["machine_lateral"])
        repo.setStep("machine_lateral", 4.0, Catalog["machine_lateral"].step, 0.0)
        assertEquals(null, store.settingsNow().stepsFrom["machine_lateral"])
    }
}
