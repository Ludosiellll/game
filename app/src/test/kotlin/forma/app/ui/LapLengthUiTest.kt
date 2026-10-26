package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.LapLengthCard
import forma.app.ui.theme.F
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

/** After a stadium run: the loop GPS measured against the lap set, put right with a tap. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class LapLengthUiTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() = repeat(4) {
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    @Test fun theLoopAsRunIsSetWithATap() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "ui-lap-length")
        runBlocking { store.clear() }
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        runBlocking {
            repo.completeOnboarding(Profile(goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 3, 5), bodyWeightKg = 70.0, lapMeters = 230))
        }
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo) {
                FormaTheme(Looks.byId("carbon")) {
                    Column(Modifier.fillMaxSize().background(F.c.bg).padding(16.dp)) {
                        LapLengthCard(230, 205)
                        // Close to the lap set: nothing to say.
                        LapLengthCard(230, 228)
                    }
                }
            }
        }
        settle()
        compose.onNodeWithText("По GPS твой круг ≈ 205\u00A0м", substring = true).assertExists()
        assertEquals(1, compose.onAllNodesWithText("Длина круга", ignoreCase = true).fetchSemanticsNodes().size)
        compose.onRoot().captureRoboImage("build/shots/carbon-lap-length.png")
        compose.onNodeWithText("Поставить 205\u00A0м", ignoreCase = true).performClick()
        compose.waitUntil(10_000) { settle(); runBlocking { repo.store.profileNow()?.lapMeters } == 205 }
        compose.waitUntil(10_000) { settle(); compose.onAllNodesWithText("Круг — 205\u00A0м", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
