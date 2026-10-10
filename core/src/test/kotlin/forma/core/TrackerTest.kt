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
        for (s in 0..w.totalSeconds) {
            val now = s * 1000L
            // 4 m/s during work, 2.5 m/s otherwise.
            cues += t.tick(now)
            val seg = t.timeline(now).segment
            val speed = if (seg?.kind == forma.core.run.SegmentKind.WORK) 4.0 else 2.5
            val lat = 53.9 + (t.distanceM + speed) / metrePerDegLat
            t.onFix(Fix(now, if (s == 0) 53.9 else lat, 27.56, 5f), now)
        }
        t.finish(w.totalSeconds * 1000L)
        val r = t.result(w.totalSeconds * 1000L, 8)
        assertEquals(1.0, r.completed)
        assertTrue(abs(r.workPace!! - 250.0) < 8, "work pace ${r.workPace}")
        assertTrue(cues.any { it is RunEvent.Timeline })
    }
}
