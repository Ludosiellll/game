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
    /** One day of this week: what was planned and what was actually done (as sessions). */
    data class Day(val day: Int, val planned: List<PlannedSession>, val done: List<PlannedSession>)

    data class Suggestion(val session: PlannedSession, val plannedOn: Int)

    /** [today] is ISO 1..7; [week] holds Monday..Sunday of this week; days before [firstDay] (ISO) are ignored. */
    fun suggest(program: Program, today: Int, week: List<Day>, firstDay: Int = 1): Suggestion? {
        val todayPlan = week.firstOrNull { it.day == today } ?: return null
        if (todayPlan.planned.isNotEmpty() || todayPlan.done.isNotEmpty()) return null

        // Missed: planned before today and not done on any day of the week (one done session covers one planned).
        val done = week.flatMap { it.done }.map(::key).toMutableList()
        val missed = mutableListOf<Suggestion>()
        for (d in week.filter { it.day in firstDay until today }.sortedBy { it.day }) {
            for (s in d.planned) {
                if (!done.remove(key(s))) missed += Suggestion(s, d.day)
            }
        }
        if (missed.isEmpty()) return null

        val yesterday = week.firstOrNull { it.day == today - 1 }?.done.orEmpty()
        val tomorrow = if (today == 7) program.week.on(1) else week.firstOrNull { it.day == today + 1 }?.planned.orEmpty()
        fun legs(s: PlannedSession) = s.kind == SessionKind.LIFT && (program.templates.firstOrNull { it.id == s.templateId }?.legs ?: true)
        fun fits(s: PlannedSession): Boolean = when {
            legs(s) -> (yesterday + tomorrow).none { legs(it) || it.isHardRun }
            s.isHardRun -> (yesterday + tomorrow).none { it.isHardRun || legs(it) }
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

    private fun key(s: PlannedSession) = s.kind to (if (s.kind == SessionKind.LIFT) s.templateId else null)
}
