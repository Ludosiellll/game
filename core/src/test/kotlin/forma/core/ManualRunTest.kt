package forma.core

import forma.core.model.RunGoal
import forma.core.run.ManualRun
import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunState
import forma.core.run.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A run with a watch and no phone, entered by hand: the plan moves as after a timed one. */
class ManualRunTest {
    private val caps = RunCaps(60, 110)
    private val goal = RunGoal(1500, 335)
    private val lapM = 230

    @Test fun anEasyRunCountsItsTime() {
        val st = RunState(walkStage = -1, vdot = 45.0, easyMinutes = 30)
        val w = RunPlan.easy(st)
        // As planned and easy: the next one is longer.
        val r = RunPlan.manual(w, ManualRun(durationSec = 31 * 60, distanceM = 5600.0, rpe = 4))
        assertEquals(1.0, r.completed)
        val after = RunPlan.after(st, w, r, caps, day = 100)
        assertTrue(after.outcomes.any { it is RunOutcome.Longer }, after.outcomes.toString())
        // Cut short: not complete, the length stays.
        val short = RunPlan.manual(w, ManualRun(durationSec = 15 * 60, distanceM = null, rpe = 4))
        assertEquals(0.5, short.completed, 1e-9)
        assertTrue(RunPlan.after(st, w, short, caps, day = 100).outcomes.none { it is RunOutcome.Longer })
    }

    @Test fun goalPaceRepsWithTheirAverageLap() {
        val st = RunState(walkStage = -1, vdot = 45.0, goalStage = 2)
        val w = RunPlan.onTrack(RunPlan.goalPace(st, goal, lapM), lapM)
        val lapPace = { sec: Double -> sec / lapM * 1000 }
        // All reps at 52 s a lap (the goal is ~51.4): on pace, the ladder goes up.
        val ok = RunPlan.manual(w, ManualRun(null, null, rpe = 7, reps = w.reps, workPace = lapPace(52.0)))
        assertEquals(w.totalSeconds, ok.durationSec)
        val up = RunPlan.after(st, w, ok, caps, day = 100, goal = goal)
        assertTrue(up.outcomes.any { it is RunOutcome.StageUp }, up.outcomes.toString())
        // Laps of 55 s are not the goal pace: the step repeats.
        val slow = RunPlan.after(st, w, RunPlan.manual(w, ManualRun(null, null, rpe = 7, reps = w.reps, workPace = lapPace(55.0))), caps, day = 100, goal = goal)
        assertTrue(slow.outcomes.any { it is RunOutcome.StageRepeat }, slow.outcomes.toString())
        // Two reps short: not done.
        val fewer = RunPlan.manual(w, ManualRun(null, null, rpe = 6, reps = w.reps - 2, workPace = lapPace(52.0)))
        assertTrue(fewer.completed < 1.0)
        assertTrue(RunPlan.after(st, w, fewer, caps, day = 100, goal = goal).outcomes.none { it is RunOutcome.StageUp })
        // Without the lap time the effort decides, and the result says the pace is unknown.
        val noPace = RunPlan.after(st, w, RunPlan.manual(w, ManualRun(null, null, rpe = 7, reps = w.reps)), caps, day = 100, goal = goal)
        assertTrue(noPace.outcomes.any { it is RunOutcome.PaceUnknown })
        assertEquals(51.4, Track.lapSec(goal.pace, lapM), 0.1)
    }

    @Test fun aTrialTimedOnTheWatchCounts() {
        val st = RunState(walkStage = -1, vdot = 45.0, goalStage = 8)
        val w = RunPlan.goalTest(st, goal, lapM)
        val m = assertNotNull(w.testMeters)
        val r = RunPlan.manual(w, ManualRun(null, null, rpe = 9, testSeconds = 5 * 60 + 38))
        val after = RunPlan.after(st, w, r, caps, day = 100, goal = goal)
        assertTrue(after.outcomes.contains(RunOutcome.TestTime(m, 338)), after.outcomes.toString())
        assertTrue(after.outcomes.any { it is RunOutcome.VdotChanged })
        // No time: not counted, as with the timer.
        val none = RunPlan.after(st, w, RunPlan.manual(w, ManualRun(null, null, rpe = 9)), caps, day = 100, goal = goal)
        assertTrue(none.outcomes.contains(RunOutcome.TestNotCounted), none.outcomes.toString())
    }

    @Test fun theTwelveMinuteTestTakesItsDistance() {
        val st = RunState(walkStage = -1)
        val w = RunPlan.test(st)
        val r = RunPlan.manual(w, ManualRun(durationSec = 30 * 60, distanceM = 6000.0, rpe = 9, testDistanceM = 2800.0))
        val after = RunPlan.after(st, w, r, caps, day = 100)
        val v = after.outcomes.filterIsInstance<RunOutcome.VdotChanged>().single()
        // The fitness of 2800 m in the test's twelve minutes, as from the timer.
        assertEquals(Math.round(forma.core.run.Vdot.fromRace(2800.0, 720.0) * 2) / 2.0, v.to)
    }
}
