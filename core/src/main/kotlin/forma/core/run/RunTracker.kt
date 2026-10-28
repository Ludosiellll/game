package forma.core.run

import kotlinx.serialization.Serializable
import kotlin.math.abs
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
    /** Steps so far, and the steps and time at running cadence (the run's cadence, not the rests'). */
    val steps: Long = 0,
    val runSteps: Long = 0,
    val runStepMs: Long = 0,
    /** The stride model the run started with (a, b), and the (cadence, stride) pairs measured in it. */
    val stride: List<Double>? = null,
    val strideSamples: List<List<Double>> = emptyList(),
    /** Runs without reps: the last visit of the line (timeline ms) and the GPS distance there; the start off the line. */
    val lineT: Long? = null,
    val lineDistM: Double? = null,
    val startedOff: Boolean = false,
    /** Well away from the line since the last crossing: the next one is seen. */
    val away: Boolean = false,
    /** Seconds of each whole kilometre, active time (pauses left out). */
    val kmSplits: List<Int> = emptyList(),
    /** The stadium's loop as fitted ([Oval.toGround]): line crossings timed on it. */
    val loop: List<Double>? = null,
    /** Every rep of laps waits on the line (see [RunTimeline.waitsOnLine]); false in a run saved before they did. */
    val waitEvery: Boolean = false,
)

/** What a press of the lap button was. */
sealed interface Press {
    /** A second press within seconds of the last one, or the run is not going. */
    data object Ignored : Press

    /** A press on the line as a segment began: the runner's own start, not a lap. */
    data object Start : Press

    /** A press far from the line on a loop GPS follows (a thumb on the screen mid-lap): no lap, said so. */
    data object OffLine : Press

