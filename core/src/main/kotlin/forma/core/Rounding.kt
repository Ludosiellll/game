package forma.core

import kotlin.math.floor
import kotlin.math.roundToLong

/** Rounds [x] to the nearest multiple of [step]. Halves go up. */
fun roundTo(x: Double, step: Double): Double {
    require(step > 0) { "step must be positive" }
    return (x / step + 1e-9).roundToLong() * step
}

/** Rounds [x] down to a multiple of [step] (with a tiny tolerance for float noise). */
fun floorTo(x: Double, step: Double): Double {
    require(step > 0) { "step must be positive" }
    return floor(x / step + 1e-9) * step
}

fun Double.clampTo(min: Double, max: Double): Double = if (this < min) min else if (this > max) max else this
