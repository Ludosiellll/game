package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import forma.app.ui.screens.ProfileScreen
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
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The profile's week, day by day: shown as the plan has it, changed with a tap. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi")
class OwnWeekUiTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    /** Denis's profile on the profile screen, the week laid out by the app (Tuesday, Thursday, Saturday). */
    private fun open(): TrainingRepository {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "ui-own-week")
        runBlocking { store.clear() }
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        runBlocking {
            repo.completeOnboarding(
                Profile(
                    name = "Денис", aim = Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
                    runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), bodyWeightKg = 80.0, birthYear = 2008, doubleDays = true,
                    runGoal = RunGoal(1500, 330), lapMeters = 230,
                ),
            )
        }
        val nav = Navigator(Screen.Plan).also { it.push(Screen.Profile) }
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav, LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { ProfileScreen() } }
            }
        }
        settle()
        return repo
    }

    @Test fun mondayBecomesARunningDay() {
        val repo = open()
        // As the plan has it now: the gym and a run on Tuesday, nothing on Monday.
        compose.onNodeWithContentDescription("Зал, вторник").performScrollTo().assertIsOn()
        compose.onNodeWithContentDescription("Бег, вторник").assertIsOn()
        compose.onNodeWithContentDescription("Бег, понедельник").assertIsOff()
        compose.onNodeWithContentDescription("Бег, понедельник").performScrollTo().performClick()
        settle()
        compose.onNodeWithContentDescription("Бег, понедельник").assertIsOn()
        compose.onRoot().captureRoboImage("build/shots/carbon-ownweek.png")
        compose.onNodeWithText("Сохранить и пересчитать план").performScrollTo().performClick()
        settle()
        val p = runBlocking { repo.store.profileNow()!! }
        assertEquals(setOf(1, 2, 4, 6), p.runDays)
        assertEquals(setOf(2, 4, 6), p.gymDays)
        assertTrue(runBlocking { repo.store.programNow()!! }.week.on(1).any { it.isRun })
    }

    /** Saved with the days untouched (another weight, another goal): the app keeps laying the week out, it does not change. */
    @Test fun savedWithTheDaysUntouchedTheWeekStays() {
        val repo = open()
        val before = runBlocking { repo.store.programNow()!! }.week
        compose.onNodeWithContentDescription("Бег, вторник").performScrollTo().assertIsOn()
        compose.onNodeWithText("Сохранить и пересчитать план").performScrollTo().performClick()
        settle()
        val p = runBlocking { repo.store.profileNow()!! }
        assertEquals(null, p.gymDays)
        assertEquals(null, p.runDays)
        assertEquals(before, runBlocking { repo.store.programNow()!! }.week)
    }
}
