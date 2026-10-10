package forma.core.load

import java.time.LocalDate
import kotlin.math.exp

/** One day of the fitness–fatigue model. */
data class LoadDay(
    val date: LocalDate,
    val load: Double,
    /** Chronic load, 42-day exponentially weighted average: "fitness". */
    val fitness: Double,
    /** Acute load, 7-day exponentially weighted average: "fatigue". */
    val fatigue: Double,
) {
    /** Fitness minus fatigue: positive means fresh. */
    val form: Double get() = fitness - fatigue
}

object TrainingLoad {
    /** Session-RPE (Foster et al. 2001): CR-10 rating × minutes. */
    fun sessionLoad(rpe: Int, minutes: Double): Double = rpe.coerceIn(0, 10) * minutes.coerceAtLeast(0.0)

    const val FITNESS_DAYS = 42.0
    const val FATIGUE_DAYS = 7.0

    /**
     * Day-by-day fitness and fatigue from [from] to [to] inclusive. [loads] maps a date to the summed session load.
     * Uses the Banister impulse–response shape with exponential decay constants of 42 and 7 days.
     */
    fun series(loads: Map<LocalDate, Double>, from: LocalDate, to: LocalDate): List<LoadDay> {
        val kFit = 1 - exp(-1 / FITNESS_DAYS)
        val kFat = 1 - exp(-1 / FATIGUE_DAYS)
        var fit = 0.0
        var fat = 0.0
        val out = ArrayList<LoadDay>()
        var d = from
        while (!d.isAfter(to)) {
            val l = loads[d] ?: 0.0
            fit += (l - fit) * kFit
            fat += (l - fat) * kFat
            out += LoadDay(d, l, fit, fat)
            d = d.plusDays(1)
        }
        return out
    }
}
