package forma.core.load

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * What the load numbers say about this person: where freshness stands against their own rest line, how each week
 * compared with their usual one, and when they were run down and how they came back.
 *
 * The rest line starts at the common −30 % and is learned from the person's own sessions once there are enough: if
 * the ones that went badly (reps short, a step back, a run cut short) happened at a clearly lower freshness than the
 * ones that went well, the line sits between the two. So it is theirs, not a rule of thumb.
 */
object LoadStory {
    /** The common line: below it most people need rest. */
    const val COMMON_REST = -30.0
    /** Above this one is fresh: ready for a hard session or a trial. */
    const val FRESH = 10.0
    /** Above this rest has gone on long enough that fitness starts to fade. */
    const val DETRAINING = 25.0

    enum class Zone { REST, BUILDING, BALANCE, FRESH, DETRAINING }

    enum class Level { NONE, LIGHT, USUAL, MORE, SPIKE }

    /** The person's rest line and where it came from. */
    data class Personal(
        val restBelow: Double,
        /** Learned from their sessions (false: the common line). */
        val learned: Boolean,
        /** Sessions that went badly / well, counted on days with a freshness to speak of. */
        val bad: Int,
        val good: Int,
        /** Median freshness on those days. */
        val badMedian: Double?,
        val goodMedian: Double?,
    )

    data class Week(
        val monday: LocalDate,
        val load: Double,
        val trainingDays: Int,
        /** Load against the person's usual week (the median of the four before with training), null without them. */
        val ratio: Double?,
        val level: Level,
        /** Lowest and last freshness of the week, null without history. */
        val lowestPct: Double?,
        val endPct: Double?,
    )

    /** A stretch below the rest line: how deep, how many days without training followed, when it was over. */
    data class Dip(
        val from: LocalDate,
        val to: LocalDate,
        val lowestPct: Double,
        val lowestDay: LocalDate,
        /** Days without training from its start to its end (or to today). */
        val restDays: Int,
        /** The first day above the line again, null if still in it. */
        val recovered: LocalDate?,
    )

    data class Story(
        val today: LoadDay,
        val history: Boolean,
        val personal: Personal,
        val zone: Zone?,
        /** Fitness now against four weeks ago, percent; null when it was not yet measured then. */
        val fitnessChange: Double?,
        /** The person's usual week (median of recent weeks with training). */
        val usualWeek: Double?,
        val weeks: List<Week>,
        val dips: List<Dip>,
        /** Days since the last training. */
        val daysSinceTraining: Int?,
    )

    fun zone(pct: Double, restBelow: Double): Zone = when {
        pct < restBelow -> Zone.REST
        pct < -10 -> Zone.BUILDING
        pct <= FRESH -> Zone.BALANCE
        pct <= DETRAINING -> Zone.FRESH
        else -> Zone.DETRAINING
    }

    /**
     * The rest line from the person's own sessions: at least three bad and three good ones on days with history, and
     * the bad ones at least 5 points less fresh. Kept between −45 % and −10 %: a line past those says more about chance
     * than about the person.
     */
    fun personal(series: List<LoadDay>, bad: Set<LocalDate>, good: Set<LocalDate>, historyFrom: LocalDate?): Personal {
        // Freshness of the morning before the session: the day before's numbers (the session's own load is in today's).
        val before = series.zipWithNext().associate { (a, b) -> b.date to a.formPct }
        fun on(days: Set<LocalDate>) = days.filter { historyFrom != null && !it.isBefore(historyFrom) }.mapNotNull { before[it] }
        val b = on(bad)
        val g = on(good - bad)
        val mb = median(b)
        val mg = median(g)
        val learned = b.size >= 3 && g.size >= 3 && mb != null && mg != null && mb <= mg - 5
        val line = if (learned) ((mb!! + mg!!) / 2).coerceIn(-45.0, -10.0).roundToInt().toDouble() else COMMON_REST
        return Personal(line, learned, b.size, g.size, mb, mg)
    }

