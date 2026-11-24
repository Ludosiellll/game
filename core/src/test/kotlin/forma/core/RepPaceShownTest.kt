package forma.core

import forma.core.model.RunGoal
import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.SegmentKind
import forma.core.run.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis, 16 November: «Темп цели · 6 × 1 круг» run at 42.6–44.4 s a 205 m lap, effort 6 — the goal's 45.1 s and quicker.
 * The release of 23 November paced these reps from his estimated fitness (40): 52.6 s a lap, far slower than he runs them
 * with ease. Fitness is about endurance; he is quicker over a lap than it says. The reps now go by the pace the runner
 * holds in them: never slower than that, never quicker than the goal.
 */
class RepPaceShownTest {
    private val caps = RunCaps(45, 90)
    private val goal = RunGoal(1500, 330)
    // As on 23 November: no trial counted, the last hard run missed, race-pace ladder at its first rung.
    private val denis = RunState(walkStage = -1, easyMinutes = 30, qualityCount = 3, qualityFails = 1, runs = 12, lastTrialTryDay = 20755)

    private fun lap(w: forma.core.run.RunWorkout) = assertNotNull(w.segments.first { it.kind == SegmentKind.WORK }.lapSec)
    private fun target(w: forma.core.run.RunWorkout) = w.segments.first { it.kind == SegmentKind.WORK }.pace!!.let { (it.fast + it.slow) / 2.0 }

    /** 16 November: six laps averaging 43.5 s, effort 6, all done. */
    private fun asOn16November(w: forma.core.run.RunWorkout) =
        RunResult(1.0, 6, w.totalSeconds, workPace = 43.53 / 0.205, reps = w.reps)

    @Test fun repsHeldWithEaseBringTheStepToThem() {
        val st = RunPlan.estimated(denis, goal)
        val w = RunPlan.goalPace(st, goal, 205)
        assertTrue(lap(w) > 52.0, "by the estimate alone: ${lap(w)}")
        val a = RunPlan.after(st, w, asOn16November(w), caps, day = 20780, goal = goal)
        assertEquals(goal.pace, assertNotNull(a.state.goalRepPace), 1e-9)
        assertTrue(a.outcomes.any { it is RunOutcome.RepPace && it.to < it.from }, "${a.outcomes}")
        // The next race-pace session: the goal's 45.1 s a lap, not 52.6 s.
        assertEquals(Track.lapSec(goal.pace, 205), lap(RunPlan.goalPace(a.state, goal, 205)), 0.1)
        // The estimate itself (tempo, intervals, the trial) does not move for short reps.
        assertEquals(st.estVdot, a.state.estVdot)
    }

    @Test fun aRunnerWhoRanThemBeforeHasThePaceFromThatRun() {
        val st = RunPlan.estimated(denis, goal)
        // The session of 16 November as the phone keeps it: planned at the goal's pace then (no estimate yet).
        val old = RunPlan.goalPace(denis, goal, 205)
        assertEquals(Track.lapSec(goal.pace, 205), lap(old), 0.1)
        val seeded = RunPlan.withShownRepPace(st, goal, listOf(old to asOn16November(old)))
        assertEquals(goal.pace, assertNotNull(seeded.goalRepPace), 1e-9)
        assertEquals(Track.lapSec(goal.pace, 205), lap(RunPlan.goalPace(seeded, goal, 205)), 0.1)
        // A kept pace is not replaced, and nothing is taken from a session that was too hard or not measured.
        assertEquals(250.0, RunPlan.withShownRepPace(st.copy(goalRepPace = 250.0), goal, listOf(old to asOn16November(old))).goalRepPace)
        assertNull(RunPlan.withShownRepPace(st, goal, listOf(old to RunResult(1.0, 9, old.totalSeconds, workPace = 212.0, reps = old.reps))).goalRepPace)
        assertNull(RunPlan.withShownRepPace(st, goal, listOf(old to RunResult(1.0, 6, old.totalSeconds, reps = old.reps))).goalRepPace)
        assertNull(RunPlan.withShownRepPace(st, goal, listOf(old to RunResult(0.5, 6, old.totalSeconds / 2, workPace = 212.0, reps = 3))).goalRepPace)
        assertNull(RunPlan.withShownRepPace(st, null, listOf(old to asOn16November(old))).goalRepPace)
    }

