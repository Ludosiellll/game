package forma.core.plan

import forma.core.model.Emphasis

/**
 * A session missed earlier this week that can be done today instead of resting.
 *
 * Only on a free day, and only when it breaks none of the layout rules against what was done yesterday and what is
 * planned tomorrow (on Sunday: next Monday): no heavy legs on consecutive days, no two hard runs in a row, no hard
 * run and heavy legs on consecutive days in either order. One exception: legs owed ([LEGS_OWED] days or more without a
 * leg session) before a hard run tomorrow, offered with that run made easy for the day or moved a day later
 * ([Suggestion.beforeHard]).
 */
object CatchUp {
    /**
     * One day of this week: what was planned and what was actually done (as sessions). [doneLegs]: for each done session
     * (in the same order), whether it worked the legs when its template cannot say (a friend's copied workout); null or
     * missing — as its template says. [paused]: a day of a pause (nothing planned): what was done on it is beyond the
     * plan, never a later day's done ahead.
     */
    data class Day(
        val day: Int,
        val planned: List<PlannedSession>,
        val done: List<PlannedSession>,
        val doneLegs: List<Boolean?> = emptyList(),
        val paused: Boolean = false,
        /** For each done session (in order): an easy run done for a hard one missed before it ([CatchUp.Suggestion.easy]). */
        val doneInstead: List<Boolean> = emptyList(),
    )

    /**
     * [easy]: the missed [session] is a hard run that cannot be today (a hard day around it): done as an easy run.
     * [beforeHard]: the missed [session] is legs, owed ([legsDaysAgo] days without a leg session, [LEGS_OWED] or more),
     * with a hard run tomorrow: offered only with that run made easy for its day, or ([moveOk]) moved to the day after
     * it, the person's pick on the card (research D1, approved by Denis on 25 November).
     */
    data class Suggestion(
        val session: PlannedSession,
        val plannedOn: Int,
        val easy: Boolean = false,
        val beforeHard: Boolean = false,
        val moveOk: Boolean = false,
        val legsDaysAgo: Int? = null,
    )

    /**
     * Days without a leg session from which a missed one comes first and may go before a hard run (eased or moved). Denis
     * missed Saturday's «Низ» on 21.11 and was offered «Верх · А» on Sunday: his legs went from 7.11 to 28.11 without a
     * full session (research one-leg-day+D1; one leg session a week kept strength and sprint, one every second week lost
     * them — Rønnestad 2011).
     */
    const val LEGS_OWED = 7

    /**
     * A planned session closed by a done one: the [index]-th planned session of [day] by the [doneIndex]-th done one of
     * [doneDay] (the same day, or a later one when it was caught up).
     */
    data class Close(val day: Int, val index: Int, val doneDay: Int, val doneIndex: Int)

