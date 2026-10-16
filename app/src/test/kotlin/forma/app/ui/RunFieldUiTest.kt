package forma.app.ui

import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.AppJson
import forma.app.data.FormaDatabase
import forma.app.data.RunEntity
import forma.app.data.RunSummary
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.run.LapKey
import forma.app.run.RunSession
import forma.app.ui.screens.HistoryItemScreen
import forma.app.ui.screens.RunActiveScreen
import forma.app.ui.screens.RunDoneScreen
import forma.app.ui.screens.RunManualScreen
import forma.app.ui.screens.RunSetupScreen
import forma.app.ui.screens.SAVE_FAILED
import forma.app.ui.screens.boardDistance
import forma.app.ui.screens.deniedWarning
import forma.app.ui.screens.formLine
import forma.app.ui.screens.runResult
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
import forma.core.run.RunExplain
import forma.core.run.RunTracker
import forma.core.run.PaceRange
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.Segment
import forma.core.run.SegmentKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Denis between the gym and the stadium, on a Redmi: the run screens where the field review found trouble. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class RunFieldUiTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    @After fun tearDown() {
        RunSession.clear()
        LapKey.onLap = null
    }

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun texts(substring: String): List<String> =
        compose.onAllNodesWithText(substring, substring = true).fetchSemanticsNodes()
            .map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: n.config.getOrNull(SemanticsProperties.EditableText)?.text ?: "" }

    private val denis = Profile(
        name = "Денис", aim = Aim(LiftFocus.MUSCLE, RunFocus.SPEED, Emphasis.BALANCED), experience = Experience.INTERMEDIATE,
        runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), bodyWeightKg = 80.0, birthYear = 2008, doubleDays = true,
        runGoal = RunGoal(1500, 335), lapMeters = 230,
    )

    /** [day]: the clock fixed to that day (a plan that depends on the day of the week); null — the real clock. */
    private fun repo(name: String, day: java.time.LocalDate? = null): TrainingRepository {
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        val now: () -> Long = day?.let { d -> { d.atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() } } ?: System::currentTimeMillis
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now).also { runBlocking { it.completeOnboarding(denis) } }
    }

    private fun show(repo: TrainingRepository, nav: Navigator = Navigator(Screen.Today), settings: Settings = Settings(), content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav, LocalSettings provides settings) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { content() } }
            }
        }
        settle()
    }

    private fun type(label: String, text: String) {
        val field = compose.onAllNodesWithText(label, substring = true)[0]
        field.performScrollTo()
        val edits = compose.onAllNodes(hasSetTextAction())
        val all = edits.fetchSemanticsNodes()
        val labelTop = field.fetchSemanticsNode().boundsInRoot.top
        edits[all.indexOfFirst { it.boundsInRoot.top > labelTop }].performTextReplacement(text)
        settle()
    }

    private fun click(text: String) {
        compose.onAllNodesWithText(text)[0].performScrollTo().performClick()
        settle()
    }

    /** A button in a dialog: nothing to scroll to. */
    private fun tap(text: String) {
        compose.onAllNodesWithText(text)[0].performClick()
        settle()
    }

    private fun runs(repo: TrainingRepository) = runBlocking { repo.db.workouts().allDone().filter { it.kind == "RUN" } }

    @Test fun aGymSessionStillOpenIsOfferedToFinishBeforeTheRun() {
        val repo = repo("ui-field-gym")
        val lift = runBlocking { repo.startLift(repo.store.programNow()!!.templates.first().id) }
        val easy = runBlocking { repo.runFor(SessionKind.RUN_EASY, repo.today()) }
        val nav = Navigator(Screen.Today)
        show(repo, nav) { RunSetupScreen(easy, SessionKind.RUN_EASY) }
        click("Старт")
        assertTrue(texts("Силовая ещё открыта").isNotEmpty())
        assertTrue(RunSession.tracker == null)
        tap("Закончить")
        assertEquals(Screen.Workout(lift), nav.current)
    }

    @Test fun theBatteryCardOnlyWhileTheAppIsRestricted() {
        val repo = repo("ui-field-battery")
        val easy = runBlocking { repo.runFor(SessionKind.RUN_EASY, repo.today()) }
        val power = shadowOf(ctx.getSystemService(PowerManager::class.java))
        power.setIgnoringBatteryOptimizations(ctx.packageName, false)
        var round by mutableStateOf(0)
        show(repo) { key(round) { RunSetupScreen(easy, SessionKind.RUN_EASY) } }
        assertTrue(texts("усыпить таймер").isNotEmpty())
        // Allowed (back from the system's request): no card.
        power.setIgnoringBatteryOptimizations(ctx.packageName, true)
        round++
        settle()
        assertTrue(texts("усыпить таймер").isEmpty())
    }

    @Test fun aFailedSaveKeepsTheRunAndSaysSo() {
        val repo = repo("ui-field-savefail")
        val easy = runBlocking { repo.runFor(SessionKind.RUN_EASY, repo.today()) }
        val t = RunTracker(easy)
        t.start(0)
        t.finish(easy.totalSeconds * 1000L)
        RunSession.tracker = t
        RunSession.gpsEnabled = true
        RunSession.publish(easy.totalSeconds * 1000L)
        show(repo) { RunDoneScreen(0) }
        click("4")
        // The database refuses the run.
        repo.db.openHelper.writableDatabase.execSQL("DROP TABLE run_entry")
        click("Сохранить")
        assertTrue(texts(SAVE_FAILED).isNotEmpty())
        assertTrue(RunSession.tracker === t)
    }

    @Test fun aTreadmillRunTakesTheDistanceFromItsDisplay() {
        val repo = repo("ui-field-treadmill")
        val road = runBlocking { repo.runFor(SessionKind.RUN_EASY, repo.today(), road = true) }
        val t = RunTracker(road)
        t.start(0)
        t.finish(30 * 60_000L)
        RunSession.tracker = t
        RunSession.gpsEnabled = false
        RunSession.publish(30 * 60_000L)
        show(repo) { RunDoneScreen(0) }
        type("Дистанция с табло", "5,2")
        click("4")
        click("Сохранить")
        val saved = runs(repo).single()
        assertEquals(5200.0, runBlocking { repo.db.runs().get(saved.id)!! }.distanceM!!, 1e-6)
    }

    @Test fun withoutGpsTheStepsGiveTheDistanceOnTheRunScreen() {
        val repo = repo("ui-field-steps")
        val road = runBlocking { repo.runFor(SessionKind.RUN_EASY, repo.today(), road = true) }
        val t = RunTracker(road)
        t.setStride(0.2, 0.0055)
        t.start(0)
        for (sec in 0..60) t.onSteps(1000L + 3 * sec, sec * 1000L)
        assertTrue(t.distanceM > 100)
        RunSession.tracker = t
        RunSession.gpsEnabled = false
        RunSession.publish(60_000)
        show(repo) { RunActiveScreen() }
        assertTrue(texts("км по шагам").isNotEmpty())
        assertTrue(texts("дистанция по шагам").isNotEmpty())
        // A road run: the volume key is the volume, the headset's button the music's.
        assertNull(LapKey.onLap)
        assertTrue(texts("кнопкой гарнитуры").isEmpty())
    }

    @Test fun onTheStadiumTheVolumeKeyPressesLap() {
        val repo = repo("ui-field-key")
        val easy = runBlocking { repo.runFor(SessionKind.RUN_EASY, repo.today()) }
        assertEquals(230, easy.lapMeters)
        val t = RunTracker(easy)
        t.start(0)
        RunSession.tracker = t
        RunSession.gpsEnabled = false
        RunSession.publish(60_000)
        show(repo) { RunActiveScreen() }
        assertNotNull(LapKey.onLap)
        // The volume key works only while the screen is on, the headset's button with it off too: said on the screen,
        // not found out on the line.
        assertTrue(texts("Круг — кнопкой гарнитуры; громкостью вниз — пока экран включён").isNotEmpty())
        assertTrue(LapKey.down(KeyEvent.KEYCODE_VOLUME_DOWN, null))
        assertTrue(LapKey.up(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(!LapKey.down(KeyEvent.KEYCODE_VOLUME_UP, null))
        // Paused: no lap to press, the key is the volume again.
        t.pause(61_000)
        RunSession.publish(61_000)
        settle()
        assertNull(LapKey.onLap)
    }

    @Test fun aStartThatFailedIsSaidNotSkipped() {
        val repo = repo("ui-field-starterror")
        RunSession.clear()
        RunSession.failStart(forma.app.run.RunService.START_REFUSED)
        val nav = Navigator(Screen.Today).also { it.push(Screen.RunActive) }
        show(repo, nav) { RunActiveScreen() }
        compose.mainClock.advanceTimeBy(2_000)
        settle()
        assertTrue(texts("Телефон не дал запустить пробежку").isNotEmpty())
        assertEquals(Screen.RunActive, nav.current)
    }

    @Test fun lapsFromAWatchAreSavedAsLaps() {
        // A Tuesday: the legs (Thursday) far behind — race pace is due today, not put off to a fresher day.
        val repo = repo("ui-field-watch", java.time.LocalDate.of(2026, 10, 13))
        val w = runBlocking {
            repo.store.setRunState(repo.store.runStateNow().copy(vdot = 46.0, runs = 6, goalStage = 2, lastTestDay = repo.today().toEpochDay() - 10))
            repo.runFor(SessionKind.RUN_QUALITY, repo.today())
        }
        assertEquals(RunType.GOAL_PACE, w.type)
        val n = w.segments.filter { it.kind == SegmentKind.WORK }.sumOf { ceil(it.laps!!).toInt() }
        show(repo) { RunManualScreen(w) }
        type("каждый круг", List(n) { if (it % 2 == 0) "52" else "53" }.joinToString(" "))
        assertTrue(texts("средний в отрезках").isNotEmpty(), texts("круг").toString())
        // The effort chip (the title may hold a 7 too: "7 × 1 круг").
        compose.onAllNodes(androidx.compose.ui.test.hasText("7") and androidx.compose.ui.test.hasClickAction())[0].performScrollTo().performClick()
        settle()
        click("Сохранить")
        val saved = runs(repo).single()
        val summary = AppJson.decodeFromString(RunSummary.serializer(), saved.summaryJson!!)
        assertEquals(n, summary.laps.size)
        assertTrue(summary.laps.all { it.rep != null })
        assertNotNull(runBlocking { repo.db.runs().get(saved.id)!! }.workPace)
    }

    @Test fun whatItGivesTheBodyFoldsAfterThreeReadings() {
        val repo = repo("ui-field-body")
        val easy = runBlocking { repo.runFor(SessionKind.RUN_EASY, repo.today()) }
        val t = RunTracker(easy)
        t.start(0)
        t.finish(easy.totalSeconds * 1000L)
        val id = runBlocking { repo.saveRun(easy, t.result(easy.totalSeconds * 1000L, 4), System.currentTimeMillis(), emptyList()) }.first
        val body = RunExplain.of(easy, denis.runGoal).body
        var settings by mutableStateOf(Settings())
        var round by mutableStateOf(0)
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides settings) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { key(round) { HistoryItemScreen(id) } } }
            }
        }
        settle()
        assertTrue(texts(body).isNotEmpty())
        assertEquals(1, runBlocking { repo.store.settingsNow() }.bodySeen[RunType.EASY.name])
        // Read three times before: folded, a tap opens it.
        settings = Settings(bodySeen = mapOf(RunType.EASY.name to 3))
        round++
        settle()
        assertTrue(texts("Что это даёт телу ›").isNotEmpty())
        assertTrue(texts(body).isEmpty())
        click("Что это даёт телу ›")
        assertTrue(texts(body).isNotEmpty())
    }

    @Test fun theLockHoldsThroughATurnOfThePhone() {
        val repo = repo("ui-field-lock")
        val easy = runBlocking { repo.runFor(SessionKind.RUN_EASY, repo.today()) }
        val t = RunTracker(easy)
        t.start(0)
        RunSession.tracker = t
        RunSession.gpsEnabled = false
        RunSession.publish(60_000)
        val tester = StateRestorationTester(compose)
        tester.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { RunActiveScreen() } }
            }
        }
        settle()
        compose.onNodeWithContentDescription("Заблокировать экран").performClick()
        settle()
        tester.emulateSavedInstanceStateRestore()
        settle()
        assertTrue(texts("Заблокировано всё, кроме «Круг»").isNotEmpty())
    }

    @Test fun whatItGivesTheBodyIsCountedOncePerRun() {
        val repo = repo("ui-field-body-once")
        val easy = runBlocking { repo.runFor(SessionKind.RUN_EASY, repo.today()) }
        val t = RunTracker(easy)
        t.start(0)
        t.finish(easy.totalSeconds * 1000L)
        val id = runBlocking { repo.saveRun(easy, t.result(easy.totalSeconds * 1000L, 4), System.currentTimeMillis(), emptyList()) }.first
        val tester = StateRestorationTester(compose)
        tester.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { HistoryItemScreen(id) } }
            }
        }
        settle()
        assertEquals(1, runBlocking { repo.store.settingsNow() }.bodySeen[RunType.EASY.name])
        // A turn of the phone is the same reading.
        tester.emulateSavedInstanceStateRestore()
        settle()
        assertEquals(1, runBlocking { repo.store.settingsNow() }.bodySeen[RunType.EASY.name])
    }

    @Test fun onXiaomiTheAutostartStaysOfferedUntilItIsOn() {
        org.robolectric.shadows.ShadowBuild.setManufacturer("Xiaomi")
        val repo = repo("ui-field-autostart")
        val easy = runBlocking { repo.runFor(SessionKind.RUN_EASY, repo.today()) }
        shadowOf(ctx.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(ctx.packageName, true)
        var round by mutableStateOf(0)
        show(repo) { key(round) { RunSetupScreen(easy, SessionKind.RUN_EASY) } }
        // The battery is free already; the autostart, which brings a closed run back, is still offered.
        assertTrue(texts("усыпить таймер").isEmpty())
        assertTrue(texts("Включи «Форме» автозапуск").isNotEmpty())
        compose.onAllNodesWithText("Уже включён", ignoreCase = true)[0].performScrollTo().performClick()
        settle()
        assertTrue(texts("Включи «Форме» автозапуск").isEmpty())
        round++
        settle()
        assertTrue(texts("Включи «Форме» автозапуск").isEmpty())
        // Another phone: nothing about autostart.
        org.robolectric.shadows.ShadowBuild.reset()
        ctx.getSharedPreferences("forma_run", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        round++
        settle()
        assertTrue(texts("автозапуск").isEmpty())
    }

    @Test fun aRunsResultShowsTheRepsLapAndTheRoadsPace() {
        val goal = RunWorkout(
            RunType.GOAL_PACE, "6 × 2 круга",
            listOf(Segment(SegmentKind.WARMUP, 720), Segment(SegmentKind.WORK, 101, PaceRange(218, 222), laps = 2.0, lapSec = 50.6), Segment(SegmentKind.COOLDOWN, 600)),
            lapMeters = 230,
        )
        fun entity(w: RunWorkout, avgPace: Double, workPace: Double?) =
            RunEntity(1, w.type.name, AppJson.encodeToString(RunWorkout.serializer(), w), 1.0, 1800, 4000.0, avgPace, null, workPace, null)
        // The session's average (the warm-up and the rests in it) would make a 75-second lap of the 50-second ones.
        assertEquals("4,00 км · 30:00 · круг 50,6 с", runResult(entity(goal, 326.0, 220.0), 230))
        // On the road: the pace a km, not a lap of the stadium in the profile.
        val road = RunWorkout(RunType.EASY, "Лёгкий бег", listOf(Segment(SegmentKind.RUN, 1800, PaceRange(330, 360))))
        assertEquals("4,00 км · 30:00 · 7:30 /км", runResult(entity(road, 450.0, 450.0), 230))
    }

    @Test fun theWordsAroundTheRun() {
        assertEquals("VDOT после пробежки: 46,2", formLine(46.2, null))
        val line = formLine(46.2, RunGoal(1500, 335))
        assertTrue(line.startsWith("VDOT после пробежки: 46,2 → прогноз на 1,5 км — 5:"), line)
        assertTrue(formLine(40.0, RunGoal(10_000, 2700)).contains("на 10 км"))
        assertTrue(formLine(40.0, RunGoal(21_097, 6300)).contains("на 21,1 км"))
        assertNull(deniedWarning(gpsWanted = true, location = true, notifications = true))
        assertTrue(deniedWarning(gpsWanted = true, location = false, notifications = true)!!.contains("геопозиции"))
        assertNull(deniedWarning(gpsWanted = false, location = false, notifications = true))
        assertTrue(deniedWarning(gpsWanted = false, location = true, notifications = false)!!.contains("уведомлений"))
        assertEquals(5200.0, boardDistance("5,2", 30 * 60)!!, 1e-9)
        // 52 km in half an hour is no run.
        assertNull(boardDistance("52", 30 * 60))
        assertNull(boardDistance("", 30 * 60))
    }
}
