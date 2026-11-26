package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
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
import forma.app.ui.screens.DayScreen
import forma.app.ui.screens.PlanScreen
import forma.app.ui.screens.TodayScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.DayPlan
import forma.core.plan.DaySwap
import forma.core.plan.Focus
import forma.core.plan.PlannedSession
import forma.core.plan.SessionKind
import forma.core.plan.WeekPlan
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis, 26 November: «…поставить мне сегодня день ног с бегом, а сегодняшний верх перенести на субботу». With Thursday
 * and Saturday of this week changed places, «Сегодня», the day's screen, «Дальше по плану» and the plan's week show the
 * days as they stand, and «План» says so in one line with the way back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class SwapDaysUiTest {
    @get:Rule val compose = createComposeRule()

    private val monday = LocalDate.of(2026, 11, 23)
    private val thursday = monday.plusDays(3)
    private val friday = monday.plusDays(4)
    private val saturday = monday.plusDays(5)
    private var clock = 0L
    private var seq = 0L

    private fun at(day: LocalDate) {
        clock = day.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun has(text: String, exact: Boolean = false) = compose.onAllNodesWithText(text, substring = !exact, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String, exact: Boolean = false) = compose.waitUntil(10_000) { settle(); has(text, exact) }

    private var shown: MutableState<(@Composable () -> Unit)?>? = null

    /** The content is set once per test; later screens replace the one shown. */
    private fun show(repo: TrainingRepository, screen: @Composable () -> Unit) {
        val s = shown
        if (s != null) {
            s.value = null
            settle()
            s.value = screen
        } else {
            val state = mutableStateOf<(@Composable () -> Unit)?>(screen)
            shown = state
            compose.setContent {
                CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                    FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { state.value?.invoke() } }
                }
            }
        }
        settle()
    }

    private fun add(repo: TrainingRepository, d: LocalDate, kind: String, template: String) = runBlocking {
        val start = d.atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 60_000 * seq++
        repo.db.workouts().insert(
            WorkoutEntity(
                epochDay = d.toEpochDay(), kind = kind, templateId = template, title = template, startedAt = start,
                finishedAt = start + 1_800_000, status = Status.DONE, rpe = 6, durationSec = 1800, load = 120.0,
            ),
        )
    }

    /** His week on Thursday the 26th, Monday and Tuesday done, Thursday and Saturday changed places. */
    private fun repo(name: String): TrainingRepository = runBlocking {
        at(thursday)
        // No run left going by another test: a run going on today keeps the day where it is.
        forma.app.run.RunSession.clear()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, name)
        store.clear()
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now = { clock })
        repo.completeOnboarding(
            Profile(
                name = "Денис", goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 71.0, runGoal = RunGoal(1500, 330), lapMeters = 205,
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
        repo.store.setRunState(repo.store.runStateNow().copy(runs = 12, walkStage = -1, lastRunDay = monday.toEpochDay()))
        val p = store.programNow()!!
        val t = p.templates.first()
        store.setProgram(
            p.copy(
                templates = listOf(
                    t.copy(id = "upper_b", title = "Верх · Б", focus = Focus.UPPER), t.copy(id = "upper_a", title = "Верх · А", focus = Focus.UPPER),
                    t.copy(id = "lower_a", title = "Низ", focus = Focus.LOWER),
                ),
                startEpochDay = monday.minusWeeks(1).toEpochDay(), createdEpochDay = monday.minusWeeks(4).toEpochDay(),
                week = WeekPlan(
                    listOf(
                        DayPlan(1, listOf(PlannedSession(SessionKind.RUN_QUALITY))),
                        DayPlan(2, listOf(PlannedSession(SessionKind.LIFT, "upper_b"))),
                        DayPlan(4, listOf(PlannedSession(SessionKind.RUN_QUALITY), PlannedSession(SessionKind.LIFT, "upper_a"))),
                        DayPlan(6, listOf(PlannedSession(SessionKind.LIFT, "lower_a"), PlannedSession(SessionKind.RUN_EASY))),
                    ),
                    p.week.notes,
                ),
            ),
        )
        add(repo, monday, Kind.RUN, "INTERVALS")
        add(repo, monday.plusDays(1), Kind.LIFT, "upper_b")
        assertNull(repo.swapDays(4, 6, thursday), "Thursday and Saturday change places")
        repo
    }

    @Test fun theScreensShowTheDaysAsTheyStand() {
        val repo = repo("ui-swap-days")
        // «Сегодня» on Thursday: the leg day with its easy run, not the upper body and the hard run.
        show(repo) { TodayScreen() }
        waitFor("Низ", exact = true)
        assertTrue(has("Лёгкий бег"), "Saturday's easy run is today's")
        assertFalse(has("Верх · А"), "the upper body went to Saturday")
        // Saturday's screen: the hard run and «Верх · А».
        show(repo) { DayScreen(saturday) }
        waitFor("Верх · А")
        assertFalse(has("Низ", exact = true), "the leg day is Thursday's this week")
        // Thursday's own screen, and the next week's Thursday as planned.
        show(repo) { DayScreen(thursday) }
        waitFor("Низ", exact = true)
        assertFalse(has("Верх · А"))
        show(repo) { DayScreen(thursday.plusWeeks(1)) }
        waitFor("Верх · А")
        // «Дальше по плану» on Friday, a rest day after Thursday's sessions: Saturday with «Верх · А».
        add(repo, thursday, Kind.LIFT, "lower_a")
        add(repo, thursday, Kind.RUN, "EASY")
        at(friday)
        show(repo) { TodayScreen() }
        waitFor("Дальше по плану")
        assertTrue(has("Завтра, суббота"))
        assertTrue(has("Верх · А"), "tomorrow is the upper body and the hard run")
    }

    @Test fun thePlanSaysTheSwapAndTakesItBack() {
        val repo = repo("ui-swap-days-plan")
        show(repo) { PlanScreen() }
        waitFor("На этой неделе четверг и суббота поменяны местами.")
        assertTrue(has("Вернуть", exact = true))
        compose.onAllNodesWithText("Вернуть")[0].performScrollTo().performClick()
        compose.waitUntil(10_000) { settle(); runBlocking { repo.store.settingsNow().daySwaps.isEmpty() } }
        settle()
        assertFalse(has("поменяны местами"), "the week is as planned again")
        assertFalse(has("Вернуть", exact = true))
    }

    @Test fun noWayBackOnceADayOfTheSwapIsBegun() {
        val repo = repo("ui-swap-days-begun")
        add(repo, thursday, Kind.LIFT, "lower_a")
        show(repo) { PlanScreen() }
        waitFor("На этой неделе четверг и суббота поменяны местами.")
        assertFalse(has("Вернуть", exact = true), "Thursday's «Низ» is done: the days stay")
        // The week's list is the week as it stands: Thursday's row is «Низ», done — with Monday's run and Tuesday's gym.
        assertEquals(3, compose.onAllNodesWithText("Сделано").fetchSemanticsNodes().size)
        assertEquals(listOf(DaySwap(monday.toEpochDay(), 4, 6)), runBlocking { repo.store.settingsNow().daySwaps })
    }
}
