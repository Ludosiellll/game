package forma.core.strength

import kotlin.math.abs
import kotlin.math.max

/**
 * How this person rates "reps left": the lifting twin of the runner's own effort scale.
 *
 * Ratings of reps in reserve run off for many lifters (mostly beginners, mostly on higher reps): someone who says
 * "3 more" with 1 left keeps getting "you stopped early, the weight stays" and climbs too slowly or too fast. The only
 * ground truth is a set that ran out: fewer reps than the target, not rated as stopped with reps to spare. Set against
 * a rated set before it at the same weight (a little less for the fatigue of the sets in between), it shows how many
 * reps the rating promised that were not there. Rated against a stop by rating ("+" sets) a steady bias cancels out,
 * so those do not count. A set short of the target after the target was reached at the same weight is taken as run out
 * whatever it was rated: whoever over-rates says "2 more" on that one too. Someone who stops short by choice never
 * reaches the target first, so leaves no pair.
 */
object RirScale {
    /** At least this many pairs before trusting it. */
    const val MIN_PAIRS = 4
    /** Reps lost per set in between at the same weight. */
    private const val FATIGUE = 0.6
    /** At most this much: more would say more about the day than about the person. */
    const val MAX = 2

    /** A rated set and a later set at the same weight that ran out ([failReps] reps), [gap] sets later. */
    data class Pair(val reps: Int, val rir: Int, val failReps: Int, val gap: Int)

    /**
     * Reps the person over-rates their reserve by, from the last 8 pairs; 0 until [MIN_PAIRS]. Under a rep and a half
     * is the noise of honest ratings (seen in the population runs). Never below zero: running out says nothing about a
     * reserve rated too low.
     */
    fun bias(pairs: List<Pair>): Int {
        val use = pairs.filter { it.gap >= 1 }.takeLast(8)
        if (use.size < MIN_PAIRS) return 0
        val d = use.map { (it.reps + it.rir) - it.failReps - FATIGUE * it.gap }.sorted()
        val m = if (d.size % 2 == 1) d[d.size / 2] else (d[d.size / 2 - 1] + d[d.size / 2]) / 2
        if (m < 1.5) return 0
        return m.toInt().coerceIn(0, MAX)
    }

    /** The pair in one exercise's sets of one session ([target] reps), if there is one. */
    fun pairOf(sets: List<DoneSet>, target: Int): Pair? {
        sets.forEachIndexed { i, s ->
            val rir = s.rir ?: return@forEachIndexed
            if (s.reps < target) return@forEachIndexed
            for (j in i + 1 until sets.size) {
                val f = sets[j]
                if (abs(f.weight - s.weight) > 1e-6) continue
                if (f.reps < target && f.reps > 0) return Pair(s.reps, rir, f.reps, j - i)
            }
        }
        return null
    }

    /** The reserve as it really was. */
    fun correct(rir: Int?, bias: Int): Int? = rir?.let { max(0, it - bias) }

    /** What the correction means, null when there is none. */
    fun explain(bias: Int): String? = if (bias > 0) {
        "Ты обычно ставишь запас на $bias больше, чем есть на деле: после «ещё ${bias + 1}–${bias + 2}» в следующих подходах " +
            "повторов не хватало. Поэтому приложение считает твой запас с поправкой −$bias и по нему подбирает веса и подсказки."
    } else null
}
