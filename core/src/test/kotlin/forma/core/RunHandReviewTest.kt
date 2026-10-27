package forma.core

import forma.core.model.RunGoal
import forma.core.run.Fix
import forma.core.run.Lap
import forma.core.run.LapEdit
import forma.core.run.Oval
import forma.core.run.PaceRange
import forma.core.run.Plane
import forma.core.run.Press
import forma.core.run.RunPlan
import forma.core.run.RunState
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import forma.core.run.Track
import forma.core.text.Ru
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * From the review of the running part with the phone in the hand (an independent agent; each checked against the code
 * before it was fixed): a lap GPS timed said twice for a late press, a first press long into a warm-up or a free run
 * taken for a lap of minutes, «52. В темпе.» against a target shown as «50–51 с», a stray lap between reps that history
 * could not remove.
 */
class RunHandReviewTest {
    private val goal = RunGoal(1500, 330)
    private val lapSet = 230

    /** Denis's race-pace session as the plan builds it: 12 min warm-up, 5 × 1 lap, a jog between, a cool-down. */
    private fun denis() = RunPlan.goalPace(RunState(walkStage = -1), goal, lapSet)

    private val oval = Oval(0.0, 0.0, 0.3, 25.5, 16.3)
    private val plane = Plane(45.0, 10.0)

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

