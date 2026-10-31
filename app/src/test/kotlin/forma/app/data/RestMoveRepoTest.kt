package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.plan.Templates
import forma.core.strength.SlotRole
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Denis (31.10): «делай для всех и для моего текущего». A plan made before the longer rests takes them once on start;
 * the rest the person set for an exercise stays theirs.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RestMoveRepoTest {
    @Test fun anOldPlanTakesTheNewRestsOnce() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "rest-move")
        store.clear()
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 3, 5),
                bodyWeightKg = 80.0, aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
        // As the plan was before 31.10: the main lifts at 2:30, isolation at 1:15.
        val fresh = store.programNow()!!
        val old = fresh.copy(templates = fresh.templates.map { t ->
            t.copy(slots = t.slots.map { s ->
                when {
                    s.exerciseId == "plank" -> s
                    s.role == SlotRole.MAIN -> s.copy(restSec = 150)
                    s.role == SlotRole.ACCESSORY -> s.copy(restSec = 75)
                    else -> s
                }
            })
        })
        store.setProgram(old)
        repo.updateSettings { it.copy(restVersion = 0) }
        repo.moveRest()
        val moved = store.programNow()!!.templates.flatMap { it.slots }
        assertEquals(setOf(180), moved.filter { it.role == SlotRole.MAIN }.map { it.restSec }.toSet())
        assertEquals(setOf(90), moved.filter { it.role == SlotRole.ACCESSORY && it.exerciseId != "plank" }.map { it.restSec }.toSet())
        assertEquals(Templates.REST_VERSION, store.settingsNow().restVersion)
        // Once: a rest changed after it (by the advisor, by hand into the plan) is not moved again.
        store.setProgram(old)
        repo.moveRest()
        assertEquals(setOf(150), store.programNow()!!.templates.flatMap { it.slots }.filter { it.role == SlotRole.MAIN }.map { it.restSec }.toSet())
    }
}
