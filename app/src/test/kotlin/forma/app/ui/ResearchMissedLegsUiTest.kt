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
import forma.app.data.MovedRun
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.Status
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.app.ui.screens.DayScreen
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
import kotlin.test.assertTrue

/**
 * Research one-leg-day+D1 (c), approved by Denis on 25 November: on Sunday «Сегодня» and Saturday's day screen offer the
 * missed «Низ» with the two choices for Monday's hard run; «Тяжёлый бег во вторник» moves it, and Monday and Tuesday say so.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi")
class ResearchMissedLegsUiTest {
    @get:Rule val compose = createComposeRule()

    private val monday = LocalDate.of(2026, 11, 16)
    private val saturday = monday.plusDays(5)
    private val sunday = monday.plusDays(6)
    private var clock = sunday.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
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

    private fun has(text: String) = compose.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) = compose.waitUntil(10_000) { settle(); has(text) }

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

    /** His week: the last «Низ» on 7.11, the week of 16.11 done but Saturday. */
    private fun repo(): TrainingRepository = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "ui-research-missed-legs")
        store.clear()
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now = { clock })
        repo.completeOnboarding(
            Profile(
                name = "Денис", goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 71.0, runGoal = RunGoal(1500, 330), lapMeters = 205,
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
        repo.store.setRunState(repo.store.runStateNow().copy(runs = 2, walkStage = -1))
        val p = store.programNow()!!
        val t = p.templates.first()
        store.setProgram(
            p.copy(
                templates = listOf(
                    t.copy(id = "upper_b", title = "Верх · Б", focus = Focus.UPPER), t.copy(id = "upper_a", title = "Верх · А", focus = Focus.UPPER),
                    t.copy(id = "lower_a", title = "Низ", focus = Focus.LOWER),
                ),
                createdEpochDay = monday.minusWeeks(4).toEpochDay(),
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
        for ((d, kind, template) in listOf(
            Triple(LocalDate.of(2026, 11, 7), Kind.LIFT, "lower_a"),
            Triple(monday, Kind.RUN, "INTERVALS"), Triple(monday.plusDays(1), Kind.LIFT, "upper_b"),
            Triple(monday.plusDays(3), Kind.RUN, "TEMPO"), Triple(monday.plusDays(3), Kind.LIFT, "upper_a"),
        )) {
            val start = d.atTime(16, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 60_000 * seq++
            repo.db.workouts().insert(
                WorkoutEntity(
                    epochDay = d.toEpochDay(), kind = kind, templateId = template, title = template, startedAt = start,
                    finishedAt = start + 1_800_000, status = Status.DONE, rpe = 6, durationSec = 1800, load = 120.0,
                ),
            )
        }
        repo
    }

    @Test fun theMissedLowerOffersBothChoicesAndTheMoveShowsOnMondayAndTuesday() {
        val repo = repo()
        at(sunday)
        show(repo) { TodayScreen() }
        // The number first, one sentence, the two choices as buttons.
        waitFor("15\u00A0дней без тренировки ног: «Низ» сегодня, а в понедельник лёгкий бег или тяжёлый во вторник.")
        assertTrue(has("Лёгкий бег в понедельник"), "the easy-Monday button")
        assertTrue(has("Тяжёлый бег во вторник"), "the Tuesday button")

        // Saturday's day screen: the same card.
        show(repo) { DayScreen(saturday) }
        waitFor("без тренировки ног")
        assertTrue(has("Тяжёлый бег во вторник"), "the Tuesday button on the day screen")

        show(repo) { TodayScreen() }
        waitFor("Тяжёлый бег во вторник")
        compose.onAllNodesWithText("Тяжёлый бег во вторник", ignoreCase = true)[0].performScrollTo().performClick()
        settle()
        assertEquals(MovedRun(sunday.plusDays(1).toEpochDay(), sunday.plusDays(2).toEpochDay()), runBlocking { repo.store.settingsNow().movedRun })

        // Monday: no hard run today, said where it went.
        at(sunday.plusDays(1))
        show(repo) { TodayScreen() }
        waitFor("Засчитается за сегодня")
        assertTrue(has("Сегодня отдых"), "Monday is a rest day now")
        // Tuesday: the run first, before the gym, counted for Monday.
        at(sunday.plusDays(2))
        show(repo) { TodayScreen() }
        waitFor("Перед залом, засчитается за понедельник")
        assertTrue(has("Начать пробежку"), "the moved run starts from its card")
    }
}
