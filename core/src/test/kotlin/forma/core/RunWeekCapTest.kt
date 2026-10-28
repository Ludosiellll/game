package forma.core

import forma.core.model.RunGoal
import forma.core.run.ManualRun
import forma.core.run.RunCaps
import forma.core.run.RunLog
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunType
import forma.core.run.RunWorkout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The week cap ([RunPlan.weekCap]) on Denis's runs (1500 m in 5:30, Mon/Thu/Sat on a 230 m lap: two hard sessions and
 * one easy run a week). His first weeks with the app were partial (free runs), and the hard sessions' warm-ups, rests and
 * cool-downs counted as running: Saturday's easy run, his only one, was cut to the 15-minute floor, where it could not
 * grow either (a run shorter than the plan's length is not «full»).
 */
class RunWeekCapTest {
    private val goal = RunGoal(1500, 330)
    private val caps = RunCaps(60, 90)
    private val saturday = 20757L

    /** His running progress after 26.10 as his phone holds it: logs kept before the running in them was counted apart. */
    private val denis = RunState(
        walkStage = -1, easyMinutes = 20, longMinutes = 35, runs = 5, qualityCount = 1, lastRunDay = 20752, easyRpes = listOf(7),
        recent = listOf(RunLog(20738, 24), RunLog(20738, 2), RunLog(20748, 25), RunLog(20749, 17), RunLog(20752, 36)),
    )

    /** The easy run on [day] as the repository plans it: the plan's length, no longer than the week cap. */
    private fun easyOn(st: RunState, day: Long): RunWorkout =
        RunPlan.easy(st.copy(easyMinutes = minOf(st.easyMinutes, RunPlan.weekCap(st, day) ?: st.easyMinutes)), day = day)

    @Test fun hisFirstPartialWeeksDoNotCutHisOnlyEasyRunOfTheWeek() {
        // The busiest week before held 42 minutes (an easy run and a free one); the plan's own week is 2 × 20 + 35. The
        // race-pace session of 26.10 is an old log: all its 36 minutes count, and there is still room.
        assertEquals(75, RunPlan.ownWeek(denis))
        assertEquals(82 - 36, RunPlan.weekCap(denis, saturday))
        val w = easyOn(denis, saturday)
        assertEquals(20 * 60, w.workSeconds, w.title)
    }

    @Test fun theHardSessionsWarmUpsAndRestsDoNotEatHisEasyRun() {
        // His week as it goes on: the race-pace session of 26.10 and Thursday's trial, logged with the running in them.
        var st = denis.copy(runs = 4, qualityCount = 0, lastRunDay = 20749, recent = denis.recent.dropLast(1))
        val race = RunPlan.goalPace(st, goal, 230)
        st = RunPlan.after(st, race, RunResult(completed = 1.0, rpe = 7, durationSec = 2142, workSec = 240, reps = 5), caps, day = 20752, goal = goal).state
        val trial = RunPlan.goalTest(st, goal, 230)
        st = RunPlan.after(st, trial, RunResult(completed = 1.0, rpe = 9, durationSec = 1700, workSec = 300), caps, day = 20755, goal = goal).state
        assertEquals(listOf(RunLog(20752, 36, 4), RunLog(20755, 28, 5)), st.recent.takeLast(2))
        // 64 minutes of hard sessions, 9 of them running: Saturday is his whole easy length.
        val w = easyOn(st, saturday)
        assertEquals(st.easyMinutes * 60, w.workSeconds, "cap ${RunPlan.weekCap(st, saturday)}: ${w.title}")
    }

    @Test fun aWeekOfLongerHardSessionsLeavesTheEasyRunItsLength() {
        // The week after, his log now reaching back over the three weeks: 26.10–1.11 held race pace (36 min, 4 running),
        // the trial (28, 5) and the easy run (20); now a tempo session (40, 15 running) and race pace (38, 5). The busiest
        // week before is the one of 19.10 (42); counted whole it would be 26.10's (84), the hard sessions alone 78 of the
        // 92 the week may hold, and Saturday 15 minutes again.
        val st = denis.copy(
            recent = denis.recent.dropLast(1) + listOf(
                RunLog(20752, 36, 4), RunLog(20755, 28, 5), RunLog(20757, 20, 20),
                RunLog(20759, 40, 15), RunLog(20762, 38, 5),
            ),
        )
        val next = saturday + 7
        assertEquals(46 - 20, RunPlan.weekCap(st, next))
        assertEquals(20 * 60, easyOn(st, next).workSeconds)
    }

    @Test fun aRunnerWhoMissedSessionsComesBackByTenPercentAWeek() {
        // Known for longer than the three weeks, with sessions missed in them: 90, 83 and 100 minutes of running. The plan's
        // own week is 2 × 43 + 60 = 146, but the week comes back by 10 %: 110, of which 83 are run by Sunday.
        val runner = RunState(walkStage = -1, easyMinutes = 43, longMinutes = 60)
        val recent = listOf(
            RunLog(1, 45), RunLog(4, 45),
            RunLog(8, 43), RunLog(11, 40),
            RunLog(14, 38, 10), RunLog(16, 43), RunLog(19, 47),
            RunLog(21, 38, 10), RunLog(22, 43), RunLog(24, 30),
        )
        val st = runner.copy(recent = recent)
        assertEquals(110 - 83, RunPlan.weekCap(st, 27))
        val long = RunPlan.long(st.copy(longMinutes = minOf(st.longMinutes, RunPlan.weekCap(st, 27)!!)), day = 27)
        assertEquals(27 * 60, long.workSeconds, long.title)
    }

    @Test fun aWeekStillGrowsByTenPercentAtMost() {
        // Three weeks of intervals (38 min, 10 of reps), two easy runs of 40 and a long one of 60: 150 minutes of running.
        val week = { mon: Long -> listOf(RunLog(mon, 38, 10), RunLog(mon + 1, 40, 40), RunLog(mon + 3, 40, 40), RunLog(mon + 5, 60, 60)) }
        val runner = RunState(walkStage = -1, easyMinutes = 40, longMinutes = 60)
        val sunday = 27L
        val before = week(0) + week(7) + week(14)
        // This week ran more: the intervals, easy runs of 50, a free run of 30 — 140 of running by Sunday.
        val st = runner.copy(recent = before + listOf(RunLog(21, 38, 10), RunLog(22, 50, 50), RunLog(23, 30, 30), RunLog(24, 50, 50)))
        assertEquals(165 - 140, RunPlan.weekCap(st, sunday))
        val long = RunPlan.long(st.copy(longMinutes = minOf(st.longMinutes, RunPlan.weekCap(st, sunday)!!)), day = sunday)
        assertEquals(25 * 60, long.workSeconds, long.title)
    }

    @Test fun aLittleLoggedIsNotStricterThanNothingLogged() {
        // A runner starting at the base level (25 and 40) who logged a free run of 24 minutes last week and one of 20 this
        // week: the plan's own easy run, as for someone who logged nothing (110 % of last week would leave 6 minutes).
        val start = RunState(walkStage = -1, easyMinutes = 25, longMinutes = 40)
        assertNull(RunPlan.weekCap(start, 100))
        assertEquals(25 * 60, easyOn(start, 100).workSeconds)
        val little = start.copy(recent = listOf(RunLog(91, 24), RunLog(98, 20)))
        assertEquals(99 - 20, RunPlan.weekCap(little, 100))
        assertEquals(25 * 60, easyOn(little, 100).workSeconds)
    }

    @Test fun theRunningOfASession() {
        val st = RunState(walkStage = -1, easyMinutes = 25, vdot = 45.0)
        // An easy run kept going past the plan: all of it is running (the timer counts the plan's 25 minutes only).
        assertEquals(35, RunPlan.runningMinutes(RunPlan.easy(st), RunResult(1.0, 4, 2100, workSec = 1500)))
        val free = RunWorkout(RunType.FREE, "Свободный бег", emptyList())
        assertEquals(17, RunPlan.runningMinutes(free, RunResult(1.0, 5, 17 * 60)))
        // Intervals, 38 minutes: the reps as timed; typed in by hand, the reps' share of the session.
        val iv = RunPlan.intervals(st)
        assertEquals(38 * 60, iv.totalSeconds)
        assertEquals(9, RunPlan.runningMinutes(iv, RunResult(1.0, 8, 38 * 60, workSec = 540)))
        assertEquals(10, RunPlan.runningMinutes(iv, RunPlan.manual(iv, ManualRun(38 * 60, null, 8))))
        // Run/walk: the runs, not the walks between them nor the walk before and after.
        val walk = RunPlan.runWalk(RunState())
        assertEquals(8, RunPlan.runningMinutes(walk, RunResult(1.0, 5, walk.totalSeconds, workSec = 480)))
    }
}
