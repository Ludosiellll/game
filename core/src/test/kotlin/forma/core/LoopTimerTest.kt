package forma.core

import forma.core.run.LoopTimer
import forma.core.run.Oval
import forma.core.run.Plane
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The line timed from GPS on simulated runs with the GPS error measured on a real stadium run (1.3 m a fix, lasting ~4 s,
 * a jump now and then, the satellites' speed ±0.15 m/s): every lap found, none made up, each crossing within tenths.
 */
class LoopTimerTest {
    private val denis = Oval(0.0, 0.0, 0.3, 25.5, 16.3)
    private val standard = Oval(0.0, 0.0, 1.0, 42.2, 36.5)
    private val plane = Plane(45.0, 10.0)

    /** Speed pieces: seconds, speed at the start and at the end (m/s). */
    private class Piece(val sec: Double, val from: Double, val to: Double)

    private class Sim(val fixes: List<DoubleArray>, val truth: List<Double>, val truthSpeed: List<Double>, val starts: List<Double>)

    /** Runs [pieces] round [oval] in a lane [lane] m out, GPS once a second; the line [lineArc] m round from the arc's 0. */
    private fun simulate(oval: Oval, pieces: List<Piece>, seed: Int, lineArc: Double, lane: Double = 0.6, sigma: Double = 1.3, tau: Double = 4.0): Sim {
        val rnd = Random(seed)
        fun gauss() = sqrt(-2 * kotlin.math.ln(1 - rnd.nextDouble())) * cos(2 * PI * rnd.nextDouble())
        val dt = 0.01
        val speeds = pieces.flatMap { p -> val n = (p.sec / dt).toInt(); List(n) { i -> p.from + (p.to - p.from) * i / n } }
        val scale = oval.perimeter / (oval.perimeter + 2 * PI * lane)
        val arc = DoubleArray(speeds.size)
        for (i in 1 until speeds.size) arc[i] = arc[i - 1] + speeds[i - 1] * dt * scale
        // True crossings of the line, and the starts from standing (speed rising from 0).
        val truth = mutableListOf<Double>()
        val truthSpeed = mutableListOf<Double>()
        // Each lap once: stepping back over the line and on again crosses it again, but ends no lap.
        var most = Double.NEGATIVE_INFINITY
        for (i in 1 until arc.size) {
            val a = Math.floor((arc[i - 1] - lineArc) / oval.perimeter)
            val b = Math.floor((arc[i] - lineArc) / oval.perimeter)
            if (b > a && b > most) {
                most = b
                val g = lineArc + b * oval.perimeter
                truth += (i - 1 + (g - arc[i - 1]) / (arc[i] - arc[i - 1])) * dt
                truthSpeed += speeds[i]
            }
        }
        val starts = (1 until speeds.size).filter { speeds[it - 1] == 0.0 && speeds[it] > 0.0 }.map { it * dt }
        val fixes = mutableListOf<DoubleArray>()
        var t = rnd.nextDouble()
        var ex = gauss() * sigma
        var ey = gauss() * sigma
        val keep = exp(-1.0 / tau)
        while (t < speeds.size * dt) {
            val i = (t / dt).toInt().coerceAtMost(speeds.size - 1)
            val (px, py) = oval.point(arc[i])
            // Out to the lane: the outward normal of the loop at that point.
            val (qx, qy) = oval.point(arc[i] + 0.01)
            val hx = (qx - px) / 0.01
            val hy = (qy - py) / 0.01
            var x = px + hy * lane + ex
            var y = py - hx * lane + ey
            if (rnd.nextDouble() < 0.02) {
                x += gauss() * 6
                y += gauss() * 6
            }
            // The phone's own accuracy grows with its error (on the real run 1.5–2.2 m for an error of 1.3 m).
            val acc = 1.15 * sigma + abs(gauss()) * 0.4
            val v = maxOf(0.0, speeds[i] + gauss() * 0.15)
            fixes += doubleArrayOf(t, plane.lat(y), plane.lon(x), acc, v)
            ex = keep * ex + sqrt(1 - keep * keep) * sigma * gauss()
            ey = keep * ey + sqrt(1 - keep * keep) * sigma * gauss()
            t += 1.0
        }
        return Sim(fixes, truth, truthSpeed, starts)
    }

    private fun lineOf(oval: Oval, arc: Double, rnd: Random): Pair<Double, Double> {
        val (x, y) = oval.point(arc)
        return plane.lat(y + rnd.nextDouble(-0.7, 0.7)) to plane.lon(x + rnd.nextDouble(-0.7, 0.7))
    }

    private class Result(val errors: List<Double>, val lapErrors: List<Double>, val missed: Int, val extra: Int, val timer: LoopTimer)

    /** Feeds the run; crossings compared with the true ones at running speed after the loop was learnt. */
    private fun replay(sim: Sim, oval: Oval, lineArc: Double, seed: Int, lap: Int, known: List<Double>? = null): Result {
        val timer = LoopTimer(lap)
        known?.let { timer.setGround(it) }
        val (la, lo) = lineOf(oval, lineArc, Random(seed + 100))
        timer.setLine(la, lo)
        val found = mutableListOf<Double>()
        var fittedAt: Double? = null
        for (f in sim.fixes) {
            timer.fix((f[0] * 1000).toLong(), f[1], f[2], f[3].toFloat(), f[4].toFloat())
            if (fittedAt == null && timer.oval != null) fittedAt = f[0]
            found += timer.crossings().map { it.activeMs / 1000.0 }
        }
        val from = (fittedAt ?: Double.MAX_VALUE) - 25.0
        val errors = mutableListOf<Double>()
        val matched = mutableListOf<Double?>()
        var missed = 0
        sim.truth.forEachIndexed { i, c ->
            val near = found.minByOrNull { abs(it - c) }?.takeIf { abs(it - c) < 5 }
            if (c >= from && c < sim.fixes.last()[0] - 6 && sim.truthSpeed[i] >= 2.5) {
                if (near == null) missed++ else errors += near - c
            }
            matched += near?.let { it - c }
        }
        val extra = found.count { f -> sim.truth.none { abs(it - f) < 5 } }
        val laps = matched.zipWithNext().mapIndexedNotNull { i, (a, b) ->
            if (a != null && b != null && sim.truthSpeed[i] >= 2.5 && sim.truthSpeed[i + 1] >= 2.5) b - a else null
        }
        return Result(errors, laps, missed, extra, timer)
    }

    private fun easy(rnd: Random) = listOf(Piece(5.0, 0.0, 3.0)) + List(12) { Piece(60.0, 3.0 + rnd.nextDouble(-0.15, 0.15), 3.0 + rnd.nextDouble(-0.15, 0.15)) }

    private fun reps(rnd: Random) = (0 until 5).flatMap {
        val v = 5.2 + rnd.nextDouble(-0.3, 0.3)
        listOf(Piece(8.0, 0.0, 0.0), Piece(1.5, 0.0, v * 0.7), Piece(1.5, v * 0.7, v)) +
            List(4) { Piece(20.0, v, v * (1 + rnd.nextDouble(-0.04, 0.04))) } + listOf(Piece(3.0, v, 1.0), Piece(60.0, 1.0, 1.0))
    }

    private fun kick() = listOf(Piece(3.0, 0.0, 4.0)) +
        List(3) { listOf(Piece(30.0, 4.0, 4.3), Piece(30.0, 4.3, 4.0), Piece(20.0, 4.0, 6.0), Piece(10.0, 6.0, 6.5), Piece(30.0, 6.5, 3.0)) }.flatten()

    private fun sd(xs: List<Double>): Double = xs.average().let { m -> sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size) }
    private fun p95(xs: List<Double>): Double = xs.map { abs(it) }.sorted()[(xs.size * 0.95).toInt().coerceAtMost(xs.size - 1)]
    private fun rms(xs: List<Double>): Double = sqrt(xs.sumOf { it * it } / xs.size)

    @Test fun everyLapFoundAndTimedToTenths() {
        val all = mutableListOf<Double>()
        val laps = mutableListOf<Double>()
        var missed = 0
        var extra = 0
        for ((name, oval) in listOf("207 m" to denis, "400 m" to standard)) {
            val lap = oval.perimeter.toInt()
            for (seed in 0 until 8) {
                for ((kind, pieces) in listOf("easy" to easy(Random(seed)), "reps" to reps(Random(seed)), "kick" to kick())) {
                    val sim = simulate(oval, pieces, seed * 7 + kind.length, lineArc = 5.0, lane = 0.4 + 0.5 * Random(seed).nextDouble())
                    val r = replay(sim, oval, 5.0, seed, lap)
                    assertNotNull(r.timer.oval, "$name $kind $seed: the loop fitted")
                    all += r.errors
                    laps += r.lapErrors
                    missed += r.missed
                    extra += r.extra
                }
            }
        }
        println("crossings ${all.size}: mean %.3f sd %.3f p95 %.3f max %.3f; laps ${laps.size}: rms %.3f p95 %.3f; missed $missed extra $extra"
            .format(all.average(), sd(all), p95(all), all.maxOf { abs(it) }, rms(laps), p95(laps)))
        assertEquals(0, missed, "laps missed")
        assertEquals(0, extra, "laps made up")
        assertTrue(abs(all.average()) < 0.15, "no lean: ${all.average()}")
        assertTrue(sd(all) < 0.35, "crossing sd ${sd(all)}")
        assertTrue(p95(all) < 0.7, "crossing p95 ${p95(all)}")
        assertTrue(rms(laps) < 0.4, "lap rms ${rms(laps)}")
    }

    @Test fun theOldWayOnTheSameRunsIsSecondsOff() {
        // The first fix within 15 m of the line, plus the metres left at the satellites' speed — what the app did before.
        val errs = mutableListOf<Double>()
        for (seed in 0 until 6) {
            val sim = simulate(denis, easy(Random(seed)), seed, lineArc = 5.0)
            val (lx, ly) = denis.point(5.0)
            var away = true
            val found = mutableListOf<Double>()
            for (f in sim.fixes) {
                val x = plane.x(f[2])
                val y = plane.y(f[1])
                val d = kotlin.math.hypot(x - lx, y - ly)
                if (d > 40) away = true else if (away && d <= 15) {
                    away = false
                    found += f[0] + d / maxOf(f[4], 0.5)
                }
            }
            sim.truth.forEach { c -> found.minByOrNull { abs(it - c) }?.takeIf { abs(it - c) < 10 }?.let { errs += it - c } }
        }
        println("old way: sd %.2f p95 %.2f".format(sd(errs), p95(errs)))
        assertTrue(sd(errs) > 0.8, "the old way was not this good: ${sd(errs)}")
    }

    @Test fun theLoopFromTheLastSessionTimesTheFirstLap() {
        val sim = simulate(denis, easy(Random(3)), 3, lineArc = 5.0)
        val known = Oval(0.3, -0.2, 0.3, 25.4, 16.9).toGround(plane)
        val r = replay(sim, denis, 5.0, 3, 207, known)
        // From the first lap on (the loop was known before the run): every crossing at running speed, timed.
        assertEquals(sim.truth.count { it > 10 && it < sim.fixes.last()[0] - 6 }, r.errors.size)
        assertEquals(0, r.missed)
        assertTrue(r.errors.all { abs(it) < 1.0 }, r.errors.toString())
    }

    @Test fun anotherStadiumIsNotTakenForTheLastOne() {
        val sim = simulate(standard, easy(Random(5)), 5, lineArc = 5.0)
        // The loop kept is 300 m away: dropped, and this one is fitted.
        val far = Oval(300.0, 0.0, 0.3, 25.5, 16.3).toGround(plane)
        val r = replay(sim, standard, 5.0, 5, 400, far)
        val o = assertNotNull(r.timer.oval)
        assertTrue(abs(o.perimeter - standard.perimeter) < 6, "${o.perimeter}")
        assertEquals(0, r.extra)
    }

    @Test fun theLapAsRunIsMeasured() {
        val sim = simulate(denis, easy(Random(9)), 9, lineArc = 5.0, lane = 0.6)
        val r = replay(sim, denis, 5.0, 9, 230)
        val measured = assertNotNull(r.timer.lapLength())
        // The lane run: the loop plus 2π·0.6 m. Set 230 m, run about 208 m — a tenth.
        val lane = denis.perimeter + 2 * PI * 0.6
        assertTrue(abs(measured - lane) < 2.5, "measured $measured, ran $lane")
    }

    @Test fun aStartFromStandingIsFoundToTenths() {
        val errs = mutableListOf<Double>()
        for (seed in 0 until 30) {
            val rnd = Random(seed)
            val v = 5.0 + rnd.nextDouble(-0.4, 0.4)
            val up = 1.5 + rnd.nextDouble() * 1.5
            val pieces = listOf(Piece(5.0, 0.0, 2.5), Piece(60.0, 2.5, 2.5), Piece(4.0, 2.5, 0.0), Piece(15.0, 0.0, 0.0), Piece(up, 0.0, v), Piece(40.0, v, v))
            val sim = simulate(denis, pieces, seed, lineArc = 5.0)
            val onset = 5.0 + 60 + 4 + 15
            val timer = LoopTimer(207)
            var found: Long? = null
            for (f in sim.fixes) {
                timer.fix((f[0] * 1000).toLong(), f[1], f[2], f[3].toFloat(), f[4].toFloat())
                timer.crossings()
                if (found == null && f[0] > onset - 10) found = timer.runUp(((onset - 8) * 1000).toLong(), 0.75 * v)
            }
            errs += (assertNotNull(found, "seed $seed") / 1000.0 - onset)
        }
        println("start: mean %.2f sd %.2f p95 %.2f".format(errs.average(), sd(errs), p95(errs)))
        assertTrue(abs(errs.average()) < 0.3 && sd(errs) < 0.3, errs.toString())
    }

    @Test fun roughGpsLosesNoLapAndMakesNone() {
        val errs = mutableListOf<Double>()
        for (seed in 0 until 6) {
            // Twice the error, lasting twice as long (trees, stands): worse, but every lap still there, and only once.
            val sim = simulate(denis, easy(Random(seed)), 40 + seed, lineArc = 5.0, sigma = 2.6, tau = 8.0)
            val r = replay(sim, denis, 5.0, seed, 207)
            assertEquals(0, r.missed, "seed $seed")
            assertEquals(0, r.extra, "seed $seed")
            errs += r.errors
        }
        println("rough: sd %.2f p95 %.2f".format(sd(errs), p95(errs)))
        assertTrue(sd(errs) < 0.6, "${sd(errs)}")
    }

    @Test fun standingAboutAtTheLineIsNoLap() {
        // A lap, then a minute shuffling to and fro across the line, then another lap: two crossings, not more.
        val pieces = listOf(Piece(3.0, 0.0, 3.0), Piece(68.0, 3.0, 3.0)) + List(6) { listOf(Piece(5.0, 0.8, 0.8), Piece(5.0, -0.8, -0.8)) }.flatten() +
            listOf(Piece(72.0, 3.0, 3.0))
        val sim = simulate(denis, pieces, 21, lineArc = 205.0)
        val r = replay(sim, denis, 205.0, 21, 207, Oval(0.0, 0.0, 0.3, 25.5, 16.3).toGround(plane))
        assertEquals(0, r.extra)
    }

    /**
     * A real run: 18 laps on a stadium, pressed (two filled in by hand later), GPS without the satellites' speed (not kept
     * then). The presses themselves are a runner's: a metre or two either side of the line. Laps found match them.
     */
    @Test fun aRealRunsLapsMatchItsPresses() {
        val rows = javaClass.getResourceAsStream("/stadium_run.csv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { l -> l.split(",").map { it.toDouble() } }
        val timer = LoopTimer(230)
        // He started on the line: the first fix is it (a few metres either way only moves every crossing alike).
        timer.setLine(plane.lat(rows[0][2]), plane.lon(rows[0][1]))
        val crossings = mutableListOf<Pair<Double, Int>>()
        var gap = 0.0
        var seg = 0
        val bounds = mutableListOf(0.0)
        var prev: Double? = null
        for (r in rows) {
            // The run's pauses: no fixes, and no active time.
            if (prev != null && r[0] - prev > 5) {
                gap += r[0] - prev - 1.0
                timer.pause()
                timer.crossings().forEach { crossings += it.activeMs / 1000.0 to seg }
                seg++
                bounds += r[0] - gap
            }
            prev = r[0]
            timer.fix(((r[0] - gap) * 1000).toLong(), plane.lat(r[2]), plane.lon(r[1]), r[3].toFloat(), null)
            timer.crossings().forEach { crossings += it.activeMs / 1000.0 to seg }
        }
        val pressed = listOf(76.274, 75.29, 76.901, 63.661, 65.525, 60.786, 60.478, 58.926, 94.884, 69.317, 71.781, 106.7775, 106.7775, 79.884, 74.378, 73.644, 75.036, 71.54)
            .runningReduce { a, b -> a + b }
        val byHand = setOf(11, 12)
        val presses = pressed.withIndex().filter { it.index !in byHand }.map { (i, t) -> Triple(i, t, bounds.indexOfLast { it <= t + 1.5 }) }
        // The phone's clock and this file's line up within a second or two per part of the run: aligned by the median.
        val residuals = mutableListOf<Double>()
        val lapDiffs = mutableListOf<Double>()
        for (s in bounds.indices) {
            val ps = presses.filter { it.third == s }
            val cs = crossings.filter { it.second == s }.map { it.first }
            if (ps.isEmpty()) continue
            val shift = ps.map { p -> cs.minBy { abs(it - p.second) } - p.second }.sorted().let { it[it.size / 2] }
            val near = ps.map { p -> p to cs.minBy { abs(it - shift - p.second) } }
            near.forEach { (p, c) -> residuals += c - shift - p.second }
            near.zipWithNext().filter { (a, b) -> b.first.first == a.first.first + 1 }.forEach { (a, b) -> lapDiffs += (b.second - a.second) - (b.first.second - a.first.second) }
        }
        println("real run: ${crossings.size} crossings, presses off by sd %.2f max %.2f, laps rms %.2f (n=${lapDiffs.size}), lap ${timer.lapLength()}"
            .format(sd(residuals), residuals.maxOf { abs(it) }, rms(lapDiffs)))
        assertEquals(16, residuals.size)
        assertTrue(residuals.all { abs(it) < 1.5 }, residuals.toString())
        assertTrue(sd(residuals) < 0.6, "${sd(residuals)}")
        assertTrue(rms(lapDiffs) < 1.0, "${rms(lapDiffs)}")
        // The loop he ran is about 207 m, not the 230 m set.
        val lap = assertNotNull(timer.lapLength())
        assertTrue(lap in 200.0..213.0, "$lap")
    }

    @Test fun walkingAboutIsNoRunUp() {
        val pieces = listOf(Piece(5.0, 0.0, 2.5), Piece(80.0, 2.5, 2.5), Piece(5.0, 2.5, 0.0), Piece(20.0, 0.0, 0.0), Piece(3.0, 0.0, 1.4), Piece(30.0, 1.4, 1.4))
        val sim = simulate(denis, pieces, 11, lineArc = 5.0)
        val timer = LoopTimer(207)
        for (f in sim.fixes) {
            timer.fix((f[0] * 1000).toLong(), f[1], f[2], f[3].toFloat(), f[4].toFloat())
            assertNull(timer.runUp(((f[0] - 20) * 1000).toLong().coerceAtLeast(90_000), 3.75).takeIf { f[0] > 92 }, "at ${f[0]}")
        }
    }
}
