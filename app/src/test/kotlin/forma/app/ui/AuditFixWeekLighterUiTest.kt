package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.CoachActions
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.sync.CoachCall
import forma.app.ui.screens.PlanScreen
import forma.app.ui.screens.TodayScreen
import forma.app.ui.screens.shortDate
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The audit of 24 November, the week (group C), on the screens: the lighter week's banner, the week's count, pauses. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class AuditFixWeekLighterUiTest {
    @get:Rule val compose = createComposeRule()

    private val monday = LocalDate.of(2026, 11, 23)
    private var clock = at(monday)

    private fun at(d: LocalDate) = d.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

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
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now = { clock })
    }

    private fun show(repo: TrainingRepository, look: String = "carbon", content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId(look)) { Box(Modifier.fillMaxSize().background(F.c.bg)) { content() } }
            }
        }
        settle()
    }

    private fun shown(text: String, exact: Boolean = false) =
        compose.onAllNodesWithText(text, substring = !exact).fetchSemanticsNodes().size

    private val denis = Profile(
        name = "Денис", goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
        bodyWeightKg = 73.0, birthYear = 2008, runGoal = RunGoal(1500, 330), lapMeters = 205,
        aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
    )

    /** novice-lighter-week-gym-unchanged: a novice's lighter week asked of the coach: «Сегодня» says the gym is lighter too. */
    @Test fun aNovicesLighterWeekSaysTheGymToo() {
        val repo = repo("ui-audit-c-novice-lighter")
        runBlocking {
            repo.completeOnboarding(
                Profile(name = "Аня", goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 3, 5), bodyWeightKg = 60.0, birthYear = 1998),
            )
            val call = CoachCall(CoachActions.LIGHTER, JsonObject(mapOf("when" to JsonPrimitive("this"), "why" to JsonPrimitive("устала"))))
            CoachActions.make(repo, assertIs<CoachActions.Ready>(CoachActions.prepare(repo, call, monday)))
        }
        show(repo) { TodayScreen() }
        // The gym's half sets in the banner (with runs or without), not «Лёгкая неделя бега» alone.
        compose.waitUntil(10_000) { settle(); shown("Лёгкая неделя: ") > 0 && shown("вдвое меньше подходов") > 0 }
        assertTrue(shown("Лёгкая неделя бега") == 0)
    }

    /**
     * week-count-before-plan: the plan made on Thursday of a week with six sessions, three of them before Thursday: the
     * line under the title counts the plan's three, not six.
     */
    @Test fun theWeeksCountIsOfThePlansDays() {
        val repo = repo("ui-audit-c-week-count")
        val thursday = monday.plusDays(3)
        clock = at(thursday)
        runBlocking {
            repo.completeOnboarding(denis)
            val p = repo.store.programNow()!!
            val t = p.templates.first()
            repo.store.setProgram(
                p.copy(
                    templates = listOf(t.copy(id = "upper_a", title = "Верх", focus = Focus.UPPER), t.copy(id = "lower_a", title = "Низ", focus = Focus.LOWER)),
                    createdEpochDay = thursday.toEpochDay(),
                    week = WeekPlan(
                        listOf(
                            DayPlan(1, listOf(PlannedSession(SessionKind.RUN_QUALITY))),
                            DayPlan(2, listOf(PlannedSession(SessionKind.LIFT, "upper_a"))),
                            DayPlan(3, listOf(PlannedSession(SessionKind.RUN_EASY))),
                            DayPlan(4, listOf(PlannedSession(SessionKind.RUN_QUALITY))),
                            DayPlan(6, listOf(PlannedSession(SessionKind.LIFT, "lower_a"), PlannedSession(SessionKind.RUN_EASY))),
                        ),
                        p.week.notes,
                    ),
                ),
            )
        }
        show(repo, look = "minimal") { TodayScreen() }
        compose.waitUntil(10_000) { settle(); shown("на этой неделе") > 0 }
        assertTrue(shown("0 из 3 тренировок") > 0, "the plan's three sessions from Thursday")
        assertTrue(shown("из 6 тренировок") == 0)
    }

    /** plan-week-ignores-pause: a week's pause: the Plan's week says «Пауза» on each day, no sessions and no how-to. */
    @Test fun thePlansWeekSaysPauseOnPausedDays() {
        val repo = repo("ui-audit-c-plan-pause")
        runBlocking {
            repo.completeOnboarding(denis)
            repo.pause(monday, monday.plusDays(6))
        }
        show(repo) { PlanScreen() }
        compose.waitUntil(10_000) { settle(); shown("Пауза", exact = true) >= 7 }
        assertTrue(shown("Отдых", exact = true) == 0, "a pause day is not a rest day of the plan")
    }

    /**
     * sick-pause-inside-pause-stuck: a short pause an older version left inside a two-week holiday: on its sixth day
     * «Сегодня» says the holiday's end, not a day already gone.
     */
    @Test fun thePauseCardSaysTheEndOfThePauseThatHoldsToday() {
        val repo = repo("ui-audit-c-pause-card")
        runBlocking {
            repo.completeOnboarding(denis)
            repo.pause(monday, monday.plusDays(13))
            repo.pause(monday.plusDays(2), monday.plusDays(4))
        }
        clock = at(monday.plusDays(5))
        show(repo) { TodayScreen() }
        compose.waitUntil(10_000) { settle(); shown("Отдыхаем до") > 0 }
        assertTrue(shown("Отдыхаем до ${shortDate(monday.plusDays(13))}") > 0, "the holiday's end")
    }
}
