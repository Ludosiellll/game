package forma.core.plan

import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec

/** Rep schemes and workout templates for each goal. */
object Templates {
    private data class Scheme(val sets: Int, val min: Int, val max: Int, val rir: Int, val rest: Int)

    private fun scheme(goal: Goal, role: SlotRole): Scheme = when (goal) {
        Goal.STRENGTH -> when (role) {
            SlotRole.MAIN -> Scheme(4, 4, 6, 2, 180)
            SlotRole.SECONDARY -> Scheme(3, 6, 8, 2, 150)
            SlotRole.ACCESSORY -> Scheme(3, 8, 12, 2, 90)
        }
        Goal.MUSCLE -> when (role) {
            SlotRole.MAIN -> Scheme(3, 6, 10, 2, 150)
            SlotRole.SECONDARY -> Scheme(3, 8, 12, 1, 120)
            SlotRole.ACCESSORY -> Scheme(3, 10, 15, 1, 75)
        }
        Goal.HYBRID -> when (role) {
            SlotRole.MAIN -> Scheme(3, 5, 8, 2, 150)
            SlotRole.SECONDARY -> Scheme(3, 8, 10, 2, 120)
            SlotRole.ACCESSORY -> Scheme(2, 10, 15, 2, 75)
        }
        Goal.ENDURANCE -> when (role) {
            SlotRole.MAIN -> Scheme(3, 5, 8, 2, 150)
            SlotRole.SECONDARY -> Scheme(2, 8, 10, 2, 120)
            SlotRole.ACCESSORY -> Scheme(2, 12, 15, 2, 60)
        }
        Goal.FITNESS -> when (role) {
            SlotRole.MAIN -> Scheme(3, 8, 12, 2, 120)
            SlotRole.SECONDARY -> Scheme(3, 10, 12, 2, 90)
            SlotRole.ACCESSORY -> Scheme(2, 12, 15, 2, 60)
        }
    }

    private fun slot(goal: Goal, exp: Experience, id: String, role: SlotRole): SlotSpec {
        if (id == "plank") return SlotSpec(id, 3, 20, 60, 1, 60, SlotRole.ACCESSORY)
        val s = scheme(goal, role)
        // Novices keep a bit more in reserve and fewer sets on main lifts while learning technique.
        val rir = if (exp == Experience.NOVICE) maxOf(s.rir, 2) else s.rir
        val sets = if (exp == Experience.NOVICE && role == SlotRole.MAIN) minOf(s.sets, 3) else s.sets
        return SlotSpec(id, sets, s.min, s.max, rir, s.rest, role)
    }

    private fun build(goal: Goal, exp: Experience, id: String, title: String, focus: Focus, vararg parts: Pair<String, SlotRole>) =
        Template(id, title, focus, parts.map { (ex, role) -> slot(goal, exp, ex, role) })

    /**
     * Workout templates for [lifts] sessions a week. 2–3: full body A/B/C; 4+: upper/lower, a fifth day is full body C.
     */
    fun forWeek(goal: Goal, exp: Experience, lifts: Int): List<Template> {
        val novice = exp == Experience.NOVICE
        val M = SlotRole.MAIN
        val S = SlotRole.SECONDARY
        val A = SlotRole.ACCESSORY
        val runner = goal == Goal.ENDURANCE || goal == Goal.HYBRID

        val fullA = build(goal, exp, "full_a", "Всё тело · А", Focus.FULL,
            "squat" to M, "bench" to M, (if (novice) "cable_row" else "row") to S,
            "lateral_raise" to A, "plank" to A)
        val fullB = build(goal, exp, "full_b", "Всё тело · Б", Focus.FULL,
            (if (novice) "rdl" else "deadlift") to M, "ohp" to M, (if (novice) "lat_pulldown" else "pullup") to S,
            (if (runner) "bulgarian" else "db_lunge") to S, "db_curl" to A, "pushdown" to A)
        val fullC = build(goal, exp, "full_c", "Всё тело · В", Focus.FULL,
            (if (novice) "goblet_squat" else "leg_press") to M, "db_incline" to M, "db_row" to S,
            "hip_thrust" to S, "face_pull" to A, (if (runner) "calf_raise" else "leg_raise") to A)

        val upperA = build(goal, exp, "upper_a", "Верх · А", Focus.UPPER,
            "bench" to M, (if (novice) "cable_row" else "row") to M, "db_shoulder" to S, "lat_pulldown" to S,
            "db_curl" to A, "pushdown" to A)
        val lowerA = build(goal, exp, "lower_a", "Низ · А", Focus.LOWER,
            "squat" to M, "rdl" to S, (if (runner) "bulgarian" else "leg_press") to S, "leg_curl" to A, "calf_raise" to A, "plank" to A)
        val upperB = build(goal, exp, "upper_b", "Верх · Б", Focus.UPPER,
            "ohp" to M, (if (novice) "lat_pulldown" else "pullup") to M, "db_incline" to S, "cable_row" to S,
            "lateral_raise" to A, "hammer_curl" to A)
        val lowerB = build(goal, exp, "lower_b", "Низ · Б", Focus.LOWER,
            (if (novice) "leg_press" else "deadlift") to M, (if (novice) "goblet_squat" else "front_squat") to S, "hip_thrust" to S,
            "leg_ext" to A, "calf_raise" to A, "leg_raise" to A)

        return when {
            lifts <= 0 -> emptyList()
            lifts == 1 -> listOf(fullA)
            lifts == 2 -> listOf(fullA, fullB)
            // Runners keep legs to two sessions: the third lifting day trains the upper body.
            lifts == 3 && runner -> listOf(fullA, upperA.copy(title = "Верх"), fullB)
            lifts == 3 -> listOf(fullA, fullB, fullC)
            lifts == 4 -> listOf(upperA, lowerA, upperB, lowerB)
            else -> listOf(upperA, lowerA, upperB, lowerB, fullC)
        }
    }

    /** Rough minutes for a template: work + rest per set, plus 10 minutes of warm-up. */
    fun minutes(t: Template): Int = 10 + t.slots.sumOf { it.sets * (it.restSec + 45) } / 60

    /** Drops accessories (then a set from secondary lifts) until the template fits [budget] minutes. */
    fun fit(t: Template, budget: Int): Template {
        var cur = t
        while (minutes(cur) > budget) {
            val acc = cur.slots.lastOrNull { it.role == SlotRole.ACCESSORY }
            cur = if (acc != null && cur.slots.size > 3) {
                cur.copy(slots = cur.slots - acc)
            } else {
                val sec = cur.slots.lastOrNull { it.role == SlotRole.SECONDARY && it.sets > 2 } ?: break
                cur.copy(slots = cur.slots.map { if (it === sec) it.copy(sets = it.sets - 1) else it })
            }
        }
        return cur
    }
}
