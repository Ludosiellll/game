package forma.core

import forma.core.model.RunGoal
import forma.core.run.Cue
import forma.core.run.Fix
import forma.core.run.Oval
import forma.core.run.PaceRange
import forma.core.run.Plane
import forma.core.run.Press
import forma.core.run.RunEvent
import forma.core.run.RunPlan
import forma.core.run.RunState
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.Segment
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The runner holds the phone in his hand, screen on, and presses «Круг» on the line. What reached the laps by accident
 * (the volume key turned down twice in a jog, a thumb mid-lap) must not, and each rep starts when he starts it, not when
 * the recovery's clock runs out.
 */
class PhoneInHandTest {
    private val goal = RunGoal(1500, 335)
    private val warm = 12 * 60 * 1000L

    private fun cues(t: RunTracker, ms: Long) = t.tick(ms).mapNotNull { (it as? RunEvent.Timeline)?.cue }

    @Test fun aTouchSecondsAfterTheRepIsNoLapOfTheJog() {
        val t = RunTracker(RunPlan.goalPace(RunState(walkStage = -1), goal, 230))
        t.start(0)
        assertEquals(Press.Start, t.press(warm))
        assertNotNull(t.lap(warm + 51_000))
        // The voice says the lap; he turns it down with two touches 6 and 7 s later: 230 m in 6 s is no lap.
        assertEquals(Press.Ignored, t.press(warm + 57_000))
        assertEquals(Press.Ignored, t.press(warm + 58_000))
        assertEquals(1, t.laps.size)
    }

