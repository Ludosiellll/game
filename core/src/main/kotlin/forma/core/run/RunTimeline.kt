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
    data class Finished(override val at: Int) : Cue
}

object RunTimeline {
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
        segs.forEachIndexed { i, s ->
            val end = start + s.seconds
            out += Cue.SegmentStart(start, i, s, segs.getOrNull(i + 1))
            if (s.seconds >= 6 * 60) out += Cue.Halfway(start + s.seconds / 2, i)
            if (s.seconds >= 3 * 60) out += Cue.MinuteLeft(end - 60, i)
            if (s.seconds >= 10) for (left in 3 downTo 1) out += Cue.Countdown(end - left, i, left)
            start = end
        }
        if (segs.isNotEmpty()) out += Cue.Finished(start)
        return out.sortedBy { it.at }
    }

    /** Cues whose second lies in (fromExclusive, toInclusive]. Start cue of the first segment fires at 0. */
    fun cuesBetween(workout: RunWorkout, fromExclusive: Int, toInclusive: Int): List<Cue> =
        allCues(workout).filter { it.at > fromExclusive && it.at <= toInclusive }
}
