package forma.app.run

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.AppJson
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
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis on the 230 m stadium, the phone in a pocket with the screen off: the headset's button presses "lap", as the
 * notification's does, and only while a stadium run goes on.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HeadsetLapTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val easy = Segment(SegmentKind.RUN, 40 * 60, PaceRange(330, 360))
    private val stadium = RunWorkout(RunType.EASY, "Лёгкий бег", listOf(easy), lapMeters = 230)
    private val road = RunWorkout(RunType.EASY, "Лёгкий бег", listOf(easy))

    @After fun tearDown() {
        RunSession.clear()
    }

    private fun send(service: RunService, action: String, intent: Intent = Intent(app, RunService::class.java).setAction(action)) {
        service.onStartCommand(intent, 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun started(workout: RunWorkout): RunService {
        RunSession.clear()
        val service = Robolectric.buildService(RunService::class.java).create().get()
        send(
            service, RunService.ACTION_START,
            Intent(app, RunService::class.java).setAction(RunService.ACTION_START)
                .putExtra(RunService.EXTRA_WORKOUT, AppJson.encodeToString(RunWorkout.serializer(), workout))
                .putExtra(RunService.EXTRA_VOICE, false).putExtra(RunService.EXTRA_BEEPS, false).putExtra(RunService.EXTRA_VIBRATION, false),
        )
        return service
    }

    private fun button(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) =
        Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(0L, 0L, action, code, repeat))

    private fun later(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @Test fun onTheStadiumTheHeadsetButtonPressesLap() {
        val service = started(stadium)
        val t = assertNotNull(RunSession.tracker)
        assertEquals(RunTracker.State.RUNNING, t.state)
        val session = assertNotNull(service.headset.session)
        assertTrue(session.isActive)
        val cb = service.headset.callback
        // On the line in the first seconds: the start, no lap. The release is taken too, and does nothing.
        assertTrue(cb.onMediaButtonEvent(button(KeyEvent.KEYCODE_HEADSETHOOK)))
        assertTrue(cb.onMediaButtonEvent(button(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_UP)))
        assertEquals(0, t.laps.size)
        // Twenty seconds on, a touch in the pocket: far too early for a lap at the easy pace — no lap.
        later(20)
        assertTrue(cb.onMediaButtonEvent(button(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        assertEquals(0, t.laps.size)
        // On the line a lap later: the lap.
        later(60)
        assertTrue(cb.onMediaButtonEvent(button(KeyEvent.KEYCODE_HEADSETHOOK)))
        assertEquals(1, t.laps.size)
        assertEquals(80.0, t.laps.last().seconds, 1.0)
        // A double press, and the key held down: one lap.
        assertTrue(cb.onMediaButtonEvent(button(KeyEvent.KEYCODE_HEADSETHOOK)))
        assertTrue(cb.onMediaButtonEvent(button(KeyEvent.KEYCODE_HEADSETHOOK, repeat = 1)))
        assertEquals(1, t.laps.size)
        // Every key a headset's one button may send is "lap".
        var laps = 1
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            later(80)
            assertTrue(cb.onMediaButtonEvent(button(code)))
            assertEquals(++laps, t.laps.size, KeyEvent.keyCodeToString(code))
        }
        // Other keys are not taken.
        later(80)
        assertFalse(cb.onMediaButtonEvent(button(KeyEvent.KEYCODE_MEDIA_PREVIOUS)))
        assertEquals(laps, t.laps.size)
        // The same lap as the notification's: the run screen shows it.
        assertEquals(laps, assertNotNull(RunSession.ui.value).laps)
        service.onDestroy()
        assertNull(service.headset.session)
        assertFalse(session.isActive)
    }

    @Test fun pausedOrStoppedTheButtonsAreLetGo() {
        val service = started(stadium)
        assertNotNull(service.headset.session)
        send(service, RunService.ACTION_PAUSE)
        assertEquals(RunTracker.State.PAUSED, RunSession.tracker?.state)
        assertNull(service.headset.session)
        send(service, RunService.ACTION_RESUME)
        assertEquals(RunTracker.State.RUNNING, RunSession.tracker?.state)
        assertNotNull(service.headset.session)
        send(service, RunService.ACTION_STOP)
        assertEquals(RunTracker.State.FINISHED, RunSession.tracker?.state)
        assertNull(service.headset.session)
        service.onDestroy()
    }

    @Test fun noSessionOutsideAStadiumRun() {
        // No run: nothing held.
        RunSession.clear()
        val idle = Robolectric.buildService(RunService::class.java).create().get()
        assertNull(idle.headset.session)
        idle.onDestroy()
        // A road run: the headset's button stays the music's.
        val service = started(road)
        assertEquals(RunTracker.State.RUNNING, RunSession.tracker?.state)
        later(5)
        assertNull(service.headset.session)
        service.onDestroy()
    }
}
