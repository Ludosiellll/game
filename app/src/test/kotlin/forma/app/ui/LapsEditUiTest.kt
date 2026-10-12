package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.AppJson
import forma.app.data.FormaDatabase
import forma.app.data.RunSummary
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.HistoryItemScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.run.Lap
import forma.core.run.RunResult
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** A free run on the stadium with a press forgotten: its 2:21 lap is split into two in history. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class LapsEditUiTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    @Test fun aForgottenPressIsSplitInHistory() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "ui-laps-edit")
        runBlocking { store.clear() }
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        val laps = listOf(76.0, 75.0, 77.0, 64.0, 141.0, 80.0, 74.0).mapIndexed { i, s -> Lap(s, number = i + 1) }
        val id = runBlocking {
            repo.completeOnboarding(Profile(name = "Денис", goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), bodyWeightKg = 80.0, lapMeters = 230))
            val w = repo.freeRun(230)
            repo.saveRun(w, RunResult(1.0, 7, laps.sumOf { it.seconds }.toInt(), distanceM = 7 * 230.0, laps = laps), System.currentTimeMillis(), emptyList()).first
        }
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { HistoryItemScreen(id) } }
            }
        }
        settle()
        compose.onNodeWithText("5 · 2:21").performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Это 2 круга — разделить").performClick()
        settle()
        val summary = AppJson.decodeFromString(RunSummary.serializer(), runBlocking { repo.db.workouts().get(id)!!.summaryJson!! })
        assertEquals(listOf(76.0, 75.0, 77.0, 64.0, 70.5, 70.5, 80.0, 74.0), summary.laps.map { it.seconds })
        // The distance was the laps: one more lap.
        assertEquals(8 * 230.0, runBlocking { repo.db.runs().get(id)!!.distanceM!! }, 1e-6)
        compose.onNodeWithText("Круги", ignoreCase = true).performScrollTo()
        settle()
        compose.onRoot().captureRoboImage("build/shots/carbon-lapsedit.png")
    }
}
