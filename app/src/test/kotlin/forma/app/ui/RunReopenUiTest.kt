package forma.app.ui

import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.run.RunService
import forma.app.run.RunSession
import forma.app.ui.screens.RunDoneScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.RunTracker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * «Стоп» held by mistake (Denis, 24 November: «случайно не ту кнопку удержал и закончил а продолжить нельзя»): on the
 * finish screen, before the run is saved, «Продолжить пробежку» takes it back to the run screen, paused where it stopped.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class RunReopenUiTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    @After fun clear() = RunSession.clear()

    @Test fun aRunStoppedByMistakeComesBackPaused() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = SettingsStore(ctx, "ui-run-reopen")
        runBlocking { store.clear() }
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        val w = runBlocking {
            repo.completeOnboarding(
                Profile(
                    name = "Денис", goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE,
                    trainingDays = setOf(1, 3, 5), bodyWeightKg = 80.0, birthYear = 1995,
                ),
            )
            repo.runFor(SessionKind.RUN_EASY)
        }
        // Ten minutes run, then «Стоп».
        val t = RunTracker(w)
        t.start(0)
        t.finish(600_000)
        RunSession.tracker = t
        RunSession.gpsEnabled = false
        RunSession.publish(600_000)
        val nav = Navigator(Screen.RunDone(0))
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav, LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { RunDoneScreen(0) } }
            }
        }
        settle()
        compose.onNodeWithText("Продолжить пробежку", ignoreCase = true).performScrollTo().performClick()
        settle()
        assertEquals(RunTracker.State.PAUSED, t.state)
        assertEquals(600, t.activeSec(SystemClock.elapsedRealtime()), "the clock stands where it stopped")
        assertEquals(Screen.RunActive, nav.current)
        // The service brings the run back to the foreground: GPS, voice, the notification.
        assertEquals(RunService.ACTION_REOPEN, shadowOf(ctx).nextStartedService?.action)
    }
}
