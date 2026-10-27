package forma.core

import forma.core.model.Catalog
import forma.core.strength.DoneSet
import forma.core.strength.Outcome
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The engine alone on one real lifter's numbers (71 kg; the same cases as the app-level replay, without the repository). */
class WeightsReviewTest {
    private val bw = 71.0

    /** Pull-ups 22.10: planned 22.5 × 7 (3 sets, rir 2); done 25×7 rir3, 25×7 rir2, 25×8 rir0 — every set a step heavier. */
    @Test fun setsDoneHeavierThanPlannedMoveTheWeight() {
        val ex = Catalog["pullup"]
        val spec = SlotSpec("pullup", 3, 5, 8, 2, 150, SlotRole.MAIN)
        val p = Progression.prescribe(ex, spec, SlotState(22.5, 7, sessions = 2, linear = false), bodyWeight = bw)
        assertEquals(22.5 to 7, p.weight to p.reps)
        val ev = Progression.evaluate(ex, spec, SlotState(22.5, 7, sessions = 2, linear = false), p, listOf(DoneSet(25.0, 7, 3), DoneSet(25.0, 7, 2), DoneSet(25.0, 8, 0)), bw)
        assertTrue(ev.state.weight!! >= 25.0, "planned 22.5 × 7, did 25×7 rir3, 25×7 rir2, 25×8 rir0 → ${ev.outcome}; next ${ev.state.weight} × ${ev.state.reps}")
    }

    /** Cable row 13.10 (first time, 8–10): 64×10 rir5, 76×8 rir2, 76×7 rir0 — the 76×8 with 2 left is the target itself. */
    @Test fun calibrationIsNotDecidedByTheTiredRepeat() {
        val ex = Catalog["cable_row"]
        val spec = SlotSpec("cable_row", 3, 8, 10, 2, 120, SlotRole.SECONDARY)
        val p = Progression.prescribe(ex, spec, SlotState.fresh(spec), bodyWeight = bw, hint = 55.0)
        assertTrue(p.calibrate)
        val ev = Progression.evaluate(ex, spec, SlotState.fresh(spec), p, listOf(DoneSet(64.0, 10, 5), DoneSet(76.0, 8, 2), DoneSet(76.0, 7, 0)), bw)
        val c = assertIs<Outcome.Calibrated>(ev.outcome)
        assertTrue(c.weight >= 75.0, "64×10 rir5, 76×8 rir2, 76×7 rir0 → $c (estimate ${"%.1f".format(ev.e1rm)})")
    }

    /** Shoulder press 27.10: planned 70 × 5 rir 1; 70×9 rir4 → 75; 75×8 rir2 at the hinted weight → still 75. */
    @Test fun secondHintFollowsASetAtTheFirstHintedWeight() {
        val ex = Catalog["machine_shoulder_press"]
        val spec = SlotSpec("machine_shoulder_press", 3, 5, 8, 2, 150, SlotRole.MAIN)
        val p = Progression.prescribe(ex, spec, SlotState(70.0, 5, sessions = 1, linear = false), rirOffset = -1, bodyWeight = bw)
        assertEquals(1, p.rir)
        val first = Progression.nextSet(ex, p, listOf(DoneSet(70.0, 9, 4)), bw)!!
        assertEquals(75.0, first.weight)
        val second = Progression.nextSet(ex, p, listOf(DoneSet(70.0, 9, 4), DoneSet(75.0, 8, 2)), bw)!!
        assertTrue(second.weight >= 80.0, "70×9 rir4 → ${first.weight}; 75×8 rir2 (reserve ${second.reserve} vs ${second.plannedReserve}) → ${second.weight} × ${second.reps}")
    }

    /**
     * Lat pulldown 22.10 (2 sets): 85×8 "5 left", then the "+" set with nothing to spare. His "+" set was at 92, which
     * covered the jump; at the planned weight the one far-from-failure rating alone still moves the weight up.
     */
    @Test fun oneHighReserveRatingDoesNotOverrideAStuckPlusSet() {
        val ex = Catalog["lat_pulldown"]
        val spec = SlotSpec("lat_pulldown", 2, 8, 10, 2, 120, SlotRole.SECONDARY)
        val st = SlotState(85.0, 8, sessions = 1, linear = false)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = bw)
        val ev = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(85.0, 8, 5), DoneSet(85.0, 8, 0)), bw)
        assertTrue(ev.outcome !is Outcome.Jump, "85×8 rir5, 85×8 rir0 (the \"+\" set, 0 to spare) → ${ev.outcome}")
    }
}
