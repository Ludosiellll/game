package forma.core

import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.SegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The measured pace decides, not the effort alone: reps run slower than their target are not a step on. */
class PaceDecidesTest {
    private val state = RunState(vdot = 50.0, walkStage = -1, runs = 6, intervalStage = 2, tempoStage = 2)
    private val caps = RunCaps(45, 90)

    private fun target(w: forma.core.run.RunWorkout) =
        w.segments.first { it.kind == SegmentKind.WORK }.pace!!.let { (it.fast + it.slow) / 2.0 }

    @Test fun intervalsSlowerThanTheirPaceAreNotAStep() {
        val w = RunPlan.intervals(state)
        // Done in full, felt fine (7 of 8) — but 5 % slower than the interval pace.
        val slow = RunPlan.after(state, w, RunResult(1.0, 7, 1800, workPace = target(w) * 1.05), caps)
        assertEquals(2, slow.state.intervalStage)
        assertTrue(RunOutcome.TooSlow in slow.outcomes && slow.outcomes.any { it is RunOutcome.StageRepeat }, slow.outcomes.toString())
        // On pace: the step.
        val on = RunPlan.after(state, w, RunResult(1.0, 7, 1800, workPace = target(w)), caps)
        assertEquals(3, on.state.intervalStage)
        // Not measured: effort and the reps decide, and it is said that the pace was not measured.
        val blind = RunPlan.after(state, w, RunResult(1.0, 7, 1800), caps)
        assertEquals(3, blind.state.intervalStage)
        assertTrue(RunOutcome.PaceUnknown in blind.outcomes)
    }

    @Test fun tempoSlowThreeTimesBringsThePacesDown() {
        var s = state
        repeat(3) { s = RunPlan.after(s, RunPlan.tempo(s), RunResult(1.0, 6, 1800, workPace = target(RunPlan.tempo(s)) * 1.05), caps).state }
        assertEquals(49.0, s.vdot)
    }
}

/** Race-pace reps far from the goal are a step toward it; within reach, the goal's own pace. */
class StepToTheGoalTest {
    private val goal = forma.core.model.RunGoal(1500, 330)

    private fun repPace(w: forma.core.run.RunWorkout) = w.segments.first { it.kind == SegmentKind.WORK }.pace!!.let { (it.fast + it.slow) / 2.0 }

    @Test fun farFromTheGoalTheRepsAreAStepTowardIt() {
        // 1500 m in 6:40: the step is that form's race pace 2.5 % faster — not 5:30.
        val far = RunState(vdot = forma.core.run.Vdot.fromRace(1500.0, 400.0), walkStage = -1, runs = 6)
        val w = RunPlan.goalPace(far, goal, 230)
        val current = 400.0 / 1.5
        assertEquals(current * 0.975, repPace(w), 1.0)
        assertTrue("Ступень к цели" in w.note && "цель — 5:30" in w.note, w.note)
        assertTrue("Ступень к цели" in forma.core.run.RunExplain.of(w, goal).purpose)
        // Within 2.5 % of the goal: the goal's own pace, said as the goal.
        val close = RunState(vdot = forma.core.run.Vdot.fromRace(1500.0, 336.0), walkStage = -1, runs = 6)
        val c = RunPlan.goalPace(close, goal, 230)
        assertEquals(goal.pace, repPace(c), 1.0)
        assertTrue("Ступень" !in c.note)
        // Before any trial: the goal's pace (the trial comes first anyway).
        assertEquals(goal.pace, repPace(RunPlan.goalPace(RunState(walkStage = -1), goal, 230)), 1.0)
    }
}

/** A runner who rates easy runs 6–7 has a stricter scale: the plan raises its thresholds, the pace still decides. */
class OwnScaleTest {
    private val caps = RunCaps(45, 90)

    @Test fun easyRunsRatedHardRaiseTheThresholds() {
        var s = RunState(vdot = 45.0, walkStage = -1, runs = 6, easyMinutes = 30)
        // Five easy runs done in full, all rated 7 ("тяжело"): the first ones shorten the run, then the scale is learnt.
        repeat(5) { s = RunPlan.after(s, RunPlan.easy(s), RunResult(1.0, 7, s.easyMinutes * 60), caps).state }
        assertEquals(2, s.rpeOffset)
        val easy = RunPlan.after(s, RunPlan.easy(s), RunResult(1.0, 7, s.easyMinutes * 60), caps)
        assertTrue(easy.outcomes.any { it is RunOutcome.OwnScale }, easy.outcomes.toString())
        assertTrue(easy.outcomes.none { it is RunOutcome.Shorter }, easy.outcomes.toString())
        // Intervals rated 9 (limit 8), the pace held: with the scale, a step.
        val st = s.copy(intervalStage = 2)
        val w = RunPlan.intervals(st)
        val target = w.segments.first { it.kind == SegmentKind.WORK }.pace!!.let { (it.fast + it.slow) / 2.0 }
        assertEquals(3, RunPlan.after(st, w, RunResult(1.0, 9, 1800, workPace = target), caps).state.intervalStage)
        // Not measured: the scale does not count — no proof the paces were held.
        assertEquals(2, RunPlan.after(st, w, RunResult(1.0, 9, 1800), caps).state.intervalStage)
        // An honest scale (easy runs at 3–4): nothing changes.
        assertEquals(0, RunState(easyRpes = listOf(3, 4, 4, 3, 5)).rpeOffset)
    }
}
