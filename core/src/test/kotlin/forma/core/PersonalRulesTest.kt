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

    @Test fun restFollowsTheSetJustDone() {
        // A set as planned: the plan's rest.
        assertEquals(150, RestAdvice.after(150, 8, 2, 2, compound = true, deload = false, personal = 0).sec)
        // To failure: longer, and it says why.
        val hard = RestAdvice.after(150, 8, 0, 2, compound = true, deload = false, personal = 0)
        assertEquals(195, hard.sec)
        assertTrue("до отказа" in RestAdvice.words(hard))
        // Heavy and to failure: longer still; reps to spare: shorter.
        assertEquals(210, RestAdvice.after(150, 4, 0, 2, compound = true, deload = false, personal = 0).sec)
        assertEquals(135, RestAdvice.after(150, 8, 4, 2, compound = true, deload = false, personal = 0).sec)
        // A single-joint exercise with many reps and reps to spare goes short, but not under a minute (31.10).
        assertEquals(60, RestAdvice.after(60, 15, 4, 1, compound = false, deload = false, personal = 0).sec)
        // A multi-joint one never under 1:30, never over 5 min.
        assertEquals(90, RestAdvice.after(90, 15, 4, 1, compound = true, deload = true, personal = 0).sec)
        assertEquals(300, RestAdvice.after(240, 3, 0, 2, compound = true, deload = false, personal = 60).sec)
        // The person's own rest stays as set.
        assertEquals(120, RestAdvice.after(120, 3, 0, 2, compound = true, deload = false, personal = 60, own = true).sec)
        // Not rated: taken as planned.
        assertEquals(150, RestAdvice.after(150, 8, null, 2, compound = true, deload = false, personal = 0).sec)
        // Not rated but short of the target: it ran out.
        assertEquals(195, RestAdvice.after(150, 6, null, 2, compound = true, deload = false, personal = 0, target = 8).sec)
    }

    @Test fun restLearnsHowTheyRecover() {
        // Losing three reps to failure from set to set after 2 minutes: a minute more.
        assertEquals(60, RestAdvice.personal(List(6) { RestAdvice.Pair(120, 10, 7) }, 150))
        assertEquals(30, RestAdvice.personal(List(6) { RestAdvice.Pair(150, 10, 8) }, 150))
        // About a rep, as expected: nothing.
        assertEquals(0, RestAdvice.personal(List(6) { RestAdvice.Pair(150, 10, 9) }, 150))
        // Nothing lost on rests no longer than the plan's: they recover fast.
        assertEquals(-15, RestAdvice.personal(List(6) { RestAdvice.Pair(100, 10, 10) }, 150))
        // Five pairs are not enough.
        assertEquals(0, RestAdvice.personal(List(5) { RestAdvice.Pair(120, 10, 7) }, 150))
        // Pairs from logged sets: the rest between them from the times, failure where a set fell short unrated.
        val t = 1_000_000L
        val pairs = RestAdvice.pairsOf(listOf(DoneSet(80.0, 8, 2) to t, DoneSet(80.0, 7, null) to t + 180_000, DoneSet(85.0, 5, 1) to t + 360_000), 8)
        // The next set 5 kg heavier (within a tenth) pairs too: the unrated short set (failure at 7) carried to 85 kg.
        assertEquals(listOf(RestAdvice.Pair(180 - 21 - 10, 10, 7), RestAdvice.Pair(180 - 15 - 10, 5, 6)), pairs)
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
