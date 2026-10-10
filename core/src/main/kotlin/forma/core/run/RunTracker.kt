package forma.core.run

import kotlinx.serialization.Serializable
import kotlin.math.max

/** A GPS fix. */
data class Fix(val timeMs: Long, val lat: Double, val lon: Double, val accuracy: Float)

/** Everything a run needs to carry on after the app process was killed (GPS points are kept apart). */
@Serializable
data class TrackerSnapshot(
    val workout: RunWorkout,
    val state: RunTracker.State,
    val activeMs: Long,
    val skipMs: Long = 0,
    val skippedWorkSec: Int = 0,
    val distanceM: Double = 0.0,
    val workDistanceM: Double = 0.0,
    val nextKm: Int = 1,
    val lastKmSec: Int = 0,
    val fixes: Int = 0,
)

/** Events produced while running that the coach announces. */
sealed interface RunEvent {
    data class Timeline(val cue: Cue) : RunEvent
    data class Kilometre(val km: Int, val splitSec: Int, val totalSec: Int) : RunEvent
}

/**
 * Keeps time, distance and pace for one run. Time is "active" time: pauses don't count.
 * GPS fixes worse than [maxAccuracy] metres or implying more than [maxSpeed] m/s are dropped. Distance is counted
 * from an anchor fix once the move is bigger than the fixes' own uncertainty, so standing still adds nothing.
 */
