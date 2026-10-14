package forma.app.run

import android.view.KeyEvent

/**
 * The volume-down key as the lap button while the run screen of a stadium run is open (over the lock screen too): found
 * by feel in a pocket, where the screen's button is not. The volume does not change then. Set by the run screen, read by
 * the activity.
 */
object LapKey {
    /** What a press does; null when no run screen wants it. */
    @Volatile var onLap: (() -> Unit)? = null

    /** True when the key was taken as "lap" (the activity then does not pass it on to the volume). */
    fun down(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val lap = onLap ?: return false
        // Held down, the key repeats: one press is one lap.
        if ((event?.repeatCount ?: 0) == 0) lap()
        return true
    }

    /** The key's release, taken too while it presses "lap" (or the system would show the volume panel). */
    fun up(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && onLap != null
}
