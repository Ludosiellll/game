package forma.core

import forma.core.model.RunGoal
import forma.core.run.Lap
import forma.core.run.LapEdit
import forma.core.run.Oval
import forma.core.run.Plane
import forma.core.run.Press
import forma.core.run.Fix
import forma.core.run.RunCaps
import forma.core.run.RunLog
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.SegmentKind
import forma.core.run.Track
import forma.core.run.Vdot
import forma.core.text.Ru
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The review of one runner's real runs (Denis, 1500 m in 5:30, a stadium set as 230 m whose loop GPS measures at about
 * 205 m): what a coach would decide on what he did. Each test failed before the laps were read on the loop GPS measured.
 */
class RunLoopReviewTest {
    private val goal = RunGoal(1500, 330)
    private val caps = RunCaps(60, 90)

    /** The lap length in his settings, and the loop as the app's own GPS fit measured it on his runs. */
    private val set = 230
    private val measured = 205

    /** His running progress as his phone holds it after 26.10: no trial yet, the first step of the race-pace ladder. */
    private val denis = RunState(
        walkStage = -1, easyMinutes = 20, longMinutes = 35, runs = 5, qualityCount = 1, lastRunDay = 20752, easyRpes = listOf(7),
        recent = listOf(RunLog(20738, 24), RunLog(20738, 2), RunLog(20748, 25), RunLog(20749, 17), RunLog(20752, 36)),
    )

    /** 26.10, «Темп цели · 5 × 1 круг»: his five reps, one lap each, as pressed (seconds). */
    private val reps26 = listOf(46.399, 49.982, 48.993, 47.286, 46.614)

    private fun pace(secPerKm: Double) = Ru.pace(secPerKm)

    /**
     * Finding: the race-pace verdict is read off the lap length in the settings. His 26.10 reps averaged 47.9 s a lap:
     * on 230 m that is 3:28/km, 5 % faster than the goal's 3:40 — «TooFastRepeat», the step again at 50–51 s a lap. On
     * the 205 m the loop measures, 47.9 s is 3:53/km: 6 % SLOWER than the goal, «TooSlow» by the plan's own rule. The
     * repeat it prescribes (50–51 s a lap) is 4:06–4:10/km on his loop — 12 % slower than the race he is training for.
     */
    @Test fun hisRacePaceRepsOnTheLoopGpsMeasuredAreSlowNotFast() {
        val before = denis.copy(qualityCount = 0, runs = 4)
        val w = RunPlan.goalPace(before, goal, set)
        assertEquals("Темп цели · 5 × 1 круг", w.title)
        val target = w.segments.first { it.kind == SegmentKind.WORK }
        assertEquals(50.6, target.lapSec!!, 0.01)
        val avg = reps26.average()
        val laps = reps26.mapIndexed { i, s -> Lap(s, 49.91, 51.29, 1, 1, i + 1) }
        // As his phone decided it, with no loop measured (work pace from the laps on 230 m).
        val old = RunPlan.after(
            before, w, RunResult(1.0, 7, 2142, distanceM = 20.0 * set, workPace = avg / set * 1000, laps = laps, reps = 5), caps, day = 20752, goal = goal,
        )
        assertTrue(RunOutcome.TooFastRepeat in old.outcomes, "reproduces his phone: ${old.outcomes}")
        // The same run as the tracker now reports it: the loop GPS fitted, 205 m.
        val atSet = RunPlan.after(
            before, w, RunResult(1.0, 7, 2142, distanceM = 20.0 * set, workPace = avg / set * 1000, laps = laps, reps = 5, loopM = measured.toDouble()),
            caps, day = 20752, goal = goal,
        )
        assertTrue(RunOutcome.LoopShorter(measured, set) in atSet.outcomes, "said: ${atSet.outcomes}")
        assertTrue(RunOutcome.TooSlow in atSet.outcomes, "read on the loop: ${atSet.outcomes}")
        // The same laps on the loop as measured: the plan's own rule says too slow.
        val wReal = RunPlan.goalPace(before, goal, measured)
        val atReal = RunPlan.after(
            before, wReal, RunResult(1.0, 7, 2142, distanceM = 20.0 * measured, workPace = avg / measured * 1000, laps = laps, reps = wReal.reps), caps, day = 20752, goal = goal,
        )
        assertTrue(RunOutcome.TooSlow in atReal.outcomes, "on the loop as measured: ${atReal.outcomes}")
        val repeatLap = Ru.lapRange(Track.lapSec(217.0, set), Track.lapSec(223.0, set))
        assertFalse(
            RunOutcome.TooFastRepeat in atSet.outcomes,
            "his 26.10 reps (avg ${Ru.one(avg)} s a lap) were called too fast: on $set m that is ${pace(avg / set * 1000)}/km against the goal's " +
                "${pace(goal.pace)}/km; on the $measured m the loop measures it is ${pace(avg / measured * 1000)}/km, " +
                "${((avg / measured * 1000 / goal.pace - 1) * 100).toInt()} % slower than the goal — the plan's own verdict there is ${atReal.outcomes}. " +
                "The repeat asks for $repeatLap a lap, which on his loop is ${pace(217.0 * set / measured)}–${pace(223.0 * set / measured)}/km.",
        )
    }

