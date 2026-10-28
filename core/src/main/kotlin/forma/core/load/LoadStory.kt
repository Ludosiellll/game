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

    /**
     * [ONGOING]: the week under way, too little of it to say how it compares — a day in, or before the days the usual
     * weeks trained on (a Thursday of someone who trains at weekends).
     */
    enum class Level { NONE, LIGHT, USUAL, MORE, SPIKE, ONGOING }

    /** The person's rest line and where it came from. */
    data class Personal(
        val restBelow: Double,
        /** Learned from their sessions (false: the common line). */
        val learned: Boolean,
        /** Sessions that went badly / well, counted on days with a freshness to speak of. */
        val bad: Int,
        val good: Int,
        /** Below and above the line: sessions that went badly of those judged (learned only). */
        val badBelow: Int = 0,
        val judgedBelow: Int = 0,
        val badAbove: Int = 0,
        val judgedAbove: Int = 0,
    )

    data class Week(
        val monday: LocalDate,
        val load: Double,
        val trainingDays: Int,
        /**
         * Load against the person's usual week (the median of the four before with training), null without them. For the
         * week under way: so far against the same days of those weeks ([soFar]).
         */
        val ratio: Double?,
        val level: Level,
        /** Lowest and last freshness of the week, null without history. */
        val lowestPct: Double?,
        val endPct: Double?,
        /** The week under way: [ratio] is up to now, set against the same days of the usual weeks. */
        val soFar: Boolean = false,
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
        /** [lowestPct] is today's own freshness (the one «Сегодня» shows), lower than any morning of the dip. */
        val lowestNow: Boolean = false,
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
     * The rest line from the person's own sessions: the least tired freshness below which their sessions go badly
     * clearly more often — at least 1.5 times as often as above it and at least 4 in 10 — with at least 3 bad and 6
     * judged sessions below. Searched between −45 % and −20 %: everyday productive tiredness (−10…−20 %) is never called
     * a need to rest, and a line deeper than −45 % says more about chance. None found: the common −30 %.
     */
    fun personal(series: List<LoadDay>, bad: Set<LocalDate>, good: Set<LocalDate>, historyFrom: LocalDate?): Personal {
        // Freshness of the morning before the session: the day before's numbers (the session's own load is in today's).
        val before = series.zipWithNext().associate { (a, b) -> b.date to a.formPct }
        fun on(days: Set<LocalDate>) = days.filter { historyFrom != null && !it.isBefore(historyFrom) }.mapNotNull { before[it] }
        val b = on(bad)
        val g = on(good - bad)
        for (t in -20 downTo -45) {
            val bb = b.count { it < t }
            val gb = g.count { it < t }
            val ba = b.size - bb
            val ga = g.size - gb
            val below = bb + gb
            val above = ba + ga
            if (bb < 3 || below < 6 || above < 6) continue
            val rb = bb.toDouble() / below
            val ra = ba.toDouble() / above
            if (rb >= 0.4 && rb >= ra * 1.5) return Personal(t.toDouble(), true, b.size, g.size, bb, below, ba, above)
        }
        return Personal(COMMON_REST, false, b.size, g.size)
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
        // The week under way is set against the same days of the usual ones, from Monday: through today once today has
        // a session, through yesterday before it (in the morning the usual weeks' sessions of today are not to be had yet).
        val upTo = if ((loads[today] ?: 0.0) > 0) today else today.minusDays(1)
        val daysIn = (upTo.toEpochDay() - thisMonday.toEpochDay() + 1).toInt()
        val rows = all.mapIndexedNotNull { i, (m, days, load) ->
            if (i < 4) return@mapIndexedNotNull null
            // Weeks before the first session say nothing.
            if (first == null || m.plusDays(6).isBefore(first)) return@mapIndexedNotNull null
            val priorWeeks = all.subList(i - 4, i).filter { it.third > 0 }
            val current = m == thisMonday
            val usual = if (!current) median(priorWeeks.map { it.third })
            else median(priorWeeks.map { (pm, pd, _) -> pd.filter { it.date.isBefore(pm.plusDays(daysIn.toLong())) }.sumOf { it.load } })
            val soFar = if (current) days.filter { !it.date.isAfter(upTo) }.sumOf { it.load } else load
            val ratio = usual?.takeIf { it > 0 }?.let { soFar / it }
            val raw = when {
                load <= 0 -> Level.NONE
                ratio == null -> Level.USUAL
                ratio < 0.7 -> Level.LIGHT
                ratio <= 1.1 -> Level.USUAL
                ratio <= 1.3 -> Level.MORE
                else -> Level.SPIKE
            }
            // A day or so in, one session more or less is all the difference; and before the usual training days there is
            // nothing to set it against: no verdict yet.
            val level = when {
                !current -> raw
                daysIn <= 1 && raw != Level.USUAL -> Level.ONGOING
                load > 0 && ratio == null && priorWeeks.isNotEmpty() -> Level.ONGOING
                else -> raw
            }
            val pcts = days.mapNotNull { pctOn(it) }
            Week(m, load, days.count { it.load > 0 }, ratio, level, pcts.minOrNull(), days.lastOrNull()?.let { pctOn(it) }, soFar = current)
        }
        val usualWeek = median(all.takeLast(5).dropLast(1).map { it.third }.filter { it > 0 })

        // Freshness of the morning (the day before's numbers), as for the rest line: a hard session's own load does not
        // make its evening a dip that "needed rest".
        val morning = series.zipWithNext().associate { (a, b) -> b.date to pctOn(a) }
        val dips = mutableListOf<Dip>()
        var start: Int? = null
        val window = series.filter { !it.date.isBefore(thisMonday.minusWeeks((weeks - 1).toLong())) }
        fun close(endIdx: Int, recovered: LocalDate?) {
            val s = start ?: return
            val part = window.subList(s, endIdx + 1)
            val low = part.minBy { morning[it.date] ?: it.formPct }
            var lowPct = morning[low.date] ?: low.formPct
            var lowDay = low.date
            // Still in it and going deeper today: the lowest is now, not a morning before.
            val nowPct = pctOn(now)
            val deeper = recovered == null && nowPct != null && nowPct < lowPct
            if (deeper) { lowPct = nowPct!!; lowDay = today }
            val restTo = recovered ?: today
            val rest = window.count { !it.date.isBefore(part.first().date) && !it.date.isAfter(restTo) && it.load <= 0 }
            // A single day just under the line is noise: a dip lasts two days or goes 5 points under it.
            if (part.size >= 2 || lowPct <= personal.restBelow - 5 || recovered == null) {
                dips += Dip(part.first().date, part.last().date, lowPct, lowDay, rest, recovered, lowestNow = deeper)
            }
            start = null
        }
        window.forEachIndexed { i, d ->
            val p = morning[d.date]
            val below = p != null && p < personal.restBelow
            if (below && start == null) start = i
            if (!below && start != null) close(i - 1, d.date)
        }
        // Still under the line this morning, but today's freshness (the one «Сегодня» shows: the day as it ends, rest
        // included) is above it again: over, recovered today — never "below the line now" next to a value above it.
        if (start != null) close(window.lastIndex, if (pctOn(now)?.let { it >= personal.restBelow } == true) today else null)
        // The morning still above the line, today's own freshness under it (a very hard session today): a dip begins
        // today — never "not once below the line" next to «нужен отдых».
        else pctOn(now)?.takeIf { it < personal.restBelow && window.isNotEmpty() && window.last().date == today }?.let {
            dips += Dip(today, today, it, today, 0, null, lowestNow = true)
        }

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
