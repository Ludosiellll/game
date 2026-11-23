package forma.core

import forma.core.model.RunGoal
import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunType
import forma.core.run.SegmentKind
import forma.core.run.Vdot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis, 23 November: «слишком сильная нагрузка в беге (ну очевидно что я не могу пробежать с темпом нужным полтора
 * километра, зачем ставить контрольный? почему нельзя просто продолжать бегать чуть в более легком режиме но почти на
 * пределе?)». Without a counted trial everything hard went by the goal: reps at the goal's own pace (45 s a 205 m lap), a
 * trial due every hard day and paced at the goal plus 8 % (49 s laps — he held three). His goal (1500 m in 5:30) is ten
 * points of fitness away. Now the app goes by an estimate of the fitness, from the goal at first and moved by how the runs
 * go; every hard run is paced from it, and no trial is asked for until training says it is near.
 */
class RunEstimateTest {
    private val caps = RunCaps(45, 90)
    private val goal = RunGoal(1500, 330)
    private val goalVdot = Vdot.fromRace(1500.0, 330.0)
    // As on 23 November: no trial counted, the last hard run missed.
    private val denis = RunState(walkStage = -1, easyMinutes = 30, qualityCount = 3, qualityFails = 1, runs = 12, lastTrialTryDay = 20755)

    private fun work(w: forma.core.run.RunWorkout) = w.segments.filter { it.kind == SegmentKind.WORK }
    private fun lapOf(pace: Double) = pace * 0.205

    @Test fun theEstimateStartsBelowTheGoalAndLowerAfterAMiss() {
        val fresh = RunPlan.estimated(denis.copy(qualityFails = 0), goal)
        assertEquals(RunPlan.provisionalVdot(goal), fresh.estVdot)
        val st = RunPlan.estimated(denis, goal)
        assertEquals(RunPlan.provisionalVdot(goal) - 1, st.estVdot)
        assertNull(st.vdot)
        // A fitness measured by a trial is never replaced, and no goal gives nothing to guess from.
        assertNull(RunPlan.estimated(denis.copy(vdot = 44.0), goal).estVdot)
        assertNull(RunPlan.estimated(denis, null).estVdot)
    }

    @Test fun everyHardRunIsPacedFromTheEstimateNotTheGoal() {
        val st = RunPlan.estimated(denis, goal)
        val est = st.estVdot!!
        // Intervals had no pace at all without a trial.
        val i = RunPlan.intervals(st)
        assertNotNull(work(i).first().pace, "intervals have a pace")
        // Race-pace reps: a step ahead of the race pace of the estimate, far slower than the goal's 45 s laps.
        val reps = RunPlan.goalPace(st, goal, 205)
        val now = Vdot.predictSeconds(est, 1500.0) / 1.5
        val lap = assertNotNull(work(reps).first().lapSec)
        assertEquals(lapOf(now * 0.975), lap, 0.2)
        assertTrue(lap > 50.0, "not the goal's 45 s: $lap")
        assertTrue("Ступень к цели" in reps.note, reps.note)
        // The trial, when it comes, is paced from the estimate too: not the goal plus 8 % (49 s laps).
        val trial = RunPlan.goalTest(st, goal, 205)
        assertEquals(lapOf(now), assertNotNull(work(trial).first().lapSec), 0.2)
        // Tempo: approximate, said so.
        val t = RunPlan.tempo(st)
        assertNotNull(work(t).first().pace)
        assertTrue("примерн" in t.note, t.note)
    }

    @Test fun aHardRunFarTooHardLowersTheEstimate() {
        val st = RunPlan.estimated(denis.copy(qualityCount = 1), goal)
        val w = RunPlan.onTrack(RunPlan.tempo(st), 205)
        val target = work(w).first().pace!!.let { (it.fast + it.slow) / 2.0 }
        val a = RunPlan.after(st, w, RunResult(0.5, 8, 1100, workPace = target), caps, day = 20780, goal = goal)
        assertEquals(st.estVdot!! - 1, a.state.estVdot)
        assertTrue(a.outcomes.any { it is RunOutcome.EstChanged && it.to < it.from }, "${a.outcomes}")
        assertNull(a.state.vdot)
        assertTrue(!a.state.testSoon)
        // Run far quicker than asked and blown up: the pace was the mistake, not the estimate.
        val b = RunPlan.after(st, w, RunResult(0.5, 8, 1100, workPace = target * 0.9), caps, day = 20780, goal = goal)
        assertEquals(st.estVdot, b.state.estVdot)
    }

    @Test fun hardRunsWithReserveRaiseItAndNoTrialIsCalled() {
        var st = RunPlan.estimated(denis.copy(qualityFails = 0), goal)
        val from = st.estVdot!!
        var day = 20780L
        repeat(3) {
            st = st.copy(qualityCount = 1)
            val w = RunPlan.tempo(st)
            val target = work(w).first().pace!!.let { (it.fast + it.slow) / 2.0 }
            st = RunPlan.after(st, w, RunResult(1.0, 5, w.totalSeconds, workPace = target), caps, day = day, goal = goal).state
            day += 7
        }
        assertTrue(st.estVdot!! > from, "${st.estVdot} > $from")
        assertTrue(!st.testSoon, "no trial is called while far from the goal")
        assertNull(st.vdot)
    }

    @Test fun nearTheGoalByTheEstimateATrialIsDue() {
        val near = denis.copy(qualityFails = 0, estVdot = goalVdot - 1.2, qualityCount = 1, qualityAhead = 2, tempoStage = 2)
        val w = RunPlan.tempo(near)
        val target = work(w).first().pace!!.let { (it.fast + it.slow) / 2.0 }
        val a = RunPlan.after(near, w, RunResult(1.0, 5, w.totalSeconds, workPace = target * 0.96), caps, day = 20780, goal = goal)
        assertTrue(a.state.estVdot!! > near.estVdot!!)
        assertTrue(a.state.testSoon, "training says the goal is near: the trial is earned")
        assertTrue(a.outcomes.contains(RunOutcome.TrialEarned), "${a.outcomes}")
    }

    @Test fun aCountedTrialTakesOver() {
        val st = RunPlan.estimated(denis, goal).copy(testSoon = true)
        val w = RunPlan.goalTest(st, goal, null)
        val a = RunPlan.after(st, w, RunResult(1.0, 9, 1700, testSeconds = 395), caps, day = 20780, goal = goal)
        assertNotNull(a.state.vdot)
        assertNull(a.state.estVdot)
    }

    @Test fun twoMissesInARowOfRacePaceLowerIt() {
        val st = RunPlan.estimated(denis.copy(qualityFails = 0, qualityCount = 0, goalStage = 3), goal)
        val w = RunPlan.goalPace(st, goal, 205)
        val target = work(w).first().pace!!.let { (it.fast + it.slow) / 2.0 }
        val miss = RunResult(0.6, 9, 1500, workPace = target * 1.06)
        val a = RunPlan.after(st, w, miss, caps, day = 20780, goal = goal)
        assertEquals(st.estVdot, a.state.estVdot)
        val b = RunPlan.after(a.state.copy(qualityCount = 0), RunPlan.goalPace(a.state, goal, 205), miss, caps, day = 20787, goal = goal)
        assertEquals(st.estVdot!! - 1, b.state.estVdot)
    }

    @Test fun aRunWithoutAGoalHasNothingToGuessFrom() {
        val w = RunPlan.intervals(denis)
        assertNull(work(w).first().pace)
        assertEquals(RunType.INTERVALS, w.type)
    }
}
