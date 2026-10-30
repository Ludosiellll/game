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
data class LiftPlan(
    val templateId: String,
    val title: String,
    val items: List<LiftItem>,
    val deload: Boolean,
    /** Why the session is lighter when it is not the plan's lighter week (the first one after being sick). */
    val why: String? = null,
)

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
    /** The warm-up's strides: each one's speed by GPS, m/s (null where not measured); null when there were none. */
    val strides: List<Double?>? = null,
    /** The warm-up's jog before the strides, m/s. */
    val jog: Double? = null,
)

/** How the person feels today: decides between the planned session, a lighter one and rest. */
enum class Feel { UNSET, GOOD, LOW, SICK }

/**
 * What of someone's program is taken: all of it ([ALL]), their gym with one's own running ([GYM]), or one's own gym with
 * their running ([RUN]) — see [ProgramMix].
 */
enum class CopyPart { ALL, GYM, RUN }

/**
 * The last run saved: the running progress before it and after it, and the run as planned and as done. While the
 * progress is still [after] (nothing built on it since), the run's rating put right decides it again.
 */
@kotlinx.serialization.Serializable
data class LastRun(
    val workoutId: Long,
    val day: Long,
    val before: forma.core.run.RunState,
    val after: forma.core.run.RunState,
    val workout: forma.core.run.RunWorkout,
    val result: forma.core.run.RunResult,
)

/**
 * A day of the week with the plan and what was actually done. [onDay]: planned sessions of this day done on it (index in
 * [planned] → the workout); [doneLater]: planned sessions of this day done on another day of the week — caught up later
 * or done ahead (index → the workout); [catchUps]: workouts of this day that closed a session of another day (workout id
 * → that day) — as [forma.core.plan.CatchUp.closes] decides. [paused]: a day of a pause, nothing expected ([planned] is
 * empty).
 */
data class DayItem(
    val date: LocalDate,
    val planned: List<PlannedSession>,
    val done: List<WorkoutEntity>,
    val doneLater: Map<Int, WorkoutEntity> = emptyMap(),
    val catchUps: Map<Long, LocalDate> = emptyMap(),
    val paused: Boolean = false,
    val onDay: Map<Int, WorkoutEntity>? = null,
) {
    /** Done, on this day or on another day of the week. */
    fun isDone(s: PlannedSession): Boolean = doneFor(s) != null || doneLaterFor(s) != null

    /** The workout done that day in place of [s]: of the same kind first, then the gym for a gym session, any run for a run. */
    fun doneFor(s: PlannedSession): WorkoutEntity? {
        onDay?.let { return it[planned.indexOf(s)] }
        val sameKind = done.filter { if (s.isRun) it.kind == Kind.RUN else it.kind == Kind.LIFT }
        val index = planned.filter { it.isRun == s.isRun }.indexOf(s)
        return sameKind.getOrNull(index)
    }

    /** The workout done on another day of the week for [s] of this one. */
    fun doneLaterFor(s: PlannedSession): WorkoutEntity? = doneLater[planned.indexOf(s)]

    /** Workouts of this day that stand for none of its planned sessions: one for another day, or one extra. */
    val offPlan: List<WorkoutEntity>
        get() {
            onDay?.let { m -> return done.filter { w -> m.values.none { it.id == w.id } } }
            val lifts = done.filter { it.kind == Kind.LIFT }.drop(planned.count { !it.isRun })
            val runs = done.filter { it.kind == Kind.RUN }.drop(planned.count { it.isRun })
            return done.filter { it in lifts || it in runs }
        }

    /** Planned and not done, on a day already over. */
    fun missed(today: LocalDate): List<PlannedSession> = if (date.isBefore(today)) planned.filter { !isDone(it) } else emptyList()
}

/** A day with the gym and a run: the two sessions, the day as it stands and the advice on which goes first. */
data class DayOrder(
    val run: forma.core.plan.PlannedSession,
    val lift: forma.core.plan.PlannedSession,
    val day: forma.core.plan.SessionOrder.Day,
    val advice: forma.core.plan.SessionOrder.Advice,
) {
    /** [sessions] of the day in the advised order (others after them as they were). */
    fun ordered(sessions: List<forma.core.plan.PlannedSession>): List<forma.core.plan.PlannedSession> {
        val first = if (advice.runFirst) run else lift
        val second = if (advice.runFirst) lift else run
        return listOf(first, second).filter { it in sessions } + sessions.filter { it != first && it != second }
    }
}

