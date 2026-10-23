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
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.Status
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.app.ui.screens.TodayScreen
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
import java.time.LocalDate
import java.time.ZoneId

/** Saturday's «Низ» and run done on Friday: on Saturday the main screen says so, and the gym can still be done. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi")
class DoneAheadUiTest {
    @get:Rule val compose = createComposeRule()

    private val monday = LocalDate.of(2026, 10, 19)
    private var clock = monday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun ms(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    @Test fun saturdayDoneOnFridaySaysSo() {
        val repo = done("ui-done-ahead")
        clock = ms(monday.plusDays(5), 9)
        show(repo)
        compose.waitUntil(10_000) {
            settle()
            compose.onAllNodesWithText("Сделано заранее, в пятницу", substring = true).fetchSemanticsNodes().size >= 2
        }
        compose.onAllNodesWithText("Всё равно сделать сегодня", ignoreCase = true).fetchSemanticsNodes().isNotEmpty().let { kotlin.test.assertTrue(it) }
        compose.onNodeWithText("Сделано заранее, в пятницу: Свободный бег").assertExists()
        // Nothing left to do today: no "how are you" before sessions that are done.
        compose.onNodeWithText("Как ты сегодня?").assertDoesNotExist()
    }

    /** On Friday itself: the two said to be Saturday's, and what comes next is Monday's, not Saturday's done ones. */
    @Test fun onFridayNextIsMonday() {
        val repo = done("ui-done-ahead-fri")
        clock = ms(monday.plusDays(4), 19)
        show(repo)
        compose.waitUntil(10_000) {
            settle()
            compose.onAllNodesWithText("Заранее — за сб", substring = true, ignoreCase = true).fetchSemanticsNodes().size >= 2
        }
        compose.onNodeWithText("Понедельник, 26 октября").performScrollTo().assertExists()
        compose.onNodeWithText("Завтра, суббота").assertDoesNotExist()
    }

    private fun show(repo: TrainingRepository) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { TodayScreen() } }
            }
        }
    }

    private fun done(name: String): TrainingRepository {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now = { clock })
        val friday = monday.plusDays(4)
        runBlocking {
            repo.completeOnboarding(
                Profile(
                    name = "Денис", aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
                    runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6), bodyWeightKg = 70.0, birthYear = 2008, doubleDays = true,
                    runGoal = RunGoal(1500, 330), lapMeters = 230, gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
                ),
            )
            repo.store.setRunState(repo.store.runStateNow().copy(runs = 4, walkStage = -1, lastRunDay = friday.toEpochDay()))
            repo.db.workouts().insert(WorkoutEntity(epochDay = friday.toEpochDay(), kind = Kind.LIFT, templateId = "lower_a", title = "Низ", startedAt = ms(friday, 14), finishedAt = ms(friday, 15), status = Status.DONE, rpe = 5, durationSec = 2820))
            repo.db.workouts().insert(WorkoutEntity(epochDay = friday.toEpochDay(), kind = Kind.RUN, templateId = "FREE", title = "Свободный бег", startedAt = ms(friday, 16), finishedAt = ms(friday, 17), status = Status.DONE, rpe = 4, durationSec = 1015))
        }
        return repo
    }
}
