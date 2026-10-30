package forma.core

import forma.core.model.RunGoal
import forma.core.run.Cue
import forma.core.run.Fix
import forma.core.run.LoopTimer
import forma.core.run.Oval
import forma.core.run.Plane
import forma.core.run.RunEvent
import forma.core.run.RunPlan
import forma.core.run.RunState
import forma.core.run.RunTracker
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
 * Stadium sessions at their edges, found by an independent review: a line from another stadium, a start half a lap back
 * reached by walking back over the line, strides and a jog to the line while the plan waits, a rep ending just before its
 * planned end, "Стоп" right after the finish, presses with GPS laps, a restore mid-rep.
 */
class StadiumEdgeTest {
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

    /** A piece of the run: seconds, speed from, speed to (m/s; negative walks back). */
    private class Piece(val sec: Double, val from: Double, val to: Double)

    private fun lineAt(arc: Double): Pair<Double, Double> {
        val (x, y) = oval.point(arc)
        return plane.lat(y) to plane.lon(x)
    }

    private val twoLaps = run {
        val work = forma.core.run.Segment(SegmentKind.WORK, 136, forma.core.run.PaceRange(330, 336), laps = 2.0, lapSec = 68.0)
        RunWorkout(
            forma.core.run.RunType.GOAL_PACE, "2 × 2 круга",
            listOf(forma.core.run.Segment(SegmentKind.WARMUP, 60), work, forma.core.run.Segment(SegmentKind.RECOVERY, 90), work, forma.core.run.Segment(SegmentKind.COOLDOWN, 60)),
            lapMeters = 204,
        )
    }

