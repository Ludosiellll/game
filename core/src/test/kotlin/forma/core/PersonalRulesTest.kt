package forma.core

import forma.core.plan.SessionTime
import forma.core.run.PaceTable
import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.strength.DoneSet
import forma.core.strength.Progression
import forma.core.strength.RestAdvice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rules that learn from the person's own data, each falling back to the plan's number until there is enough of it. */
class PersonalRulesTest {
    @Test fun sessionTimeFollowsTheirPace() {
        assertEquals(1.0, SessionTime.ratio(listOf(60.0 to 80.0, 60.0 to 80.0)))
        assertEquals(1.5, SessionTime.ratio(listOf(60.0 to 90.0, 60.0 to 90.0, 60.0 to 90.0)))
        // A session closed hours later or a mistap does not count.
        assertEquals(1.0, SessionTime.ratio(listOf(60.0 to 5.0, 60.0 to 400.0, 60.0 to 60.0, 60.0 to 60.0, 60.0 to 60.0)))
        assertEquals(2.0, SessionTime.ratio(List(5) { 30.0 to 200.0 }))
        assertEquals(85, SessionTime.round(83.0))
    }

    @Test fun restGrowsWhenRepsKeepFalling() {
        val falling = List(4) { listOf(DoneSet(80.0, 8, 2), DoneSet(80.0, 7, 1), DoneSet(80.0, 5, 0)) }
        assertTrue(RestAdvice.longer(falling))
        val steady = List(4) { listOf(DoneSet(80.0, 8, 2), DoneSet(80.0, 8, 2), DoneSet(80.0, 7, 1)) }
        assertFalse(RestAdvice.longer(steady))
        // Two sessions are not a pattern.
        assertFalse(RestAdvice.longer(falling.take(2)))
        // Fewer reps on a heavier last set is the plan, not fatigue.
        assertFalse(RestAdvice.longer(List(4) { listOf(DoneSet(80.0, 8, 2), DoneSet(80.0, 8, 2), DoneSet(90.0, 4, 1)) }))
    }

    @Test fun startGuessesLearnHowFarOffTheyWere() {
        assertEquals(1.0, Progression.ownStart(listOf(1.6)))
        assertEquals(1.5, Progression.ownStart(listOf(1.4, 1.5, 1.7)))
        assertEquals(2.5, Progression.ownStart(listOf(4.0, 5.0)))
        assertEquals(0.4, Progression.ownStart(listOf(0.1, 0.2)))
    }

    private val caps = RunCaps(60, 120)

    /** An easy run of 40 minutes at [pace] s/km rated [rpe]. */
    private fun easy(s: RunState, pace: Double, rpe: Int, day: Long) =
        RunPlan.after(s, RunPlan.easy(s), RunResult(1.0, rpe, 2400, distanceM = 2400 / pace * 1000), caps, day)

    @Test fun easyRunsMoveStalePaces() {
        var s = RunState(vdot = 40.0, walkStage = -1, runs = 10, easyMinutes = 40)
        val fast = PaceTable.forVdot(40.0).easy.fast * 0.92
        var last = easy(s, fast, 3, 100)
        repeat(4) { i -> s = last.state; last = easy(s, fast, 3, 102L + i * 2) }
        assertEquals(41.0, last.state.vdot)
        assertTrue(last.outcomes.any { it is RunOutcome.EasyPaces })
        // Not again for four weeks.
        s = last.state
        repeat(5) { i -> s = easy(s, PaceTable.forVdot(41.0).easy.fast * 0.92, 3, 110L + i * 2).state }
        assertEquals(41.0, s.vdot)
    }

    @Test fun aRecentTrialDecidesInstead() {
        var s = RunState(vdot = 40.0, walkStage = -1, runs = 10, easyMinutes = 40, lastTestDay = 95)
        val fast = PaceTable.forVdot(40.0).easy.fast * 0.92
        repeat(6) { i -> s = easy(s, fast, 3, 100L + i * 2).state }
        assertEquals(40.0, s.vdot)
    }

    @Test fun slowAndHardBringsThemDown() {
        var s = RunState(vdot = 45.0, walkStage = -1, runs = 10, easyMinutes = 40)
        val slow = PaceTable.forVdot(45.0).easy.slow * 1.08
        repeat(5) { i -> s = easy(s, slow, 6, 100L + i * 2).state }
        assertEquals(44.0, s.vdot)
        // Easy runs that feel easy at an easy pace change nothing.
        var t = RunState(vdot = 45.0, walkStage = -1, runs = 10, easyMinutes = 40)
        repeat(8) { i -> t = easy(t, PaceTable.forVdot(45.0).easy.slow.toDouble() - 5, 4, 100L + i * 2).state }
        assertEquals(45.0, t.vdot)
        assertNull(RunState().vdot)
    }
}
