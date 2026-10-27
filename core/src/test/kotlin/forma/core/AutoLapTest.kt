package forma.core

import forma.core.model.RunGoal
import forma.core.run.Fix
import forma.core.run.Press
import forma.core.run.RunPlan
import forma.core.run.RunState
import forma.core.run.RunTracker
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * GPS on a 230 m stadium: two straights and two bends, fixes once a second a few metres off. The button stays the
 * timer; GPS fills a lap nobody pressed and gives the distance the bends would otherwise cut.
 */
class AutoLapTest {
    private val lapM = 230
    private val r = 22.0
    private val straight = (lapM - 2 * PI * r) / 2
    private val lat0 = 53.9
    private val lon0 = 27.56

    /** A point [s] metres along the lap from the line (the start of a straight), in local metres. */
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

    private fun fix(s: Double, ms: Long, rnd: Random, noise: Double = 3.0): Fix {
        val (x, y) = at(s)
        val nx = x + rnd.nextDouble(-1.0, 1.0) * noise * 1.7
        val ny = y + rnd.nextDouble(-1.0, 1.0) * noise * 1.7
        return Fix(ms, lat0 + ny / 111_195.0, lon0 + nx / (111_195.0 * cos(Math.toRadians(lat0))), 5f)
    }

    private class Run(val tracker: RunTracker, val trueLaps: List<Double>, val pressed: List<Boolean>)

    /**
     * Race pace on the stadium: a warm-up jog, the start pressed on the line, reps of whole laps run evenly, rests
     * standing near the line. [forget]: reps whose last press is forgotten.
     */
    private fun session(w: RunWorkout, forget: Set<Int>, seed: Int, line: Boolean): Run {
        val rnd = Random(seed)
        val t = RunTracker(w)
        if (line) {
            val (x, y) = at(0.0)
            t.setLine(lat0 + y / 111_195.0, lon0 + x / (111_195.0 * cos(Math.toRadians(lat0))))
        }
        t.start(0)
        val dt = 250L
        var now = 0L
        var s = 0.0
        var phase = "warm"
        var rep = 0
        var repStart = 0L
        val trueLaps = mutableListOf<Double>()
        val pressed = mutableListOf<Boolean>()
        val workIdx = w.segments.indices.filter { w.segments[it].kind == SegmentKind.WORK }
        var lapStart = 0L
        var lapsLeft = 0
        while (now < 3_600_000 && phase != "done") {
            now += dt
            when (phase) {
                "warm" -> {
                    val before = s
                    s += 2.9 * dt / 1000
                    // Waiting on the line: the next time the line comes, press and go.
                    if (t.waiting(now) && (before % lapM) > (s % lapM)) {
                        s = Math.round(s / lapM) * lapM.toDouble()
                        assertEquals(Press.Start, t.press(now))
                        phase = "rep"; repStart = now; lapStart = now
                        lapsLeft = w.segments[workIdx[0]].laps!!.toInt()
                    }
                }
                "rep" -> {
                    val seg = w.segments[workIdx[rep]]
                    val v = lapM / seg.lapSec!!
                    val before = s
                    s += v * dt / 1000
                    if ((before % lapM) > (s % lapM)) {
                        s = Math.round(s / lapM) * lapM.toDouble()
                        trueLaps += (now - lapStart) / 1000.0
                        lapStart = now
                        lapsLeft--
                        val last = lapsLeft == 0
                        val forgot = last && rep in forget
                        pressed += !forgot
                        if (!forgot) t.press(now)
                        if (last) {
                            rep++
                            phase = if (rep >= workIdx.size) "done" else "rest"
                        }
                    }
                }
                "rest" -> {
                    // Standing and stepping about at the line until the recovery is over; the rep waits for him there and
                    // starts with his press.
                    s = Math.round(s / lapM) * lapM.toDouble() + sin(now / 3000.0) * 3
                    if (t.waiting(now)) {
                        s = Math.round(s / lapM) * lapM.toDouble()
                        assertEquals(Press.Start, t.press(now))
                        phase = "rep"; lapStart = now; repStart = now
                        lapsLeft = w.segments[workIdx[rep]].laps!!.toInt()
                    }
                }
            }
            if (now % 1000 == 0L) {
                t.onFix(fix(s, now, rnd), now)
                t.autoLap(now)
            }
        }
        // The last crossing gets its wait.
        repeat(12) {
            now += 1000
            t.onFix(fix(s, now, rnd), now)
            t.autoLap(now)
        }
        return Run(t, trueLaps, pressed)
    }

