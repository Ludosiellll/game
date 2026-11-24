package forma.app.ui

import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.SlotStateEntity
import forma.app.data.TrainingRepository
import forma.app.ui.screens.TodayScreen
import forma.app.ui.screens.WorkoutScreen
import forma.app.ui.screens.finishOnOpen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.Program
import forma.core.plan.SessionKind
import forma.core.text.Ru
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gym screen after the audit of November 2026 (group B, the workout screen): a session left open on another day,
 * a plank still on the stopwatch, the last set rated after the next one was typed, notifications refused for good.
 * Legacy graphics: with native graphics a dialog over the workout screen never lets Compose go idle under Robolectric.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class AuditFixWorkoutScreenUiTest {
    // The activity itself: the permission request goes to it and its answer comes back through it.
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun tidy() {
        RestTimer.stop()
        finishOnOpen = null
    }

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

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

    /** Whether [done] comes true, settling between tries. */
    private fun eventually(done: () -> Boolean): Boolean = waitFor { true.takeIf { done() } } == true

    private fun texts(substring: String): List<String> =
        compose.onAllNodesWithText(substring, substring = true).fetchSemanticsNodes()
            .map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "" }

    private fun show(repo: TrainingRepository, nav: Navigator = Navigator(Screen.Today), content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav, LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { content() } }
            }
        }
        settle()
    }

    // ---------- a session left open on another day (gym-01) ----------

    private val monday = LocalDate.of(2026, 10, 12)
    private val lastSaturday = monday.plusDays(5)
    private val saturday = monday.plusDays(12)
    private var clock = monday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun at(day: LocalDate, hour: Int) {
        clock = day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** Denis: the gym Tue/Thu/Sat, running Mon/Thu/Sat; the repository's clock is [clock]. */
    private fun denis(name: String): TrainingRepository = runBlocking {
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
    private fun saturdayLift(p: Program) = p.week.on(6).first { it.kind == SessionKind.LIFT }.templateId!!
    private fun titleOf(repo: TrainingRepository, id: Long) = runBlocking { repo.db.workouts().get(id)!!.title }

    /** Saturday's session with a set done, then off to the stadium: left open. */
    private fun leftOpenLastSaturday(repo: TrainingRepository): Long = runBlocking {
        at(lastSaturday, 10)
        val id = repo.startLift(saturdayLift(program(repo)), lastSaturday)
        val item = repo.planOf(repo.db.workouts().get(id)!!)!!.items.first()
        repo.logSet(id, item, item.prescription.weight ?: 20.0, item.prescription.reps, 2)
        id
    }

    /** The screen the navigator is on: the workout once it is opened, else the main screen. */
    @Composable
    private fun Routed(nav: Navigator) {
        when (val s = nav.current) {
            is Screen.Workout -> WorkoutScreen(s.workoutId)
            else -> TodayScreen()
        }
    }

    /** gym-01: a week on, the banner of last Saturday's session asks first, with its day; «Удалить» only deletes it. */
    @Test fun theBannerOfASessionLeftOpenOnAnotherDayAsksFirst() {
        val repo = denis("audit-b-banner-old")
        val old = leftOpenLastSaturday(repo)
        val title = titleOf(repo, old)
        at(saturday, 10)
        val nav = Navigator(Screen.Today)
        show(repo, nav) { TodayScreen() }
        compose.onNodeWithText("Тренировка «$title» не закончена. Продолжить?").performScrollTo().performClick()
        settle()
        compose.onNodeWithText("«$title» не закончена").assertExists()
        assertTrue(texts("Начата 17 октября").isNotEmpty(), "the day it was started on is said")
        compose.onNodeWithText("Продолжить «$title»").assertExists()
        compose.onNodeWithText("Закончить «$title»").assertExists()
        assertTrue(compose.onAllNodesWithText("Удалить и начать").fetchSemanticsNodes().isEmpty(), "nothing new to start from the banner")
        assertEquals(Screen.Today, nav.current, "nothing opened before the answer")
        assertEquals(1, runBlocking { repo.db.sets().forWorkout(old) }.size)

        compose.onNodeWithText("Удалить").performClick()
        assertTrue(eventually { runBlocking { repo.db.workouts().get(old) } == null }, "deleted")
        assertNull(runBlocking { repo.db.workouts().active() }, "nothing started in its place")
        assertEquals(Screen.Today, nav.current)
    }

    /** gym-01: going on with an earlier day's session is a choice; its screen says the day, with «Закончить» at hand. */
    @Test fun aSessionGoneOnWithFromAnotherDaySaysItsDay() {
        val repo = denis("audit-b-banner-go-on")
        val old = leftOpenLastSaturday(repo)
        val title = titleOf(repo, old)
        at(saturday, 10)
        val nav = Navigator(Screen.Today)
        show(repo, nav) { Routed(nav) }
        compose.onNodeWithText("Тренировка «$title» не закончена. Продолжить?").performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Продолжить «$title»").performClick()
        settle()
        assertEquals(Screen.Workout(old), waitFor { nav.current.takeIf { it is Screen.Workout } } ?: nav.current)
        settle()
        compose.onNodeWithText("Начата 17 октября").assertExists()
        compose.onNodeWithText("Закончить").performClick()
        settle()
        assertTrue(texts("Как тяжело было в целом?").isNotEmpty(), "finishing it is one tap away")
    }

    /** gym-01: the banner of today's own session (back from the stadium) just opens it, with no date said. */
    @Test fun theBannerOfTodaysSessionJustOpensIt() {
        val repo = denis("audit-b-banner-today")
        at(saturday, 10)
        val id = runBlocking { repo.startLift(saturdayLift(program(repo)), saturday) }
        val title = titleOf(repo, id)
        val nav = Navigator(Screen.Today)
        show(repo, nav) { Routed(nav) }
        compose.onNodeWithText("Тренировка «$title» не закончена. Продолжить?").performScrollTo().performClick()
        assertEquals(Screen.Workout(id), waitFor { nav.current.takeIf { it is Screen.Workout } } ?: nav.current, "opened, not asked about")
        settle()
        assertTrue(texts("Начата").isEmpty(), "today's own session says no date")
    }

    // ---------- the gym screen of a session started today ----------

    private val profile = Profile(
        name = "Денис", goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE,
        trainingDays = setOf(2, 4, 6), bodyWeightKg = 72.0, birthYear = 2008,
    )

    private fun gymRepo(name: String): TrainingRepository {
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store).also { runBlocking { it.completeOnboarding(profile) } }
    }

    /** A session whose first exercise has a working weight (no first-time search). */
    private fun startWithWeight(repo: TrainingRepository, weight: Double = 60.0): Long = runBlocking {
        val t = repo.store.programNow()!!.templates.first()
        val first = repo.liftPlan(t.id, LocalDate.now()).items.first()
        repo.db.state().upsertSlot(SlotStateEntity(first.slot.key, first.exerciseId, weight, first.slot.repMin, 0, 0, 3, 0L, false, 0, null, null))
        repo.startLift(t.id)
    }

    private fun items(repo: TrainingRepository, id: Long) = runBlocking { repo.planOf(repo.db.workouts().get(id)!!)!!.items }
    private fun setsOf(repo: TrainingRepository, id: Long) = runBlocking { repo.db.sets().forWorkout(id) }

    /** gym-02: «Подход сделан» pressed with the plank's stopwatch going saves the seconds held and stops the stopwatch. */
    @Test fun theSetButtonTakesTheSecondsOfAHoldStillOnTheStopwatch() {
        val repo = gymRepo("audit-b-hold")
        val id = startWithWeight(repo)
        runBlocking { assertTrue(repo.addToWorkout(id, "plank")) }
        val planned = items(repo, id).first { it.exerciseId == "plank" }.prescription.reps
        show(repo) { WorkoutScreen(id) }
        compose.onAllNodesWithText(Catalog["plank"].name)[0].performScrollTo().performClick()
        settle()
        val before = System.currentTimeMillis()
        compose.onNodeWithText("Засечь время", ignoreCase = true).performScrollTo().performClick()
        val started = System.currentTimeMillis()
        // Three seconds to get into position, then more than two held.
        while (System.currentTimeMillis() - started < 5_600) settle()
        compose.onNodeWithText("Подход сделан").performClick()
        val pressed = System.currentTimeMillis()
        settle()
        val held = setsOf(repo, id).single { it.exerciseId == "plank" }.reps
        assertTrue(held in 2..((pressed - before - 3_000) / 1000).toInt(), "the seconds held, not the plan's $planned: $held")
        compose.onNodeWithText("Засечь время", ignoreCase = true).assertExists()
        assertTrue(compose.onAllNodesWithText("Стоп").fetchSemanticsNodes().isEmpty(), "the stopwatch stopped with the set")
    }

    /**
     * gym-04: rating the last set as its hint asks keeps the weight typed and the reserve picked for the next set. (Driven
     * through the screen, the set's dialog has text fields that never let Compose go idle under Robolectric: the rule the
     * dialog's «Сохранить» applies is checked on its own.)
     */
    @Test fun ratingTheLastSetKeepsWhatWasChosenForTheNext() {
        // The set done at 80 × 8 put 80 × 8 in the fields; «+» made it 82,5 and «1» was picked for the next set. Rated now,
        // the sets would put 85 × 7 there: the typed weight and the reserve stay.
        assertEquals(Triple(82.5, 8, 1), forma.app.ui.screens.fieldsAfterEdit(Triple(82.5, 8, 1), 80.0 to 8, 85.0 to 7))
        // Nothing typed since: the fields follow the sets as rated, the reserve picked still stays.
        assertEquals(Triple(85.0, 7, 1), forma.app.ui.screens.fieldsAfterEdit(Triple(80.0, 8, 1), 80.0 to 8, 85.0 to 7))
        assertEquals(Triple(85.0, 7, null), forma.app.ui.screens.fieldsAfterEdit(Triple(80.0, 8, null), 80.0 to 8, 85.0 to 7))
    }

    /** gym-10: with notifications refused for good, «Разрешить» opens the app's own notification settings. */
    @Test fun allowOpensTheNotificationSettingsWhenTheyAreRefusedForGood() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(ctx).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val repo = gymRepo("audit-b-notifications")
        val id = startWithWeight(repo)
        RestTimer.start(ctx, id, 90_000, beep = false, vibrate = false)
        show(repo) { WorkoutScreen(id) }
        compose.onNodeWithText("Разрешить").performScrollTo().performClick()
        settle()
        val asked = assertNotNull(shadowOf(compose.activity).lastRequestedPermission, "the system is asked first")
        assertEquals(listOf(android.Manifest.permission.POST_NOTIFICATIONS), asked.requestedPermissions.toList())
        shadowOf(ctx).clearNextStartedActivities()
        // Refused for good: the answer comes back at once, with no question shown.
        compose.runOnUiThread {
            @Suppress("DEPRECATION")
            compose.activity.onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        }
        settle()
        val opened = generateSequence { shadowOf(ctx).nextStartedActivity }.toList()
        val page = assertNotNull(opened.firstOrNull { it.action == android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS }, opened.map { it.action }.toString())
        assertEquals(ctx.packageName, page.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
    }
}