    /**
     * Which planned sessions of the week are done, and by what. On its own day a session is done by one of its kind: the
     * same kind first (the hard run by the hard run, a lift by its template), then in order (a lift by a lift, whichever
     * template; a run by a run) — as the day shows it. A session done on a day where nothing of its kind was left to do
     * closes another of the week (from [firstDay]): the same session first — the same template, a run of its kind — a
     * missed one before it (the last one missed, as [suggest] offers it), else a later one (done ahead: «Низ» on Friday
     * for Saturday's); then any lift (another template or a friend's copied workout counts as the gym done) or a hard run
     * for an easy one, missed first. Done ahead, a later day's session is closed at once: done on its own day after all,
     * the day's own one closes it and the earlier stays extra. [today] (ISO; 8 — the week is over) is kept for the
     * callers' sake: a day ahead is not waited for.
     */
    @Suppress("UNUSED_PARAMETER")
    fun closes(week: List<Day>, firstDay: Int = 1, today: Int = 8): List<Close> {
        val out = mutableListOf<Close>()
        val open = mutableListOf<Pair<Int, Int>>()
        val spare = mutableListOf<Pair<Int, Int>>()
        fun same(a: PlannedSession, b: PlannedSession) = a.kind == b.kind && (a.kind != SessionKind.LIFT || a.templateId == b.templateId)
        for (d in week.sortedBy { it.day }) {
            for (isRun in listOf(false, true)) {
                val planned = d.planned.withIndex().filter { it.value.isRun == isRun }
                val done = d.done.withIndex().filter { it.value.isRun == isRun }.toMutableList()
                val by = mutableMapOf<Int, Int>()
                for (p in planned) done.firstOrNull { same(it.value, p.value) }?.let { by[p.index] = it.index; done.remove(it) }
                for (p in planned) if (p.index !in by) done.firstOrNull()?.let { by[p.index] = it.index; done.remove(it) }
                for (p in planned) {
                    val x = by[p.index]
                    if (x != null) out += Close(d.day, p.index, d.day, x)
                    else if (d.day >= firstDay) open += d.day to p.index
                }
                done.forEach { spare += d.day to it.index }
            }
        }
        fun planned(o: Pair<Int, Int>) = week.first { it.day == o.first }.planned[o.second]
        fun sameIn(s: PlannedSession, from: List<Pair<Int, Int>>) = from.firstOrNull { same(planned(it), s) }
        fun other(s: PlannedSession, from: List<Pair<Int, Int>>) = if (s.kind == SessionKind.LIFT) {
            from.firstOrNull { planned(it).kind == SessionKind.LIFT }
        } else {
            from.firstOrNull { s.isHardRun && planned(it).kind == SessionKind.RUN_EASY }
        }
        // An easy run started for a hard one missed before it (the app offers that when the hard one cannot be today):
        // it closes that one (Denis, 4 November: Monday's tempo missed, Thursday's ahead — Wednesday's easy run is
        // Monday's, not Saturday's). Only so marked: an easy run with Saturday's lower moved to Friday is Saturday's.
        // With two missed, the last one: the one the card offered it for («засчитается за четверг», [suggest]).
        fun easier(s: PlannedSession, from: List<Pair<Int, Int>>, instead: Boolean) =
            if (instead && s.kind == SessionKind.RUN_EASY) from.lastOrNull { planned(it).isHardRun } else null
        for ((doneDay, doneIndex) in spare.sortedBy { it.first }) {
            val day = week.first { it.day == doneDay }
            val s = day.done[doneIndex]
            val instead = day.doneInstead.getOrNull(doneIndex) == true
            val before = open.filter { it.first < doneDay }
            // Ahead only from this plan's days: a session of the plan before a mid-week change closes nothing later; nor one
            // done on a pause day — nothing was expected then, it is beyond the plan.
            val ahead = if (doneDay >= firstDay && !week.first { it.day == doneDay }.paused) open.filter { it.first > doneDay } else emptyList()
            // The same session missed twice: made up, it is the last one's, as the catch-up card offers it ([suggest]).
            val match = easier(s, before, instead) ?: sameIn(s, before.asReversed()) ?: sameIn(s, ahead) ?: other(s, before) ?: other(s, ahead) ?: continue
            open.remove(match)
            out += Close(match.first, match.second, doneDay, doneIndex)
        }
        return out
    }

    /**
     * Whether [s] works the legs, as the layout rules count it: a lift whose template has legs — a template the plan no
     * longer has counts as legs; [own] says it where the template cannot (a friend's copied workout, by its exercises).
     */
    fun legs(program: Program, s: PlannedSession, own: Boolean? = null): Boolean =
        s.kind == SessionKind.LIFT && (own ?: program.templates.firstOrNull { it.id == s.templateId }?.legs ?: true)

