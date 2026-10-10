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
                    // Standing and stepping about at the line until the plan says go.
                    s = Math.round(s / lapM) * lapM.toDouble() + sin(now / 3000.0) * 3
                    val tl = t.timeline(now)
                    if (tl.segment?.kind == SegmentKind.WORK && tl.index == workIdx[rep]) {
                        s = Math.round(s / lapM) * lapM.toDouble()
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
            assertEquals(8, repLaps.size, "seed $seed: ${run.tracker.laps}")
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
            assertEquals(8, run.tracker.laps.count { it.rep != null })
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

    @Test fun onAStadiumTheDistanceIsTheLaps() {
        val w = RunPlan.goalPace(RunState(walkStage = -1, goalStage = 2), goal, lapM)
        val run = session(w, forget = emptySet(), seed = 3, line = true)
        val t = run.tracker
        // Eight reps of a lap and the nine warm-up laps GPS saw (12 min at 2.9 m/s): whole laps, not GPS that cuts the bends.
        val laps = t.lapDistanceM() / lapM
        assertEquals(17.0, laps, 1e-9)
        assertTrue(t.shownDistanceM() >= t.lapDistanceM())
        assertEquals(t.shownDistanceM(), t.result(3_600_000, 7).distanceM)
    }
}