    /**
     * Drives a tracker through [pieces] on the oval from [arc0]; GPS once a second, ticks every 250 ms. Returns the
     * tick events with their active ms and the tracker.
     */
    private fun drive(t: RunTracker, pieces: List<Piece>, seed: Int, arc0: Double = 0.0, press: (Double) -> Boolean = { false }): List<Pair<Long, RunEvent>> {
        val gps = Gps(seed)
        val events = mutableListOf<Pair<Long, RunEvent>>()
        val dt = 0.05
        var time = 0.0
        var arc = arc0
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
                    t.tick(ms).forEach { events += ms to it }
                    t.autoStart(ms)
                    t.autoLap(ms)
                }
                if (press(time)) t.press(ms)
            }
        }
        return events
    }

    @Test fun halfLapStartReachedByWalkingBackIsTimedFromTheLine() {
        // LoopTimer alone, loop and line known. A lap forward over the line, stop 15 m past it, walk back to half a lap
        // before the line (-15 - p/2), stand, run two laps. Crossings: the lap forward, then at +p/2, +1.5p from the stand.
        for (seed in 0 until 3) {
            val timer = LoopTimer(204)
            timer.setGround(oval.toGround(plane))
            val (la, lo) = lineAt(0.0)
            timer.setLine(la, lo)
            val gps = Gps(seed)
            val pieces = listOf(
                Piece(5.0, 0.0, 0.0),
                Piece(2.0, 0.0, 3.5), Piece((p + 15.0) / 3.5, 3.5, 3.5), Piece(2.0, 3.5, 0.0),
                Piece(5.0, 0.0, 0.0),
                Piece((15.0 + p / 2) / 1.2, -1.2, -1.2),
                Piece(10.0, 0.0, 0.0),
                Piece(2.0, 0.0, 3.5), Piece(2 * p / 3.5, 3.5, 3.5),
                Piece(15.0, 3.5, 3.5),
            )
            var time = 0.0
            var arc = -3.5 // starting 3.5 m before the line (a step behind it), so the first run crosses it
            var nextFix = 0.3
            val found = mutableListOf<Pair<Double, Long>>()
            val truth = mutableListOf<Double>()
            val dt = 0.05
            var standStart = 0.0
            var secs = 0.0
            for ((i, pc) in pieces.withIndex()) {
                val n = (pc.sec / dt).toInt()
                if (i == 7) standStart = time
                for (k in 0 until n) {
                    val v = pc.from + (pc.to - pc.from) * k / n
                    val before = arc
                    time += dt
                    arc += v * dt
                    if (v > 0 && Math.floor(before / p) < Math.floor(arc / p)) truth += time
                    if (time >= nextFix) {
                        nextFix += 1.0
                        val (x, y) = oval.point(arc)
                        val f = gps.fix((time * 1000).toLong(), x, y, v, plane)
                        timer.fix((time * 1000).toLong(), f.lat, f.lon, f.accuracy, f.speed)
                        timer.crossings().forEach { found += it.activeMs / 1000.0 to it.index }
                    }
                }
                secs += pc.sec
            }
            println("seed $seed truth=$truth found=$found standStart=$standStart")
            // The first steps over the line at the very start come before the filter knows the way round (no lap anyway).
            val laps = truth.filter { it > 15 }
            assertEquals(laps.size, found.size, "seed $seed: every forward crossing of the line found: truth $truth, found $found")
            laps.zip(found).forEach { (a, b) -> assertTrue(abs(a - b.first) < 0.7, "seed $seed: $a vs ${b.first}") }
        }
    }

    @Test fun aLineFromAnotherStadiumDoesNotEndTheRepEarly() {
        // The stored loop is 300 m away (another stadium) and so is the stored line. Here the runner starts at the real line
        // (arc 0) and runs the rep of 2 laps. Where does the app put the line?
        val far = Oval(300.0, 0.0, 0.3, 25.5, 16.3)
        // The far line: in the direction of the point at 0.75 of the loop from the real line, far away.
        val (qx, qy) = oval.point(0.75 * p)
        val farLine = plane.lat(qy * 12) to plane.lon(qx * 12)
        for (seed in 0 until 3) {
            val t = RunTracker(twoLaps)
            t.setLine(farLine.first, farLine.second)
            t.setLoop(far.toGround(plane))
            val repSpeed = p / 68.0
            val pieces = listOf(
                Piece(60.0, p / 60.0, p / 60.0),
                Piece(8.0, 0.0, 0.0),
                Piece(1.5, 0.0, repSpeed),
                Piece(136.0 - 0.75, repSpeed, repSpeed),
                Piece(3.0, repSpeed, 0.0),
                Piece(30.0, 0.0, 0.0),
            )
            val events = drive(t, pieces, seed)
            val starts = events.filter { (it.second as? RunEvent.Timeline)?.cue is Cue.SegmentStart }
                .map { (ms, e) -> ms to (e as RunEvent.Timeline).cue as Cue.SegmentStart }
            val repEnd = (60 + 8 + 1.5 + 136 - 0.75) * 1000
            println("seed $seed laps=${t.laps} starts=${starts.map { "${it.first}:${it.second.index}" }} line=${t.line} repEnd=$repEnd")
            val recovery = starts.filter { it.second.index == 2 }.map { it.first }
            assertTrue(t.laps.all { !it.auto || abs(it.seconds - 68.0) < 2.0 }, "seed $seed: GPS laps of ~68 s, got ${t.laps}")
            assertTrue(recovery.all { it >= repEnd - 1000 }, "seed $seed: recovery said at $recovery, rep ends at $repEnd")
        }
    }

    @Test fun pressesAtEveryLineWithExactCrossingsMakeNoDoubleLaps() {
        for (seed in 0 until 3) {
            val t = RunTracker(twoLaps)
            t.setLine(lineAt(0.0).first, lineAt(0.0).second)
            t.setLoop(oval.toGround(plane))
            val repSpeed = p / 68.0
            val pieces = listOf(
                Piece(60.0, p / 60.0, p / 60.0),
                Piece(8.0, 0.0, 0.0),
                Piece(1.5, 0.0, repSpeed),
                Piece(136.0 - 0.75, repSpeed, repSpeed),
                Piece(3.0, repSpeed, 0.0),
                Piece(30.0, 0.0, 0.0),
            )
            // Presses: the start at 68.4 (0.4 s after going), the lines at +68.0 and +136 (+0.6 s late each), from the onset 68.0.
            val pressAt = listOf(68.4, 68.0 + 68.0 + 0.6, 68.0 + 136.0 + 0.6).map { (it * 1000).toLong() }
            val pressed = mutableSetOf<Long>()
            drive(t, pieces, seed) { time ->
                val ms = (time * 1000).toLong()
                val hit = pressAt.firstOrNull { it <= ms && it !in pressed }
                if (hit != null) pressed += hit
                hit != null
            }
            println("seed $seed presses: laps=${t.laps.map { "%.1f%s".format(it.seconds, if (it.auto) "a" else "p") }} reps=${t.repTimes()}")
            assertEquals(2, t.laps.size, "seed $seed: ${t.laps}")
            assertTrue(t.laps.all { abs(it.seconds - 68.0) < 1.5 }, "seed $seed: ${t.laps}")
        }
    }

    @Test fun aFreeRunStartedOffTheTrackKeepsNoLineOffIt() {
        val w = RunWorkout(forma.core.run.RunType.FREE, "Свободный бег", emptyList(), lapMeters = 204)
        val t = RunTracker(w)
        t.setLoop(oval.toGround(plane))
        val gps = Gps(3)
        t.start(0)
        var time = 0.0
        // 60 m inside the field (across from the line at arc 0: the centre is 16.3 m in; go to the far side).
        var x = 0.0
        var y = 60.0
        var nextFix = 0.3
        while (time < 25) {
            time += 0.05
            y -= 1.2 * 0.05 // walking to the track at 1.2 m/s... 60 m takes 50 s; the first 15 s decide the line.
            if (time >= nextFix) {
                nextFix += 1.0
                t.onFix(gps.fix((time * 1000).toLong(), x, y, 1.2, plane), (time * 1000).toLong())
            }
        }
        // No line yet, or one on the loop: never one in the field, kept for the next session.
        t.keptLine()?.let { line ->
            val off = oval.off(plane.x(line.second), plane.y(line.first))
            assertTrue(abs(off) < 15.0, "the line is $off m off the loop")
        }
    }

}