    /** GPS once a second with the error of a real run (as the stadium tests model it: 1.3 m, lasting ~4 s). */
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

    private fun cross(a: Double, b: Double, at: Double, p: Double) = Math.floor((a - at) / p) != Math.floor((b - at) / p)

    /**
     * Finding: the trial due on 29.10 is «6,5 кругов (1495 м)» on the 230 m setting. Run on the loop GPS measures at 204 m
     * — in the very same run, the tracker's own fit says so — it is 1329 m, yet its time is scored as 1495 m: the VDOT
     * comes out 7 points too high and the 5:30 goal is declared reached by a runner who ran 1329 m at 3:45/km. Every
     * pace after that (easy limit, tempo, intervals, the race-pace step) is then set from a fitness he does not have.
     */
    @Test fun aTrialOnTheLoopGpsMeasuresAt204mIsScoredOnThatLoop() {
        // The loop of the stadium tests: 204 m, what his GPS tracks fit to.
        val oval = Oval(0.0, 0.0, 0.3, 25.5, 16.3)
        val plane = Plane(45.0, 10.0)
        val p = oval.perimeter
        val w = RunPlan.goalTest(denis, goal, set)
        val work = w.segments.first { it.kind == SegmentKind.WORK }
        assertEquals(6.5, work.laps)
        assertEquals(1495, w.testMeters)
        val t = RunTracker(w)
        val gps = Gps(3)
        t.start(0)
        var now = 0L
        // The line is at arc 0 of the loop; the trial starts half a lap before it.
        var arc = p * 0.3
        var phase = "warm"
        var pressed = 0
        var raceStart = 0L
        var raceEnd = 0L
        var v = 2.8
        // 3:45 a km: about what he held on 26.10 at an effort of 7, run here for five minutes flat out.
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
                        raceStart = now
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
                    v = 2.5
                    arc += v * 0.25
                    if (now - raceEnd > 120_000) phase = "done"
                }
            }
            if (now % 1000 == 0L) {
                val (x, y) = oval.point(arc)
                t.onFix(gps.fix(now, x, y, v, plane), now)
                t.autoLap(now)
            }
        }
        val result = t.result(now, 9)
        val time = assertNotNull(result.testSeconds, "the trial's time from the laps")
        assertEquals((raceEnd - raceStart) / 1000.0, time.toDouble(), 1.5)
        val loop = assertNotNull(t.lapLength(), "the loop GPS fitted in this run")
        assertEquals(p, loop, 3.0)
        val after = RunPlan.after(denis, w, result, caps, day = 20755, goal = goal)
        val scored = after.outcomes.filterIsInstance<RunOutcome.VdotChanged>().single().to
        val ran = 6.5 * loop
        val real = Vdot.fromRace(ran, time.toDouble())
        val goalVdot = Vdot.fromRace(goal.meters.toDouble(), goal.seconds.toDouble())
        val easyAfter = RunPlan.paces(after.state)!!.easy
        assertFalse(
            RunOutcome.GoalReached in after.outcomes,
            "the trial was scored as ${w.testMeters} m in $time s: VDOT $scored, outcomes ${after.outcomes}. The tracker's own GPS fit measured the loop at " +
                "${loop.toInt()} m in this run, so 6,5 laps were ${ran.toInt()} m at ${pace(time / (ran / 1000))}/km: VDOT ${"%.1f".format(real)}, " +
                "against the goal's ${"%.1f".format(goalVdot)} — not reached. From now on his easy limit is ${pace(easyAfter.fast.toDouble())}/km " +
                "(${Ru.lap(Track.lapSec(easyAfter.fast.toDouble(), set))} a lap, which on his loop is ${pace(easyAfter.fast * set.toDouble() / loop)}/km).",
        )
    }

    /**
     * With the phone in his hand a touch mid-rep is a lap when it comes past 45 % of the lap time (30 s on a 50-s lap):
     * the rep is then «fast», the session «TooFastRepeat». The rep's lap gets its time put right after the run; decided
     * again on it, every rep is on pace.
     */
    @Test fun aStrayPressInsideARepCanBePutRightAfterTheRun() {
        val stray = Lap(30.0, 49.91, 51.29, 1, 1, 1)
        val laps = listOf(Lap(68.0, number = 1), stray) + (2..5).map { Lap(50.5, 49.91, 51.29, 1, 1, it) }
        val before = denis.copy(qualityCount = 0, runs = 4)
        val w = RunPlan.goalPace(before, goal, set)
        val lapPace = laps.filter { it.rep != null }.sumOf { it.seconds } / (5 * set / 1000.0)
        val after = RunPlan.after(before, w, RunResult(1.0, 7, 2142, workPace = lapPace, laps = laps, reps = 5), caps, day = 20752, goal = goal)
        assertTrue(RunOutcome.TooFastRepeat in after.outcomes, "the stray press decides the session: ${after.outcomes}")
        assertTrue(LapEdit.editable(laps, 0), "a warm-up lap can be put right in every way")
        assertFalse(LapEdit.editable(laps, 1), "a rep's lap only gets its time")
        val fixed = LapEdit.setTime(laps, 1, 50.5)
        assertEquals(50.5, fixed[1].seconds)
        assertEquals(1, fixed[1].rep)
        assertEquals(laps.size, fixed.size)
        val again = RunPlan.after(before, w, RunResult(1.0, 7, 2142, workPace = forma.core.run.ManualLaps.repPace(fixed, set), laps = fixed, reps = 5), caps, day = 20752, goal = goal)
        assertTrue(
            RunOutcome.TooFastRepeat !in again.outcomes && again.outcomes.any { it is RunOutcome.StageUp },
            "the 30-s touch in rep 1 made the reps' pace ${pace(lapPace)}/km and the session ${after.outcomes}; put right: ${again.outcomes}",
        )
    }

    /**
     * Sanity (expected to pass): the plan's verdict on an easy run is not the lap length's — the 22.10 run is «easy
     * pace, as it should be» on either length, and a run rated 7 shortens the easy run on either.
     */
    @Test fun theEasyRunVerdictDoesNotDependOnTheLapLength() {
        val guess = denis.copy(easyMinutes = 25, vdot = RunPlan.provisionalVdot(goal))
        for (lap in listOf(set, measured)) {
            val w = RunPlan.onTrack(RunPlan.easy(guess), lap)
            val r = RunResult(1.0, 7, 1517, distanceM = 18.0 * lap)
            val after = RunPlan.after(guess.copy(vdot = null), w, r, caps, day = 20748, goal = goal)
            assertEquals(listOf<RunOutcome>(RunOutcome.Shorter(20)), after.outcomes, "lap $lap")
        }
        assertEquals(RunType.EASY, RunPlan.easy(guess).type)
    }
}
