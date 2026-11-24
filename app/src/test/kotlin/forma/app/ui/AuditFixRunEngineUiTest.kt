package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.run.RunSession
import forma.app.ui.screens.RunActiveScreen
import forma.app.ui.screens.TourScreen
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
import forma.core.run.Fix
import forma.core.run.RunTracker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** The run engine's findings of the audit of 24 November (group D) seen on the screens, each fixed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class AuditFixRunEngineUiTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    @After fun tearDown() {
        RunSession.clear()
    }

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun texts(substring: String): List<String> =
        compose.onAllNodesWithText(substring, substring = true).fetchSemanticsNodes()
            .map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "" }

    private val denis = Profile(
        name = "Денис", aim = Aim(LiftFocus.MUSCLE, RunFocus.SPEED, Emphasis.BALANCED), experience = Experience.INTERMEDIATE,
        runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), bodyWeightKg = 80.0, birthYear = 2008, doubleDays = true,
        runGoal = RunGoal(1500, 330), lapMeters = 205,
    )

    private fun repo(name: String): TrainingRepository {
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store).also { runBlocking { it.completeOnboarding(denis) } }
    }

    private fun show(repo: TrainingRepository, nav: Navigator = Navigator(Screen.Today), content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav, LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { content() } }
            }
        }
        settle()
    }

    /** run-07 */
    @Test fun theGpsLineSaysTheSignalIsGoneWhenTheFixesStop() {
        val repo = repo("ui-audit-gps")
        val road = runBlocking { repo.runFor(SessionKind.RUN_EASY, repo.today(), road = true) }
        val t = RunTracker(road)
        t.start(0)
        t.onFix(Fix(10_000, 53.9, 27.56, 5f, 3f), 10_000)
        RunSession.tracker = t
        RunSession.gpsEnabled = true
        RunSession.publish(12_000)
        show(repo) { RunActiveScreen() }
        assertTrue(texts("точность 5 м").isNotEmpty(), texts("GPS").toString())
        // Under a bridge: no fix for 20 s — the last accuracy is not shown in green as if all were well.
        RunSession.publish(30_000)
        settle()
        assertTrue(texts("точность 5 м").isEmpty(), texts("GPS").toString())
        assertTrue(texts("GPS · нет сигнала").isNotEmpty(), texts("GPS").toString())
    }

    /** gaps:tight-loop-one-way */
    @Test fun theStadiumHowToSaysEasyRunningCanGoTheOtherWayRound() {
        val repo = repo("ui-audit-tour")
        show(repo, Navigator(Screen.Tour(first = false))) { TourScreen(first = false) }
        // «Сегодня», «В зале», then «Бег».
        repeat(2) {
            compose.onNodeWithText("Дальше", ignoreCase = true).performScrollTo().performClick()
            settle()
        }
        compose.onNodeWithText("Бег").assertExists()
        assertTrue(texts("можно бежать по часовой, если дорожка свободна").isNotEmpty())
    }
}
