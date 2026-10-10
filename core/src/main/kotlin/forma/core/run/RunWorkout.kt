package forma.core.run

import kotlinx.serialization.Serializable

@Serializable
enum class RunType { RUN_WALK, EASY, LONG, INTERVALS, TEMPO, TEST, FREE }

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
) {
    val totalSeconds: Int get() = segments.sumOf { it.seconds }
    val workSeconds: Int get() = segments.filter { it.kind == SegmentKind.WORK || it.kind == SegmentKind.RUN }.sumOf { it.seconds }
    val isOpenEnded: Boolean get() = segments.isEmpty()
}
