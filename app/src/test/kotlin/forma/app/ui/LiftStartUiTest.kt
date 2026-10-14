package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
import forma.app.ui.screens.NextToday
import forma.app.ui.screens.TodayScreen
import forma.app.ui.screens.WorkoutScreen
import forma.app.ui.screens.finishOnOpen
import forma.app.ui.screens.sameLift
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
import forma.core.plan.Program
import forma.core.plan.SessionKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis leaves Tuesday's gym session open (off to the stadium) and comes back on Thursday: starting the gym from the
 * main screen, a day of the plan or "Дальше" never puts Thursday's sets into Tuesday's session. Legacy graphics: a
 * dialog over the workout screen never lets Compose go idle under Robolectric with native graphics.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class LiftStartUiTest {
    @get:Rule val compose = createComposeRule()

    @After fun tidy() {
        RestTimer.stop()
        finishOnOpen = null
    }

    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val monday = LocalDate.of(2026, 10, 12)
    private val tuesday = monday.plusDays(1)
    private val thursday = monday.plusDays(3)
    private var clock = monday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun at(day: LocalDate, hour: Int) {
        clock = day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    /** [read] once it is there, settling between tries. */
    private fun <T> waitFor(read: () -> T?): T? {
        repeat(20) { settle(); read()?.let { return it } }
        return null
    }

    private fun texts(substring: String): List<String> =
        compose.onAllNodesWithText(substring, substring = true).fetchSemanticsNodes()
            .map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "" }

    /** Denis: the gym Tue/Thu/Sat, running Mon/Thu/Sat. */
    private fun repo(name: String): TrainingRepository = runBlocking {
        val store = SettingsStore(ctx, name)
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

    private fun program(repo: TrainingRepository): Program = runBlocking { repo.store.programNow()!! }
    private fun liftOn(p: Program, day: Int) = p.week.on(day).first { it.kind == SessionKind.LIFT }.templateId!!
    private fun titleOf(p: Program, templateId: String) = p.templates.first { it.id == templateId }.title

    /** Tuesday's session, left open after a set. */
    private fun leftOpenOnTuesday(repo: TrainingRepository): Long = runBlocking {
        at(tuesday, 18)
        val id = repo.startLift(liftOn(program(repo), 2), tuesday)
        val item = repo.planOf(repo.db.workouts().get(id)!!)!!.items.first()
        repo.logSet(id, item, item.prescription.weight ?: 20.0, item.prescription.reps, 2)
        id
    }

    private fun show(repo: TrainingRepository, nav: Navigator, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav, LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { content() } }
            }
        }
        settle()
    }

    /** Thursday's gym from the main screen with Tuesday's still open: asked about; "Удалить и начать" starts Thursday's. */
    @Test fun aSessionLeftOpenOnAnotherDayIsAskedAbout() {
        val repo = repo("lift-start-other-day")
        val p = program(repo)
        val old = leftOpenOnTuesday(repo)
        RestTimer.start(ctx, old, 90_000, beep = false, vibrate = false)
        val oldTitle = titleOf(p, liftOn(p, 2))
        at(thursday, 18)
        val nav = Navigator(Screen.Today)
        show(repo, nav) { TodayScreen() }
        compose.onNodeWithText("Начать тренировку").performScrollTo().performClick()
        settle()
        compose.onNodeWithText("«$oldTitle» не закончена").assertExists()
        assertTrue(texts("Начата 13 октября").isNotEmpty(), "the day it was started on is said")
        compose.onNodeWithText("Продолжить «$oldTitle»").assertExists()
        compose.onNodeWithText("Закончить «$oldTitle»").assertExists()
        assertEquals(Screen.Today, nav.current, "nothing opened before the answer")
        assertEquals(1, runBlocking { repo.db.sets().forWorkout(old) }.size, "nothing went into Tuesday's")

        compose.onNodeWithText("Удалить и начать").performClick()
        // Laying out a session takes a moment under Robolectric.
        val now = assertNotNull(waitFor { runBlocking { repo.db.workouts().active() } })
        assertEquals(liftOn(p, 4), now.templateId)
        assertEquals(thursday.toEpochDay(), now.epochDay)
        assertNull(runBlocking { repo.db.workouts().get(old) })
        assertEquals(Screen.Workout(now.id), nav.current)
        assertNull(RestTimer.rest.value, "the rest of the deleted session went with it")
    }

    /** "Закончить «…»": the session left open opens with "Завершить" asked. */
    @Test fun finishOpensItWithTheEffortAsked() {
        val repo = repo("lift-start-finish")
        val p = program(repo)
        val old = leftOpenOnTuesday(repo)
        at(thursday, 18)
        val nav = Navigator(Screen.Today)
        show(repo, nav) {
            when (val s = nav.current) {
                is Screen.Workout -> WorkoutScreen(s.workoutId)
                else -> TodayScreen()
            }
        }
        compose.onNodeWithText("Начать тренировку").performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Закончить «${titleOf(p, liftOn(p, 2))}»").performClick()
        settle()
        assertEquals(Screen.Workout(old), nav.current)
        assertTrue(texts("Как тяжело было в целом?").isNotEmpty())
    }

    /** The same session of the same day (back from the stadium) just opens; another day's of the same session does not. */
    @Test fun theSameSessionOfTheSameDayJustOpens() {
        val repo = repo("lift-start-same")
        val p = program(repo)
        at(thursday, 18)
        val open = runBlocking { repo.startLift(liftOn(p, 4), thursday) }
        val nav = Navigator(Screen.Today)
        show(repo, nav) { TodayScreen() }
        compose.onNodeWithText("Начать тренировку").performScrollTo().performClick()
        settle()
        assertEquals(Screen.Workout(open), nav.current)
        assertTrue(texts("Удалить и начать").isEmpty())

        val w = WorkoutEntity(epochDay = tuesday.toEpochDay(), kind = Kind.LIFT, templateId = "a", title = "А", startedAt = 0L, status = Status.ACTIVE)
        assertTrue(sameLift(w, "a", tuesday))
        assertFalse(sameLift(w, "a", thursday), "the same session from another day")
        assertFalse(sameLift(w, "b", tuesday))
    }

    /** "Дальше" after a session asks the same way: Saturday's gym with Tuesday's still open. */
    @Test fun nextAsksTheSameWay() {
        val repo = repo("lift-start-next")
        val p = program(repo)
        val old = leftOpenOnTuesday(repo)
        val saturday = monday.plusDays(5)
        val first = p.week.on(6).first()
        assertEquals(SessionKind.LIFT, first.kind, "Saturday starts in the gym")
        at(saturday, 10)
        val nav = Navigator(Screen.Today)
        show(repo, nav) { Column { NextToday {} } }
        compose.onNodeWithText("Дальше: ${titleOf(p, first.templateId!!)}").performClick()
        settle()
        compose.onNodeWithText("«${titleOf(p, liftOn(p, 2))}» не закончена").assertExists()
        assertEquals(old, runBlocking { repo.db.workouts().active() }!!.id, "nothing started over it")
        assertEquals(Screen.Today, nav.current)
    }
}
