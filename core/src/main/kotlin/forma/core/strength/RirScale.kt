package forma.core.strength

import kotlin.math.abs
import kotlin.math.max

/**
 * How this person rates "reps left": the lifting twin of the runner's own effort scale.
 *
 * Ratings of reps in reserve run off for many lifters (mostly beginners, mostly on higher reps): someone who says
 * "3 more" with 1 left keeps getting "you stopped early, the weight stays", someone who says "1" with 3 left never gets
 * the jump. The "+" set shows how far the lifter really goes. Set against the rated set just before it at the same
 * weight (one rep less for the fatigue of one more set), the difference is how far the ratings run off.
 */
object RirScale {
    /** At least this many pairs before trusting it. */
    const val MIN_PAIRS = 5
    /** The "+" set comes one set more tired. */
    private const val FATIGUE = 1
    /** At most this much either way: more would say more about the day than about the person. */
    const val MAX = 2

    /** A rated regular set and the "+" set after it at the same weight. */
    data class Pair(val reps: Int, val rir: Int, val plusReps: Int, val plusRir: Int?)

    /**
     * Reps the person over-rates their reserve by (minus: under-rates), from the last 8 pairs where the "+" set went
     * near failure (1 or 0 left); 0 until [MIN_PAIRS] of them. Half a rep is not enough to act on.
     */
    fun bias(pairs: List<Pair>): Int {
        val use = pairs.filter { (it.plusRir ?: 1) <= 1 && it.plusReps > 0 }.takeLast(8)
        if (use.size < MIN_PAIRS) return 0
        val d = use.map { (it.reps + it.rir) - (it.plusReps + (it.plusRir ?: 1)) - FATIGUE }.sorted()
        val m = if (d.size % 2 == 1) d[d.size / 2].toDouble() else (d[d.size / 2 - 1] + d[d.size / 2]) / 2.0
        if (abs(m) < 1) return 0
        return m.toInt().coerceIn(-MAX, MAX)
    }

    /** The reserve as it really was. */
    fun correct(rir: Int?, bias: Int): Int? = rir?.let { max(0, it - bias) }

    /** What the correction means, null when there is none. */
    fun explain(bias: Int): String? = when {
        bias > 0 -> "Свой запас ты обычно называешь на $bias больше, чем он есть: «+»-подходы показывают, что до отказа ближе. " +
            "Приложение читает твой запас с поправкой −$bias — веса и подсказки считаются по нему."
        bias < 0 -> "Свой запас ты обычно называешь на ${-bias} меньше, чем он есть: в «+»-подходах ты делаешь больше. " +
            "Приложение читает твой запас с поправкой +${-bias} — веса растут быстрее."
        else -> null
    }
}
