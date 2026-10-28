package forma.core

import forma.core.model.Catalog
import forma.core.model.LoadKind
import forma.core.strength.DoneSet
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Denis: «после того как я подтягивался 25 на 7 с запасом, приложение все равно рекомендовало 22.5 на 7 — такой проблемы
 * больше во всех упражнениях не будет?» Every exercise of the catalog: every set a step heavier than planned, the reps
 * made (with reserve, to failure, or one short on the last) — the next plan is never under the weight done.
 */
class HeavierSetsCreditedTest {
    private val bw = 75.0

    @Test fun setsDoneHeavierThanPlannedAreCreditedInEveryExercise() {
        val wrong = mutableListOf<String>()
        for (ex in Catalog.builtIn.filter { !it.archived && it.load != LoadKind.TIMED }) {
            val spec = SlotSpec(ex.id, 3, 5, 8, 2, 120, SlotRole.SECONDARY)
            val start = if (ex.load == LoadKind.BODYWEIGHT_PLUS) 10.0 else max(ex.bar ?: 0.0, 20.0)
            val w = forma.core.floorTo(start, ex.stepAt(start))
            val heavier = w + ex.stepAt(w)
            val st = SlotState(w, 6, sessions = 3, linear = false)
            val p = Progression.prescribe(ex, spec, st, e1rm = null, bodyWeight = bw)
            val cases = mapOf(
                "with 2 left" to List(3) { DoneSet(heavier, 6, 2) },
                "to failure" to List(3) { DoneSet(heavier, 6, 0) },
                "one short on the last" to listOf(DoneSet(heavier, 6, 1), DoneSet(heavier, 6, 0), DoneSet(heavier, 5, 0)),
                "not rated" to List(3) { DoneSet(heavier, 6, null) },
            )
            for ((name, done) in cases) {
                val ev = Progression.evaluate(ex, spec, st, p, done, bw)
                val next = Progression.prescribe(ex, spec, ev.state, e1rm = ev.e1rm, bodyWeight = bw)
                if (next.weight!! < heavier - 1e-9) wrong += "${ex.id}: planned ${p.weight} × ${p.reps}, did $heavier × 6 $name → ${ev.outcome}, next ${next.weight} × ${next.reps}"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    private fun next(id: String, spec: SlotSpec, w: Double, reps: Int, rir: Int, done: List<DoneSet>, steps: Double? = null): Pair<Double, Int> {
        val ex = Catalog[id].let { e -> steps?.let { e.copy(step = it, fineStep = null) } ?: e }
        val st = SlotState(w, reps, sessions = 2, linear = false)
        val p = Progression.prescribe(ex, spec, st, rirOffset = rir - spec.rir, bodyWeight = 71.0)
        val ev = Progression.evaluate(ex, spec, st, p, done, 71.0)
        val n = Progression.prescribe(ex, spec, ev.state, bodyWeight = 71.0)
        return n.weight!! to n.reps
    }

    /**
     * His chest press 8–10 on 27.10: planned 85 × 8 (1 left); 85 × 8 with 4 left, then — after the app's hint — 90 × 8
     * (2 left) and 90 × 8 (0 left). The next plan stays 85 (the weight all sets reached; crediting what most of them
     * reached made simulated lifters miss more), and after a first set with that much to spare the hint says 90 again.
     */
    @Test fun aFirstSetAtThePlanKeepsItAndTheHintGoesUp() {
        val spec = SlotSpec("chest_press", 3, 8, 10, 2, 120, SlotRole.SECONDARY)
        val ex = Catalog["chest_press"]
        val (w, reps) = next("chest_press", spec, 85.0, 8, 1, listOf(DoneSet(85.0, 8, 4), DoneSet(90.0, 8, 2), DoneSet(90.0, 8, 0)))
        assertTrue(w >= 85.0, "85 × 8, 90 × 8, 90 × 8 → next $w × $reps")
        val p = Progression.prescribe(ex, spec, SlotState(w, reps, sessions = 3, linear = false), rirOffset = -1, bodyWeight = 71.0)
        val hint = Progression.nextSet(ex, p, listOf(DoneSet(w, 8, 4)), 71.0)!!
        assertTrue(hint.weight >= 90.0, "after $w × 8 with 4 left the hint says ${hint.weight}")
    }

    /** One heavier set — the "+" set — does not move the weight by itself; nor heavier sets short of the reps. */
    @Test fun oneHeavierSetOrMissedRepsDoNot() {
        val spec = SlotSpec("chest_press", 3, 8, 10, 2, 120, SlotRole.SECONDARY)
        val (hero, _) = next("chest_press", spec, 85.0, 8, 2, listOf(DoneSet(85.0, 8, 2), DoneSet(85.0, 8, 2), DoneSet(95.0, 8, 0)))
        assertTrue(hero < 95.0, "85, 85, then one 95 × 8 to failure → next $hero")
        // His pull-ups 27.10: 25 × 8 (3 left), 30 × 7, 30 × 6 on a target of 7 — at 30 the reps were not all there: 25.
        val pull = SlotSpec("pullup", 3, 5, 8, 2, 150, SlotRole.MAIN)
        val (w, _) = next("pullup", pull, 22.5, 7, 1, listOf(DoneSet(25.0, 8, 3), DoneSet(30.0, 7, 0), DoneSet(30.0, 6, 0)))
        assertTrue(w in 25.0..27.5, "25 × 8, 30 × 7, 30 × 6 → next $w")
    }
}
