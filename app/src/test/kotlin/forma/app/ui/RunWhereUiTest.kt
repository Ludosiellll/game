package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.RunSetupScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** With a stadium in the profile every run is planned on it; this one can be run on the road (auto-pause, km by GPS). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class RunWhereUiTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun texts(substring: String) = compose.onAllNodesWithText(substring, substring = true).fetchSemanticsNodes()

    @Test fun aStadiumRunCanGoOnTheRoad() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "ui-run-where")
        runBlocking { store.clear() }
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        val easy = runBlocking {
            repo.completeOnboarding(
                Profile(
                    name = "Денис", aim = Aim(LiftFocus.MUSCLE, RunFocus.SPEED, Emphasis.BALANCED), experience = Experience.INTERMEDIATE,
                    runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), bodyWeightKg = 80.0, birthYear = 2008, doubleDays = true,
                    runGoal = RunGoal(1500, 335), lapMeters = 230,
                ),
            )
            repo.runFor(SessionKind.RUN_EASY, repo.today())
        }
        assertEquals(230, easy.lapMeters)
        assertNull(runBlocking { repo.runFor(SessionKind.RUN_EASY, repo.today(), road = true) }.lapMeters)
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { RunSetupScreen(easy, SessionKind.RUN_EASY) } }
            }
        }
        settle()
        assertTrue(texts("Стадион, круг 230 м").isNotEmpty())
        compose.onNodeWithText("Улица", ignoreCase = true).performClick()
        settle()
        // On the road: no lap button banner, the targets in minutes a km.
        assertTrue(texts("Стадион, круг 230 м").isEmpty())
        assertTrue(texts("мин/км").isNotEmpty())
    }
}
