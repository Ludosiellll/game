package forma.core

import forma.core.model.RunGoal
import forma.core.run.Lap
import forma.core.run.ManualLaps
import forma.core.run.ManualRun
import forma.core.run.Press
import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunTracker
import forma.core.run.RunWorkout
import forma.core.run.Vdot
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The stretch held from the start of a trial (`RunPlan.held`) next to the whole time: it may only add to what the whole
 * time says, never throw it away, stand in for it on the road, or call a trial typed in full "not run to the end".
 */
class TrialHeldEdgeTest {
    private val goal = RunGoal(1500, 330)
    private val lap = 205
    private val caps = RunCaps(45, 90)

    private fun run(st: RunState, laps: List<Double>): Pair<RunWorkout, RunResult> {
        val w = RunPlan.goalTest(st, goal, lap)
        val t = RunTracker(w)
        t.start(0)
        val start = 12 * 60 * 1000L
        assertEquals(Press.Start, t.press(start))
        var at = start
        laps.forEach { s ->
            at += (s * 1000).roundToInt()
            t.lap(at)
        }
        t.finish(at + 2_000)
        return w to t.result(at + 2_000, 9)
    }

    /**
     * Fitness 40 known. Away far too fast (923 m at 3:25/km, a fitness of 50 for that stretch — not believable next to
     * 40) and then jogging to the line: the whole time (7:09, a fitness of 37,6) is a trial like any other and counts,
     * and the stretch adds to it what any faded trial's does, 3 at most — capped, not dropped: dropped at the edge of
     * belief, a trial run faster gave a lower fitness.
     */
    @Test fun aStretchTooGoodToBelieveAddsAtMostThreeToTheWholeTime() {
        val known = RunState(walkStage = -1, vdot = 40.0)
        val (w, r) = run(known, listOf(21.0) + List(4) { 42.0 } + List(3) { 80.0 })
        val t = assertNotNull(r.testSeconds)
        val raw = Vdot.fromRace(w.testMeters!!.toDouble(), t.toDouble())
        val part = assertNotNull(RunPlan.held(w, r))
        assertTrue(RunPlan.believable(raw, 40.0) && !RunPlan.believable(part.vdot, 40.0), "raw $raw, part ${part.vdot}")
        val after = RunPlan.after(known, w, r, caps, day = 20, goal = goal)
        assertTrue(after.outcomes.none { it is RunOutcome.TestDoubtful }, "${after.outcomes}")
        assertTrue(after.outcomes.any { it is RunOutcome.TestTime && it.seconds == t }, "${after.outcomes}")
        assertEquals(((raw + RunPlan.PART_OVER_WHOLE) * 2).roundToInt() / 2.0, after.state.vdot)
    }

    /**
     * A 3 km trial on 205 m laps typed as 14:55, a lap walked in the middle (1.72 times the beeps): the laps were read
     * apart from the time typed and the stretch counted as run; 2 % faster the walked lap passed for a lap (1.69 times),
     * the laps were brought to the time typed — the stretch 5 % slower, the fitness 1.5 lower for the faster trial.
     */
    @Test fun aWalkedLapDoesNotSetTheLapsApartFromTheTimeTyped() {
        val known = RunState(walkStage = -1, vdot = 38.0)
        val g = RunGoal(3000, 777)
        val w = RunPlan.goalTest(known, g, lap)
        val lapSec = w.segments.first { it.laps != null }.lapSec!!
        val laps = listOf(51.0, 52.2, 52.7, 53.0, 52.0, 51.7, 51.1, 50.3, 103.7, 52.6, 52.5, 51.5, 52.9, 52.0, 53.0)
        assertTrue(103.7 / lapSec > 1.7 && 103.7 * 0.98 / lapSec < 1.7, "lap $lapSec")
        fun after(scale: Double): Double {
            val ls = laps.mapIndexed { i, s -> Lap(s * scale, number = i + 1, of = laps.size, rep = 1) }
            val r = RunResult(1.0, 9, 2400, laps = ls, testSeconds = (895 * scale).roundToInt())
            return RunPlan.after(known, w, r, caps, day = 20, goal = g).state.vdot!!
        }
        val slower = after(1.0)
        val faster = after(0.98)
        assertTrue(faster >= slower, "slower $slower, faster $faster")
    }

