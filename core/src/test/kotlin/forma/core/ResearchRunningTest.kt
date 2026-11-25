package forma.core

import forma.core.model.RunGoal
import forma.core.run.CustomRun
import forma.core.run.RunCaps
import forma.core.run.RunExplain
import forma.core.run.RunLog
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The running part of the research of 24 November, approved by Denis on 25 November («по 6 пунктам добавляй все кроме
 * первого»). MD-1: with a 1500 m goal far away intervals came one hard run in six (race pace, tempo, race pace, intervals,
 * race pace, tempo), whatever the gap to the goal; now the order goes by how far the fitness is below the goal's own,
 * still counted by hard runs done. hard-session-spike + MD-3: the cap of one run (110 % of the longest of the last 30 days)
 * held for easy and long runs only; now every hard training session is held to it as a whole — not a race or a trial.
 */
class ResearchRunningTest {
    private val goal = RunGoal(1500, 330)
    private val caps = RunCaps(45, 90)
    private val day = 20790L

    /** Denis as on 23 November: no trial counted, the last hard run missed — the estimate is 40 (the goal is about 48.9). */
    private val denis = RunPlan.estimated(
        RunState(walkStage = -1, easyMinutes = 30, qualityCount = 3, qualityFails = 1, runs = 12, lastRunDay = day - 2),
        goal,
    )

    /** A runner whose longest session of the last 30 days is [longest] minutes (Egor in early December: about 25). */
    private fun runner(longest: Int) = RunState(
        walkStage = -1, vdot = 45.0, easyMinutes = 25, longMinutes = 35, runs = 20, lastRunDay = day - 2,
        recent = listOf(
            RunLog(day - 20, longest), RunLog(day - 16, longest - 3), RunLog(day - 13, longest), RunLog(day - 9, longest - 5),
            RunLog(day - 6, longest), RunLog(day - 2, longest - 1),
        ),
    )

    private fun types(st: RunState, counts: IntRange, g: RunGoal = goal, lap: Int? = 205) =
        counts.map { RunPlan.quality(st.copy(qualityCount = it), goal = g, lapMeters = lap).type }

    private fun part(w: RunWorkout, kind: SegmentKind) = w.segments.first { it.kind == kind }.seconds

    // ---------- MD-1: how often intervals come ----------

    @Test fun farFromTheGoalIntervalsComeEveryOtherHardRun() {
        assertEquals(40.0, denis.estVdot)
        // Four hard runs in a row: intervals, tempo, intervals, race pace.
        assertEquals(listOf(RunType.INTERVALS, RunType.TEMPO, RunType.INTERVALS, RunType.GOAL_PACE), types(denis, 1..4))
        // Over eight: intervals four times (one in six before), race pace and tempo two each; race pace stays the first.
        val eight = types(denis, 0..7)
        assertEquals(RunType.GOAL_PACE, eight.first())
        assertEquals(4, eight.count { it == RunType.INTERVALS }, eight.toString())
        assertEquals(2, eight.count { it == RunType.GOAL_PACE }, eight.toString())
        assertEquals(2, eight.count { it == RunType.TEMPO }, eight.toString())
        assertTrue(eight.filterIndexed { i, _ -> i % 2 == 1 }.all { it == RunType.INTERVALS }, eight.toString())
    }

    /** His week: hard runs on Monday (legs on Saturday) and Thursday (fresh), from his count of 23 November. */
    @Test fun denisRunsIntervalsOnMondayAndTempoAndRacePaceByTurnsOnThursday() {
        var st = denis
        val done = mutableListOf<RunType>()
        repeat(6) { i ->
            val monday = i % 2 == 0
            val d = day + (i / 2) * 7 + if (monday) 0 else 3
            val w = RunPlan.quality(st, goal = goal, lapMeters = 205, goalLater = monday, day = d)
            done += w.type
            st = RunPlan.after(st, w, RunResult(1.0, 7, w.totalSeconds), caps, day = d, goal = goal).state
        }
        assertEquals(
            listOf(RunType.INTERVALS, RunType.GOAL_PACE, RunType.INTERVALS, RunType.TEMPO, RunType.INTERVALS, RunType.GOAL_PACE),
            done,
        )
        assertEquals(40.0, st.estVdot, "runs without a measured pace leave the estimate where it was")
        assertNull(st.goalDeferredOn)
    }

