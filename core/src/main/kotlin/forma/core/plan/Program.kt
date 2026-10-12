package forma.core.plan

import forma.core.model.Experience
import forma.core.model.Aim
import forma.core.model.Goal
import forma.core.model.aim
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
    val aim: Aim = goal.aim(),
    /** The day this program was made (catch-up ignores what was planned before it). */
    val createdEpochDay: Long? = null,
    /** Name of the friend this program was copied from. */
    val copiedFrom: String? = null,
    /** Version of the planner that laid this week out (see [ProgramGenerator.PLANNER]): an older one is laid out again. */
    val planner: Int = 0,
) {
    fun template(id: String): Template = templates.first { it.id == id }

    /**
     * The program made safe to use when it comes from someone else: exercises this phone does not know ([known] says
     * which it does) are dropped, numbers are kept within sane bounds, and every planned lift must name a template with
     * exercises left. Null when nothing usable remains.
     */
    fun checked(known: (String) -> Boolean): Program? {
        val clean = templates.map { t ->
            t.copy(
                title = t.title.take(60),
                slots = t.slots.filter { known(it.exerciseId) }.take(12).map { s ->
                    val lo = s.repMin.coerceIn(1, 100)
                    s.copy(
                        sets = s.sets.coerceIn(1, 10), repMin = lo, repMax = s.repMax.coerceIn(lo, 100), rir = s.rir.coerceIn(0, 5),
                        restSec = s.restSec.coerceIn(0, 600), increment = s.increment?.takeIf { it > 0 && it <= 20 },
                    )
                },
            )
        }.filter { it.slots.isNotEmpty() }.distinctBy { it.id }
        val ids = clean.map { it.id }.toSet()
        val days = week.days.filter { it.day in 1..7 }.distinctBy { it.day }
        if (days.any { d -> d.sessions.any { it.kind == SessionKind.LIFT && it.templateId !in ids } }) return null
        if (days.all { it.sessions.isEmpty() }) return null
        return copy(templates = clean, week = week.copy(days = days.map { it.copy(sessions = it.sessions.take(3)) }))
    }
}
