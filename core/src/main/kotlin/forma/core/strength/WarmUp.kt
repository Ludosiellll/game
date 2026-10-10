package forma.core.strength

import forma.core.floorTo
import forma.core.roundTo
import kotlinx.serialization.Serializable

@Serializable
data class WarmUpSet(val weight: Double, val reps: Int)

/**
 * Warm-up ramp before the first working set of an exercise.
 * Barbell lifts start with the empty bar, then ~40–50 %, ~60–70 % and ~80–85 % of the working weight
 * with falling reps, so the lifter arrives warm but not tired.
 */
object WarmUp {
    fun sets(workWeight: Double, step: Double, barWeight: Double? = 20.0): List<WarmUpSet> {
        if (workWeight <= 0) return emptyList()
        val ramp = when {
            workWeight < 30 -> listOf(0.5 to 10)
            workWeight < 60 -> listOf(0.5 to 8, 0.75 to 4)
            workWeight < 100 -> listOf(0.45 to 6, 0.65 to 4, 0.85 to 2)
            else -> listOf(0.4 to 5, 0.6 to 3, 0.75 to 2, 0.88 to 1)
        }
        val out = mutableListOf<WarmUpSet>()
        if (barWeight != null && workWeight >= barWeight + 20) out += WarmUpSet(barWeight, 10)
        for ((pct, reps) in ramp) {
            var w = roundTo(workWeight * pct, step)
            if (barWeight != null && w < barWeight) w = barWeight
            if (w >= workWeight) w = floorTo(workWeight - step, step)
            if (w <= 0 || (barWeight != null && w < barWeight)) continue
            if (out.isNotEmpty() && w <= out.last().weight) continue
            out += WarmUpSet(w, reps)
        }
        return out
    }
}
