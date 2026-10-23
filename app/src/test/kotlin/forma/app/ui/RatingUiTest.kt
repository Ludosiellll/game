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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
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
import forma.core.plan.SessionKind
import forma.core.run.RunResult
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** An easy run rated 7 by mistake: in history the rating is put right to 4, and the plan decides the run again. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi")
class RatingUiTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    @Test fun theRatingIsPutRightInHistory() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "ui-rating")
        runBlocking { store.clear() }
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        val id = runBlocking {
            repo.completeOnboarding(Profile(name = "Денис", goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), bodyWeightKg = 70.0, lapMeters = 230))
            repo.store.setRunState(repo.store.runStateNow().copy(walkStage = -1, runs = 4, easyMinutes = 30))
            val w = repo.runFor(SessionKind.RUN_EASY)
            repo.saveRun(w, RunResult(1.0, 7, w.totalSeconds), System.currentTimeMillis() - w.totalSeconds * 1000L, emptyList()).first
        }
        assertEquals(25, runBlocking { repo.store.runStateNow().easyMinutes })
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { HistoryItemScreen(id) } }
            }
        }
        settle()
        compose.onNodeWithText("Изменить оценку", ignoreCase = true).performScrollTo().performClick()
        settle()
        compose.onNodeWithText("4").performClick()
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(10_000) {
            settle()
            compose.onAllNodesWithText("план заново решил", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(4, runBlocking { repo.db.workouts().get(id)!!.rpe })
        kotlin.test.assertTrue(runBlocking { repo.store.runStateNow().easyMinutes } >= 30)
    }
}
