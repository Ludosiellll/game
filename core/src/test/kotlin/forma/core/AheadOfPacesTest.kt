package forma.core

import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #17 (a life of the background run, person 422734): a runner without a goal growing fast, 49.6 to 54.9 in two
 * months, ran every hard session on its paces at a 6 or 7 where an 8 was asked, and the fitness stayed at the 48.5 of
 * the first trial until the next one, eight weeks later. Three hard sessions in a row ahead of their paces (well quicker,
 * or on them with effort to spare) call the trial on the next hard day — from four weeks after the last one.
 */
class AheadOfPacesTest {
    private val caps = RunCaps(60, 90)
    private val day0 = 21_000L

    private fun runner(lastTest: Long) = RunState(walkStage = -1, vdot = 48.5, runs = 20, qualityCount = 6, lastTestDay = lastTest, lastRunDay = day0 - 2)

    /** [w] run in full at [share] of its reps' pace, at the effort it asks and [spare] less. */
    private fun ran(w: RunWorkout, share: Double, spare: Int): RunResult {
        val pace = w.segments.first { it.kind == SegmentKind.WORK }.pace!!.let { (it.fast + it.slow) / 2.0 } * share
        return RunResult(completed = 1.0, rpe = w.targetRpe - spare, durationSec = w.totalSeconds, workPace = pace, reps = w.reps)
    }

    private fun three(st0: RunState, share: Double, spare: Int, cutback: Set<Int> = emptySet()): Pair<RunState, List<RunOutcome>> {
        var st = st0
        val all = mutableListOf<RunOutcome>()
        for (k in 0 until 3) {
            val w = RunPlan.quality(st, cutback = k in cutback)
            assertTrue(w.type == RunType.INTERVALS || w.type == RunType.TEMPO, "${w.type}")
            val a = RunPlan.after(st, w, ran(w, share, spare), caps, day0 + 3L * k)
            all += a.outcomes
            st = a.state
        }
        return st to all
    }

    @Test fun threeHardSessionsWellQuickerCallATrial() {
        val (st, outs) = three(runner(day0 - 35), 0.94, spare = 0)
        assertTrue(RunOutcome.AheadOfPaces in outs, "$outs")
        assertTrue(st.testSoon)
        assertEquals(0, st.qualityAhead)
    }

    @Test fun onPaceWithEffortToSpareCallsATrialToo() {
        val (st, outs) = three(runner(day0 - 35), 1.0, spare = 1)
        assertTrue(RunOutcome.AheadOfPaces in outs, "$outs")
        assertTrue(st.testSoon)
    }

    @Test fun notWithinFourWeeksOfTheLastTrial() {
        val (st, outs) = three(runner(day0 - 10), 0.94, spare = 1)
        assertFalse(RunOutcome.AheadOfPaces in outs, "$outs")
        assertFalse(st.testSoon)
    }

    @Test fun onPaceAtTheEffortAskedOrALighterWeekIsNoSign() {
        val (fit, a) = three(runner(day0 - 35), 1.0, spare = 0)
        assertFalse(RunOutcome.AheadOfPaces in a, "$a")
        assertFalse(fit.testSoon)
        val (lighter, b) = three(runner(day0 - 35), 0.94, spare = 1, cutback = setOf(1))
        assertFalse(RunOutcome.AheadOfPaces in b, "$b")
        assertFalse(lighter.testSoon)
    }
}
