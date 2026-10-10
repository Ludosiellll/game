package forma.core.run

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Jack Daniels & Jimmy Gilbert "Oxygen Power" (1979) running model, as used in Daniels' Running Formula.
 * Velocity v is in metres per minute, duration t in minutes, VO2 in ml/kg/min.
 */
object Vdot {
    /** Oxygen cost of running at [v] m/min. */
    fun vo2(v: Double): Double = -4.60 + 0.182258 * v + 0.000104 * v * v

    /** Fraction of VO2max that can be sustained for [t] minutes. */
    fun fractionSustainable(t: Double): Double =
        0.8 + 0.1894393 * exp(-0.012778 * t) + 0.2989558 * exp(-0.1932605 * t)

    /** VDOT from a race or time trial: [meters] covered in [seconds]. */
    fun fromRace(meters: Double, seconds: Double): Double {
        require(meters > 0 && seconds > 0)
        val t = seconds / 60.0
        val v = meters / t
        return vo2(v) / fractionSustainable(t)
    }

    /** Velocity (m/min) whose oxygen cost equals [vo2]. Inverse of [Vdot.vo2]. */
    fun velocityForVo2(vo2: Double): Double {
        val a = 0.000104
        val b = 0.182258
        val c = -4.60 - vo2
        return (-b + sqrt(b * b - 4 * a * c)) / (2 * a)
    }

    /** Predicted race time in seconds over [meters] for a runner with [vdot]. */
    fun predictSeconds(vdot: Double, meters: Double): Double {
        // fromRace(meters, t) falls as t grows: bisection on t.
        var lo = 30.0
        var hi = 60.0 * 60 * 12
        repeat(80) {
            val mid = (lo + hi) / 2
            if (fromRace(meters, mid) > vdot) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /** Seconds per kilometre when running at the velocity that costs [fraction] × [vdot]. */
    fun paceAtFraction(vdot: Double, fraction: Double): Double = 60_000.0 / velocityForVo2(vdot * fraction)
}

/** Riegel (1977/1981): T2 = T1 × (D2 / D1)^1.06. Good between ~1.5 km and the marathon. */
object Riegel {
    fun predictSeconds(knownMeters: Double, knownSeconds: Double, targetMeters: Double, exponent: Double = 1.06): Double =
        knownSeconds * Math.pow(targetMeters / knownMeters, exponent)
}
