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

    /** Form as a share of fitness, percent: scale-free, so it reads the same for any training volume. */
    val formPct: Double get() = if (fitness > 1e-6) form / fitness * 100 else 0.0
}

object TrainingLoad {
    /** Session-RPE (Foster et al. 2001): CR-10 rating × minutes. */
    fun sessionLoad(rpe: Int, minutes: Double): Double = rpe.coerceIn(0, 10) * minutes.coerceAtLeast(0.0)

    const val FITNESS_DAYS = 42.0
    const val FATIGUE_DAYS = 7.0

    /**
     * Day-by-day fitness and fatigue from [from] to [to] inclusive. [loads] maps a date to the summed session load.
     * Uses the Banister impulse–response shape with exponential decay constants of 42 and 7 days.
     *
     * Both averages start at the first training day, seeded with the mean daily load of its first two weeks.
     * Starting from zero would show a new user a deep "fatigue hole" for weeks while the 42-day average fills up.
     */
    fun series(loads: Map<LocalDate, Double>, from: LocalDate, to: LocalDate): List<LoadDay> {
        val kFit = 1 - exp(-1 / FITNESS_DAYS)
        val kFat = 1 - exp(-1 / FATIGUE_DAYS)
        val first = loads.filter { it.value > 0 }.keys.minOrNull()
        val seed = if (first == null) 0.0 else (0 until 14).sumOf { loads[first.plusDays(it.toLong())] ?: 0.0 } / 14
        var fit = 0.0
        var fat = 0.0
        var seeded = false
        val out = ArrayList<LoadDay>()
        var d = if (first != null && first.isBefore(from)) first else from
        while (!d.isAfter(to)) {
            if (!seeded && first != null && !d.isBefore(first)) {
                fit = seed
                fat = seed
                seeded = true
            }
            val l = loads[d] ?: 0.0
            if (seeded) {
                fit += (l - fit) * kFit
                fat += (l - fat) * kFat
            }
            if (!d.isBefore(from)) out += LoadDay(d, l, fit, fat)
            d = d.plusDays(1)
        }
        return out
    }
}