    /**
     * Denis on his 204 m loop (set 230), GPS on, the loop and the line known; each rep waits for him on the line and he
     * goes a moment after the wait begins; he presses «Круг» [react] seconds after each crossing. How many times a lap is
     * said: each lap GPS gives ([RunTracker.autoLap]) and each [Press.Done] of the button.
     */
    private fun saidLaps(seed: Int, react: Double): Pair<Int, RunTracker> {
        val rnd = Random(seed)
        val w = denis()
        val work = w.segments.indices.filter { w.segments[it].kind == SegmentKind.WORK }
        val p = oval.perimeter
        val t = RunTracker(w)
        t.setLoop(oval.toGround(plane))
        val (lx, ly) = oval.point(0.0)
        t.setLine(plane.lat(ly), plane.lon(lx))
        val gps = Gps(seed)
        t.start(0)
        var arc = 0.0
        var v = 0.0
        var phase = "warm"
        var rep = 0
        var target = 0.0
        var standUntil = 0.0
        var repSpeed = 0.0
        var pressAt = -1.0
        var said = 0
        val dt = 0.05
        var time = 0.0
        var nextFix = rnd.nextDouble()
        while (time < 3600 && phase != "done") {
            time += dt
            val ms = (time * 1000).toLong()
            when (phase) {
                "warm" -> {
                    v = 2.8
                    if (t.waiting(ms) && Math.floor((arc + v * dt) / p) > Math.floor(arc / p)) {
                        arc = Math.ceil(arc / p) * p
                        v = 0.0
                        phase = "stand"
                        standUntil = time + 6 + rnd.nextDouble() * 4
                    }
                }
                "stand" -> {
                    v = 0.0
                    if (rep > 0 && standUntil == 0.0 && t.waiting(ms)) standUntil = time + 0.4 + rnd.nextDouble() * 1.2
                    if (standUntil > 0.0 && time >= standUntil) {
                        phase = "rep"
                        repSpeed = p / 50.5 * (1 + rnd.nextDouble(-0.03, 0.03))
                        target = arc + p
                    }
                }
                "rep" -> {
                    v = minOf(repSpeed, v + 3.0 * dt)
                    if (arc + v * dt >= target) {
                        pressAt = time + (target - arc) / v + react
                        phase = "slow"
                        rep++
                    }
                }
                "slow" -> {
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
                            standUntil = 0.0
                        }
                    }
                }
            }
            arc += v * dt
            if (pressAt >= 0 && time >= pressAt) {
                pressAt = -1.0
                if (t.press(ms) is Press.Done) said++
            }
            if (time >= nextFix) {
                nextFix += 1.0
                val (x, y) = oval.point(arc)
                t.onFix(gps.fix(ms, x, y, v, plane), ms)
            }
            if (ms % 250 < 50) {
                t.autoStart(ms)
                if (t.autoLap(ms) != null) said++
                t.tick(ms)
            }
        }
        repeat(10) {
            time += 1.0
            val (x, y) = oval.point(arc)
            val ms = (time * 1000).toLong()
            t.onFix(gps.fix(ms, x, y, 0.0, plane), ms)
            if (t.autoLap(ms) != null) said++
        }
        return said to t
    }

    @Test fun aPressThatOnlyTimesAGpsLapIsNotSaidASecondTime() {
        val (said, t) = saidLaps(seed = 3, react = 7.0)
        val laps = t.laps.filter { it.rep != null }
        assertEquals(5, laps.size, "${t.laps}")
        assertTrue(laps.all { it.auto && it.exact }, "every lap was GPS's: ${t.laps}")
        assertEquals(laps.size, said, "each lap is said once (GPS's or the press), not twice")
    }

    @Test fun pressingAtTheLineSaysEachLapOnce() {
        val (said, t) = saidLaps(seed = 1, react = 0.4)
        assertEquals(5, t.laps.count { it.rep != null })
        assertEquals(5, said)
    }

    /** The warm-up with an easy lap to judge by (the plan's own has none on some days). */
    private fun paced() = denis().let { w -> w.copy(segments = w.segments.mapIndexed { i, s -> if (i == 0) s.copy(pace = PaceRange(330, 360), lapSec = Track.lapSec(345.0, lapSet)) else s }) }

    @Test fun onTheLineInTheWarmUpsLastMinuteThePressStartsTheFirstRep() {
        // At 11:50 he is on the line (the voice said at 11:00 to come to the start) and presses: the first rep starts then.
        val t = RunTracker(paced())
        t.start(0)
        assertEquals(Press.Start, t.press(710_000))
        assertEquals(SegmentKind.WORK, t.timeline(711_000).segment!!.kind)
        assertTrue(t.laps.isEmpty(), "${t.laps}")
        assertEquals(51.0, assertIs<Press.Done>(t.press(761_000)).lap.seconds, 1e-9)
    }

    @Test fun aFirstPressLongIntoTheWarmUpIsNoLapOfMinutes() {
        val t = RunTracker(paced())
        t.start(0)
        // Five minutes in, the first press: the warm-up's laps count from here.
        assertEquals(Press.Start, t.press(300_000))
        assertTrue(t.laps.isEmpty(), "${t.laps}")
        val l = assertIs<Press.Done>(t.press(385_000)).lap
        assertEquals(85.0, l.seconds, 1e-9)
    }

    @Test fun aFreeRunsFirstPressAfterAWalkToTheLineIsTheStart() {
        val own = RunWorkout(RunType.FREE, "Свободный бег", emptyList(), lapMeters = lapSet, targetPace = PaceRange(322, 330))
        val t = RunTracker(own)
        t.start(0)
        assertEquals(Press.Start, t.press(40_000))
        assertEquals(75.0, assertIs<Press.Done>(t.press(115_000)).lap.seconds, 1e-9)
    }

    @Test fun aLapSaidOnPaceIsInsideTheTargetAsShown() {
        val fast = Track.lapSec(217.0, lapSet)
        val slow = Track.lapSec(223.0, lapSet)
        assertEquals("50–51 с", Ru.lapRange(fast, slow))
        // «52» against «50–51 с»: slow, as said; «51» on pace; «49» fast.
        assertEquals(1, Lap(51.6, fast, slow, 1, 1, 1).verdict)
        assertEquals(0, Lap(51.4, fast, slow, 1, 1, 1).verdict)
        assertEquals(0, Lap(49.6, fast, slow, 1, 1, 1).verdict)
        assertEquals(-1, Lap(49.4, fast, slow, 1, 1, 1).verdict)
        for (s in listOf(49.0, 49.5, 50.2, 51.49, 51.5, 52.0)) {
            val l = Lap(s, fast, slow, 1, 1, 1)
            assertEquals(l.verdict == 0, s.roundToInt() in fast.roundToInt()..slow.roundToInt(), "$s")
        }
    }

    @Test fun aStrayLapBetweenRepsCanBeRemoved() {
        val laps = listOf(Lap(51.0, 50.0, 52.0, 1, 1, 1), Lap(6.0, number = 1), Lap(50.5, 50.0, 52.0, 1, 1, 2))
        val fixed = LapEdit.remove(laps, 1)
        assertEquals(listOf(51.0, 50.5), fixed.map { it.seconds })
        // A rep's lap stays: the plan was judged on it.
        assertEquals(laps, LapEdit.remove(laps, 0))
    }
}
