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