    private val goal = RunGoal(1500, 335)

    @Test fun aForgottenPressIsFilledByGpsWithinTwoSeconds() {
        val w = RunPlan.goalPace(RunState(walkStage = -1, goalStage = 2), goal, lapM)
        repeat(20) { seed ->
            val run = session(w, forget = setOf(2, 5), seed = seed, line = true)
            val repLaps = run.tracker.laps.filter { it.rep != null }
            assertEquals(7, repLaps.size, "seed $seed: ${run.tracker.laps}")
            val autos = repLaps.withIndex().filter { it.value.auto }
            assertEquals(listOf(2, 5), autos.map { it.index }, "seed $seed: ${run.tracker.laps}")
            for ((i, l) in autos) assertTrue(abs(l.seconds - run.trueLaps[i]) <= 2.5, "seed $seed lap $i: ${l.seconds} vs ${run.trueLaps[i]}")
            // Pressed laps stay exactly what was pressed.
            for ((i, l) in repLaps.withIndex()) if (!l.auto) assertTrue(abs(l.seconds - run.trueLaps[i]) < 0.6, "seed $seed lap $i")
        }
    }

    @Test fun standingAtTheLineAndPressingEveryLapAddNothing() {
        val w = RunPlan.goalPace(RunState(walkStage = -1, goalStage = 2), goal, lapM)
        repeat(20) { seed ->
            val run = session(w, forget = emptySet(), seed = seed, line = true)
            assertTrue(run.tracker.laps.none { it.auto }, "seed $seed: ${run.tracker.laps}")
            assertEquals(7, run.tracker.laps.count { it.rep != null })
        }
    }

    @Test fun theLineIsLearntFromThePressesAndKeptOnARestore() {
        val w = RunPlan.goalPace(RunState(walkStage = -1, goalStage = 2), goal, lapM)
        // No line known before: the start press sets it, then forgotten presses are filled as before.
        val run = session(w, forget = setOf(4), seed = 7, line = false)
        val line = assertNotNull(run.tracker.line)
        val (x, y) = at(0.0)
        val dLat = (line.first - lat0) * 111_195.0 - y
        val dLon = (line.second - lon0) * 111_195.0 * cos(Math.toRadians(lat0)) - x
        assertTrue(dLat * dLat + dLon * dLon < 10.0 * 10.0, "line off by ${Math.sqrt(dLat * dLat + dLon * dLon)} m")
        assertTrue(run.tracker.laps.any { it.auto })
        val back = RunTracker.restore(run.tracker.snapshot(0), 0)
        assertEquals(run.tracker.line, back.line)
    }

    /** Crossing a point [at] (metres along the loop) between positions [a] and [b]. */
    private fun cross(a: Double, b: Double, at: Double) = Math.floorDiv((a - at).toLong(), lapM.toLong()) != Math.floorDiv((b - at).toLong(), lapM.toLong())

