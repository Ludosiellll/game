package forma.core

import forma.core.model.Catalog
import forma.core.model.RunGoal
import forma.core.run.Fix
import forma.core.run.Press
import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunTracker
import forma.core.run.SegmentKind
import forma.core.strength.Phase
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the engine review of 2026-10-10 found, each one fixed: a test per finding, named after what must hold. */
class ReviewFixesTest {
    private val goal = RunGoal(1500, 330)
    private val caps = RunCaps(50, 110)
    private val warm = 12 * 60 * 1000L

    @Test fun aLateStartOfTheTimeTrialStillTimesItFromThePress() {
        // Started 15 s after the warm-up ended, pressed on the way: half a lap and six laps, 344.5 s really run.
        val st = RunState(walkStage = -1, vdot = 46.0, runs = 10)
        val w = RunPlan.goalTest(st, goal, 230)
        val t = RunTracker(w)
        t.start(0)
        assertEquals(Press.Start, t.press(warm + 15_000))
        var at = warm + 15_000 + 26_500
        repeat(7) {
            t.press(at)
            at += 53_000
        }
        val r = t.result(at, 9)
        assertEquals(345, r.testSeconds!!, "${r.laps}")
        val a = RunPlan.after(st, w, r, caps, day = 100, goal = goal)
        assertTrue(a.outcomes.none { it == RunOutcome.GoalReached }, a.outcomes.toString())
    }

    @Test fun aTrialTimeFarFromTheFormIsATypoNotARecord() {
        val st = RunState(walkStage = -1, vdot = 46.0)
        val w = RunPlan.goalTest(st, goal, 230)
        // "3:52" typed for "5:52": VDOT 72.5 and "goal reached" before; now not counted.
        val typo = RunPlan.after(st, w, RunResult(1.0, 9, 1700, testSeconds = 232), caps, day = 100, goal = goal)
        assertEquals(46.0, typo.state.vdot)
        assertTrue(typo.outcomes.any { it is RunOutcome.TestDoubtful }, typo.outcomes.toString())
        assertTrue(typo.outcomes.none { it == RunOutcome.GoalReached })
        // A real result is counted.
        val real = RunPlan.after(st, w, RunResult(1.0, 9, 1700, testSeconds = 352), caps, day = 100, goal = goal)
        assertTrue(real.outcomes.any { it is RunOutcome.VdotChanged }, real.outcomes.toString())
        // The 12-minute test: 1200 m (a GPS failure) is not a VDOT of 15.
        val twelve = RunPlan.after(st, RunPlan.test(st), RunResult(1.0, 9, 1800, workDistanceM = 1200.0, workSec = 720), caps, day = 100)
        assertEquals(46.0, twelve.state.vdot)
        // Ninety failed hard runs do not take the form below what people run.
        var s = RunState(walkStage = -1, vdot = 21.0, intervalStage = 3, runs = 10)
        val iv = RunPlan.intervals(s)
        repeat(12) { k -> s = RunPlan.after(s, iv, RunResult(1.0, 10, iv.totalSeconds), caps, day = k * 3L).state }
        assertTrue(s.vdot!! >= RunPlan.MIN_VDOT, "${s.vdot}")
    }

    @Test fun aRoadTrialCountsFromGpsAndAnUncountedOneSaysSo() {
        val st = RunState(walkStage = -1, runs = 5)
        val w = RunPlan.goalTest(st, goal, null)
        // No laps on a road: 1500 m in 5:45 by GPS is the result.
        val gps = RunPlan.after(st, w, RunResult(1.0, 9, 1700, workDistanceM = 1500.0, workSec = 345), caps, day = 100, goal = goal)
        assertNotNull(gps.state.vdot)
        // Nothing at all: not counted, and the app says so (and waits two weeks for the next one).
        val none = RunPlan.after(st, w, RunResult(1.0, 9, 1700), caps, day = 100, goal = goal)
        assertNull(none.state.vdot)
        assertTrue(none.outcomes.contains(RunOutcome.TestNotCounted))
    }

