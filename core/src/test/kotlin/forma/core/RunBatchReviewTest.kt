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

}
