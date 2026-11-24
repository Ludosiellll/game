package forma.app.ui

import android.Manifest
import android.location.LocationManager
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.AppJson
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.Status
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.app.run.RunService
import forma.app.run.RunSession
import forma.app.ui.screens.RunDoneScreen
import forma.app.ui.screens.RunSetupScreen
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
import forma.core.run.PaceRange
import forma.core.run.Press
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.Segment
import forma.core.run.SegmentKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The run's own screens: the finish screen's numbers, «Продолжить пробежку» around the save, a stadium run on the road. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class AuditFixRunAppUiTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() = RunSession.clear()

    @After fun tearDown() = RunSession.clear()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun texts(s: String) = compose.onAllNodesWithText(s, substring = true, ignoreCase = true).fetchSemanticsNodes()

    private fun click(text: String) {
        compose.onAllNodesWithText(text, ignoreCase = true)[0].performScrollTo().performClick()
        settle()
    }

    private fun reopenOffered() = compose.onAllNodesWithText("Продолжить пробежку", ignoreCase = true).fetchSemanticsNodes().isNotEmpty()

    private fun show(repo: TrainingRepository, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { content() } }
            }
        }
        settle()
    }

    private fun repo(name: String): TrainingRepository {
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store).also {
            runBlocking {
                it.completeOnboarding(
                    Profile(
                        name = "Денис", goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE,
                        trainingDays = setOf(1, 3, 5), bodyWeightKg = 80.0, birthYear = 1995,
                    ),
                )
            }
        }
    }

    /** Ten minutes run, then «Стоп»: on its finish screen, not saved yet. */
    private fun stopped(w: RunWorkout): RunTracker {
        val t = RunTracker(w)
        t.start(0)
        t.finish(600_000)
        RunSession.tracker = t
        RunSession.startedAtWall = System.currentTimeMillis() - 700_000
        RunSession.gpsEnabled = false
        RunSession.publish(600_000)
        return t
    }

    /**
     * run-06: an easy run on the stadium with GPS on, 20 laps pressed (4,10 km by the laps) in 25:00, GPS 3,80 km (the
     * bends cut, a gap in the fixes). The finish screen's pace is that of the distance and the time beside it, as the run
     * is saved and «История» shows it: 6:06, not 6:35 from the GPS metres.
     */
    @Test fun theFinishScreensPaceIsThatOfTheDistanceShown() {
        val repo = repo("audit-e-pace")
        val easy = RunWorkout(RunType.EASY, "Лёгкий бег", listOf(Segment(SegmentKind.RUN, 25 * 60, PaceRange(330, 360))), lapMeters = 205)
        val t = RunTracker(easy)
        t.start(0)
        assertEquals(Press.Start, t.press(1_000))
        for (k in 1..20) assertIs<Press.Done>(t.press(1_000 + k * 74_950L))
        t.finish(1_500_000)
        // GPS measured 3,80 km of it.
        val done = RunTracker.restore(t.snapshot(1_500_000).copy(distanceM = 3800.0), 0)
        assertEquals(4100.0, assertNotNull(done.result(0, 0).distanceM), 1e-6)
        RunSession.tracker = done
        RunSession.gpsEnabled = true
        RunSession.publish(0)
        show(repo) { RunDoneScreen(0) }
        assertTrue(texts("4,10").isNotEmpty(), "the distance by the laps")
        assertTrue(texts("6:06").isNotEmpty(), "the pace of 4,10 km in 25:00")
        assertTrue(texts("6:35").isEmpty(), "not the pace of GPS's 3,80 km")
    }

    /**
     * run-04: «Сохранить» tapped on a long run (thousands of GPS points take a moment to store). While it is saved,
     * «Продолжить пробежку» is not there: the run cannot go back to the run screen and be cleared under it when the save
     * ends.
     */
    @Test fun whileTheRunIsSavedItCannotGoOn() {
        val repo = repo("audit-e-saving")
        val t = stopped(runBlocking { repo.runFor(SessionKind.RUN_EASY) })
        show(repo) { RunDoneScreen(0) }
        assertTrue(reopenOffered(), "offered before the save")
        click("4")
        // The database busy for a while: the save waits for it.
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val busy = thread { runBlocking { repo.db.withTransaction { entered.complete(Unit); gate.await() } } }
        try {
            runBlocking { withTimeout(10_000) { entered.await() } }
            click("Сохранить")
            assertTrue(texts("Сохраняю").isNotEmpty(), "the save is under way")
            assertFalse(reopenOffered())
            assertEquals(RunTracker.State.FINISHED, t.state)
        } finally {
            gate.complete(Unit)
            busy.join(10_000)
        }
        compose.waitUntil(10_000) { settle(); RunSession.tracker == null }
        assertTrue(texts("Сохранено").isNotEmpty())
    }

    /**
     * run-04: the save was cut off after the run was stored (the phone turned while «Сохраняю…» showed), and the finish
     * screen is back with the run in history already. It is not offered to go on: a save of the same start returns the
     * run stored, and what was run after it would be lost.
     */
    @Test fun aRunInHistoryAlreadyCannotGoOn() {
        val repo = repo("audit-e-stored")
        val easy = runBlocking { repo.runFor(SessionKind.RUN_EASY) }
        val t = stopped(easy)
        runBlocking { repo.saveRun(easy, t.result(600_000, 4), RunSession.startedAtWall, emptyList()) }
        show(repo) { RunDoneScreen(0) }
        assertTrue(texts("Насколько тяжело было").isNotEmpty(), "the finish screen, not saved on it")
        assertFalse(reopenOffered())
    }

    private val monday = LocalDate.of(2026, 11, 2)
    private val wednesday = monday.plusDays(2)

    /**
     * Denis's week (as in RunTodayTest), on Wednesday: Monday's hard run missed, the gym done on Tuesday, a hard run again on
     * Thursday, the lower body and an easy run on Saturday; a stadium of 205 m in the profile.
     */
    private fun denisWeek(name: String): TrainingRepository {
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        var clock = wednesday.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now = { clock++ })
        runBlocking {
            repo.completeOnboarding(
                Profile(
                    goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                    bodyWeightKg = 71.0, runGoal = RunGoal(1500, 330), lapMeters = 205,
                    aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
                ),
            )
            val p = store.programNow()!!
            val t = p.templates.first()
            store.setProgram(
                p.copy(
                    templates = listOf(
                        t.copy(id = "upper_b", title = "Верх · Б", focus = Focus.UPPER), t.copy(id = "upper_a", title = "Верх · А", focus = Focus.UPPER),
                        t.copy(id = "lower_a", title = "Низ", focus = Focus.LOWER),
                    ),
                    createdEpochDay = monday.minusWeeks(3).toEpochDay(),
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
            val at = monday.plusDays(1).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            repo.db.workouts().insert(
                WorkoutEntity(
                    epochDay = monday.plusDays(1).toEpochDay(), kind = Kind.LIFT, templateId = "upper_b", title = "upper_b", startedAt = at,
                    finishedAt = at + 1_800_000, status = Status.DONE, rpe = 4, durationSec = 1800, load = 120.0,
                ),
            )
        }
        return repo
    }

    /** «Старт» with every permission given and the location on: the workout the run service is started with. */
    private fun startRun(): RunWorkout {
        shadowOf(ctx).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACTIVITY_RECOGNITION,
        )
        shadowOf(ctx.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        shadowOf(ctx).clearStartedServices()
        click("Старт")
        val start = shadowOf(ctx).allStartedServices.last { it.action == RunService.ACTION_START }
        return AppJson.decodeFromString(RunWorkout.serializer(), assertNotNull(start.getStringExtra(RunService.EXTRA_WORKOUT)))
    }

    /**
     * road-run-loses-instead: Monday's hard run missed; on Wednesday «Сегодня» offers it as an easy run, and he runs it on
     * the road («Улица» on the setup screen). The run started is still the easy run for Monday's: saved, it closes Monday,
     * and Saturday's easy run, the only one of his week, stays ahead.
     */
    @Test fun anEasyRunForAMissedOneRunOnTheRoadStillCountsForIt() {
        val repo = denisWeek("audit-e-instead")
        assertTrue(assertNotNull(runBlocking { repo.catchUp(wednesday) }).easy)
        val stadium = runBlocking { repo.runFor(SessionKind.RUN_EASY, wednesday) }.copy(instead = true)
        assertEquals(205, stadium.lapMeters)
        show(repo) { RunSetupScreen(stadium, SessionKind.RUN_EASY) }
        click("Улица")
        val started = startRun()
        assertNull(started.lapMeters, "on the road")
        assertTrue(started.instead, "still the easy run for Monday's hard one")
        // Run, and saved as the finish screen saves it.
        val t = RunTracker(started).apply { start(0); finish(started.totalSeconds * 1000L) }
        val summary = runBlocking { repo.saveRun(started, t.result(started.totalSeconds * 1000L, 4), repo.nowMs(), emptyList(), wednesday) }.second
        assertTrue(summary.instead)
        val week = runBlocking { repo.weekFlow(monday).first() }
        assertTrue(week.first { it.date == monday }.isDone(PlannedSession(SessionKind.RUN_QUALITY)), "Monday's hard run is done (easier)")
        assertFalse(week.first { it.date == monday.plusDays(5) }.isDone(PlannedSession(SessionKind.RUN_EASY)), "Saturday's easy run is still ahead")
    }

    /** road-run-loses-instead: the coach's easy run of 20 minutes for today, run on the road: still 20 minutes, not the plan's. */
    @Test fun theCoachsEasyRunOnTheRoadKeepsItsMinutes() {
        val repo = denisWeek("audit-e-minutes")
        val stadium = runBlocking { repo.runFor(SessionKind.RUN_EASY, wednesday, minutes = 20) }
        val planned = runBlocking { repo.runFor(SessionKind.RUN_EASY, wednesday, road = true) }
        assertTrue(planned.totalSeconds != stadium.totalSeconds, "the plan's easy run is another length: ${planned.totalSeconds} s")
        show(repo) { RunSetupScreen(stadium, SessionKind.RUN_EASY, minutes = 20) }
        click("Улица")
        val started = startRun()
        assertNull(started.lapMeters, "on the road")
        assertEquals(stadium.totalSeconds, started.totalSeconds)
    }
}
