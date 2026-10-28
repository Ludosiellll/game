package forma.core.run

/** Where a running workout is at a given second. */
data class TimelineState(
    val index: Int,
    val segment: Segment?,
    val segmentElapsed: Int,
    val segmentRemaining: Int,
    val totalElapsed: Int,
    val totalRemaining: Int,
    val finished: Boolean,
) {
    val next: Int get() = index + 1
}

/** Something the coach should say or beep at a precise second. */
sealed interface Cue {
    val at: Int

    data class SegmentStart(override val at: Int, val index: Int, val segment: Segment, val next: Segment?) : Cue
    data class Countdown(override val at: Int, val index: Int, val left: Int) : Cue
    data class Halfway(override val at: Int, val index: Int) : Cue
    data class MinuteLeft(override val at: Int, val index: Int) : Cue
    /** On a track: the moment a lap of a rep should be done (a pacer beep); [lap] of [of]. */
    data class LapMark(override val at: Int, val index: Int, val lap: Int, val of: Int) : Cue
    /** The warm-up is over: the rep [index] waits for the runner's start press on the line (see [RunTimeline.startOnLine]). */
    data class ToLine(override val at: Int, val index: Int) : Cue
    /**
     * Near the end of a warm-up before fast running: the stride [n] of [of] starts ([on]) or ends (see
     * [RunTimeline.stride]).
     */
    data class Stride(override val at: Int, val index: Int, val n: Int, val of: Int, val on: Boolean) : Cue
    data class Finished(override val at: Int) : Cue
}

object RunTimeline {
    /**
     * On a stadium the first rep (and the time trial) waits for the runner on the line: after the warm-up the plan stands
     * still until the lap button is pressed at the start, wherever on the track the warm-up ended. Its index, or null.
     */
    fun startOnLine(workout: RunWorkout): Int? = waitsOnLine(workout).firstOrNull()

    /**
     * Every rep of laps on a stadium waits for the runner on the line, not only the first: its time counts from his start
     * (the press, or the start GPS sees), not from the end of the recovery by the clock — a rep that ran off without him
     * gave lap times from a start he never made. The recovery is then as long as he takes, at least the plan's.
     */
    fun waitsOnLine(workout: RunWorkout): List<Int> {
        if (workout.lapMeters == null) return emptyList()
        return workout.segments.indices.filter { it > 0 && workout.segments[it].kind == SegmentKind.WORK && workout.segments[it].laps != null }
    }

    fun at(workout: RunWorkout, elapsed: Int): TimelineState {
        val total = workout.totalSeconds
        if (workout.isOpenEnded) return TimelineState(0, null, elapsed, 0, elapsed, 0, false)
        var start = 0
        workout.segments.forEachIndexed { i, s ->
            val end = start + s.seconds
            if (elapsed < end) {
                return TimelineState(i, s, elapsed - start, end - elapsed, elapsed, total - elapsed, false)
            }
            start = end
        }
        return TimelineState(workout.segments.size, null, 0, 0, total, 0, true)
    }

    private fun fast(w: RunWorkout) = w.type == RunType.GOAL_PACE || w.type == RunType.INTERVALS || w.type == RunType.TEST

    /** Strides at the end of a warm-up before fast running: [STRIDES] of [STRIDE_SEC] s, [STRIDE_JOG] s of jogging between. */
    const val STRIDES = 4
    const val STRIDE_SEC = 15
    const val STRIDE_JOG = 30

    /** The strides start this long before the warm-up ends: the last is over a minute before it (time to get to the line). */
    private const val STRIDES_BEFORE_END = 225

    /** Said this long before the first stride. */
    private const val STRIDES_AHEAD = 30

    /**
     * Where the strides of a warm-up stand: in the stride [n] of [of] ([on]: [left] seconds of it to go, [into] seconds
     * run), or jogging before it ([left] seconds until it starts).
     */
    data class StrideNow(val n: Int, val of: Int, val on: Boolean, val left: Int, val into: Int = 0)

