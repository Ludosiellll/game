package forma.app.run

import forma.app.data.AppJson
import forma.core.run.Fix
import forma.core.run.RunTracker
import forma.core.run.RunWorkout
import forma.core.run.TimelineState
import forma.core.run.TrackerSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

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
    /** Brought back after the system closed the app mid-run: paused where it was last saved. */
    val restored: Boolean = false,
    /** The last lap pressed on a track, how many so far, and seconds into the lap being run (null off a track). */
    val lastLap: forma.core.run.Lap? = null,
    val laps: Int = 0,
    val lapClock: Int? = null,
    /** An easy or long run kept going past its plan: seconds beyond it. */
    val overSec: Int = 0,
    /** On a stadium after the warm-up: the plan waits for the start press on the line. */
    val waiting: Boolean = false,
    /** Paused by the auto-pause (standing on the road), not by hand: it goes on with running. */
    val autoPaused: Boolean = false,
    /** Steps a minute right now, from the step counter. */
    val cadence: Int? = null,
)

/** A run on disk, enough to carry on after the process was killed. */
@Serializable
data class SavedRun(
    val tracker: TrackerSnapshot,
    val startedAtWall: Long,
    val gps: Boolean,
    val voice: Boolean = true,
    val beeps: Boolean = true,
    val vibration: Boolean = true,
    val autoPause: Boolean = true,
)

/**
 * The current run on disk: a small state file rewritten every few seconds (write to a temporary file, then rename)
 * and the GPS points appended as they come.
 */
class RunStore(dir: File) {
    private val state = File(dir, "run_current.json")
    private val tmp = File(dir, "run_current.json.tmp")
    private val track = File(dir, "run_current.csv")

    fun saveState(s: SavedRun) {
        tmp.writeText(AppJson.encodeToString(SavedRun.serializer(), s))
        if (!tmp.renameTo(state)) {
            state.delete()
            tmp.renameTo(state)
        }
    }

    fun appendPoints(points: List<Fix>) {
        if (points.isEmpty()) return
        track.appendText(points.joinToString("") { "${it.timeMs},${it.lat},${it.lon},${it.accuracy}\n" })
    }

    fun load(): Pair<SavedRun, List<Fix>>? {
        if (!state.exists()) return null
        val saved = runCatching { AppJson.decodeFromString(SavedRun.serializer(), state.readText()) }.getOrNull() ?: return null
        val points = if (!track.exists()) emptyList() else runCatching {
            track.readLines().mapNotNull { line ->
                // The last line may be cut short by the kill: skip what does not parse.
                runCatching {
                    val p = line.split(',')
                    Fix(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toFloat())
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        return saved to points
    }

    fun clear() {
        state.delete()
        tmp.delete()
        track.delete()
    }
}

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
    internal var voice = true
    internal var beeps = true
    internal var vibration = true
    internal var autoPause = true
    /** The run stands by the auto-pause right now. */
    @Volatile internal var autoPaused = false
    internal val points = mutableListOf<Fix>()
    private var restored = false
    private var store: RunStore? = null
    private var savedPoints = 0

    /**
     * Called once per process. A run the system closed together with the app comes back paused at its last save,
     * so the runner can carry on or save it.
     */
    fun init(store: RunStore, nowMs: Long) {
        this.store = store
        if (tracker != null) return
        val (saved, pts) = store.load() ?: return
        tracker = RunTracker.restore(saved.tracker, nowMs)
        gpsEnabled = saved.gps
        startedAtWall = saved.startedAtWall
        voice = saved.voice
        beeps = saved.beeps
        vibration = saved.vibration
        autoPause = saved.autoPause
        points.clear()
        points += pts
        savedPoints = points.size
        restored = saved.tracker.state != RunTracker.State.FINISHED
        publish(nowMs)
    }

    /** A new run: whatever was on disk is replaced. */
    internal fun begin(t: RunTracker, gps: Boolean, voice: Boolean, beeps: Boolean, vibration: Boolean, autoPause: Boolean = true) {
        store?.clear()
        tracker = t
        gpsEnabled = gps
        startedAtWall = System.currentTimeMillis()
        this.voice = voice
        this.beeps = beeps
        this.vibration = vibration
        this.autoPause = autoPause
        autoPaused = false
        points.clear()
        savedPoints = 0
        restored = false
    }

    /** The run is going again in the service. */
    internal fun attached() {
        restored = false
    }

    /** Writes the run to disk: the state and the GPS points since the last save. */
    internal fun save(nowMs: Long) {
        val t = tracker ?: return
        val st = store ?: return
        runCatching {
            if (points.size > savedPoints) {
                st.appendPoints(points.subList(savedPoints, points.size).toList())
                savedPoints = points.size
            }
            st.saveState(SavedRun(t.snapshot(nowMs), startedAtWall, gpsEnabled, voice, beeps, vibration, autoPause))
        }
    }

    internal fun publish(nowMs: Long) {
        val t = tracker ?: return
        _ui.value = RunUi(
            t.workout, t.state, t.activeSec(nowMs), t.timeline(nowMs), t.shownDistanceM(), t.currentPace(), t.averagePace(nowMs),
            gpsEnabled, t.lastAccuracy, startedAtWall, restored, t.laps.lastOrNull(), t.laps.size,
            if (t.workout.lapMeters != null) t.lapClockSec(nowMs) else null,
            if (t.workout.isOpenEnded) 0 else ((t.timelineMs(nowMs) / 1000).toInt() - t.workout.totalSeconds).coerceAtLeast(0),
            t.waiting(nowMs),
            autoPaused && t.state == RunTracker.State.PAUSED,
            t.cadence(),
        )
    }

    /** The run was saved or thrown away. */
    fun clear() {
        tracker = null
        points.clear()
        savedPoints = 0
        restored = false
        store?.clear()
        _ui.value = null
    }
}
