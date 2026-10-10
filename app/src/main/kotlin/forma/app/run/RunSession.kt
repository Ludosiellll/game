package forma.app.run

import forma.core.run.RunTracker
import forma.core.run.RunWorkout
import forma.core.run.TimelineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Snapshot of the running session for the screen and the notification. */
data class RunUi(
    val workout: RunWorkout,
    val state: RunTracker.State,
    val elapsedSec: Int,
    val timeline: TimelineState,
    val distanceM: Double,
    val pace: Double?,
    val avgPace: Double?,
    val gps: Boolean,
    val gpsAccuracy: Float?,
    val startedAt: Long,
)

/** Process-wide holder: the service writes, the UI reads. One run at a time. */
object RunSession {
    private val _ui = MutableStateFlow<RunUi?>(null)
    val ui: StateFlow<RunUi?> = _ui.asStateFlow()

    @Volatile var tracker: RunTracker? = null
        internal set
    @Volatile var gpsEnabled: Boolean = false
        internal set
    @Volatile var startedAtWall: Long = 0L
        internal set
    internal val points = mutableListOf<forma.core.run.Fix>()

    internal fun publish(nowMs: Long) {
        val t = tracker ?: return
        _ui.value = RunUi(
            t.workout, t.state, t.activeSec(nowMs), t.timeline(nowMs), t.distanceM, t.currentPace(), t.averagePace(nowMs),
            gpsEnabled, t.lastAccuracy, startedAtWall,
        )
    }

    internal fun clear() {
        tracker = null
        points.clear()
        _ui.value = null
    }
}
