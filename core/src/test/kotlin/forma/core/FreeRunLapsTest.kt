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
     * The line known, a free run started anywhere on the lap, nothing pressed. Started at the line the laps count from
     * the start; started off it, from the first time on the line — never a part of a lap told as a lap (150 m before
     * the line used to give a "lap" of 45 s), never a first crossing missed for a start 30 m before it.
     */
    @Test fun aRunStartedAnywhereGetsWholeLaps() {
        val whole = lapM / 3.2
        for (s0 in listOf(0.0, 40.0, 80.0, 120.0, 160.0, 200.0, 226.0)) for (seed in 0 until 3) {
            val sim = sim(free(), s0, 6, seed)
            val laps = sim.t.laps
            // A start a few metres from the line is a start at it: the laps count from the start.
            val expected = if (s0 == 0.0 || lapM - s0 <= 10) 6 else 5
            assertEquals(expected, laps.size, "start $s0, seed $seed: ${secs(laps)}")
            assertTrue(laps.all { abs(it.seconds - whole) <= 3.5 }, "start $s0, seed $seed: ${secs(laps)}")
            assertEquals((1..expected).toList(), laps.map { it.number })
        }
    }

    /** An easy run started 150 m before the line: no "fast" first lap (it was a part of one), slower is fine. */
    @Test fun anEasyRunStartedOffTheLineSaysNoFastFirstLap() {
        val easy = RunPlan.onTrack(RunPlan.easy(RunState(walkStage = -1, vdot = 46.0, easyMinutes = 25)), lapM)
        for (seed in 0 until 3) {
            val sim = sim(easy, 80.0, 5, seed, speed = { 2.9 })
            assertTrue(sim.t.laps.isNotEmpty())
            assertTrue(sim.t.laps.none { it.verdict == -1 }, "seed $seed: ${sim.t.laps}")
            assertTrue(sim.t.laps.all { abs(it.seconds - lapM / 2.9) <= 3.5 }, "seed $seed: ${secs(sim.t.laps)}")
        }
    }

    /** Started off the line, every crossing pressed: the first press starts the laps, it closes none (no part of a lap). */
    @Test fun theFirstPressAfterAStartOffTheLineIsTheStart() {
        for (seed in 0 until 3) {
            val sim = sim(free(), 80.0, 5, seed, press = { 0L })
            assertEquals(Press.Start, sim.presses.first(), "seed $seed")
            assertEquals(4, sim.t.laps.size, "seed $seed: ${secs(sim.t.laps)}")
            assertTrue(sim.t.laps.all { abs(it.seconds - lapM / 3.2) <= 0.6 && !it.auto }, "seed $seed: ${secs(sim.t.laps)}")
        }
    }

    /** The first crossing pressed 10 s late (GPS has marked the line by then): the press for that visit, not a 10 s lap. */
    @Test fun aLatePressForTheFirstVisitIsNoLap() {
        for (seed in 0 until 3) {
            val sim = sim(free(), 80.0, 4, seed, press = { k -> if (k == 1) 10_000L else null })
            assertEquals(Press.Start, sim.presses.single(), "seed $seed")
            // The laps after it count from the press: the first is 10 s short of a lap, then whole ones.
            assertTrue(sim.t.laps.none { it.seconds < 40 }, "seed $seed: ${secs(sim.t.laps)}")
        }
    }

    /**
     * The line the phone keeps is 20–45 m past the real one (learnt from late presses); every lap pressed on the real
     * line, on time. The laps are the presses: GPS passing its line in between never cuts one short. Only the first,
     * counted from where GPS saw that line, is off by the way between the two.
     */
    @Test fun pressedLapsAreThePressesWhereverTheLineIs() {
        for (off in listOf(20.0, 45.0)) for (seed in 0 until 3) {
            val sim = sim(free(), 0.0, 6, seed, line = off, press = { 0L })
            val truth = (listOf(0L) + sim.crossings).zipWithNext { a, b -> (b - a) / 1000.0 }
            val laps = sim.t.laps
            assertEquals(6, laps.size, "line $off, seed $seed: ${secs(laps)}")
            assertTrue(laps.none { it.auto }, "line $off, seed $seed: ${secs(laps)}")
            assertTrue(abs(laps.first().seconds - truth.first()) <= off / 3.2 + 1.5, "line $off, seed $seed: ${secs(laps)}")
            assertTrue(laps.drop(1).zip(truth.drop(1)).all { (l, tr) -> abs(l.seconds - tr) <= 0.6 }, "line $off, seed $seed: ${secs(laps)} vs $truth")
        }
    }

    /** No line known yet: no GPS laps until a press on the line teaches it; from then on GPS fills the laps not pressed. */
    @Test fun withNoLineKnownTheFirstPressTeachesIt() {
        for (seed in 0 until 3) {
            val sim = sim(free(), 0.0, 6, seed, line = null, press = { k -> if (k == 1) 0L else null })
            val laps = sim.t.laps
            assertTrue(laps.first().let { !it.auto && abs(it.seconds - lapM / 3.2) <= 0.6 }, "seed $seed: ${secs(laps)}")
            assertEquals(6, laps.size, "seed $seed: ${secs(laps)}")
            assertTrue(laps.drop(1).all { it.auto && abs(it.seconds - lapM / 3.2) <= 3.5 }, "seed $seed: ${secs(laps)}")
        }
    }

    /** Run/walk: the laps go on across the run and walk segments, numbered through the run, with no verdict on them. */
    @Test fun runWalkLapsGoOnAcrossItsSegments() {
        val rw = RunPlan.onTrack(RunPlan.runWalk(RunState(walkStage = 4, vdot = 30.0)), lapM)
        assertEquals(0, rw.reps)
        for (seed in 0 until 3) {
            val sim = sim(rw, 0.0, 8, seed, speed = { k -> if (k == SegmentKind.RUN) 2.6 else 1.4 })
            val truth = (listOf(0L) + sim.crossings).zipWithNext { a, b -> (b - a) / 1000.0 }
            val laps = sim.t.laps
            assertEquals(truth.size, laps.size, "seed $seed: ${secs(laps)} vs $truth")
            assertEquals((1..laps.size).toList(), laps.map { it.number })
            // Walking, a few metres of GPS are seconds: each lap within 6 s, and the errors do not add up.
            assertTrue(laps.zip(truth).all { (l, tr) -> abs(l.seconds - tr) <= 6.0 }, "seed $seed: ${secs(laps)} vs $truth")
            assertEquals(truth.sum(), laps.sumOf { it.seconds }, 6.0, "seed $seed")
            assertTrue(laps.all { it.verdict == null })
        }
    }

    /** The app closed by the system mid-run and the run restored: the laps go on from the same visit of the line. */
    @Test fun aRestoreKeepsWhereTheLapsCountFrom() {
        val first = sim(free(), 80.0, 3, 5, stopAtMs = 100_000)
        assertEquals(0, first.t.laps.size)
        val back = RunTracker.restore(first.t.snapshot(first.endMs), first.endMs)
        val sim = sim(free(), 80.0, 3, 6, tracker = back, startMs = first.endMs, sStart = first.s)
        assertTrue(sim.t.laps.size >= 2, secs(sim.t.laps).toString())
        assertTrue(sim.t.laps.all { abs(it.seconds - lapM / 3.2) <= 3.5 }, secs(sim.t.laps).toString())
    }
}
