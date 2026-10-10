package forma.core.strength

import kotlin.math.abs

/** Which plates go on each side of the bar. */
object Plates {
    val STANDARD_KG = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)

    data class Loadout(
        /** Plates for one side, heaviest first. */
        val perSide: List<Double>,
        /** Weight actually on the bar, including the bar. */
        val total: Double,
    ) {
        fun isExact(target: Double) = abs(total - target) < 1e-6
    }

    /**
     * Greedy loadout for [target] kg on a [bar] kg bar. If the target cannot be hit exactly
     * with the given plates, the closest lighter total is returned.
     */
    fun loadout(target: Double, bar: Double = 20.0, plates: List<Double> = STANDARD_KG, pairsPerPlate: Int = 8): Loadout {
        if (target <= bar) return Loadout(emptyList(), bar)
        var perSideLeft = (target - bar) / 2
        val side = mutableListOf<Double>()
        for (p in plates.sortedDescending()) {
            var used = 0
            while (perSideLeft + 1e-9 >= p && used < pairsPerPlate) {
                side += p
                perSideLeft -= p
                used++
            }
        }
        return Loadout(side, bar + side.sum() * 2)
    }
}
