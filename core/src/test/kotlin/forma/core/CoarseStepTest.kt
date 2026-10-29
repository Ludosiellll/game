package forma.core

import forma.core.model.Catalog
import forma.core.model.LoadKind
import forma.core.strength.DoneSet
import forma.core.strength.OneRm
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Light dumbbells: the next one is a big share heavier (10 → 12 kg, a fifth). Lives of 40 sessions on them went up to
 * the next dumbbell, fell short, came back and went up again, month after month (105 of 4000 random lives).
 */
class CoarseStepTest {
    @Test fun aGenerousRatingDoesNotTakeTheNextDumbbell() {
        // Dumbbell row 8–10, cut from 12 × 7 (done 6, 6, 5) to 10 × 7: 10 × 7, 7 «ещё 3», then the «+» set 10 «ещё 3» —
        // with one or two really there. Read as said, 12 × 7 was "there", and the lifter was back at 12 to fall short.
        val ex = Catalog["db_row"]
        val spec = SlotSpec(ex.id, 3, 8, 10, 1, 90, SlotRole.ACCESSORY)
        val st = SlotState(10.0, 7, sessions = 25, linear = false, cutFrom = 12.0, cutReps = 6)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 80.0)
        assertEquals(10.0, p.weight)
        val e = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(10.0, 7, null), DoneSet(10.0, 7, 3), DoneSet(10.0, 10, 3)), 80.0)
        assertTrue(e.state.weight == 10.0, "${e.outcome}")
    }

    /**
     * Lives on light dumbbells near the big steps (8–12 kg), forty sessions each, the strength flat or growing, the
     * ratings honest or not: none goes up to a dumbbell and back down to the one before three times.
     */
    @Test fun noLifeSwingsBetweenTwoDumbbells() {
        val ids = listOf("lateral_raise", "db_curl", "db_bench", "db_incline", "db_row", "db_shoulder")
        val r = Random(20261029)
        val swinging = mutableListOf<String>()
        repeat(600) {
            val ex = Catalog[ids[r.nextInt(ids.size)]]
            val lo = listOf(6, 8, 10, 12)[r.nextInt(4)]
            val spec = SlotSpec(ex.id, 3, lo, lo + listOf(2, 4)[r.nextInt(2)], r.nextInt(1, 3), 90, SlotRole.ACCESSORY)
            val bw = 80.0
            val honest = r.nextBoolean()
            val fatigue = listOf(0.0, 0.5, 1.0)[r.nextInt(3)]
            var true1 = OneRm.estimate(listOf(8.0, 9.0, 10.0, 12.0)[r.nextInt(4)], spec.repMax + spec.rir, 0) * (0.95 + 0.1 * r.nextDouble())
            val gain = listOf(0.0, 0.002, 0.005)[r.nextInt(3)]
            var state = SlotState.fresh(spec)
            var e1rm: Double? = null
            val weights = mutableListOf<Double>()
            val trail = mutableListOf<String>()
            for (k in 0 until 40) {
                val p = Progression.prescribe(ex, spec, state, e1rm = e1rm, bodyWeight = bw, hint = Progression.startHint(ex, bw, false, 1.0))
                var weight = p.weight ?: p.startHint ?: 4.0
                val done = mutableListOf<DoneSet>()
                for (i in 0 until p.sets) {
                    val rtf = (OneRm.rtfAt(true1, Progression.effectiveLoad(ex, weight, bw)) - fatigue * i).toInt().coerceAtLeast(1)
                    val goal = when {
                        p.calibrate -> rtf - p.rir
                        p.top && i == p.sets - 1 -> max(p.reps, rtf - p.topRir)
                        else -> p.reps
                    }
                    val reps = min(rtf, max(1, goal))
                    val rir = if (honest) (rtf - reps).coerceIn(0, 5) else if (r.nextBoolean()) null else (rtf - reps + r.nextInt(-1, 3)).coerceIn(0, 5)
                    done += DoneSet(weight, reps, rir)
                    Progression.adjustAfterSet(ex, p, done.last(), bw, i)?.let { weight = it.weight }
                }
                val ev = Progression.evaluate(ex, spec, state, p, done, bw, false, e1rm)
                ev.e1rm?.let { e1rm = Progression.smoothE1rm(e1rm, it, false) }
                weights += ev.state.weight ?: 0.0
                trail += "${k + 1}: [cut ${state.cutFrom}/${state.cutReps} top ${p.top} sets ${p.sets} rir ${p.rir}] ${p.weight}×${p.reps} ${done.joinToString(" ") { "${it.weight}×${it.reps}${it.rir?.let { x -> "@$x" } ?: ""}" }} → ${ev.outcome}"
                state = ev.state
                true1 *= 1 + gain
            }
            val swings = (1 until weights.size).count { i ->
                weights[i] > weights[i - 1] + 1e-9 && (i + 1 until min(weights.size, i + 4)).any { weights[it] <= weights[i - 1] + 1e-9 }
            }
            if (swings >= 3) swinging += "${ex.id} ${spec.repMin}–${spec.repMax}: ${weights.joinToString(" ")}\n" + trail.take(12).joinToString("\n")
        }
        assertTrue(swinging.isEmpty(), "${swinging.size} lives swing:\n${swinging.take(3).joinToString("\n")}")
        assertTrue(ids.all { Catalog[it].load == LoadKind.EXTERNAL })
    }
}
