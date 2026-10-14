package forma.core

import forma.core.model.RunGoal
import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunType
import forma.core.run.SegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the running lacked: each ladder counts its own misses, the tempo of a speed type, strides after easy runs, the race
 * on its day.
 */
class RunBacklogTest {
    private val caps = RunCaps(45, 90)
    private val goal = RunGoal(1500, 330)

    /** Intervals missed twice with a tempo run well between them: a step back, not a repeat for months. */
    @Test fun eachLadderCountsItsOwnMisses() {
        var st = RunState(walkStage = -1, vdot = 50.0, intervalStage = 5, tempoStage = 3, runs = 20)
        val i1 = RunPlan.intervals(st)
        st = RunPlan.after(st, i1, RunResult(1.0, 10, i1.totalSeconds), caps, day = 100).state
        assertEquals(1, st.intervalFails)
        val t = RunPlan.tempo(st)
        st = RunPlan.after(st, t, RunResult(1.0, 6, t.totalSeconds), caps, day = 103).state
        assertEquals(1, st.intervalFails, "a tempo run does not wipe the intervals' miss")
        val i2 = RunPlan.intervals(st)
        val a = RunPlan.after(st, i2, RunResult(1.0, 10, i2.totalSeconds), caps, day = 107)
        assertEquals(4, a.state.intervalStage)
        assertTrue(a.outcomes.any { it is RunOutcome.StageDown })
    }

    /** Tempo measured slow at a hard effort: softer by 3 s/km; held easily on pace later: firmer again. */
    @Test fun aSpeedTypesTempoGetsSofter() {
        val st = RunState(walkStage = -1, vdot = 50.0, tempoStage = 2, runs = 20)
        val t = RunPlan.tempo(st)
        val target = t.segments.first { it.kind == SegmentKind.WORK }.pace!!.let { (it.fast + it.slow) / 2.0 }
        val slow = RunPlan.after(st, t, RunResult(1.0, 9, t.totalSeconds, workPace = target * 1.05), caps, day = 100)
        assertEquals(3, slow.state.tempoSoft)
        assertTrue(RunOutcome.TempoPace(3, softer = true) in slow.outcomes)
        val next = RunPlan.tempo(slow.state).segments.first { it.kind == SegmentKind.WORK }.pace!!
        assertEquals(target + 3, (next.fast + next.slow) / 2.0)
        // On pace and light: back toward the fitness's own.
        val t2 = RunPlan.tempo(slow.state)
        val tgt2 = (next.fast + next.slow) / 2.0
        val easy = RunPlan.after(slow.state, t2, RunResult(1.0, 4, t2.totalSeconds, workPace = tgt2 - 3), caps, day = 107)
        assertEquals(0, easy.state.tempoSoft)
    }

    @Test fun stridesFollowAnEasyRun() {
        val st = RunState(walkStage = -1, vdot = 50.0, runs = 12)
        val w = RunPlan.easy(st, strides = RunPlan.strides(st, cutback = false))
        assertEquals("Лёгкий бег · 30 мин + 6 ускорений", w.title)
        assertTrue("80 м" in w.note)
        // The run itself is the same length: strides are not part of the minutes that grow.
        assertEquals(RunPlan.easy(st).workSeconds, w.workSeconds)
        assertEquals(4, RunPlan.strides(st.copy(runs = 3), cutback = false))
        assertEquals(4, RunPlan.strides(st, cutback = true))
    }

    @Test fun theRaceIsATrialOverTheGoal() {
        val race = RunPlan.race(RunState(walkStage = -1, vdot = 49.0, runs = 20), goal)
        assertEquals(RunType.TEST, race.type)
        assertEquals("Забег · 1500 м", race.title)
        assertEquals(1500, race.testMeters)
        assertEquals("Забег · 10 км", RunPlan.race(RunState(walkStage = -1, vdot = 49.0), RunGoal(10_000, 2700)).title)
    }
}
