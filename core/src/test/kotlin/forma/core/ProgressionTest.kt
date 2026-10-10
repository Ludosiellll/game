package forma.core

import forma.core.model.Catalog
import forma.core.strength.DoneSet
import forma.core.strength.HoldReason
import forma.core.strength.OneRm
import forma.core.strength.Outcome
import forma.core.strength.Phase
import forma.core.strength.Prescription
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every rule of the strength progression, one case at a time. Bigger behaviour is covered by the app simulations. */
class ProgressionTest {
    private val squat = Catalog["squat"]
    private val bench = Catalog["bench"]
    private val spec = SlotSpec("squat", sets = 3, repMin = 5, repMax = 8, rir = 2, restSec = 150, role = SlotRole.MAIN)
    private val benchSpec = spec.copy(exerciseId = "bench")

    private fun sets(weight: Double, vararg reps: Int) = reps.map { DoneSet(weight, it) }

    private fun eval(
        st: SlotState,
        done: List<DoneSet>,
        s: SlotSpec = spec,
        ex: forma.core.model.Exercise = squat,
        novice: Boolean = false,
        recentBest: Double? = null,
        p: Prescription = Progression.prescribe(ex, s, st),
    ) = Progression.evaluate(ex, s, st, p, done, 80.0, novice, recentBest)

    // ---------- finding the working weight ----------

    @Test fun calibrationClimbsFromWhatEachSetShowed() {
        val fresh = SlotState.fresh(spec)
        val p = Progression.prescribe(squat, spec, fresh, hint = 40.0)
        assertTrue(p.calibrate)
        assertEquals(40.0, p.startHint)
        assertEquals(2, p.rir)
        assertFalse(p.top)
        // 40 × 15 with the instructed 2 in reserve is far too light: next set +30 % at most.
        assertEquals(50.0, assertNotNull(Progression.adjustAfterSet(squat, p, DoneSet(40.0, 15))).weight)
        assertEquals(55.0, assertNotNull(Progression.adjustAfterSet(squat, p, DoneSet(50.0, 9), index = 1)).weight)
        // The heaviest set (fewest reps to failure) decides, not the most optimistic estimate.
        val ev = eval(fresh, listOf(DoneSet(40.0, 15), DoneSet(50.0, 9), DoneSet(55.0, 6)), p = p)
        assertEquals(Outcome.Calibrated(55.0, 5), ev.outcome)
    }

    @Test fun lastSetIsAPlusSetOnlyInNormalSessions() {
        val st = SlotState(60.0, 5)
        assertTrue(Progression.prescribe(squat, spec, st).top)
        assertFalse(Progression.prescribe(squat, spec, st, phase = Phase.DELOAD).top)
        assertFalse(Progression.prescribe(squat, spec.copy(sets = 1), st).top)
        assertFalse(Progression.prescribe(squat, spec, SlotState.fresh(spec)).top)
    }

    // ---------- doing well ----------

    @Test fun novicesAddWeightEverySessionPacedByThePlusSet() {
        val st = SlotState(60.0, 5)
        assertEquals(Outcome.AddWeight(60.0, 62.5, 5), eval(st, sets(60.0, 5, 5, 6), benchSpec, bench, novice = true).outcome)
        assertEquals(Outcome.AddWeight(60.0, 65.0, 5), eval(st, sets(60.0, 5, 5, 7), benchSpec, bench, novice = true).outcome)
        // Nothing to spare: consolidate once, then add anyway (in case the "+" set was not pushed).
        val first = eval(st, sets(60.0, 5, 5, 5), benchSpec, bench, novice = true)
        assertEquals(Outcome.Hold(60.0, 5, HoldReason.CONSOLIDATE), first.outcome)
        assertEquals(Outcome.AddWeight(60.0, 62.5, 5), eval(first.state, sets(60.0, 5, 5, 5), benchSpec, bench, novice = true).outcome)
        // 4 spare reps: straight to what the estimate supports (novices +15 % at most).
        assertEquals(Outcome.Jump(60.0, 65.0, 5), eval(st, sets(60.0, 5, 5, 9), benchSpec, bench, novice = true).outcome)
    }