    @Test fun aForgottenLapDoesNotLetACoolDownPressFinishTheTrial() {
        val w = RunPlan.goalTest(RunState(walkStage = -1, vdot = 46.0), goal, 230)
        val t = RunTracker(w)
        t.start(0)
        t.press(warm)
        var at = warm + 26_500
        for (k in 1..7) {
            if (k != 3) t.press(at)
            at += 53_000
        }
        // A press a minute into the cool-down: before, it closed the trial at 405 s.
        t.press(warm + 344_500 + 60_000)
        assertNull(t.result(warm + 500_000, 9).testSeconds)
    }

    @Test fun aTimedRepOnABigLoopIsNotJudgedAsAWholeLap() {
        // 150 m reps for a 1000 m goal on a 400 m track: no lap count fits, the rep stays timed without a lap target.
        val w = RunPlan.quality(RunState(walkStage = -1, vdot = 47.0), goal = RunGoal(1000, 200), lapMeters = 400)
        val rep = w.segments.first { it.kind == SegmentKind.WORK }
        if (rep.laps == null) {
            assertNull(rep.lapSec)
            val t = RunTracker(w)
            t.start(0)
            t.press(warm)
            val l = t.lap(warm + rep.seconds * 1000L)
            assertNull(l?.verdict)
        }
    }

    @Test fun lightMachinesAndDumbbellsNeverGoToZero() {
        val legExt = Catalog["leg_ext"]
        val spec = SlotSpec("leg_ext", 2, 10, 15, 2, 75, SlotRole.ACCESSORY)
        // A lighter week on a 5 kg leg extension: 5 kg (fewer sets and more in reserve), not 0.
        assertEquals(5.0, Progression.prescribe(legExt, spec, SlotState(5.0, 12), phase = Phase.DELOAD).weight)
        // 10 kg stays 10, not 5: half the weight is not a lighter week.
        assertEquals(10.0, Progression.prescribe(legExt, spec, SlotState(10.0, 12), phase = Phase.DELOAD).weight)
        // A layoff: the same.
        assertEquals(10.0, Progression.prescribe(legExt, spec, SlotState(10.0, 12), daysSinceLast = 40).weight)
        val raise = Catalog["lateral_raise"]
        val rs = SlotSpec("lateral_raise", 3, 10, 15, 2, 60, SlotRole.ACCESSORY)
        assertTrue(Progression.prescribe(raise, rs, SlotState(1.0, 10), phase = Phase.DELOAD).weight!! >= 1.0)
        // Missing reps at the lightest dumbbell lowers the reps, never the weight to 0.
        val p = Progression.prescribe(raise, rs, SlotState(1.0, 10))
        val ev = Progression.evaluate(raise, rs, SlotState(1.0, 10), p, listOf(forma.core.strength.DoneSet(1.0, 6), forma.core.strength.DoneSet(1.0, 5)), 50.0)
        assertTrue(ev.state.weight!! >= 1.0, "${ev.outcome}")
    }

    @Test fun aStepChangedForTheGymAppliesToTheStoredWeight() {
        // 72.5 kg needs 1.25 kg plates; in a gym with 2.5 kg plates the bench goes in 5 kg steps: 70.
        val bench = Catalog["bench"].copy(step = 5.0, fineStep = null)
        val spec = SlotSpec("bench", 3, 6, 10, 2, 150, SlotRole.MAIN)
        assertEquals(70.0, Progression.prescribe(bench, spec, SlotState(72.5, 8)).weight)
        // With the usual step nothing changes.
        assertEquals(72.5, Progression.prescribe(Catalog["bench"], spec, SlotState(72.5, 8)).weight)
    }

