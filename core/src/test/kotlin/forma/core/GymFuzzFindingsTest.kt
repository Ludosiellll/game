package forma.core

import forma.core.model.Catalog
import forma.core.strength.DoneSet
import forma.core.strength.Phase
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the gym rules on random cases (forma.core.fuzz.GymRulesTest) found, each as the smallest case it was. */
class GymFuzzFindingsTest {
    @Test fun aFirstTimeInALighterWeekFindsTheWeight() {
        // A new exercise whose first session falls in the lighter week: its sets were the search for the weight.
        val ex = Catalog["goblet_squat"]
        val spec = SlotSpec(ex.id, 3, 4, 11, 3, 90, SlotRole.ACCESSORY)
        val fresh = SlotState.fresh(spec)
        val p = Progression.prescribe(ex, spec, fresh, Phase.DELOAD, bodyWeight = 108.5)
        assertTrue(p.calibrate)
        val e = Progression.evaluate(ex, spec, fresh, p, listOf(DoneSet(30.0, 4, 1)), 108.5)
        assertNotNull(e.state.weight, "${e.outcome}")
    }

    @Test fun backAfterABreakAShakySessionDoesNotJumpBack() {
        // 55 before two months off: 45 the first time back; one set of five done — held, at 45, not 55 next time.
        val ex = Catalog["pushdown"]
        val spec = SlotSpec(ex.id, 5, 15, 22, 0, 90, SlotRole.SECONDARY)
        val state = SlotState(55.0, 19, sessions = 6)
        val p = Progression.prescribe(ex, spec, state, bodyWeight = 80.0, daysSinceLast = 70)
        assertEquals(45.0, p.weight)
        val e = Progression.evaluate(ex, spec, state, p, listOf(DoneSet(45.0, 19)), 80.0)
        assertEquals(45.0, e.state.weight, "${e.outcome}")
    }

    @Test fun fivePlusInReserveNeverGivesLessThanFour() {
        val ex = Catalog["ohp"]
        val spec = SlotSpec(ex.id, 4, 6, 10, 0, 180, SlotRole.MAIN)
        val state = SlotState(20.0, 11, stalls = 3, sessions = 2, steady = 1)
        val p = Progression.prescribe(ex, spec, state, bodyWeight = 53.8)
        fun next(rir: Int) = Progression.evaluate(ex, spec, state, p, listOf(DoneSet(20.0, 11), DoneSet(20.0, 11), DoneSet(20.0, 11, rir), DoneSet(20.0, 11)), 53.8).state
        val four = next(4)
        val five = next(5)
        assertTrue(five.weight!! > four.weight!! || five.weight == four.weight && five.reps >= four.reps, "«ещё 4»: ${four.weight}×${four.reps}, «ещё 5+»: ${five.weight}×${five.reps}")
    }

    @Test fun noHeavierHintAfterASetToFailureBelowThePlan() {
        // 16 × 11 planned, 12 × 11 done with nothing left: not «16» next.
        val ex = Catalog["arnold_press"]
        val spec = SlotSpec(ex.id, 1, 5, 9, 3, 90, SlotRole.ACCESSORY)
        val p = Progression.prescribe(ex, spec, SlotState(16.0, 11, sessions = 35), bodyWeight = 56.8)
        val adj = Progression.adjustAfterSet(ex, p, DoneSet(12.0, 11, 0), 56.8, 0)
        assertTrue(adj == null || !adj.heavier, "$adj")
    }

    @Test fun aSetFarHeavierThanThePlanDoesNotBreakTheHint() {
        // 60 × 8 planned, 90 × 8 done to failure (a typo, or the lifter knew better): a hint, not a crash.
        val squat = Catalog["squat"]
        val spec = SlotSpec("squat", 3, 5, 8, 2, 180, SlotRole.MAIN)
        val p = Progression.prescribe(squat, spec, SlotState(60.0, 8))
        val adj = Progression.adjustAfterSet(squat, p, DoneSet(90.0, 8, 0), 80.0, 0)
        assertTrue(adj == null || !adj.heavier, "$adj")
    }

    @Test fun aTestDayFirstTimeFindsTheWeight() {
        val ex = Catalog["incline_pushup"]
        val spec = SlotSpec(ex.id, 1, 4, 8, 2, 90, SlotRole.ACCESSORY)
        val fresh = SlotState(null, 7)
        val p = Progression.prescribe(ex, spec, fresh, Phase.TEST, bodyWeight = 63.1)
        val e = Progression.evaluate(ex, spec, fresh, p, listOf(DoneSet(5.0, 7)), 63.1)
        assertNotNull(e.state.weight, "${e.outcome}")
    }
}