    @Test fun theNextRepWaitsForHimAfterTheRecovery() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, 230)
        val rest = w.segments.first { it.kind == SegmentKind.RECOVERY }.seconds * 1000L
        val t = RunTracker(w)
        t.start(0)
        assertEquals(Press.Start, t.press(warm))
        t.tick(warm + 1_000)
        assertNotNull(t.lap(warm + 51_000))
        t.tick(warm + 51_100)
        // The recovery's clock runs out: the plan stands at its end, no rep clock runs without him.
        val end = warm + 51_000 + rest
        val said = (1..33).flatMap { cues(t, end - 4_000 + it * 1_000L) }
        assertTrue(t.waiting(end + 30_000))
        assertTrue(said.none { it is Cue.SegmentStart }, said.toString())
        assertEquals(SegmentKind.RECOVERY, t.timeline(end + 30_000).segment!!.kind)
        // He goes 30 s later: the rep and its laps count from his press.
        assertEquals(Press.Start, t.press(end + 30_000))
        assertTrue(cues(t, end + 30_500).any { it is Cue.SegmentStart && it.segment.kind == SegmentKind.WORK })
        assertEquals(50.0, assertNotNull(t.lap(end + 80_000)).seconds, 1e-9)
        assertEquals(listOf(51.0, 50.0), t.repTimes().take(2))
    }

    @Test fun aPressInTheCountdownStartsTheRepThen() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, 230)
        val rest = w.segments.first { it.kind == SegmentKind.RECOVERY }.seconds * 1000L
        val t = RunTracker(w)
        t.start(0)
        assertEquals(Press.Start, t.press(warm))
        assertNotNull(t.lap(warm + 51_000))
        val end = warm + 51_000 + rest
        cues(t, end - 4_000)
        // On the line with 3 s of the recovery left: the start, and the countdown's last beeps are not played after it.
        assertEquals(Press.Start, t.press(end - 3_000))
        val next = cues(t, end - 2_900)
        assertTrue(next.none { it is Cue.Countdown }, next.toString())
        assertTrue(next.any { it is Cue.SegmentStart && it.segment.kind == SegmentKind.WORK }, next.toString())
        assertEquals(52.0, assertNotNull(t.lap(end + 49_000)).seconds, 1e-9)
    }

    @Test fun aRunSavedBeforeRepsWaitedKeepsItsClock() {
        val w = RunPlan.goalPace(RunState(walkStage = -1), goal, 230)
        val rest = w.segments.first { it.kind == SegmentKind.RECOVERY }.seconds * 1000L
        val t = RunTracker(w)
        t.start(0)
        assertEquals(Press.Start, t.press(warm))
        assertNotNull(t.lap(warm + 51_000))
        // Saved by the version before: its later reps ran by the clock, and still do after the update.
        val old = RunTracker.restore(t.snapshot(warm + 60_000).copy(waitEvery = false), warm + 60_000)
        old.resume(warm + 60_000)
        assertTrue(!old.waiting(warm + 51_000 + rest + 10_000))
        assertEquals(SegmentKind.WORK, old.timeline(warm + 51_000 + rest + 10_000).segment!!.kind)
    }

    // ---------- with GPS on the loop ----------

    private val oval = Oval(0.0, 0.0, 0.3, 25.5, 16.3)
    private val plane = Plane(45.0, 10.0)
    private val p = oval.perimeter

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

    /** Seconds, speed from, speed to (m/s). */
    private class Piece(val sec: Double, val from: Double, val to: Double)

    private fun lineAt(arc: Double): Pair<Double, Double> {
        val (x, y) = oval.point(arc)
        return plane.lat(y) to plane.lon(x)
    }

    /** One-lap reps at 51 s on the 204 m loop, 90 s of jog between. */
    private val oneLaps = run {
        val work = Segment(SegmentKind.WORK, 51, PaceRange(245, 255), laps = 1.0, lapSec = 51.0)
        RunWorkout(
            RunType.GOAL_PACE, "3 × 1 круг",
            listOf(Segment(SegmentKind.WARMUP, 60), work, Segment(SegmentKind.RECOVERY, 90), work, Segment(SegmentKind.RECOVERY, 90), work, Segment(SegmentKind.COOLDOWN, 60)),
            lapMeters = 204,
        )
    }

    /** Runs [pieces] from the line; GPS once a second, ticks every 250 ms; [press] says when he presses (its results kept). */
    private fun drive(t: RunTracker, pieces: List<Piece>, seed: Int, press: (Double, Double) -> Boolean = { _, _ -> false }): Drive {
        val gps = Gps(seed)
        val out = Drive()
        val dt = 0.05
        var time = 0.0
        var arc = 0.0
        var nextFix = 0.3
        var nextTick = 0.0
        t.start(0)
        for (pc in pieces) {
            val n = (pc.sec / dt).toInt()
            for (i in 0 until n) {
                val v = pc.from + (pc.to - pc.from) * i / n
                time += dt
                arc += v * dt
                val ms = (time * 1000).toLong()
                if (time >= nextFix) {
                    nextFix += 1.0
                    val (x, y) = oval.point(arc)
                    t.onFix(gps.fix(ms, x, y, v, plane), ms)
                }
                if (time >= nextTick) {
                    nextTick += 0.25
                    t.tick(ms)
                    if (t.autoStart(ms)) out.starts += time
                    t.autoLap(ms)
                }
                if (press(time, arc)) out.presses += t.press(ms)
            }
        }
        return out
    }

    private class Drive {
        val starts = mutableListOf<Double>()
        val presses = mutableListOf<Press>()
    }

    @Test fun aThumbMidLapIsNoLapWhileGpsHasHimOnTheLoop() {
        for (seed in 0 until 3) {
            val t = RunTracker(oneLaps)
            t.setLine(lineAt(0.0).first, lineAt(0.0).second)
            t.setLoop(oval.toGround(plane))
            val repSpeed = p / 51.0
            // A lap of warm-up onto the line, 8 s standing, the rep from standing; a touch 25 s into it (100 m from the line).
            val pieces = listOf(
                Piece(60.0, p / 60.0, p / 60.0),
                Piece(8.0, 0.0, 0.0),
                Piece(1.5, 0.0, repSpeed),
                Piece(51.0 - 0.75, repSpeed, repSpeed),
                Piece(3.0, repSpeed, 0.0),
                Piece(20.0, 0.0, 0.0),
            )
            var touched = false
            val run = drive(t, pieces, seed) { time, _ ->
                (!touched && time >= 68.0 + 25.0).also { if (it) touched = true }
            }
            assertEquals(listOf<Press>(Press.OffLine), run.presses, "seed $seed")
            // The lap is GPS's at the line, whole: from the onset, the ramp's 1.5 s and the rest at the rep's speed (51.75 s).
            val lap = t.laps.single { it.rep == 1 }
            assertTrue(lap.exact && abs(lap.seconds - 51.75) < 0.8, "seed $seed: ${t.laps}")
        }
    }

    @Test fun twoPressesAtOneSpotOffTheKeptLineMoveTheLine() {
        // A free run; the line kept from another day lies 70 m on (on the loop, the wrong place). He presses where he
        // starts and finishes his laps: the first press there is not taken, the second is — the line is there now.
        val t = RunTracker(RunWorkout(RunType.FREE, "Свободный бег", emptyList(), lapMeters = 204))
        t.setLine(lineAt(70.0).first, lineAt(70.0).second)
        t.setLoop(oval.toGround(plane))
        val v = p / 60.0
        var lastLap = 0
        val run = drive(t, listOf(Piece(6 * 60.0 + 10.0, v, v)), seed = 4) { _, arc ->
            val lap = (arc / p).toInt()
            // On his own line (arc 0) each lap from the second.
            (lap >= 2 && lap > lastLap).also { if (it) lastLap = lap }
        }
        println("presses ${run.presses} line ${t.line} laps ${t.laps.map { "%.1f%s".format(it.seconds, if (it.auto) "a" else "p") }}")
        assertEquals(Press.OffLine, run.presses.first())
        assertTrue(run.presses.drop(1).all { it is Press.Done }, run.presses.toString())
        val line = assertNotNull(t.line)
        val (lx, ly) = plane.x(line.second) to plane.y(line.first)
        val (x0, y0) = oval.point(0.0)
        assertTrue(kotlin.math.hypot(lx - x0, ly - y0) < 8.0, "line ${kotlin.math.hypot(lx - x0, ly - y0)} m off his")
    }

    @Test fun aPickupFromTheJogOnTheLineStartsTheNextRep() {
        for (seed in 0 until 3) {
            val t = RunTracker(oneLaps)
            t.setLine(lineAt(0.0).first, lineAt(0.0).second)
            t.setLoop(oval.toGround(plane))
            val repSpeed = p / 51.0
            // Rep 1 from standing (onset 68 s, done ≈ 119.75 s); 3 s easing off; then the jog round the loop, onto the line
            // a second after the recovery's end, and on through it into rep 2 — no stop, no press.
            val finish1 = 68.0 + 0.75 + 51.0
            val jogTo = finish1 + 90.0 + 1.0
            val eased = repSpeed * 1.5
            val jog = (p - eased) / (jogTo - finish1 - 3.0)
            val pieces = listOf(
                Piece(60.0, p / 60.0, p / 60.0),
                Piece(8.0, 0.0, 0.0),
                Piece(1.5, 0.0, repSpeed),
                Piece(51.0 - 0.75, repSpeed, repSpeed),
                Piece(3.0, repSpeed, 0.0),
                Piece(jogTo - finish1 - 3.0, jog, jog),
                Piece(1.5, jog, repSpeed),
                Piece(51.0 - 1.5 * (jog / repSpeed + 1) / 2, repSpeed, repSpeed),
                Piece(3.0, repSpeed, 0.0),
                Piece(20.0, 0.0, 0.0),
            )
            val run = drive(t, pieces, seed)
            println("seed $seed starts ${run.starts} reps ${t.repTimes()} laps ${t.laps}")
            assertEquals(2, run.starts.size, "seed $seed: both reps started by GPS, ${run.starts}")
            // Said a few seconds after he went (GPS sees the speed held first); the rep counts from the pickup itself.
            assertTrue(run.starts[1] - jogTo in 0.0..6.0, "seed $seed: rep 2's start said at ${run.starts[1]}, he went at $jogTo")
            // From his pickup to the line: the ramp, then the rest of the lap at the rep's speed.
            val truth = 1.5 + 51.0 - 1.5 * (jog / repSpeed + 1) / 2
            val rep2 = assertNotNull(t.repTimes()[1], "seed $seed: ${t.laps}")
            assertTrue(abs(rep2 - truth) < 0.8, "seed $seed: rep 2 $rep2, ran $truth")
            assertNull(t.repTimes()[2])
        }
    }
}
