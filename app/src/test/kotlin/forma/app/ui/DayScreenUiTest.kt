package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.DayScreen
import forma.app.ui.screens.HistoryItemScreen
import forma.app.ui.screens.PlanScreen
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
import forma.core.plan.SessionKind
import forma.core.run.Lap
import forma.core.run.RunResult
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

/** A day of the plan in full, one tap from the week; and a saved run talked through. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi")
class DayScreenUiTest {
    @get:Rule val compose = createComposeRule()

    private val monday = LocalDate.of(2026, 10, 12)
    private val clock = monday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    /** Denis: the gym Tue/Thu/Sat, running Mon/Tue/Thu/Sat, a trial due. */
    private fun repo(): TrainingRepository = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "ui-day")
        store.clear()
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now = { clock })
        repo.completeOnboarding(
            Profile(
                name = "Денис", aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
                runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6), bodyWeightKg = 80.0, birthYear = 2008, doubleDays = true,
                runGoal = RunGoal(1500, 330), lapMeters = 230, gymDays = setOf(2, 4, 6), runDays = setOf(1, 2, 4, 6),
            ),
        )
        repo.store.setRunState(repo.store.runStateNow().copy(runs = 2, walkStage = -1))
        repo
    }

    @Test fun thursdayOpensFromThePlanWithWhyAndHow() {
        val repo = repo()
        val nav = Navigator(Screen.Plan)
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav, LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) {
                    Box(Modifier.fillMaxSize().background(F.c.bg)) {
                        when (val s = nav.current) {
                            is Screen.Day -> DayScreen(LocalDate.ofEpochDay(s.epochDay))
                            else -> PlanScreen()
                        }
                    }
                }
            }
        }
        settle()
        compose.onNodeWithText("Чт").performScrollTo().performClick()
        settle()
        assertEquals(Screen.Day(monday.plusDays(3).toEpochDay()), nav.current)
        compose.onNodeWithText("Четверг").assertExists()
        // The hard run of the day: the trial is due, and Monday is two days after Saturday's legs — so it falls on Thursday.
        compose.onAllNodesWithText("Контрольный", substring = true)[0].assertExists()
        // The run first (the hard run before the gym), then the gym: each with its own why.
        compose.onAllNodesWithText("Зачем")[0].performScrollTo()
        compose.onNodeWithText("Что даёт телу").performScrollTo()
        compose.onAllNodesWithText("Как дальше")[0].performScrollTo()
        compose.onRoot().captureRoboImage("build/shots/carbon-day.png")
        compose.onNodeWithText("Почему в этот день", ignoreCase = true).performScrollTo()
        compose.onNodeWithText("Тяжёлый бег — в день верха", substring = true).performScrollTo()
        compose.onRoot().captureRoboImage("build/shots/carbon-day-why.png")
    }

    /** The days of the main screen's week open the same way. */
    @Test fun aDayOnTheMainScreenOpensToo() {
        val repo = repo()
        val nav = Navigator(Screen.Today)
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav, LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) {
                    Box(Modifier.fillMaxSize().background(F.c.bg)) {
                        when (val s = nav.current) {
                            is Screen.Day -> DayScreen(LocalDate.ofEpochDay(s.epochDay))
                            else -> TodayScreen()
                        }
                    }
                }
            }
        }
        settle()
        // Thursday the 15th in the strip of the week.
        compose.onNodeWithText("15").performClick()
        settle()
        assertEquals(Screen.Day(monday.plusDays(3).toEpochDay()), nav.current)
        compose.onNodeWithText("Четверг").assertExists()
        compose.onNodeWithText("15 октября").assertExists()
    }

    /** Monday's hard run replaced by a free run: the day says so, as the main screen does, and the run's story is a tap away. */
    @Test fun aHardDayDoneByAFreeRunSaysSo() {
        val repo = repo()
        runBlocking { repo.saveRun(repo.freeRun(230), RunResult(1.0, 5, 1500, distanceM = 4200.0), clock, emptyList(), monday) }
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Plan), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { DayScreen(monday) } }
            }
        }
        settle()
        compose.onNodeWithText("Сегодня").assertExists()
        compose.onNodeWithText("Сделано: Свободный бег").assertExists()
        compose.onNodeWithText("перешла на следующий тяжёлый день", substring = true).assertExists()
        compose.onNodeWithText("Как прошло — разбор", ignoreCase = true).assertExists()
        compose.onRoot().captureRoboImage("build/shots/carbon-day-done-other.png")
    }

    /**
     * Monday's planned hard run done (race pace, the trial not due): the plan has moved on (the next hard one is tempo),
     * but Monday — on the main screen, in the day and in the week — shows what was run, not the next one as "moved".
     */
    private fun doneMonday(): TrainingRepository {
        val repo = repo()
        runBlocking {
            repo.store.setRunState(repo.store.runStateNow().copy(runs = 6, vdot = 50.0, lastTestDay = monday.toEpochDay() - 7))
            val w = repo.runFor(SessionKind.RUN_QUALITY, monday)
            assertEquals(forma.core.run.RunType.GOAL_PACE, w.type)
            repo.saveRun(w, RunResult(1.0, 7, 1800, distanceM = 4000.0), clock - 1_900_000, emptyList(), monday)
            assertEquals(forma.core.run.RunType.TEMPO, repo.runFor(SessionKind.RUN_QUALITY, monday.plusDays(3)).type)
        }
        return repo
    }

    private fun show(repo: TrainingRepository, screen: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { screen() } }
            }
        }
        settle()
    }

    @Test fun aDoneRunShowsOnTheMainScreenAsItWasRun() {
        show(doneMonday()) { TodayScreen() }
        compose.onAllNodesWithText("Темп цели", substring = true)[0].assertExists()
        compose.onAllNodesWithText("в следующий тяжёлый день", substring = true).assertCountEquals(0)
    }

    @Test fun aDoneRunShowsInItsDayAsItWasRun() {
        show(doneMonday()) { DayScreen(monday) }
        compose.onNodeWithText("Темп цели", substring = true).assertExists()
        compose.onAllNodesWithText("Интервалы", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("перешла на следующий тяжёлый день", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Как прошло — разбор", ignoreCase = true).assertExists()
        compose.onRoot().captureRoboImage("build/shots/carbon-day-done.png")
    }

    @Test fun aDoneRunShowsInTheWeekAsItWasRun() {
        show(doneMonday()) { PlanScreen() }
        // Monday as run, Thursday what comes next.
        compose.onNodeWithText("Темп цели", substring = true).performScrollTo()
        compose.onNodeWithText("Темповый", substring = true).performScrollTo()
        compose.onAllNodesWithText("Сделано")[0].assertExists()
        compose.onRoot().captureRoboImage("build/shots/carbon-plan-done.png")
    }

    /** Trained on a pause day anyway: the run counts in the week, so the main screen and the day show it under the pause. */
    private fun runOnAPauseDay(): TrainingRepository {
        val repo = repo()
        runBlocking {
            repo.pause(monday, monday.plusDays(2))
            repo.saveRun(repo.freeRun(230), RunResult(1.0, 5, 1500, distanceM = 4200.0), clock, emptyList(), monday)
        }
        return repo
    }

    @Test fun aRunOnAPauseDayShowsOnTheMainScreen() {
        show(runOnAPauseDay()) { TodayScreen() }
        compose.onNodeWithText("Отдыхаем до", substring = true).assertExists()
        compose.onNodeWithText("Сделано вне плана", substring = true, ignoreCase = true).performScrollTo()
    }

    @Test fun aRunOnAPauseDayShowsInItsDay() {
        show(runOnAPauseDay()) { DayScreen(monday) }
        compose.onNodeWithText("Пауза:", substring = true).assertExists()
        compose.onNodeWithText("Сделано вне плана", substring = true, ignoreCase = true).performScrollTo()
    }

    /** Large font (×1.5): the day still reads — the run's parts and its reasons wrap, nothing is cut off. */
    @Test fun theDayReadsInALargeFont() {
        val repo = repo()
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(d.density, 1.5f),
                LocalRepo provides repo, LocalNav provides Navigator(Screen.Plan), LocalSettings provides Settings(),
            ) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { DayScreen(monday.plusDays(3)) } }
            }
        }
        settle()
        compose.onNodeWithText("Четверг").assertExists()
        compose.onRoot().captureRoboImage("build/shots/carbon-day-large.png")
        compose.onNodeWithText("Что даёт телу").performScrollTo()
        compose.onRoot().captureRoboImage("build/shots/carbon-day-large2.png")
    }

    /** Race pace on the stadium, the first reps too fast, a 9 for effort: the debrief says so and what follows. */
    @Test fun aSavedRunIsTalkedThrough() {
        val repo = repo()
        val id = runBlocking {
            val w = repo.runFor(SessionKind.RUN_QUALITY, monday.plusDays(3), trial = false)
            val lap = w.segments.first { it.lapSec != null }.lapSec!!
            val laps = (1..6).map { rep -> Lap(if (rep <= 2) lap - 3 else lap, lap - 0.5, lap + 0.5, 1, 1, rep) }
            repo.saveRun(w, RunResult(1.0, 9, 1800, distanceM = 4000.0, workPace = 220.0, laps = laps), clock, emptyList(), monday).first
        }
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.History), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { HistoryItemScreen(id) } }
            }
        }
        settle()
        compose.onNodeWithText("Разбор", ignoreCase = true).performScrollTo()
        compose.onNodeWithText("Круги отрезков: 4 из 6 в темпе", substring = true).performScrollTo()
        compose.onRoot().captureRoboImage("build/shots/carbon-debrief.png")
        compose.onNodeWithText("Оценка 9", substring = true).performScrollTo()
        compose.onNodeWithText("Восстановление").performScrollTo()
        compose.onRoot().captureRoboImage("build/shots/carbon-debrief2.png")
    }
}