class RunTracker(
    val workout: RunWorkout,
    private val maxAccuracy: Float = 25f,
    private val maxSpeed: Double = 9.0,
) {
    enum class State { READY, RUNNING, PAUSED, FINISHED }

    var state = State.READY
        private set
    private var startedAt = 0L
    private var pausedTotal = 0L
    private var pauseStart = 0L
    private var lastTickSec = -1
    private var lastFix: Fix? = null
    private var lastFixActiveMs = 0L
    private var speedRejects = 0

    var distanceM = 0.0
        private set
    var workDistanceM = 0.0
        private set
    private var nextKm = 1
    private var lastKmSec = 0
    private val window = ArrayDeque<Pair<Long, Double>>()
    var fixes = 0
        private set
    var lastAccuracy: Float? = null
        private set

    fun start(nowMs: Long) {
        if (state != State.READY) return
        startedAt = nowMs
        state = State.RUNNING
    }

    fun pause(nowMs: Long) {
        if (state != State.RUNNING) return
        pauseStart = nowMs
        state = State.PAUSED
        lastFix = null
    }

    fun resume(nowMs: Long) {
        if (state != State.PAUSED) return
        pausedTotal += nowMs - pauseStart
        state = State.RUNNING
    }

    fun finish(nowMs: Long) {
        if (state == State.PAUSED) resume(nowMs)
        if (state == State.RUNNING) {
            finishedActiveMs = activeMs(nowMs)
            state = State.FINISHED
        }
    }

    private var finishedActiveMs = 0L

    /** Seconds the runner skipped ahead in the plan (the timeline runs ahead of the clock by this much). */
    private var skipMs = 0L
    private var skippedWorkSec = 0

    /** Position in the planned timeline: active time plus skipped parts. */
    fun timelineMs(nowMs: Long): Long = activeMs(nowMs) + skipMs

    /** Jumps to the start of the next segment. Skipped running time does not count as done. */
    fun skipSegment(nowMs: Long) {
        if (workout.isOpenEnded || (state != State.RUNNING && state != State.PAUSED)) return
        val tl = RunTimeline.at(workout, (timelineMs(nowMs) / 1000).toInt())
        val seg = tl.segment ?: return
        if (seg.kind == SegmentKind.WORK || seg.kind == SegmentKind.RUN) skippedWorkSec += tl.segmentRemaining
        skipMs += tl.segmentRemaining * 1000L
        lastTickSec = (timelineMs(nowMs) / 1000).toInt() - 1
    }

    fun activeMs(nowMs: Long): Long = when (state) {
        State.READY -> 0
        State.RUNNING -> nowMs - startedAt - pausedTotal
        State.PAUSED -> pauseStart - startedAt - pausedTotal
        State.FINISHED -> finishedActiveMs
    }

    fun activeSec(nowMs: Long): Int = (activeMs(nowMs) / 1000).toInt()

    fun timeline(nowMs: Long): TimelineState = RunTimeline.at(workout, (timelineMs(nowMs) / 1000).toInt())

    /** Feeds a GPS fix; returns kilometre events crossed by it. */
    fun onFix(fix: Fix, nowMs: Long): List<RunEvent> {
        lastAccuracy = fix.accuracy
        if (state != State.RUNNING || fix.accuracy > maxAccuracy) return emptyList()
        val active = activeMs(nowMs)
        val prev = lastFix
        fixes++
        if (prev == null) {
            lastFix = fix
            lastFixActiveMs = active
            return emptyList()
        }
        val dt = (active - lastFixActiveMs) / 1000.0
        if (dt <= 0) return emptyList()
        val d = Geo.haversine(prev.lat, prev.lon, fix.lat, fix.lon)
        if (d / dt > maxSpeed) {
            // A jump: drop this fix and keep the anchor — unless the anchor itself was the bad one (three jumps in a row).
            if (++speedRejects >= 3) {
                lastFix = fix
                lastFixActiveMs = active
                speedRejects = 0
            }
            return emptyList()
        }
        speedRejects = 0
        // Within the fixes' uncertainty: no movement yet; the anchor stays until the move is clear.
        if (d < max(3.0, 0.5 * (prev.accuracy + fix.accuracy))) return emptyList()
        lastFix = fix
        lastFixActiveMs = active
        distanceM += d
        val seg = RunTimeline.at(workout, ((active + skipMs) / 1000).toInt()).segment
        if (seg != null && (seg.kind == SegmentKind.WORK || seg.kind == SegmentKind.RUN)) workDistanceM += d
        window.addLast(active to distanceM)
        while (window.size > 2 && active - window.first().first > 30_000) window.removeFirst()
        val out = mutableListOf<RunEvent>()
        while (distanceM >= nextKm * 1000.0) {
            val total = (active / 1000).toInt()
            out += RunEvent.Kilometre(nextKm, total - lastKmSec, total)
            lastKmSec = total
            nextKm++
        }
        return out
    }

    /** Current pace over the last ~30 s, seconds per km. */
    fun currentPace(): Double? {
        if (window.size < 2) return null
        val (t0, d0) = window.first()
        val (t1, d1) = window.last()
        val dd = d1 - d0
        if (dd < 15 || t1 <= t0) return null
        return (t1 - t0) / 1000.0 / (dd / 1000.0)
    }

    fun averagePace(nowMs: Long): Double? =
        if (distanceM < 50) null else activeMs(nowMs) / 1000.0 / (distanceM / 1000.0)

    /** Timeline cues for whole seconds passed since the previous tick. */
    fun tick(nowMs: Long): List<RunEvent> {
        if (state != State.RUNNING) return emptyList()
        val sec = (timelineMs(nowMs) / 1000).toInt()
        if (sec == lastTickSec) return emptyList()
        val from = lastTickSec
        lastTickSec = sec
        if (workout.isOpenEnded) return emptyList()
        return RunTimeline.cuesBetween(workout, from, sec).map { RunEvent.Timeline(it) }
    }

    /** Seconds spent in work segments so far. */
    fun workSec(nowMs: Long): Int {
        val t = (timelineMs(nowMs) / 1000).toInt()
        var start = 0
        var sum = 0
        for (s in workout.segments) {
            val end = start + s.seconds
            if (s.kind == SegmentKind.WORK || s.kind == SegmentKind.RUN) sum += (minOf(t, end) - start).coerceAtLeast(0)
            start = end
        }
        return (sum - skippedWorkSec).coerceAtLeast(0)
    }

    /** Completion is the share of the planned running done: a skipped cool-down or warm-up does not count against it. */
    fun snapshot(nowMs: Long) = TrackerSnapshot(
        workout, state, activeMs(nowMs), skipMs, skippedWorkSec, distanceM, workDistanceM, nextKm, lastKmSec, fixes,
    )

    fun result(nowMs: Long, rpe: Int): RunResult {
        val sec = activeSec(nowMs)
        val work = workSec(nowMs)
        val completed = when {
            workout.isOpenEnded -> 1.0
            workout.workSeconds > 0 -> work.toDouble() / workout.workSeconds
            else -> ((timelineMs(nowMs) / 1000).toDouble() / workout.totalSeconds)
        }.coerceIn(0.0, 1.0)
        val workPace = if (workDistanceM > 100 && work > 0) work / (workDistanceM / 1000.0) else null
        return RunResult(
            completed = completed, rpe = rpe, durationSec = sec,
            distanceM = distanceM.takeIf { it > 0 }, workPace = workPace,
            workDistanceM = workDistanceM.takeIf { it > 0 }, workSec = if (workout.isOpenEnded) null else work,
        )
    }

    companion object {
        /**
         * The run as it was at [s]. A run that was going is paused at that point: what happened after it was not tracked,
         * so it does not count; the runner resumes or finishes. A finished run stays finished.
         */
        fun restore(s: TrackerSnapshot, nowMs: Long): RunTracker = RunTracker(s.workout).apply {
            distanceM = s.distanceM
            workDistanceM = s.workDistanceM
            skipMs = s.skipMs
            skippedWorkSec = s.skippedWorkSec
            nextKm = s.nextKm
            lastKmSec = s.lastKmSec
            fixes = s.fixes
            when (s.state) {
                State.READY -> Unit
                State.FINISHED -> {
                    finishedActiveMs = s.activeMs
                    state = State.FINISHED
                }
                State.RUNNING, State.PAUSED -> {
                    startedAt = nowMs - s.activeMs
                    pauseStart = nowMs
                    state = State.PAUSED
                }
            }
            lastTickSec = ((s.activeMs + s.skipMs) / 1000).toInt()
        }
    }
}
