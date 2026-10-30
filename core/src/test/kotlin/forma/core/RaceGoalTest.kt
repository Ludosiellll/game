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
import forma.core.run.Press
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

    @Test fun aRaceOfUpToThreeKilometresDecidesTheRunningWhateverTheFocusIsCalled() {
        // Denis picked "endurance" (he runs out of breath) with 1500 m as the goal: race pace every week, no long run.
        val denis = Profile(
            aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
            runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6, 7), runGoal = RunGoal(1500, 330), lapMeters = lap, doubleDays = true,
        )
        fun runs(p: forma.core.plan.Program, k: SessionKind) = p.week.days.sumOf { d -> d.sessions.count { it.kind == k } }
        for (focus in listOf(RunFocus.ENDURANCE, RunFocus.HEALTH, RunFocus.SPEED)) {
            val p = ProgramGenerator.generate(denis.copy(aim = denis.aim.copy(run = focus)), LocalDate.of(2026, 10, 12))
            assertEquals(2, runs(p, SessionKind.RUN_QUALITY), "$focus")
            assertEquals(0, runs(p, SessionKind.RUN_LONG), "$focus")
            assertTrue(p.runCaps.longMax <= 90, "$focus: ${p.runCaps}")
            // What he chose stays his: only the running is planned by the goal.
            assertEquals(focus, p.aim.run)
        }
        // Without a goal (or with a 10 km one) endurance still means the long run.
        for (g in listOf(null, RunGoal(10_000, 2700))) {
            val p = ProgramGenerator.generate(denis.copy(runGoal = g), LocalDate.of(2026, 10, 12))
            assertEquals(1, runs(p, SessionKind.RUN_LONG), "$g")
        }
    }

    // ---------- race pace ----------

    @Test fun theRacePaceLadderNeverGetsEasierOnAnyTrack() {
        // Steps 3, 5 and 7 lengthen the reps (400, 600, 800 m for 1500): on the track too, never the same length — whole
        // laps, or the metres by time where no whole lap count fits.
        for (track in listOf(200, 230, 250, 300, 333, 400, 450)) {
            val reps = (0 until RunPlan.goalLadderSize - 1).map { stage ->
                val s = RunPlan.goalPace(RunState(walkStage = -1, goalStage = stage), goal, track).segments.first { it.kind == SegmentKind.WORK }
                s.laps?.let { it * track } ?: (s.seconds * 1000.0 / s.pace!!.let { (it.fast + it.slow) / 2.0 })
            }
            assertTrue(reps.zipWithNext().all { (a, b) -> b >= a - 2 }, "$track m: $reps")
            for (longer in listOf(3, 5, 7)) assertTrue(reps[longer] > reps[longer - 1] + 2, "$track m: $reps")
            assertTrue(reps.withIndex().all { (i, m) -> m <= listOf(200, 200, 200, 400, 400, 600, 600, 800)[i] * 1.25 + 1 }, "$track m: $reps")
        }
        // On 230 m: 600 m is 3 laps, 800 m is 4 (920 m), not 3 again — from the line, not half a lap before it.
        val two = RunPlan.goalPace(RunState(walkStage = -1, goalStage = 7), goal, lap)
        assertEquals("Темп цели · 2 × 4 круга", two.title)
        assertTrue("середины" !in two.note, two.note)
    }

    @Test fun racePaceIsInWholeLapsWithTheGoalLapTime() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, lap)
        assertEquals(RunType.GOAL_PACE, w.type)
        assertEquals("Темп цели · 5 × 1 круг", w.title)
        val work = w.segments.filter { it.kind == SegmentKind.WORK }
        assertEquals(5, work.size)
        // 335 s for 1500 m is 51.4 s for 230 m.
        assertEquals(51.4, work.first().lapSec!!, 0.05)
        assertEquals(51, work.first().seconds)
        assertEquals(1.0, work.first().laps)
        assertTrue(w.note.contains("51–52 с"), w.note)
        assertEquals(105, w.segments.first { it.kind == SegmentKind.RECOVERY }.seconds)
        assertEquals(230, w.lapMeters)
        // Further up: two-lap reps with a beep at the first lap; at the top, the race in two parts.
        val two = RunPlan.goalPace(RunState(walkStage = -1, goalStage = 3), goal, lap)
        assertEquals("Темп цели · 4 × 2 круга", two.title)
        val marks = RunTimeline.allCues(two).filterIsInstance<Cue.LapMark>()
        assertEquals(4, marks.size)
        val top = RunPlan.goalPace(RunState(walkStage = -1, goalStage = RunPlan.goalLadderSize - 1), goal, lap)
        assertEquals(listOf(4.0, 3.0), top.segments.filter { it.kind == SegmentKind.WORK }.map { it.laps })
        assertEquals("Темп цели · 4 круга + 3 круга", top.title)
        // Outside, without laps: metres.
        assertEquals("Темп цели · 6 × 200 м", RunPlan.goalPace(RunState(walkStage = -1), goal, null).title)
    }

    @Test fun everyOtherHardSessionIsAtRacePace() {
        val types = (0 until 4).map { RunPlan.quality(RunState(vdot = 44.0, walkStage = -1, qualityCount = it), goal = goal, lapMeters = lap).type }
        assertEquals(listOf(RunType.GOAL_PACE, RunType.TEMPO, RunType.GOAL_PACE, RunType.INTERVALS), types)
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
        // The reps' own pace: a step toward the goal while the form is below it.
        val target = w.segments.first { it.kind == SegmentKind.WORK }.pace!!.let { (it.fast + it.slow) / 2.0 }
        // All reps on pace at 7: next step.
        assertEquals(1, after(st, RunResult(1.0, 7, 1800, workPace = target, reps = w.reps)).state.goalStage)
        // Two extra reps: two steps.
        assertEquals(2, after(st, RunResult(1.0, 7, 1800, workPace = target, reps = w.reps + 2)).state.goalStage)
        // Clearly slower than the goal pace: repeat, then one step back.
        val slow = after(st.copy(goalStage = 2), RunResult(1.0, 8, 1800, workPace = target * 1.06, reps = w.reps))
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
        // 7 laps from the line: 1610 m.
        assertEquals(1610, t.testMeters)
        assertEquals(7.0, t.segments.first { it.kind == SegmentKind.WORK }.laps)
        assertTrue("середины" !in t.note, t.note)
        // 1610 m in 6:18: fitness from the time; not the goal yet.
        val trial = RunPlan.after(done.state, t, RunResult(1.0, 9, 1700, testSeconds = 378), caps, day = 20, goal = goal)
        val v = Vdot.fromRace(1610.0, 378.0)
        assertEquals((v * 2).toInt() / 2.0, trial.state.vdot!!, 0.51)
        assertTrue(!trial.state.testSoon)
        assertTrue(trial.outcomes.contains(RunOutcome.TestTime(1610, 378)))
        assertTrue(RunOutcome.GoalReached !in trial.outcomes)
        // 5:55 for 1610 m (1500 m in about 5:29): the goal is reached.
        val fast = RunPlan.after(done.state, t, RunResult(1.0, 9, 1700, testSeconds = 355), caps, day = 20, goal = goal)
        assertTrue(RunOutcome.GoalReached in fast.outcomes)
        // Before anything is known the trial starts at the goal pace plus 8 %.
        val first = RunPlan.goalTest(RunState(walkStage = -1), goal, lap)
        val race = first.segments.first { it.kind == SegmentKind.WORK }
        assertEquals(51.4 * 1.08, race.lapSec!!, 0.1)
        // Room for a slower finish; the beeps still mark the planned laps and the planned finish.
        assertTrue(race.seconds > 7 * race.lapSec!! * 1.1)
        val marks = RunTimeline.allCues(first).filterIsInstance<Cue.LapMark>()
        assertEquals(7, marks.size)
        assertEquals(12 * 60 + (7 * race.lapSec!!).roundToInt(), marks.last().at)
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
        // On the line as the warm-up ends: the start.
        assertEquals(Press.Start, t.press(warm))
        // First rep (51 s planned): on the line at 52 s — on pace, a moment after the beep.
        val first = assertNotNull(t.lap(warm + 52_000))
        assertEquals(52.0, first.seconds, 1e-9)
        assertEquals(0, first.verdict)
        assertEquals(1, first.rep)
        assertTrue(first.closesRep)
        // The second rep waits on the line after 51 s of work and the rest, and starts with his press: 47 s is too fast,
        // and the rest starts at once.
        val rep2 = warm + (51 + 105) * 1000L
        assertEquals(Press.Start, t.press(rep2))
        assertEquals(-1, t.lap(rep2 + 47_000)!!.verdict)
        // Third: 56 s, a bit after the beep — still that rep, too slow. It waits 4 s sooner (rep 2 ended early).
        val rep3 = rep2 + (47 + 105) * 1000L
        assertEquals(Press.Start, t.press(rep3))
        assertEquals(1, t.lap(rep3 + 56_000)!!.verdict)
        assertEquals(listOf(52.0, 47.0, 56.0), t.laps.map { it.seconds })
        assertEquals(listOf(52.0, 47.0, 56.0, null, null), t.repTimes())
        // Half of the reps pressed: the pace comes from the laps.
        val rep4 = rep3 + (51 + 105) * 1000L
        assertEquals(Press.Start, t.press(rep4))
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
        assertEquals(Press.Start, t.press(warm))
        t.tick(warm + 1000)
        t.lap(warm + 47_000)
        val tl = t.timeline(warm + 47_000)
        assertEquals(SegmentKind.RECOVERY, tl.segment!!.kind)
        assertEquals(105, tl.segmentRemaining)
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
        assertEquals(Press.Start, t.press(warm))
        assertNotNull(t.lap(warm + 52_000))
        assertNull(t.lap(warm + 53_000))
        // Eighteen seconds later: 230 m in 18 s is no lap at all (the volume key pressed twice made such ones).
        assertNull(t.lap(warm + 70_000))
        // Forty seconds later, still in that rep's grace: the rep is complete, so it is a lap of the recovery.
        val extra = assertNotNull(t.lap(warm + 92_000))
        assertNull(extra.rep)
        assertNull(extra.verdict)
        assertEquals(listOf(52.0, null, null, null, null), t.repTimes())
    }

    @Test fun skippedRepsAreNotDoneAndLapsSurviveARestore() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, lap)
        val t = RunTracker(w)
        t.start(0)
        val warm = 12 * 60 * 1000L
        assertEquals(Press.Start, t.press(warm))
        t.lap(warm + 51_000)
        // Rest, then the second rep skipped at its start.
        val rep2 = warm + (51 + 105) * 1000L
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
        assertEquals(Press.Start, t.press(start))
        // Seven laps of 54 s from the line: 7 presses.
        var at = start + 54_000L
        assertEquals(1, t.lap(at)!!.number)
        repeat(6) {
            at += 54_000
            t.lap(at)
        }
        val r = t.result(at + 1000, 9)
        assertEquals(7 * 54, r.testSeconds)
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

    // ---------- after the review ----------

    @Test fun aPressOnTheStartLineIsTheStartNotALap() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, lap)
        val t = RunTracker(w)
        t.start(0)
        val warm = 12 * 60 * 1000L
        // On the line as the rep begins: the start; the lap counts from it and closes at the finish.
        assertEquals(Press.Start, t.press(warm + 800))
        assertEquals(SegmentKind.WORK, t.timeline(warm + 2_000).segment!!.kind)
        val l = assertNotNull(t.lap(warm + 52_300))
        assertEquals(51.5, l.seconds, 1e-9)
        assertTrue(l.closesRep)
        assertEquals(51.5, t.repTimes().first()!!, 1e-9)
    }

    @Test fun aDoubleTapAfterAnEarlyFinishIsStillOneTap() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, lap)
        val t = RunTracker(w)
        t.start(0)
        val warm = 12 * 60 * 1000L
        assertEquals(Press.Start, t.press(warm))
        assertNotNull(t.lap(warm + 40_000))
        // The rep ended early and the plan jumped to the rest; the second tap 0.3 s later is still a double tap.
        assertEquals(Press.Ignored, t.press(warm + 40_300))
        assertEquals(1, t.laps.size)
    }

    @Test fun aSkippedRepTakesNoLaps() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, lap)
        val t = RunTracker(w)
        t.start(0)
        val warm = 12 * 60 * 1000L
        t.skipSegment(warm + 1_000)
        // Half a minute into the rest: not a lap of the skipped rep.
        val l = assertNotNull(t.lap(warm + 1_000 + 30_000))
        assertNull(l.rep)
        assertEquals(listOf(null, null, null, null, null), t.repTimes())
        assertEquals(0, t.repsDone(warm + 20_000))
    }

    // ---------- what moves the ladders ----------

    @Test fun repsFasterThanTheGoalAreOneStepAndAHintNotTwoSteps() {
        val st = RunState(walkStage = -1, vdot = 45.0, goalStage = 2, runs = 4)
        val w = RunPlan.goalPace(st, goal, lap)
        val target = w.segments.first { it.kind == SegmentKind.WORK }.pace!!.let { (it.fast + it.slow) / 2.0 }
        // 2.5 % faster at a moderate effort: one step, and "keep the lap time".
        val fast = RunPlan.after(st, w, RunResult(1.0, 7, w.totalSeconds, workPace = target * 0.975), caps, day = 30, goal = goal)
        assertEquals(3, fast.state.goalStage)
        assertTrue(fast.outcomes.contains(RunOutcome.TooFast))
        // An extra rep at the goal pace: two steps.
        val more = RunPlan.after(st, w, RunResult(1.0, 7, w.totalSeconds, workPace = target, reps = w.reps + 1), caps, day = 30, goal = goal)
        assertEquals(4, more.state.goalStage)
        // Not measured at all (no laps, no GPS): it moves on effort, and says the pace is unknown.
        val blind = RunPlan.after(st, w, RunResult(1.0, 7, w.totalSeconds), caps, day = 30, goal = goal)
        assertEquals(3, blind.state.goalStage)
        assertTrue(blind.outcomes.contains(RunOutcome.PaceUnknown))
    }

    @Test fun aLighterWeekMovesNoLadder() {
        val st = RunState(walkStage = -1, vdot = 45.0, goalStage = 4, intervalStage = 3, tempoStage = 2, runs = 10)
        val g = RunPlan.goalPace(st, goal, lap, cutback = true)
        assertTrue(g.cutback)
        val ga = RunPlan.after(st, g, RunResult(1.0, 7, g.totalSeconds, workPace = goal.pace), caps, day = 40, goal = goal)
        assertEquals(4, ga.state.goalStage)
        assertTrue(ga.outcomes.contains(RunOutcome.LighterWeek("goal")))
        for (w in listOf(RunPlan.intervals(st, cutback = true), RunPlan.tempo(st, cutback = true))) {
            assertTrue(w.cutback, w.title)
            val a = RunPlan.after(st, w, RunResult(1.0, w.targetRpe, w.totalSeconds), caps, day = 40)
            assertEquals(st.intervalStage, a.state.intervalStage)
            assertEquals(st.tempoStage, a.state.tempoStage)
        }
        // The full session moves it as before.
        val full = RunPlan.goalPace(st, goal, lap)
        assertTrue(!full.cutback)
        val fullTarget = full.segments.first { it.kind == SegmentKind.WORK }.pace!!.let { (it.fast + it.slow) / 2.0 }
        assertEquals(5, RunPlan.after(st, full, RunResult(1.0, 7, full.totalSeconds, workPace = fullTarget), caps, day = 40, goal = goal).state.goalStage)
    }

    @Test fun theFormRisesOnlyWhenHardRunsAreOnPaceWithEffortToSpareForAMonth() {
        val st = RunState(walkStage = -1, vdot = 46.0, intervalStage = 2, runs = 10)
        val w = RunPlan.intervals(st)
        val target = RunPlan.paces(st)!!.interval.toDouble()
        fun three(pace: Double, rpe: Int, goal: RunGoal? = null, gap: Long = 7): RunState {
            var s = st
            for (k in 1..3) s = RunPlan.after(s, w, RunResult(1.0, rpe, w.totalSeconds, workPace = pace), caps, day = k * gap, goal = goal).state
            return s
        }
        // 2 % slower than asked, at the planned effort: the paces stay (it used to say "the form grew").
        assertEquals(46.0, three(target * 1.02, 8).vdot)
        // On pace but at the limit: they stay too.
        assertEquals(46.0, three(target, 8).vdot)
        // On pace with effort to spare, three times: one point up.
        assertEquals(47.0, three(target - 2, 7).vdot)
        // Training for 1500 m the time trial measures the form instead.
        assertEquals(46.0, three(target - 2, 7, goal).vdot)
        // At most once in four weeks.
        val raised = RunState(walkStage = -1, vdot = 46.0, intervalStage = 2, runs = 10, qualityOnPace = 2, lastVdotUpDay = 10)
        assertEquals(46.0, RunPlan.after(raised, w, RunResult(1.0, 7, w.totalSeconds, workPace = target - 2), caps, day = 30).state.vdot)
        assertEquals(47.0, RunPlan.after(raised, w, RunResult(1.0, 7, w.totalSeconds, workPace = target - 2), caps, day = 38).state.vdot)
    }

    @Test fun onATrackGpsDoesNotJudgeThePace() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, lap)
        val t = RunTracker(w)
        t.start(0)
        // GPS on a small loop, no lap pressed: no pace from it.
        var lat = 53.9
        for (sec in 0..1500) {
            lat += 4.0 / 111_195.0
            t.onFix(Fix(sec * 1000L, lat, 27.56, 4f), sec * 1000L)
        }
        assertNull(t.result(1_500_000, 7).workPace)
    }

    // ---------- waiting on the line ----------

    @Test fun afterTheWarmUpThePlanWaitsForTheStartPressWhereverTheRunnerIs() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, lap)
        val t = RunTracker(w)
        t.start(0)
        val warm = 12 * 60 * 1000L
        // A minute before: the coach says to finish the lap; at the end no countdown, but "go to the line".
        val cues = t.tick(warm).map { (it as forma.core.run.RunEvent.Timeline).cue }
        assertTrue(cues.any { it is Cue.MinuteLeft })
        assertEquals(1, cues.count { it is Cue.ToLine })
        assertTrue(cues.none { it is Cue.Countdown })
        // Half a minute to get to the line: the plan stands still, nothing is said, the rep has not begun.
        assertTrue(t.waiting(warm + 30_000))
        assertTrue(t.tick(warm + 30_000).isEmpty())
        assertEquals(SegmentKind.WARMUP, t.timeline(warm + 30_000).segment!!.kind)
        assertEquals(0, t.workSec(warm + 30_000))
        // A press that late is the start, not a 30-second lap; the rep and its beeps go from it.
        assertEquals(Press.Start, t.press(warm + 30_000))
        assertTrue(!t.waiting(warm + 30_000))
        val started = t.tick(warm + 31_000).map { (it as forma.core.run.RunEvent.Timeline).cue }
        assertTrue(started.any { it is Cue.SegmentStart && it.segment.kind == SegmentKind.WORK })
        val l = assertNotNull(t.lap(warm + 30_000 + 52_000))
        assertEquals(52.0, l.seconds, 1e-9)
        assertEquals(1, l.rep)
        assertEquals(0, l.verdict)
        // The second rep comes 90 s after the first ended early (51 s planned): on the clock as before.
        assertEquals(SegmentKind.RECOVERY, t.timeline(warm + 30_000 + 60_000).segment!!.kind)
        // The wait is time spent, not work done, and it survives a restore.
        val back = RunTracker.restore(t.snapshot(warm + 90_000), 0)
        assertEquals(listOf(52.0), back.laps.map { it.seconds })
        assertTrue(!back.waiting(0))
        assertEquals(t.timeline(warm + 90_000).index, back.timeline(0).index)
    }

    @Test fun theTimeTrialStartsWithThePressHoweverLateItIs() {
        val w = RunPlan.goalTest(RunState(walkStage = -1, vdot = 45.0), goal, lap)
        val t = RunTracker(w)
        t.start(0)
        val warm = 12 * 60 * 1000L
        // 45 s to walk to the line.
        assertEquals(Press.Start, t.press(warm + 45_000))
        var at = warm + 45_000 + 54_000L
        assertEquals(1, t.lap(at)!!.number)
        repeat(6) {
            at += 54_000
            t.lap(at)
        }
        assertEquals(7 * 54, t.result(at + 1000, 9).testSeconds)
    }

    @Test fun waitingCanBeSkippedAndSurvivesARestore() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, lap)
        val warm = 12 * 60 * 1000L
        // Brought back while waiting: still waiting, and the first press is the start.
        val t = RunTracker(w)
        t.start(0)
        val back = RunTracker.restore(t.snapshot(warm + 20_000), 0)
        back.resume(0)
        assertTrue(back.waiting(1_000))
        assertEquals(Press.Start, back.press(1_000))
        // Skipping while waiting skips that rep: the recovery after it begins, the rep is not done.
        val s = RunTracker(w)
        s.start(0)
        s.skipSegment(warm + 20_000)
        assertTrue(!s.waiting(warm + 20_000))
        assertEquals(SegmentKind.RECOVERY, s.timeline(warm + 21_000).segment!!.kind)
        assertEquals(0, s.repsDone(warm + 60_000))
        assertEquals(listOf(1), s.snapshot(warm + 60_000).skippedReps)
        // Off a stadium nothing waits: the timed plan goes on by itself.
        val road = RunTracker(RunPlan.goalPace(RunState(walkStage = -1), goal, null))
        road.start(0)
        assertTrue(!road.waiting(warm + 10_000))
        assertEquals(SegmentKind.WORK, road.timeline(warm + 10_000).segment!!.kind)
    }

    @Test fun aTypoInTheTrialTimeChangesNothing() {
        val st = RunState(walkStage = -1, vdot = 46.0)
        val t = RunPlan.goalTest(st, goal, lap)
        // "552" read as 552 minutes, and "55:20": no fitness from that.
        for (bad in listOf(33_120, 3_320)) {
            val r = RunPlan.after(st, t, RunResult(1.0, 9, 1700, testSeconds = bad), caps, day = 20, goal = goal)
            assertEquals(46.0, r.state.vdot)
            assertTrue(r.outcomes.none { it is RunOutcome.VdotChanged })
        }
    }

    @Test fun theGoalIsJudgedOnTheExactTime() {
        val st = RunState(walkStage = -1, vdot = 46.0)
        val t = RunPlan.goalTest(st, goal, lap)
        // 1610 m in 6:01 is as good as 1500 m in 5:34.
        val r = RunPlan.after(st, t, RunResult(1.0, 9, 1700, testSeconds = 361), caps, day = 20, goal = goal)
        assertTrue(RunOutcome.GoalReached in r.outcomes)
        // 6:03 is not (1500 m in 5:36).
        val slower = RunPlan.after(st, t, RunResult(1.0, 9, 1700, testSeconds = 363), caps, day = 20, goal = goal)
        assertTrue(RunOutcome.GoalReached !in slower.outcomes)
    }

    @Test fun fewerRepsOrRepsCutShortDoNotMoveTheLadder() {
        val st = RunState(walkStage = -1, vdot = 44.0, intervalStage = 2)
        val w = RunPlan.intervals(st)
        // The timer saw them all, the runner says one fewer.
        val fewer = RunPlan.after(st, w, RunResult(1.0, 7, 2400, reps = w.reps - 1), caps, day = 10)
        assertEquals(2, fewer.state.intervalStage)
        // Every rep skipped after half of it: not done, whatever the count.
        val cut = RunPlan.after(st, w, RunResult(0.55, 7, 2000, reps = w.reps), caps, day = 10)
        assertEquals(2, cut.state.intervalStage)
        // All of them: a step up.
        assertEquals(3, RunPlan.after(st, w, RunResult(1.0, 7, 2400, reps = w.reps), caps, day = 10).state.intervalStage)
    }

    @Test fun theTopOfTheLadderCallsATrialAtMostEveryThreeWeeks() {
        val top = RunState(walkStage = -1, vdot = 46.0, goalStage = RunPlan.goalLadderSize - 1, lastTestDay = 100)
        val w = RunPlan.goalPace(top, goal, lap)
        val ok = RunResult(1.0, 8, 1800, workPace = goal.pace, reps = 2)
        // Ten days after a trial: hold the top, no trial yet.
        val soon = RunPlan.after(top, w, ok, caps, day = 110, goal = goal)
        assertTrue(!soon.state.testSoon)
        assertTrue(RunOutcome.TrialLater in soon.outcomes)
        // Three weeks after it: the trial.
        assertTrue(RunPlan.after(top, w, ok, caps, day = 121, goal = goal).state.testSoon)
        // A trial short of the goal after the whole ladder: back to the 400s (not the longest reps) to build up again.
        val trial = RunPlan.goalTest(top.copy(testSoon = true), goal, lap)
        val after = RunPlan.after(top.copy(testSoon = true), trial, RunResult(1.0, 9, 1700, testSeconds = 378), caps, day = 125, goal = goal)
        assertEquals(RunPlan.goalLadderSize - 6, after.state.goalStage)
        assertTrue(!after.state.testSoon)
    }

    @Test fun aLongBreakStepsTheRacePaceLadderBackAndDropsTheTrial() {
        val st = RunState(walkStage = -1, vdot = 46.0, goalStage = 8, testSoon = true, lastRunDay = 100, runs = 20)
        val back = RunPlan.withBreak(st, 170)
        assertTrue(back.goalStage < 8)
        assertTrue(!back.testSoon)
        // A short one keeps the trial.
        assertTrue(RunPlan.withBreak(st, 110).testSoon)
    }

    @Test fun theTwelveMinuteTestClearsATrialCall() {
        val st = RunState(walkStage = -1, vdot = 44.0, testSoon = true)
        val t = RunPlan.test(st)
        val r = RunPlan.after(st, t, RunResult(1.0, 9, t.totalSeconds, distanceM = 3000.0, workDistanceM = 2600.0, workSec = 720), caps, day = 30)
        assertTrue(!r.state.testSoon)
    }

    @Test fun onA400MetreTrackShortRepsAreTimedAndTheTrialIsWholeLaps() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, 400)
        // 200 m reps: no whole lap fits (a lap is twice as long), so they stay timed — never half a lap from a mid-lap start.
        assertTrue(w.segments.filter { it.kind == SegmentKind.WORK }.all { it.laps == null && it.seconds in 40..50 }, w.title)
        val top = RunPlan.goalPace(RunState(walkStage = -1, goalStage = RunPlan.goalLadderSize - 1), goal, 400)
        assertEquals(listOf(3.0, 1.0), top.segments.filter { it.kind == SegmentKind.WORK }.map { it.laps })
        val trial = RunPlan.goalTest(RunState(walkStage = -1), goal, 400)
        assertEquals(1600, trial.testMeters)
        assertEquals(4.0, trial.segments.first { it.kind == SegmentKind.WORK }.laps)
        assertTrue("четверти" !in trial.note, trial.note)
        // A long loop: a 200 m rep no lap count fits stays timed.
        val loop = RunPlan.goalPace(RunState(walkStage = -1), goal, 1000)
        assertTrue(loop.segments.filter { it.kind == SegmentKind.WORK }.all { it.laps == null && it.seconds in 40..50 })
        // An 800 m goal on a 640 m loop: the top step is the race in one go, never a rep of no laps.
        val short = RunPlan.goalPace(RunState(walkStage = -1, goalStage = RunPlan.goalLadderSize - 1), RunGoal(800, 150), 640)
        assertTrue(short.segments.filter { it.kind == SegmentKind.WORK }.all { (it.laps ?: 1.0) >= 1.0 && it.seconds > 0 })
    }

    @Test fun sharedDaysKeepThePrioritysWeek() {
        // Running first, five days: still one or two lifts, not four.
        val runner = Profile(
            aim = Aim(LiftFocus.STRENGTH, RunFocus.ENDURANCE, Emphasis.RUN), experience = Experience.INTERMEDIATE,
            runLevel = RunLevel.TRAINED, trainingDays = setOf(1, 2, 3, 5, 6), doubleDays = true,
        )
        val p = ProgramGenerator.generate(runner, LocalDate.of(2026, 10, 10))
        assertTrue(p.week.days.sumOf { d -> d.sessions.count { it.kind == SessionKind.LIFT } } <= 2)
        // Easy runs only: full-body sessions stay (legs three times a week), no upper / lower split.
        val health = Profile(
            aim = Aim(LiftFocus.MUSCLE, RunFocus.HEALTH, Emphasis.GYM), experience = Experience.INTERMEDIATE,
            runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), doubleDays = true,
        )
        val h = ProgramGenerator.generate(health, LocalDate.of(2026, 10, 10))
        assertTrue(h.templates.all { it.focus == forma.core.plan.Focus.FULL }, h.templates.map { it.title }.toString())
    }
}