    /**
     * Stopped after a lap slowed to 60.7 s (6.5 of 7.5 laps): no better than running the last lap at 60.7 too — the stop
     * counted the stretch whole (48), the trial run to its end 3 above its time (47.5).
     */
    @Test fun aStopIsNoBetterThanRunningOnAtThePaceComeDownTo() {
        val fresh = RunState(walkStage = -1)
        val g = RunGoal(1500, 337)
        val w = RunPlan.goalTest(fresh, g, 200)
        val laps = listOf(22.2, 43.6, 42.8, 43.0, 43.9, 44.4, 60.7)
        val ls = laps.mapIndexed { i, s -> Lap(s, number = i + 1, of = 8, rep = 1, part = if (i == 0) 0.5 else 1.0) }
        fun after(l: List<Lap>) = RunPlan.after(fresh, w, RunResult(1.0, 9, 1354, laps = l), caps, day = 20, goal = g).state.vdot!!
        val stopped = after(ls)
        val ran = after(ls + Lap(60.7, number = 8, of = 8, rep = 1))
        assertTrue(ran >= stopped, "stopped $stopped, ran on $ran")
    }

    /**
     * A trial 2 % faster never gives a lower fitness: a stretch just past belief next to the fitness known (36: 922 m in
     * 3:34 a 43.8, 2 % faster a 44.8) once dropped and the whole time alone counted — 38.5 for the faster trial, 44 for
     * the slower one (found on random runners: run_trial_faster).
     */
    @Test fun aFasterTrialNeverGivesALowerFitnessAtTheEdgeOfBelief() {
        val known = RunState(walkStage = -1, vdot = 36.0)
        fun after(scale: Double): Double {
            val (w, r) = run(known, (listOf(23.0) + List(4) { 47.75 } + List(3) { 72.0 }).map { it * scale })
            return RunPlan.after(known, w, r, caps, day = 20, goal = goal).state.vdot!!
        }
        val slower = after(1.0)
        val faster = after(0.98)
        assertTrue(faster >= slower, "slower $slower, faster $faster")
    }

    /**
     * On the road the time is typed (1500 m in 5:40) and GPS measured the distance a few per cent long, as it does: the
     * GPS distance in the same time is the same run, not a stretch held and then faded — the typed time is the fitness.
     */
    @Test fun onTheRoadGpsDoesNotOverrideTheTypedTime() {
        val fresh = RunState(walkStage = -1)
        val w = RunPlan.goalTest(fresh, goal, null)
        val r = RunResult(1.0, 9, 1900, workDistanceM = 1545.0, workSec = 340, testSeconds = 340)
        val after = RunPlan.after(fresh, w, r, caps, day = 20, goal = goal)
        assertTrue(after.outcomes.none { it is RunOutcome.TestFromPart }, "${after.outcomes}")
        assertEquals((Vdot.fromRace(1500.0, 340.0) * 2).roundToInt() / 2.0, after.state.vdot)
    }

    /**
     * A trial without the phone, typed in as its laps (all 7,5 of them, each a lap) and no time: it was run to the end,
     * and its time is the laps' — not "not run to the end, a full trial again in two weeks".
     */
    @Test fun aTrialTypedAsAllItsLapsIsRunToTheEnd() {
        val fresh = RunState(walkStage = -1)
        val w = RunPlan.goalTest(fresh, goal, lap)
        val laps = ManualLaps.assign(w, listOf(26.0) + List(7) { 52.0 })
        val r = RunPlan.manual(w, ManualRun(null, null, rpe = 9)).copy(laps = laps)
        val after = RunPlan.after(fresh, w, r, caps, day = 20, goal = goal)
        assertTrue(after.outcomes.any { it is RunOutcome.TestTime && it.seconds == 390 }, "${after.outcomes}")
        assertTrue(after.outcomes.none { it is RunOutcome.TestFromPart && !it.finished }, "${after.outcomes}")
        assertTrue(!after.state.testSoon)
        assertEquals((Vdot.fromRace(1537.5, 390.0) * 2).roundToInt() / 2.0, after.state.vdot)
    }
}
