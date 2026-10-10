package forma.core

import forma.core.model.Catalog
import forma.core.strength.DoneSet
import forma.core.strength.HoldReason
import forma.core.strength.Outcome
import forma.core.strength.Phase
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressionTest {
    private val squat = Catalog["squat"]
    private val spec = SlotSpec("squat", sets = 3, repMin = 6, repMax = 8, rir = 2, restSec = 150, role = SlotRole.MAIN)

    private fun run(state: SlotState, vararg reps: Int, weight: Double = state.weight!!) =
        Progression.evaluate(squat, spec, state, Progression.prescribe(squat, spec, state), reps.map { DoneSet(weight, it) })

    @Test fun firstSessionCalibratesFromWhatWasLifted() {
        val fresh = SlotState.fresh(spec)
        val p = Progression.prescribe(squat, spec, fresh, hint = 40.0)
        assertTrue(p.calibrate)
        assertEquals(40.0, p.startHint)
        // 60 × 8 with ~2 in reserve → about 81 kg e1RM → 6 reps @ RIR 2 is about 64 kg.
        val adj = Progression.adjustAfterSet(squat, p, DoneSet(60.0, 8))
        assertNotNull(adj)
        assertEquals(65.0, adj.weight)
        val ev = Progression.evaluate(squat, spec, fresh, p, listOf(DoneSet(60.0, 8), DoneSet(65.0, 6), DoneSet(65.0, 6)))
        assertEquals(Outcome.Calibrated(65.0, 6), ev.outcome)
        assertEquals(65.0, ev.state.weight)
    }

    @Test fun allSetsAtTargetAddsARep() {
        val ev = run(SlotState(60.0, 6), 6, 6, 6)
        assertEquals(Outcome.AddReps(60.0, 6, 7), ev.outcome)
        assertEquals(7, ev.state.reps)
    }

    @Test fun topOfRangeAddsWeight() {
        val ev = run(SlotState(60.0, 8), 8, 8, 8)
        assertEquals(Outcome.AddWeight(60.0, 62.5, 6), ev.outcome)
    }

    @Test fun farAboveTargetJumps() {
        val ev = run(SlotState(60.0, 6), 10, 10, 9)
        val out = assertIs<Outcome.Jump>(ev.outcome)
        assertEquals(67.5, out.to)
        assertEquals(0, ev.state.misses)
    }

    @Test fun reportedEasyJumpsToo() {
        val st = SlotState(60.0, 6)
        val p = Progression.prescribe(squat, spec, st)
        val ev = Progression.evaluate(squat, spec, st, p, List(3) { DoneSet(60.0, 6, rir = 5) })
        assertIs<Outcome.Jump>(ev.outcome)
    }

    @Test fun nearMissHolds() {
        val ev = run(SlotState(60.0, 8), 8, 7, 7)
        assertEquals(Outcome.Hold(60.0, 8, HoldReason.NEAR_MISS), ev.outcome)
        assertEquals(1, ev.state.stalls)
    }

    @Test fun firstClearMissHoldsSecondReduces() {
        val first = run(SlotState(60.0, 8), 7, 6, 5)
        assertEquals(Outcome.Hold(60.0, 8, HoldReason.FIRST_MISS), first.outcome)
        val second = run(first.state, 7, 6, 5)
        val out = assertIs<Outcome.Reduce>(second.outcome)
        assertEquals(55.0, out.to)
        assertEquals(0, second.state.misses)
    }

    @Test fun bigMissReducesAtOnce() {
        val ev = run(SlotState(60.0, 8), 4, 4, 3)
        assertEquals(Outcome.Reduce(60.0, 50.0, 8), ev.outcome)
    }

    @Test fun threeStallsResetTenPercent() {
        var st = SlotState(60.0, 8)
        repeat(2) { st = run(st, 8, 7, 7).state }
        val ev = run(st, 8, 7, 7)
        assertEquals(Outcome.Reset(60.0, 52.5, 6), ev.outcome)
    }

    @Test fun fewerSetsHold() {
        val ev = run(SlotState(60.0, 6), 6, 6)
        assertEquals(Outcome.Hold(60.0, 6, HoldReason.FEWER_SETS), ev.outcome)
    }

    @Test fun deloadIsLighterAndDoesNotMoveProgress() {
        val st = SlotState(60.0, 7)
        val p = Progression.prescribe(squat, spec, st, phase = Phase.DELOAD)
        assertEquals(52.5, p.weight)
        assertEquals(2, p.sets)
        assertEquals(4, p.rir)
        val ev = Progression.evaluate(squat, spec, st, p, List(2) { DoneSet(52.5, 7) })
        assertEquals(Outcome.Deload, ev.outcome)
        assertEquals(60.0, ev.state.weight)
    }

    @Test fun inSessionHints() {
        val p = Progression.prescribe(squat, spec, SlotState(60.0, 8))
        val lighter = Progression.adjustAfterSet(squat, p, DoneSet(60.0, 4))
        assertNotNull(lighter)
        assertTrue(!lighter.heavier && lighter.weight < 60.0)
        val heavier = Progression.adjustAfterSet(squat, p, DoneSet(60.0, 13))
        assertNotNull(heavier)
        assertTrue(heavier.heavier && heavier.weight <= 69.0)
        assertNull(Progression.adjustAfterSet(squat, p, DoneSet(60.0, 9)))
    }

    @Test fun pullUpsAddWeightOnTopOfBodyWeight() {
        val pull = Catalog["pullup"]
        val s = SlotSpec("pullup", 3, 5, 10, 2, 120, SlotRole.MAIN)
        val st = SlotState(0.0, 10)
        val p = Progression.prescribe(pull, s, st, bodyWeight = 80.0)
        val ev = Progression.evaluate(pull, s, st, p, List(3) { DoneSet(0.0, 10) }, bodyWeight = 80.0)
        val out = assertIs<Outcome.AddWeight>(ev.outcome)
        assertEquals(2.5, out.to)
        assertTrue(out.reps in 5..9)
    }

    @Test fun e1rmSmoothingFavoursGains() {
        assertEquals(107.0, Progression.smoothE1rm(100.0, 110.0), 1e-9)
        assertEquals(96.5, Progression.smoothE1rm(100.0, 90.0), 1e-9)
        assertEquals(90.0, Progression.smoothE1rm(null, 90.0))
    }

    @Test fun startHintRespectsBarAndStep() {
        assertEquals(37.5, Progression.startHint(squat, 75.0, female = false, trained = 1.0))
        assertEquals(20.0, Progression.startHint(Catalog["ohp"], 50.0, female = true, trained = 1.0))
    }
}
