package forma.core

import forma.core.model.RunGoal
import forma.core.run.Press
import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunTracker
import forma.core.run.RunWorkout
import forma.core.run.Vdot
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis before his first trial (1500 m in 5:30 as the goal, a 205 m loop: 7 laps from the line, 1435 m; no fitness
 * known yet: the beeps at 48.7 s a lap): «он начнет в нужном темпе но просто не факт что добежит с нужной скоростью». A trial whose second half is slower,
 * or that ends in a walk or a stop, still says what the runner held from the start (at least half the trial and three
 * minutes), never thrown away: stopped, the fitness is that stretch's; run to the end slower, the whole time's, raised
 * toward the stretch's by 3 at most (a runner who died after half lacks some of what the stretch alone would credit).
 */
class TrialHeldTest {
    private val goal = RunGoal(1500, 330)
    private val lap = 205
    private val caps = RunCaps(45, 90)
    private val fresh = RunState(walkStage = -1)

    /** The trial run lap by lap (seconds each), then stopped. */
    private fun run(st: RunState, laps: List<Double>): Pair<RunWorkout, RunResult> {
        val w = RunPlan.goalTest(st, goal, lap)
        val t = RunTracker(w)
        t.start(0)
        val start = 12 * 60 * 1000L
        assertEquals(Press.Start, t.press(start))
        var at = start
        laps.forEach { s ->
            at += (s * 1000).roundToInt()
            t.lap(at)
        }
        t.finish(at + 2_000)
        return w to t.result(at + 2_000, 9)
    }

    private val held = List(4) { 48.7 }

    @Test fun theBeepsAreAtTheGoalPlusEightPercent() {
        val w = RunPlan.goalTest(fresh, goal, lap)
        assertEquals(1435, w.testMeters)
        val seg = w.segments.first { it.laps != null }
        assertEquals(7.0, seg.laps)
        assertEquals(48.7, seg.lapSec!!, 0.1)
    }

    @Test fun slowerAfterHalfTheFitnessIsWhatWasHeld() {
        val (w, r) = run(fresh, held + listOf(56.0, 62.0, 66.0))
        assertEquals(379, r.testSeconds)
        val after = RunPlan.after(fresh, w, r, caps, day = 20, goal = goal)
        // The whole time alone: VDOT 39.7. The first 820 m in 3:15: 42.1.
        assertEquals(42.0, after.state.vdot)
        val part = after.outcomes.filterIsInstance<RunOutcome.TestFromPart>().single()
        assertEquals(820, part.meters)
        assertEquals(195, part.seconds)
        assertTrue(part.finished)
        assertTrue(after.outcomes.any { it is RunOutcome.TestTime && it.seconds == 379 }, "the time run is kept")
        assertTrue(RunOutcome.GoalReached !in after.outcomes)
        assertTrue(!after.state.testSoon)
    }

    @Test fun aWalkedLapAtTheEndDoesNotThrowTheTrialAway() {
        // 90 s for a lap of 48.7: more than 1.7 times — not trusted as the end of the trial, but what came before is.
        val (w, r) = run(fresh, held + listOf(65.0, 90.0, 80.0))
        assertNull(r.testSeconds)
        val after = RunPlan.after(fresh, w, r, caps, day = 20, goal = goal)
        // The stretch gives 42.1; the trial walked to its end in 7:10 gives 34.4: 3 above that, as a slower end would.
        assertEquals(((Vdot.fromRace(1435.0, 429.8) + RunPlan.PART_OVER_WHOLE) * 2).roundToInt() / 2.0, after.state.vdot)
        assertEquals(37.5, after.state.vdot)
        val part = assertNotNull(after.outcomes.filterIsInstance<RunOutcome.TestFromPart>().singleOrNull())
        assertTrue(!part.finished && part.capped)
        assertTrue(RunOutcome.TestNotCounted !in after.outcomes)
        assertTrue(after.state.testSoon)
    }

    /**
     * The last laps jogged at 82 s (just under 1.7 times the beeps: the whole time counts) or walked at 84 s (just over
     * it: the stretch counts, held back by the time to the end): a slower lap never gives the higher fitness.
     */
    @Test fun walkingTheLastLapsNeverBeatsJoggingThem() {
        val jogged = run(fresh, held + List(3) { 82.0 }).let { (w, r) -> RunPlan.after(fresh, w, r, caps, day = 20, goal = goal) }
        val walked = run(fresh, held + List(3) { 84.0 }).let { (w, r) -> RunPlan.after(fresh, w, r, caps, day = 20, goal = goal) }
        assertTrue(walked.state.vdot!! <= jogged.state.vdot!!, "jogged ${jogged.state.vdot}, walked ${walked.state.vdot}")
    }

