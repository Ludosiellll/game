package forma.app.ui

import android.content.Context
import forma.app.run.Feedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Rest between sets. It lives outside the workout screen: it keeps counting, and beeps at the end, while the library
 * or another screen is open on top.
 */
object RestTimer {
    data class Rest(val workoutId: Long, val endAt: Long, val totalMs: Long)

    private val scope = MainScope()
    private var job: Job? = null
    private val _rest = MutableStateFlow<Rest?>(null)
    val rest: StateFlow<Rest?> = _rest.asStateFlow()

    fun start(context: Context, workoutId: Long, ms: Long, beep: Boolean, vibrate: Boolean) =
        set(context, Rest(workoutId, System.currentTimeMillis() + ms, ms), beep, vibrate)

    /** ±15 s from the rest bar; more time also makes the ring longer. */
    fun adjust(context: Context, deltaMs: Long, beep: Boolean, vibrate: Boolean) {
        val r = _rest.value ?: return
        set(context, r.copy(endAt = r.endAt + deltaMs, totalMs = r.totalMs + deltaMs.coerceAtLeast(0)), beep, vibrate)
    }

    fun stop() {
        job?.cancel()
        _rest.value = null
    }

    private fun set(context: Context, r: Rest, beep: Boolean, vibrate: Boolean) {
        job?.cancel()
        _rest.value = r
        val app = context.applicationContext
        job = scope.launch {
            delay((r.endAt - System.currentTimeMillis()).coerceAtLeast(0))
            Feedback.restOver(app, beep, vibrate)
            _rest.value = null
        }
    }
}
