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
import kotlin.test.assertIs
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

    /**
     * A press is a lap wherever GPS has the runner (Denis, 30.10: «нажимаешь, а он не засчитывает — решил, что ты не
     * добежал»). The line kept from another day lies 70 m on; he starts and presses on his own line after a lap: once
     * «Круг не засчитан: ты не на линии», now the lap.
     */
    @Test fun aPressFarFromTheKeptLineIsALapAllTheSame() {
        for (seed in 0 until 3) {
            val t = RunTracker(oneLaps)
            t.setLine(lineAt(70.0).first, lineAt(70.0).second)
            t.setLoop(oval.toGround(plane))
            val repSpeed = p / 51.0
            val pieces = listOf(
                Piece(60.0, p / 60.0, p / 60.0),
                Piece(8.0, 0.0, 0.0),
                Piece(1.5, 0.0, repSpeed),
                Piece(51.0 - 0.75, repSpeed, repSpeed),
                Piece(3.0, repSpeed, 0.0),
                Piece(20.0, 0.0, 0.0),
            )
            var started = false
            var done = false
            val run = drive(t, pieces, seed) { time, _ ->
                (!started && time >= 68.0).also { if (it) started = true } ||
                    (!done && time >= 68.0 + 51.75).also { if (it) done = true }
            }
            assertEquals(Press.Start, run.presses.first(), "seed $seed")
            val lap = assertIs<Press.Done>(run.presses.drop(1).single(), "seed $seed: ${run.presses}").lap
            assertTrue(!lap.auto && abs(lap.seconds - 51.75) < 0.3, "seed $seed: ${t.laps}")
        }
    }

    /** A rep starts on a press only: a pickup from the jog on the line is no start without one (Denis, 30.10). */
    @Test fun aPickupFromTheJogOnTheLineIsNoStartWithoutAPress() {
        for (seed in 0 until 3) {
            val t = RunTracker(oneLaps)
            t.setLine(lineAt(0.0).first, lineAt(0.0).second)
            t.setLoop(oval.toGround(plane))
            val repSpeed = p / 51.0
            val pieces = listOf(
                Piece(60.0, p / 60.0, p / 60.0),
                Piece(8.0, 0.0, 0.0),
                Piece(1.5, 0.0, repSpeed),
                Piece(51.0 - 0.75, repSpeed, repSpeed),
                Piece(3.0, repSpeed, 0.0),
                Piece(20.0, 0.0, 0.0),
            )
            val run = drive(t, pieces, seed)
            assertTrue(run.starts.isEmpty(), "seed $seed: started by GPS, ${run.starts}")
            assertTrue(t.laps.isEmpty(), "seed $seed: laps GPS made ${t.laps}")
        }
    }
}
