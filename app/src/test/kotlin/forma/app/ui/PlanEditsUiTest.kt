package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.LibraryScreen
import forma.app.ui.screens.PlanScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Catalog
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Changing the plan from its screen and from a session: rep range, rest, an exercise added for good. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class PlanEditsUiTest {
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
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store).also {
            runBlocking {
                it.completeOnboarding(
                    Profile(name = "Денис", goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 3, 5), bodyWeightKg = 80.0),
                )
            }
        }
    }

    private fun show(repo: TrainingRepository, nav: Navigator, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav, LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { content() } }
            }
        }
        settle()
    }

    @Test fun repRangeAndRestAreSetOnThePlan() {
        val repo = repo("ui-plan-range")
        val t = runBlocking { repo.store.programNow()!!.templates.first() }
        val slot = t.slots.first()
        show(repo, Navigator(Screen.Plan)) { PlanScreen() }
        compose.onAllNodesWithText(Catalog[slot.exerciseId].name)[0].performScrollTo().performClick()
        settle()
        val range = if (slot.repMin == 10 && slot.repMax == 15) "12–20" else "10–15"
        compose.onNodeWithText(range).performScrollTo().performClick()
        compose.onNodeWithText("1:30").performScrollTo().performClick()
        settle()
        compose.onAllNodesWithText("Сохранить")[0].performClick()
        settle()
        val s = runBlocking { repo.store.settingsNow() }
        assertEquals(range.split("–").map { it.toInt() }, s.reps[slot.exerciseId])
        assertEquals(if (slot.restSec == 90) null else 90, s.rest[slot.exerciseId])
        // The row shows the new numbers and what the plan had.
        assertTrue(compose.onAllNodesWithText("в плане было", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun theAddButtonOfADayOpensTheLibraryForThatDay() {
        val repo = repo("ui-plan-add")
        val t = runBlocking { repo.store.programNow()!!.templates.first() }
        val nav = Navigator(Screen.Plan)
        show(repo, nav) { PlanScreen() }
        compose.onAllNodesWithText("Добавить упражнение", ignoreCase = true)[0].performScrollTo().performClick()
        settle()
        assertEquals(Screen.Library(LibraryMode.AddToPlan(t.id)), nav.current)
    }

    @Test fun anExerciseAddedInASessionCanGoIntoThePlan() {
        val repo = repo("ui-plan-session")
        val t = runBlocking { repo.store.programNow()!!.templates.first() }
        val id = runBlocking { repo.startLift(t.id) }
        val nav = Navigator(Screen.Today)
        show(repo, nav) { LibraryScreen(LibraryMode.AddToWorkout(id)) }
        val pick = Catalog["db_curl"]
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(pick.name))
        compose.onAllNodesWithText(pick.name)[0].performClick()
        settle()
        compose.onNodeWithText("В план насовсем").performClick()
        settle()
        assertEquals(listOf(pick.id), runBlocking { repo.store.settingsNow().added[t.id] })
        val item = runBlocking { repo.planOf(repo.db.workouts().get(id)!!)!!.items.last() }
        assertEquals(pick.id, item.exerciseId)
        assertTrue(item.addedForGood)
    }
}