    @Test fun aGrindOnThePlusSetHoldsTheWeight() {
        // The target on the "+" set with nothing left (rated 0) is not a reason to add weight, even for a novice.
        val done = listOf(DoneSet(60.0, 5, 2), DoneSet(60.0, 5, 1), DoneSet(60.0, 5, 0))
        val first = eval(SlotState(60.0, 5), done, benchSpec, bench, novice = true)
        assertEquals(Outcome.Hold(60.0, 5, HoldReason.CONSOLIDATE), first.outcome)
        val second = eval(first.state, done, benchSpec, bench, novice = true)
        assertEquals(Outcome.Hold(60.0, 5, HoldReason.CONSOLIDATE), second.outcome)
        // The third time the reps go up instead of the weight.
        assertEquals(Outcome.AddReps(60.0, 5, 6), eval(second.state, done, benchSpec, bench, novice = true).outcome)
    }

    @Test fun bigRelativeStepsUseRepsNotTheLinearPhase() {
        val db = Catalog["db_bench"]
        val s = spec.copy(exerciseId = "db_bench")
        // 12 → 14 kg is +17 %: climb the reps first.
        assertEquals(Outcome.AddReps(12.0, 5, 6), eval(SlotState(12.0, 5), sets(12.0, 5, 5, 6), s, db, novice = true).outcome)
    }

    @Test fun doubleProgressionClimbsTheRangeThenAddsWeight() {
        assertEquals(Outcome.AddReps(60.0, 5, 6), eval(SlotState(60.0, 5), sets(60.0, 5, 5, 6)).outcome)
        assertEquals(Outcome.AddReps(60.0, 5, 7), eval(SlotState(60.0, 5), sets(60.0, 5, 5, 7)).outcome)
        val hold = eval(SlotState(60.0, 5), sets(60.0, 5, 5, 5))
        assertEquals(Outcome.Hold(60.0, 5, HoldReason.CONSOLIDATE), hold.outcome)
        assertEquals(Outcome.AddReps(60.0, 5, 6), eval(hold.state, sets(60.0, 5, 5, 5)).outcome)
        // Top of the range: +2.5 kg and the reps the estimate expects there.
        assertEquals(Outcome.AddWeight(60.0, 62.5, 6), eval(SlotState(60.0, 8), sets(60.0, 8, 8, 9)).outcome)
    }

    @Test fun reportedEasySetsJump() {
        val done = listOf(DoneSet(60.0, 5, 4), DoneSet(60.0, 5, 4), DoneSet(60.0, 5))
        assertEquals(Outcome.Jump(60.0, 62.5, 5), eval(SlotState(60.0, 5), done).outcome)
    }

    @Test fun coarseStepsGrowRepsUntilTheNextWeightFits() {
        val curl = Catalog["db_curl"]
        val s = SlotSpec("db_curl", 3, 5, 8, 2, 75, SlotRole.ACCESSORY)
        // 10 → 12 kg would leave about 2 reps: a rep more with 10 kg instead.
        assertEquals(Outcome.AddReps(10.0, 8, 9), eval(SlotState(10.0, 8), sets(10.0, 8, 8, 9), s, curl).outcome)
        // Lots to spare but 12 kg still out of reach: a few more reps (at most +3).
        assertEquals(Outcome.AddReps(10.0, 8, 11), eval(SlotState(10.0, 8), sets(10.0, 8, 8, 13), s, curl).outcome)
    }

    // ---------- falling short ----------

    @Test fun nearMissesHoldThenResetAtTheBottomOfTheRange() {
        var st = SlotState(60.0, 5)
        repeat(2) {
            val ev = eval(st, sets(60.0, 5, 4, 4))
            assertEquals(Outcome.Hold(60.0, 5, HoldReason.NEAR_MISS), ev.outcome)
            st = ev.state
        }
        assertEquals(Outcome.Reset(60.0, 52.5, 5), eval(st, sets(60.0, 5, 4, 4)).outcome)
    }

    @Test fun missedRepsLowerTheRepTargetBeforeTheWeight() {
        assertEquals(Outcome.LowerReps(60.0, 7, 5), eval(SlotState(60.0, 7), sets(60.0, 5, 5, 4)).outcome)
        // A near miss gets one more try, the second one lowers the reps.
        val near = eval(SlotState(60.0, 7), sets(60.0, 7, 6, 6))
        assertEquals(Outcome.Hold(60.0, 7, HoldReason.NEAR_MISS), near.outcome)
        assertEquals(Outcome.LowerReps(60.0, 7, 6), eval(near.state, sets(60.0, 7, 6, 6)).outcome)
    }

