package forma.core.plan

import forma.core.model.Emphasis

/**
 * A session missed earlier this week that can be done today instead of resting.
 *
 * Only on a free day, and only when it breaks none of the layout rules against what was done yesterday and what is
 * planned tomorrow (on Sunday: next Monday): no heavy legs on consecutive days, no two hard runs in a row, no hard
 * run and heavy legs on consecutive days in either order.
 */
object CatchUp {
    /**
     * One day of this week: what was planned and what was actually done (as sessions). [doneLegs]: for each done session
     * (in the same order), whether it worked the legs when its template cannot say (a friend's copied workout); null or
     * missing — as its template says.
     */
    data class Day(
        val day: Int,
        val planned: List<PlannedSession>,
        val done: List<PlannedSession>,
        val doneLegs: List<Boolean?> = emptyList(),
    )

    data class Suggestion(val session: PlannedSession, val plannedOn: Int)

    /**
     * A planned session closed by a done one: the [index]-th planned session of [day] by the [doneIndex]-th done one of
     * [doneDay] (the same day, or a later one when it was caught up).
     */
    data class Close(val day: Int, val index: Int, val doneDay: Int, val doneIndex: Int)

    /**
     * Which planned sessions of the week are done, and by what. On its own day a session is done by one of its kind: the
     * same kind first (the hard run by the hard run, a lift by its template), then in order (a lift by a lift, whichever
     * template; a run by a run) — as the day shows it. A session done on a day where nothing of its kind was left to do
     * closes a missed one from an earlier day of the week (from [firstDay]): a lift any missed lift, the same template
     * first (another template or a friend's copied workout counts as the gym done); a run a missed run of its kind, and a
     * hard run a missed easy one. Failing that, it was done ahead: it closes a later one the same way once that day is
     * over (before [today], ISO; 8 — the whole week is over).
     */
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
        fun pick(s: PlannedSession, from: List<Pair<Int, Int>>) = if (s.kind == SessionKind.LIFT) {
            from.firstOrNull { planned(it).kind == SessionKind.LIFT && planned(it).templateId == s.templateId }
                ?: from.firstOrNull { planned(it).kind == SessionKind.LIFT }
        } else {
            from.firstOrNull { planned(it).kind == s.kind }
                ?: from.firstOrNull { s.isHardRun && planned(it).kind == SessionKind.RUN_EASY }
        }
        for ((doneDay, doneIndex) in spare.sortedBy { it.first }) {
            val s = week.first { it.day == doneDay }.done[doneIndex]
            // Ahead only from this plan's days: a session of the plan before a mid-week change closes nothing later.
            val ahead = if (doneDay >= firstDay) open.filter { it.first in doneDay + 1 until today } else emptyList()
            val match = pick(s, open.filter { it.first < doneDay }) ?: pick(s, ahead) ?: continue
            open.remove(match)
            out += Close(match.first, match.second, doneDay, doneIndex)
        }
        return out
    }

    /** [today] is ISO 1..7; [week] holds Monday..Sunday of this week; days before [firstDay] (ISO) are ignored. */
    fun suggest(program: Program, today: Int, week: List<Day>, firstDay: Int = 1): Suggestion? {
        val todayPlan = week.firstOrNull { it.day == today } ?: return null
        if (todayPlan.planned.isNotEmpty() || todayPlan.done.isNotEmpty()) return null

        // Missed: planned before today and not done on its day or caught up on a later one (as the week shows it).
        val closed = closes(week.filter { it.day <= today }, firstDay, today).map { it.day to it.index }.toSet()
        val missed = week.filter { it.day in firstDay until today }.sortedBy { it.day }.flatMap { d ->
            d.planned.withIndex().filter { (d.day to it.index) !in closed }.map { Suggestion(it.value, d.day) }
        }
        if (missed.isEmpty()) return null

        val yesterdayDay = week.firstOrNull { it.day == today - 1 }
        val yesterday = yesterdayDay?.done.orEmpty().mapIndexed { i, s -> s to yesterdayDay?.doneLegs?.getOrNull(i) }
        val tomorrow = (if (today == 7) program.week.on(1) else week.firstOrNull { it.day == today + 1 }?.planned.orEmpty()).map { it to null }
        fun legs(s: PlannedSession, own: Boolean? = null) =
            s.kind == SessionKind.LIFT && (own ?: program.templates.firstOrNull { it.id == s.templateId }?.legs ?: true)
        val around = yesterday + tomorrow
        fun fits(s: PlannedSession): Boolean = when {
            legs(s) -> around.none { (x, own) -> legs(x, own) || x.isHardRun }
            s.isHardRun -> around.none { (x, own) -> x.isHardRun || legs(x, own) }
            else -> true
        }
        val emphasis = program.aim.normalized().emphasis
        return missed.filter { fits(it.session) }.sortedWith(
            compareByDescending<Suggestion> {
                when (emphasis) {
                    Emphasis.GYM -> if (it.session.kind == SessionKind.LIFT) 1 else 0
                    Emphasis.RUN -> if (it.session.isRun) 1 else 0
                    Emphasis.BALANCED -> 0
                }
            }.thenByDescending { it.session.isHardRun || it.session.kind == SessionKind.LIFT }.thenByDescending { it.plannedOn },
        ).firstOrNull()
    }
}
