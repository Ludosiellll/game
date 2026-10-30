package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.GymGoalCard
import forma.app.ui.screens.GymGoalScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** «Цель в зале»: typed, the way to it and the plan's changes shown, set with one button; the progress card says it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class GymGoalUiTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() = repeat(4) {
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun repo(): TrainingRepository {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "ui-gym-goal")
        runBlocking { store.clear() }
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        runBlocking {
            repo.completeOnboarding(
                Profile(
                    goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                    bodyWeightKg = 71.0, lapMeters = 230, runGoal = RunGoal(1500, 330), aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
                    doubleDays = true, gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
                ),
            )
        }
        return repo
    }

    @Test fun aGoalIsTypedSeenAndSet() {
        val repo = repo()
        val nav = Navigator(Screen.Progress).also { it.push(Screen.GymGoal) }
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { GymGoalScreen() } }
            }
        }
        compose.waitUntil(10_000) { settle(); compose.onAllNodesWithText("Жим штанги лёжа", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Жим штанги лёжа", substring = true)[0].performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("100")
        compose.waitUntil(10_000) { settle(); compose.onAllNodesWithText("Что поменяется в плане", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("тяжёлый день", substring = true).fetchSemanticsNodes().let { assert(it.isNotEmpty()) }
        compose.onRoot().captureRoboImage("build/shots/carbon-gym-goal.png")
        compose.onNodeWithText("Поставить цель и подстроить план").performScrollTo().performClick()
        // The goal is saved first and the plan's change right after it: both, not just the first.
        compose.waitUntil(10_000) { settle(); runBlocking { repo.store.profileNow()?.liftGoal != null && repo.store.settingsNow().goalPlan != null } }
        val goal = assertNotNull(runBlocking { repo.store.profileNow()?.liftGoal })
        assertEquals("bench", goal.exerciseId)
        assertEquals(100.0, goal.kg, 1e-9)
        assertNotNull(runBlocking { repo.store.settingsNow().goalPlan })
    }

    @Test fun theProgressCardSaysTheGoal() {
        val repo = repo()
        runBlocking { repo.setLiftGoal(forma.core.model.LiftGoal("bench", 100.0), adjust = false) }
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Progress)) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { GymGoalCard() } }
            }
        }
        compose.waitUntil(10_000) { settle(); compose.onAllNodesWithText("Путь покажу после первой тренировки", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Жим штанги лёжа 100 кг").assertExists()
        compose.onNodeWithText("Изменить цель", ignoreCase = true).assertExists()
    }
}
