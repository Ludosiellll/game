package forma.core.strength

import forma.core.floorTo
import forma.core.roundTo
import kotlinx.serialization.Serializable

@Serializable
data class WarmUpSet(val weight: Double, val reps: Int)

/**
 * Warm-up ramp before the first working set of an exercise.
 * Barbell lifts start with the empty bar, then ~40–50 %, ~60–70 % and ~80–88 % of the working weight
 * with falling reps, so the lifter arrives warm but not tired. The work is sets of several reps, so the ramp wakes the
 * muscles with reps too: eight on the light sets, and the last set is two or three, never a single (Denis, 23 November:
 * «лучше сделать не на раз а на 2–3 чтобы лучше включить мышцы»). A single belongs to the test of a max
 * (`GymGoal.testDay`), which has its own ramp.
 */
object WarmUp {
    /**
     * One set before a heavy exercise whose muscles have not worked yet today (a secondary press after a different
     * main lift): about 60 % of the working weight for 6. Empty when that is no lighter than the work itself.
     */
    fun single(workWeight: Double, step: Double, barWeight: Double? = null, from: Double = 0.0): List<WarmUpSet> {
        var w = roundTo(workWeight * 0.6, step, from)
        if (barWeight != null && w < barWeight) w = barWeight
        return if (w <= 0 || w >= workWeight) emptyList() else listOf(WarmUpSet(w, 6))
    }

    /**
     * Before a main lift done with the body and extra weight (pull-ups or dips with +25 on a belt; [workWeight] is the
     * extra): the body alone for five, then about half and three quarters of the extra for three and two. A ramp on the
     * whole load would go below the body — help that not every gym has; before, there was no ramp at all.
     */
    fun extra(workWeight: Double, step: Double): List<WarmUpSet> {
        if (workWeight <= 0) return emptyList()
        val out = mutableListOf(WarmUpSet(0.0, 5))
        for ((pct, reps) in listOf(0.5 to 3, 0.75 to 2)) {
            val w = roundTo(workWeight * pct, step)
            if (w <= out.last().weight || w >= workWeight) continue
            out += WarmUpSet(w, reps)
        }
        return out
    }

    /** [from]: the weight a machine stack's steps go from (0: multiples of [step]). */
    fun sets(workWeight: Double, step: Double, barWeight: Double? = 20.0, from: Double = 0.0): List<WarmUpSet> {
        if (workWeight <= 0) return emptyList()
        val ramp = when {
            workWeight < 30 -> listOf(0.5 to 10)
            workWeight < 60 -> listOf(0.5 to 8, 0.75 to 5)
            workWeight < 100 -> listOf(0.45 to 8, 0.65 to 5, 0.85 to 3)
            else -> listOf(0.4 to 8, 0.6 to 5, 0.75 to 3, 0.88 to 2)
        }
        val out = mutableListOf<WarmUpSet>()
        if (barWeight != null && workWeight >= barWeight + 20) out += WarmUpSet(barWeight, 10)
        for ((pct, reps) in ramp) {
            var w = roundTo(workWeight * pct, step, from)
            if (barWeight != null && w < barWeight) w = barWeight
            if (w >= workWeight) w = floorTo(workWeight - step, step, from)
            if (w <= 0 || w >= workWeight || (barWeight != null && w < barWeight)) continue
            if (out.isNotEmpty() && w <= out.last().weight) continue
            out += WarmUpSet(w, reps)
        }
        return out
    }
}
