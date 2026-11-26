package forma.core.plan

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Two days of one week changed places, whole: every session of day [a] is on day [b] that week, and back. [week] is that
 * week's Monday (epoch day); [a] and [b] are ISO weekdays 1..7. The plan itself does not change: from the next week the
 * days are as planned. Denis, 26 November: «например я пропустил день ног и он не скоро, смогу ли я попросить тренера
 * поставить мне сегодня день ног с бегом, а сегодняшний верх перенести на субботу».
 */
@Serializable
data class DaySwap(val week: Long, val a: Int, val b: Int)

/**
 * The week as it stands with its days changed places ([DaySwap]), and the rules a new swap must keep ([refusal]). The
 * plan of a date is read in one place — [Program.on] — so «Сегодня», the plan's week, the day, the runs and the coach
 * all see the same days.
 */
object DaySwaps {
    private val NAMES = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    private val ON_DAY = listOf("в понедельник", "во вторник", "в среду", "в четверг", "в пятницу", "в субботу", "в воскресенье")

    /** The Monday of [date]'s week. */
    fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * The week of [monday] (epoch day) as its [swaps] leave it: for each weekday, Monday first, the plan's weekday whose
     * sessions are on it. Several swaps of one week apply in the order made; an entry that names no day moves nothing.
     */
    fun layout(monday: Long, swaps: List<DaySwap>): List<Int> {
        val at = (1..7).toMutableList()
        for (s in swaps) {
            if (s.week != monday || s.a !in 1..7 || s.b !in 1..7) continue
            val x = at[s.a - 1]
            at[s.a - 1] = at[s.b - 1]
            at[s.b - 1] = x
        }
        return at
    }

    /** The plan's weekday whose sessions are on [date]: its own, or another's by the swaps of that week. */
    fun origin(date: LocalDate, swaps: List<DaySwap>): Int =
        if (swaps.isEmpty()) date.dayOfWeek.value else layout(monday(date).toEpochDay(), swaps)[date.dayOfWeek.value - 1]

    /** The swaps of [date]'s week, in the order made. */
    fun of(date: LocalDate, swaps: List<DaySwap>): List<DaySwap> = monday(date).toEpochDay().let { m -> swaps.filter { it.week == m } }

    /**
     * [swaps] with [swap] made on [today]: the weeks before this one are dropped (this week and the next are kept), and a
     * week back as planned (the same two days changed twice) keeps nothing.
     */
    fun with(swaps: List<DaySwap>, swap: DaySwap, today: LocalDate): List<DaySwap> {
        val now = monday(today).toEpochDay()
        val kept = (swaps + swap).filter { it.week in now..now + 7 }
        return if (layout(swap.week, kept) == (1..7).toList()) kept.filter { it.week != swap.week } else kept
    }

    /**
     * [swaps] without [swap]; null when it is not the last one of its week any more (taken back already, or another swap
     * of the week made after it: the days it moved are not where it left them).
     */
    fun without(swaps: List<DaySwap>, swap: DaySwap): List<DaySwap>? {
        val last = swaps.indexOfLast { it.week == swap.week }
        return if (last < 0 || swaps[last] != swap) null else swaps.filterIndexed { i, _ -> i != last }
    }

    /** "четверг и суббота": the days of a week's [swaps], in the order made ("…, потом суббота и воскресенье"). */
    fun names(swaps: List<DaySwap>): String =
        swaps.filter { it.a in 1..7 && it.b in 1..7 }.joinToString(", потом ") { "${NAMES[it.a - 1]} и ${NAMES[it.b - 1]}" }

    /** What a day holds, in words for a sentence: "«Низ» и лёгкий бег", "отдых". */
    fun words(program: Program, sessions: List<PlannedSession>): String {
        val all = sessions.map { s ->
            when (s.kind) {
                SessionKind.LIFT -> program.templates.firstOrNull { it.id == s.templateId }?.title?.let { "«$it»" } ?: "силовая"
                SessionKind.RUN_EASY -> "лёгкий бег"
                SessionKind.RUN_QUALITY -> "тяжёлый бег"
                SessionKind.RUN_LONG -> "длинная пробежка"
            }
        }
        return when (all.size) {
            0 -> "отдых"
            1 -> all[0]
            else -> all.dropLast(1).joinToString(", ") + " и " + all.last()
        }
    }