    /**
     * [loads]: load per day. [bad] / [good]: days with a session that went badly / well. [weeks]: how many weeks to tell
     * about, this one included.
     */
    fun of(
        loads: Map<LocalDate, Double>,
        today: LocalDate,
        bad: Set<LocalDate> = emptySet(),
        good: Set<LocalDate> = emptySet(),
        weeks: Int = 8,
    ): Story {
        val from = today.minusDays(180)
        val series = TrainingLoad.series(loads, from, today)
        val history = TrainingLoad.hasHistory(loads, today)
        // Freshness means something from two weeks after the first session (the same rule as for today).
        val first = loads.filter { it.value > 0 }.keys.minOrNull()
        val historyFrom = first?.plusDays(14)
        val personal = personal(series, bad, good, historyFrom)
        val now = series.last()
        fun pctOn(d: LoadDay) = d.formPct.takeIf { historyFrom != null && !d.date.isBefore(historyFrom) }

        val fourAgo = series.firstOrNull { it.date == today.minusDays(28) }
        val fitnessChange = if (fourAgo != null && historyFrom != null && !fourAgo.date.isBefore(historyFrom) && fourAgo.fitness > 1e-6) {
            (now.fitness / fourAgo.fitness - 1) * 100
        } else null

        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val all = (0 until weeks + 4).map { thisMonday.minusWeeks((weeks + 3 - it).toLong()) }.map { m ->
            val days = series.filter { !it.date.isBefore(m) && it.date.isBefore(m.plusDays(7)) }
            Triple(m, days, days.sumOf { it.load })
        }
        val rows = all.mapIndexedNotNull { i, (m, days, load) ->
            if (i < 4) return@mapIndexedNotNull null
            // Weeks before the first session say nothing.
            if (first == null || m.plusDays(6).isBefore(first)) return@mapIndexedNotNull null
            val prior = all.subList(i - 4, i).map { it.third }.filter { it > 0 }
            val usual = median(prior)
            // This week so far is set against the same number of days of a usual one.
            val share = if (m == thisMonday) ((today.toEpochDay() - m.toEpochDay() + 1) / 7.0) else 1.0
            val ratio = usual?.takeIf { it > 0 }?.let { load / (it * share) }
            val level = when {
                load <= 0 -> Level.NONE
                ratio == null -> Level.USUAL
                ratio < 0.7 -> Level.LIGHT
                ratio <= 1.1 -> Level.USUAL
                ratio <= 1.3 -> Level.MORE
                else -> Level.SPIKE
            }
            val pcts = days.mapNotNull { pctOn(it) }
            Week(m, load, days.count { it.load > 0 }, ratio, level, pcts.minOrNull(), days.lastOrNull()?.let { pctOn(it) })
        }
        val usualWeek = median(all.takeLast(5).dropLast(1).map { it.third }.filter { it > 0 })

        val dips = mutableListOf<Dip>()
        var start: Int? = null
        val window = series.filter { !it.date.isBefore(thisMonday.minusWeeks((weeks - 1).toLong())) }
        fun close(endIdx: Int, recovered: LocalDate?) {
            val s = start ?: return
            val part = window.subList(s, endIdx + 1)
            val low = part.minBy { it.formPct }
            val restTo = recovered ?: today
            val rest = window.count { !it.date.isBefore(part.first().date) && !it.date.isAfter(restTo) && it.load <= 0 }
            dips += Dip(part.first().date, part.last().date, low.formPct, low.date, rest, recovered)
            start = null
        }
        window.forEachIndexed { i, d ->
            val p = pctOn(d)
            val below = p != null && p < personal.restBelow
            if (below && start == null) start = i
            if (!below && start != null) close(i - 1, d.date)
        }
        if (start != null) close(window.lastIndex, null)

        val lastTraining = loads.filter { it.value > 0 && !it.key.isAfter(today) }.keys.maxOrNull()
        return Story(
            today = now, history = history, personal = personal,
            zone = if (history) zone(now.formPct, personal.restBelow) else null,
            fitnessChange = fitnessChange, usualWeek = usualWeek, weeks = rows, dips = dips,
            daysSinceTraining = lastTraining?.let { (today.toEpochDay() - it.toEpochDay()).toInt() },
        )
    }

    private fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
