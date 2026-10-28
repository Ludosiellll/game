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

    @Test fun aReductionAsksNoMoreRepsThanTheEstimateGives() {
        // Glute-ham raises with 42.5 kg, 11 asked, 6 done: not 32.5 × 11 where 9.5 are there to failure.
        val ex = Catalog["glute_ham_raise"]
        val spec = SlotSpec(ex.id, 1, 8, 9, 0, 120, SlotRole.SECONDARY)
        val st = SlotState(42.5, 11, stalls = 1, sessions = 10)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 109.3)
        val e = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(42.5, 6)), 109.3)
        val there = OneRm.rtfAt(e.e1rm!!, Progression.effectiveLoad(ex, e.state.weight!!, 109.3))
        assertTrue(e.state.reps <= there + 1, "${e.outcome}, ${"%.1f".format(there)} there")
    }

    @Test fun backAtTheWeightCutFromWithMoreRepsIsNoSecondReset() {
        // Knee push-ups with 42.5 kg, cut from 45: 42.5 × 6 and × 3 on the third stall held 42.5; the same sets with 45 —
        // the weight cut from — reset to 35.
        val ex = Catalog["knee_pushup"]
        val spec = SlotSpec(ex.id, 4, 8, 10, 2, 90, SlotRole.MAIN)
        val st = SlotState(42.5, 6, stalls = 3, misses = 1, sessions = 39, linear = true, steady = 2, cutFrom = 45.0)
        val p = Progression.prescribe(ex, spec, st, Phase.NORMAL, rirOffset = 1, bodyWeight = 72.2, topRir = 2)
        fun next(w: Double) = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(w, 6), DoneSet(w, 3)), 72.2)
        val at = next(42.5)
        val heavier = next(45.0)
        assertTrue(atLeast(at.state, heavier.state), "${at.outcome} / ${heavier.outcome}")
    }

    @Test fun oneHeavySetDoesNotJumpTheRepsByMoreThanThree() {
        // Reverse flies 5 × 4 planned, one set of 8 × 4 after a hint among them: not 6 × 10 next.
        val ex = Catalog["reverse_fly"]
        val spec = SlotSpec(ex.id, 5, 6, 11, 3, 60, SlotRole.SECONDARY)
        val st = SlotState(5.0, 4, stalls = 1, misses = 2, sessions = 31, linear = true)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 86.4, topRir = 1)
        val e = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(5.0, 4), DoneSet(5.0, 4), DoneSet(8.0, 4), DoneSet(5.0, 4), DoneSet(6.0, 4, 0)), 86.4)
        assertTrue(e.state.reps <= 4 + 3, "${e.outcome}")
    }

    @Test fun afterACutTheRepsAreMetAtTheWeakestSet() {
        // Hip thrusts cut from 45 (4 reps there), 35 × 9: 9 then 4 kept × 9, 9 then 5 lowered it to × 5.
        val ex = Catalog["machine_hip_thrust"]
        val spec = SlotSpec(ex.id, 2, 5, 10, 3, 90, SlotRole.ACCESSORY)
        val st = SlotState(35.0, 9, stalls = 2, sessions = 34, linear = true, steady = 3, cutFrom = 45.0, cutReps = 4)
        val p = Progression.prescribe(ex, spec, st, rirOffset = 1, bodyWeight = 90.4, topRir = 1)
        fun next(second: Int) = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(35.0, 9), DoneSet(35.0, second)), 90.4)
        val four = next(4)
        val five = next(5)
        assertTrue(five.state.reps >= four.state.reps - 1, "${four.outcome} / ${five.outcome}")
    }

    @Test fun setsDoneHeavierThanThePlanAreNoStall() {
        // Diamond push-ups +10 × 8 planned on the third stall: 10, 10, 12.5 × 8, 8, 7 added a rep; the same a step heavier
        // (12.5, 12.5, 15) reset to +2.5.
        val ex = Catalog["diamond_pushup"]
        val spec = SlotSpec(ex.id, 3, 10, 10, 3, 60, SlotRole.ACCESSORY)
        val st = SlotState(10.0, 8, stalls = 3, misses = 2, sessions = 28, steady = 1)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 104.4, topRir = 1)
        fun next(a: Double, b: Double) = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(a, 8), DoneSet(a, 8), DoneSet(b, 7)), 104.4)
        val at = next(10.0, 12.5)
        val heavier = next(12.5, 15.0)
        assertTrue(atLeast(at.state, heavier.state), "${at.outcome} / ${heavier.outcome}")
    }

    @Test fun aBeginnersDoubleStepOnlyWhereTheRepsAreThere() {
        // Smith squats 50 × 8 with 2 left: not 55 × 8, 6.9 there to failure.
        val ex = Catalog["smith_squat"]
        val spec = SlotSpec(ex.id, 1, 6, 7, 0, 120, SlotRole.SECONDARY)
        val st = SlotState(50.0, 8, stalls = 1, misses = 2, sessions = 2, linear = true, steady = 1)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 54.3, novice = true)
        val e = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(50.0, 8, 2)), 54.3, novice = true)
        val there = OneRm.rtfAt(e.e1rm!!, Progression.effectiveLoad(ex, e.state.weight!!, 54.3))
        assertTrue(e.state.reps <= there + 1, "${e.outcome}, ${"%.1f".format(there)} there")
    }

    @Test fun moreHelpAgainAndAgainLowersTheRepsWhereHelpCannotGrow() {
        // Dips with 7.5 kg of help × 17 planned, done with 15 — again: not 7.5 × 17 held, 14.7 there to failure.
        val ex = Catalog["dip"]
        val spec = SlotSpec(ex.id, 1, 15, 16, 0, 120, SlotRole.SECONDARY)
        val st = SlotState(-7.5, 17, stalls = 3, misses = 2, sessions = 17, steady = 1, cutFrom = 5.0, cutReps = 3)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 109.7, topRir = 1)
        val e = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(-15.0, 17)), 109.7)
        val there = OneRm.rtfAt(e.e1rm!!, Progression.effectiveLoad(ex, e.state.weight!!, 109.7))
        assertTrue(e.state.reps <= there + 1, "${e.outcome}, ${"%.1f".format(there)} there")
    }

    @Test fun fewerHoldsHeldInFullAreNoMiss() {
        // Two side planks of 25 s planned, one done and held (27 s), a miss the time before: the target stayed 25 for a
        // lift done this way (FEWER_SETS); the hold was read as a second miss and the target cut to 20 s.
        val ex = Catalog["side_plank"]
        val spec = SlotSpec(ex.id, 2, 20, 30, 0, 60, SlotRole.ACCESSORY)
        val st = SlotState(null, 25, stalls = 2, misses = 1, sessions = 22, linear = false)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 111.2)
        val e = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(0.0, 27)), 111.2)
        assertTrue(e.state.reps >= 25, "${e.outcome}")
    }

    @Test fun theFloorGivesWayToWhoKeepsFailing() {
        // Pec deck 25 × 15 (15–15, one in reserve), the weeks' best at 55 holding 25 as the floor: 12, 12, 12 to failure
        // held the weight (one bad day), 11, 11, 11 again — and 10, 10, 10 again, for as long as the weeks' best stood.
        val ex = Catalog["pec_deck"]
        val spec = SlotSpec(ex.id, 3, 15, 15, 1, 60, SlotRole.ACCESSORY)
        var st = SlotState(25.0, 15, sessions = 12, linear = false)
        val trail = mutableListOf<Outcome>()
        for (reps in listOf(12, 11, 10, 10)) {
            val p = Progression.prescribe(ex, spec, st, bodyWeight = 55.2)
            if (p.weight!! < 25.0) break
            val e = Progression.evaluate(ex, spec, st, p, List(3) { DoneSet(25.0, reps, 0) }, 55.2, recentBest = 55.0)
            trail += e.outcome
            st = e.state
        }
        assertTrue(st.weight!! < 25.0 || st.reps < 15, "four sessions short to failure, and still 25 × 15: $trail")
    }

    @Test fun aCutThatWorksIsNotCutAgainButAFourthStallIs() {
        // Pec deck cut from 30 (11 reps there) to 25 × 15: 14 to failure on the third stall is held (the reps came up
        // since the cut); held for ever, with the cut remembered until a session succeeds — the fourth stall resets.
        val ex = Catalog["pec_deck"]
        val spec = SlotSpec(ex.id, 1, 15, 22, 1, 90, SlotRole.SECONDARY)
        var st = SlotState(25.0, 15, stalls = 2, sessions = 16, linear = false, cutFrom = 30.0, cutReps = 11)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 58.9)
        val third = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(25.0, 14, 0)), 58.9, recentBest = 57.1)
        assertEquals(Outcome.Hold(25.0, 15, forma.core.strength.HoldReason.NEAR_MISS), third.outcome)
        st = third.state
        val fourth = Progression.evaluate(ex, spec, st, Progression.prescribe(ex, spec, st, bodyWeight = 58.9), listOf(DoneSet(25.0, 14, 0)), 58.9, recentBest = 57.1)
        assertTrue(fourth.state.weight!! < 25.0, "${fourth.outcome}")
    }

    @Test fun aSetToFailureShortOfTheTargetIsNoStopByChoice() {
        // Romanian deadlift 62.5 × 3 (nothing in reserve planned): 3 «ещё 1», 3 «ещё 0», then the «+» set 2 «ещё 0» — to
        // failure, one short. Read as STOPPED_EARLY (two sets of three "had the reps in reserve": the two that reached
        // the target), session after session, with no stall counted and so no reset ever: a near miss, as it is.
        val ex = Catalog["rdl"]
        val spec = SlotSpec(ex.id, 3, 3, 8, 0, 180, SlotRole.MAIN)
        val st = SlotState(62.5, 3, stalls = 2, sessions = 8, linear = false)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 50.6)
        val e = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(62.5, 3, 1), DoneSet(62.5, 3, 0), DoneSet(62.5, 2, 0)), 50.6)
        assertTrue((e.outcome as? Outcome.Hold)?.reason != forma.core.strength.HoldReason.STOPPED_EARLY, "${e.outcome}")
        // The third stall in a row: the reset, as after any near miss.
        assertTrue(e.state.weight!! < 62.5, "${e.outcome}")
    }

    @Test fun notTheWeightHoldingTheTargetIsSaidTwiceAtMost() {
        // Reverse pec deck reset from 22.5 (4 reps there) to 20 × 4, the lifter getting weaker: 4, 4, 4, 3 is «not the
        // weight» (the tired last set did no more than the first one at the cut) — said again on 4, 4, 3, 2 and on
        // 4, 3, 2, 1, every time with the stalls and misses wiped, and the weight stood for ever.
        val ex = Catalog["reverse_pec_deck"]
        val spec = SlotSpec(ex.id, 4, 4, 8, 1, 60, SlotRole.ACCESSORY)
        var st = SlotState(20.0, 4, sessions = 23, linear = false, cutFrom = 22.5, cutReps = 4)
        val trail = mutableListOf<Outcome>()
        for (sets in listOf(listOf(4, 4, 4, 3), listOf(4, 4, 3, 2), listOf(4, 3, 2, 1), listOf(4, 3, 2, 1))) {
            val p = Progression.prescribe(ex, spec, st, bodyWeight = 80.8)
            if (p.weight!! < 20.0) break
            val e = Progression.evaluate(ex, spec, st, p, sets.map { DoneSet(20.0, it, 0) }, 80.8, recentBest = 36.1)
            trail += e.outcome
            st = e.state
        }
        assertEquals(forma.core.strength.HoldReason.NOT_THE_WEIGHT, (trail.first() as? Outcome.Hold)?.reason, "$trail")
        assertTrue(trail.count { (it as? Outcome.Hold)?.reason == forma.core.strength.HoldReason.NOT_THE_WEIGHT } <= 2, "$trail")
        assertTrue(st.weight!! < 20.0, "four sessions short to failure after a cut, and still 20 × 4: $trail")
    }

    @Test fun aRepMorePastTwelveNeverLowersTheEstimate() {
        // Barbell curls 22.5 × 16 asked, two in reserve: 17 done, not rated, is the same reserve a rep further — but
        // counted as 12 reps with the reserve a rep less (a set of 11 beside it brings the cap in), it estimated lower
        // than 16 done, and asked 22.5 × 10 next where 16 done asked × 12. Reps past twelve count as reserve (four at
        // most), not thrown away.
        val ex = Catalog["bb_curl"]
        val sixteen = Progression.e1rmOf(ex, listOf(DoneSet(22.5, 16), DoneSet(20.0, 11)), 16, 2, 78.0)!!
        val seventeen = Progression.e1rmOf(ex, listOf(DoneSet(22.5, 17), DoneSet(20.0, 11)), 16, 2, 78.0)!!
        assertTrue(seventeen >= sixteen - 1e-9, "16 reps: ${"%.1f".format(sixteen)}, 17 reps: ${"%.1f".format(seventeen)}")
        // And a set to failure past twelve is more than twelve to failure.
        val twelve = Progression.e1rmOf(ex, listOf(DoneSet(22.5, 12, 0), DoneSet(20.0, 11)), 12, 2, 78.0)!!
        val thirteen = Progression.e1rmOf(ex, listOf(DoneSet(22.5, 13, 0), DoneSet(20.0, 11)), 12, 2, 78.0)!!
        assertTrue(thirteen > twelve, "12 to failure: ${"%.1f".format(twelve)}, 13: ${"%.1f".format(thirteen)}")
    }

    @Test fun aLighterSetBeforeThePlanDoneWithReserveIsNoStall() {
        // Straight-arm pulldown 57.5 × 4 of four sets on the third stall: 55 × 4 (lighter), then 57.5 × 3 «ещё 3» is a
        // stop by choice and held; 57.5 × 4 «ещё 3» — the plan met, with more reserve than asked — read as a near miss
        // (the lighter set a rep short of the plan) and, the fourth stall, reset to 50.
        val ex = Catalog["straight_arm_pulldown"]
        val spec = SlotSpec(ex.id, 4, 4, 9, 2, 90, SlotRole.ACCESSORY)
        val st = SlotState(57.5, 4, stalls = 3, sessions = 6, linear = true, steady = 3)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 105.5)
        fun next(reps: Int) = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(55.0, 4), DoneSet(57.5, reps, 3)), 105.5)
        val three = next(3)
        val four = next(4)
        assertTrue(atLeast(three.state, four.state), "${three.outcome} / ${four.outcome}")
    }

    @Test fun aHeavierPlusSetJustUnderTheTargetAtTheWeightIsNoMiss() {
        // Trap bar 140 × 15 (15–19) on the third stall: 140 × 15, 140 × 15, then the «+» set 145 × 14 reads as 15 at
        // 140 and the reps go up; the same sets a step heavier — 142.5, 142.5, 147.5 × 14 — read 14.9 at 142.5, the
        // step below made it 14, a miss, and the reset took it to 127.5.
        val ex = Catalog["trap_bar_deadlift"]
        val spec = SlotSpec(ex.id, 3, 15, 19, 2, 180, SlotRole.MAIN)
        val st = SlotState(140.0, 15, stalls = 3, misses = 2, sessions = 17, linear = false, steady = 3)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 110.3)
        fun next(w: Double) = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(w, 15), DoneSet(w, 15), DoneSet(w + 5, 14)), 110.3)
        val at = next(140.0)
        val heavier = next(142.5)
        assertTrue(atLeast(at.state, heavier.state), "${at.outcome} / ${heavier.outcome}")
    }

    @Test fun anUnratedSetAtTheTargetVotesAsDoneAsAsked() {
        // Bench dips with 20 kg × 11 (12–16, two in reserve) on the third stall, three of five sets: 20 × 10 «ещё 5»,
        // 12.5 × 11 (lighter), 20 × 11 — a stop by choice, held. A rep more on the first set, 20 × 11 «ещё 5»: the set
        // not rated at the target had no vote, the lighter set's rep short made a near miss, and the reset went to 12.5.
        val ex = Catalog["bench_dip"]
        val spec = SlotSpec(ex.id, 5, 12, 16, 2, 90, SlotRole.ACCESSORY)
        val st = SlotState(20.0, 11, stalls = 2, misses = 1, sessions = 38, linear = true, steady = 1)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 98.7, topRir = 2)
        fun next(first: Int) = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(20.0, first, 5), DoneSet(12.5, 11), DoneSet(20.0, 11)), 98.7)
        val ten = next(10)
        val eleven = next(11)
        assertTrue(atLeast(ten.state, eleven.state), "${ten.outcome} / ${eleven.outcome}")
    }

    @Test fun aHeavierSetJustUnderTheRangeBottomDoesNotKeepTheWeight() {
        // Romanian deadlift 60 × 13 (12–12, three in reserve), two sets: 62.5 × 10 (heavier, to failure — 10.9 at 60),
        // then 47.5 × 13. Read as 11 at 60, a rep short of the range's bottom «in reach», the plan went to 60 × 12 where
        // the estimate gave 10.9 to failure; the weight comes down instead.
        val ex = Catalog["rdl"]
        val spec = SlotSpec(ex.id, 2, 12, 12, 3, 150, SlotRole.SECONDARY)
        val st = SlotState(60.0, 13, sessions = 7, linear = false)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 115.0)
        val e = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(62.5, 10), DoneSet(47.5, 13)), 115.0)
        val there = OneRm.rtfAt(e.e1rm!!, Progression.effectiveLoad(ex, e.state.weight!!, 115.0))
        assertTrue(e.state.reps <= there + 1, "${e.outcome}, ${"%.1f".format(there)} there")
    }

    @Test fun aSetWithReserveAgainstAHeavierSetToFailureIsNoStall() {
        // Belt squat 135 × 12 (15–20, two in reserve this week) on the third stall, three of four sets: 135 × 11 «ещё 5»,
        // 130 × 12 (lighter), 150 × 9 (heavier, to failure) — a stop by choice, held. A rep more, 135 × 12 «ещё 5»: with
        // the set with reserve one against one, the lighter set's rep short made a near miss and the reset went to 120.
        val ex = Catalog["belt_squat"]
        val spec = SlotSpec(ex.id, 4, 15, 20, 1, 150, SlotRole.MAIN)
        val st = SlotState(145.0, 12, stalls = 3, misses = 2, sessions = 21, linear = false, steady = 3)
        val p = Progression.prescribe(ex, spec, st, rirOffset = 1, bodyWeight = 105.0, daysSinceLast = 20)
        assertEquals(135.0, p.weight)
        fun next(first: Int) = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(135.0, first, 5), DoneSet(130.0, 12), DoneSet(150.0, 9)), 105.0)
        val eleven = next(11)
        val twelve = next(12)
        assertTrue(atLeast(eleven.state, twelve.state), "${eleven.outcome} / ${twelve.outcome}")
    }

    @Test fun aPlusSetMadeWithReserveKeepsItsSay() {
        // Pull-through 5 × 12 (5–12, nothing in reserve), five sets: 9 «ещё 5», 12, 11, 12, then the «+» set 11 «ещё 3» —
        // a stop by choice, held. The «+» set a rep better, 12 «ещё 3»: no longer short, it had no say, the unrated 11
        // outvoted the 9 «ещё 5» one to one, and the target came down to 9.
        val ex = Catalog["pull_through"]
        val spec = SlotSpec(ex.id, 5, 5, 12, 0, 90, SlotRole.MAIN)
        val st = SlotState(5.0, 12, sessions = 39, linear = false, steady = 1)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 52.0)
        fun next(top: Int) = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(5.0, 9, 5), DoneSet(5.0, 12), DoneSet(5.0, 11), DoneSet(5.0, 12), DoneSet(5.0, top, 3)), 52.0)
        val eleven = next(11)
        val twelve = next(12)
        assertTrue(atLeast(eleven.state, twelve.state), "${eleven.outcome} / ${twelve.outcome}")
    }

    @Test fun aNewRepRangeMadeInFullKeepsItsWeight() {
        // Rows 6–8, the first time in that range (planned 50 × 6 from the estimate of the 4–6 rows): 50 × 6 with three left
        // and 52.5 × 6 to failure — not 47.5 × 6 next (found by a whole life, person 587431).
        val ex = Catalog["row"]
        val spec = SlotSpec(ex.id, 2, 6, 8, 2, 120, SlotRole.SECONDARY)
        val fresh = SlotState.fresh(spec)
        val p = Progression.prescribe(ex, spec, fresh, e1rm = 63.0, bodyWeight = 80.0, rirOffset = -1)
        assertEquals(50.0, p.weight)
        val e = Progression.evaluate(ex, spec, fresh, p, listOf(DoneSet(50.0, 6, 3), DoneSet(52.5, 6, 0)), 80.0)
        assertTrue(e.state.weight!! >= 50.0 && e.state.reps >= 6, "${e.outcome}")
    }

    @Test fun aFirstTimeInTheTaperKeepsAWeight() {
        // Leg curls first done in the goal's taper (30 × 5 from the estimate): held, the sets left no weight behind.
        val ex = Catalog["leg_curl"]
        val spec = SlotSpec(ex.id, 1, 1, 3, 1, 90, SlotRole.ACCESSORY)
        val fresh = SlotState(null, 5)
        val p = Progression.prescribe(ex, spec, fresh, Phase.TAPER, e1rm = 40.0, bodyWeight = 74.1)
        assertNotNull(p.weight)
        val e = Progression.evaluate(ex, spec, fresh, p, listOf(DoneSet(p.weight!!, 5)), 74.1)
        assertNotNull(e.state.weight, "${e.outcome}")
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