    /**
     * What the app knows of a date that the plan does not say. [begun]: something of it is done or started (on it, or a
     * session of it done on another day). [held]: what keeps the day where it is, in words — a time trial due, the race,
     * a run the coach put on it; null — nothing. [done]: what was done on it, as sessions ([doneLegs] as in [CatchUp.Day]).
     */
    data class Day(
        val paused: Boolean = false,
        val sick: Boolean = false,
        val begun: Boolean = false,
        val held: String? = null,
        val done: List<PlannedSession> = emptyList(),
        val doneLegs: List<Boolean?> = emptyList(),
    )

    /**
     * Why the days of [swap] cannot change places, in a short sentence for the person; null when they can. Asked on
     * [today] with the [swaps] already made. Both days are of this week and not over; neither is paused, marked «Болею»,
     * begun or held ([day]); they do not hold the same sessions. And the week it leaves keeps the layout rules [CatchUp]
     * goes by, on the days around the two, the last day of the week before and the first of the next included: no leg
     * sessions on consecutive days, no two hard runs in a row, no hard run and legs on consecutive days in either order.
     * A day ahead is read as it would stand ([standing]: the plan with the swaps given, by default — the app gives a hard
     * run eased for its day as an easy one, a pause day as empty), a day over by what was done on it, today by both.
     */
    fun refusal(
        program: Program, swaps: List<DaySwap>, swap: DaySwap, today: LocalDate,
        day: (LocalDate) -> Day = { Day() },
        standing: (LocalDate, List<DaySwap>) -> List<PlannedSession> = { d, s -> program.on(d, s) },
    ): String? {
        if (swap.a !in 1..7 || swap.b !in 1..7 || swap.a == swap.b) return "Нужны два разных дня: пн, вт, ср, чт, пт, сб или вс."
        val monday = monday(today)
        if (swap.week != monday.toEpochDay()) return "Местами меняются только дни этой недели."
        val dates = listOf(swap.a, swap.b).map { monday.plusDays(it - 1L) }
        fun on(d: LocalDate) = if (d == today) "сегодня" else ON_DAY[d.dayOfWeek.value - 1]
        fun onFirst(d: LocalDate) = on(d).replaceFirstChar { it.uppercase() }
        // Said with «этой недели»: on Sunday «завтра» is a day of the next week, whose namesake of this week is over.
        dates.firstOrNull { it.isBefore(today) }?.let { d ->
            return "${NAMES[d.dayOfWeek.value - 1].replaceFirstChar { it.uppercase() }} этой недели уже позади: местами меняются только сегодня и дни до воскресенья."
        }
        for (d in dates) {
            val f = day(d)
            when {
                f.paused -> return "${onFirst(d)} пауза в плане."
                f.sick -> return "${onFirst(d)} отмечено «Болею»."
                f.begun -> return "${onFirst(d)} тренировка уже сделана или начата."
                f.held != null -> return "${onFirst(d)} ${f.held}: этот день не двигаем."
            }
        }
        if (program.on(dates[0], swaps) == program.on(dates[1], swaps)) return "${onFirst(dates[0])} и ${on(dates[1])} по плану одно и то же, менять нечего."

        val after = swaps + swap
        // A day as the rules see it: each session with whether it worked the legs when its template cannot say.
        fun stands(d: LocalDate): List<Pair<PlannedSession, Boolean?>> {
            val f = day(d)
            val done = f.done.mapIndexed { i, s -> s to f.doneLegs.getOrNull(i) }
            return when {
                d.isBefore(today) -> done
                d == today -> done + standing(d, after).map { it to null }
                else -> standing(d, after).map { it to null }
            }
        }
        fun legs(d: LocalDate) = stands(d).any { (s, own) -> CatchUp.legs(program, s, own) }
        fun hard(d: LocalDate) = stands(d).any { (s, _) -> s.isHardRun }
        for (d in dates.flatMap { listOf(it.minusDays(1), it) }.distinct()) {
            val next = d.plusDays(1)
            when {
                legs(d) && legs(next) -> return "Тогда ноги два дня подряд: ${on(d)} и ${on(next)}."
                hard(d) && hard(next) -> return "Тогда тяжёлый бег два дня подряд: ${on(d)} и ${on(next)}."
                legs(d) && hard(next) -> return "Тогда ноги ${on(d)}, а тяжёлый бег ${on(next)}: рядом их не ставим."
                hard(d) && legs(next) -> return "Тогда тяжёлый бег ${on(d)}, а ноги ${on(next)}: рядом их не ставим."
            }
        }
        return null
    }
}
