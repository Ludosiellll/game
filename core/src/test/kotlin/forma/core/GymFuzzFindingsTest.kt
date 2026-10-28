package forma.core

import forma.core.model.Catalog
import forma.core.strength.DoneSet
import forma.core.strength.OneRm
import forma.core.strength.Outcome
import forma.core.strength.Phase
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import kotlin.math.abs
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

    @Test fun twoRepsShortOfTheRangeBottomTheWeightComesDown() {
        // 40 × 18 planned (range 15–16), 13 done: not «40 × 15» — two reps more than there were.
        val ex = Catalog["incline_pushup"]
        val spec = SlotSpec(ex.id, 1, 15, 16, 1, 90, SlotRole.ACCESSORY)
        val state = SlotState(40.0, 18, misses = 1, sessions = 25, linear = true, steady = 2, cutFrom = 52.5, cutReps = 9)
        val p = Progression.prescribe(ex, spec, state, bodyWeight = 75.9)
        assertEquals(40.0, p.weight)
        val e = Progression.evaluate(ex, spec, state, p, listOf(DoneSet(40.0, 13)), 75.9)
        assertTrue(e.state.weight!! < 40.0 || e.state.reps <= 14, "${e.outcome}")
    }

    @Test fun oneRepShortOfTheRangeBottomKeepsTheWeight() {
        val squat = Catalog["squat"]
        val spec = SlotSpec("squat", 3, 5, 8, 2, 150, SlotRole.MAIN)
        val state = SlotState(60.0, 7)
        val p = Progression.prescribe(squat, spec, state)
        val e = Progression.evaluate(squat, spec, state, p, listOf(DoneSet(60.0, 5), DoneSet(60.0, 5), DoneSet(60.0, 4)), 80.0)
        assertEquals(Outcome.LowerReps(60.0, 7, 5), e.outcome)
    }

    @Test fun aCoarseStepAtTheTopAsksTheRepsTheEstimateGives() {
        // 2.5 kg × 18 four times, the last with 2 left, at the top of 10–14 with room to spare: the next step doubles the
        // load. Not «5 × 9» (the bottom of the range less one) where the estimate gives about one rep.
        val ex = Catalog["face_pull"]
        val spec = SlotSpec(ex.id, 4, 10, 14, 2, 90, SlotRole.ACCESSORY)
        val state = SlotState(2.5, 18, sessions = 23, steady = 3, cutFrom = 10.0)
        val p = Progression.prescribe(ex, spec, state, bodyWeight = 49.6)
        val e = Progression.evaluate(ex, spec, state, p, List(3) { DoneSet(2.5, 18) } + DoneSet(2.5, 18, 2), 49.6)
        val next = e.state
        if (next.weight!! > 2.5) {
            val there = forma.core.strength.OneRm.rtfAt(e.e1rm!!, Progression.effectiveLoad(ex, next.weight!!, 49.6))
            assertTrue(next.reps <= there + 1, "${e.outcome}, ${"%.1f".format(there)} there")
        }
    }

    @Test fun moreInReserveOnAHeavierSetNeverCutsThePlan() {
        // 52.5 × 10 planned, 62.5 × 5 done after a hint, then 52.5 × 10: «ещё 2» on the heavy set read as 52.5 × 9, a miss
        // on the third stall — the plan cut to 45, where «ещё 1» held it at 52.5.
        val ex = Catalog["ohp"]
        val spec = SlotSpec("ohp", 3, 12, 12, 0, 120, SlotRole.MAIN)
        val st = SlotState(52.5, 10, stalls = 2, sessions = 38, linear = true)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 54.8, topRir = 1)
        fun next(rir: Int) = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(62.5, 5, rir), DoneSet(52.5, 10)), 54.8).state
        for (rir in 1..3) {
            val a = next(rir)
            val b = next(rir + 1)
            assertTrue(b.weight!! > a.weight!! || b.weight == a.weight && b.reps >= a.reps, "«ещё $rir»: ${a.weight}×${a.reps}, «ещё ${rir + 1}»: ${b.weight}×${b.reps}")
        }
    }

    @Test fun aSetDoneHeavierNeverEndsLighterThanTheSameSetAtThePlan() {
        // 122.5 × 8 planned, one set of 3 on the third stall, the weeks' best holding the weight: 122.5 × 3 kept × 8 next,
        // 125 × 3 reset to 122.5 × 6 — the floor left a 2 % cut, and the range's bottom with it.
        val ex = Catalog["sumo_deadlift"]
        val spec = SlotSpec(ex.id, 3, 6, 9, 2, 180, SlotRole.SECONDARY)
        val st = SlotState(122.5, 8, stalls = 2, misses = 1, sessions = 9, steady = 2)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 69.0, topRir = 1)
        assertEquals(122.5, p.weight)
        fun next(w: Double) = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(w, 3)), 69.0, recentBest = 196.2)
        val at = next(122.5)
        val heavier = next(125.0)
        val a = at.state
        val b = heavier.state
        assertTrue(b.weight!! > a.weight!! || b.weight == a.weight && b.reps >= a.reps, "${at.outcome} / ${heavier.outcome}")
    }

    @Test fun aSetGivenUpDoesNotSetTheRepTarget() {
        // Cable flies, 2.5 kg after a cut from 12.5: 6 then 2 kept × 6 (the reps did not come up), 7 then 2 went to × 2.
        val ex = Catalog["cable_fly"]
        val spec = SlotSpec(ex.id, 5, 1, 8, 0, 90, SlotRole.SECONDARY)
        val st = SlotState(2.5, 6, stalls = 2, sessions = 20, steady = 1, cutFrom = 12.5, cutReps = 6)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 104.6, topRir = 1)
        fun next(first: Int) = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(2.5, first), DoneSet(2.5, 2)), 104.6)
        val six = next(6)
        val seven = next(7)
        assertTrue(seven.state.reps >= six.state.reps - 1, "${six.outcome} / ${seven.outcome}")
    }

    @Test fun noJumpWhereNotEvenAStepIsThere() {
        // Knee raises with the body (88.8 kg), 7 done with 2 left: not «+2.5 kg × 7» where the estimate gives 4.8.
        val ex = Catalog["knee_raise"]
        val spec = SlotSpec(ex.id, 1, 4, 6, 0, 60, SlotRole.ACCESSORY)
        val st = SlotState(0.0, 7, stalls = 1, misses = 1, sessions = 3)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 88.8)
        val e = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(0.0, 7, 2)), 88.8)
        val there = OneRm.rtfAt(e.e1rm!!, Progression.effectiveLoad(ex, e.state.weight!!, 88.8))
        assertTrue(e.state.reps <= there + 1, "${e.outcome}, ${"%.1f".format(there)} there")
    }

    private fun atLeast(a: SlotState, b: SlotState) = b.weight!! > a.weight!! + 1e-9 || abs(b.weight!! - a.weight!!) < 1e-9 && b.reps >= a.reps

    @Test fun aCutThatWorksIsNotCutAgain() {
        // Leg press cut from 205 (9 reps there), 185 × 7 planned: 190 × 9 «ещё 2» then 185 × 3 held 185 (the weight is not
        // what limits); «ещё 3» — the reps come up since the cut — reset it to 165 × 5.
        val ex = Catalog["leg_press"]
        val spec = SlotSpec(ex.id, 5, 5, 7, 2, 120, SlotRole.ACCESSORY)
        val st = SlotState(185.0, 7, stalls = 2, misses = 1, sessions = 22, linear = true, steady = 1, cutFrom = 205.0, cutReps = 9)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 68.7, topRir = 2)
        fun next(rir: Int) = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(190.0, 9, rir), DoneSet(185.0, 3)), 68.7)
        val two = next(2)
        val three = next(3)
        assertTrue(atLeast(two.state, three.state), "${two.outcome} / ${three.outcome}")
    }

    @Test fun aMissTheFloorHoldsUpLowersTheRepsAsAnyMiss() {
        // Split squats 28 × 12 planned, the weeks' best holding the weight: 30 × 7 «ещё 2» then 28 × 12 kept 28 × 12; «ещё
        // 3» on the first set — in reach of the range's bottom — lowered the reps to 10.
        val ex = Catalog["split_squat"]
        val spec = SlotSpec(ex.id, 4, 10, 12, 2, 90, SlotRole.ACCESSORY)
        val st = SlotState(28.0, 12, sessions = 36, linear = true)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 110.8, topRir = 1)
        fun next(rir: Int) = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(30.0, 7, rir), DoneSet(28.0, 12)), 110.8, recentBest = 53.3)
        val two = next(2)
        val three = next(3)
        assertTrue(atLeast(two.state, three.state), "${two.outcome} / ${three.outcome}")
    }

    @Test fun aHeavierSetOnTheThirdStallNeverEndsLighter() {
        // Trap bar 125 × 7 planned, one set of 2 on the third stall, the weeks' best at 181: 125 × 2 and 127.5 × 2.
        val ex = Catalog["trap_bar_deadlift"]
        val spec = SlotSpec(ex.id, 1, 5, 10, 1, 180, SlotRole.MAIN)
        val st = SlotState(125.0, 7, stalls = 2, misses = 1, sessions = 23, steady = 3)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 102.5, topRir = 1)
        fun next(w: Double) = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(w, 2)), 102.5, recentBest = 181.2)
        val at = next(125.0)
        val heavier = next(127.5)
        assertTrue(atLeast(at.state, heavier.state), "${at.outcome} / ${heavier.outcome}")
    }

    @Test fun aResetAsksNoMoreRepsThanTheTargetNorTheEstimate() {
        // Calf raises 75 × 9 (below the range 12–19), 6 done on the third stall: not 65 × 12.
        val ex = Catalog["calf_raise"]
        val spec = SlotSpec(ex.id, 2, 12, 19, 3, 60, SlotRole.SECONDARY)
        val st = SlotState(75.0, 9, stalls = 2, misses = 2, sessions = 36, steady = 2)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 63.2, topRir = 1)
        val e = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(75.0, 6)), 63.2)
        val there = OneRm.rtfAt(e.e1rm!!, Progression.effectiveLoad(ex, e.state.weight!!, 63.2))
        assertTrue(e.state.reps <= 9 && e.state.reps <= there + 1, "${e.outcome}, ${"%.1f".format(there)} there")
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
