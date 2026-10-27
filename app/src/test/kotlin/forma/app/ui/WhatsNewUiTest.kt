package forma.app.ui

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.RELEASES
import forma.app.ui.screens.WhatsNewDialog
import forma.app.ui.screens.unseenReleases
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** After an update the app says what is new, once; a first install says nothing. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class WhatsNewUiTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun settle() = repeat(4) {
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun repo(name: String): TrainingRepository {
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
    }

    private val profile = Profile(goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 3, 5), bodyWeightKg = 70.0, lapMeters = 230)

    @Test fun theNewsOfTheLastUpdateReadsPlainly() {
        var closed = false
        compose.setContent { FormaTheme(Looks.byId("carbon")) { WhatsNewDialog(unseenReleases(0)) { closed = true } } }
        settle()
        compose.onNodeWithText("Что нового").assertExists()
        compose.onNodeWithText("Отрезок ждёт тебя").assertExists()
        compose.onNodeWithText("Кнопка громкости теперь только меняет громкость", substring = true).assertExists()
        compose.onNodeWithText("Понятно").captureRoboImage("build/shots/carbon-whats-new.png")
        compose.onNodeWithText("Понятно").performClick()
        assertTrue(closed)
    }

    @Test fun afterAnUpdateItShowsOnceAndAFirstInstallSaysNothing() {
        val updated = repo("ui-news-updated")
        runBlocking { updated.completeOnboarding(profile) }
        compose.setContent { CompositionLocalProvider(LocalRepo provides updated) { AppRoot() } }
        compose.waitUntil(10_000) { settle(); compose.onAllNodesWithText("Что нового").fetchSemanticsNodes().isNotEmpty() }
        // The window's own «Понятно» (the screen's first-visit hint below has one too).
        compose.onNode(hasText("Понятно") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { settle(); runBlocking { updated.store.settingsNow().newsSeen } == RELEASES.last().id }
        settle()
        assertTrue(compose.onAllNodesWithText("Что нового").fetchSemanticsNodes().isEmpty())
    }

    @Test fun aFirstInstallCountsTheNewsAsSeen() {
        val fresh = repo("ui-news-fresh")
        compose.setContent { CompositionLocalProvider(LocalRepo provides fresh) { AppRoot() } }
        compose.waitUntil(10_000) { settle(); runBlocking { fresh.store.settingsNow().newsSeen } == RELEASES.last().id }
        assertTrue(compose.onAllNodesWithText("Что нового").fetchSemanticsNodes().isEmpty())
        assertEquals(RELEASES.last().id, runBlocking { fresh.store.settingsNow().newsSeen })
    }
}
