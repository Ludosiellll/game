package forma.app.run

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * The button of a headset (wired or Bluetooth) as the lap button on a stadium run: it works with the screen off, in a
 * pocket, where the screen's button does not. A media session, "playing", is held by
 * [RunService] only while a stadium run goes on there; paused, finished or stopped, the buttons are the music's again.
 *
 * Who gets the button is the system's choice: on Android 8 and later the media buttons go to the app that last played
 * audio, when it has an active session (developer.android.com/media/legacy/media-buttons). Music playing in another app
 * keeps the button for that app — not fought here. Forma's own sound is the countdown beeps; the voice is played by the
 * speech engine's app and may not count as Forma's. How it goes on a given phone (HyperOS) is to be checked on it.
 */
internal class HeadsetLap(private val context: Context, private val onLap: () -> Unit) {
    /** The session while one is held (a stadium run going); null otherwise. */
    var session: MediaSession? = null
        private set

    /** Holds the media buttons while [on] (a stadium run going), lets them go otherwise. */
    fun hold(on: Boolean) {
        if (on) open() else release()
    }

    private fun open() {
        if (session != null) return
        session = runCatching {
            MediaSession(context, "forma-lap").apply {
                setCallback(callback, Handler(Looper.getMainLooper()))
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT)
                        .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                        .build(),
                )
                isActive = true
            }
        }.getOrNull()
    }

    fun release() {
        val s = session ?: return
        session = null
        runCatching {
            s.isActive = false
            s.release()
        }
    }

    val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val e = if (Build.VERSION.SDK_INT >= 33) {
                mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION") mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
            return e != null && press(e)
        }
    }

    /**
     * True when [e] is a lap key, its release too (taken whole: not passed on as "play" or "pause"). The press itself is
     * the lap button's, double taps and stray touches judged as for any press ([forma.core.run.RunTracker.press]); held
     * down, a key repeats — one press is one lap.
     */
    fun press(e: KeyEvent): Boolean {
        if (e.keyCode !in KEYS) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) onLap()
        return true
    }

    companion object {
        /** A headset's one button sends any of these, by its kind and by the state it thinks the player is in. */
        val KEYS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT,
        )
    }
}
