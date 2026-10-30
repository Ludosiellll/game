package forma.core

import forma.core.model.RunGoal
import forma.core.run.PaceRange
import forma.core.run.RunPlan
import forma.core.run.RunState
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.Segment
import forma.core.run.SegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Denis (30.10): «я начинаю в той точке, которую отметил, и там же заканчиваю». The 29.10 trial was 7,5 laps started
 * half a lap before the line; he started on the line, his first full lap was taken for the half and the trial was lost.
 * On a stadium every rep and every trial is whole laps: it starts and ends on the runner's line.
 */
class WholeLapsTest {
    private val st = RunState(walkStage = -1, easyMinutes = 43, longMinutes = 34, runs = 190, testSoon = true, lastRunDay = 20978)

    @Test fun theTrialOnDenisLoopIsSevenLapsFromTheLine() {
        val w = RunPlan.goalTest(st, RunGoal(1500, 330), 205)
        val work = w.segments.single { it.kind == SegmentKind.WORK }
        assertEquals(7.0, work.laps)
        assertEquals(1435, w.testMeters)
        assertTrue("середины" !in w.note && "Старт —" !in w.note, w.note)
    }

    @Test fun everyRepAndTrialIsWholeLaps() {
        val bad = mutableListOf<String>()
        fun check(w: RunWorkout, what: String) {
            w.segments.filter { it.kind == SegmentKind.WORK }.forEach { s ->
                val l = s.laps
                if (l != null && l % 1.0 != 0.0) bad += "$what: ${w.title} — $l"
            }
            if ("середины" in w.note || "Старт —" in w.note) bad += "$what: ${w.note}"
        }
        for (lap in 150..450 step 5) for (m in listOf(800, 1000, 1500, 1609, 2000, 3000)) {
            val goal = RunGoal(m, (m * 0.24).toInt())
            check(RunPlan.goalTest(st, goal, lap), "trial $m on $lap")
            for (stage in 0 until RunPlan.goalLadderSize) check(RunPlan.goalPace(st.copy(goalStage = stage), goal, lap), "stage $stage of $m on $lap")
        }
        // Timed reps of other sessions on a stadium.
        for (lap in 150..450 step 5) for (sec in 30..600 step 15) {
            val w = RunWorkout(
                RunType.INTERVALS, "×", listOf(
                    Segment(SegmentKind.WARMUP, 600, PaceRange(330, 360)),
                    Segment(SegmentKind.WORK, sec, PaceRange(225, 235)),
                    Segment(SegmentKind.COOLDOWN, 600, PaceRange(330, 360)),
                ), "", targetRpe = 8,
            )
            check(RunPlan.onTrack(w, lap), "$sec s on $lap")
        }
        assertTrue(bad.isEmpty(), "${bad.size} half laps:\n${bad.take(8).joinToString("\n")}")
    }
}