    /** Counted the other way round (race pace due on the tired Monday): it still goes to Thursday, intervals every other run. */
    @Test fun racePaceDueOnTheTiredDayStillGoesToTheFreshOne() {
        var st = denis.copy(qualityCount = 0)
        val done = mutableListOf<RunType>()
        repeat(6) { i ->
            val monday = i % 2 == 0
            val d = day + (i / 2) * 7 + if (monday) 0 else 3
            val w = RunPlan.quality(st, goal = goal, lapMeters = 205, goalLater = monday, day = d)
            done += w.type
            st = RunPlan.after(st, w, RunResult(1.0, 7, w.totalSeconds), caps, day = d, goal = goal).state
        }
        assertTrue(done.filterIndexed { i, _ -> i % 2 == 0 }.none { it == RunType.GOAL_PACE }, done.toString())
        assertEquals(3, done.count { it == RunType.INTERVALS }, done.toString())
        assertEquals(2, done.count { it == RunType.GOAL_PACE }, done.toString())
    }

    /** Tempo run where tempo was due is no race pace put off: nothing is owed (the even count said so before). */
    @Test fun onlyRacePaceDueIsOwed() {
        val tempo = RunPlan.quality(denis.copy(qualityCount = 2), goal = goal, lapMeters = 205)
        assertEquals(RunType.TEMPO, tempo.type)
        val a = RunPlan.after(denis.copy(qualityCount = 2), tempo, RunResult(1.0, 7, tempo.totalSeconds), caps, day = day, goal = goal)
        assertNull(a.state.goalDeferredOn)
        // Race pace due on tired legs: intervals today, race pace owed.
        val tired = RunPlan.quality(denis.copy(qualityCount = 0), goal = goal, lapMeters = 205, goalLater = true)
        assertEquals(RunType.INTERVALS, tired.type)
        val b = RunPlan.after(denis.copy(qualityCount = 0), tired, RunResult(1.0, 7, tired.totalSeconds), caps, day = day, goal = goal)
        assertEquals(day, b.state.goalDeferredOn)
        assertEquals(RunType.GOAL_PACE, RunPlan.quality(b.state, goal = goal, lapMeters = 205).type)
    }

    /** A hard day missed only shifts the order: the next hard run is the one that was due. */
    @Test fun aMissedDayOnlyShiftsTheOrder() {
        val due = RunPlan.quality(denis, goal = goal, lapMeters = 205, day = day).type
        val aWeekLater = RunPlan.quality(denis, goal = goal, lapMeters = 205, day = day + 7).type
        assertEquals(due, aWeekLater)
        assertEquals(RunType.INTERVALS, due)
    }

    @Test fun withinThreePointsIntervalsAndRacePaceGoByTurns() {
        val near = denis.copy(estVdot = 47.0)
        val six = types(near, 0..5)
        assertEquals(
            listOf(RunType.GOAL_PACE, RunType.INTERVALS, RunType.GOAL_PACE, RunType.INTERVALS, RunType.GOAL_PACE, RunType.TEMPO),
            six,
        )
        assertEquals(1, six.count { it == RunType.TEMPO })
    }

    @Test fun withinAPointOrARaceEightWeeksAwayRacePaceEveryOtherRun() {
        val close = denis.copy(estVdot = 48.5)
        val six = types(close, 0..5)
        assertTrue(six.filterIndexed { i, _ -> i % 2 == 0 }.all { it == RunType.GOAL_PACE }, six.toString())
        // A trial counted at the goal: the same.
        assertEquals(six, types(RunState(walkStage = -1, vdot = 49.0), 0..5))
        // Far from the goal, but the race is 50 days away: race pace every other run; 70 days away, intervals every other.
        val soon = goal.copy(date = day + 50)
        assertEquals(RunType.GOAL_PACE, RunPlan.hardDue(denis, soon, day, count = 2))
        assertEquals(RunType.TEMPO, RunPlan.hardDue(denis, soon, day, count = 1))
        val later = goal.copy(date = day + 70)
        assertEquals(RunType.INTERVALS, RunPlan.hardDue(denis, later, day, count = 1))
        assertEquals(RunType.TEMPO, RunPlan.hardDue(denis, later, day, count = 2))
    }

