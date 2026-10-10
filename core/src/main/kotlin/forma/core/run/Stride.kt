package forma.core.run

import kotlin.math.abs

/**
 * Stride length from the runner's own runs: pairs of (cadence, stride) measured where GPS was good, fitted as a line —
 * faster running is longer strides more than it is quicker ones. Used for the distance where GPS is missing.
 */
object Stride {
    /** (a, b) for stride = a + b × cadence (m, steps a minute), or null with too little to go on. */
    fun fit(samples: List<Pair<Double, Double>>): Pair<Double, Double>? {
        val ok = samples.filter { (c, s) -> c in 120.0..230.0 && s in 0.5..2.2 }
        if (ok.size < 5) return null
        val mc = ok.sumOf { it.first } / ok.size
        val ms = ok.sumOf { it.second } / ok.size
        val varC = ok.sumOf { (it.first - mc) * (it.first - mc) }
        // Cadence hardly changed: one stride for all.
        if (varC / ok.size < 9.0) return ms to 0.0
        val b = ok.sumOf { (it.first - mc) * (it.second - ms) } / varC
        // A stride that shrinks as cadence grows is noise, not running.
        return if (b < 0 || abs(b) > 0.05) ms to 0.0 else (ms - b * mc) to b
    }
}
