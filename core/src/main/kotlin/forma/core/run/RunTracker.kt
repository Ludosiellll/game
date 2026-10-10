package forma.core.run

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToInt

/** A GPS fix; [speed]: the satellites' own (Doppler) speed, m/s, when the phone gives it. */
data class Fix(val timeMs: Long, val lat: Double, val lon: Double, val accuracy: Float, val speed: Float? = null)

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
    /** WORK segments skipped for more than a quarter of them. */
    val skippedReps: List<Int> = emptyList(),
    /** Segments the runner started by pressing on the line (timeline ms), by index; −1 is an open-ended run. */
    val starts: Map<Int, Long> = emptyMap(),
    /** Time the plan stood still while the runner got to the start line (see [RunTimeline.startOnLine]). */
    val heldMs: Long = 0,
    /** Active time of the last press, so a double tap stays one press after a restore. */
    val lastPressMs: Long? = null,
    /** The start line on a stadium (lat, lon), learnt from the fixes at the presses. */
    val line: List<Double>? = null,
    /** Laps outside the reps counted from GPS crossings of the line (distance only). */
    val freeLaps: Double = 0.0,
)

/** What a press of the lap button was. */
sealed interface Press {
    /** A second press within seconds of the last one, or the run is not going. */
    data object Ignored : Press

    /** A press on the line as a segment began: the runner's own start, not a lap. */
    data object Start : Press

