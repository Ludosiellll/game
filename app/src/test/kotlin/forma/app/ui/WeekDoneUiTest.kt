package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.Feel
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.DayScreen
import forma.app.ui.screens.HistoryScreen
import forma.app.ui.screens.NextToday
import forma.app.ui.screens.ProgressScreen
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

/** Denis's week on the main screen and its days: the day's feeling kept, done sessions as done, missed and caught up. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi")
class WeekDoneUiTest {
    @get:Rule val compose = createComposeRule()

    private val monday = LocalDate.of(2026, 10, 12)
    private var clock = monday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun at(day: LocalDate, hour: Int = 9) {
        clock = day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    /** Denis: the gym Tue/Thu/Sat, running Mon/Thu/Sat on a 230 m track, 1500 m in 5:30 as the goal. */
    private fun repo(): TrainingRepository = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "ui-week-done")
        store.clear()
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now = { clock })
        repo.completeOnboarding(
            Profile(
                name = "Денис", aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
                runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6), bodyWeightKg = 80.0, birthYear = 2008, doubleDays = true,
                runGoal = RunGoal(1500, 330), lapMeters = 230, gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
            ),
        )
        repo.store.setRunState(repo.store.runStateNow().copy(runs = 2, walkStage = -1))
        repo
    }

    private suspend fun lift(repo: TrainingRepository, templateId: String, day: LocalDate): Long {
        at(day, 18)
        val id = repo.startLift(templateId, day)
        repo.planOf(repo.db.workouts().get(id)!!)!!.items.forEach { clock += 60_000; repo.logSet(id, it, it.prescription.weight ?: 20.0, it.prescription.reps, 2) }
        repo.finishLift(id, 7)
        return id
    }

    private var shown: androidx.compose.runtime.MutableState<(@androidx.compose.runtime.Composable () -> Unit)?>? = null

    /** The content is set once per test; later screens replace the one shown. */
    private fun show(repo: TrainingRepository, nav: Navigator = Navigator(Screen.Today), screen: @androidx.compose.runtime.Composable () -> Unit) {
        val s = shown
        if (s != null) {
            s.value = null
            settle()
            s.value = screen
        } else {
            val state = androidx.compose.runtime.mutableStateOf<(@androidx.compose.runtime.Composable () -> Unit)?>(screen)
            shown = state
            compose.setContent {
                CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav, LocalSettings provides Settings()) {
                    FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { state.value?.invoke() } }
                }
            }
        }
        settle()
    }

    /** "Так себе" is kept for the day: the second session of a double day starts light too. */
    @Test fun soSoIsKeptForTheDaysSecondSession() {
        val repo = repo()
        val thursday = monday.plusDays(3)
        val p = runBlocking { repo.store.programNow()!! }
        val run = p.week.on(4).first { it.isRun }
        at(thursday)
        show(repo) { TodayScreen() }
        compose.onNodeWithText("Так себе").performScrollTo().performClick()
        settle()
        assertEquals(Feel.LOW, runBlocking { repo.store.settingsNow().feelOn(thursday) })
        // The gym done first; after it the run is offered lighter.
        runBlocking { lift(repo, p.week.on(4).first { it.kind == SessionKind.LIFT }.templateId!!, thursday) }
        val light = runBlocking { repo.runFor(run.kind, thursday, light = true).title }
        show(repo) { Column { NextToday {} } }
        compose.onNodeWithText("Дальше: $light").assertExists()
    }

    @Test fun sickOffersAPauseOfThreeDays() {
        val repo = repo()
        show(repo) { TodayScreen() }
        compose.onNodeWithText("Болею").performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Пауза на 3 дня — до 14 октября").performScrollTo()
        compose.onRoot().captureRoboImage("build/shots/carbon-today-sick.png")
        compose.onNodeWithText("Пауза на 3 дня — до 14 октября").performClick()
        settle()
        compose.onNodeWithText("Отдыхаем до 14 октября").assertExists()
        // The days of the pause hold no sessions.
        show(repo) { DayScreen(monday.plusDays(1)) }
        compose.onNodeWithText("Пауза", substring = true).assertExists()
        compose.onAllNodesWithText("Начать", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Зачем").assertCountEquals(0)
    }

    /** Tuesday's gym skipped, done on Wednesday: Wednesday says so, Tuesday shows "сделано в ср", Monday's run missed. */
    @Test fun aMissedLiftCaughtUpShowsOnBothDays() {
        val repo = repo()
        val p = runBlocking { repo.store.programNow()!! }
        val tue = p.week.on(2).first { it.kind == SessionKind.LIFT }
        val title = p.templates.first { it.id == tue.templateId }.title
        runBlocking { lift(repo, tue.templateId!!, monday.plusDays(2)) }
        at(monday.plusDays(2), 19)
        show(repo) { TodayScreen() }
        compose.onNodeWithText("Вместо пропущенной во вт", substring = true, ignoreCase = true).assertExists()
        compose.onNodeWithText("Разбор тренировки", ignoreCase = true).performScrollTo()
        compose.onAllNodesWithText("Сегодня день восстановления").assertCountEquals(0)
        // In the week: Monday's run missed (a hollow dot), Wednesday done off plan.
        compose.onAllNodesWithContentDescription("пропущено").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("сделано вне плана").assertCountEquals(1)
        compose.onRoot().captureRoboImage("build/shots/carbon-today-caught-up.png")

        show(repo) { DayScreen(monday.plusDays(1)) }
        compose.onNodeWithText("Сделано в ср: $title").assertExists()
        show(repo) { DayScreen(monday) }
        compose.onNodeWithText("Пропущено", ignoreCase = true).assertExists()
        compose.onAllNodesWithText("Как бежать").assertCountEquals(0)
        compose.onRoot().captureRoboImage("build/shots/carbon-day-missed.png")
    }

    /** A done gym session shows what was done (its sets), with the breakdown a tap away — not the next session's weights. */
    @Test fun aDoneLiftShowsWhatWasDone() {
        val repo = repo()
        val tuesday = monday.plusDays(1)
        val p = runBlocking { repo.store.programNow()!! }
        val tue = p.week.on(2).first { it.kind == SessionKind.LIFT }
        val id = runBlocking { lift(repo, tue.templateId!!, tuesday) }
        val first = runBlocking { repo.planOf(repo.db.workouts().get(id)!!)!!.items.first() }
        show(repo) { TodayScreen() }
        compose.onNodeWithText(first.name).performScrollTo()
        compose.onNodeWithText("Разбор тренировки", ignoreCase = true).performScrollTo()
        compose.onRoot().captureRoboImage("build/shots/carbon-today-lift-done.png")
        show(repo) { DayScreen(tuesday) }
        compose.onNodeWithText("Разбор тренировки", ignoreCase = true).performScrollTo()
    }

    @Test fun historyFiltersAndLabelsTheLoad() {
        val repo = repo()
        val p = runBlocking { repo.store.programNow()!! }
        runBlocking { lift(repo, p.week.on(2).first { it.kind == SessionKind.LIFT }.templateId!!, monday.plusDays(1)) }
        show(repo, Navigator(Screen.History)) { HistoryScreen() }
        compose.onNodeWithText("нагрузка", substring = true).assertExists()
        compose.onNodeWithText("Контрольные").performClick()
        settle()
        compose.onNodeWithText("Контрольных забегов ещё не было.").assertExists()
        compose.onRoot().captureRoboImage("build/shots/carbon-history-filter.png")
    }

    @Test fun theLoadMetricIsTrainedness() {
        val repo = repo()
        val p = runBlocking { repo.store.programNow()!! }
        runBlocking { lift(repo, p.week.on(2).first { it.kind == SessionKind.LIFT }.templateId!!, monday.plusDays(1)) }
        show(repo, Navigator(Screen.Progress)) { ProgressScreen(initialTab = 2) }
        compose.onAllNodesWithText("Тренированность")[0].assertExists()
        compose.onAllNodesWithText("Форма").assertCountEquals(0)
    }
}
