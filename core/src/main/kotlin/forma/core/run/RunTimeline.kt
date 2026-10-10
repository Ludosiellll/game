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
    data class Finished(override val at: Int) : Cue
}

object RunTimeline {
    /**
     * On a stadium the first rep (and the time trial) waits for the runner on the line: after the warm-up the plan stands
     * still until the lap button is pressed at the start, wherever on the track the warm-up ended. Its index, or null.
     */
    fun startOnLine(workout: RunWorkout): Int? {
        if (workout.lapMeters == null) return null
        val i = workout.segments.indexOfFirst { it.kind == SegmentKind.WORK }
        return i.takeIf { it > 0 && workout.segments[it].laps != null }
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