    @Test fun stoppedAfterHalfTheRunPartCountsAndAFullTrialComesAgain() {
        val (w, r) = run(fresh, held)
        assertNull(r.testSeconds)
        val after = RunPlan.after(fresh, w, r, caps, day = 20, goal = goal)
        assertEquals(42.0, after.state.vdot)
        val part = after.outcomes.filterIsInstance<RunOutcome.TestFromPart>().single()
        assertTrue(!part.finished)
        assertTrue(after.outcomes.any { it is RunOutcome.VdotChanged })
        assertTrue(after.state.testSoon, "a full trial again")
    }

    @Test fun stoppedBeforeHalfIsNotCountedAndSaysWhy() {
        // 615 m: under half the trial (718 m).
        val (w, r) = run(fresh, List(3) { 48.7 })
        val after = RunPlan.after(fresh, w, r, caps, day = 20, goal = goal)
        assertNull(after.state.vdot)
        val stop = after.outcomes.filterIsInstance<RunOutcome.TestStopped>().single()
        assertEquals(615, stop.meters)
        assertTrue(RunOutcome.TestNotCounted !in after.outcomes)
    }

    @Test fun aPartNeverLowersAKnownFitnessButRaisesALowerOne() {
        val known = fresh.copy(vdot = 45.0)
        // 820 m in 3:15 give 42.1: a stop may have had more in it, the 45 stays.
        val (w, r) = run(known, held)
        val kept = RunPlan.after(known, w, r, caps, day = 20, goal = goal)
        assertEquals(45.0, kept.state.vdot)
        assertTrue(kept.outcomes.none { it is RunOutcome.VdotChanged })
        assertTrue(kept.outcomes.filterIsInstance<RunOutcome.TestFromPart>().single().kept)
        assertTrue(kept.state.testSoon)

        val low = fresh.copy(vdot = 40.0)
        val (w2, r2) = run(low, held)
        val raised = RunPlan.after(low, w2, r2, caps, day = 20, goal = goal)
        assertEquals(42.0, raised.state.vdot)
    }

    @Test fun aStretchFarBelowAKnownFitnessKeepsItAndIsNoTypo() {
        // 52 known (a few years back, say); stopped after 820 m at the beeps: 42.1 — kept, not "a typo".
        val known = fresh.copy(vdot = 52.0)
        val (w, r) = run(known, held)
        val after = RunPlan.after(known, w, r, caps, day = 20, goal = goal)
        assertEquals(52.0, after.state.vdot)
        assertTrue(after.outcomes.none { it is RunOutcome.TestDoubtful }, "${after.outcomes}")
        assertTrue(after.outcomes.filterIsInstance<RunOutcome.TestFromPart>().single().kept)
    }

    @Test fun anEvenTrialIsItsTime() {
        val (w, r) = run(fresh, List(7) { 52.0 })
        val after = RunPlan.after(fresh, w, r, caps, day = 20, goal = goal)
        assertEquals((Vdot.fromRace(1435.0, 364.0) * 2).roundToInt() / 2.0, after.state.vdot)
        assertEquals(41.5, after.state.vdot)
        assertTrue(after.outcomes.none { it is RunOutcome.TestFromPart })
    }

    @Test fun theGoalIsReachedOnlyByTheWholeTime() {
        // Away fast and faded: the first stretch is at the goal's fitness, the whole time far from it — 3 above the whole
        // time's, and no goal.
        val (w, r) = run(fresh, List(4) { 42.0 } + listOf(60.0, 66.0, 70.0))
        val part = assertNotNull(RunPlan.held(w, r))
        assertTrue(part.vdot >= Vdot.fromRace(1500.0, 330.0) - 0.5)
        val after = RunPlan.after(fresh, w, r, caps, day = 20, goal = goal)
        val raw = Vdot.fromRace(w.testMeters!!.toDouble(), r.testSeconds!!.toDouble())
        assertEquals(((raw + RunPlan.PART_OVER_WHOLE) * 2).roundToInt() / 2.0, after.state.vdot)
        assertTrue(after.outcomes.filterIsInstance<RunOutcome.TestFromPart>().single().let { it.finished && it.capped })
        assertTrue(RunOutcome.GoalReached !in after.outcomes)
    }

    @Test fun onTheRoadAStoppedTrialCountsTheGpsPartToo() {
        val w = RunPlan.goalTest(fresh, goal, null)
        val r = RunResult(0.6, 9, 1500, workDistanceM = 900.0, workSec = 215)
        val after = RunPlan.after(fresh, w, r, caps, day = 20, goal = goal)
        assertEquals((Vdot.fromRace(900.0, 215.0) * 2).roundToInt() / 2.0, after.state.vdot)
        assertTrue(after.outcomes.filterIsInstance<RunOutcome.TestFromPart>().single().let { !it.finished && it.meters == 900 })
    }
}
