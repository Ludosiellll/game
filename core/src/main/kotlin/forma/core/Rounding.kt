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

/**
 * Rounds [x] down to a weight of a stack that goes [from], [from] + [step], … (a machine starting at 9 kg by 5: 9, 14,
 * 19); never below [from]. [from] 0 is plain multiples of [step].
 */
fun floorTo(x: Double, step: Double, from: Double): Double =
    if (from <= 0.0) floorTo(x, step) else maxOf(from, from + floorTo(x - from, step))

/** Rounds [x] to the nearest weight of a stack that goes [from], [from] + [step], …; never below [from]. */
fun roundTo(x: Double, step: Double, from: Double): Double =
    if (from <= 0.0) roundTo(x, step) else maxOf(from, from + roundTo(x - from, step))
