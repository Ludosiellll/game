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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.run.RunSession
import forma.app.ui.screens.RunActiveScreen
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
import forma.core.run.RunPlan
import forma.core.run.RunState
import forma.core.run.RunTracker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** Denis's trial warm-up on the run screen: which stride, how long — and nothing about strides outside them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class WarmupStridesUiTest {
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

    private fun repo(name: String): TrainingRepository {
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store).also {
            runBlocking {
                it.completeOnboarding(
                    Profile(
                        name = "Денис", aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
                        runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6), bodyWeightKg = 71.0, birthYear = 2008, doubleDays = true,
                        runGoal = RunGoal(1500, 330), lapMeters = 205,
                    ),
                )
            }
        }
    }

    private fun at(sec: Int) {
        val t = RunTracker(RunPlan.goalTest(RunState(walkStage = -1), RunGoal(1500, 330), 205))
        t.start(0)
        RunSession.tracker = t
        RunSession.gpsEnabled = false
        RunSession.publish(sec * 1000L)
        val repo = repo("ui-strides-$sec")
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { RunActiveScreen() } }
            }
        }
        settle()
    }

    @Test fun inAStrideTheScreenSaysWhichAndHowLong() {
        at(500)
        assertTrue(texts("Ускорение 1 из 4 · ещё 10 с").isNotEmpty(), "${texts("Ускор")}")
    }

    @Test fun betweenStridesItSaysWhenTheNextComes() {
        at(520)
        assertTrue(texts("Трусца · ускорение 2 из 4 через 20 с").isNotEmpty(), "${texts("Ускор")} ${texts("ускор")}")
    }

    @Test fun beforeTheStridesNothingAboutThemButSoon() {
        at(300)
        assertTrue(texts("ускорен").isEmpty() && texts("Ускорен").isEmpty())
    }
}
