package forma.core

import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.run.Cue
import forma.core.run.Fix
import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunTimeline
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.SegmentKind
import forma.core.run.Vdot
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Training for a race on a stadium, the case it was built for: 1500 m in 5:30–5:40 on a 230 m lap, the gym and running on
 * the same three days (Tue, Thu, Sat).
 */
class RaceGoalTest {
    private val goal = RunGoal(1500, 335)
    private val lap = 230
    private val caps = RunCaps(45, 90)

    // ---------- the week ----------

    @Test fun threeDaysHoldThreeLiftsAndThreeRunsWithHardRunsAfterTheUpperBody() {
        val profile = Profile(
            aim = Aim(LiftFocus.MUSCLE, RunFocus.SPEED, Emphasis.BALANCED), experience = Experience.INTERMEDIATE,
            runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), runGoal = goal, lapMeters = lap, doubleDays = true,
        )
        val p = ProgramGenerator.generate(profile, LocalDate.of(2026, 10, 10))
        fun day(d: Int) = p.week.on(d)
        // Tuesday and Saturday: a hard run first (fresh legs), then the upper body; Thursday: legs, then an easy run.
        for (d in listOf(2, 6)) {
            val (run, lift) = day(d)
            assertEquals(SessionKind.LIFT, lift.kind)
            assertTrue(!p.template(lift.templateId!!).legs, "upper body on $d")
            assertEquals(SessionKind.RUN_QUALITY, run.kind)
        }
        val (legs, easy) = day(4)
        assertTrue(p.template(legs.templateId!!).legs)
        assertEquals(SessionKind.RUN_EASY, easy.kind)
        assertTrue(p.week.days.size == 3)
        assertTrue(p.week.notes.any { it.contains("дни верха") }, p.week.notes.toString())
        assertTrue(p.week.notes.any { it.contains("тяжёлый бег — первым") && it.contains("сначала зал") }, p.week.notes.toString())
        // Without shared days the same three days stay two of each.
        val apart = ProgramGenerator.generate(profile.copy(doubleDays = false), LocalDate.of(2026, 10, 10))
        assertEquals(2, apart.week.days.sumOf { d -> d.sessions.count { it.kind == SessionKind.LIFT } })
    }

    // ---------- race pace ----------

    @Test fun racePaceIsInWholeLapsWithTheGoalLapTime() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, lap)
        assertEquals(RunType.GOAL_PACE, w.type)
        assertEquals("Темп цели · 6 × 1 круг", w.title)
        val work = w.segments.filter { it.kind == SegmentKind.WORK }
        assertEquals(6, work.size)
        // 335 s for 1500 m is 51.4 s for 230 m.
        assertEquals(51.4, work.first().lapSec!!, 0.05)
        assertEquals(51, work.first().seconds)
        assertEquals(1.0, work.first().laps)
        assertTrue(w.note.contains("51–52 с"), w.note)
        assertEquals(90, w.segments.first { it.kind == SegmentKind.RECOVERY }.seconds)
        assertEquals(230, w.lapMeters)
        // Further up: two-lap reps with a beep at the first lap; at the top, the race in two parts.
        val two = RunPlan.goalPace(RunState(walkStage = -1, goalStage = 3), goal, lap)
        assertEquals("Темп цели · 5 × 2 круга", two.title)
        val marks = RunTimeline.allCues(two).filterIsInstance<Cue.LapMark>()
        assertEquals(5, marks.size)
        val top = RunPlan.goalPace(RunState(walkStage = -1, goalStage = RunPlan.goalLadderSize - 1), goal, lap)
        assertEquals(listOf(4.0, 2.5), top.segments.filter { it.kind == SegmentKind.WORK }.map { it.laps })
        assertEquals("Темп цели · 4 круга + 2,5 круга", top.title)
        // Outside, without laps: metres.
        assertEquals("Темп цели · 6 × 200 м", RunPlan.goalPace(RunState(walkStage = -1), goal, null).title)
    }

    @Test fun everyOtherHardSessionIsAtRacePace() {
        val types = (0 until 4).map { RunPlan.quality(RunState(vdot = 44.0, walkStage = -1, qualityCount = it), goal = goal, lapMeters = lap).type }
        assertEquals(listOf(RunType.GOAL_PACE, RunType.INTERVALS, RunType.GOAL_PACE, RunType.TEMPO), types)
        // Intervals on the track are whole laps at interval pace.
        val i = RunPlan.quality(RunState(vdot = 44.0, walkStage = -1, qualityCount = 1), goal = goal, lapMeters = lap)
        val rep = i.segments.first { it.kind == SegmentKind.WORK }
        assertEquals(rep.laps!! * rep.lapSec!!, rep.seconds.toDouble(), 1.0)
        assertTrue(i.title.contains("круг"), i.title)
    }

    @Test fun racePaceClimbsFasterWhenMoreIsDoneAndStepsBackAfterTwoMisses() {
        val st = RunState(walkStage = -1, vdot = 44.0)
        val w = RunPlan.goalPace(st, goal, lap)
        fun after(s: RunState, r: RunResult) = RunPlan.after(s, w, r, caps, day = 10, goal = goal)
        val target = goal.pace
        // All six on pace at 7: next step.
        assertEquals(1, after(st, RunResult(1.0, 7, 1800, workPace = target, reps = 6)).state.goalStage)
        // Two extra reps: two steps.
        assertEquals(2, after(st, RunResult(1.0, 7, 1800, workPace = target, reps = 8)).state.goalStage)
        // Clearly slower than the goal pace: repeat, then one step back.
        val slow = after(st.copy(goalStage = 2), RunResult(1.0, 8, 1800, workPace = target * 1.06, reps = 6))
        assertEquals(2, slow.state.goalStage)
        assertTrue(slow.outcomes.contains(RunOutcome.StageRepeat("goal")))
        assertEquals(1, after(slow.state, RunResult(0.6, 9, 1500, reps = 4)).state.goalStage)
        // Race pace is a hard session: it counts in the rotation.
        assertEquals(1, after(st, RunResult(1.0, 7, 1800)).state.qualityCount)
    }

    @Test fun theTopOfTheLadderCallsTheTimeTrialAndTheTrialSetsTheFitness() {
        val top = RunState(walkStage = -1, vdot = 46.0, goalStage = RunPlan.goalLadderSize - 1)
        val w = RunPlan.goalPace(top, goal, lap)
        val done = RunPlan.after(top, w, RunResult(1.0, 8, 1800, workPace = goal.pace, reps = 2), caps, day = 10, goal = goal)
        assertTrue(done.state.testSoon)
        assertTrue(done.outcomes.contains(RunOutcome.TestNext))

        val t = RunPlan.goalTest(done.state, goal, lap)
        assertEquals(RunType.TEST, t.type)
        assertEquals(1495, t.testMeters)
        assertEquals(6.5, t.segments.first { it.kind == SegmentKind.WORK }.laps)
        assertTrue(t.note.contains("с середины круга"), t.note)
        // 1495 m in 5:52: fitness from the time; not the goal yet.
        val trial = RunPlan.after(done.state, t, RunResult(1.0, 9, 1700, testSeconds = 352), caps, day = 20, goal = goal)
        val v = Vdot.fromRace(1495.0, 352.0)
        assertEquals((v * 2).toInt() / 2.0, trial.state.vdot!!, 0.51)
        assertTrue(!trial.state.testSoon)
        assertTrue(trial.outcomes.contains(RunOutcome.TestTime(1495, 352)))
        assertTrue(RunOutcome.GoalReached !in trial.outcomes)
        // 5:30: the goal is reached.
        val fast = RunPlan.after(done.state, t, RunResult(1.0, 9, 1700, testSeconds = 330), caps, day = 20, goal = goal)
        assertTrue(RunOutcome.GoalReached in fast.outcomes)
        // Before anything is known the trial starts at the goal pace plus 8 %.
        val first = RunPlan.goalTest(RunState(walkStage = -1), goal, lap)
        val race = first.segments.first { it.kind == SegmentKind.WORK }
        assertEquals(51.4 * 1.08, race.lapSec!!, 0.1)
        // Room for a slower finish; the beeps still mark the planned laps and the planned finish.
        assertTrue(race.seconds > 6.5 * race.lapSec!! * 1.1)
        val marks = RunTimeline.allCues(first).filterIsInstance<Cue.LapMark>()
        assertEquals(7, marks.size)
        assertEquals(12 * 60 + (6.5 * race.lapSec!!).roundToInt(), marks.last().at)
    }

    @Test fun anEasyRunKeptGoingGrowsByHalfTheExtra() {
        val st = RunState(walkStage = -1, easyMinutes = 30, vdot = 44.0)
        val w = RunPlan.easy(st)
        assertTrue(w.continuable)
        // 42 minutes instead of 30, easily: +6.
        assertEquals(36, RunPlan.after(st, w, RunResult(1.0, 4, 42 * 60), caps, day = 10).state.easyMinutes)
        // As planned: +3.
        assertEquals(33, RunPlan.after(st, w, RunResult(1.0, 4, 30 * 60), caps, day = 10).state.easyMinutes)
    }

    // ---------- the lap button ----------

    @Test fun lapsAreTimedAgainstTheGoalLap() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, lap)
        val t = RunTracker(w)
        t.start(0)
        val warm = 12 * 60 * 1000L
        // First rep (51 s planned): on the line at 52 s — on pace, a moment after the beep.
        val first = assertNotNull(t.lap(warm + 52_000))
        assertEquals(52.0, first.seconds, 1e-9)
        assertEquals(0, first.verdict)
        assertEquals(1, first.rep)
        assertTrue(first.closesRep)
        // Second rep starts after 51 s of work and 90 s of rest: 47 s is too fast, and the rest starts at once.
        val rep2 = warm + (51 + 90) * 1000L
        assertEquals(-1, t.lap(rep2 + 47_000)!!.verdict)
        // Third: 56 s, a bit after the beep — still that rep, too slow. It started 4 s sooner (rep 2 ended early).
        val rep3 = rep2 + (47 + 90) * 1000L
        assertEquals(1, t.lap(rep3 + 56_000)!!.verdict)
        assertEquals(listOf(52.0, 47.0, 56.0), t.laps.map { it.seconds })
        assertEquals(listOf(52.0, 47.0, 56.0, null, null, null), t.repTimes())
        // Half of the reps pressed: the pace comes from the laps.
        val rep4 = rep3 + (51 + 90) * 1000L
        t.lap(rep4 + 51_000)
        val r = t.result(rep4 + 60_000, 7)
        assertEquals((52 + 47 + 56 + 51) / 0.92, r.workPace!!, 0.5)
        assertEquals(listOf(52.0, 47.0, 56.0, 51.0), r.laps!!.map { it.seconds })
        assertEquals(listOf(1, 2, 3, 4), r.laps!!.map { it.rep })
        // Nothing is counted while paused.
        t.pause(rep4 + 70_000)
        assertNull(t.lap(rep4 + 80_000))
    }

    @Test fun theLastLapPressedEarlyStartsTheRecovery() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, lap)
        val t = RunTracker(w)
        t.start(0)
        val warm = 12 * 60 * 1000L
        t.tick(warm + 1000)
        t.lap(warm + 47_000)
        val tl = t.timeline(warm + 47_000)
        assertEquals(SegmentKind.RECOVERY, tl.segment!!.kind)
        assertEquals(90, tl.segmentRemaining)
        // The recovery is announced at once; the skipped end of the rep (its countdown) is not.
        val cues = t.tick(warm + 47_100).map { (it as forma.core.run.RunEvent.Timeline).cue }
        assertTrue(cues.any { it is Cue.SegmentStart && it.segment.kind == SegmentKind.RECOVERY })
        assertTrue(cues.none { it is Cue.Countdown })
        // The rep counts as done in full.
        assertEquals(1, t.repsDone(warm + 48_000))
    }

    @Test fun aDoubleTapIsOnePressAndExtraPressesAreNotLapsOfTheRep() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, lap)
        val t = RunTracker(w)
        t.start(0)
        val warm = 12 * 60 * 1000L
        assertNotNull(t.lap(warm + 52_000))
        assertNull(t.lap(warm + 53_000))
        // Twenty seconds later, still in that rep's grace: the rep is complete, so it is a lap of the recovery.
        val extra = assertNotNull(t.lap(warm + 70_000))
        assertNull(extra.rep)
        assertNull(extra.verdict)
        assertEquals(listOf(52.0, null, null, null, null, null), t.repTimes())
    }

    @Test fun skippedRepsAreNotDoneAndLapsSurviveARestore() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, lap)
        val t = RunTracker(w)
        t.start(0)
        val warm = 12 * 60 * 1000L
        t.lap(warm + 51_000)
        // Rest, then the second rep skipped at its start.
        val rep2 = warm + (51 + 90) * 1000L
        t.skipSegment(rep2 + 1000)
        assertEquals(1, t.repsDone(rep2 + 2000))
        val back = RunTracker.restore(t.snapshot(rep2 + 2000), 0)
        assertEquals(listOf(51.0), back.laps.map { it.seconds })
        assertEquals(1, back.repsDone(0))
        assertEquals(51.0, back.repTimes().first())
    }

    @Test fun theTimeTrialTimeIsThePressThatCompletesItsLaps() {
        val w = RunPlan.goalTest(RunState(walkStage = -1, vdot = 45.0), goal, lap)
        val t = RunTracker(w)
        t.start(0)
        val start = 12 * 60 * 1000L
        // Half a lap, then six laps of 54 s: 7 presses.
        var at = start + 27_000L
        assertEquals(1, t.lap(at)!!.number)
        repeat(6) {
            at += 54_000
            t.lap(at)
        }
        val r = t.result(at + 1000, 9)
        assertEquals(27 + 6 * 54, r.testSeconds)
        // The first half is judged against the planned lap; in the second half faster is fine.
        val laps = r.laps!!
        assertTrue(laps.take(3).all { it.fast != null })
        assertTrue(laps.drop(4).all { it.fast == null && it.slow != null })
        assertEquals(7, laps.last().of)
        assertTrue(laps.last().closesRep)
    }

    @Test fun gpsStillWorksWhenNoLapIsPressed() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, lap)
        val t = RunTracker(w)
        t.start(0)
        t.onFix(Fix(0, 53.9, 27.56, 5f), 0)
        val r = t.result(10_000, 6)
        assertNull(r.laps)
        assertNull(r.testSeconds)
        assertTrue(abs(r.completed) < 1e-9)
    }
}
