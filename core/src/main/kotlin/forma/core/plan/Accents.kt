package forma.core.plan

import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Experience
import forma.core.model.Muscle
import forma.core.strength.SlotRole

/**
 * Up to two muscle groups worked more than evenly (the program wizard's «акценты», Denis 31.10): a set more on the
 * other exercises that work the group first, and its own isolation exercise where the week has too little of it. The
 * main lifts stay as they are; nothing goes past [MAX_SETS] sets an exercise or [MAX_WEEK] a group a week.
 */
object Accents {
    /** At most this many groups at once: more than two is evenly again. */
    const val MAX = 2

    /** Sets an exercise at most. */
    const val MAX_SETS = 5

    /** A group's planned sets a week at most with the accent: past this more sets add fatigue, not growth. */
    const val MAX_WEEK = 22.0

    /** Below this many direct sets a week a group gets its own exercise. */
    const val MIN_DIRECT = 8

    /** The isolation exercise that works each group first. */
    val ISOLATION = mapOf(
        Muscle.CHEST to "cable_fly", Muscle.BACK to "lat_pulldown", Muscle.SHOULDERS to "lateral_raise", Muscle.BICEPS to "db_curl",
        Muscle.TRICEPS to "pushdown", Muscle.QUADS to "leg_ext", Muscle.HAMSTRINGS to "leg_curl", Muscle.GLUTES to "hip_thrust",
        Muscle.CALVES to "calf_raise", Muscle.CORE to "leg_raise",
    )

    private val LOWER = setOf(Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES, Muscle.CALVES)

    fun apply(templates: List<Template>, accents: Set<Muscle>, aim: Aim, exp: Experience): List<Template> {
        val chosen = accents.take(MAX)
        if (chosen.isEmpty() || templates.isEmpty()) return templates
        var out = templates
        for (m in chosen) {
            // A set more where the group works first, outside the main lifts.
            out = out.map { t ->
                t.copy(slots = t.slots.map { s ->
                    val first = Catalog.find(s.exerciseId)?.primary?.contains(m) == true
                    if (first && s.role != SlotRole.MAIN && s.sets < MAX_SETS && weekly(out)[m]!! < MAX_WEEK) s.copy(sets = s.sets + 1) else s
                })
            }
            // Too little straight work on it: its own exercise on the day that suits it, before the other isolation work
            // (a day too long for the time loses those first).
            val direct = out.sumOf { t -> t.slots.filter { Catalog.find(it.exerciseId)?.primary?.contains(m) == true }.sumOf { it.sets } }
            val id = ISOLATION[m] ?: continue
            if (direct >= MIN_DIRECT || Catalog.find(id) == null) continue
            val lower = m in LOWER
            val day = out.filter { t -> t.slots.none { it.exerciseId == id } }
                .sortedWith(compareBy<Template>({ if (lower) it.focus == Focus.UPPER else it.focus == Focus.LOWER }, { it.slots.size }))
                .firstOrNull() ?: continue
            val add = Templates.accessory(aim, exp, id)
            out = out.map { t ->
                if (t.id != day.id) t else {
                    val at = t.slots.indexOfFirst { it.role == SlotRole.ACCESSORY }.let { if (it < 0) t.slots.size else it }
                    t.copy(slots = t.slots.take(at) + add + t.slots.drop(at))
                }
            }
        }
        return out
    }

    /**
     * Sets a week per group, each template once: a set counts whole for the groups an exercise works first and half for
     * those it helps (as the progress screen counts them).
     */
    fun weekly(templates: List<Template>): Map<Muscle, Double> {
        val out = Muscle.entries.associateWith { 0.0 }.toMutableMap()
        for (t in templates) for (s in t.slots) {
            val ex = Catalog.find(s.exerciseId) ?: continue
            ex.primary.forEach { out[it] = out.getValue(it) + s.sets }
            ex.secondary.forEach { out[it] = out.getValue(it) + s.sets * 0.5 }
        }
        return out
    }

    /** The program's week: each lifting session of the week with its template. */
    fun weekly(program: Program): Map<Muscle, Double> =
        weekly(program.week.days.flatMap { d -> d.sessions.mapNotNull { s -> s.templateId?.let { id -> program.templates.firstOrNull { it.id == id } } } })
}
