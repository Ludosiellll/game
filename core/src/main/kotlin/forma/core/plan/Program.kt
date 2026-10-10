package forma.core.plan

import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.run.RunCaps
import forma.core.strength.SlotSpec
import kotlinx.serialization.Serializable

@Serializable
enum class SessionKind { LIFT, RUN_EASY, RUN_QUALITY, RUN_LONG }

@Serializable
enum class Focus { FULL, UPPER, LOWER }

@Serializable
data class Template(val id: String, val title: String, val focus: Focus, val slots: List<SlotSpec>) {
    val legs: Boolean get() = focus != Focus.UPPER
}

@Serializable
data class PlannedSession(val kind: SessionKind, val templateId: String? = null) {
    val isRun: Boolean get() = kind != SessionKind.LIFT
    val isHardRun: Boolean get() = kind == SessionKind.RUN_QUALITY || kind == SessionKind.RUN_LONG
}

/** [day] is ISO 1 = Monday … 7 = Sunday. Sessions are in the order to do them. */
@Serializable
data class DayPlan(val day: Int, val sessions: List<PlannedSession>)

@Serializable
data class WeekPlan(val days: List<DayPlan>, val notes: List<String>) {
    fun on(day: Int): List<PlannedSession> = days.firstOrNull { it.day == day }?.sessions.orEmpty()
}

/** Everything generated from the profile: workouts, the weekly layout and block length. */
@Serializable
data class Program(
    val goal: Goal,
    val experience: Experience,
    val templates: List<Template>,
    val week: WeekPlan,
    /** Weeks per block including the lighter last week; 0 — no planned deload (novices deload on demand). */
    val blockWeeks: Int,
    val runCaps: RunCaps,
    /** Monday of the first week, epoch day. */
    val startEpochDay: Long,
) {
    fun template(id: String): Template = templates.first { it.id == id }
}