    /**
     * [today] is ISO 1..7; [week] holds Monday..Sunday of this week; days before [firstDay] (ISO) are ignored.
     * [legsDaysAgo]: days since the last leg session done (null: none known). [next]: the sessions of the days after
     * today (tomorrow first, up to three) as they stand, with a hard run made easy for its day as an easy one and a moved
     * one on its new day; null: as planned (this week's days, then next week's plan — [program]'s week is read for the
     * days of next week only, so with days changed places ([DaySwaps]) it is next week's own, [Program.forWeek]). The
     * checks read it, not the plan alone: an eased Monday no longer blocks Sunday's legs (research D1, 25 November).
     */
    fun suggest(
        program: Program, today: Int, week: List<Day>, firstDay: Int = 1, legsDaysAgo: Int? = null, next: List<List<PlannedSession>>? = null,
    ): Suggestion? {
        val todayPlan = week.firstOrNull { it.day == today } ?: return null
        if (todayPlan.planned.isNotEmpty() || todayPlan.done.isNotEmpty()) return null

        // Missed: planned before today and not done on its day or caught up on a later one (as the week shows it). Closed
        // over the whole week, as the week is shown: a session done ahead closes its own later day («Низ» on Wednesday is
        // Saturday's), not a missed one of another template.
        val closed = closes(week, firstDay, today).map { it.day to it.index }.toSet()
        val missed = week.filter { it.day in firstDay until today }.sortedBy { it.day }.flatMap { d ->
            d.planned.withIndex().filter { (d.day to it.index) !in closed }.map { Suggestion(it.value, d.day) }
        }
        if (missed.isEmpty()) return null

        val yesterdayDay = week.firstOrNull { it.day == today - 1 }
        val yesterday = yesterdayDay?.done.orEmpty().mapIndexed { i, s -> s to yesterdayDay?.doneLegs?.getOrNull(i) }
        // The days after today: tomorrow (on Sunday next Monday), the day after it and the one after that.
        val ahead = next ?: (1..3).map { k ->
            if (today + k <= 7) week.firstOrNull { it.day == today + k }?.planned.orEmpty() else program.week.on(today + k - 7)
        }
        val tomorrow = ahead.getOrElse(0) { emptyList() }.map { it to null }
        fun legs(s: PlannedSession, own: Boolean? = null) = legs(program, s, own)
        val around = yesterday + tomorrow
        fun fits(s: PlannedSession): Boolean = when {
            legs(s) -> around.none { (x, own) -> legs(x, own) || x.isHardRun }
            s.isHardRun -> around.none { (x, own) -> x.isHardRun || legs(x, own) }
            else -> true
        }
        // Legs owed with only tomorrow's hard run in the way (nothing hard and no legs yesterday, no legs tomorrow, a hard
        // run that can be eased or moved, not a long one): offered only with that run eased or moved, so the legs and a hard
        // run still never fall on consecutive days.
        val owed = legsDaysAgo != null && legsDaysAgo >= LEGS_OWED
        fun beforeHard(s: PlannedSession): Boolean = owed && legs(s) &&
            yesterday.none { (x, own) -> legs(x, own) || x.isHardRun } &&
            tomorrow.none { (x, _) -> legs(x) || x.kind == SessionKind.RUN_LONG } && tomorrow.any { (x, _) -> x.kind == SessionKind.RUN_QUALITY }
        // Moved to the day after tomorrow: 48 h after today's legs, onto a day with no legs or hard run of its own, and the
        // day after it free of both (48 h before the next hard run). Not into the next week (tomorrow a Sunday): the moved
        // run counts for its own day, which [closes] books within one week.
        val moveOk = today != 6 && listOf(1, 2).all { k -> ahead.getOrElse(k) { emptyList() }.none { it.isHardRun || legs(it) } }
        val emphasis = program.aim.normalized().emphasis
        val fitting = missed.mapNotNull { m ->
            when {
                fits(m.session) -> m
                beforeHard(m.session) -> m.copy(beforeHard = true, moveOk = moveOk, legsDaysAgo = legsDaysAgo)
                else -> null
            }
        }
        // Nothing fits, but a hard run was missed: it can be today as an easy one (an easy run fits any day).
        if (fitting.isEmpty()) return missed.lastOrNull { it.session.isHardRun }?.copy(easy = true)
        return fitting.sortedWith(
            // Owed legs first, whatever the emphasis: a week or more without them (research D1, 25 November).
            compareByDescending<Suggestion> { owed && legs(it.session) }.thenByDescending {
                when (emphasis) {
                    Emphasis.GYM -> if (it.session.kind == SessionKind.LIFT) 1 else 0
                    Emphasis.RUN -> if (it.session.isRun) 1 else 0
                    Emphasis.BALANCED -> 0
                }
            }.thenByDescending { it.session.isHardRun || it.session.kind == SessionKind.LIFT }.thenByDescending { it.plannedOn },
        ).firstOrNull()
    }
}