    /**
     * The first trial on a stadium with no line known yet: 6.5 laps start half a lap before the line, here in the middle of
     * the back straight (44 m across the field). The start press must not teach the line: laps end on the line only.
     */
    @Test fun aTrialStartedHalfALapBackKeepsItsLaps() {
        val lineAt = straight / 2
        val w = RunPlan.goalTest(RunState(walkStage = -1, vdot = 46.0), goal, lapM)
        val work = w.segments.first { it.kind == SegmentKind.WORK }
        assertEquals(6.5, work.laps)
        repeat(30) { seed ->
            val rnd = Random(seed)
            val t = RunTracker(w)
            t.start(0)
            var now = 0L
            var s = lineAt + lapM / 2.0
            var phase = "warm"
            var pressed = 0
            var raceStart = 0L
            var raceEnd = 0L
            while (phase != "done") {
                now += 250
                val before = s
                when (phase) {
                    "warm" -> {
                        s += 2.9 * 0.25
                        // The plan waits: on to the start, half a lap before the line, and go.
                        if (t.waiting(now) && cross(before, s, lineAt + lapM / 2.0)) {
                            assertEquals(Press.Start, t.press(now))
                            phase = "race"
                            raceStart = now
                        }
                    }
                    "race" -> {
                        s += lapM / work.lapSec!! * 0.25
                        if (cross(before, s, lineAt)) {
                            val p = t.press(now)
                            assertTrue(p is Press.Done, "seed $seed press ${pressed + 1}: $p")
                            if (++pressed == 7) {
                                phase = "cool"
                                raceEnd = now
                            }
                        }
                    }
                    "cool" -> {
                        s += 2.5 * 0.25
                        if (now - raceEnd > 120_000) phase = "done"
                    }
                }
                if (now % 1000 == 0L) {
                    t.onFix(fix(s, now, rnd), now)
                    t.autoLap(now)
                }
            }
            val laps = t.laps.filter { it.rep == 1 }
            assertEquals(7, laps.size, "seed $seed: ${t.laps}")
            assertTrue(laps.none { it.auto }, "seed $seed: ${t.laps}")
            assertEquals(0.5, laps.first().part)
            val time = assertNotNull(t.repTimes().first())
            assertEquals((raceEnd - raceStart) / 1000.0, time, 0.6, "seed $seed")
            assertEquals(time.roundToInt(), t.result(now, 9).testSeconds, "seed $seed")
            // The line is where the laps end.
            val line = assertNotNull(t.line)
            val (x, y) = at(lineAt)
            val dLat = (line.first - lat0) * 111_195.0 - y
            val dLon = (line.second - lon0) * 111_195.0 * cos(Math.toRadians(lat0)) - x
            assertTrue(dLat * dLat + dLon * dLon < 10.0 * 10.0, "seed $seed: line off by ${Math.sqrt(dLat * dLat + dLon * dLon)} m")
        }
    }

    /** In the warm-up a jog 50 m along the track and back over the line is no lap; a whole lap is one. */
    @Test fun aJogOffTheLineAndBackIsNoLap() {
        val w = RunPlan.goalPace(RunState(walkStage = -1, goalStage = 2), goal, lapM)
        repeat(10) { seed ->
            val rnd = Random(seed)
            val t = RunTracker(w)
            val (x, y) = at(0.0)
            t.setLine(lat0 + y / 111_195.0, lon0 + x / (111_195.0 * cos(Math.toRadians(lat0))))
            t.start(0)
            val path = mutableListOf<Double>()
            repeat(2) {
                (1..20).forEach { path += it * 2.5 }
                (19 downTo 0).forEach { path += it * 2.5 }
            }
            (1..80).forEach { path += it * 2.9 }
            var now = 0L
            for (p in path + List(12) { path.last() }) {
                now += 1000
                // Doppler speed as a phone gives it, so the way counts as run.
                t.onFix(fix(p, now, rnd).copy(speed = 2.6f), now)
                t.autoLap(now)
            }
            assertEquals(1.0, t.lapDistanceM() / lapM, 1e-9, "seed $seed")
        }
    }

    /**
     * Two laps a rep; the first crossing not pressed in time — the press comes 10 s late, after GPS closed the lap. It
     * times that lap: the rep is not ended after a 10-second "lap".
     */
    @Test fun aPressLateAfterAGpsLapTimesThatLap() {
        val work = forma.core.run.Segment(SegmentKind.WORK, 104, forma.core.run.PaceRange(224, 228), laps = 2.0, lapSec = 52.0)
        val w = RunWorkout(
            forma.core.run.RunType.GOAL_PACE, "2 × 2 круга",
            listOf(forma.core.run.Segment(SegmentKind.WARMUP, 60), work, forma.core.run.Segment(SegmentKind.RECOVERY, 90), work, forma.core.run.Segment(SegmentKind.COOLDOWN, 60)),
            lapMeters = lapM,
        )
        repeat(10) { seed ->
            val rnd = Random(seed)
            val t = RunTracker(w)
            val (x, y) = at(0.0)
            t.setLine(lat0 + y / 111_195.0, lon0 + x / (111_195.0 * cos(Math.toRadians(lat0))))
            t.start(0)
            var now = 0L
            var s = 0.0
            // Warm-up on the spot near the line until the plan waits, then the start press.
            while (!t.waiting(now)) {
                now += 1000
                t.onFix(fix(s, now, rnd), now)
            }
            assertEquals(Press.Start, t.press(now))
            val go = now
            var late: Press? = null
            var second: Press? = null
            while (now - go < 104_000) {
                now += 250
                s += lapM / 52.0 * 0.25
                if (now - go == 62_000L) late = t.press(now)
                if (now - go == 104_000L) second = t.press(now)
                if (now % 1000 == 0L) {
                    t.onFix(fix(s, now, rnd), now)
                    t.autoLap(now)
                }
            }
            val first = assertNotNull(late as? Press.Done, "seed $seed: $late").lap
            assertEquals(1, first.number, "seed $seed")
            assertEquals(62.0, first.seconds, 0.6, "seed $seed")
            assertTrue(!first.auto)
            val last = assertNotNull(second as? Press.Done, "seed $seed: $second").lap
            assertEquals(2, last.number, "seed $seed")
            assertEquals(104.0, assertNotNull(t.repTimes().first()), 0.6, "seed $seed")
            assertEquals(2, t.laps.count { it.rep == 1 })
        }
    }

