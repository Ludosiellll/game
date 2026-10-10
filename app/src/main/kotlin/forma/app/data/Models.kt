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
)

/** A day of the week with the plan and what was actually done. */
data class DayItem(val date: LocalDate, val planned: List<PlannedSession>, val done: List<WorkoutEntity>) {
    fun isDone(s: PlannedSession): Boolean {
        val sameKind = done.filter { if (s.isRun) it.kind == Kind.RUN else it.kind == Kind.LIFT }
        val index = planned.filter { it.isRun == s.isRun }.indexOf(s)
        return index in sameKind.indices
    }
}
