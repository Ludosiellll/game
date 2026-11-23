package forma.core

import forma.core.model.RunGoal
import forma.core.run.CustomRun
import forma.core.run.RunCaps
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunType
import forma.core.run.SegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis, 23 November: the coach «не имеет некоторых функций» — he asks for a run that is not in the plan («хочу сегодня
 * 8 по 200»), and the coach could only put an easy one. A run of the coach's own making: its reps at the app's paces for
 * the runner, whole laps on a stadium; it counts as a run and moves no ladder.
 */
class CustomRunTest {
    private val goal = RunGoal(1500, 330)
    private val st = RunPlan.estimated(RunState(walkStage = -1, easyMinutes = 30, runs = 12, qualityCount = 3), goal)

    private fun work(w: forma.core.run.RunWorkout) = w.segments.filter { it.kind == SegmentKind.WORK }

    @Test fun eightTwoHundredsFastWithAMinuteBetween() {
        val c = CustomRun(8, null, 200, CustomRun.Pace.FAST, 60, 10, 5)
        val w = assertNotNull(RunPlan.custom(st, goal, c))
        assertTrue(w.custom)
        assertEquals("От тренера · 8 × 200 м", w.title)
        assertEquals(8, work(w).size)
        assertEquals(7, w.segments.count { it.kind == SegmentKind.RECOVERY && it.seconds == 60 })
        assertEquals(600, w.segments.first().seconds)
        assertEquals(SegmentKind.COOLDOWN, w.segments.last().kind)
        // Paced from his fitness (the estimate): 200 m at the repetition pace, well under a minute.
        val pace = assertNotNull(work(w).first().pace)
        assertEquals((pace.fast + pace.slow) / 2.0 * 0.2, work(w).first().seconds.toDouble(), 1.0)
        assertTrue(work(w).first().seconds in 40..58, "${work(w).first().seconds} s")
        // On his 205 m loop each rep is one lap from the line.
        val track = assertNotNull(RunPlan.custom(st, goal, c, 205))
        assertTrue(work(track).all { it.laps == 1.0 })
        assertEquals("От тренера · 8 × 1 круг", track.title)
    }

    @Test fun anEasyTwentyMinutesIsOneRun() {
        val w = assertNotNull(RunPlan.custom(st, goal, CustomRun(1, 1200, null, CustomRun.Pace.EASY, 0, 0, 0)))
        assertEquals(RunType.EASY, w.type)
        assertEquals(listOf(SegmentKind.RUN), w.segments.map { it.kind })
        assertEquals(1200, w.workSeconds)
        assertTrue(w.custom)
    }

    @Test fun itCountsAsARunAndMovesNoLadder() {
        val w = assertNotNull(RunPlan.custom(st, goal, CustomRun(6, 120, null, CustomRun.Pace.INTERVAL, 120, 10, 5)))
        assertEquals(RunType.INTERVALS, w.type)
        val a = RunPlan.after(st, w, RunResult(0.4, 9, 900), RunCaps(45, 90), day = 20780, goal = goal)
        assertEquals(st.runs + 1, a.state.runs)
        assertEquals(st.intervalStage to st.qualityCount, a.state.intervalStage to a.state.qualityCount)
        assertEquals(st.estVdot, a.state.estVdot)
        assertTrue(a.outcomes.isEmpty(), "${a.outcomes}")
    }

    @Test fun metresWithNoPaceKnownCannotBeTimed() {
        val unknown = RunState(walkStage = -1)
        assertNull(RunPlan.custom(unknown, null, CustomRun(8, null, 200, CustomRun.Pace.FAST, 60, 10, 5)))
        // By feel, in seconds, it can: no pace on the reps.
        val w = assertNotNull(RunPlan.custom(unknown, null, CustomRun(8, 45, null, CustomRun.Pace.FREE, 60, 10, 5)))
        assertNull(work(w).first().pace)
        assertEquals("От тренера · 8 × 45 с", w.title)
    }
}
