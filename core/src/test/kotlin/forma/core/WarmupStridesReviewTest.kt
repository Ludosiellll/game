package forma.core

import forma.core.model.RunGoal
import forma.core.run.Cue
import forma.core.run.Fix
import forma.core.run.RunPlan
import forma.core.run.RunReview
import forma.core.run.RunState
import forma.core.run.RunTimeline
import forma.core.run.RunTracker
import forma.core.run.TrackerSnapshot
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The warm-up's strides after review: strides never reached are not "done", a walk to the track is not the jog they are
 * measured against, the trial leads its own debrief, the plan's note says what the clock does, and the voice warns
 * before the first stride instead of lecturing through it.
 */
class WarmupStridesReviewTest {
    private val goal = RunGoal(1500, 330)
    private val stadium = RunPlan.goalTest(RunState(walkStage = -1), goal, 205)
    private val road = RunPlan.goalTest(RunState(walkStage = -1), goal, null)

    /** A road trial: a fix a second, [speed] by the plan's second. */
    private fun tracked(speed: (Int) -> Float?, until: Int): RunTracker {
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

    @Test fun aWarmupSkippedBeforeTheStridesHasNone() {
        // Denis presses «skip» five minutes into the warm-up: the plan jumps to the line, no stride was started.
        val t = RunTracker(stadium)
        t.start(0)
        t.skipSegment(300_000)
        assertTrue(t.waiting(300_000))
        assertNull(t.result(300_000, 6).strideSpeeds)
        // The same after the app was closed and the run brought back.
        assertNull(RunTracker.restore(t.snapshot(300_000), 300_000).result(300_000, 6).strideSpeeds)
    }

    @Test fun aSkipDuringTheStridesCountsOnlyThoseStarted() {
        // On the road, «skip» 25 s into the jog after the first stride: one started, three never — not «4 из 4».
        val t = RunTracker(road)
        t.start(0)
        t.skipSegment(520_000)
        val s = assertNotNull(t.result(600_000, 6).strideSpeeds)
        assertEquals(1, s.size)
        val review = RunReview.of(RunReview.Run(road, strides = s)).single { it.title == "Ускорения" }
        assertTrue("1 из 4" in review.text, review.text)
    }

    @Test fun walkingToTheTrackIsNotTheJog() {
        // Four minutes of walking (1.3 m/s) with the run already on, then jogging at 2.8: the strides are measured
        // against the jog, not against a mean dragged down by the walk.
        val t = tracked({ sec ->
            val st = RunTimeline.stride(road, sec)
            when {
                st != null && st.on -> 5.5f
                sec <= 240 -> 1.3f
                else -> 2.8f
            }
        }, 700)
        assertEquals(2.8, t.result(700_000, 6).jogSpeed!!, 0.05)
    }

    @Test fun theTrialLeadsItsDebrief() {
        val items = RunReview.of(RunReview.Run(stadium, trial = 1500 to 328, strides = List(4) { 5.4 }, jog = 2.8), goal)
        assertEquals("Как пробежал", items.first().title, items.joinToString { it.title })
        assertTrue(items.any { it.title == "Ускорения" })
    }

    @Test fun thePlanSaysWhatTheClockDoes() {
        val st = RunState(walkStage = -1, vdot = 46.0)
        for (w in listOf(RunPlan.goalTest(st, goal, 205), RunPlan.goalPace(st, goal, 205), RunPlan.intervals(st), RunPlan.race(st, goal))) {
            assertNotNull(RunTimeline.stridesFrom(w), w.title)
            assertFalse("3–4" in w.note, "${w.title}: ${w.note}")
            assertTrue("4 ускорения по 15" in w.note, "${w.title}: ${w.note}")
        }
    }

    @Test fun theVoiceWarnsHalfAMinuteBeforeTheFirstStride() {
        // The banner comes 30 s ahead; so does the voice — at the stride itself there is only time for «пошёл».
        val cues = RunTimeline.allCues(stadium)
        assertTrue(cues.any { it.at == 495 - 30 && it !is Cue.Stride }, cues.filter { it.at in 460..500 }.toString())
    }

    @Test fun snapshotsWithoutTheStrideFieldsStillDecode() {
        val json = Json { ignoreUnknownKeys = true }
        val t = RunTracker(stadium)
        t.start(0)
        val full = json.encodeToString(TrackerSnapshot.serializer(), t.snapshot(100_000))
        val old = full.replace(Regex(""","strideSpeeds":\{[^}]*\}"""), "").replace(Regex(""","jog":\[[^\]]*\]"""), "")
        assertFalse("strideSpeeds" in old)
        val back = json.decodeFromString(TrackerSnapshot.serializer(), old)
        assertEquals(t.snapshot(100_000).copy(strideSpeeds = emptyMap(), jog = emptyList()), back)
        // And a run killed after a stride keeps the stride's speeds through the file.
        val run = tracked({ sec -> if (RunTimeline.stride(road, sec)?.on == true) 5.5f else 2.8f }, 520)
        val again = json.decodeFromString(TrackerSnapshot.serializer(), json.encodeToString(TrackerSnapshot.serializer(), run.snapshot(520_000)))
        assertEquals(run.snapshot(520_000), again)
        assertEquals(5.5, RunTracker.restore(again, 520_000).result(520_000, 6).strideSpeeds!![0]!!, 0.01)
    }
}
