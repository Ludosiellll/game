package forma.app.run

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short beep and buzz for in-app events (end of rest). One tone generator for the process. */
object Feedback {
    private val tones by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull() }

    /** [alarm]: the app is not on the screen — the buzz goes as an alarm's: a plain one from the background may be dropped. */
    fun restOver(context: Context, beep: Boolean, vibrate: Boolean, alarm: Boolean = false) {
        if (beep) runCatching { tones?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400) }
        if (!vibrate) return
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1)
        runCatching {
            when {
                !alarm -> vibrator?.vibrate(effect)
                Build.VERSION.SDK_INT >= 33 -> vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
                else -> @Suppress("DEPRECATION") vibrator?.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        }
    }
}
