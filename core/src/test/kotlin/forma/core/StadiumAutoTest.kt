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

    /** GPS once a second: the error of a real run (1.3 m, lasting ~4 s), the satellites' speed ±0.15 m/s. */
    private class Gps(seed: Int) {
        private val rnd = Random(seed)
        private fun gauss() = sqrt(-2 * kotlin.math.ln(1 - rnd.nextDouble())) * cos(2 * PI * rnd.nextDouble())
        private var ex = gauss() * 1.3
        private var ey = gauss() * 1.3
        private val keep = exp(-1.0 / 4.0)
        fun fix(ms: Long, x: Double, y: Double, speed: Double, plane: Plane): Fix {
            val f = Fix(ms, plane.lat(y + ey), plane.lon(x + ex), (1.5 + abs(gauss()) * 0.4).toFloat(), (abs(speed) + gauss() * 0.15).coerceAtLeast(0.0).toFloat())
            ex = keep * ex + sqrt(1 - keep * keep) * 1.3 * gauss()
            ey = keep * ey + sqrt(1 - keep * keep) * 1.3 * gauss()
            return f
        }
    }

    @Test fun repsWithNoPressStartedTimedAndEnded() {
        for (seed in 0 until 4) {
            val rnd = Random(seed)
            val w = RunPlan.goalPace(RunState(walkStage = -1, goalStage = 3), RunGoal(1500, 330), 207)
            val work = w.segments.indices.filter { w.segments[it].kind == SegmentKind.WORK }
            val lapsPerRep = w.segments[work[0]].laps!!.toInt()
            val p = oval.perimeter
            val t = RunTracker(w)
            val gps = Gps(seed)
            t.start(0)
            var arc = 0.0
            var v = 0.0
            var phase = "warm"
            var rep = 0
            var target = 0.0
            var standUntil = 0.0
            var repSpeed = 0.0
            val onsets = mutableListOf<Double>()
            val finishes = mutableListOf<Double>()
            val dt = 0.05
            var time = 0.0
            var nextFix = rnd.nextDouble()
            while (time < 3600 && phase != "done") {
                time += dt
                when (phase) {
                    "warm" -> {
                        v = 2.8
                        // The plan waits on the line: on to it, and stop there.
                        if (t.waiting((time * 1000).toLong()) && Math.floor((arc + v * dt) / p) > Math.floor(arc / p)) {
                            arc = Math.ceil(arc / p) * p
                            v = 0.0
                            phase = "stand"
                            standUntil = time + 6 + rnd.nextDouble() * 4
                        }
                    }
                    "stand" -> {
                        v = 0.0
                        val tl = t.timeline((time * 1000).toLong())
                        val go = if (rep == 0) time >= standUntil else tl.segment?.kind == SegmentKind.WORK && tl.index == work[rep] && time >= standUntil
                        if (go) {
                            phase = "rep"
                            onsets += time
                            repSpeed = p / 45.54 * (1 + rnd.nextDouble(-0.03, 0.03))
                            target = arc + lapsPerRep * p
                        }
                    }
                    "rep" -> {
                        v = minOf(repSpeed, v + 3.0 * dt)
                        if (arc + v * dt >= target) {
                            finishes += time + (target - arc) / v
                            phase = "slow"
                            rep++
                        }
                    }
                    "slow" -> {
                        // Easing off past the line, then walking back to it, then standing till the next rep's beep.
                        v = maxOf(0.0, v - 2.0 * dt)
                        if (v == 0.0) phase = "back"
                    }
                    "back" -> {
                        v = -1.0
                        if (arc + v * dt <= target) {
                            arc = target
                            v = 0.0
                            if (rep >= work.size) phase = "done" else {
                                phase = "stand"
                                // The beep, then a moment to react.
                                standUntil = 0.0
                            }
                        }
                    }
                }
                if (phase == "stand" && rep > 0 && standUntil == 0.0) {
                    val tl = t.timeline((time * 1000).toLong())
                    if (tl.segment?.kind == SegmentKind.WORK && tl.index == work[rep]) standUntil = time + 0.4 + rnd.nextDouble() * 1.2
                }
                arc += v * dt
                if (time >= nextFix) {
                    nextFix += 1.0
                    val (x, y) = oval.point(arc)
                    val ms = (time * 1000).toLong()
                    t.onFix(gps.fix(ms, x, y, v, plane), ms)
                }
                val ms = (time * 1000).toLong()
                if (ms % 250 < 50) {
                    t.autoStart(ms)
                    t.autoLap(ms)
                }
            }
            repeat(10) {
                time += 1.0
                val (x, y) = oval.point(arc)
                val ms = (time * 1000).toLong()
                t.onFix(gps.fix(ms, x, y, 0.0, plane), ms)
                t.autoLap(ms)
            }
            assertEquals(work.size, finishes.size, "seed $seed: all reps run")
            val times = t.repTimes()
            val truth = onsets.zip(finishes) { a, b -> b - a }
            println("seed $seed reps: " + times.zip(truth).joinToString { (a, b) -> "%.2f/%.2f".format(a ?: -1.0, b) })
            times.zip(truth).forEachIndexed { i, (got, real) ->
                assertNotNull(got, "seed $seed rep ${i + 1} timed")
                assertTrue(abs(got - real) < 0.8, "seed $seed rep ${i + 1}: $got vs $real")
            }
            val repLaps = t.laps.filter { it.rep != null }
            assertEquals(work.size * lapsPerRep, repLaps.size, "seed $seed: ${t.laps}")
            assertTrue(repLaps.all { it.auto && it.exact }, "seed $seed: every lap GPS's, to tenths")
        }
    }

    @Test fun aFreeRunsLapsAndTheLoopsRealLength() {
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
        val laps = t.laps
        val trueLaps = truth.zipWithNext { a, b -> b - a }
        println("free laps: " + laps.joinToString { "%.2f".format(it.seconds) } + " truth " + trueLaps.joinToString { "%.2f".format(it) })
        assertTrue(laps.size >= truth.size - 1, "${laps.size} of ${truth.size}")
        assertTrue(laps.all { it.auto && it.exact })
        // Lap by lap against the true ones from the second on (the first counts from the start).
        val diffs = laps.drop(1).map { l -> trueLaps.minBy { abs(it - l.seconds) } - l.seconds }
        assertTrue(diffs.all { abs(it) < 0.8 }, diffs.toString())
        val lap = assertNotNull(t.lapLength())
        assertTrue(abs(lap - p) < 2.5, "measured $lap, ran $p")
    }
}
