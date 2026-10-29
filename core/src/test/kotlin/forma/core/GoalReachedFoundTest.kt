package forma.core

import forma.core.model.RunGoal
import forma.core.run.Lap
import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.Vdot
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the rule «the goal is reached only by a time that reaches it» (`run_goal_reached_honest`) found, kept as it was found. */
class GoalReachedFoundTest {
    /**
     * Seed 40721046238, case 1410124 (issue #18): 800 m in 3:02 as the goal, a trial of 4 laps of 205 m pressed in 46,9,
     * 45,9, 47,3 and 46,7 — 186,8 s, shown as 820 m in 3:07. The laps to the tenth reach the goal by a hair, the time
     * shown does not, and «Цель достигнута» stood under a time that does not reach it. A goal is said reached only when
     * the time shown reaches it too: a press is not that exact, the next trial says it beyond doubt.
     */
    @Test fun theGoalIsReachedByTheTimeShown() {
        val goal = RunGoal(800, 182)
        val st = RunState(walkStage = -1, easyMinutes = 43, longMinutes = 34, runs = 190, testSoon = true, lastRunDay = 20978)
        val w = RunPlan.goalTest(st, goal, 205)
        val lap = w.segments.first { it.laps != null }.lapSec!!
        // The laps alone, no time in the field.
        val laps = listOf(46.9, 45.9, 47.3, 46.7).mapIndexed { i, s -> Lap(s, lap * 0.98, lap * 1.02, i + 1, 4, 1) }
        val r = RunResult(completed = 1.0, rpe = 3, durationSec = 1487, laps = laps)
        val a = RunPlan.after(st, w, r, RunCaps(60, 90), 20979, goal)
        val time = assertNotNull(a.outcomes.filterIsInstance<RunOutcome.TestTime>().firstOrNull(), "${a.outcomes}")
        val need = Vdot.fromRace(goal.meters.toDouble(), goal.seconds.toDouble())
        val shown = Vdot.fromRace(time.meters.toDouble(), time.seconds.toDouble())
        assertTrue(shown < need, "the case: ${time.meters} m in ${time.seconds} s against $need")
        assertTrue(RunOutcome.GoalReached !in a.outcomes, "${a.outcomes}")
    }
}
