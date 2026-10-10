package forma.core.run

import kotlinx.serialization.Serializable

@Serializable
enum class RunType { RUN_WALK, EASY, LONG, INTERVALS, TEMPO, TEST, FREE, GOAL_PACE }

@Serializable
enum class SegmentKind { WARMUP, RUN, WALK, WORK, RECOVERY, COOLDOWN }

/** Target pace range, seconds per km. [fast] < [slow]. */
@Serializable
data class PaceRange(val fast: Int, val slow: Int)

@Serializable
data class Segment(
    val kind: SegmentKind,
    val seconds: Int,
    val pace: PaceRange? = null,
    /** On a track: the rep's length in laps (6.5 starts half a lap before the line). */
    val laps: Double? = null,
    /** On a track: seconds per lap at the target pace. */
    val lapSec: Double? = null,
)

@Serializable
data class RunWorkout(
    val type: RunType,
    val title: String,
    val segments: List<Segment>,
    /** Short explanation shown before the start. */
    val note: String = "",
    /** Effort the session should feel like, CR-10. */
    val targetRpe: Int = 5,
    /** Stage in its progression ladder, if any. */
    val stage: Int = 0,
    /** Made lighter for a bad day: done or not, it moves no ladder. */
    val light: Boolean = false,
    /** Run on a stadium with laps of this many metres: the lap button and lap times. */
    val lapMeters: Int? = null,
    /** A time trial over this distance (the race itself): its time gives the fitness. */
    val testMeters: Int? = null,
) {
    /** Work reps (WORK segments) in the plan. */
    val reps: Int get() = segments.count { it.kind == SegmentKind.WORK }

    /** Easy and long runs go on past the plan if the runner wants: more is taken into account. */
    val continuable: Boolean get() = type == RunType.EASY || type == RunType.LONG

    val totalSeconds: Int get() = segments.sumOf { it.seconds }
    val workSeconds: Int get() = segments.filter { it.kind == SegmentKind.WORK || it.kind == SegmentKind.RUN }.sumOf { it.seconds }
    val isOpenEnded: Boolean get() = segments.isEmpty()
}
