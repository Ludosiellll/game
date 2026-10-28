package forma.core

import forma.core.model.RunGoal
import forma.core.run.Cue
import forma.core.run.Fix
import forma.core.run.RunPlan
import forma.core.run.RunReview
import forma.core.run.RunState
import forma.core.run.RunTimeline
import forma.core.run.RunTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis: «насчет ускорений во время разминки, я думаю стоит это на экране отображать, чтобы понимать сколько ускорение по
 * времени делать и чтобы оно как-то учитывалось». Before fast running the warm-up ends with 4 strides of 15 s, 30 s of
 * jogging between: each one timed (said, beeped, shown) and its speed measured by GPS against the jog before them.
 */
class WarmupStridesTest {
    private val goal = RunGoal(1500, 330)
    private val trial = RunPlan.goalTest(RunState(walkStage = -1), goal, 205)

    @Test fun fourStridesOfFifteenSecondsEndOverAMinuteBeforeTheStart() {
        // The warm-up is 12 minutes: strides at 8:15, 9:00, 9:45, 10:30, each 15 s.
        assertEquals(12 * 60, trial.segments.first().seconds)
        val cues = RunTimeline.allCues(trial).filterIsInstance<Cue.Stride>()
        assertEquals(listOf(495, 540, 585, 630), cues.filter { it.on }.map { it.at })
        assertEquals(listOf(510, 555, 600, 645), cues.filter { !it.on }.map { it.at })
        assertEquals(listOf(1, 2, 3, 4), cues.filter { it.on }.map { it.n })
        // «Через минуту забег. Подходи к старту» comes after the last stride, not inside it.
        val minute = RunTimeline.allCues(trial).filterIsInstance<Cue.MinuteLeft>().single { it.index == 0 }
        assertTrue(minute.at > cues.last().at, "${minute.at}")
    }

    @Test fun theScreenKnowsWhichStrideAndHowLong() {
        assertNull(RunTimeline.stride(trial, 400))
        assertEquals(RunTimeline.StrideNow(1, 4, false, 30), RunTimeline.stride(trial, 465))
        assertEquals(RunTimeline.StrideNow(1, 4, true, 15, 0), RunTimeline.stride(trial, 495))
        assertEquals(RunTimeline.StrideNow(1, 4, true, 1, 14), RunTimeline.stride(trial, 509))
        assertEquals(RunTimeline.StrideNow(2, 4, false, 30), RunTimeline.stride(trial, 510))
        assertEquals(RunTimeline.StrideNow(4, 4, true, 15, 0), RunTimeline.stride(trial, 630))
        assertNull(RunTimeline.stride(trial, 645))
        // An easy run has no strides in a warm-up.
        assertNull(RunTimeline.stride(RunPlan.easy(RunState(walkStage = -1, vdot = 42.0)), 30))
    }

    /** A road trial (no loop fitting): a fix a second, [speed] by the plan's second. */
    private fun tracked(speed: (Int) -> Float?, until: Int): RunTracker {
        val road = RunPlan.goalTest(RunState(walkStage = -1), goal, null)
        val t = RunTracker(road)
        t.start(0)
        var lat = 53.9
        for (sec in 1..until) {
            val v = speed(sec)
            lat += (v ?: 0f) / 111_000.0
            t.onFix(Fix(sec * 1000L, lat, 27.56, 5f, v), sec * 1000L)
        }
        return t
    }

    @Test fun eachStrideIsMeasuredAgainstTheJog() {
        // Jogging at 2.8 m/s (5:57/km), strides at 5.5 m/s (3:02/km), the last only at 3.0.
        val t = tracked({ sec ->
            val st = RunTimeline.stride(t0, sec)
            when {
                st != null && st.on -> if (st.n == 4) 3.0f else 5.5f
                else -> 2.8f
            }
        }, 700)
        val r = t.result(700_000, 6)
        val s = assertNotNull(r.strideSpeeds)
        assertEquals(4, s.size)
        assertEquals(5.5, s[0]!!, 0.01)
        assertEquals(3.0, s[3]!!, 0.01)
        assertEquals(2.8, r.jogSpeed!!, 0.01)
        // Kept through the app being closed mid-run.
        val back = RunTracker.restore(t.snapshot(700_000), 700_000)
        assertEquals(s, back.result(700_000, 6).strideSpeeds)

        val review = RunReview.of(RunReview.Run(t0, strides = s, jog = r.jogSpeed)).single { it.title == "Ускорения" }
        assertTrue("4 из 4" in review.text && "Ускорение 4 почти не быстрее трусцы" in review.text, review.text)
    }

    @Test fun stridesStartedOnlyCountAndNoGpsSaysSo() {
        // Stopped in the second stride.
        val t = tracked({ 2.8f }, 545)
        assertEquals(2, t.result(545_000, 6).strideSpeeds!!.size)
        val none = RunReview.of(RunReview.Run(t0, strides = listOf(null, null, null, null))).single { it.title == "Ускорения" }
        assertTrue("не измерен" in none.text, none.text)
        val good = RunReview.of(RunReview.Run(t0, strides = List(4) { 5.4 }, jog = 2.8)).single { it.title == "Ускорения" }
        assertTrue("Ноги готовы" in good.text && good.tone == 1, good.text)
    }

    private val t0 = RunPlan.goalTest(RunState(walkStage = -1), goal, null)
}
