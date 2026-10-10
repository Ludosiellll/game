package forma.core.run

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToInt

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
    /** Timeline moments (ms) of the lap button, and the laps they closed. */
    val taps: List<Long> = emptyList(),
    val laps: List<Lap> = emptyList(),
    /** WORK segments skipped before half of them was run. */
    val skippedReps: List<Int> = emptyList(),
)

/**
 * A lap as the runner pressed it: its time, and what it should have been ([fast]..[slow], seconds) in a rep or a paced
 * run. [number] counts laps in the current rep, [of] is how many the rep has; [rep] is the rep (1, 2, …), null between.
 */
@Serializable
data class Lap(
    val seconds: Double,
    val fast: Double? = null,
    val slow: Double? = null,
    val number: Int = 1,
    val of: Int? = null,
    val rep: Int? = null,
) {
    /** The press that closed the rep's last lap. */
    val closesRep: Boolean get() = of != null && number >= of

    /** −1 faster than the target, 1 slower, 0 on it; null without a target. No [slow] bound: any slower lap is fine. */
    val verdict: Int? get() = when {
        fast == null && slow == null -> null
        fast != null && seconds < fast - 0.5 -> -1
        slow != null && seconds > slow + 0.5 -> 1
        else -> 0
    }
}

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

    /** Timeline moments (ms) the runner pressed "lap" at, and the laps they closed (same order). */
    private val taps = mutableListOf<Long>()
    private val lapList = mutableListOf<Lap>()
    val laps: List<Lap> get() = lapList

    /** WORK segments skipped before half of them was run: not done. */
    private val skippedReps = mutableSetOf<Int>()

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
        if (seg.kind == SegmentKind.WORK && tl.segmentRemaining * 2 > seg.seconds) skippedReps += tl.index
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
        taps.toList(), lapList.toList(), skippedReps.sorted(),
    )

    /** Start and end of every segment on the timeline, ms. */
    private val bounds: List<LongRange> by lazy {
        var start = 0L
        workout.segments.map { s -> (start until start + s.seconds * 1000L).also { start += s.seconds * 1000L } }
    }

    /**
     * The WORK segment a press at timeline [t] belongs to: the one running, or one that ended a moment ago (a rep finished
     * a little slower than the beep: up to 40 % of the rep, at most half of what follows).
     */
    private fun repAt(t: Long): Int? {
        val segs = workout.segments
        for (i in segs.indices) {
            if (segs[i].kind != SegmentKind.WORK) continue
            val b = bounds[i]
            val after = segs.getOrNull(i + 1)?.seconds ?: 0
            val grace = minOf(after * 500L, maxOf(20_000L, segs[i].seconds * 400L))
            if (t >= b.first && t < b.last + 1 + grace) return i
        }
        return null
    }

    /**
     * The runner crossed the line. Returns the lap just finished; null when the run is not going or for a second press
     * within seconds of the last one (a double tap). The press that closes a rep's last lap before its planned end
     * finishes the rep: the recovery starts at once.
     */
    fun lap(nowMs: Long): Lap? {
        if (state != State.RUNNING) return null
        val t = timelineMs(nowMs)
        val prev = taps.lastOrNull()
        if (prev != null && t - prev < MIN_LAP_MS) return null
        val lapM = workout.lapMeters
        // The rep this press belongs to, unless all of its laps are pressed already.
        val rep = repAt(t)?.takeIf { i ->
            val laps = workout.segments[i].laps
            laps == null || taps.count { it >= bounds[i].first } < kotlin.math.ceil(laps).toInt()
        }
        val result = if (rep != null) {
            val seg = workout.segments[rep]
            val start = bounds[rep].first
            val before = taps.count { it >= start }
            val from = if (prev != null && prev >= start) prev else start
            val laps = seg.laps
            // A rep of 6.5 laps starts half a lap before the line: its first "lap" is the half.
            val part = if (before == 0 && laps != null && laps % 1.0 != 0.0) laps % 1.0 else 1.0
            val target = seg.lapSec?.times(part)
            val range = seg.pace?.let { p -> lapM?.let { Track.lapSec(p.fast.toDouble(), it) * part to Track.lapSec(p.slow.toDouble(), it) * part } }
            val number = workout.segments.take(rep + 1).count { it.kind == SegmentKind.WORK }
            val of = laps?.let { kotlin.math.ceil(it).toInt() }
            // A time trial is run evenly at first; in its second half faster is what it is for.
            val free = workout.testMeters != null && of != null && (before + 1) * 2 > of
            Lap((t - from) / 1000.0, if (free) null else range?.first ?: target, range?.second ?: target, before + 1, of, number)
        } else {
            val i = RunTimeline.at(workout, (t / 1000).toInt()).index.coerceAtMost(workout.segments.lastIndex)
            val start = if (workout.isOpenEnded || i < 0) 0L else bounds[i].first
            val from = if (prev != null && prev >= start) prev else start
            // An easy pace has only a fast limit: slower is fine.
            val pace = workout.segments.getOrNull(i)?.pace?.takeIf { workout.segments[i].kind != SegmentKind.WORK }
            val fast = pace?.let { p -> lapM?.let { Track.lapSec(p.fast.toDouble(), it) } }
            Lap((t - from) / 1000.0, fast, null, taps.count { it >= start } + 1, null)
        }
        taps += t
        lapList += result
        // The rep's last lap, pressed before its planned end: the rep is done, the recovery starts now.
        if (rep != null && result.closesRep) {
            val end = bounds[rep].last + 1
            if (t < end) {
                skipMs += end - t
                lastTickSec = (end / 1000).toInt() - 1
            }
        }
        return result
    }

    /** Time of each WORK rep from the lap button: from its start to the press that closes its last lap (null if none). */
    fun repTimes(): List<Double?> {
        val work = workout.segments.indices.filter { workout.segments[it].kind == SegmentKind.WORK }
        return work.mapIndexed { k, i ->
            val laps = workout.segments[i].laps ?: return@mapIndexed null
            val need = kotlin.math.ceil(laps).toInt()
            val at = lapList.indexOfFirst { it.rep == k + 1 && it.number == need }
            if (at < 0) null else (taps[at] - bounds[i].first) / 1000.0
        }
    }

    /** Seconds into the lap being run: since the last press, or since the current segment began if it came later. */
    fun lapClockSec(nowMs: Long): Int {
        val t = timelineMs(nowMs)
        val i = RunTimeline.at(workout, (t / 1000).toInt()).index.coerceAtMost(workout.segments.lastIndex)
        val start = if (workout.isOpenEnded || i < 0) 0L else bounds[i].first
        val prev = taps.lastOrNull()
        return ((t - if (prev != null && prev >= start) prev else start) / 1000).toInt()
    }

    /** Reps whose time has passed and that were not skipped: what the runner most likely did. */
    fun repsDone(nowMs: Long): Int {
        val t = timelineMs(nowMs)
        return workout.segments.indices.count {
            workout.segments[it].kind == SegmentKind.WORK && t >= bounds[it].last + 1 && it !in skippedReps
        }
    }

    fun result(nowMs: Long, rpe: Int): RunResult {
        val sec = activeSec(nowMs)
        val work = workSec(nowMs)
        val completed = when {
            workout.isOpenEnded -> 1.0
            workout.workSeconds > 0 -> work.toDouble() / workout.workSeconds
            else -> ((timelineMs(nowMs) / 1000).toDouble() / workout.totalSeconds)
        }.coerceIn(0.0, 1.0)
        val gpsPace = if (workDistanceM > 100 && work > 0) work / (workDistanceM / 1000.0) else null
        // On a track the lap button is better than GPS: the pace of the reps that were pressed through.
        val lapM = workout.lapMeters
        val reps = repTimes()
        val timed = workout.segments.filter { it.kind == SegmentKind.WORK }.zip(reps).filter { it.second != null }
        val lapPace = if (lapM != null && timed.isNotEmpty() && timed.size * 2 >= reps.size) {
            timed.sumOf { it.second!! } / (timed.sumOf { it.first.laps!! * lapM } / 1000.0)
        } else null
        val testSeconds = if (workout.testMeters != null) reps.firstOrNull()?.roundToInt() else null
        return RunResult(
            completed = completed, rpe = rpe, durationSec = sec,
            distanceM = distanceM.takeIf { it > 0 }, workPace = lapPace ?: gpsPace,
            workDistanceM = workDistanceM.takeIf { it > 0 }, workSec = if (workout.isOpenEnded) null else work,
            laps = lapList.toList().takeIf { it.isNotEmpty() }, testSeconds = testSeconds,
        )
    }

    companion object {
        /** Presses closer than this are one press (a double tap): no lap on a stadium is this short. */
        const val MIN_LAP_MS = 5_000L

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
            // Laps are kept only with their presses (both lists or neither).
            if (s.taps.size == s.laps.size) {
                taps += s.taps
                lapList += s.laps
            }
            skippedReps += s.skippedReps
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
