package forma.core

import forma.core.model.RunGoal
import forma.core.run.ManualLaps
import forma.core.run.ManualRun
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
import kotlin.test.assertTrue

/**
 * The stretch held from the start of a trial (`RunPlan.held`) next to the whole time: it may only add to what the whole
 * time says, never throw it away, stand in for it on the road, or call a trial typed in full "not run to the end".
 */
class TrialHeldEdgeTest {
    private val goal = RunGoal(1500, 330)
    private val lap = 205
    private val caps = RunCaps(45, 90)

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

    /**
     * Fitness 40 known. Away far too fast (923 m at 3:25/km, a fitness of 50 for that stretch — not believable next to
     * 40) and then jogging to the line: the whole time (7:09, a fitness of 37,6) is a trial like any other and counts.
     * Only the stretch is beyond belief, not the trial.
     */
    @Test fun aStretchTooGoodToBelieveLeavesTheWholeTimeToCount() {
        val known = RunState(walkStage = -1, vdot = 40.0)
        val (w, r) = run(known, listOf(21.0) + List(4) { 42.0 } + List(3) { 80.0 })
        val t = assertNotNull(r.testSeconds)
        val raw = Vdot.fromRace(w.testMeters!!.toDouble(), t.toDouble())
        val part = assertNotNull(RunPlan.held(w, r))
        assertTrue(RunPlan.believable(raw, 40.0) && !RunPlan.believable(part.vdot, 40.0), "raw $raw, part ${part.vdot}")
        val after = RunPlan.after(known, w, r, caps, day = 20, goal = goal)
        assertTrue(after.outcomes.none { it is RunOutcome.TestDoubtful }, "${after.outcomes}")
        assertTrue(after.outcomes.any { it is RunOutcome.TestTime && it.seconds == t }, "${after.outcomes}")
        assertEquals((raw * 2).roundToInt() / 2.0, after.state.vdot)
    }

    /**
     * On the road the time is typed (1500 m in 5:40) and GPS measured the distance a few per cent long, as it does: the
     * GPS distance in the same time is the same run, not a stretch held and then faded — the typed time is the fitness.
     */
    @Test fun onTheRoadGpsDoesNotOverrideTheTypedTime() {
        val fresh = RunState(walkStage = -1)
        val w = RunPlan.goalTest(fresh, goal, null)
        val r = RunResult(1.0, 9, 1900, workDistanceM = 1545.0, workSec = 340, testSeconds = 340)
        val after = RunPlan.after(fresh, w, r, caps, day = 20, goal = goal)
        assertTrue(after.outcomes.none { it is RunOutcome.TestFromPart }, "${after.outcomes}")
        assertEquals((Vdot.fromRace(1500.0, 340.0) * 2).roundToInt() / 2.0, after.state.vdot)
    }

    /**
     * A trial without the phone, typed in as its laps (all 7,5 of them, each a lap) and no time: it was run to the end,
     * and its time is the laps' — not "not run to the end, a full trial again in two weeks".
     */
    @Test fun aTrialTypedAsAllItsLapsIsRunToTheEnd() {
        val fresh = RunState(walkStage = -1)
        val w = RunPlan.goalTest(fresh, goal, lap)
        val laps = ManualLaps.assign(w, listOf(26.0) + List(7) { 52.0 })
        val r = RunPlan.manual(w, ManualRun(null, null, rpe = 9)).copy(laps = laps)
        val after = RunPlan.after(fresh, w, r, caps, day = 20, goal = goal)
        assertTrue(after.outcomes.any { it is RunOutcome.TestTime && it.seconds == 390 }, "${after.outcomes}")
        assertTrue(after.outcomes.none { it is RunOutcome.TestFromPart && !it.finished }, "${after.outcomes}")
        assertTrue(!after.state.testSoon)
        assertEquals((Vdot.fromRace(1537.5, 390.0) * 2).roundToInt() / 2.0, after.state.vdot)
    }
}