    /** A free run on the stadium, no line known and no button pressed: GPS times every lap from where the run began. */
    private fun freeRun(seed: Int, lapSec: Double, laps: Int, target: forma.core.run.PaceRange? = null): Pair<RunTracker, Double> {
        val rnd = Random(seed)
        val w = RunWorkout(forma.core.run.RunType.FREE, "Свободный бег", emptyList(), lapMeters = lapM, targetPace = target)
        val t = RunTracker(w)
        // The line known from the presses of earlier runs on this stadium.
        t.setLine(lat0, lon0)
        t.start(0)
        // Starting somewhere along the back straight: the laps begin the first time on the line.
        val s0 = 120.0
        var s = s0
        var now = 0L
        val v = lapM / lapSec
        while (s < s0 + laps * lapM + 5) {
            now += 250
            s += v * 0.25
            if (now % 1000 == 0L) {
                t.onFix(fix(s, now, rnd).copy(speed = v.toFloat()), now)
                t.autoLap(now)
            }
        }
        repeat(12) {
            now += 1000
            s += v
            t.onFix(fix(s, now, rnd).copy(speed = v.toFloat()), now)
            t.autoLap(now)
        }
        return t to (now / 1000.0)
    }

    @Test fun aFreeRunCountsItsLapsWithoutPresses() {
        repeat(10) { seed ->
            val (t, _) = freeRun(seed, 72.0, 10)
            val laps = t.laps
            assertTrue(laps.size in 9..10, "seed $seed: ${laps.size} laps ${laps.map { it.seconds }}")
            assertTrue(laps.all { it.auto }, "seed $seed")
            // Every lap is a lap of 72 s give or take GPS's two or three seconds, and the errors do not add up: the laps
            // together are the time run.
            assertTrue(laps.all { abs(it.seconds - 72.0) <= 3.5 }, "seed $seed: ${laps.map { it.seconds }}")
            assertEquals(laps.size * 72.0, laps.sumOf { it.seconds }, 3.5, "seed $seed")
            assertEquals(laps.size * lapM.toDouble(), t.lapDistanceM(), 1e-9)
        }
    }

    @Test fun aFreeRunWithATargetJudgesEachLap() {
        // Target 75 s a lap (±1 s), run at 66: every lap is "fast" (a GPS lap only when clearly off: 2.5 s past the bound).
        val target = forma.core.run.PaceRange((74.0 / lapM * 1000).toInt(), (76.0 / lapM * 1000).toInt())
        val (t, _) = freeRun(3, 66.0, 6, target)
        assertTrue(t.laps.isNotEmpty())
        assertTrue(t.laps.all { it.verdict == -1 }, t.laps.toString())
        val (on, _) = freeRun(4, 75.0, 6, target)
        assertTrue(on.laps.count { it.verdict == 0 } >= on.laps.size - 1, on.laps.toString())
    }

    @Test fun onAStadiumTheDistanceIsTheLaps() {
        val w = RunPlan.goalPace(RunState(walkStage = -1, goalStage = 2), goal, lapM)
        val run = session(w, forget = emptySet(), seed = 3, line = true)
        val t = run.tracker
        // Seven reps of a lap and the nine warm-up laps GPS saw (12 min at 2.9 m/s): whole laps, not GPS that cuts the bends.
        val laps = t.lapDistanceM() / lapM
        assertEquals(16.0, laps, 1e-9)
        assertTrue(t.shownDistanceM() >= t.lapDistanceM())
        assertEquals(t.shownDistanceM(), t.result(3_600_000, 7).distanceM)
    }
}
