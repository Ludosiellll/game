package forma.core.strength

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Rest before the next set, worked out after each set from what the set was and from how this person recovers.
 *
 * - The plan's rest for the exercise is the base: it already follows the goal and the role (a main lift of a strength
 *   plan 3 min, an accessory of a muscle plan 1:15).
 * - The set just done: one taken to failure or a rep from it needs more (more fatigue to clear), one with reps to spare
 *   less; a heavy set of few reps more, a light set of many reps less.
 * - A multi-joint lift (much muscle, the whole body tired) never goes as short as a single-joint one.
 * - The person: in their past sessions, how many reps they lost from one set to the next after the rest they actually
 *   took (the time between the sets as logged; a next set up to a tenth heavier or lighter is carried over through the
 *   one-rep estimate — most people move the weight between sets). Losing much after about the planned rest — they need
 *   longer; losing nothing on short rests — they recover fast. Reps lost after a rest far longer than planned say the
 *   weight was the limit, not the rest: those never lengthen it.
 *
 * Grounds: for growth, rests over a minute help a little and beyond 1.5 min no clear gain was found (Singer et al.
 * 2024, a meta-analysis of 9 studies); for heavy multi-joint strength work 2–5 min is the usual advice; rest mostly
 * matters through the reps it keeps in the next set — which is what the personal part measures.
 */
object RestAdvice {
    /** A rest before the next set and why. */
    data class Rest(val sec: Int, val reasons: List<String>)

    /** One pair of sets in a row from the past: the rest taken between them and the reps to failure of each (the first at the second's weight). */
    data class Pair(val restSec: Int, val before: Int, val after: Int)

    /** At least this many pairs before trusting the person's own recovery. */
    const val MIN_PAIRS = 6

    /**
     * Seconds more (or less) this person needs, from [pairs] (last 12): their median rep loss from one set to the next
     * against the about one rep a set expected. Two or more lost after rests of about the [planned] one (up to half
     * again) — +30 s, three or more — +60 s; next to nothing lost though their rests were no longer than planned — −15 s.
     */
    fun personal(pairs: List<Pair>, planned: Int): Int {
        val use = pairs.filter { it.restSec in 30..480 }.takeLast(12)
        if (use.size < MIN_PAIRS) return 0
        fun loss(ps: List<Pair>) = median(ps.map { (it.before - it.after).toDouble() })
        val rest = median(use.map { it.restSec.toDouble() })
        // Only rests about as planned say whether the planned rest is enough.
        val near = use.filter { it.restSec <= planned * 1.5 }
        val slow = if (near.size >= MIN_PAIRS) loss(near) else 0.0
        return when {
            slow >= 3 -> 60
            slow >= 2 -> 30
            loss(use) <= 0.3 && rest <= planned -> -15
            else -> 0
        }
    }

    /**
     * Rest after a set of [reps] with [rir] left ([plannedRir] in the plan; not rated — as planned, or failure when short
     * of [target]) on top of the plan's [base]. [own]: the user set the rest themselves — it stays as set.
     */
    fun after(base: Int, reps: Int, rir: Int?, plannedRir: Int, compound: Boolean, deload: Boolean, personal: Int, own: Boolean = false, target: Int? = null): Rest {
        if (own) return Rest(base, listOf("твой отдых"))
        val why = mutableListOf<String>()
        var s = base
        val r = rir ?: if (target != null && reps < target) 0 else plannedRir
        when {
            reps > 0 && r <= 0 -> { s += 45; why += "подход до отказа" }
            r == 1 && plannedRir >= 2 -> { s += 20; why += "подход тяжелее плана" }
            r >= plannedRir + 2 -> { s -= 20; why += "был запас" }
        }
        when {
            reps in 1..5 -> { s += 20; why += "тяжёлый вес" }
            reps >= 13 -> { s -= 15; why += "много повторов, вес лёгкий" }
        }
        if (deload) { s -= 30; why += "разгрузка" }
        if (personal > 0) { s += personal; why += "ты восстанавливаешься дольше" }
        if (personal < 0) { s += personal; why += "ты восстанавливаешься быстро" }
        val min = if (compound) 90 else 45
        val max = if (compound) 300 else 180
        val sec = ((s.coerceIn(min, max) / 15.0).roundToInt() * 15)
        return Rest(sec, why)
    }

    /**
     * Pairs from one exercise's sets of one session, in order: each set and the next at the same weight or up to a tenth
     * off it, the rest between them from the times they were logged (less about 3 s a rep of the second set's own time).
     * Reps to failure: reps plus the reserve said; a set short of [target] without a rating — failure. A first set at
     * another weight is carried to the second's through the one-rep estimate ([load]: the load a weight is, the body
     * included for body-weight lifts).
     */
    fun pairsOf(sets: List<kotlin.Pair<DoneSet, Long>>, target: Int, load: (Double) -> Double = { it }): List<Pair> {
        fun rtf(s: DoneSet): Int? = s.rir?.let { s.reps + it } ?: if (s.reps < target) s.reps else null
        return sets.zipWithNext().mapNotNull { (a, b) ->
            val (sa, ta) = a
            val (sb, tb) = b
            if (sa.reps <= 0 || sb.reps <= 0) return@mapNotNull null
            val la = load(sa.weight)
            val lb = load(sb.weight)
            if (la <= 0 || lb <= 0 || kotlin.math.abs(la - lb) > la * 0.1 + 1e-6) return@mapNotNull null
            val first = rtf(sa) ?: return@mapNotNull null
            val after = rtf(sb) ?: return@mapNotNull null
            val before = if (kotlin.math.abs(la - lb) < 1e-6) first else OneRm.rtfAt(OneRm.estimate(la, first), lb).roundToInt()
            val rest = ((tb - ta) / 1000 - sb.reps * 3 - 10).toInt()
            Pair(max(0, rest), before, after)
        }
    }

    /** The reasons as said: "подход до отказа, тяжёлый вес". */
    fun words(r: Rest): String = r.reasons.joinToString(", ")

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
