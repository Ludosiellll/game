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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.AppJson
import forma.app.data.FormaDatabase
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
import forma.core.run.RunType
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

    private fun repo(name: String): TrainingRepository {
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store).also { runBlocking { it.completeOnboarding(denis) } }
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
        // A road run: the volume key is the volume.
        assertNull(LapKey.onLap)
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
        val repo = repo("ui-field-watch")
        val w = runBlocking {
            repo.store.setRunState(repo.store.runStateNow().copy(vdot = 46.0, runs = 6, goalStage = 2, lastTestDay = repo.today().toEpochDay() - 10))
            repo.runFor(SessionKind.RUN_QUALITY, repo.today())
        }
        assertEquals(RunType.GOAL_PACE, w.type)
        val n = w.segments.filter { it.kind == SegmentKind.WORK }.sumOf { ceil(it.laps!!).toInt() }
        show(repo) { RunManualScreen(w) }
        type("каждый круг", List(n) { if (it % 2 == 0) "52" else "53" }.joinToString(" "))
        assertTrue(texts("средний в отрезках").isNotEmpty(), texts("круг").toString())
        compose.onNodeWithText("7").performScrollTo().performClick()
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

    @Test fun theWordsAroundTheRun() {
        assertEquals("Форма (VDOT): 46,2", formLine(46.2, null))
        val line = formLine(46.2, RunGoal(1500, 335))
        assertTrue(line.startsWith("Форма (VDOT): 46,2 → прогноз на 1500 м — 5:"), line)
        assertTrue(formLine(40.0, RunGoal(10_000, 2700)).contains("на 10 км"))
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
