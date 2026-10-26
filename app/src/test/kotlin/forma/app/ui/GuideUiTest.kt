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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.CoachScreen
import forma.app.ui.screens.Hints
import forma.app.ui.screens.TodayScreen
import forma.app.ui.screens.TourScreen
import forma.app.ui.screens.coachContext
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A newcomer: the look round after the questionnaire, a hint on a screen until «Понятно», the coach without the cloud. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class GuideUiTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun repo(name: String): TrainingRepository {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        runBlocking { repo.completeOnboarding(Profile(name = "Денис", goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 3, 5), bodyWeightKg = 70.0)) }
        return repo
    }

    private fun show(repo: TrainingRepository, nav: Navigator, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav, LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { content() } }
            }
        }
        settle()
    }

    @Test fun theLookRoundEndsOnTheMainScreen() {
        val repo = repo("ui-tour")
        val nav = Navigator(Screen.Tour(first = true))
        show(repo, nav) { TourScreen(first = true) }
        compose.onRoot().captureRoboImage("build/shots/carbon-tour.png")
        repeat(5) {
            compose.onNodeWithText("Дальше", ignoreCase = true).performScrollTo().performClick()
            settle()
        }
        compose.onNodeWithText("Прогресс и всё остальное").assertExists()
        compose.onNodeWithText("Начать", ignoreCase = true).performScrollTo().performClick()
        settle()
        assertEquals(Screen.Today, nav.current)
        assertTrue(runBlocking { repo.store.settingsNow().tourSeen })
    }

    @Test fun aHintStaysClosed() {
        val repo = repo("ui-hint")
        show(repo, Navigator(Screen.Today)) { TodayScreen() }
        compose.waitUntil(10_000) {
            settle()
            compose.onAllNodesWithText("Здесь — что делать сегодня", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onRoot().captureRoboImage("build/shots/carbon-today-hint.png")
        compose.onNodeWithText("Понятно", ignoreCase = true).performClick()
        settle()
        compose.onNodeWithText("Здесь — что делать сегодня", substring = true).assertDoesNotExist()
        assertTrue(Hints.TODAY in runBlocking { repo.store.settingsNow().hintsSeen })
    }

    /** No cloud in this build: the coach says it needs the cloud and shows the way in; its questions wait. */
    @Test fun theCoachWithoutTheCloud() {
        val repo = repo("ui-coach")
        show(repo, Navigator(Screen.Coach())) { CoachScreen() }
        compose.onNodeWithText("Тренер работает через облако", substring = true).assertExists()
        compose.onNodeWithText("Войти в облако", ignoreCase = true).assertExists()
        compose.onNodeWithText("Как идёт мой прогресс?").performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Тренер думает", substring = true).assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/shots/carbon-coach.png")
    }

    /** What goes with a question: today, the training and how the app works — not the person's name. */
    @Test fun theCoachGetsTheTrainingNotTheName() {
        val repo = repo("ui-coach-context")
        val text = runBlocking { coachContext(repo) }
        assertTrue("Сегодня" in text && "## Как устроено приложение" in text && "Запас" in text, text.take(400))
        assertFalse("Денис" in text)
    }
}
