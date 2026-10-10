package forma.core

import forma.core.run.Cue
import forma.core.run.PaceTable
import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunTimeline
import forma.core.run.RunType
import forma.core.run.SegmentKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunTest {
    private val caps = RunCaps(easyMax = 45, longMax = 90)
    private fun ok(rpe: Int, done: Double = 1.0) = RunResult(done, rpe, 1800)

    @Test fun pacesForVdot50() {
        val p = PaceTable.forVdot(50.0)
        assertTrue(abs(p.threshold - 255) <= 2, "T ${p.threshold}")
        assertTrue(abs(p.interval - 235) <= 2, "I ${p.interval}")
        assertTrue(p.easy.fast in 295..315 && p.easy.slow in 330..345, "E ${p.easy}")
        assertTrue(p.marathon in 268..275, "M ${p.marathon}")
    }

    @Test fun runWalkStartsWithOneMinuteRuns() {
        val w = RunPlan.runWalk(RunState())
        assertEquals(RunType.RUN_WALK, w.type)
        assertEquals(30 * 60, w.totalSeconds)
        assertEquals(8, w.segments.count { it.kind == SegmentKind.RUN })
    }

    @Test fun runWalkClimbsRepeatsAndStepsBack() {
        val w = RunPlan.runWalk(RunState(walkStage = 3))
        assertEquals(RunOutcome.StageUp("walk", 4), RunPlan.after(RunState(walkStage = 3), w, ok(6), caps).outcomes.single())
        assertEquals(RunOutcome.StageRepeat("walk"), RunPlan.after(RunState(walkStage = 3), w, ok(8), caps).outcomes.single())
        assertEquals(RunOutcome.StageDown("walk", 2), RunPlan.after(RunState(walkStage = 3), w, ok(9, 0.6), caps).outcomes.single())
    }

    @Test fun lastRunWalkStepGraduates() {
        val last = RunState(walkStage = RunPlan.walkLadderSize - 1)
        val after = RunPlan.after(last, RunPlan.runWalk(last), ok(6), caps)
        assertEquals(RunOutcome.Graduated, after.outcomes.single())
        assertTrue(after.state.canRunContinuously)
    }

    @Test fun intervalsUseIntervalPaceAndClimb() {
        val st = RunState(vdot = 50.0, walkStage = -1)
        val w = RunPlan.intervals(st)
        assertEquals(5, w.segments.count { it.kind == SegmentKind.WORK })
        assertEquals(600 + 5 * 120 + 4 * 120 + 600, w.totalSeconds)
        val up = RunPlan.after(st, w, ok(8), caps)
        assertEquals(1, up.state.intervalStage)
        assertEquals(1, up.state.qualityCount)
    }

    @Test fun fastIntervalsNudgeVdotUp() {
        val st = RunState(vdot = 50.0, walkStage = -1)
        val w = RunPlan.intervals(st)
        val after = RunPlan.after(st, w, RunResult(1.0, 7, 2280, workPace = 225.0), caps)
        assertEquals(50.5, after.state.vdot)
    }

    @Test fun cutbackWeekIsShorter() {
        val st = RunState(walkStage = -1, easyMinutes = 40, longMinutes = 60)
        assertEquals(30 * 60, RunPlan.easy(st, cutback = true).totalSeconds)
        assertEquals(45 * 60, RunPlan.long(st, cutback = true).totalSeconds)
    }

    @Test fun longRunGrowsByAboutTenPercent() {
        val st = RunState(walkStage = -1, longMinutes = 60)
        val after = RunPlan.after(st, RunPlan.long(st), ok(5), caps)
        assertEquals(65, after.state.longMinutes)
        val hard = RunPlan.after(st, RunPlan.long(st), ok(8), caps)
        assertEquals(55, hard.state.longMinutes)
    }

    @Test fun timeTrialSetsVdot() {
        val st = RunState(walkStage = -1)
        val after = RunPlan.after(st, RunPlan.test(st), RunResult(1.0, 9, 1800, workDistanceM = 3000.0), caps)
        assertEquals(48.0, after.state.vdot)
    }

    @Test fun timelineWalksThroughSegments() {
        val w = RunPlan.intervals(RunState(vdot = 50.0, walkStage = -1))
        val s0 = RunTimeline.at(w, 0)
        assertEquals(0, s0.index)
        assertEquals(600, s0.segmentRemaining)
        val s1 = RunTimeline.at(w, 610)
        assertEquals(1, s1.index)
        assertEquals(SegmentKind.WORK, s1.segment?.kind)
        assertTrue(RunTimeline.at(w, w.totalSeconds).finished)
    }

    @Test fun cuesFireOncePerWindow() {
        val w = RunPlan.intervals(RunState(vdot = 50.0, walkStage = -1))
        val cues = RunTimeline.allCues(w)
        assertTrue(cues.first() is Cue.SegmentStart)
        assertTrue(cues.last() is Cue.Finished)
        assertEquals(listOf(3, 2, 1), RunTimeline.cuesBetween(w, 596, 599).filterIsInstance<Cue.Countdown>().map { it.left })
        val start = RunTimeline.cuesBetween(w, 599, 600)
        assertTrue(start.any { it is Cue.SegmentStart && it.index == 1 })
        // Sum over consecutive windows equals the whole list: nothing is lost or repeated.
        val collected = (0 until w.totalSeconds + 2).flatMap { RunTimeline.cuesBetween(w, it - 1, it) }
        assertEquals(cues.size, collected.size)
    }
}
