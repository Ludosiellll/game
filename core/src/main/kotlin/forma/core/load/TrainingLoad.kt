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

/**
 * What the numbers lean on while there is little history. [planned]: what the person's plan asks each day, Monday first
 * (7 values, load units), null without a plan — the floor of the usual week while there are few weeks. [off]: days not
 * to judge by — marked sick or paused.
 */
data class LoadBase(val planned: List<Double>? = null, val off: Set<LocalDate> = emptySet()) {
    /** The plan's week, null without one (or an empty one). */
    val plannedWeek: Double? get() = planned?.sum()?.takeIf { it > 0 }

    /** The plan from Monday through ISO day [day] (1 = Monday). */
    fun plannedThrough(day: Int): Double = planned?.take(day.coerceIn(0, 7))?.sum() ?: 0.0
}

object TrainingLoad {
    /** Session-RPE (Foster et al. 2001): CR-10 rating × minutes. */
    fun sessionLoad(rpe: Int, minutes: Double): Double = rpe.coerceIn(0, 10) * minutes.coerceAtLeast(0.0)

    /**
     * Form means something only with history: two weeks since the first session and at least four sessions.
     * Before that one hard session makes the 7-day average jump over a 42-day one that barely moved.
     */
    fun hasHistory(loads: Map<LocalDate, Double>, today: LocalDate): Boolean {
        val days = loads.filter { it.value > 0 && !it.key.isAfter(today) }.keys
        val first = days.minOrNull() ?: return false
        return days.size >= 4 && !first.isAfter(today.minusDays(14))
    }

    const val FITNESS_DAYS = 42.0
    const val FATIGUE_DAYS = 7.0

    /**
     * Day-by-day fitness and fatigue from [from] to [to] inclusive. [loads] maps a date to the summed session load.
     * Uses the Banister impulse–response shape with exponential decay constants of 42 and 7 days.
     *
     * Both averages start at the first training day, seeded with the mean daily load of the first six weeks (as many
     * days of them as there are up to [to]; sick and paused days in [base] left out). Starting from zero would show a new
     * user a deep "fatigue hole" for weeks while the 42-day average fills up. The first two weeks alone were the seed
     * before, and they are often not the usual ones (Denis, October: the app new, then a week ill without «Болею» — his
     * first full week by plan read as freshness −37 %, «нужен отдых»).
     */
    fun series(loads: Map<LocalDate, Double>, from: LocalDate, to: LocalDate, base: LoadBase = LoadBase()): List<LoadDay> {
        val kFit = 1 - exp(-1 / FITNESS_DAYS)
        val kFat = 1 - exp(-1 / FATIGUE_DAYS)
        val first = loads.filter { it.value > 0 }.keys.minOrNull()
        val seed = if (first == null) 0.0 else {
            val span = (to.toEpochDay() - first.toEpochDay() + 1).coerceIn(14, FITNESS_DAYS.toLong())
            val days = (0 until span).map { first.plusDays(it) }.filter { it !in base.off }
            if (days.isEmpty()) 0.0 else days.sumOf { loads[it] ?: 0.0 } / days.size
        }
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
