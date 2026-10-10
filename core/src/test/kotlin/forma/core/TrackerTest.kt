package forma.core

import forma.core.run.Fix
import forma.core.run.Geo
import forma.core.run.RunEvent
import forma.core.run.RunPlan
import forma.core.run.RunState
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.RunWorkout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerTest {
    // 1 degree of latitude ≈ 111.2 km.
    private val metrePerDegLat = 111_195.0

    @Test fun haversineOneKilometreNorth() {
        assertTrue(abs(Geo.haversine(53.9, 27.56, 53.9 + 1000 / metrePerDegLat, 27.56) - 1000) < 1)
    }

    @Test fun distancePaceAndKilometres() {
        val t = RunTracker(RunWorkout(RunType.FREE, "free", emptyList()))
        t.start(0)
        val events = mutableListOf<RunEvent>()
        // 3 m/s north for 400 s, a fix every second: 1200 m, pace 333 s/km.
        for (s in 0..400) {
            val now = s * 1000L
            events += t.onFix(Fix(now, 53.9 + s * 3 / metrePerDegLat, 27.56, 5f), now)
        }
        assertTrue(abs(t.distanceM - 1200) < 3, "distance ${t.distanceM}")
        assertTrue(abs(t.currentPace()!! - 333.3) < 3)
        val km = events.filterIsInstance<RunEvent.Kilometre>().single()
        assertEquals(1, km.km)
        assertTrue(abs(km.splitSec - 333) <= 1)
    }

    @Test fun badFixesAndPausesAreIgnored() {
        val t = RunTracker(RunWorkout(RunType.FREE, "free", emptyList()))
        t.start(0)
        t.onFix(Fix(0, 53.9, 27.56, 5f), 0)
        t.onFix(Fix(1000, 53.9 + 500 / metrePerDegLat, 27.56, 5f), 1000) // 500 m in 1 s: jump
        t.onFix(Fix(2000, 53.9, 27.56, 80f), 2000) // inaccurate
        assertEquals(0.0, t.distanceM, 1e-6)
        t.pause(3000)
        assertEquals(3, t.activeSec(10_000))
        t.resume(10_000)
        assertEquals(4, t.activeSec(11_000))
        assertNull(t.averagePace(11_000))
    }

    @Test fun intervalWorkoutReportsCompletionAndWorkPace() {
        val w = RunPlan.intervals(RunState(vdot = 50.0, walkStage = -1))
        val t = RunTracker(w)
        t.start(0)
        val cues = mutableListOf<RunEvent>()
        var pos = 0.0
        for (s in 0..w.totalSeconds) {
            val now = s * 1000L
            // 4 m/s during work, 2.5 m/s otherwise.
            cues += t.tick(now)
            val seg = t.timeline(now).segment
            if (s > 0) pos += if (seg?.kind == forma.core.run.SegmentKind.WORK) 4.0 else 2.5
            t.onFix(Fix(now, 53.9 + pos / metrePerDegLat, 27.56, 5f), now)
        }
        t.finish(w.totalSeconds * 1000L)
        val r = t.result(w.totalSeconds * 1000L, 8)
        assertEquals(1.0, r.completed)
        assertTrue(abs(r.workPace!! - 250.0) < 8, "work pace ${r.workPace}")
        assertTrue(cues.any { it is RunEvent.Timeline })
    }

    @Test fun standingStillAddsNoDistance() {
        val t = RunTracker(RunWorkout(RunType.FREE, "free", emptyList()))
        t.start(0)
        // Two minutes at a traffic light: fixes wander ±4 m around one point with 10 m accuracy.
        for (s in 0..120) {
            val jitter = (if (s % 2 == 0) 4.0 else -4.0) / metrePerDegLat
            t.onFix(Fix(s * 1000L, 53.9 + jitter, 27.56, 10f), s * 1000L)
        }
        assertEquals(0.0, t.distanceM, 1e-6)
        // Then 100 s north at 3 m/s: all of it counts.
        for (s in 1..100) {
            val now = (120 + s) * 1000L
            t.onFix(Fix(now, 53.9 + (4.0 + s * 3) / metrePerDegLat, 27.56, 10f), now)
        }
        assertTrue(abs(t.distanceM - 300) < 15, "distance ${t.distanceM}")
    }

    @Test fun aJumpDoesNotMoveTheAnchor() {
        val t = RunTracker(RunWorkout(RunType.FREE, "free", emptyList()))
        t.start(0)
        t.onFix(Fix(0, 53.9, 27.56, 5f), 0)
        t.onFix(Fix(1000, 53.9 + 300 / metrePerDegLat, 27.56, 5f), 1000) // a 300 m jump
        t.onFix(Fix(2000, 53.9 + 6 / metrePerDegLat, 27.56, 5f), 2000) // back on track, 6 m from the start
        assertEquals(6.0, t.distanceM, 0.5)
    }

    @Test fun completionIsTheShareOfTheRunningDone() {
        val w = RunPlan.intervals(RunState(vdot = 50.0, walkStage = -1))
        val t = RunTracker(w)
        t.start(0)
        // Finished right after the last interval: the cool-down is not part of the work.
        val end = (w.totalSeconds - 600) * 1000L
        t.finish(end)
        val r = t.result(end, 8)
        assertEquals(1.0, r.completed)
        assertEquals(w.workSeconds, r.workSec)
    }

    @Test fun aRunSurvivesTheAppBeingKilled() {
        val w = RunPlan.intervals(RunState(vdot = 50.0, walkStage = -1))
        val t = RunTracker(w)
        t.start(1_000)
        t.onFix(Fix(1_000, 53.9, 27.56, 5f), 1_000)
        t.onFix(Fix(61_000, 53.9 + 200 / metrePerDegLat, 27.56, 5f), 61_000)
        t.skipSegment(120_000)
        val snap = kotlinx.serialization.json.Json.decodeFromString(
            forma.core.run.TrackerSnapshot.serializer(),
            kotlinx.serialization.json.Json.encodeToString(forma.core.run.TrackerSnapshot.serializer(), t.snapshot(130_000)),
        )
        // A new process, a new clock: paused where it was saved, the rest of the plan ahead.
        val back = RunTracker.restore(snap, 5_000)
        assertEquals(RunTracker.State.PAUSED, back.state)
        assertEquals(129, back.activeSec(9_000))
        assertEquals(200.0, back.distanceM, 1.0)
        assertEquals(t.timeline(130_000).index, back.timeline(9_000).index)
        assertTrue(back.tick(9_000).isEmpty())
        back.resume(10_000)
        assertEquals(139, back.activeSec(20_000))
        back.finish(20_000)
        val done = RunTracker.restore(back.snapshot(20_000), 0)
        assertEquals(RunTracker.State.FINISHED, done.state)
        assertEquals(back.result(20_000, 7), done.result(99_000, 7))
    }

    @Test fun skippingWarmUpCountsAsDoneButSkippingWorkDoesNot() {
        val w = RunPlan.intervals(RunState(vdot = 50.0, walkStage = -1))
        val t = RunTracker(w)
        t.start(0)
        t.tick(1000)
        t.skipSegment(60_000) // skip the rest of the 10-minute warm-up
        assertEquals(1, t.timeline(60_000).index)
        val cues = t.tick(61_000)
        assertTrue(cues.any { it is RunEvent.Timeline && (it.cue as? forma.core.run.Cue.SegmentStart)?.index == 1 })
        assertTrue(cues.none { it is RunEvent.Timeline && it.cue is forma.core.run.Cue.Countdown })
        t.skipSegment(70_000) // skip most of the first work interval
        val r = t.result(70_000, 7)
        assertTrue(r.completed < 0.35, "completed ${r.completed}")
    }
}
