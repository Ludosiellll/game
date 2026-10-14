package forma.app.data

import forma.core.plan.PlannedSession
import forma.core.run.RunOutcome
import forma.core.strength.Outcome
import forma.core.strength.Prescription
import forma.core.strength.SlotSpec
import forma.core.strength.WarmUpSet
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** One exercise of today's lifting session with what to do. */
@Serializable
data class LiftItem(
    val position: Int,
    val slot: SlotSpec,
    val exerciseId: String,
    val name: String,
    val prescription: Prescription,
    val warmups: List<WarmUpSet>,
    val step: Double,
    /** The plan's own exercise at this place (before a swap); null for one added today only (and in older sessions). */
    val planId: String? = null,
    /** Added to the plan by the user, not one of the generated plan's own. */
    val addedForGood: Boolean = false,
    /** Why there is no warm-up before it (muscles warm already, single-muscle work), when there is none. */
    val noWarmUp: String? = null,
    /** Why the rest differs from the plan's for this person, when it does. */
    val restWhy: String? = null,
    /** Seconds this person needs more (less) than the plan's rest here, learned from their sets ([forma.core.strength.RestAdvice.personal]). */
    val restShift: Int = 0,
    /** The rest was set by the person: it stays as set. */
    val restOwn: Boolean = false,
)

@Serializable
data class LiftPlan(val templateId: String, val title: String, val items: List<LiftItem>, val deload: Boolean)

@Serializable
data class SlotReport(val exerciseId: String, val name: String, val outcome: Outcome, val e1rm: Double?, val record: Boolean)

@Serializable
data class LiftSummary(val reports: List<SlotReport>, val volumeKg: Double, val sets: Int, val minutes: Int, val load: Double)

@Serializable
data class RunSummary(
    val outcomes: List<RunOutcome>,
    val minutes: Int,
    val load: Double,
    /** Laps from the lap button on a stadium. */
    val laps: List<forma.core.run.Lap> = emptyList(),
    /** Steps a minute while running, from the phone's step counter. */
    val cadence: Int? = null,
    /** Run without the phone (a watch, a stopwatch) and entered by hand. */
    val manual: Boolean = false,
    /** Seconds of each whole kilometre (active time), as the voice said them. */
    val kmSplits: List<Int> = emptyList(),
)

/** How the person feels today: decides between the planned session, a lighter one and rest. */
enum class Feel { UNSET, GOOD, LOW, SICK }

/**
 * A day of the week with the plan and what was actually done. [doneLater]: planned sessions of this day caught up on a
 * later day of the week (index in [planned] → the workout); [catchUps]: workouts of this day that closed a missed session
 * of an earlier day (workout id → that day) — as [forma.core.plan.CatchUp.closes] decides. [paused]: a day of a pause,
 * nothing expected ([planned] is empty).
 */
data class DayItem(
    val date: LocalDate,
    val planned: List<PlannedSession>,
    val done: List<WorkoutEntity>,
    val doneLater: Map<Int, WorkoutEntity> = emptyMap(),
    val catchUps: Map<Long, LocalDate> = emptyMap(),
    val paused: Boolean = false,
) {
    /** Done, on this day or caught up later in the week. */
    fun isDone(s: PlannedSession): Boolean = doneFor(s) != null || doneLaterFor(s) != null

    /** The workout done that day in place of [s]: the gym for a gym session, any run for a run, in order. */
    fun doneFor(s: PlannedSession): WorkoutEntity? {
        val sameKind = done.filter { if (s.isRun) it.kind == Kind.RUN else it.kind == Kind.LIFT }
        val index = planned.filter { it.isRun == s.isRun }.indexOf(s)
        return sameKind.getOrNull(index)
    }

    /** The workout done on a later day of the week for [s] missed here. */
    fun doneLaterFor(s: PlannedSession): WorkoutEntity? = doneLater[planned.indexOf(s)]

    /** Workouts of this day that stand for none of its planned sessions: a missed one caught up, or one extra. */
    val offPlan: List<WorkoutEntity>
        get() {
            val lifts = done.filter { it.kind == Kind.LIFT }.drop(planned.count { !it.isRun })
            val runs = done.filter { it.kind == Kind.RUN }.drop(planned.count { it.isRun })
            return done.filter { it in lifts || it in runs }
        }

    /** Planned and not done, on a day already over. */
    fun missed(today: LocalDate): List<PlannedSession> = if (date.isBefore(today)) planned.filter { !isDone(it) } else emptyList()
}