    @Test fun clearMissAtTheBottomReducesHalfWayThenFully() {
        assertEquals(Outcome.Reduce(60.0, 55.0, 5), eval(SlotState(60.0, 5), sets(60.0, 3, 3, 2)).outcome)
        // Far short: all the way to what was shown, but never more than −10 % at once.
        assertEquals(Outcome.Reduce(60.0, 52.5, 5), eval(SlotState(60.0, 5), sets(60.0, 2, 2, 2)).outcome)
    }

    @Test fun reductionsStopAtTheFloorOfRecentStrength() {
        // Best estimate of the last weeks 85 kg: never below 85 % of what it supports (57.5 kg).
        assertEquals(Outcome.Reduce(60.0, 57.5, 5), eval(SlotState(60.0, 5), sets(60.0, 2, 2, 2), recentBest = 85.0).outcome)
    }

    @Test fun aCutThatBringsNoRepsMeansItIsNotTheWeight() {
        val st = SlotState(55.0, 5, cutFrom = 60.0, cutReps = 3)
        assertEquals(Outcome.Hold(55.0, 3, HoldReason.NOT_THE_WEIGHT), eval(st, sets(55.0, 3, 3, 2)).outcome)
    }

    @Test fun setsDoneLighterAfterAHintCountAsANearMiss() {
        val done = listOf(DoneSet(60.0, 5, 0), DoneSet(55.0, 5), DoneSet(55.0, 6))
        assertEquals(Outcome.Hold(60.0, 5, HoldReason.NEAR_MISS), eval(SlotState(60.0, 5), done).outcome)
    }

    @Test fun fewerSetsOrLighterByChoiceKeepTheTarget() {
        assertEquals(Outcome.Hold(60.0, 5, HoldReason.FEWER_SETS), eval(SlotState(60.0, 5), sets(60.0, 5, 5)).outcome)
        assertEquals(Outcome.Hold(60.0, 5, HoldReason.LIGHTER), eval(SlotState(60.0, 5), sets(55.0, 5, 5, 5)).outcome)
        assertEquals(Outcome.NotDone, eval(SlotState(60.0, 5), emptyList()).outcome)
    }

    // ---------- lighter weeks, breaks, hints ----------

    @Test fun deloadIsLighterAndDoesNotMoveProgress() {
        val st = SlotState(60.0, 7)
        val p = Progression.prescribe(squat, spec, st, phase = Phase.DELOAD)
        assertEquals(52.5, p.weight)
        assertEquals(2, p.sets)
        assertEquals(4, p.rir)
        val ev = eval(st, sets(52.5, 7, 7), p = p)
        assertEquals(Outcome.Deload, ev.outcome)
        assertEquals(60.0, ev.state.weight)
    }

    @Test fun layoffStartsLighter() {
        val st = SlotState(60.0, 8)
        assertEquals(60.0, Progression.prescribe(squat, spec, st, daysSinceLast = 10).weight)
        assertEquals(55.0, Progression.prescribe(squat, spec, st, daysSinceLast = 20).weight)
        assertEquals(52.5, Progression.prescribe(squat, spec, st, daysSinceLast = 40).weight)
        assertEquals(50.0, Progression.prescribe(squat, spec, st, daysSinceLast = 90).weight)
    }

    @Test fun inSessionHintsStayWithinTenPercentOfThePlan() {
        val p = Progression.prescribe(squat, spec, SlotState(60.0, 8))
        assertEquals(Progression.adjustAfterSet(squat, p, DoneSet(60.0, 4)), forma.core.strength.Adjustment(55.0, 8, false))
        assertEquals(Progression.adjustAfterSet(squat, p, DoneSet(60.0, 13)), forma.core.strength.Adjustment(65.0, 8, true))
        assertNull(Progression.adjustAfterSet(squat, p, DoneSet(60.0, 9)))
        // After a lighter set the bounds still come from the planned 60 kg: hints never compound.
        val again = Progression.adjustAfterSet(squat, p, DoneSet(55.0, 3), index = 1)
        assertTrue(again == null || again.weight >= 54.0)
    }

    // ---------- body weight, dumbbells, holds ----------

