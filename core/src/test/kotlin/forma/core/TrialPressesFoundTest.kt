package forma.core

import forma.core.model.RunGoal
import forma.core.run.Press
import forma.core.run.RunPlan
import forma.core.run.RunState
import forma.core.run.RunTracker
import forma.core.run.RunWorkout
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the rule on trials pressed lap by lap (`run_trial_presses`) found on random trials, kept as it was found. */
class TrialPressesFoundTest {
    /** The trial pressed: the start on the line, a press at every crossing ([laps], the first the half lap), [strays] of the way into a lap. */
    private fun run(w: RunWorkout, laps: List<Double>, strays: Map<Int, Double> = emptyMap()): Pair<RunTracker, Int> {
        val t = RunTracker(w)
        t.start(0)
        val start = 12 * 60 * 1000L
        assertEquals(Press.Start, t.press(start))
        var at = start
        laps.forEachIndexed { i, s ->
            strays[i + 1]?.let { f -> t.press(at + (s * f * 1000).roundToInt()) }
            at += (s * 1000).roundToInt()
            t.press(at)
        }
        t.finish(at + 3_000)
        return t to ((at - start) / 1000.0).roundToInt()
    }

    /**
     * Seed 21, case 8546: a 200 m loop at 38,4-s beeps, a touch at 0,74 of the half lap (4,7 s before the line, so the
     * line press is a double tap and goes), then one slow lap (64 s after 41) and the usual pace again. That one lap
     * stood for the next, the two laps after it (36,6 and 41,0) passed for a touch and its line and were made one lap of
     * 77,6 s, past belief for a lap: no time for a trial run to the end. A single slow lap is no fade: it does not say
     * what the next lap is.
     */
    @Test fun oneSlowLapDoesNotStandForTheNext() {
        val w = RunPlan.goalTest(RunState(walkStage = -1, vdot = 57.0), RunGoal(1500, 361), 200)
        val laps = listOf(18.2, 36.1, 64.2, 36.6, 41.0, 46.5, 49.0, 54.1)
        val (t, real) = run(w, laps, strays = mapOf(1 to 0.74))
        val reps = t.laps.filter { it.rep == 1 }
        assertEquals(8, reps.size, "${reps.map { "%.1f".format(it.seconds) }}")
        val time = assertNotNull(t.result(0, 9).testSeconds, "${reps.map { "%.1f".format(it.seconds) }}")
        assertTrue(abs(time - real) <= 1, "time $time, run in $real")
    }
}
