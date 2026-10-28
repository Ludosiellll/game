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
}
