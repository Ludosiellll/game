package forma.core

import forma.core.run.Fix
import forma.core.run.Lap
import forma.core.run.Press
import forma.core.run.RunPlan
import forma.core.run.RunState
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GPS laps of runs without reps on a 230 m stadium (free, easy, run/walk): wherever the run starts, whatever the
 * presses, the laps are whole laps. The line is at s = 0; fixes once a second, a few metres off, with the satellites'
 * speed.
 */
class FreeRunLapsTest {
    private val lapM = 230
    private val r = 22.0
    private val straight = (lapM - 2 * PI * r) / 2
    private val lat0 = 53.9
    private val lon0 = 27.56

    /** A point [s0] metres along the lap from the line (the start of a straight), in local metres. */
    private fun at(s0: Double): Pair<Double, Double> {
        val s = ((s0 % lapM) + lapM) % lapM
        val bend = PI * r
        return when {
            s < straight -> s to 0.0
            s < straight + bend -> {
                val th = -PI / 2 + (s - straight) / r
                straight + r * cos(th) to r + r * sin(th)
            }
            s < 2 * straight + bend -> straight - (s - straight - bend) to 2 * r
            else -> {
                val th = PI / 2 + (s - 2 * straight - bend) / r
                r * cos(th) to r + r * sin(th)
            }
        }
    }

    private fun latLon(s: Double): Pair<Double, Double> {
        val (x, y) = at(s)
        return lat0 + y / 111_195.0 to lon0 + x / (111_195.0 * cos(Math.toRadians(lat0)))
    }

    private fun fix(s: Double, ms: Long, rnd: Random, speed: Float): Fix {
        val (x, y) = at(s)
        val nx = x + rnd.nextDouble(-1.0, 1.0) * 5.1
        val ny = y + rnd.nextDouble(-1.0, 1.0) * 5.1
        return Fix(ms, lat0 + ny / 111_195.0, lon0 + nx / (111_195.0 * cos(Math.toRadians(lat0))), 5f, speed)
    }

    private class Sim(val t: RunTracker, val crossings: List<Long>, val presses: List<Press>, val endMs: Long, val s: Double)

    /**
     * A run from [s0] metres past the line, [laps] laps of the line crossed. [line]: where the tracker thinks the line
     * is (metres past the real one), null — not known. [press]: for the k-th crossing (from 1) the delay of the press,
     * null — none. [speed]: m/s by the kind of segment being run.
     */
    private fun sim(
        w: RunWorkout, s0: Double, laps: Int, seed: Int, line: Double? = 0.0, press: (Int) -> Long? = { null },
        speed: (SegmentKind?) -> Double = { 3.2 }, tracker: RunTracker? = null, startMs: Long = 0, sStart: Double? = null,
        stopAtMs: Long? = null,
    ): Sim {
        val rnd = Random(seed)
        val t = tracker ?: RunTracker(w).also { tr ->
            line?.let { val (a, b) = latLon(it); tr.setLine(a, b) }
            tr.start(0)
        }
        if (tracker != null) t.resume(startMs)
        var s = sStart ?: s0
        var now = startMs
        val crossings = mutableListOf<Long>()
        val presses = mutableListOf<Press>()
        val due = mutableListOf<Long>()
        val end = s0 + laps * lapM + 1
        while (s < end && (stopAtMs == null || now < stopAtMs)) {
            now += 250
            val v = speed(t.timeline(now).segment?.kind)
            val before = s
            s += v * 0.25
            if (Math.floorDiv(before.toLong(), lapM.toLong()) != Math.floorDiv(s.toLong(), lapM.toLong())) {
                crossings += now
                press(crossings.size)?.let { due += now + it }
            }
            due.filter { it <= now }.forEach {
                due.remove(it)
                presses += t.press(now)
            }
            if (now % 1000 == 0L) t.onFix(fix(s, now, rnd, v.toFloat()), now)
            t.autoLap(now)
        }
        repeat(12) {
            now += 1000
            val v = speed(t.timeline(now).segment?.kind)
            s += v
            t.onFix(fix(s, now, rnd, v.toFloat()), now)
            t.autoLap(now)
        }
        return Sim(t, crossings, presses, now, s)
    }

    private fun free() = RunWorkout(RunType.FREE, "Свободный бег", emptyList(), lapMeters = lapM)

    private fun secs(l: List<Lap>) = l.map { "%.1f".format(it.seconds) }

    /**
     * The line the phone keeps is 20–45 m past the real one (learnt from late presses); every lap pressed on the real
     * line, on time. The laps are the presses: GPS passing its line in between never cuts one short, and the first lap
     * counts from «Старт» (where GPS had the line says nothing).
     */
    @Test fun pressedLapsAreThePressesWhereverTheLineIs() {
        for (off in listOf(20.0, 45.0)) for (seed in 0 until 3) {
            val sim = sim(free(), 0.0, 6, seed, line = off, press = { 0L })
            val truth = (listOf(0L) + sim.crossings).zipWithNext { a, b -> (b - a) / 1000.0 }
            val laps = sim.t.laps
            assertEquals(truth.size, laps.size, "line $off, seed $seed: ${secs(laps)}")
            assertTrue(laps.none { it.auto }, "line $off, seed $seed: ${secs(laps)}")
            assertTrue(laps.zip(truth).all { (l, tr) -> abs(l.seconds - tr) <= 0.6 }, "line $off, seed $seed: ${secs(laps)} vs $truth")
        }
    }

}
