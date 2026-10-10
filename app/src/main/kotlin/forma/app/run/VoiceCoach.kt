package forma.app.run

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Speaks cues in Russian, beeps the countdown and vibrates on segment changes. Music is ducked while speaking. */
class VoiceCoach(context: Context, var voice: Boolean, var beeps: Boolean, var vibration: Boolean) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private var tts: TextToSpeech? = null
    private var ready = false
    private val tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") app.getSystemService(Vibrator::class.java)
    }

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                val ru = Locale.forLanguageTag("ru-RU")
                val res = t.setLanguage(ru)
                ready = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                t.setAudioAttributes(attrs)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { audio?.abandonAudioFocusRequest(focus) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { audio?.abandonAudioFocusRequest(focus) }
                })
            }
        }
    }

    fun speak(text: String) {
        if (!voice) return
        val t = tts ?: return
        if (!ready) return
        audio?.requestAudioFocus(focus)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "c" + System.nanoTime())
    }

    fun countdown(left: Int) {
        if (beeps) tones?.startTone(if (left == 1) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 180)
        if (vibration && left == 1) vibrate(longArrayOf(0, 60))
    }

    fun segmentChange() {
        if (beeps) tones?.startTone(ToneGenerator.TONE_PROP_ACK, 300)
        if (vibration) vibrate(longArrayOf(0, 350, 150, 350))
    }

    fun restOver() {
        if (beeps) tones?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        if (vibration) vibrate(longArrayOf(0, 250, 120, 250))
    }

    private fun vibrate(pattern: LongArray) {
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    fun release() {
        tts?.shutdown()
        tts = null
        tones?.release()
    }
}