    @Test fun aRunSavedLateDoesNotFakeABreakAndADoubleTapSurvivesARestore() {
        val st = RunState(walkStage = -1, vdot = 46.0, longMinutes = 80, lastRunDay = 20, runs = 10)
        val easy = RunPlan.easy(st)
        val late = RunPlan.after(st, easy, RunResult(1.0, 4, easy.totalSeconds), caps, day = 10)
        assertEquals(20L, late.state.lastRunDay)
        // A lap pressed (50 s, about the lap planned: a press far quicker than the rep's lap is a touch, not a lap),
        // the app restored, a second tap 0.6 s later: one press.
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, 230)
        val t = RunTracker(w)
        t.start(0)
        t.press(warm)
        assertIs<Press.Done>(t.press(warm + 50_000))
        val back = RunTracker.restore(t.snapshot(warm + 50_000), warm + 50_000)
        back.resume(warm + 50_000)
        assertEquals(Press.Ignored, back.press(warm + 50_600))
    }

    @Test fun fourDaysForA1500mGoalHoldTwoHardAndTwoEasyRuns() {
        val denis = forma.core.model.Profile(
            aim = forma.core.model.Aim(forma.core.model.LiftFocus.POWERBUILDING, forma.core.model.RunFocus.ENDURANCE, forma.core.model.Emphasis.BALANCED),
            experience = forma.core.model.Experience.ADVANCED, runLevel = forma.core.model.RunLevel.BASE,
            trainingDays = setOf(2, 4, 6, 7), runGoal = goal, lapMeters = 230, doubleDays = true,
        )
        val p = forma.core.plan.ProgramGenerator.generate(denis, java.time.LocalDate.of(2026, 10, 12))
        fun count(k: forma.core.plan.SessionKind) = p.week.days.sumOf { d -> d.sessions.count { it.kind == k } }
        assertEquals(3, count(forma.core.plan.SessionKind.LIFT))
        assertEquals(2, count(forma.core.plan.SessionKind.RUN_QUALITY))
        assertEquals(2, count(forma.core.plan.SessionKind.RUN_EASY))
        assertEquals(0, count(forma.core.plan.SessionKind.RUN_LONG))
        // Never heavy legs and a hard run on one day, nor a hard run the day after legs.
        fun legs(s: forma.core.plan.PlannedSession?) = s?.kind == forma.core.plan.SessionKind.LIFT && p.template(s.templateId!!).legs
        for (d in 1..7) {
            val today = p.week.on(d)
            val tomorrow = p.week.on(d % 7 + 1)
            val hard = today.any { it.kind == forma.core.plan.SessionKind.RUN_QUALITY }
            assertTrue(!(hard && today.any { legs(it) }), "day $d: ${p.week.days}")
            assertTrue(!(today.any { legs(it) } && tomorrow.any { it.kind == forma.core.plan.SessionKind.RUN_QUALITY }), "day $d: ${p.week.days}")
        }
        // Three days stay three runs.
        val three = forma.core.plan.ProgramGenerator.generate(denis.copy(trainingDays = setOf(2, 4, 6)), java.time.LocalDate.of(2026, 10, 12))
        assertEquals(3, three.week.days.sumOf { d -> d.sessions.count { it.isRun } })
    }

    @Test fun beforeTheFirstTestEasyRunsHaveAnApproximateLimitFromTheGoal() {
        // 1500 m in 5:30 is VDOT 48.9; the guess is 8 points lower.
        assertEquals(41.0, RunPlan.provisionalVdot(goal))
        val easy = RunPlan.provisional(RunPlan.easy(RunState(walkStage = -1, vdot = RunPlan.provisionalVdot(goal))))
        assertNotNull(easy.segments.first().pace)
        assertTrue(easy.note.contains("примерный"), easy.note)
    }

    @Test fun stridesAreSaidBeforeFastRunningOnly() {
        val st = RunState(walkStage = -1, vdot = 46.0)
        for (w in listOf(RunPlan.goalPace(st, goal, 230), RunPlan.intervals(st), RunPlan.goalTest(st, goal, 230))) {
            val cues = forma.core.run.RunTimeline.allCues(w)
            assertEquals(4, cues.count { it is forma.core.run.Cue.Stride && it.on }, w.title)
        }
        for (w in listOf(RunPlan.easy(st), RunPlan.tempo(st))) {
            assertTrue(forma.core.run.RunTimeline.allCues(w).none { it is forma.core.run.Cue.Stride }, w.title)
        }
    }

    @Test fun gpsOnASmallLoopIsNotThePace() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, 230)
        val t = RunTracker(w)
        t.start(0)
        var lat = 53.9
        for (sec in 0..900) {
            lat += 4.0 / 111_195.0
            t.onFix(Fix(sec * 1000L, lat, 27.56, 4f), sec * 1000L)
        }
        assertNull(t.result(900_000, 7).workPace)
    }
}
