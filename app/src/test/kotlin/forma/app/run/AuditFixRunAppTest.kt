package forma.app.run

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationManager
import android.os.SystemClock
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
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HyperOS closed the app mid-run and did not bring it back. The loud «Пробежка прервалась» is for telling the runner
 * once: not again with every later start of the app in the background, and not for a run he had paused himself.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AuditFixRunAppTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val easy = RunWorkout(RunType.EASY, "Лёгкий бег", listOf(Segment(SegmentKind.RUN, 40 * 60, PaceRange(330, 360))))

    @After fun tearDown() {
        RunSession.clear()
        app.getSystemService(NotificationManager::class.java).cancelAll()
    }

    private fun alert() = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(RunAlerts.INTERRUPTED_ID)

    private fun store(name: String) = RunStore(File(app.filesDir, "$name-${System.nanoTime()}").apply { mkdirs() })

    /** The process started for something else (a sync, the messages check), not for the screen. */
    private fun inTheBackground() {
        shadowOf(app.getSystemService(ActivityManager::class.java)).setProcesses(
            listOf(
                ActivityManager.RunningAppProcessInfo().apply {
                    pid = android.os.Process.myPid()
                    processName = app.packageName
                    importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
                },
            ),
        )
    }

    /** The next start of the process: nothing in memory, the run read from disk as the app's start reads it. */
    private fun nextStart(store: RunStore) {
        RunSession.tracker = null
        RunSession.init(store, SystemClock.elapsedRealtime())
    }

    /**
     * run-02: the run closed with the app 20 minutes ago, five minutes in. A sync starts the process in the background:
     * the loud notice. He swipes it away and leaves the run to save later; the messages check starts the process again
     * (every 15 minutes, at night too): no notice this time. Carried on later and closed by the system again, it is a new
     * stop: told again.
     */
    @Test fun theNoticeThatTheRunStoppedComesOncePerStop() {
        RunSession.clear()
        val store = store("once")
        val t = RunTracker(easy)
        t.start(0)
        val started = System.currentTimeMillis() - 25 * 60_000L
        store.saveState(SavedRun(t.snapshot(300_000), started, gps = false, savedAtWall = System.currentTimeMillis() - 20 * 60_000L, stepTotal = 5000))
        RunSession.init(store, SystemClock.elapsedRealtime())
        inTheBackground()
        RunAlerts.checkNow(app)
        assertEquals(RunAlerts.TITLE, assertNotNull(alert()).extras.getCharSequence("android.title").toString())
        // Swiped away.
        app.getSystemService(NotificationManager::class.java).cancelAll()
        nextStart(store)
        assertTrue(RunSession.interrupted(), "the run on disk was still going when the app was closed")
        RunAlerts.checkNow(app)
        assertNull(alert(), "told once already")
        // He carried it on (the service saves it as it goes), and the system closed the app again.
        store.saveState(SavedRun(t.snapshot(900_000), started, gps = false, savedAtWall = System.currentTimeMillis() - 60_000L, stepTotal = 6500))
        nextStart(store)
        RunAlerts.checkNow(app)
        assertNotNull(alert(), "a new stop of the run")
    }

    /**
     * run-02: a run he paused himself when the app was closed; the system brings the run service back and refuses it the
     * foreground. Nothing stopped behind his back: no loud notice (as for the check at the start and past 30 minutes).
     */
    @Test fun aRunPausedByHandIsNotToldWhenItsRestartIsRefused() {
        RunSession.clear()
        val store = store("paused")
        val t = RunTracker(easy)
        t.start(0)
        t.pause(100_000)
        store.saveState(SavedRun(t.snapshot(120_000), System.currentTimeMillis() - 200_000L, gps = false, savedAtWall = System.currentTimeMillis() - 60_000L))
        RunSession.init(store, SystemClock.elapsedRealtime())
        assertFalse(RunSession.interrupted())
        val controller = Robolectric.buildService(RunService::class.java).create()
        shadowOf(controller.get()).setThrowInStartForeground(IllegalStateException("not allowed from the background"))
        controller.get().onStartCommand(null, 0, 1)
        assertNull(alert())
        // Still there, paused, for the app.
        assertEquals(RunTracker.State.PAUSED, RunSession.tracker?.state)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }
}