    /** No goal or a longer one: intervals and tempo by turns, as before. */
    @Test fun withoutAMiddleDistanceGoalIntervalsAndTempoByTurns() {
        val st = RunState(walkStage = -1, vdot = 45.0)
        assertEquals(listOf(RunType.INTERVALS, RunType.TEMPO, RunType.INTERVALS), types(st, 0..2, RunGoal(10000, 3000), null))
    }

    @Test fun theIntervalsSayTheirLapsAreSlowerThanTheRacePaceRepsOnPurpose() {
        val how = RunExplain.of(RunPlan.intervals(denis), goal).how
        assertTrue("медленнее, чем в отрезках темпа цели" in how, how)
        assertTrue("так и задумано" in how, how)
        assertTrue("темпа цели" !in RunExplain.of(RunPlan.intervals(denis), null).how)
    }

    // ---------- hard-session-spike + MD-3: the cap of one run for hard runs ----------

    /** Longest 25 minutes in 30 days: a tempo or interval session of 27 minutes at most, two reps or more. */
    @Test fun aHardSessionIsNoLongerThanTheCapOfOneRun() {
        val st = runner(25)
        assertEquals(27, RunPlan.singleRunCap(st, day))
        val t = RunPlan.hardSession(st, RunType.TEMPO, day = day)
        assertEquals(RunType.TEMPO, t.type)
        assertTrue(t.totalSeconds <= 27 * 60, "${t.title}: ${t.totalSeconds / 60.0} min")
        // 3 × 5 with 10 and 10 (37 min): 2 × 5, the minutes left to the warm-up first.
        assertEquals(2, t.reps, t.title)
        assertEquals(10 * 60, part(t, SegmentKind.WARMUP))
        assertEquals(6 * 60, part(t, SegmentKind.COOLDOWN))
        assertTrue("27\u00A0мин на всю пробежку, это 110\u00A0% самой длинной за месяц." in t.note, t.note)
        val i = RunPlan.hardSession(st, RunType.INTERVALS, day = day)
        assertEquals(RunType.INTERVALS, i.type)
        assertTrue(i.totalSeconds <= 27 * 60, "${i.title}: ${i.totalSeconds / 60.0} min")
        // 5 × 2 min (38 min): 4 × 2 with 8 and 5.
        assertEquals(4, i.reps, i.title)
        assertEquals("Интервалы · 4 × 2 мин", i.title)
        // The plan's hard day does the same.
        val q = RunPlan.quality(st.copy(qualityCount = 0), day = day)
        assertEquals(i.segments, q.segments)
    }

    @Test fun theCoolDownGoesFirstThenTheWarmUpThenReps() {
        // 5 × 2 min intervals, 10 + 10: 38 minutes.
        fun at(longest: Int) = RunPlan.hardSession(runner(longest), RunType.INTERVALS, day = day)
        assertEquals(38 * 60, at(40).totalSeconds)
        // Cap 36: the cool-down 8.
        at(33).let { w ->
            assertEquals(Triple(10 * 60, 8 * 60, 5), Triple(part(w, SegmentKind.WARMUP), part(w, SegmentKind.COOLDOWN), w.reps))
        }
        // Cap 33: the cool-down at its 5.
        at(30).let { w ->
            assertEquals(Triple(10 * 60, 5 * 60, 5), Triple(part(w, SegmentKind.WARMUP), part(w, SegmentKind.COOLDOWN), w.reps))
        }
        // Cap 31: the warm-up at its 8 too, all reps still there.
        at(29).let { w ->
            assertEquals(Triple(8 * 60, 5 * 60, 5), Triple(part(w, SegmentKind.WARMUP), part(w, SegmentKind.COOLDOWN), w.reps))
            assertEquals(31 * 60, w.totalSeconds)
        }
        // Cap 30: one rep fewer, and the minutes it frees go back to the warm-up first.
        at(28).let { w ->
            assertEquals(Triple(10 * 60, 6 * 60, 4), Triple(part(w, SegmentKind.WARMUP), part(w, SegmentKind.COOLDOWN), w.reps))
        }
    }

