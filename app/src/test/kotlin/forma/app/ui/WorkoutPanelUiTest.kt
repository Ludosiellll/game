package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.WorkoutScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The set panel at the bottom of the gym screen: always in reach, with the rest inside and "Отменить" after a set. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi")
class WorkoutPanelUiTest {
    @get:Rule val compose = createComposeRule()

    @After fun stopRest() = RestTimer.stop()

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
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
    }

    private val profile = Profile(
        name = "Денис", goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE,
        trainingDays = setOf(1, 3, 5), bodyWeightKg = 80.0, birthYear = 1995,
    )

    private fun start(repo: TrainingRepository): Long = runBlocking {
        repo.completeOnboarding(profile)
        repo.startLift(repo.store.programNow()!!.templates.first().id)
    }

    private fun show(repo: TrainingRepository, id: Long, fontScale: Float = 1f) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(d.density, fontScale),
                LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings(),
            ) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { WorkoutScreen(id) } }
            }
        }
        settle()
    }

    private fun setsOf(repo: TrainingRepository, id: Long) = runBlocking { repo.db.sets().forWorkout(id) }

    @Test fun aSetLoggedByMistakeIsTakenBack() {
        val repo = repo("panel-undo")
        val id = start(repo)
        show(repo, id)
        compose.onNodeWithText("Подход сделан").performClick()
        settle()
        assertEquals(1, setsOf(repo, id).size)
        assertTrue(RestTimer.rest.value != null, "the rest started")
        compose.onNodeWithText("Подход 1 записан").assertIsDisplayed()

        compose.onNodeWithText("Отменить").performClick()
        settle()
        assertEquals(0, setsOf(repo, id).size)
        assertNull(RestTimer.rest.value, "the rest of a set taken back is gone too")
        assertTrue(texts("записан").isEmpty())
        compose.onNodeWithText("Подход 1 из", substring = true, ignoreCase = true).assertIsDisplayed()
    }

    @Test fun takingBackTheLastSetGoesBackToItsExercise() {
        val repo = repo("panel-undo-last")
        val id = start(repo)
        val (first, second) = runBlocking {
            val items = repo.planOf(repo.db.workouts().get(id)!!)!!.items
            items[0] to items[1]
        }
        val planned = first.prescription.sets
        runBlocking { repeat(planned - 1) { repo.logSet(id, first, first.prescription.weight ?: first.prescription.startHint ?: 20.0, first.prescription.reps, 2) } }
        show(repo, id)
        compose.onNodeWithText("Подход $planned из $planned", substring = true, ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("Подход сделан").performClick()
        settle()
        // The exercise is done: the screen moved on to the next one.
        assertTrue(texts(second.name).isNotEmpty())
        compose.onNodeWithText("Подход 1 из", substring = true, ignoreCase = true).assertIsDisplayed()

        compose.onNodeWithText("Отменить").performClick()
        settle()
        assertEquals(planned - 1, setsOf(repo, id).size)
        compose.onNodeWithText("Подход $planned из $planned", substring = true, ignoreCase = true).assertIsDisplayed()
    }

    /** A 360 × 640 dp phone with the system font at 150 %: the set button and "Отменить" stay on screen with the rest running. */
    @Test fun thePanelStaysInReachOnASmallPhone() {
        var scale by mutableStateOf(1f)
        var shown by mutableStateOf<Pair<TrainingRepository, Long>?>(null)
        compose.setContent {
            val d = LocalDensity.current
            val (repo, id) = shown ?: return@setContent
            CompositionLocalProvider(
                LocalDensity provides Density(d.density, scale),
                LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings(),
            ) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { key(id, scale) { WorkoutScreen(id) } } }
            }
        }
        for (s in listOf(1f, 1.3f, 1.5f)) {
            val repo = repo("panel-small-${(s * 100).toInt()}")
            val id = start(repo)
            scale = s
            shown = repo to id
            settle()
            compose.onNodeWithText("Подход сделан").assertIsDisplayed()
            compose.onNodeWithText("Подход сделан").performClick()
            settle()
            compose.onNodeWithText("Отменить").assertIsDisplayed()
            compose.onNodeWithText("Подход сделан").assertIsDisplayed()
            compose.onRoot().captureRoboImage("build/shots/panel-${(s * 100).toInt()}.png")
            RestTimer.stop()
        }
    }
}