    /** A press seconds after a lap GPS had already timed on the loop (and said): that lap, nothing new to say. */
    data class Timed(val lap: Lap) : Press

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
    /** Not pressed: GPS saw the runner cross the line and no press came. */
    val auto: Boolean = false,
    /** A lap GPS timed on the stadium's fitted loop ([LoopTimer]): to a few tenths, like a press. Without it about ±2 s. */
    val exact: Boolean = false,
) {
    /** The press that closed the rep's last lap. */
    val closesRep: Boolean get() = of != null && number >= of

    /** Timed only roughly: by GPS near the line, not on the fitted loop (about ±2 s). */
    val rough: Boolean get() = auto && !exact

    /**
     * −1 faster than the target, 1 slower, 0 on it; null without a target. No [slow] bound: any slower lap is fine. A
     * lap GPS timed is off by up to two or three seconds: it is called fast or slow only when it clearly is.
     */
    val verdict: Int? get() {
        if (fast == null && slow == null) return null
        // Roughly timed: a couple of seconds either way.
        if (rough) return when {
            fast != null && seconds < fast - 2.5 -> -1
            slow != null && seconds > slow + 2.5 -> 1
            else -> 0
        }
        // To the whole second, as the lap is said and the target shown («52» is not on pace for «50–51 с»).
        val s = seconds.roundToInt()
        return when {
            fast != null && s < fast.roundToInt() -> -1
            slow != null && s > slow.roundToInt() -> 1
            else -> 0
        }
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

    /** Seconds of each whole kilometre, active time: what the voice said at each kilometre. */
    private val kmSplitList = mutableListOf<Int>()
    val kmSplits: List<Int> get() = kmSplitList

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

    /** The step counter's own total at its last reading (it counts since the phone started), or null before one. */
    val stepTotal: Long? get() = lastStepCount
    var steps = 0L
        private set
    private var stepsActiveMs = 0L
    private val stepWindow = ArrayDeque<Pair<Long, Long>>()
    private var stride: Pair<Double, Double>? = null
    private var lastGpsDistanceAt = -1L

    /** The steps give the distance now (GPS lost): GPS starts afresh once, when the steps take over. */
    private var stepsLead = false

    /** Steps and time between counter events at running cadence (120–240 a minute): standing and walking left out. */
    private var runSteps = 0L
    private var runStepMs = 0L

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
        val dt = active - stepsActiveMs
        if (dt in 1..60_000 && delta * 60_000.0 / dt in 120.0..240.0) {
            runSteps += delta
            runStepMs += dt
        }
        stepsActiveMs = active
        stepWindow.addLast(active to steps)
        while (stepWindow.size > 2 && active - stepWindow.first().first > 30_000) stepWindow.removeFirst()
        val c = cadence() ?: return emptyList()
        val s = stride ?: return emptyList()
        if (c < 120 || active - lastGpsDistanceAt <= 5_000 && lastGpsDistanceAt >= 0) return emptyList()
        if (!stepsLead) {
            // Once, as the steps take over: the first fix after the gap is a new anchor, not a jump across it. (Cleared
            // on every counter event, GPS would never get two fixes in a row and never take the distance back.)
            stepsLead = true
            lastFix = null
            lastSpeed = null
            calFrom = null
        }
        return addDistance(delta * (s.first + s.second * c).coerceIn(0.5, 2.2), active)
    }

    /** Where GPS gives none, the steps give the distance: the stride is known and the counter shows a running cadence. */
    fun stepsGiveDistance(): Boolean = stride != null && (cadence() ?: 0) >= 120

    /** Steps a minute over the last half minute (at least 10 s of steps), or null. */
    fun cadence(): Int? {
        if (stepWindow.size < 2) return null
        val (t0, s0) = stepWindow.first()
        val (t1, s1) = stepWindow.last()
        if (t1 - t0 < 10_000) return null
        return ((s1 - s0) * 60_000.0 / (t1 - t0)).toInt().takeIf { it > 0 }
    }

    /**
     * Time the app did not see: the system closed it mid-run and it came back on its own, [gapMs] later. The phone's step
     * counter went on meanwhile: [gapSteps] steps (null when unknown). As much of the gap as the steps cover at the run's
     * own cadence counts as running (standing at a traffic light is not filled), and with a known stride the steps give
     * its way too. Nothing is said for the plan's moments skipped over. Returns the active ms added.
     */
    fun fillGap(gapMs: Long, gapSteps: Long?, nowMs: Long): Long {
        if (state != State.RUNNING || gapMs <= 0 || gapSteps == null || gapSteps <= 0) return 0
        // Implausibly many steps for the time (a counter that jumped): nothing is guessed from them.
        if (gapSteps > 4 * (gapMs / 1000 + 1)) return 0
        val cad = (if (runSteps >= 100 && runStepMs > 30_000) runSteps * 60_000.0 / runStepMs else GAP_CADENCE).coerceIn(120.0, 240.0)
        val covered = minOf(gapMs, (gapSteps * 60_000.0 / cad).toLong())
        if (covered < 1000) return 0
        startedAt -= covered
        steps += gapSteps
        val active = activeMs(nowMs)
        stepsActiveMs = active
        stride?.let { s ->
            addDistance(gapSteps * (s.first + s.second * cad).coerceIn(0.5, 2.2), active)
            // GPS starts afresh after the gap: its first fix is a new anchor, not a jump across the way run unseen.
            lastFix = null
            lastSpeed = null
        }
        lastTickSec = (timelineMs(nowMs) / 1000).toInt()
        return covered
    }

    /** The reps that wait for the start on the line (see [RunTimeline.waitsOnLine]). */
    private val waits: List<Int> = RunTimeline.waitsOnLine(workout)

    /** Every rep waits, not only the first: false for a run restored from before they did (its later reps ran by the clock). */
    private var waitEvery = true

    /** The rep the plan waits for next on the line: the first of [waits] neither started nor skipped. */
    private fun nextWait(): Int? = (if (waitEvery) waits else waits.take(1)).firstOrNull { it !in starts && it !in skippedReps }

    /** Time the plan stood still at the end of the warm-up while the runner got to the line. */
    private var heldMs = 0L

    /** The start line on a stadium, from the GPS fixes at the presses (and remembered from the last session). */
    var line: Pair<Double, Double>? = null
        private set(v) {
            field = v
            v?.let { loop?.setLine(it.first, it.second) }
        }

    /** On a stadium: the loop fitted to the runner's fixes, and the line crossings timed on it to tenths. */
    private val loop: LoopTimer? = workout.lapMeters?.let { LoopTimer(it) }

    /** The loop from the last session on this stadium (see [Oval.toGround]): laps timed on it from the first one. */
    fun setLoop(ground: List<Double>?) {
        loop?.setGround(ground)
    }

    /** The loop as fitted (to keep for the next session), or null. */
    fun loopGround(): List<Double>? = loop?.ground()

    /** The loop's length as run (the lane, not the kerb), from two laps on; null before or off a stadium. */
    fun lapLength(): Double? = loop?.lapLength()

    /** The last usable fix and its active time: where the runner is when they press. */
    private var lastRaw: Fix? = null
    private var lastRawActive = 0L

    /** Line watching: the runner has been well away from it since the last crossing. */
    private var away = false
    private var lastCross: Long? = null

    /** A crossing seen by GPS: active ms, the GPS distance there, and whether it was timed on the fitted loop. */
    private data class Crossing(val at: Long, val dist: Double, val exact: Boolean, val speed: Double = 0.0)

    /** Crossings seen by GPS, waiting to see whether a press comes with them. */
    private val pending = ArrayDeque<Crossing>()

    /** GPS distance at the last lap or start on the line: a crossing seen by GPS needs most of a lap run since. */
    private var lineDistM = 0.0

    /**
     * Runs without reps: timeline moment of the last visit of the line (a press, or a crossing GPS saw), null until the
     * first one — laps GPS closes count from it.
     */
    private var lineT: Long? = null

    /** Runs without reps: the first usable fix was looked at (on the line — laps count from the start; off it — not). */
    private var looked = false

    /** Runs without reps: the run started away from the known line, so the way to it is no lap. */
    private var startedOff = false

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
        loop?.pause()
    }

    fun resume(nowMs: Long) {
        if (state != State.PAUSED) return
        pausedTotal += nowMs - pauseStart
        state = State.RUNNING
    }

    fun finish(nowMs: Long) {
        if (state == State.PAUSED) resume(nowMs)
        if (state == State.RUNNING) {
            // The last crossings come seconds after the line: taken now, before the clock stops — "Стоп" at the finish of a
            // trial keeps its last lap.
            loop?.let { lp ->
                lp.pause()
                val active = activeMs(nowMs)
                for (c in lp.crossings()) {
                    if (!c.rough && upgrade(c.activeMs)) continue
                    pending.addLast(Crossing(c.activeMs, distanceM - c.speed * (active - c.activeMs).coerceAtLeast(0) / 1000.0, !c.rough, c.speed))
                }
            }
            resolve(activeMs(nowMs), all = true)
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

    /** The plan at [active] ms of running: it stands at the last moment before a rep until the runner starts it. */
    private fun planAt(active: Long): Long {
        val t = active + skipMs - heldMs
        val w = nextWait() ?: return t
        return minOf(t, bounds[w].first - 1)
    }

    /** The plan waits for the runner's start on the line: the warm-up or the recovery is over, the rep has not begun. */
    fun waiting(nowMs: Long): Boolean {
        val w = nextWait() ?: return false
        if (state != State.RUNNING && state != State.PAUSED) return false
        return activeMs(nowMs) + skipMs - heldMs >= bounds[w].first - 1
    }

    /**
     * The start found from GPS, no press needed: the plan waits on the line (or a rep of laps begins by the clock) and the
     * runner went from slow to most of the rep's speed. The rep starts when the run-up began (to a few tenths), as if
     * pressed then; a start from standing on the line also places the line there. True when a start was made (to say it).
     */
    fun autoStart(nowMs: Long): Boolean {
        val lp = loop ?: return false
        val lapM = workout.lapMeters ?: return false
        if (state != State.RUNNING || !lp.following()) return false
        val w = nextWait()?.takeIf { waiting(nowMs) }
        val t = timelineMs(nowMs)
        // The rep to start: the one the plan waits for, or one of laps that began by the clock in the last half minute
        // with no start and no lap yet.
        val i = w ?: workout.segments.indices.firstOrNull { j ->
            val s = workout.segments[j]
            s.kind == SegmentKind.WORK && s.laps != null && j !in starts && j !in skippedReps && j != nextWait() &&
                t - bounds[j].first in 0..30_000 && taps.none { it >= bounds[j].first }
        } ?: return false
        val seg = workout.segments[i]
        val fast = seg.lapSec?.let { 0.75 * lapM / it } ?: 3.0
        // Active time of the wait's beginning, or of the rep's planned start; an eager runner goes a second or two before.
        val begun = bounds[i].first - skipMs + heldMs - if (w != null) 1 else 0
        // A rep of 6.5 laps starts half a lap before the line.
        val part = seg.laps?.let { it % 1.0 }?.takeIf { it > 0.01 } ?: 0.0
        val pm = lp.oval?.perimeter ?: return false
        /** At the rep's start (within 20 m), or the line not known: a stride or a jog to the line elsewhere is no start. */
        fun atStart(ms: Long): Boolean {
            if (!lp.lineOnLoop()) return true
            val past = lp.pastLine(ms) ?: return false
            val d = abs(past - (1 - part) * pm) % pm
            return minOf(d, pm - d) <= START_NEAR_M
        }
        val active = activeMs(nowMs)
        // Off from standing (a stride from a jog in the warm-up is no start); or, on the line, through it at speed — a rolling
        // start. After a recovery there are no strides: a pickup from the jog at the start is the start.
        val afterRest = w != null && w != waits.firstOrNull()
        val stand = lp.runUp(begun - 2_000, fast)?.takeIf { on ->
            val before = recent.filter { it.first in (on - 4_000)..(on - 500) }.map { it.second.speed }
            on >= begun - 2_000 && atStart(on) &&
                (afterRest || before.isEmpty() || before.any { it == null } || before.size >= 2 && before.map { it!! }.sorted()[before.size / 2] < 1.3f)
        }
        val rolling = if (stand != null || part > 0.0 || !lp.lineOnLoop()) null else pending.firstOrNull { c ->
            c.exact && c.at >= begun - 2_000 && c.speed >= fast && (lp.speed() ?: 0.0) >= 0.85 * fast
        }?.at
        val at = stand ?: rolling ?: return false
        if (w != null && at >= begun) {
            val tl = at + skipMs - heldMs
            if (tl > bounds[w].first) heldMs += tl - bounds[w].first
            starts[w] = bounds[w].first
        } else {
            // Before the plan's start (or a rep by the clock): the laps count from the start itself.
            starts[i] = at + skipMs - heldMs
        }
        lineT = starts[i]
        lastPressMs = at
        lineDistM = distanceM - (lp.speed() ?: 0.0) * (active - at) / 2000.0
        // Standing still before a rep of whole laps, at its start: that is where the line is (a line kept from another day
        // in the wrong place, or none, is put there).
        if (part == 0.0 && stand != null) {
            val stood = recent.filter { it.first in (at - 3_000)..at }.map { it.second }
            if (stood.size >= 2 && stood.all { (it.speed ?: 0f) < 0.7f && it.accuracy <= LINE_ACCURACY }) {
                val lat = stood.map { it.lat }.average()
                val lon = stood.map { it.lon }.average()
                val ln = line
                line = if (ln != null && lp.lineOnLoop() && Geo.haversine(ln.first, ln.second, lat, lon) <= 15.0) {
                    (ln.first + (lat - ln.first) * 0.3) to (ln.second + (lon - ln.second) * 0.3)
                } else lat to lon
            }
        }
        return true
    }

    /** The line to keep for the next session: none that lies off the loop GPS found (see [LoopTimer.lineOnLoop]). */
    fun keptLine(): Pair<Double, Double>? = line?.takeIf { loop?.oval == null || loop.lineOnLoop() }

    /** Ends the wait on the line: the plan goes on from the start of the rep. */
    private fun release(nowMs: Long) {
        val w = nextWait() ?: return
        val t = activeMs(nowMs) + skipMs - heldMs
        if (t > bounds[w].first) heldMs += t - bounds[w].first
    }

    /** Jumps to the start of the next segment. Skipped running time does not count as done. */
    fun skipSegment(nowMs: Long) {
        if (workout.isOpenEnded || (state != State.RUNNING && state != State.PAUSED)) return
        val wait = nextWait()?.takeIf { waiting(nowMs) }
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
        recent.addLast(active to fix)
        while (recent.size > 1 && active - recent.first().first > 40_000) recent.removeFirst()
        watchLine(fix, active)
        loop?.let { feedLoop(it, fix, active) }
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
        stepsLead = false
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

    /** The fixes of the last 40 s (active ms): where the runner stood at a start found after it. */
    private val recent = ArrayDeque<Pair<Long, Fix>>()

    /** A fix for the loop's filter; the crossings it timed wait for a press like any (see [autoLap]). */
    private fun feedLoop(lp: LoopTimer, fix: Fix, active: Long) {
        lp.fix(active, fix.lat, fix.lon, fix.accuracy, fix.speed)
        for (c in lp.crossings()) {
            val back = (active - c.activeMs).coerceAtLeast(0)
            if (!c.rough && upgrade(c.activeMs)) continue
            pending.addLast(Crossing(c.activeMs, distanceM - c.speed * back / 1000.0, exact = !c.rough, speed = c.speed))
            lastCross = c.activeMs
        }
        // A free run with no line on this loop: the line is where the runner is when the loop is first followed.
        if (workout.reps == 0 && lp.following() && !lp.lineOnLoop() && fix.accuracy <= LINE_ACCURACY && lp.near(fix.lat, fix.lon)) line = fix.lat to fix.lon
    }

    /**
     * A crossing timed on the loop that GPS had seen before the loop was known (the first lap or two): the rough guess
     * waiting is dropped, and a lap already made of it is timed again — the exact time, the next lap counted from it.
     * True when it was such a lap (nothing more to do).
     */
    private fun upgrade(at: Long): Boolean {
        pending.removeAll { !it.exact && kotlin.math.abs(it.at - at) <= UPGRADE_MS }
        val k = lapList.lastIndex
        if (k < 0) return false
        val lap = lapList[k]
        val t = planAt(at)
        if (!lap.rough || kotlin.math.abs(taps[k] - t) > UPGRADE_MS) return false
        val delta = t - taps[k]
        lapList[k] = lap.copy(seconds = lap.seconds + delta / 1000.0, exact = true)
        if (lineT == taps[k]) lineT = t
        taps[k] = t
        return true
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
            kmSplitList += total - lastKmSec
            lastKmSec = total
            nextKm++
        }
        return out
    }

    /**
     * Current pace: the satellites' speed over the last 10 s when the phone gives it, else the distance over ~30 s. With
     * [nowMs] nothing older than 10 s counts: GPS gone (trees, tall buildings) gives no pace, not the last one frozen.
     */
    fun currentPace(nowMs: Long? = null): Double? {
        val now = nowMs?.let { activeMs(it) }
        val fresh = if (now == null) speeds.toList() else speeds.filter { now - it.first <= 10_000 }
        if (fresh.size >= 3) {
            val v = fresh.sumOf { it.second.toDouble() } / fresh.size
            return if (v < 0.5) null else 1000.0 / v
        }
        if (window.size < 2) return null
        val (t0, d0) = window.first()
        val (t1, d1) = window.last()
        if (now != null && now - t1 > 10_000) return null
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
        line?.let { listOf(it.first, it.second) }, freeLaps, steps, runSteps, runStepMs,
        stride?.let { listOf(it.first, it.second) }, strides.map { listOf(it.first, it.second) },
        lineT, lineDistM, startedOff, away, kmSplitList.toList(), loop?.ground(), waitEvery,
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
        // The plan waits on the line: this is the start, whenever it comes (never a double tap of a lap before it).
        val w = nextWait()
        if (w != null && waiting(nowMs)) {
            lastPressMs = active
            release(nowMs)
            starts[w] = bounds[w].first
            lineDistM = distanceM
            lineT = timelineMs(nowMs)
            return Press.Start
        }
        val last = lastPressMs
        if (last != null && active - last < MIN_LAP_MS) return Press.Ignored
        val t = timelineMs(nowMs)
        // The last seconds of a recovery (the countdown), or the last minute of the warm-up (the voice said to come to the
        // start) with no lap pressed in it: on the line, his start, a little early — the rest of it is skipped.
        val next = nextWait()
        val early = when {
            next == null -> false
            next != waits.first() -> waitEvery && bounds[next].first - t in 1..EARLY_START_MS
            else -> bounds[next].first - t in 1..WARM_START_MS && taps.none { it >= bounds[next - 1].first }
        }
        if (next != null && early) {
            skipMs += bounds[next].first - t
            starts[next] = bounds[next].first
            lastPressMs = active
            lineDistM = distanceM
            lineT = bounds[next].first
            // The countdown's last beeps are not played after the start.
            lastTickSec = maxOf(lastTickSec, (bounds[next].first / 1000).toInt() - 1)
            return Press.Start
        }
        val retimed = retimeAuto(t)
        val p = retimed ?: pressAt(t, auto = false, far = farFromLine(active))
        if (p == Press.Ignored || p == Press.OffLine) {
            // A touch in the pocket or the hand, far too early for a lap or far from the line: as if it never came (a press
            // on the line seconds later still counts, and a lap GPS sees is not taken for its press).
            pressTimes.removeAt(pressTimes.lastIndex)
            return p
        }
        lastPressMs = active
        // A late press for a lap timed on the loop: the lap and the visit of the line stay as GPS had them — yet two late
        // presses at one spot still say the line is there (a line kept from another day in the wrong place).
        if (retimed is Press.Done && retimed.lap.exact) {
            lateLine(active, retimed.lap)
            return Press.Timed(retimed.lap)
        }
        lineDistM = distanceM
        lineT = t
        // Only a press that closes a lap is surely on the line: a start may be half a lap before it (6.5 laps). A press
        // that times a lap GPS closed comes seconds after the line it knows: late, or that line is not where he presses.
        if (p is Press.Done) if (retimed == null) learnLine(active, p.lap) else lateLine(active, p.lap)
        return p
    }

    /**
     * A press soon after a lap GPS closed — more than [AUTO_WAIT_MS] late: the screen was off, the button hard to find —
     * is the press for that same lap: it times the lap, it is not another lap of a few seconds that ends the rep early.
     */
    private fun retimeAuto(t: Long): Press? {
        val k = lapList.lastIndex
        if (k < 0) return null
        val lap = lapList[k]
        if (!lap.auto) return null
        // Timed on the loop: the late press is for it, and later than the line — the lap keeps the time GPS gave.
        if (lap.exact) return if (t - taps[k] in 0..(((lap.slow ?: lap.fast)?.let { it / lap.part } ?: (lap.seconds / lap.part)) * 500).toLong()) Press.Done(lap) else null
        // Within half a lap of it: the next crossing is a whole lap away.
        val whole = (lap.slow ?: lap.fast)?.let { it / lap.part } ?: (lap.seconds / lap.part)
        val late = t - taps[k]
        if (late <= 0 || late > whole * 500) return null
        val fixed = lap.copy(seconds = lap.seconds + late / 1000.0, auto = false)
        lapList[k] = fixed
        taps[k] = t
        // The rep's last lap, now pressed before the planned end: the recovery starts now, as with any press.
        val rep = fixed.rep?.let { n -> workout.segments.indices.filter { workout.segments[it].kind == SegmentKind.WORK }.getOrNull(n - 1) }
        if (rep != null && fixed.closesRep) {
            val end = bounds[rep].last + 1
            if (t < end) {
                skipMs += end - t
                // Cues already said up to now are not said again (the lap may come seconds after the line).
                lastTickSec = maxOf(lastTickSec, (end / 1000).toInt() - 1)
            }
        }
        return Press.Done(fixed)
    }

    /**
     * A lap at timeline [t]: pressed, or [auto] — a crossing GPS saw with no press. An automatic one never starts the
     * plan or ends a rep early (the plan goes on by the clock), and never comes while the plan waits on the line.
     */
    private fun pressAt(t: Long, auto: Boolean, dist: Double = distanceM, exact: Boolean = false, far: Boolean = false): Press {
        if (workout.reps == 0) return pressFree(t, auto, dist, exact, far)
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
            if (auto) lineDistM = dist
            return Press.Start
        }
        // Outside a rep a crossing GPS saw is only distance: no lap list of the warm-up for laps nobody pressed. A jog a
        // few dozen metres off the line and back is no lap: GPS must have seen most of one since the last.
        if (auto && rep == null) {
            if (lapM != null && dist - lineDistM >= 0.6 * lapM) freeLaps += 1.0
            // Either way the runner was at the line: the next lap counts from here.
            lineDistM = dist
            return Press.Ignored
        }
        val prev = taps.lastOrNull()
        val start = startOf(i)
        val from = if (prev != null && prev >= start) prev else start
        // A lap GPS closed far quicker than the rep's lap (a line learnt in the wrong place, a stray fix) is no lap.
        if (auto && seg?.lapSec != null && (t - from) / 1000.0 < 0.6 * seg.lapSec * part) return Press.Ignored
        val result = if (rep != null && seg != null) {
            val target = seg.lapSec?.times(part)
            // A rep in laps is judged lap by lap; a timed one (no lap count fits it) has no lap to judge against.
            val range = if (laps == null) null else seg.pace?.let { p -> lapM?.let { Track.lapSec(p.fast.toDouble(), it) * part to Track.lapSec(p.slow.toDouble(), it) * part } }
            val number = workout.segments.take(rep + 1).count { it.kind == SegmentKind.WORK }
            val of = laps?.let { kotlin.math.ceil(it).toInt() }
            // A time trial is run evenly at first; in its second half faster is what it is for.
            val free = workout.testMeters != null && of != null && (before + 1) * 2 > of
            Lap((t - from) / 1000.0, if (free) null else range?.first ?: target, range?.second ?: target, before + 1, of, number, part, auto, exact)
        } else {
            val l = freeLap(t - from, before + 1, seg, lapM, auto, exact)
            // The first press of a warm-up, a jog or a cool-down long after it began (on the line to mark a start, a lap
            // pressed only now): the laps count from here — no "lap" of eleven minutes.
            if (!auto && before == 0 && i !in starts && (l.fast ?: l.slow)?.let { l.seconds > LONG_LAP * it } == true) {
                starts[i] = t
                return Press.Start
            }
            l
        }
        if (!auto && tooShort(result)) return Press.Ignored
        if (!auto && far && !strayAgrees()) return Press.OffLine
        taps += t
        lapList += result
        if (auto) lineDistM = dist
        // The rep's last lap, pressed (or timed on the loop) before its planned end: the rep is done, the recovery starts
        // then. A rough GPS lap never ends a rep: two seconds off and the recovery starts at the wrong time.
        if (rep != null && result.closesRep && (!auto || exact)) {
            val end = bounds[rep].last + 1
            if (t < end) {
                skipMs += end - t
                // Cues already said up to now are not said again (the lap may come seconds after the line).
                lastTickSec = maxOf(lastTickSec, (end / 1000).toInt() - 1)
            }
        }
        return Press.Done(result)
    }

    /**
     * A lap of a run without reps (free, easy, long, run/walk on a stadium). Its laps go on through the run whatever its
     * segments, numbered from the start, each from the press or the line visit before it.
     *
     * A crossing GPS saw is a lap only from the second time on the line: a run started off it begins its laps there. A
     * jog off the line and back (under 0.6 of a lap of way) is no lap, nor is far more than a lap (off the track and
     * back) — both only mark the visit. A press counts from the press before it: a line GPS has slightly off never cuts
     * a lap the runner timed. Before the first lap a press is the start when it comes in the first seconds, soon after
     * GPS saw the line (the press for that visit), or as the first on the line after a start off it.
     */
    private fun pressFree(t: Long, auto: Boolean, dist: Double, exact: Boolean = false, far: Boolean = false): Press {
        val lapM = workout.lapMeters
        val seg = workout.segments.getOrNull(segmentAt(t))
        val prev = taps.lastOrNull()
        val number = lapList.size + 1
        val visit = lineT
        if (auto) {
            val way = dist - lineDistM
            lineDistM = dist
            lineT = t
            if (visit == null || lapM == null || way < 0.6 * lapM || way > 2.5 * lapM) return Press.Ignored
            val lap = freeLap(t - visit, number, seg, lapM, auto = true, exact = exact)
            taps += t
            lapList += lap
            return Press.Done(lap)
        }
        // Once started, a press is a lap: without GPS the way since the start stays 0 and would make every press a start.
        if (prev == null && -1 !in starts) {
            val sinceVisit = visit?.let { distanceM - lineDistM }
            // A lap target: a first press far quicker than a lap is the walk to the line after «Старт», not a lap.
            val ref = freeLap(t, 1, seg, lapM, auto = false).let { it.fast ?: it.slow }
            val start = when {
                visit == null && t < MIN_LAP_MS -> true
                visit == null && ref != null && t < WALK_TO_LINE * ref * 1000 -> true
                sinceVisit != null && lapM != null && sinceVisit < 0.5 * lapM -> true
                visit == null && startedOff -> true
                else -> false
            }
            if (start) {
                starts[-1] = t
                return Press.Start
            }
        }
        val from = prev ?: visit ?: starts[-1] ?: 0L
        val lap = freeLap(t - from, number, seg, lapM, auto = false)
        if (tooShort(lap)) return Press.Ignored
        if (far && !strayAgrees()) return Press.OffLine
        taps += t
        lapList += lap
        return Press.Done(lap)
    }

    /**
     * A lap outside the reps, [ms] long: an easy pace has only a fast limit (slower is fine); a free run's own target
     * ([RunWorkout.targetPace]) has both. A lap of run/walk mixes the two: no verdict.
     */
    private fun freeLap(ms: Long, number: Int, seg: Segment?, lapM: Int?, auto: Boolean, exact: Boolean = false): Lap {
        val own = workout.targetPace?.takeIf { workout.isOpenEnded }
        val pace = seg?.pace?.takeIf { seg.kind != SegmentKind.WORK && workout.type != RunType.RUN_WALK }
        val fast = (own ?: pace)?.let { p -> lapM?.let { Track.lapSec(p.fast.toDouble(), it) } }
        val slow = own?.let { p -> lapM?.let { Track.lapSec(p.slow.toDouble(), it) } }
        return Lap(ms / 1000.0, fast, slow, number, null, auto = auto, exact = exact)
    }

    /**
     * A pressed lap far quicker than its target (under [SHORT_LAP] of it): no one runs that, a sleeve or a pocket pressed
     * the button. It closes no lap and no rep, and the line is not moved to where it happened. Without a target any lap
     * of [MIN_LAP_MS] or more counts.
     */
    private fun tooShort(l: Lap): Boolean {
        // Faster than anyone runs a lap, whatever it has for a target (a jog's lap has none): a key or a touch. The volume
        // key pressed twice made a lap of 230 m in 6 s in a recovery.
        val lapM = workout.lapMeters
        if (lapM != null && l.seconds < lapM * l.part / MAX_LAP_SPEED) return true
        val ref = l.fast ?: l.slow ?: return false
        return l.seconds < SHORT_LAP * ref
    }

    /**
     * A press is made on the line: the fix of that moment moves the line toward it. Only a press whose [lap] took about
     * the time expected ([onTime]): a touch in the pocket mid-lap would move the line off, for this run and the next
     * ones. A press far from the line starts a new stadium once the next one agrees (one alone may be a stray touch).
     */
    private fun learnLine(active: Long, lap: Lap) {
        if (workout.lapMeters == null) return
        val f = lastRaw ?: return
        if (active - lastRawActive > 3_000 || f.accuracy > LINE_ACCURACY || !onTime(lap)) return
        val ln = line
        if (ln != null && Geo.haversine(ln.first, ln.second, f.lat, f.lon) > 60.0) {
            val first = farPress
            farPress = f.lat to f.lon
            if (first == null || Geo.haversine(first.first, first.second, f.lat, f.lon) > 30.0) return
            farPress = null
            line = (first.first + f.lat) / 2 to (first.second + f.lon) / 2
            return
        }
        farPress = null
        line = if (ln == null) f.lat to f.lon else (ln.first + (f.lat - ln.first) * 0.3) to (ln.second + (f.lon - ln.second) * 0.3)
    }

    /** A press far from the known line, waiting for the next one there to start a new stadium (see [learnLine]). */
    private var farPress: Pair<Double, Double>? = null

    /**
     * A press that timed a lap GPS had closed at the known line, seconds before. Late once (the button hard to find) it
     * lands anywhere; two in a row at the same spot (within [LATE_AGREE_M]) mean the line is there, not where it was kept.
     */
    private fun lateLine(active: Long, lap: Lap) {
        if (workout.lapMeters == null) return
        val f = lastRaw ?: return
        if (active - lastRawActive > 3_000 || f.accuracy > LINE_ACCURACY || !onTime(lap)) return
        val first = latePress
        latePress = f.lat to f.lon
        if (first == null || Geo.haversine(first.first, first.second, f.lat, f.lon) > LATE_AGREE_M) return
        latePress = null
        line = (first.first + f.lat) / 2 to (first.second + f.lon) / 2
    }

    /** The last late press (see [lateLine]). */
    private var latePress: Pair<Double, Double>? = null

    /**
     * A press at [active] far from the line on a loop GPS follows: more than [BEFORE_LINE_M] before it or [AFTER_LINE_M]
     * past it (a late press comes seconds after), with a fresh good fix. A thumb on the screen of a phone held in the hand,
     * mid-lap: no lap (see [strayAgrees]). Without the loop, the line on it or a good fix, nothing is known: false.
     */
    private fun farFromLine(active: Long): Boolean {
        val lp = loop ?: return false
        val f = lastRaw ?: return false
        if (!lp.ready() || active - lastRawActive > 3_000 || f.accuracy > LINE_ACCURACY) return false
        val past = lp.pastLine(active) ?: return false
        val pm = lp.oval?.perimeter ?: return false
        return past > AFTER_LINE_M && pm - past > BEFORE_LINE_M
    }

    /**
     * A press far from the line ([farFromLine]) counts only when the one before it was at the same spot (within
     * [LATE_AGREE_M]): two presses there say the line is there, not where GPS had it (a line kept from another day). One
     * alone is a stray touch.
     */
    private fun strayAgrees(): Boolean {
        val f = lastRaw ?: return false
        val first = strayPress
        strayPress = f.lat to f.lon
        if (first == null || Geo.haversine(first.first, first.second, f.lat, f.lon) > LATE_AGREE_M) return false
        strayPress = null
        line = (first.first + f.lat) / 2 to (first.second + f.lon) / 2
        return true
    }

    /** The last press far from the line (see [strayAgrees]). */
    private var strayPress: Pair<Double, Double>? = null

    /**
     * The lap [l] (the last one in the list) took about the time expected: within [LINE_LAP] of its target, or without one
     * of the laps pressed before it. A touch mid-lap gives a share of a lap, and the press on the line after it the rest.
     * With nothing to judge by (the first laps of a run without a target) it counts.
     */
    private fun onTime(l: Lap): Boolean {
        val before = lapList.dropLast(1).filter { !it.auto && it.part == 1.0 }.map { it.seconds }.sorted()
        val usual = before.takeIf { it.size >= 2 }?.let { it[it.size / 2] * l.part }
        // An easy run has only a fast limit: slower is fine, so its own laps (when there are) say how slow.
        val lo = l.fast ?: l.slow ?: usual ?: return true
        val hi = l.slow ?: usual ?: l.fast ?: return true
        return l.seconds in lo * (1 - LINE_LAP)..hi * (1 + LINE_LAP)
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
        if (workout.reps == 0 && !looked) {
            looked = true
            if (lineT == null && lapList.isEmpty()) {
                when {
                    // Started at the line (within GPS's few metres): the laps count from the start.
                    d <= 15.0 && active <= 5_000 -> {
                        lineT = 0L
                        lineDistM = 0.0
                        return
                    }
                    // GPS found only now, at the line: this is the first time on it.
                    d <= 15.0 -> {
                        lineT = planAt(active)
                        lineDistM = distanceM
                        return
                    }
                    // Started off it: the first time on the line is where the laps begin, however near it is.
                    else -> {
                        startedOff = true
                        away = true
                    }
                }
            }
        }
        if (d > maxOf(40.0, lapM / 6.0)) {
            away = true
            return
        }
        if (!away || d > 15.0) return
        away = false
        // The loop is known: its crossings are timed there (see [feedLoop]), far better than this guess.
        if (loop?.ready() == true) return
        // The satellites' speed, else the pace of the rep being run (a lap a little slower or faster changes the guess by a
        // fraction of a second; walking at the run's pace would put the crossing seconds early).
        val seg = RunTimeline.at(workout, (planAt(active) / 1000).toInt()).segment
        val speed = fix.speed?.takeIf { it >= 0.5f }?.toDouble() ?: seg?.lapSec?.let { lapM / it } ?: 3.5
        val tc = active + (d / speed * 1000).toLong()
        val minGap = (lapM / 9.0 * 1000).toLong()
        if (lastCross?.let { tc - it >= minGap } != false) {
            pending.addLast(Crossing(tc, distanceM + d, exact = false))
            lastCross = tc
        }
    }

    /**
     * Crossings with no press within [AUTO_WAIT_MS] become laps: in a rep a lap marked as GPS-timed (returned, for the
     * coach to say it), elsewhere only distance. Nothing starts the plan by itself while it waits on the line.
     */
    fun autoLap(nowMs: Long): Lap? {
        if (state != State.RUNNING || workout.lapMeters == null) return null
        return resolve(activeMs(nowMs), all = false)
    }

    /** The crossings waiting whose wait is over ([all]: every one, at the finish). Returns the last lap made. */
    private fun resolve(now: Long, all: Boolean): Lap? {
        var made: Lap? = null
        while (pending.isNotEmpty() && (all || now - pending.first().at >= if (pending.first().exact) EXACT_WAIT_MS else AUTO_WAIT_MS)) {
            val c = pending.removeFirst()
            val wait = if (c.exact) EXACT_WAIT_MS else AUTO_WAIT_MS
            if (pressTimes.any { kotlin.math.abs(it - c.at) <= wait }) continue
            // Before the first rep (the warm-up, the wait on the line) a crossing is distance only: pressAt finds no rep.
            val p = pressAt(planAt(c.at), auto = true, dist = c.dist, exact = c.exact)
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
            kmSplits = kmSplitList.toList().takeIf { it.isNotEmpty() },
            // Steps a minute while running (the counter misses nothing with the screen off; rests and walking left out).
            cadence = if (runSteps >= 100 && runStepMs > 60_000) (runSteps * 60_000.0 / runStepMs).toInt() else null,
            // The loop as GPS fitted it: the laps are read on it when the lap length set is clearly off.
            loopM = if (lapM != null) lapLength() else null,
        )
    }

    companion object {
        /** Presses closer than this are one press (a double tap): no lap on a stadium is this short. */
        const val MIN_LAP_MS = 5_000L

        /** A pressed lap under this share of its target is a stray touch, not a lap (see [tooShort]). */
        const val SHORT_LAP = 0.45

        /** No lap is run faster than this (m/s; the 200 m record is 10.4): a lap pressed faster is a touch (see [tooShort]). */
        const val MAX_LAP_SPEED = 10.0

        /** A press this far before the line or past it, on a loop GPS follows, is not on it (see [farFromLine]). */
        const val BEFORE_LINE_M = 15.0
        const val AFTER_LINE_M = 30.0

        /** A press this close before a rep that waits on the line is its start, a little early. */
        const val EARLY_START_MS = 5_000L

        /** The same for the first rep in the last minute of the warm-up, when no lap of it was pressed. */
        const val WARM_START_MS = 60_000L

        /** A first press this many laps' time into a warm-up, a jog or a cool-down marks a start, not a lap. */
        const val LONG_LAP = 3.0

        /** A free run's first press under this share of its target lap is the walk to the line: the start. */
        const val WALK_TO_LINE = 0.6

        /** A crossing GPS saw becomes a lap when no press came this close to it. */
        const val AUTO_WAIT_MS = 8_000L

        /** A start found by GPS is a start within this of the rep's own start (the line, or part of a lap before it). */
        const val START_NEAR_M = 20.0

        /** A rough crossing and one timed on the loop this close are one. */
        const val UPGRADE_MS = 10_000L

        /** The same for a crossing timed on the fitted loop: it comes [LoopTimer.LAG_MS] after the line, a second more. */
        const val EXACT_WAIT_MS = LoopTimer.LAG_MS + 1_000L

        /** Steps a minute assumed for a gap when the run has not measured its own cadence yet. */
        const val GAP_CADENCE = 160.0

        /** Fixes worse than this neither move the line nor count as passing it. */
        const val LINE_ACCURACY = 20f
        /** Two late presses this close together: the line is where he presses (see lateLine). */
        const val LATE_AGREE_M = 12.0

        /** A press moves the line only when its lap is within this share of the lap expected (see [onTime]). */
        const val LINE_LAP = 0.25

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
            waitEvery = s.waitEvery
            lastPressMs = s.lastPressMs
            line = s.line?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
            setLoop(s.loop)
            freeLaps = s.freeLaps
            lineDistM = s.lineDistM ?: s.distanceM
            lineT = s.lineT
            startedOff = s.startedOff
            away = s.away
            // The start is long past: no first look at the line after a restore.
            looked = true
            kmSplitList += s.kmSplits
            steps = s.steps
            runSteps = s.runSteps
            runStepMs = s.runStepMs
            stride = s.stride?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
            strides += s.strideSamples.filter { it.size == 2 }.map { it[0] to it[1] }
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