    @Test fun whenEvenTwoRepsAreTooLongTheDayIsTheEasyRun() {
        val st = runner(15)
        val w = RunPlan.hardSession(st, RunType.INTERVALS, day = day)
        assertEquals(RunType.EASY, w.type)
        assertTrue(w.note.startsWith("16\u00A0мин на всю пробежку, это 110\u00A0% самой длинной за месяц, поэтому сегодня лёгкий бег вместо тяжёлого."), w.note)
        assertTrue(w.totalSeconds <= 16 * 60)
        // Done, it moves no hard run on: the same one comes next time.
        val a = RunPlan.after(st, w, RunResult(1.0, 4, w.totalSeconds), caps, day = day)
        assertEquals(st.qualityCount, a.state.qualityCount)
    }

    /** Race-pace reps on the 205 m loop: whole laps kept, cut in minutes. */
    @Test fun racePaceRepsAreCutInMinutesOnTheLoop() {
        val st = denis.copy(goalStage = 1, recent = runner(25).recent)
        val full = RunPlan.goalPace(st, goal, 205)
        assertEquals("Темп цели · 8 × 1 круг", full.title)
        val w = RunPlan.hardSession(st, RunType.GOAL_PACE, goal = goal, lapMeters = 205, day = day)
        assertEquals(RunType.GOAL_PACE, w.type)
        assertTrue(w.totalSeconds <= 27 * 60, "${w.title}: ${w.totalSeconds / 60.0} min")
        assertEquals("Темп цели · 6 × 1 круг", w.title)
        assertTrue(w.segments.filter { it.kind == SegmentKind.WORK }.all { it.laps == 1.0 })
        assertTrue(part(w, SegmentKind.WARMUP) >= 8 * 60)
        assertTrue(!w.cutback, "not a lighter week: the step as far as it fits")
    }

    @Test fun withoutADayTheSessionIsAsTheLadderHasIt() {
        val st = runner(25)
        val w = RunPlan.quality(st.copy(qualityCount = 0))
        assertEquals("Интервалы · 5 × 2 мин", w.title)
        assertEquals(38 * 60, w.totalSeconds)
    }

    /** Two runs in the month: the hard work goes by the plan's own week (20 + 20 + 30), not with no limit. */
    @Test fun aShortLogTakesThePlansOwnWeek() {
        val st = RunState(
            walkStage = -1, vdot = 45.0, easyMinutes = 20, longMinutes = 30, intervalStage = 4, runs = 2, lastRunDay = day - 2,
            recent = listOf(RunLog(day - 9, 40), RunLog(day - 2, 45)),
        )
        assertEquals(4, RunPlan.intervals(st).reps, "a ladder's step as written")
        val w = RunPlan.hardSession(st, RunType.INTERVALS, day = day)
        // 7 minutes of a 70-minute week, at least 12: three of the 4-minute reps.
        assertEquals(3, w.reps, w.title)
    }

    @Test fun theCoachsHardRunIsCutTheSameWay() {
        val st = runner(25)
        // «5 по 3 минуты в темпе интервалов, отдых 2 минуты»: 10 + 15 + 8 + 5 = 38 minutes.
        val c = CustomRun(5, 180, null, CustomRun.Pace.INTERVAL, 120, 10, 5)
        val cut = assertNotNull(RunPlan.customWithinCap(st, goal, c, day))
        assertEquals(c.copy(reps = 3, warmupMin = 9, cooldownMin = 5), cut)
        assertTrue(assertNotNull(RunPlan.custom(st, goal, cut)).totalSeconds <= 27 * 60)
        // Within the cap, and an easy run: as asked.
        assertEquals(c, RunPlan.customWithinCap(runner(40), goal, c, day))
        val easy = CustomRun(1, 1800, null, CustomRun.Pace.EASY, 0, 0, 0)
        assertEquals(easy, RunPlan.customWithinCap(st, goal, easy, day))
        // Even two reps too long: refused (null).
        assertNull(RunPlan.customWithinCap(runner(15), goal, c, day))
    }

    @Test fun aRaceAndATrialAreNotBuiltUnderTheCap() {
        // The trial and the race have their 12 and 8 minutes whatever the month held: the cap is for training.
        val st = denis.copy(recent = runner(15).recent)
        val trial = RunPlan.goalTest(st, goal, 205)
        assertEquals(12 * 60, part(trial, SegmentKind.WARMUP))
        assertEquals(8 * 60, part(trial, SegmentKind.COOLDOWN))
        assertEquals(12 * 60, part(RunPlan.race(st, goal), SegmentKind.WARMUP))
    }
}
