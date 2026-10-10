package forma.core.strength

/**
 * One-repetition maximum estimates.
 *
 * Reps-to-failure (RTF) = reps done + reps in reserve (RIR). The share of 1RM that a lifter can move
 * for a given RTF comes from the RPE chart for 1–12 RTF and from the Epley formula beyond that.
 */
object OneRm {
    /** Epley (1985): 1RM = w × (1 + r / 30). One rep is the 1RM itself. */
    fun epley(weight: Double, reps: Int): Double = if (reps <= 1) weight else weight * (1 + reps / 30.0)

    /** Brzycki (1993): 1RM = w × 36 / (37 − r), defined for r < 37. */
    fun brzycki(weight: Double, reps: Int): Double {
        require(reps in 1..36) { "Brzycki is defined for 1..36 reps" }
        return if (reps == 1) weight else weight * 36.0 / (37 - reps)
    }

    /**
     * Share of 1RM that can be lifted for [rtf] reps to failure.
     * Values for 1..12 follow the widely used RPE-10 column of the Tuchscherer / Helms RPE chart;
     * beyond 12 the Epley curve takes over, scaled to join the chart at 12 (estimates there are rough).
     */
    fun fractionForRtf(rtf: Double): Double {
        if (rtf <= 1.0) return 1.0
        val last = RPE_TABLE.size
        if (rtf >= last) return RPE_TABLE[last - 1] * (1 + last / 30.0) / (1 + rtf / 30.0)
        val lo = rtf.toInt()
        val t = rtf - lo
        val a = RPE_TABLE[lo - 1]
        val b = RPE_TABLE[lo]
        return a + (b - a) * t
    }

    /** e1RM from a set of [reps] at [weight] with [rir] reps left in reserve. */
    fun estimate(weight: Double, reps: Int, rir: Int = 0): Double {
        if (reps <= 0 || weight <= 0) return 0.0
        return weight / fractionForRtf((reps + rir.coerceAtLeast(0)).toDouble())
    }

    /** Load to prescribe for [reps] with [rir] in reserve, given an [e1rm]. */
    fun loadFor(e1rm: Double, reps: Int, rir: Int = 0): Double =
        e1rm * fractionForRtf((reps + rir.coerceAtLeast(0)).toDouble())

    /** How many reps to failure the lifter has at [weight] given [e1rm] (fractional). */
    fun rtfAt(e1rm: Double, weight: Double): Double {
        if (weight >= e1rm) return 1.0
        val target = weight / e1rm
        // fractionForRtf is strictly decreasing: bisection over [1, 60].
        var lo = 1.0
        var hi = 60.0
        repeat(60) {
            val mid = (lo + hi) / 2
            if (fractionForRtf(mid) > target) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /** RPE-10 column, RTF = 1..12 (index 0 → 1 rep). */
    internal val RPE_TABLE = doubleArrayOf(
        1.000, 0.955, 0.922, 0.892, 0.863, 0.837, 0.811, 0.786, 0.762, 0.739, 0.707, 0.680,
    )
}