    /**
     * The second of the plan the strides start, before race pace, intervals or a time trial (common coaching practice:
     * the legs and breathing meet the fast pace before it counts); null when the workout has none.
     */
    fun stridesFrom(w: RunWorkout): Int? {
        if (!fast(w)) return null
        var start = 0
        w.segments.forEachIndexed { i, s ->
            val end = start + s.seconds
            if (s.kind == SegmentKind.WARMUP && s.seconds >= 8 * 60 && w.segments.getOrNull(i + 1)?.kind == SegmentKind.WORK) return end - STRIDES_BEFORE_END
            start = end
        }
        return null
    }

    /** The strides at second [elapsed] of the plan: from [STRIDES_AHEAD] s before the first to the end of the last; else null. */
    fun stride(w: RunWorkout, elapsed: Int): StrideNow? {
        val t = elapsed - (stridesFrom(w) ?: return null)
        if (t < -STRIDES_AHEAD) return null
        if (t < 0) return StrideNow(1, STRIDES, false, -t)
        val k = t / (STRIDE_SEC + STRIDE_JOG)
        val into = t % (STRIDE_SEC + STRIDE_JOG)
        return when {
            k >= STRIDES -> null
            into < STRIDE_SEC -> StrideNow(k + 1, STRIDES, true, STRIDE_SEC - into, into)
            k + 1 < STRIDES -> StrideNow(k + 2, STRIDES, false, STRIDE_SEC + STRIDE_JOG - into)
            else -> null
        }
    }

    /** Every cue for the whole workout, in time order. */
    fun allCues(workout: RunWorkout): List<Cue> {
        val out = ArrayList<Cue>()
        var start = 0
        val segs = workout.segments
        val waits = startOnLine(workout)
        segs.forEachIndexed { i, s ->
            val end = start + s.seconds
            out += Cue.SegmentStart(start, i, s, segs.getOrNull(i + 1))
            val laps = s.laps
            val lapSec = s.lapSec
            if (s.kind == SegmentKind.WORK && laps != null && lapSec != null && laps > 1) {
                // Pacer: a beep when each lap should be done (the rep's end has the countdown). A rep of 6.5 laps
                // starts half a lap before the line, so its first beep comes after half a lap.
                val first = if (laps % 1.0 != 0.0) laps % 1.0 else 1.0
                val of = kotlin.math.ceil(laps).toInt()
                var k = 0
                var at = first
                while (at < laps - 0.01) {
                    out += Cue.LapMark(start + kotlin.math.round(at * lapSec).toInt(), i, k + 1, of)
                    k++
                    at += 1.0
                }
                // A rep with room past its target (a time trial): a beep at the target finish too.
                if (s.seconds > laps * lapSec + 2) out += Cue.LapMark(start + kotlin.math.round(laps * lapSec).toInt(), i, of, of)
            } else {
                if (s.seconds >= 6 * 60) out += Cue.Halfway(start + s.seconds / 2, i)
                if (s.seconds >= 3 * 60) out += Cue.MinuteLeft(end - 60, i)
                // Before race pace, intervals or a time trial: strides in the last minutes of the warm-up.
                val from = stridesFrom(workout)
                if (from != null && from in start until end) for (k in 0 until STRIDES) {
                    val on = from + k * (STRIDE_SEC + STRIDE_JOG)
                    out += Cue.Stride(on, i, k + 1, STRIDES, true)
                    out += Cue.Stride(on + STRIDE_SEC, i, k + 1, STRIDES, false)
                }
            }
            // Before a rep that waits on the line no countdown: the runner starts it, the app asks to go to the line.
            if (waits == i + 1) out += Cue.ToLine(end - 1, i + 1)
            else if (s.seconds >= 10) for (left in 3 downTo 1) out += Cue.Countdown(end - left, i, left)
            start = end
        }
        if (segs.isNotEmpty()) out += Cue.Finished(start)
        return out.sortedBy { it.at }
    }

    /** Cues whose second lies in (fromExclusive, toInclusive]. Start cue of the first segment fires at 0. */
    fun cuesBetween(workout: RunWorkout, fromExclusive: Int, toInclusive: Int): List<Cue> =
        allCues(workout).filter { it.at > fromExclusive && it.at <= toInclusive }
}
