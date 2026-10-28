package forma.core

import forma.core.model.Catalog
import forma.core.strength.DoneSet
import forma.core.strength.OneRm
import forma.core.strength.Phase
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Review of 4ac6d0c (pull-ups without needless help). Each test states what a good coach would do. */
class AssistedReviewTest {
    private val ex = Catalog["pullup"]
    private val spec = SlotSpec("pullup", 3, 5, 8, 2, 150, SlotRole.MAIN)
    private val bw = 55.0

    /**
     * The plan is the body alone × 5 (the lifter does 5–6). Set 1: 5 to failure; set 2 (tired): 4 to failure.
     * The in-session hint for set 3 must not be «помощь 5 кг» — the commit says hints build up reps with the body alone.
     */
    @Test fun aTiredSecondSetAtBodyWeightMustNotBringAHintOfHelp() {
        val st = SlotState(0.0, 5, sessions = 3, linear = false)
        val p = Progression.prescribe(ex, spec, st, e1rm = 66.0, bodyWeight = bw)
        assertEquals(0.0, p.weight)
        val next = Progression.nextSet(ex, p, listOf(DoneSet(0.0, 5, 0), DoneSet(0.0, 4, 0)), bw)!!
        assertTrue(next.weight >= 0.0, "after 5 and 4 with the body alone the hint for set 3 is help: ${next.weight} kg")
    }

    /** The same lifter climbing the range (0 × 6): the first set 4 to failure → hint «помощь 5 кг × 6». */
    @Test fun aShortFirstSetAtBodyWeightMustNotBringAHintOfHelp() {
        val st = SlotState(0.0, 6, sessions = 4, linear = false)
        val p = Progression.prescribe(ex, spec, st, e1rm = 66.0, bodyWeight = bw)
        val next = Progression.nextSet(ex, p, listOf(DoneSet(0.0, 4, 0)), bw)!!
        assertTrue(next.weight >= 0.0, "after 4 of 6 with the body alone the hint is help: ${next.weight} kg × ${next.reps}")
    }

    /**
     * A lighter week / a return after a break with the body alone: help is gone (right), but nothing else makes it
     * lighter — the reps stay at 5 with 4 (deload) or 2 (layoff) in reserve for a lifter whose estimate says 5 reps to
     * failure. The instruction cannot be followed; a coach would cut the reps (2–3).
     */
    @Test fun aLighterWeekAtBodyWeightAsksForRepsTheEstimateDoesNotSupport() {
        val e1rm = 64.0
        val rtf = OneRm.rtfAt(e1rm, bw)
        assertTrue(rtf >= 5.0 && rtf < 5.5, "RTF at body weight: $rtf")
        val st = SlotState(0.0, 5, sessions = 3)
        val deload = Progression.prescribe(ex, spec, st, Phase.DELOAD, e1rm = e1rm, bodyWeight = bw)
        assertEquals(0.0, deload.weight)
        assertTrue(deload.reps + deload.rir <= rtf + 1e-9, "deload: ${deload.sets} × ${deload.reps} with ${deload.rir} in reserve, but only $rtf to failure")
    }

    @Test fun aReturnAfterABreakAtBodyWeightAsksForRepsTheEstimateDoesNotSupport() {
        val e1rm = 64.0
        val rtf = OneRm.rtfAt(e1rm, bw)
        val st = SlotState(0.0, 5, sessions = 3)
        val back = Progression.prescribe(ex, spec, st, e1rm = e1rm, bodyWeight = bw, daysSinceLast = 40)
        assertEquals(0.0, back.weight)
        // The layoff says 10 % lighter; the body cannot get lighter, so the reps would have to.
        assertTrue(back.reps + back.rir <= rtf * 0.9 + 1e-9, "after 40 days: ${back.sets} × ${back.reps} with ${back.rir} in reserve, but only $rtf to failure before the break")
    }

    /**
     * Help is raised to the body alone only by the latest session done without help ([ownBest]), with that session's
     * reps — not by an old best of the last weeks. The latest session was with help (no [ownBest]) and the estimate says
     * about two to failure with the body: the help stays.
     */
    @Test fun theRepsAfterRaisingStaleHelpFollowTheOldBestNotTheCurrentEstimate() {
        val stale = SlotState(-7.5, 8, sessions = 6, linear = false)
        val p = Progression.prescribe(ex, spec, stale, e1rm = 58.0, bodyWeight = bw)
        assertEquals(-7.5, p.weight)
        // The latest session without help showed six to failure: the body alone, four with two in reserve.
        val raised = Progression.prescribe(ex, spec, stale, e1rm = 58.0, bodyWeight = bw, ownBest = OneRm.estimate(bw, 4, 2))
        assertEquals(0.0 to 4, raised.weight to raised.reps)
    }

