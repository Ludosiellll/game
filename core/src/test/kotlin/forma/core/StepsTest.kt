package forma.core

import forma.core.run.Fix
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.Stride
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone's step counter: cadence, the stride learnt where GPS is good, the distance where it is not. */
class StepsTest {
    private fun free() = RunTracker(RunWorkout(RunType.FREE, "Свободный бег", emptyList())).apply { start(0) }

    @Test fun cadenceIsStepsAMinute() {
        val t = free()
        var total = 5_000L
        t.onSteps(total, 0)
        for (sec in 1..60) {
            total += if (sec % 3 == 0) 2 else 3
            t.onSteps(total, sec * 1000L)
        }
        val c = assertNotNull(t.cadence())
        assertTrue(abs(c - 160) <= 3, "$c")
    }

    @Test fun withoutGpsTheStepsGiveTheDistance() {
        val t = free()
        // 1.2 m a stride at 170 steps a minute.
        t.setStride(1.2, 0.0)
        var total = 0L
        t.onSteps(total, 0)
        for (halfSec in 1..1200) {
            total += if (halfSec % 3 == 0) 1 else 2
            t.onSteps(total, halfSec * 500L)
        }
        // About 10 minutes at ~200 steps a minute… minus the first seconds before the cadence is known.
        val expected = total * 1.2
        assertTrue(abs(t.distanceM - expected) < expected * 0.03, "${t.distanceM} vs $expected")
        // Steps while paused do not count.
        val before = t.distanceM
        t.pause(600_500)
        total += 300
        t.onSteps(total, 700_000)
        t.resume(700_000)
        total += 2
        t.onSteps(total, 701_000)
        assertTrue(t.distanceM - before < 5.0, "${t.distanceM - before}")
    }

    @Test fun withGoodGpsTheStepsOnlyLearnTheStride() {
        val t = free()
        t.setStride(1.0, 0.0)
        var total = 0L
        t.onSteps(total, 0)
        // 10 minutes at 3.4 m/s and 180 steps a minute: a stride of 1.13 m.
        for (sec in 1..600) {
            total += 3
            t.onSteps(total, sec * 1000L)
            t.onFix(Fix(sec * 1000L, 53.9, 27.56 + 3.4 * sec / 65_500.0, 4f, 3.4f), sec * 1000L)
        }
        // The distance is GPS's, not the steps' (1.0 m × 1800 = 1800 m).
        assertTrue(abs(t.distanceM - 2040) < 40, "${t.distanceM}")
        val samples = t.strideSamples()
        assertTrue(samples.size >= 15, "${samples.size}")
        assertTrue(samples.all { abs(it.second - 1.133) < 0.05 && abs(it.first - 180) < 3 }, samples.toString())
        val (a, b) = assertNotNull(Stride.fit(samples))
        assertEquals(1.133, a + b * 180, 0.03)
    }

    /**
     * The steps lead while GPS is not there yet (a slow lock at the start) — and give the distance back once it is, even
     * with the counter reporting several times a second (each report used to wipe GPS's anchor).
     */
    @Test fun gpsTakesTheDistanceBackFromTheSteps() {
        val t = free()
        // A stride model 25 % too long: the steps alone would say 2520 m.
        t.setStride(1.4, 0.0)
        var total = 0L
        t.onSteps(total, 0)
        for (k in 1..1500) {
            val ms = k * 400L
            total += if (k % 5 == 0) 2 else 1
            t.onSteps(total, ms)
            if (ms % 1000 == 0L && ms >= 15_000) t.onFix(Fix(ms, 53.9, 27.56 + 3.4 * ms / 1000.0 / 65_500.0, 4f, 3.4f), ms)
        }
        // 3.4 m/s for 10 minutes: 2040 m, give or take the first seconds on the steps.
        assertTrue(abs(t.distanceM - 2040) < 60, "${t.distanceM}")
        assertTrue(t.strideSamples().isNotEmpty(), "the stride is learnt again once GPS is back")
    }

    /** The system closes the app mid-run: the steps so far and the stride come back with the run. */
    @Test fun stepsAndTheStrideSurviveARestore() {
        val t = free()
        t.setStride(1.2, 0.0)
        var total = 0L
        t.onSteps(total, 0)
        for (sec in 1..120) {
            total += 3
            t.onSteps(total, sec * 1000L)
        }
        val back = RunTracker.restore(t.snapshot(120_000), 120_000)
        assertEquals(t.steps, back.steps)
        back.resume(120_000)
        // The counter goes on from wherever it is: a new baseline.
        var again = 900_000L
        back.onSteps(again, 120_000)
        for (sec in 121..240) {
            again += 3
            back.onSteps(again, sec * 1000L)
        }
        // Distance from the steps after the restore too: about 2 minutes × 180 × 1.2 m less the first 10 s.
        assertTrue(back.distanceM - t.distanceM > 350, "${back.distanceM} after ${t.distanceM}")
        val c = assertNotNull(back.result(240_000, 5).cadence)
        assertTrue(abs(c - 180) <= 3, "$c")
    }

    /** Intervals with standing rests: the cadence is the running one, not halved by the rests. */
    @Test fun theCadenceIsWhileRunning() {
        val t = free()
        var total = 0L
        t.onSteps(total, 0)
        for (sec in 1..600) {
            if ((sec - 1) / 60 % 2 == 0) total += 3
            t.onSteps(total, sec * 1000L)
        }
        val c = assertNotNull(t.result(600_000, 5).cadence)
        assertTrue(abs(c - 180) <= 3, "$c")
    }

    @Test fun theStrideModelNeedsEnoughOfTheRunner() {
        assertNull(Stride.fit(listOf(170.0 to 1.1, 172.0 to 1.12)))
        // Longer strides at higher cadence: a rising line.
        val rising = (0..9).map { (160.0 + it * 4) to (1.0 + it * 0.02) }
        val (a, b) = assertNotNull(Stride.fit(rising))
        assertTrue(b > 0)
        assertEquals(1.18, a + b * 196, 0.01)
        // Noise in one cadence: one stride.
        assertEquals(0.0, Stride.fit((0..9).map { 170.0 to (1.1 + (it % 3) * 0.01) })!!.second)
    }
}
