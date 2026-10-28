package forma.core

import forma.core.model.RunGoal
import forma.core.run.Fix
import forma.core.run.Lap
import forma.core.run.Oval
import forma.core.run.Plane
import forma.core.run.Press
import forma.core.run.RunLog
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunReview
import forma.core.run.RunState
import forma.core.run.RunTracker
import forma.core.run.SegmentKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The review of the loop / week-cap batch in the engine (Denis: 1500 m in 5:30, a stadium set as 230 m whose loop GPS
 * measures at about 205 m): what a runner would expect. Each test failed on c8820fe and passes since.
 */
class RunBatchReviewTest {
    private val goal = RunGoal(1500, 330)
    private val set = 230
    private val measured = 205

    private val denis = RunState(
        walkStage = -1, easyMinutes = 20, longMinutes = 35, runs = 5, qualityCount = 1, lastRunDay = 20752, easyRpes = listOf(7),
        recent = listOf(RunLog(20738, 24), RunLog(20738, 2), RunLog(20748, 25), RunLog(20749, 17), RunLog(20752, 36)),
    )

    /**
     * Finding: the debrief of a trial read on the loop no longer relates it to the goal. The history's review speaks of
     * the goal only for a trial within 3 % of its distance: 1495 m was, 1332 m (6,5 laps of the loop) is not — so the
     * runner reads «Контрольный: 1332 м за 5:00» and nothing about how far from 5:30 over 1500 m that is.
     */
    @Test fun theDebriefOfATrialReadOnTheLoopStillSpeaksOfTheGoal() {
        val w = RunPlan.goalTest(denis, goal, set)
        val work = w.segments.first { it.kind == SegmentKind.WORK }
        val laps = (0 until 7).map { i ->
            val part = if (i == 0) 0.5 else 1.0
            Lap(300.0 / 6.5 * part, work.lapSec!! * part, work.lapSec!! * part, i + 1, 7, 1, part)
        }
        val r = RunResult(1.0, 9, 1700, distanceM = 6.5 * set + 2000, laps = laps, testSeconds = 300, loopM = measured.toDouble())
        val (plan, result) = RunPlan.onMeasuredLoop(w, r)!!
        val trial = plan.testMeters!! to result.testSeconds!!
        val review = RunReview.of(RunReview.Run(plan, result.laps.orEmpty(), 9, 1700, null, null, trial, 1.0, goalReached = false), goal)
        val text = review.joinToString(" ") { it.text }
        assertTrue("цел" in text, "the debrief of the trial (${trial.first} m in ${trial.second} s): «$text»")
    }

    /** GPS once a second with the error of a real run (as the stadium tests model it: 1.3 m, lasting ~4 s). */
    private class Gps(seed: Int) {
        private val rnd = Random(seed)
        private fun gauss() = sqrt(-2 * ln(1 - rnd.nextDouble())) * cos(2 * PI * rnd.nextDouble())
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

    private fun cross(a: Double, b: Double, at: Double, p: Double) = Math.floor((a - at) / p) != Math.floor((b - at) / p)

    /**
     * Finding: the distance of a stadium run is read on the loop only when it is exactly the pressed laps × the setting.
     * As the tracker makes it on a stadium it knows (the line from the last session, as the app hands it over), the laps
     * GPS sees outside the reps — the warm-up and cool-down jogs, «Круги засчитает GPS» — are counted on the setting too,
     * and the distance is the laps' (more than GPS saw). So the stored distance of the trial run stays on 230 m a lap
     * (13 % long) while its plan, trial distance and laps are on the loop measured.
     */
    @Test fun theLapsGpsCountedOutsideTheRepsAreReadOnTheLoopToo() {
        val oval = Oval(0.0, 0.0, 0.3, 25.5, 16.3)
        val plane = Plane(45.0, 10.0)
        val p = oval.perimeter
        val w = RunPlan.goalTest(denis, goal, set)
        val t = RunTracker(w)
        // The line from the last session on this stadium (arc 0 of the loop).
        val (lx, ly) = oval.point(0.0)
        t.setLine(plane.lat(ly), plane.lon(lx))
        val gps = Gps(3)
        t.start(0)
        var now = 0L
        val start = p * 0.3
        var arc = start
        var phase = "warm"
        var pressed = 0
        var raceEnd = 0L
        var v = 2.8
        val race = 1000.0 / 225
        while (phase != "done") {
            now += 250
            val before = arc
            when (phase) {
                "warm" -> {
                    v = 2.8
                    arc += v * 0.25
                    if (t.waiting(now) && cross(before, arc, p / 2, p)) {
                        assertEquals(Press.Start, t.press(now))
                        phase = "race"
                    }
                }
                "race" -> {
                    v = race
                    arc += v * 0.25
                    if (cross(before, arc, 0.0, p)) {
                        val pr = t.press(now)
                        assertTrue(pr is Press.Done, "press ${pressed + 1}: $pr")
                        if (++pressed == 7) {
                            phase = "cool"
                            raceEnd = now
                        }
                    }
                }
                "cool" -> {
                    // A cool-down jog of three laps and a bit, the line crossed three times, no press.
                    v = 2.5
                    arc += v * 0.25
                    if (now - raceEnd > (3.2 * p / 2.5 * 1000).toLong()) phase = "done"
                }
            }
            if (now % 1000 == 0L) {
                val (x, y) = oval.point(arc)
                t.onFix(gps.fix(now, x, y, v, plane), now)
                t.autoLap(now)
            }
        }
        val result = t.result(now, 9)
        val loop = assertNotNull(t.lapLength())
        assertEquals(p, loop, 3.0)
        val pressed6 = result.laps!!.sumOf { it.part }
        val free = t.lapDistanceM() / set - pressed6
        assertTrue(free >= 8.0, "GPS counted $free laps outside the trial (the warm-up and cool-down)")
        // What was run: the way along the loop from the start.
        val ran = arc - start
        assertTrue(t.lapDistanceM() > t.distanceM, "the laps (${t.lapDistanceM().toInt()} m on $set) are more than GPS saw (${t.distanceM.toInt()} m): the distance is the laps'")
        val fixed = assertNotNull(RunPlan.onMeasuredLoop(w, result)).second
        assertEquals(
            ran, fixed.distanceM!!, ran * 0.03,
            "on the ${loop.toInt()} m loop the run was ${ran.toInt()} m ($pressed6 laps of the trial and $free laps GPS saw around it); " +
                "read on the loop it is stored as ${fixed.distanceM!!.toInt()} m — the tracker's ${result.distanceM!!.toInt()} m on $set a lap, untouched " +
                "(plan on ${RunPlan.onMeasuredLoop(w, result)!!.first.lapMeters} m)",
        )
    }
}
