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
import kotlin.test.assertNull
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
        // NHS Couch to 5K week 1: 5 min walk, 8 × 1 min run with 1:30 walks between, 5 min walk.
        assertEquals(600 + 7 * 150 + 60, w.totalSeconds)
        assertEquals(8, w.segments.count { it.kind == SegmentKind.RUN })
        assertEquals(27, RunPlan.walkLadderSize)
    }

    @Test fun runWalkClimbsRepeatsAndStepsBack() {
        val w = RunPlan.runWalk(RunState(walkStage = 3))
        assertEquals(RunOutcome.StageUp("walk", 4), RunPlan.after(RunState(walkStage = 3), w, ok(6), caps).outcomes.single())
        val first = RunPlan.after(RunState(walkStage = 3), w, ok(8), caps)
        assertEquals(RunOutcome.StageRepeat("walk"), first.outcomes.single())
        // A second miss in a row goes back one week (three runs).
        assertEquals(RunOutcome.StageDown("walk", 0), RunPlan.after(first.state, w, ok(9, 0.6), caps).outcomes.single())
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

    @Test fun threeOnPaceSessionsLiftVdotByOne() {
        var st = RunState(vdot = 50.0, walkStage = -1)
        repeat(3) { i ->
            val w = RunPlan.intervals(st)
            st = RunPlan.after(st, w, RunResult(1.0, 7, w.totalSeconds, workPace = 232.0), caps, day = 100L + i * 7).state
        }
        assertEquals(51.0, st.vdot)
        // Not again within three weeks.
        repeat(3) { i ->
            val w = RunPlan.intervals(st)
            st = RunPlan.after(st, w, RunResult(1.0, 7, w.totalSeconds, workPace = 228.0), caps, day = 121L + i).state
        }
        assertEquals(51.0, st.vdot)
    }

    @Test fun failedQualitySessionsRepeatThenStepDownThenLowerVdot() {
        var st = RunState(vdot = 50.0, walkStage = -1, intervalStage = 3)
        val fail = RunResult(0.5, 10, 1200)
        st = RunPlan.after(st, RunPlan.intervals(st), fail, caps, 10).state
        assertEquals(3, st.intervalStage)
        st = RunPlan.after(st, RunPlan.intervals(st), fail, caps, 12).state
        assertEquals(2, st.intervalStage)
        st = RunPlan.after(st, RunPlan.intervals(st), fail, caps, 14).state
        assertEquals(49.0, st.vdot)
    }

    @Test fun longRunNeverExceedsTenPercentOverRecentLongest() {
        val st = RunState(walkStage = -1, longMinutes = 70, recent = listOf(forma.core.run.RunLog(95, 50)))
        assertEquals(55 * 60, RunPlan.long(st, day = 100).totalSeconds)
    }

    // ---------- breaks ----------

    private val fit = RunState(
        vdot = 50.0, walkStage = -1, easyMinutes = 40, longMinutes = 60, intervalStage = 4, tempoStage = 3,
        lastRunDay = 0, qualityOnPace = 2,
    )

    @Test fun aWeekOffChangesNothing() {
        assertEquals(fit, RunPlan.withBreak(fit, 7))
        assertNull(RunPlan.comebackNote(fit, 7))
    }

    @Test fun fitnessFadesWithTheBreak() {
        // About −7 % in the first weeks, −15 % at most (Coyle et al. 1984).
        assertEquals(49.5, RunPlan.withBreak(fit, 10).vdot)
        assertEquals(47.5, RunPlan.withBreak(fit, 28).vdot)
        assertEquals(45.5, RunPlan.withBreak(fit, 56).vdot)
        assertEquals(42.5, RunPlan.withBreak(fit, 200).vdot)
    }

    @Test fun aBreakShortensRunsAndStepsBackQuality() {
        val three = RunPlan.withBreak(fit, 21)
        assertEquals(30, three.easyMinutes)
        assertEquals(45, three.longMinutes)
        assertEquals(3, three.intervalStage)
        assertEquals(2, three.tempoStage)
        assertEquals(0, three.returnEasy)
        assertEquals(0, three.qualityOnPace)

        val six = RunPlan.withBreak(fit, 42)
        assertEquals(25, six.easyMinutes)
        assertEquals(35, six.longMinutes)
        assertEquals(1, six.intervalStage)
        assertEquals(0, six.tempoStage)
        // Four weeks or more: the first runs back are easy, the hard day included.
        assertEquals(2, six.returnEasy)
        assertEquals(RunType.EASY, RunPlan.quality(six).type)
        assertTrue(RunPlan.quality(six).note.contains("2 лёгкие пробежки"))

        val ten = RunPlan.withBreak(fit, 70)
        assertEquals(20, ten.easyMinutes)
        assertEquals(30, ten.longMinutes)
        assertEquals(3, ten.returnEasy)
        assertTrue(RunPlan.comebackNote(fit, 70)!!.contains("10 недель"))
    }

    @Test fun runWalkGoesBackAWeekPerTwoWeeksOff() {
        val st = RunState(walkStage = 15, lastRunDay = 0)
        assertEquals(15, RunPlan.withBreak(st, 10).walkStage)
        assertEquals(12, RunPlan.withBreak(st, 14).walkStage)
        assertEquals(9, RunPlan.withBreak(st, 28).walkStage)
        assertEquals(0, RunPlan.withBreak(st, 100).walkStage)
    }

    @Test fun aBreakCountsOnceAndRunsBuildBackUp() {
        val back = RunPlan.withBreak(fit, 30)
        assertEquals(back, RunPlan.withBreak(back, 30))
        // The first run back grows from the shortened length, not from the old one.
        val first = RunPlan.after(fit, RunPlan.easy(back), RunResult(1.0, 4, back.easyMinutes * 60), caps, day = 30)
        assertEquals(back.easyMinutes + 3, first.state.easyMinutes)
        assertEquals(1, first.state.returnEasy)
        assertTrue(first.outcomes.any { it is RunOutcome.VdotChanged })
        val second = RunPlan.after(first.state, RunPlan.quality(first.state), RunResult(1.0, 4, 1800), caps, day = 32)
        assertEquals(0, second.state.returnEasy)
        assertEquals(RunType.INTERVALS, RunPlan.quality(second.state).type)
    }

    @Test fun cutbackWeekIsShorter() {
        val st = RunState(walkStage = -1, easyMinutes = 40, longMinutes = 60)
        assertEquals(30 * 60, RunPlan.easy(st, cutback = true).totalSeconds)
        assertEquals(45 * 60, RunPlan.long(st, cutback = true).totalSeconds)
    }

    @Test fun longRunGrowsByFiveMinutesWithinTheCap() {
        val st = RunState(walkStage = -1, longMinutes = 60)
        val after = RunPlan.after(st, RunPlan.long(st), RunResult(1.0, 5, 3600), caps, day = 50)
        assertEquals(65, after.state.longMinutes)
        val hard = RunPlan.after(st, RunPlan.long(st), RunResult(1.0, 8, 3600), caps, day = 50)
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
