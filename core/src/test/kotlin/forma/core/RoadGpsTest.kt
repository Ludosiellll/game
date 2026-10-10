package forma.core

import forma.core.run.AutoPause
import forma.core.run.Fix
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.RunWorkout
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GPS on the road: the satellites' speed against positions a few metres off, and the auto-pause at a traffic light. */
class RoadGpsTest {
    private fun run(speed: Boolean, seed: Int): RunTracker {
        val rnd = Random(seed)
        val t = RunTracker(RunWorkout(RunType.FREE, "Свободный бег", emptyList()))
        t.start(0)
        // Ten minutes along a straight road at 3.33 m/s (5:00 a km): 2000 m.
        for (sec in 0..600) {
            val x = 3.333 * sec
            val nx = x + rnd.nextDouble(-5.0, 5.0)
            val ny = rnd.nextDouble(-5.0, 5.0)
            val v = if (speed) (3.333 + rnd.nextDouble(-0.15, 0.15)).toFloat() else null
            t.onFix(Fix(sec * 1000L, 53.9 + ny / 111_195.0, 27.56 + nx / 65_500.0, 5f, v), sec * 1000L)
        }
        return t
    }

    @Test fun theSatellitesSpeedGivesTheDistanceAndASteadyPace() {
        repeat(10) { seed ->
            val t = run(speed = true, seed = seed)
            assertTrue(abs(t.distanceM - 2000) < 2000 * 0.02, "seed $seed: ${t.distanceM}")
            val pace = assertNotNull(t.currentPace())
            assertTrue(abs(pace - 300) < 6, "seed $seed: $pace")
        }
        // Positions alone zig-zag with the noise: the speed is the better measure here.
        val byPos = (0 until 10).map { abs(run(speed = false, seed = it).distanceM - 2000) }.average()
        val bySpeed = (0 until 10).map { abs(run(speed = true, seed = it).distanceM - 2000) }.average()
        assertTrue(bySpeed < byPos, "speed $bySpeed vs positions $byPos")
    }

    @Test fun standingStillAddsNothingWithTheSpeed() {
        val rnd = Random(1)
        val t = RunTracker(RunWorkout(RunType.FREE, "Свободный бег", emptyList()))
        t.start(0)
        for (sec in 0..300) {
            t.onFix(Fix(sec * 1000L, 53.9 + rnd.nextDouble(-5.0, 5.0) / 111_195.0, 27.56 + rnd.nextDouble(-5.0, 5.0) / 65_500.0, 6f, rnd.nextDouble(0.0, 0.4).toFloat()), sec * 1000L)
        }
        assertEquals(0.0, t.distanceM, 1e-9)
        assertNull(t.currentPace())
    }

    @Test fun aTrafficLightPausesFromTheStopAndRunningGoesOn() {
        val a = AutoPause()
        // Running.
        assertNull(a.update(3.2f, 0, running = true))
        // Stopped from 10 s: after 6 s the pause, dated from the stop.
        assertNull(a.update(0.2f, 10_000, running = true))
        assertNull(a.update(0.1f, 13_000, running = true))
        assertEquals(AutoPause.Act.Pause(10_000), a.update(0.0f, 16_000, running = true))
        assertTrue(a.paused)
        // A step or two is not running again; two fixes of running are.
        assertNull(a.update(1.0f, 30_000, running = false))
        assertNull(a.update(2.5f, 31_000, running = false))
        assertEquals(AutoPause.Act.Resume, a.update(2.8f, 32_000, running = false))
        assertTrue(!a.paused)
        // Paused by hand: nothing automatic until it runs again.
        a.reset()
        assertNull(a.update(0.0f, 40_000, running = false))
        assertNull(a.update(0.0f, 50_000, running = false))
        // A short stop (5 s) is not a pause.
        assertNull(a.update(0.1f, 60_000, running = true))
        assertNull(a.update(0.1f, 64_000, running = true))
        assertNull(a.update(3.0f, 65_000, running = true))
        assertNull(a.update(0.1f, 66_000, running = true))
    }
}