    /**
     * A 10–12 secondary slot (the GENERAL focus), a lifter at help 10 kg who does 12, 12, 14: evaluate takes its own step
     * (help 7.5 kg × 10), then prescribe throws the step away and gives the body alone × 6 — the moved load goes from 45 to
     * 55 kg (+22 %) in one session, where the engine's own rule for a jump is +10 % at most, and on a 12–14-rep
     * extrapolation the code itself calls rough. A coach would take the help away a step at a time, or cap the jump.
     */
    @Test fun theRaiseToTheBodyAloneKeepsWithinTheEnginesOwnJumpCap() {
        val s = SlotSpec("pullup", 3, 10, 12, 2, 90, SlotRole.SECONDARY)
        val st = SlotState(-10.0, 12, sessions = 5, linear = false)
        // The estimate so far: about three to failure with the body alone — help is still needed.
        val p = Progression.prescribe(ex, s, st, e1rm = 60.0, bodyWeight = bw)
        assertEquals(-10.0, p.weight)
        val ev = Progression.evaluate(ex, s, st, p, listOf(DoneSet(-10.0, 12), DoneSet(-10.0, 12), DoneSet(-10.0, 14, 1)), bw, recentBest = 60.0)
        val stepped = Progression.effectiveLoad(ex, ev.state.weight!!, bw)
        // The session was with help: no session without help to raise it by.
        val next = Progression.prescribe(ex, s, ev.state, e1rm = Progression.smoothE1rm(60.0, ev.e1rm!!), bodyWeight = bw)
        val load = Progression.effectiveLoad(ex, next.weight!!, bw)
        assertTrue(
            load <= Progression.effectiveLoad(ex, -10.0, bw) * 1.10 + 1e-9,
            "evaluate: ${ev.outcome} (${stepped} kg moved); the next plan: ${next.weight} × ${next.reps} ($load kg moved, " +
                "${"%.0f".format((load / 45.0 - 1) * 100)} % more than the 45 kg of this session)",
        )
    }

    // ---- what held up (kept as regression guards for the review) ----

    /** Failing at 2 with the body alone: the hint of help is right. */
    @Test fun failingAtTwoStillGetsAHintOfHelp() {
        val st = SlotState(0.0, 5, sessions = 3, linear = false)
        val p = Progression.prescribe(ex, spec, st, e1rm = 66.0, bodyWeight = bw)
        val next = Progression.nextSet(ex, p, listOf(DoneSet(0.0, 2, 0)), bw)!!
        assertTrue(next.weight < 0.0, "2 of 5: ${next.weight}")
    }

    /** From help to the body alone and on to added weight, a weak lifter progressing through the range. */
    @Test fun aWeakLifterProgressesFromHelpToBodyToWeight() {
        val fresh = SlotState.fresh(spec)
        val p0 = Progression.prescribe(ex, spec, fresh, bodyWeight = bw, hint = 0.0)
        val ev0 = Progression.evaluate(ex, spec, fresh, p0, listOf(DoneSet(0.0, 2, 2), DoneSet(-5.0, 5, 2)), bw)
        assertTrue(ev0.state.weight!! < 0, "$ev0")
        var st = ev0.state
        var best = ev0.e1rm!!
        var own: Double? = null
        var sawBody = false
        repeat(14) {
            val p = Progression.prescribe(ex, spec, st, e1rm = best, bodyWeight = bw, ownBest = own)
            val w = p.weight!!
            if (w == 0.0) sawBody = true
            val done = List(p.sets) { i -> DoneSet(w, if (i == p.sets - 1) p.reps + 3 else p.reps, null) }
            val ev = Progression.evaluate(ex, spec, st, p, done, bw, recentBest = best)
            assertTrue(ev.state.weight!! >= st.weight!! - 1e-9, "went back to more help: ${st.weight} → ${ev.state.weight} ($ev)")
            st = ev.state
            best = maxOf(best, ev.e1rm ?: 0.0)
            own = ev.e1rm.takeIf { w >= 0 }
        }
        assertTrue(sawBody, "never reached the body alone: $st")
        assertTrue(st.weight!! > 0, "never reached added weight: $st")
    }

    /** Dips (bodyShare 0.95) and chin-ups behave the same as pull-ups. */
    @Test fun dipsAndChinUpsFollowTheSameRule() {
        for (id in listOf("dip", "chinup")) {
            val e = Catalog[id]
            val s = spec.copy(exerciseId = id)
            val fresh = SlotState.fresh(s)
            val p = Progression.prescribe(e, s, fresh, bodyWeight = bw, hint = 0.0)
            val ev = Progression.evaluate(e, s, fresh, p, listOf(DoneSet(0.0, 5, 1), DoneSet(0.0, 5, 0)), bw)
            assertTrue(ev.state.weight!! >= 0.0, "$id: $ev")
            val weak = Progression.evaluate(e, s, fresh, p, listOf(DoneSet(0.0, 2, 0)), bw)
            assertTrue(weak.state.weight!! < 0.0, "$id: $weak")
        }
    }

    /** Push-ups are BODYWEIGHT_PLUS but not assisted: the lightest load is the body, as before. */
    @Test fun pushUpsAndBarbellsAreUnchanged() {
        val push = Catalog["pushup"]
        assertEquals(0.0, Progression.lightest(push, bw, 40.0))
        assertEquals(0.0, Progression.lightest(push, bw, null))
        val squat = Catalog["squat"]
        assertEquals(20.0, Progression.lightest(squat, bw, 100.0))
    }
}