    @Test fun pullUpsGoFromAssistanceToExtraWeight() {
        val pull = Catalog["pullup"]
        val s = SlotSpec("pullup", 3, 5, 10, 2, 120, SlotRole.MAIN)
        // One rep with body weight: start with an assisted machine, 10 kg of help.
        val fresh = SlotState.fresh(s)
        val cal = Progression.evaluate(pull, s, fresh, Progression.prescribe(pull, s, fresh), listOf(DoneSet(0.0, 1)), 80.0)
        assertEquals(Outcome.Calibrated(-10.0, 5), cal.outcome)
        // Top of the range with body weight: +2.5 kg on a belt.
        val ev = Progression.evaluate(pull, s, SlotState(0.0, 10), Progression.prescribe(pull, s, SlotState(0.0, 10), bodyWeight = 80.0), sets(0.0, 10, 10, 11), 80.0)
        assertEquals(Outcome.AddWeight(0.0, 2.5, 9), ev.outcome)
        // Push-ups move about 65 % of body weight.
        assertEquals(52.0, Progression.effectiveLoad(Catalog["pushup"], 0.0, 80.0), 1e-9)
    }

    @Test fun lightDumbbellsMoveOneKiloAtATime() {
        val curl = Catalog["db_curl"]
        assertEquals(1.0, curl.stepAt(8.0))
        assertEquals(1.0, curl.stepAt(9.5))
        assertEquals(2.0, curl.stepAt(10.0))
        assertEquals(2.5, squat.stepAt(8.0))
    }

    @Test fun holdsGrowByFiveSeconds() {
        val plank = Catalog["plank"]
        val s = SlotSpec("plank", 3, 20, 60, 1, 60, SlotRole.ACCESSORY)
        val st = SlotState(null, 30)
        val p = Progression.prescribe(plank, s, st)
        assertEquals(Outcome.AddReps(0.0, 30, 35), Progression.evaluate(plank, s, st, p, sets(0.0, 30, 30, 30)).outcome)
        // Held longer than asked: from what the weakest set held, in 5 s steps.
        assertEquals(Outcome.AddReps(0.0, 30, 50), Progression.evaluate(plank, s, st, p, sets(0.0, 47, 49, 52)).outcome)
        val miss = Progression.evaluate(plank, s, st, p, sets(0.0, 30, 25, 20))
        assertIs<Outcome.Hold>(miss.outcome)
        // Short twice in a row: the target comes down to what was held.
        val second = Progression.evaluate(plank, s, miss.state, p, sets(0.0, 28, 25, 22))
        assertEquals(Outcome.LowerReps(0.0, 30, 20), second.outcome)
        assertEquals(20, second.state.reps)
    }

    @Test fun holdsStayAtTheTopAndRestInADeload() {
        val plank = Catalog["plank"]
        val s = SlotSpec("plank", 3, 20, 60, 1, 60, SlotRole.ACCESSORY)
        val top = SlotState(null, 60)
        val atTop = Progression.evaluate(plank, s, top, Progression.prescribe(plank, s, top), sets(0.0, 60, 60, 65))
        assertEquals(Outcome.Hold(0.0, 60, HoldReason.AT_TOP), atTop.outcome)
        assertEquals(60, atTop.state.reps)
        val st = SlotState(null, 30)
        val deload = Progression.evaluate(plank, s, st, Progression.prescribe(plank, s, st, phase = Phase.DELOAD), sets(0.0, 40, 40))
        assertEquals(Outcome.Deload, deload.outcome)
        assertEquals(30, deload.state.reps)
    }

    // ---------- the lightest load ----------

    @Test fun barbellsNeverGoBelowTheEmptyBar() {
        val st = SlotState(20.0, 5)
        assertEquals(20.0, Progression.prescribe(bench, benchSpec, st, phase = Phase.DELOAD).weight)
        assertEquals(20.0, Progression.prescribe(bench, benchSpec, SlotState(22.5, 5), daysSinceLast = 90).weight)
        assertEquals(20.0, Progression.prescribe(bench, benchSpec, SlotState.fresh(benchSpec), e1rm = 15.0).weight)
        // A weak first session: the working weight is the empty bar, not 17.5 kg.
        val fresh = SlotState.fresh(benchSpec)
        val cal = eval(fresh, listOf(DoneSet(20.0, 3)), s = benchSpec, ex = bench, p = Progression.prescribe(bench, benchSpec, fresh))
        assertEquals(20.0, cal.state.weight)
        val cut = eval(SlotState(22.5, 5), sets(22.5, 2, 2, 1), s = benchSpec, ex = bench)
        assertTrue(cut.state.weight!! >= 20.0)
    }

