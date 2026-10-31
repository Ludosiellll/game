package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.OnboardingScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Muscle
import forma.core.model.Split
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The program wizard (Denis, 31.10): one question a screen, how the week is divided and two groups worked more, the
 * answers gathered, the program shown with the body lit by its sets, then saved as answered. Each screen photographed
 * in the default look (build/shots/wizard-*).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h1700dp-mdpi")
class WizardUiTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() = repeat(5) {
        Thread.sleep(80)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/shots/wizard-$name.png")

    @Test fun aProgramIsBuiltAsAnswered() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "ui-wizard")
        runBlocking { store.clear() }
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Onboarding), LocalSettings provides Settings(look = Looks.minimalDark.id)) {
                FormaTheme(Looks.minimalDark) { Box(Modifier.fillMaxSize().background(F.c.bg)) { OnboardingScreen() } }
            }
        }
        settle()
        shot("0-intro")
        val next = hasText("Дальше", ignoreCase = true) and hasClickAction()
        compose.onNode(hasText("Собрать программу", ignoreCase = true) and hasClickAction()).performClick()
        settle()
        shot("1-sides")
        compose.onNode(next).performClick(); settle()
        compose.onNodeWithText("Мышцы").performClick(); settle()
        shot("2-gym")
        compose.onNode(next).performClick(); settle()
        shot("3-run")
        compose.onNode(next).performClick(); settle()
        compose.onNodeWithText("Хожу регулярно, меньше двух лет").performClick(); settle()
        shot("4-level")
        compose.onNode(next).performClick(); settle()
        compose.onNodeWithText("Пробегу 20–30 минут").performScrollTo().performClick(); settle()
        shot("5-runlevel")
        compose.onNode(next).performClick(); settle()
        shot("6-days")
        compose.onNode(next).performClick(); settle()
        compose.onNodeWithText("Верх / низ").performClick(); settle()
        shot("7-split")
        compose.onNode(next).performClick(); settle()
        compose.onNode(hasText("Ягодицы") and hasClickAction()).performScrollTo().performClick(); settle()
        compose.onNode(hasText("Спина") and hasClickAction()).performScrollTo().performClick(); settle()
        shot("8-accents")
        compose.onNode(next).performClick(); settle()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("1996")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("80")
        settle()
        shot("9-about")
        compose.onNode(next).performClick()
        // Halfway through the answers coming in.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1400)
        shot("10-analyze")
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { settle(); compose.onAllNodes(hasText("Начать", ignoreCase = true) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        settle()
        shot("11-result")
        compose.onNodeWithText("Нагрузка на мышцы", ignoreCase = true).assertExists()
        compose.onNode(hasText("Начать", ignoreCase = true) and hasClickAction()).performClick()
        settle()
        val p = assertNotNull(runBlocking { store.profileNow() })
        assertEquals(Split.UPPER_LOWER, p.split)
        assertEquals(setOf(Muscle.GLUTES, Muscle.BACK), p.accents)
        assertEquals(LiftFocus.MUSCLE, p.aim.lift)
        assertEquals(Experience.INTERMEDIATE, p.experience)
        assertEquals(1996, p.birthYear)
        val program = assertNotNull(runBlocking { store.programNow() })
        assertEquals(listOf("upper_a", "lower_a", "upper_b", "lower_b").take(program.templates.size), program.templates.map { it.id })
    }
}
