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

/**
 * Speaks cues in Russian, beeps the countdown and vibrates on segment changes. Music is ducked while speaking.
 * Lines said before the speech engine is up (the first segment of a run) wait for it.
 */
class VoiceCoach(context: Context, var voice: Boolean, var beeps: Boolean, var vibration: Boolean) {
    companion object {
        /**
         * Whether the phone can speak Russian (a speech engine with a Russian voice): without one the coach is silent,
         * and the runner must know before the start, not on the track.
         */
        fun checkRussian(context: Context, onResult: (Boolean) -> Unit) {
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            var engine: TextToSpeech? = null
            engine = runCatching {
                TextToSpeech(context.applicationContext) { status ->
                    main.post {
                        val e = engine
                        val lang = if (status == TextToSpeech.SUCCESS && e != null) runCatching { e.isLanguageAvailable(Locale.forLanguageTag("ru-RU")) }.getOrNull() else null
                        onResult(lang != null && lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED)
                        e?.shutdown()
                    }
                }
            }.getOrNull()
        }
    }

    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private val waiting = mutableListOf<String>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
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
            // The callback may come on another thread: the coach is used on the main one.
            main.post {
                val t = tts ?: return@post
                if (status == TextToSpeech.SUCCESS) {
                    val res = t.setLanguage(Locale.forLanguageTag("ru-RU"))
                    ready = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                    t.setAudioAttributes(attrs)
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { if (!t.isSpeaking) audio?.abandonAudioFocusRequest(focus) }
                        override fun onStop(id: String?, interrupted: Boolean) { if (!t.isSpeaking) audio?.abandonAudioFocusRequest(focus) }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) { audio?.abandonAudioFocusRequest(focus) }
                    })
                }
                failed = !ready
                if (ready) waiting.forEach { say(t, it) }
                waiting.clear()
            }
        }
    }

    /**
     * Says [text] after what is being said. [now]: a lap's time, which is worth something only at the line — it cuts off
     * a longer sentence still going instead of waiting behind it.
     */
    fun speak(text: String, now: Boolean = false) {
        if (!voice || failed) return
        val t = tts ?: return
        if (!ready) {
            // Only the latest few matter: a minute-old "half way" is noise.
            if (now) waiting.add(0, text) else waiting += text
            while (waiting.size > 3) waiting.removeAt(waiting.lastIndex.takeIf { now } ?: 0)
            return
        }
        say(t, text, now)
    }

    private fun say(t: TextToSpeech, text: String, now: Boolean = false) {
        audio?.requestAudioFocus(focus)
        t.speak(text, if (now) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "c" + System.nanoTime())
    }

    fun countdown(left: Int) {
        if (beeps) tones?.startTone(if (left == 1) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 180)
        if (vibration && left == 1) vibrate(longArrayOf(0, 60))
    }

    /** Pacer: the moment a lap should be done. */
    fun lapMark() {
        if (beeps) tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        if (vibration) vibrate(longArrayOf(0, 80))
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
        waiting.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        audio?.abandonAudioFocusRequest(focus)
        tones?.release()
    }
}