    @Test fun atTheLightestLoadTheRepsGoBelowTheRange() {
        // The empty bar is too heavy for 5: the target becomes what was done, then climbs back.
        val bar = eval(SlotState(20.0, 5), sets(20.0, 3, 3, 2), s = benchSpec, ex = bench)
        assertEquals(Outcome.LowerReps(20.0, 5, 2), bar.outcome)
        val back = eval(bar.state, sets(20.0, 3, 3, 6), s = benchSpec, ex = bench)
        assertEquals(Outcome.AddReps(20.0, 2, 5), back.outcome)

        val push = Catalog["pushup"]
        val s = SlotSpec("pushup", 3, 8, 15, 2, 90, SlotRole.SECONDARY)
        // A near miss holds first, like any near miss.
        assertEquals(HoldReason.NEAR_MISS, (Progression.evaluate(push, s, SlotState(0.0, 8), Progression.prescribe(push, s, SlotState(0.0, 8)), sets(0.0, 8, 8, 7), 80.0).outcome as Outcome.Hold).reason)
        val low = Progression.evaluate(push, s, SlotState(0.0, 8), Progression.prescribe(push, s, SlotState(0.0, 8)), sets(0.0, 5, 4, 4), 80.0)
        assertEquals(Outcome.LowerReps(0.0, 8, 4), low.outcome)
        // A big "+" set below the range: the reps climb faster, but the weight is not touched before the range is reached.
        val up = Progression.evaluate(push, s, low.state, Progression.prescribe(push, s, low.state), sets(0.0, 4, 4, 9), 80.0)
        assertEquals(Outcome.AddReps(0.0, 4, 7), up.outcome)
    }

    @Test fun theFloorForAssistedLiftsMeansMoreHelp() {
        val pull = Catalog["pullup"]
        val s = SlotSpec("pullup", 3, 5, 10, 2, 120, SlotRole.MAIN)
        val st = SlotState(-15.0, 5)
        // Far short with 15 kg of help: more help, and the floor of recent strength (25 kg of help) does not stop it.
        val ev = Progression.evaluate(pull, s, st, Progression.prescribe(pull, s, st, bodyWeight = 80.0), sets(-15.0, 2, 2, 1), 80.0, recentBest = 80.0)
        assertEquals(Outcome.Reduce(-15.0, -22.5, 5), ev.outcome)
    }

    @Test fun aNewRepRangeFromTheEstimateIsLighterAfterABreak() {
        val fresh = SlotState.fresh(spec)
        assertEquals(80.0, Progression.prescribe(squat, spec, fresh, e1rm = 100.0).weight)
        assertEquals(72.5, Progression.prescribe(squat, spec, fresh, e1rm = 100.0, daysSinceLast = 40).weight)
    }

    // ---------- estimates ----------

    @Test fun unratedSetsAreReadSensibly() {
        val p = Progression.prescribe(squat, spec, SlotState(60.0, 5))
        assertEquals(1, Progression.assumedRir(DoneSet(60.0, 9), 2, p)) // "+" set: one left
        assertEquals(1, Progression.assumedRir(DoneSet(60.0, 6), 0, p)) // one extra rep came out of the reserve
        assertEquals(0, Progression.assumedRir(DoneSet(60.0, 4), 0, p)) // short: to failure
        assertEquals(3, Progression.assumedRir(DoneSet(60.0, 5, 3), 0, p)) // the lifter's word wins
    }

    @Test fun highRepSetsDoNotDriveTheEstimate() {
        // 15 easy reps would claim a far higher 1RM than the 5-rep set; only the 5-rep set counts.
        val e = Progression.e1rmOf(squat, listOf(DoneSet(100.0, 5, 1), DoneSet(70.0, 15, 3)), 5, 2, 80.0)!!
        assertEquals(OneRm.estimate(100.0, 5, 1), e, 1e-9)
    }

    @Test fun e1rmSmoothingFavoursGainsAndDampsOutliers() {
        assertEquals(104.0, Progression.smoothE1rm(100.0, 108.0), 1e-9)
        assertEquals(97.0, Progression.smoothE1rm(100.0, 90.0), 1e-9)
        assertEquals(104.5, Progression.smoothE1rm(100.0, 130.0), 1e-9)
        assertEquals(105.0, Progression.smoothE1rm(100.0, 140.0), 1e-9)
        assertEquals(107.5, Progression.smoothE1rm(100.0, 160.0, novice = true), 1e-9)
        assertEquals(90.0, Progression.smoothE1rm(null, 90.0))
    }

    @Test fun startHintRespectsBarAndStep() {
        assertEquals(37.5, Progression.startHint(squat, 75.0, female = false, trained = 1.0))
        assertEquals(20.0, Progression.startHint(Catalog["ohp"], 50.0, female = true, trained = 1.0))
    }
}