    data class Done(val lap: Lap) : Press
}

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
    /** Share of a whole lap it covers: a rep of 6.5 laps starts with a half. */
    val part: Double = 1.0,
    /** Not pressed: GPS saw the runner cross the line and no press came (about ±2 s). */
    val auto: Boolean = false,
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

    /** WORK segments skipped for more than a quarter of them: not done. */
    private val skippedReps = mutableSetOf<Int>()

    /** Segments the runner started by pressing on the line (timeline ms), by index; −1 is an open-ended run. */
    private val starts = mutableMapOf<Int, Long>()

    /** Active time of the last press: a double tap is judged on the clock, not on the plan's timeline (which jumps). */
    private var lastPressMs: Long? = null

    /**
     * Doppler speed from the satellites' signal, not from positions: far steadier than the difference of two fixes a
     * few metres off each, so the distance does not zig-zag and the current pace does not jump.
     */
    private var lastSpeed: Pair<Long, Float>? = null
    private val speeds = ArrayDeque<Pair<Long, Float>>()

    /** Steps from the phone's step counter: the cadence, and the distance where GPS gives none (off, or lost). */
    private var lastStepCount: Long? = null
    var steps = 0L
        private set
    private var stepsActiveMs = 0L
    private val stepWindow = ArrayDeque<Pair<Long, Long>>()
    private var stride: Pair<Double, Double>? = null
    private var lastGpsDistanceAt = -1L

    /** Stride calibration where GPS is good: a checkpoint (active ms, distance, steps) and the samples (cadence, stride). */
    private var calFrom: Triple<Long, Double, Long>? = null
    private val strides = mutableListOf<Pair<Double, Double>>()

    /** The runner's stride model (see [Stride.fit]): steps then give a distance where GPS gives none. */
    fun setStride(a: Double, b: Double) {
        stride = a to b
    }

    /** Pairs (cadence, stride) measured in this run where GPS was good: they calibrate the next runs. */
    fun strideSamples(): List<Pair<Double, Double>> = strides.toList()

    /**
     * The phone's step counter ([total] since it started counting). Steps while paused do not count. Where GPS gives no
     * distance for 5 s (off, under trees, a treadmill) and the stride is known, the steps give it at running cadence —
     * and GPS starts afresh when it is back, so the way is not counted twice.
     */
    fun onSteps(total: Long, nowMs: Long): List<RunEvent> {
        val prev = lastStepCount
        lastStepCount = total
        if (state != State.RUNNING || prev == null) return emptyList()
        val delta = total - prev
        val active = activeMs(nowMs)
        if (delta <= 0 || delta > 12 * maxOf(1L, (active - stepsActiveMs) / 1000 + 1)) {
            stepsActiveMs = active
            return emptyList()
        }
        steps += delta
        stepsActiveMs = active
        stepWindow.addLast(active to steps)
        while (stepWindow.size > 2 && active - stepWindow.first().first > 30_000) stepWindow.removeFirst()
        val c = cadence() ?: return emptyList()
        val s = stride ?: return emptyList()
        if (c < 120 || active - lastGpsDistanceAt <= 5_000 && lastGpsDistanceAt >= 0) return emptyList()
        lastFix = null
        lastSpeed = null
        calFrom = null
        return addDistance(delta * (s.first + s.second * c).coerceIn(0.5, 2.2), active)
    }

    /** Steps a minute over the last half minute (at least 10 s of steps), or null. */
    fun cadence(): Int? {
        if (stepWindow.size < 2) return null
        val (t0, s0) = stepWindow.first()
        val (t1, s1) = stepWindow.last()
        if (t1 - t0 < 10_000) return null
        return ((s1 - s0) * 60_000.0 / (t1 - t0)).toInt().takeIf { it > 0 }
    }

    /** The rep that waits for the start press on the line after the warm-up, if any (see [RunTimeline.startOnLine]). */
    private val waitsAt: Int? = RunTimeline.startOnLine(workout)

    /** Time the plan stood still at the end of the warm-up while the runner got to the line. */
    private var heldMs = 0L

    /** The start line on a stadium, from the GPS fixes at the presses (and remembered from the last session). */
    var line: Pair<Double, Double>? = null
        private set

    /** The last usable fix and its active time: where the runner is when they press. */
    private var lastRaw: Fix? = null
    private var lastRawActive = 0L

    /** Line watching: the runner has been well away from it since the last crossing. */
    private var away = false
    private var lastCross: Long? = null

    /** Crossings seen by GPS (active ms), waiting to see whether a press comes with them. */
    private val pending = ArrayDeque<Long>()

    /** Active times of the presses (the double-tap and start presses included). */
    private val pressTimes = mutableListOf<Long>()

    /** Laps outside the reps counted from GPS crossings: only their distance matters. */
    private var freeLaps = 0.0

    /** The line from the last session on this stadium: crossings count from the very first lap. */
    fun setLine(lat: Double, lon: Double) {
        line = lat to lon
    }

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
        lastSpeed = null
        speeds.clear()
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

    /** Position in the planned timeline: active time plus skipped parts, less the wait on the line. */
    fun timelineMs(nowMs: Long): Long = planAt(activeMs(nowMs))

    /** The plan at [active] ms of running: it stands at the last moment of the warm-up until the start press. */
    private fun planAt(active: Long): Long {
        val t = active + skipMs - heldMs
        val w = waitsAt ?: return t
        if (w in starts || w in skippedReps) return t
        return minOf(t, bounds[w].first - 1)
    }

    /** The plan waits for the runner's start press on the line: the warm-up is over, the first rep has not begun. */
    fun waiting(nowMs: Long): Boolean {
        val w = waitsAt ?: return false
        if (state != State.RUNNING && state != State.PAUSED) return false
        return w !in starts && w !in skippedReps && activeMs(nowMs) + skipMs - heldMs >= bounds[w].first - 1
    }

    /** Ends the wait on the line: the plan goes on from the start of the rep. */
    private fun release(nowMs: Long) {
        val w = waitsAt ?: return
        val t = activeMs(nowMs) + skipMs - heldMs
        if (t > bounds[w].first) heldMs += t - bounds[w].first
    }

    /** Jumps to the start of the next segment. Skipped running time does not count as done. */
    fun skipSegment(nowMs: Long) {
        if (workout.isOpenEnded || (state != State.RUNNING && state != State.PAUSED)) return
        val wait = waitsAt?.takeIf { waiting(nowMs) }
        if (wait != null) {
            // Waiting on the line: skipping skips the rep that waits, and the recovery after it starts.
            release(nowMs)
            val seg = workout.segments[wait]
            skippedWorkSec += seg.seconds
            skippedReps += wait
            skipMs += seg.seconds * 1000L
            lastTickSec = (timelineMs(nowMs) / 1000).toInt() - 1
            return
        }
        val tl = RunTimeline.at(workout, (timelineMs(nowMs) / 1000).toInt())
        val seg = tl.segment ?: return
        if (seg.kind == SegmentKind.WORK || seg.kind == SegmentKind.RUN) skippedWorkSec += tl.segmentRemaining
        if (seg.kind == SegmentKind.WORK && tl.segmentRemaining * 4 > seg.seconds) skippedReps += tl.index
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
        lastRaw = fix
        lastRawActive = active
        watchLine(fix, active)
        // The distance from the Doppler speed between this fix and the one before (under 0.5 m/s is standing).
        val sp = fix.speed?.takeIf { it >= 0f && it <= maxSpeed }
        var bySpeed: Double? = null
        if (sp != null) {
            val ls = lastSpeed
            if (ls != null) {
                val dts = (active - ls.first) / 1000.0
                if (dts > 0 && dts <= 3.0) {
                    val v = (ls.second + sp) / 2.0
                    bySpeed = if (v < 0.5) 0.0 else v * dts
                }
            }
            lastSpeed = active to sp
            speeds.addLast(active to sp)
            while (speeds.size > 1 && active - speeds.first().first > 10_000) speeds.removeFirst()
        } else {
            lastSpeed = null
        }
        fixes++
        val add: Double
        if (bySpeed != null) {
            // The satellites' speed counts the way (a fix a few metres off is no jump for it); positions only keep the
            // anchor for when the speed goes missing.
            lastFix = fix
            lastFixActiveMs = active
            speedRejects = 0
            add = bySpeed
        } else {
            val prev = lastFix
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
            add = d
        }
        if (add <= 0.0) return emptyList()
        lastGpsDistanceAt = active
        calibrate(bySpeed != null && fix.accuracy <= 10f, active, add)
        return addDistance(add, active)
    }

    /** Every 30 s of good GPS (the satellites' speed, ≤ 10 m) and running cadence: one (cadence, stride) pair. */
    private fun calibrate(good: Boolean, active: Long, add: Double) {
        if (!good) {
            calFrom = null
            return
        }
        val from = calFrom ?: run {
            calFrom = Triple(active, distanceM, steps)
            return
        }
        val dtMs = active - from.first
        if (dtMs < 30_000) return
        val ds = steps - from.third
        val dd = distanceM + add - from.second
        val cad = ds * 60_000.0 / dtMs
        if (ds >= 40 && cad >= 120) strides += cad to dd / ds
        calFrom = Triple(active, distanceM + add, steps)
    }

    /** Adds a stretch of the way: work distance, the pace window and the kilometre marks. */
    private fun addDistance(add: Double, active: Long): List<RunEvent> {
        distanceM += add
        val seg = RunTimeline.at(workout, (planAt(active) / 1000).toInt()).segment
        if (seg != null && (seg.kind == SegmentKind.WORK || seg.kind == SegmentKind.RUN)) workDistanceM += add
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

    /** Current pace: the satellites' speed over the last 10 s when the phone gives it, else the distance over ~30 s. */
    fun currentPace(): Double? {
        if (speeds.size >= 3) {
            val v = speeds.sumOf { it.second.toDouble() } / speeds.size
            return if (v < 0.5) null else 1000.0 / v
        }
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
        taps.toList(), lapList.toList(), skippedReps.sorted(), starts.toMap(), heldMs, lastPressMs,
        line?.let { listOf(it.first, it.second) }, freeLaps,
    )

    /** Start and end of every segment on the timeline, ms. */
    private val bounds: List<LongRange> by lazy {
        var start = 0L
        workout.segments.map { s -> (start until start + s.seconds * 1000L).also { start += s.seconds * 1000L } }
    }

    /**
     * The WORK segment a press at timeline [t] belongs to: the one running, or one that ended a moment ago (a rep finished
     * a little slower than the beep: up to 40 % of the rep, at most half of what follows). A skipped rep gets no laps.
     */
    private fun repAt(t: Long): Int? {
        val segs = workout.segments
        for (i in segs.indices) {
            if (segs[i].kind != SegmentKind.WORK || i in skippedReps) continue
            val b = bounds[i]
            val after = segs.getOrNull(i + 1)?.seconds ?: 0
            val grace = minOf(after * 500L, maxOf(20_000L, segs[i].seconds * 400L))
            if (t >= b.first && t < b.last + 1 + grace) return i
        }
        return null
    }

    /** The segment running at timeline [t] (the last one past the plan); −1 for an open-ended run. */
    private fun segmentAt(t: Long): Int =
        if (workout.isOpenEnded) -1 else RunTimeline.at(workout, (t / 1000).toInt()).index.coerceAtMost(workout.segments.lastIndex)

    /** Where the laps of segment [i] are counted from: the runner's own start press, or the planned start. */
    private fun startOf(i: Int): Long = starts[i] ?: if (i < 0) 0L else bounds[i].first

    /** The runner crossed the line. Returns the lap just finished, or null when the press was not a lap (see [press]). */
    fun lap(nowMs: Long): Lap? = (press(nowMs) as? Press.Done)?.lap

    /**
     * The lap button. A second press within seconds of the last one is a double tap; a press on the line as a segment
     * begins is the runner's own start (laps count from it); anything else closes a lap. The press that closes a rep's
     * last lap before its planned end finishes the rep: the recovery starts at once.
     */
    fun press(nowMs: Long): Press {
        if (state != State.RUNNING) return Press.Ignored
        val active = activeMs(nowMs)
        pressTimes += active
        learnLine(active)
        // The plan waits on the line: this is the start, whenever it comes (never a double tap of a lap before it).
        val w = waitsAt
        if (w != null && waiting(nowMs)) {
            lastPressMs = active
            release(nowMs)
            starts[w] = bounds[w].first
            return Press.Start
        }
        val last = lastPressMs
        if (last != null && active - last < MIN_LAP_MS) return Press.Ignored
        lastPressMs = active
        return pressAt(timelineMs(nowMs), auto = false)
    }

    /**
     * A lap at timeline [t]: pressed, or [auto] — a crossing GPS saw with no press. An automatic one never starts the
     * plan or ends a rep early (the plan goes on by the clock), and never comes while the plan waits on the line.
     */
    private fun pressAt(t: Long, auto: Boolean): Press {
        val lapM = workout.lapMeters
        // The rep this press belongs to, unless all of its laps are pressed already.
        val rep = repAt(t)?.takeIf { i ->
            val laps = workout.segments[i].laps
            laps == null || taps.count { it >= bounds[i].first } < kotlin.math.ceil(laps).toInt()
        }
        val i = rep ?: segmentAt(t)
        val planned = if (i < 0) 0L else bounds[i].first
        val before = taps.count { it >= planned }
        val seg = workout.segments.getOrNull(i)
        val laps = if (rep != null) seg?.laps else null
        // A rep of 6.5 laps starts half a lap before the line: its first "lap" is the half.
        val part = if (before == 0 && laps != null && laps % 1.0 != 0.0) laps % 1.0 else 1.0
        // On the line as the segment begins (well under its first lap): the start, not a lap.
        val firstLapMs = (seg?.lapSec ?: 0.0) * part * 1000
        if (before == 0 && i !in starts && t - planned < maxOf(MIN_LAP_MS.toDouble(), 0.4 * firstLapMs)) {
            starts[i] = t
            return Press.Start
        }
        // Outside a rep a crossing GPS saw is only distance: no lap list of the warm-up for laps nobody pressed.
        if (auto && rep == null) {
            freeLaps += 1.0
            return Press.Ignored
        }
        val prev = taps.lastOrNull()
        val start = startOf(i)
        val from = if (prev != null && prev >= start) prev else start
        val result = if (rep != null && seg != null) {
            val target = seg.lapSec?.times(part)
            // A rep in laps is judged lap by lap; a timed one (no lap count fits it) has no lap to judge against.
            val range = if (laps == null) null else seg.pace?.let { p -> lapM?.let { Track.lapSec(p.fast.toDouble(), it) * part to Track.lapSec(p.slow.toDouble(), it) * part } }
            val number = workout.segments.take(rep + 1).count { it.kind == SegmentKind.WORK }
            val of = laps?.let { kotlin.math.ceil(it).toInt() }
            // A time trial is run evenly at first; in its second half faster is what it is for.
            val free = workout.testMeters != null && of != null && (before + 1) * 2 > of
            Lap((t - from) / 1000.0, if (free) null else range?.first ?: target, range?.second ?: target, before + 1, of, number, part, auto)
        } else {
            // An easy pace has only a fast limit: slower is fine.
            val pace = seg?.pace?.takeIf { seg.kind != SegmentKind.WORK }
            val fast = pace?.let { p -> lapM?.let { Track.lapSec(p.fast.toDouble(), it) } }
            Lap((t - from) / 1000.0, fast, null, before + 1, null, auto = auto)
        }
        taps += t
        lapList += result
        // The rep's last lap, pressed before its planned end: the rep is done, the recovery starts now.
        if (rep != null && result.closesRep && !auto) {
            val end = bounds[rep].last + 1
            if (t < end) {
                skipMs += end - t
                lastTickSec = (end / 1000).toInt() - 1
            }
        }
        return Press.Done(result)
    }

    /** A press is made on the line: the fix of that moment moves the line toward it (a far one starts a new stadium). */
    private fun learnLine(active: Long) {
        if (workout.lapMeters == null) return
        val f = lastRaw ?: return
        if (active - lastRawActive > 3_000 || f.accuracy > LINE_ACCURACY) return
        val ln = line
        line = when {
            ln == null || Geo.haversine(ln.first, ln.second, f.lat, f.lon) > 60.0 -> f.lat to f.lon
            else -> (ln.first + (f.lat - ln.first) * 0.3) to (ln.second + (f.lon - ln.second) * 0.3)
        }
    }

    /**
     * A crossing of the line from GPS: the runner was well away (a sixth of the lap, 40 m at least), then the first fix
     * within 15 m of the line — the crossing is that moment plus the metres left at the rep's pace (a runner who stops at
     * the line makes the closest fix a poor guess; the way in is steady). Standing at the line in a recovery is not a
     * crossing: the runner has to leave first. Fixes a second apart and a few metres off give about ±2 s — enough to
     * catch a lap nobody pressed, not to replace the button.
     */
    private fun watchLine(fix: Fix, active: Long) {
        val lapM = workout.lapMeters ?: return
        val ln = line ?: return
        if (fix.accuracy > LINE_ACCURACY) return
        val d = Geo.haversine(ln.first, ln.second, fix.lat, fix.lon)
        if (d > maxOf(40.0, lapM / 6.0)) {
            away = true
            return
        }
        if (!away || d > 15.0) return
        away = false
        // The pace of the rep being run (a lap a little slower or faster changes the guess by a fraction of a second).
        val seg = RunTimeline.at(workout, (planAt(active) / 1000).toInt()).segment
        val speed = seg?.lapSec?.let { lapM / it } ?: 3.5
        val tc = active + (d / speed * 1000).toLong()
        val minGap = (lapM / 9.0 * 1000).toLong()
        if (lastCross?.let { tc - it >= minGap } != false) {
            pending.addLast(tc)
            lastCross = tc
        }
    }

    /**
     * Crossings with no press within [AUTO_WAIT_MS] become laps: in a rep a lap marked as GPS-timed (returned, for the
     * coach to say it), elsewhere only distance. Nothing starts the plan by itself while it waits on the line.
     */
    fun autoLap(nowMs: Long): Lap? {
        if (state != State.RUNNING || workout.lapMeters == null) return null
        val now = activeMs(nowMs)
        var made: Lap? = null
        while (pending.isNotEmpty() && now - pending.first() >= AUTO_WAIT_MS) {
            val tc = pending.removeFirst()
            if (pressTimes.any { kotlin.math.abs(it - tc) <= AUTO_WAIT_MS }) continue
            // Before the first rep (the warm-up, the wait on the line) a crossing is distance only: pressAt finds no rep.
            val p = pressAt(planAt(tc), auto = true)
            if (p is Press.Done) made = p.lap
        }
        return made
    }

    /** Distance from the laps (pressed, and the ones GPS saw): on a small loop better than GPS, which cuts the bends. */
    fun lapDistanceM(): Double = workout.lapMeters?.let { (lapList.sumOf { it.part } + freeLaps) * it } ?: 0.0

    /** The distance to show: on a stadium the laps or GPS, whichever saw more (laps pressed only in the reps say little). */
    fun shownDistanceM(): Double = if (workout.lapMeters != null) maxOf(distanceM, lapDistanceM()) else distanceM

    /** Time of each WORK rep from the lap button: from its start to the press that closes its last lap (null if none). */
    fun repTimes(): List<Double?> {
        val work = workout.segments.indices.filter { workout.segments[it].kind == SegmentKind.WORK }
        return work.mapIndexed { k, i ->
            val laps = workout.segments[i].laps ?: return@mapIndexed null
            val need = kotlin.math.ceil(laps).toInt()
            val at = lapList.indexOfFirst { it.rep == k + 1 && it.number == need }
            if (at < 0) null else (taps[at] - startOf(i)) / 1000.0
        }
    }

    /**
     * The trial's laps look like laps: none under half or over 1.7 times its planned time. A forgotten press makes one
     * lap double, and then a press in the cool-down would close the trial a minute late — the time is not trusted, the
     * runner types it.
     */
    private fun trialLapsSane(): Boolean {
        val seg = workout.segments.firstOrNull { it.kind == SegmentKind.WORK } ?: return true
        val lapSec = seg.lapSec ?: return true
        val laps = seg.laps ?: return true
        val first = if (laps % 1.0 != 0.0) laps % 1.0 else 1.0
        return lapList.filter { it.rep == 1 }.all { l ->
            val expected = lapSec * if (l.number == 1) first else 1.0
            l.seconds in expected * 0.5..expected * 1.7
        }
    }

    /** Seconds into the lap being run: since the last press, or since the current segment began if it came later. */
    fun lapClockSec(nowMs: Long): Int {
        val t = timelineMs(nowMs)
        val start = startOf(segmentAt(t))
        val prev = taps.lastOrNull()
        return ((t - if (prev != null && prev >= start) prev else start) / 1000).toInt().coerceAtLeast(0)
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
        val testSeconds = if (workout.testMeters != null && trialLapsSane()) reps.firstOrNull()?.roundToInt() else null
        return RunResult(
            completed = completed, rpe = rpe, durationSec = sec,
            // On a small loop GPS cuts the bends: without laps pressed the pace is unknown, not a guess.
            distanceM = shownDistanceM().takeIf { it > 0 }, workPace = lapPace ?: gpsPace.takeIf { lapM == null },
            workDistanceM = workDistanceM.takeIf { it > 0 }, workSec = if (workout.isOpenEnded) null else work,
            laps = lapList.toList().takeIf { it.isNotEmpty() }, testSeconds = testSeconds,
            // Steps a minute over the running time (the counter misses nothing while the screen is off).
            cadence = if (steps >= 100 && sec > 60) (steps * 60.0 / sec).toInt() else null,
        )
    }

    companion object {
        /** Presses closer than this are one press (a double tap): no lap on a stadium is this short. */
        const val MIN_LAP_MS = 5_000L

        /** A crossing GPS saw becomes a lap when no press came this close to it. */
        const val AUTO_WAIT_MS = 8_000L

        /** Fixes worse than this neither move the line nor count as passing it. */
        const val LINE_ACCURACY = 20f

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
            starts += s.starts
            heldMs = s.heldMs
            lastPressMs = s.lastPressMs
            line = s.line?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
            freeLaps = s.freeLaps
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
            lastTickSec = (planAt(s.activeMs) / 1000).toInt()
        }
    }
}
