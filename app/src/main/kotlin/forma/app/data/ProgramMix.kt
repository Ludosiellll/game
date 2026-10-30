package forma.app.data

import forma.core.model.Aim
import forma.core.model.LiftFocus
import forma.core.model.RunFocus
import forma.core.plan.DayPlan
import forma.core.plan.Program
import forma.core.plan.SessionKind
import forma.core.plan.WeekLoad
import forma.core.plan.WeekPlan

/** A way to make a copied week lighter (see [WeekLoad]): one hard run easy, one gym day less. */
enum class Ease { RUN, LIFT }

/**
 * What a copy of someone's program ([theirs]) makes of one's own ([own]): all of theirs, their gym with one's own
 * running, or one's own gym with their running — the two weeks put together day by day (each side on its own days, a
 * day of both keeps both), the aim of the side each comes from, the running's limits from where the running comes from.
 * [ease] makes it lighter when the week asks too much.
 */
object ProgramMix {
    fun mix(own: Program, theirs: Program, part: CopyPart, ease: Set<Ease> = emptySet()): Program {
        val their = theirs.copy(templates = theirs.shared ?: theirs.templates, shared = null)
        val mixed = when (part) {
            CopyPart.ALL -> their
            CopyPart.GYM -> their.copy(
                week = merge(lifts = their.week, runs = own.week),
                aim = Aim(their.aim.lift.takeIf { it != LiftFocus.NONE } ?: LiftFocus.GENERAL, own.aim.run, own.aim.emphasis).normalized(),
                runCaps = own.runCaps,
            )
            CopyPart.RUN -> own.copy(
                templates = own.templates, shared = null,
                week = merge(lifts = own.week, runs = their.week),
                aim = Aim(own.aim.lift, their.aim.run.takeIf { it != RunFocus.NONE } ?: RunFocus.HEALTH, own.aim.emphasis).normalized(),
                runCaps = their.runCaps,
            )
        }.let { if (part == CopyPart.ALL) it else it.copy(goal = it.aim.nearestGoal()) }
        var week = mixed.week
        if (Ease.RUN in ease) week = WeekLoad.easeRun(week, mixed.templates) ?: week
        if (Ease.LIFT in ease) week = WeekLoad.dropLift(week, mixed.templates) ?: week
        return mixed.copy(week = week)
    }

    /** The gym sessions of [lifts] and the runs of [runs], day by day: the gym first on a day of both. */
    private fun merge(lifts: WeekPlan, runs: WeekPlan): WeekPlan = WeekPlan(
        (1..7).map { d -> DayPlan(d, lifts.on(d).filter { it.kind == SessionKind.LIFT } + runs.on(d).filter { it.isRun }) },
        runs.notes,
    )
}