    @Test fun aPaceShownBetweenTheEstimateAndTheGoalIsTheStep() {
        val st = RunPlan.estimated(denis, goal)
        val w = RunPlan.goalPace(st, goal, 205)
        // Run at 48 s a lap, effort 6: quicker than asked, slower than the goal.
        val a = RunPlan.after(st, w, RunResult(1.0, 6, w.totalSeconds, workPace = 48.0 / 0.205, reps = w.reps), caps, day = 20780, goal = goal)
        assertEquals(48.0 / 0.205, assertNotNull(a.state.goalRepPace), 1e-6)
        assertEquals(48.0, lap(RunPlan.goalPace(a.state, goal, 205)), 0.1)
        // A slower session later does not take it back: the quickest held stays.
        val w2 = RunPlan.goalPace(a.state, goal, 205)
        val b = RunPlan.after(a.state, w2, RunResult(1.0, 7, w2.totalSeconds, workPace = target(w2) * 1.02, reps = w2.reps), caps, day = 20787, goal = goal)
        // (The target is whole seconds a km: within one of it.)
        assertEquals(48.0 / 0.205, assertNotNull(b.state.goalRepPace), 0.5)
    }

    @Test fun twoMissesInARowEaseItAStepAndBackToTheFitnessAtMost() {
        // The pace is kept by the length of the reps it was held in (audit of 24.11, the rep-pace loop): the reps of step 3
        // are 2 laps, and the pace held in them is the one eased.
        val two = RunPlan.repLength(3)
        val st = RunPlan.estimated(denis, goal).copy(goalRepPaces = mapOf(two to goal.pace), goalStage = 3, qualityFails = 0)
        val w = RunPlan.goalPace(st, goal, 205)
        assertEquals(Track.lapSec(goal.pace, 205), lap(w), 0.1)
        val miss = RunResult(0.6, 9, 1200, workPace = target(w) * 1.06, reps = 3)
        val a = RunPlan.after(st, w, miss, caps, day = 20780, goal = goal)
        assertEquals(goal.pace, a.state.goalRepPaces[two])
        val b = RunPlan.after(a.state.copy(qualityCount = 0), RunPlan.goalPace(a.state, goal, 205), miss, caps, day = 20787, goal = goal)
        assertEquals(goal.pace * (1 + RunPlan.STEP_AHEAD), assertNotNull(b.state.goalRepPaces[two]), 1e-9)
        assertTrue(b.outcomes.any { it is RunOutcome.StageDown }, "${b.outcomes}")
        // Eased past the fitness's own step: the fitness's step it is — kept as this length's pace (capped, not dropped:
        // dropped, the quicker pace of shorter reps would come back to these, the loop the audit of 24.11 found).
        // The fitness's step after this miss (the estimate a point lower), and a pace held just quicker than it.
        val fitnessAfter = RunPlan.stepPace(b.state.copy(goalRepPaces = emptyMap(), estVdot = b.state.estVdot!! - 1), goal)
        val near = b.state.copy(goalRepPace = fitnessAfter / 1.01, goalRepPaces = emptyMap(), goalFails = 1, qualityCount = 0)
        val c = RunPlan.after(near, RunPlan.goalPace(near, goal, 205), miss, caps, day = 20794, goal = goal)
        val fitnessNow = RunPlan.stepPace(c.state.copy(goalRepPace = null, goalRepPaces = emptyMap()), goal)
        assertEquals(fitnessNow, assertNotNull(c.state.goalRepPace), 1e-9)
    }

    @Test fun aBreakSlowsIt() {
        val st = RunPlan.estimated(denis, goal).copy(goalRepPace = goal.pace, lastRunDay = 20780)
        val back = RunPlan.withBreak(st, 20780 + 30)
        assertTrue(assertNotNull(back.goalRepPace) > goal.pace)
        // A few days off change nothing.
        assertEquals(goal.pace, RunPlan.withBreak(st, 20783).goalRepPace)
    }

    @Test fun neverQuickerThanTheGoal() {
        val st = RunPlan.estimated(denis, goal).copy(goalRepPace = 200.0)
        assertEquals(goal.pace, RunPlan.stepPace(st, goal), 1e-9)
    }
}
