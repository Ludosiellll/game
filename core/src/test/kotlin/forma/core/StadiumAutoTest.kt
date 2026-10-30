package forma.core

import forma.core.model.RunGoal
import forma.core.run.Fix
import forma.core.run.Oval
import forma.core.run.Plane
import forma.core.run.RunPlan
import forma.core.run.RunState
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A whole session on a stadium with no press at all: GPS with the error of a real run, the runner standing at the line,
 * starting, running the reps, walking back to the line. The app starts each rep, counts and times each lap and ends
 * each rep by itself, to tenths; a free run's laps likewise, and the loop's real length is measured.
 */
class StadiumAutoTest {
    private val oval = Oval(0.0, 0.0, 0.3, 25.5, 16.3)
    private val plane = Plane(45.0, 10.0)

    /**
     * GPS once a second: the error of a real run (1.3 m, lasting ~4 s), the satellites' speed ±0.15 m/s. [swing]: the
     * phone held in the hand — the arm swings it back and forth with the steps (m/s at its fastest, at [cadence] steps a
     * minute), and the satellites see the hand's speed, not the body's, at the moment of each fix.
     */
    private class Gps(seed: Int, private val swing: Double = 0.0, private val cadence: Double = 174.0) {
        private val rnd = Random(seed)
        private fun gauss() = sqrt(-2 * kotlin.math.ln(1 - rnd.nextDouble())) * cos(2 * PI * rnd.nextDouble())
        private var ex = gauss() * 1.3
        private var ey = gauss() * 1.3
        private val keep = exp(-1.0 / 4.0)
        // From the seed, not drawn: the runs without a swing keep their noise as before.
        private val phase = seed * 2.399 % (2 * PI)
        fun fix(ms: Long, x: Double, y: Double, speed: Double, plane: Plane): Fix {
            // An arm swings once every two steps; standing, it does not.
            val hand = if (abs(speed) > 0.5) swing * kotlin.math.sin(2 * PI * cadence / 120.0 * ms / 1000.0 + phase) else 0.0
            val f = Fix(ms, plane.lat(y + ey), plane.lon(x + ex), (1.5 + abs(gauss()) * 0.4).toFloat(), (abs(speed + hand) + gauss() * 0.15).coerceAtLeast(0.0).toFloat())
            ex = keep * ex + sqrt(1 - keep * keep) * 1.3 * gauss()
            ey = keep * ey + sqrt(1 - keep * keep) * 1.3 * gauss()
            return f
        }
    }

    /** The loop's real length from GPS on a free run (set as 230 m, run on 204): laps are the presses, the length is GPS's. */
    @Test fun aFreeRunsLoopHasItsRealLengthMeasured() {
        val rnd = Random(7)
        // Set as 230 m; the loop run is 204 m.
        val t = RunTracker(RunWorkout(RunType.FREE, "Свободный бег", emptyList(), lapMeters = 230))
        val gps = Gps(7)
        t.start(0)
        val p = oval.perimeter
        var arc = 0.0
        var time = 0.0
        var nextFix = rnd.nextDouble()
        val truth = mutableListOf<Double>()
        while (time < 12 * 70.0) {
            time += 0.05
            val v = if (time < 3) 0.0 else 3.0 + 0.1 * kotlin.math.sin(time / 20)
            val before = arc
            arc += v * 0.05
            if (Math.floor(arc / p) > Math.floor(before / p)) {
                truth += time - (arc - Math.floor(arc / p) * p) / v
            }
            if (time >= nextFix) {
                nextFix += 1.0
                val (x, y) = oval.point(arc)
                t.onFix(gps.fix((time * 1000).toLong(), x, y, v, plane), (time * 1000).toLong())
            }
            if (((time * 1000).toLong()) % 250 < 50) t.autoLap((time * 1000).toLong())
        }
        repeat(10) {
            time += 1.0
            val (x, y) = oval.point(arc)
            t.onFix(gps.fix((time * 1000).toLong(), x, y, 3.0, plane), (time * 1000).toLong())
            arc += 3.0
            t.autoLap((time * 1000).toLong())
        }
        // GPS makes no laps: those are the presses.
        assertTrue(t.laps.isEmpty(), "${t.laps}")
        val lap = assertNotNull(t.lapLength())
        assertTrue(abs(lap - p) < 2.5, "measured $lap, ran $p")
    }
}
