package forma.app.run

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.NotificationManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.run.PaceRange
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.Segment
import forma.core.run.SegmentKind
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowTextToSpeech
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * HyperOS closes the app mid-run with the screen off. The system brings the run service back: the run goes on, the time
 * missed filled from the step counter; when the foreground is refused the runner is told by a loud notification.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RunRestartTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val easy = RunWorkout(RunType.EASY, "Лёгкий бег", listOf(Segment(SegmentKind.RUN, 40 * 60, PaceRange(330, 360))))

    @After fun tearDown() {
        RunSession.clear()
    }

    /** A run five minutes in, saved [ago] ms before now with the step counter at 5000; the process gone since. */
    private fun killedRun(ago: Long): RunStore {
        RunSession.clear()
        val store = RunStore(File(app.filesDir, "killed-${System.nanoTime()}").apply { mkdirs() })
        val t = RunTracker(easy)
        t.start(0)
        store.saveState(
            SavedRun(
                t.snapshot(300_000), System.currentTimeMillis() - 300_000 - ago, gps = false,
                savedAtWall = System.currentTimeMillis() - ago, stepTotal = 5000,
            ),
        )
        RunSession.init(store, SystemClock.elapsedRealtime())
        return store
    }

    private fun alert() = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(RunAlerts.INTERRUPTED_ID)

    @Test fun theRunGoesOnAndTheGapIsFilledByTheSteps() {
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val counter = ShadowSensor.newInstance(Sensor.TYPE_STEP_COUNTER)
        val sensors = shadowOf(app.getSystemService(SensorManager::class.java))
        sensors.addSensor(counter)
        killedRun(ago = 20_000)
        assertTrue(RunSession.interrupted())
        val service = Robolectric.buildService(RunService::class.java).create().get()
        service.onStartCommand(null, 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        val t = assertNotNull(RunSession.tracker)
        assertEquals(RunTracker.State.RUNNING, t.state)
        assertTrue(!RunSession.interrupted())
        assertNotNull(shadowOf(service).lastForegroundNotification)
        val before = t.activeMs(SystemClock.elapsedRealtime())
        // 48 steps while the app was gone: 18 of the 20 seconds at the cadence assumed for a run.
        sensors.sendSensorEventToListeners(SensorEventBuilder.newBuilder(counter, floatArrayOf(5048f)).build())
        val added = t.activeMs(SystemClock.elapsedRealtime()) - before
        assertTrue(added in 17_000..19_000, "$added")
        assertEquals(48L, t.steps)
        // The next save keeps the moment and the counter.
        RunSession.save(SystemClock.elapsedRealtime())
        assertNull(alert())
        service.onDestroy()
    }

    @Test fun theSavedRunKeepsItsMomentAndTheStepCounter() {
        val store = killedRun(ago = 1_000)
        val t = assertNotNull(RunSession.tracker)
        t.resume(SystemClock.elapsedRealtime())
        t.onSteps(7000, SystemClock.elapsedRealtime())
        RunSession.save(SystemClock.elapsedRealtime())
        val saved = assertNotNull(store.load()).first
        assertEquals(7000L, saved.stepTotal)
        assertTrue(System.currentTimeMillis() - saved.savedAtWall < 5_000)
    }

    @Test fun refusedTheForegroundTheRunnerIsTold() {
        killedRun(ago = 20_000)
        val controller = Robolectric.buildService(RunService::class.java).create()
        shadowOf(controller.get()).setThrowInStartForeground(IllegalStateException("not allowed from the background"))
        controller.get().onStartCommand(null, 0, 1)
        val n = assertNotNull(alert())
        assertEquals(RunAlerts.TITLE, n.extras.getCharSequence("android.title").toString())
        // The run stays on disk and in the app, paused where it was saved.
        assertEquals(RunTracker.State.PAUSED, RunSession.tracker?.state)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test fun noServiceAfterTheProcessStartedTheRunnerIsTold() {
        killedRun(ago = 20_000)
        // The process started for something else (a sync in the background), not for the screen.
        val am = app.getSystemService(ActivityManager::class.java)
        shadowOf(am).setProcesses(
            listOf(
                ActivityManager.RunningAppProcessInfo().apply {
                    pid = android.os.Process.myPid()
                    processName = app.packageName
                    importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
                },
            ),
        )
        RunAlerts.checkNow(app)
        assertEquals(RunAlerts.TITLE, assertNotNull(alert()).extras.getCharSequence("android.title").toString())
        // A run paused by hand when the app was closed is nothing to be told about.
        RunSession.clear()
        app.getSystemService(NotificationManager::class.java).cancelAll()
        val store = RunStore(File(app.filesDir, "paused-${System.nanoTime()}").apply { mkdirs() })
        val t = RunTracker(easy)
        t.start(0)
        t.pause(100_000)
        store.saveState(SavedRun(t.snapshot(120_000), System.currentTimeMillis(), gps = false, savedAtWall = System.currentTimeMillis()))
        RunSession.init(store, SystemClock.elapsedRealtime())
        RunAlerts.checkNow(app)
        assertNull(alert())
    }

    /** The process gone at 18:30, the run finished on the watch, the system brings the service back at 20:00. */
    @Test fun aRunBroughtBackHoursLaterStaysAsSaved() {
        killedRun(ago = 90 * 60_000L)
        val controller = Robolectric.buildService(RunService::class.java).create()
        controller.get().onStartCommand(null, 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        val t = assertNotNull(RunSession.tracker)
        // Not going on: paused where it was saved, waiting in the app to be finished or saved; no foreground, no voice.
        assertEquals(RunTracker.State.PAUSED, t.state)
        assertEquals(300_000, t.activeMs(SystemClock.elapsedRealtime()))
        assertTrue(RunSession.interrupted())
        assertNull(shadowOf(controller.get()).lastForegroundNotification)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals(RunAlerts.TITLE, assertNotNull(alert()).extras.getCharSequence("android.title").toString())
    }

    @Test fun theNoticeThatTheRunStoppedSoundsOnce() {
        killedRun(ago = 20_000)
        val controller = Robolectric.buildService(RunService::class.java).create()
        shadowOf(controller.get()).setThrowInStartForeground(IllegalStateException("not allowed from the background"))
        controller.get().onStartCommand(null, 0, 1)
        val first = assertNotNull(alert())
        assertTrue(first.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
        // The check a few seconds after the process started finds it up already: not posted again.
        val am = app.getSystemService(ActivityManager::class.java)
        shadowOf(am).setProcesses(
            listOf(
                ActivityManager.RunningAppProcessInfo().apply {
                    pid = android.os.Process.myPid()
                    processName = app.packageName
                    importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
                },
            ),
        )
        RunAlerts.checkNow(app)
        assertSame(first, alert())
    }

    @Test fun aLapTimeCutsInFrontOfALongSentence() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("ru-RU"))
        val coach = VoiceCoach(app, voice = true, beeps = false, vibration = false)
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        coach.speak("Сверх плана 5 минут. «Стоп», когда хватит.")
        assertEquals(TextToSpeech.QUEUE_ADD, tts.queueMode)
        coach.speak("52 секунды. В темпе.", now = true)
        assertEquals(TextToSpeech.QUEUE_FLUSH, tts.queueMode)
        assertEquals("52 секунды. В темпе.", tts.lastSpokenText)
        coach.release()
    }
}
