package forma.core

import forma.core.model.RunGoal
import forma.core.run.Lap
import forma.core.run.Press
import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunReview
import forma.core.run.RunState
import forma.core.run.RunTracker
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import forma.core.run.Vdot
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis's first trial on 29.10 as it may go, pressed lap by lap on the real tracker (no GPS): 1500 m in 5:30 as the goal,
 * a 205 m loop (7,5 laps, the beeps at 48,7 s), no fitness known. Even to the end; away at the beeps and faded after
 * 900 m to a jog; a lap walked; stopped; a press missed; a press doubled mid-lap; the time typed by hand after; the laps
 * counted again on the loop GPS measured. Each gives a fitness that is his, words that are true, and paces for the week
 * after that follow from them.
 */
class TrialTomorrowTest {
    private val goal = RunGoal(1500, 330)
    private val lap = 205
    private val caps = RunCaps(60, 90)
    private val day = 20755L

    /** His state the evening before: no trial yet, a few runs behind him. */
    private val denis = RunState(walkStage = -1, easyMinutes = 20, longMinutes = 35, runs = 5, qualityCount = 1, lastRunDay = day - 3, goalFails = 1)

    private val w: RunWorkout = RunPlan.goalTest(denis, goal, lap)
    private val lapSec = w.segments.first { it.laps != null }.lapSec!!

    /** The beeps' laps: the half first, then whole ones. */
    private val beeps = listOf(lapSec / 2) + List(7) { lapSec }

    /**
     * The trial pressed on the tracker: the start on the line after the warm-up, then a press at every line crossing
     * ([laps], seconds each, the first the half lap) — the laps in [missed] not pressed, and a press [strays] of the way
     * into a lap (a thumb on the screen) besides. The result as the finish screen would save it: the lap button's time
     * in the field, unless [typed] is given instead.
     */
    private fun run(laps: List<Double>, missed: Set<Int> = emptySet(), strays: Map<Int, Double> = emptyMap(), typed: Int? = null): RunResult {
        val t = RunTracker(w)
        t.start(0)
        val start = 12 * 60 * 1000L
        assertEquals(Press.Start, t.press(start))
        var at = start
        laps.forEachIndexed { i, s ->
            strays[i + 1]?.let { f -> t.press(at + (s * f * 1000).roundToInt()) }
            at += (s * 1000).roundToInt()
            if (i + 1 !in missed) t.press(at)
        }
        t.finish(at + 2_000)
        val r = t.result(at + 2_000, 9)
        return if (typed != null) r.copy(testSeconds = typed) else r
    }

    private fun after(r: RunResult, st: RunState = denis, plan: RunWorkout = w) = RunPlan.after(st, plan, r, caps, day, goal)

    private fun review(r: RunResult, a: forma.core.run.RunAfter, plan: RunWorkout = w): String {
        val held = a.outcomes.filterIsInstance<RunOutcome.TestFromPart>().firstOrNull()?.let { it.meters to it.seconds }
        val trial = a.outcomes.filterIsInstance<RunOutcome.TestTime>().firstOrNull()?.let { it.meters to it.seconds }
        return RunReview.of(RunReview.Run(plan, r.laps.orEmpty(), r.rpe, r.durationSec, null, r.workPace, trial, r.completed, RunOutcome.GoalReached in a.outcomes, held), goal)
            .joinToString(" ") { "${it.title}: ${it.text}" }
    }

    private fun vdot(meters: Double, sec: Double) = (Vdot.fromRace(meters, sec) * 2).roundToInt() / 2.0

    @Test fun thePlanIsSevenAndAHalfLapsAtTheGoalPlusEightPercent() {
        assertEquals(1538, w.testMeters)
        assertEquals(7.5, w.segments.first { it.laps != null }.laps)
        assertEquals(48.7, lapSec, 0.05)
        assertTrue("с середины круга" in w.note, w.note)
        assertTrue("по 49 с" in w.note, w.note)
    }

    @Test fun evenAtTheBeepsToTheEndIsItsTime() {
        val r = run(beeps)
        assertEquals(365, r.testSeconds)
        val a = after(r)
        assertEquals(vdot(1538.0, 365.0), a.state.vdot)
        assertEquals(45.0, a.state.vdot)
        assertTrue(a.outcomes.any { it is RunOutcome.TestTime && it.seconds == 365 }, "${a.outcomes}")
        assertTrue(a.outcomes.none { it is RunOutcome.TestFromPart }, "${a.outcomes}")
        assertTrue(RunOutcome.GoalReached !in a.outcomes)
        assertTrue(!a.state.testSoon)
        val text = review(r, a)
        assertTrue("1538 м за 6:05" in text && "До цели 5:30" in text && "Круги ровные" in text, text)
        // The week after: easy runs with a limit, race-pace reps a step toward the goal, at 47–48 s a lap.
        val easy = RunPlan.easy(a.state, day = day + 2)
        assertNotNull(easy.segments.first().pace)
        val step = RunPlan.goalPace(a.state, goal, lap)
        val rep = step.segments.first { it.kind == SegmentKind.WORK }
        assertTrue(rep.lapSec!! in 44.0..49.0, "lap ${rep.lapSec}")
        assertTrue(RunPlan.stepPace(a.state, goal) >= goal.pace)
    }

    @Test fun awayAtTheBeepsAndJoggedAfterNineHundredMetres() {
        val r = run(beeps.take(5) + List(3) { 75.0 })
        assertEquals(444, r.testSeconds)
        val a = after(r)
        val raw = Vdot.fromRace(1538.0, 444.0)
        // The stretch (923 m in 3:39, 42,6) is far above the whole time (36,2): 3 above it, said so.
        assertEquals(((raw + RunPlan.PART_OVER_WHOLE) * 2).roundToInt() / 2.0, a.state.vdot)
        assertEquals(39.0, a.state.vdot)
        val part = a.outcomes.filterIsInstance<RunOutcome.TestFromPart>().single()
        assertTrue(part.finished && part.capped && part.meters == 923 && part.seconds == 219, "$part")
        assertTrue(!a.state.testSoon)
        val text = review(r, a)
        assertTrue("Первые 923 м" in text && "Вторая половина медленнее" in text, text)
    }

    @Test fun aLapWalkedIsNoTimeAndTheStretchCountsHeldBackByTheTimeToTheEnd() {
        val r = run(beeps.take(5) + listOf(65.0, 90.0, 80.0))
        assertNull(r.testSeconds, "90 s for a lap of 48,7 is no lap: the time is not trusted")
        val a = after(r)
        assertEquals(38.5, a.state.vdot)
        val part = a.outcomes.filterIsInstance<RunOutcome.TestFromPart>().single()
        assertTrue(!part.finished && part.capped, "$part")
        assertTrue(a.state.testSoon, "a full trial again")
        assertTrue("не добежан в темпе" in review(r, a))
    }

    @Test fun stoppedAfterFiveLapsTheStretchIsTheFitnessAndATrialComesAgain() {
        val r = run(beeps.take(5))
        assertNull(r.testSeconds)
        val a = after(r)
        assertEquals(42.5, a.state.vdot)
        assertTrue(a.outcomes.filterIsInstance<RunOutcome.TestFromPart>().single().let { !it.finished && !it.capped })
        assertTrue(a.state.testSoon)
    }

    @Test fun stoppedAfterThreeLapsNothingIsCountedAndItSaysHowFar() {
        val a = after(run(beeps.take(4)))
        assertNull(a.state.vdot)
        assertEquals(718, a.outcomes.filterIsInstance<RunOutcome.TestStopped>().single().meters)
    }

    /**
     * A press missed in the second half (the sixth lap doubled, 97 s): without GPS the laps give no time, and the stretch
     * held counts, held back by the time to the end — a lower fitness than run, and a trial again in two weeks. The time
     * typed in the field puts it right: it is his time.
     */
    @Test fun aPressMissedGivesNoTimeUntilItIsTyped() {
        val r = run(beeps, missed = setOf(6))
        assertNull(r.testSeconds)
        assertEquals(7, r.laps!!.count { it.rep == 1 })
        val a = after(r)
        assertTrue(a.outcomes.filterIsInstance<RunOutcome.TestFromPart>().single().let { !it.finished }, "${a.outcomes}")
        assertTrue(a.state.vdot!! < 45.0, "${a.state.vdot}")
        assertTrue(a.state.testSoon)
        // Typed: 6:05 — the whole time, the laps brought to it.
        val typed = after(run(beeps, missed = setOf(6), typed = 365))
        assertEquals(45.0, typed.state.vdot)
        assertTrue(typed.outcomes.any { it is RunOutcome.TestTime && it.seconds == 365 }, "${typed.outcomes}")
        assertTrue(typed.outcomes.none { it is RunOutcome.TestFromPart }, "${typed.outcomes}")
        assertTrue(!typed.state.testSoon)
    }

    /**
     * A press doubled mid-lap while fading (60 s laps, a thumb on the screen half way round the fifth): both halves pass
     * for laps (30 s is over half the beeps' 48,7), the eighth press closes the trial a lap early, and its time is a lap
     * short — 6:24 for 7:24, a fitness seven points too high. A lap far quicker than the laps before it in the same rep
     * is no lap.
     */
    @Test fun aPressDoubledMidLapNeverClosesTheTrialALapEarly() {
        val laps = listOf(30.0) + List(7) { 60.0 }
        val real = laps.sum().roundToInt()
        val r = run(laps, strays = mapOf(5 to 0.5))
        val t = assertNotNull(r.testSeconds)
        assertTrue(abs(t - real) <= 1, "the trial's time $t, run in $real: ${r.laps}")
        assertEquals(8, r.laps!!.count { it.rep == 1 }, "${r.laps}")
        val a = after(r)
        assertEquals(vdot(1538.0, real.toDouble()), a.state.vdot)
    }

    /** A thumb on the screen late in a lap (two thirds round): the lap it makes is no lap either, and the time is right. */
    @Test fun aStrayPressLateInALapNeitherClosesTheTrialEarlyNorMovesItsTime() {
        for (f in listOf(0.3, 0.5, 0.65, 0.8)) {
            val laps = listOf(26.0) + List(7) { 52.0 }
            val r = run(laps, strays = mapOf(4 to f))
            val t = assertNotNull(r.testSeconds, "stray at $f")
            assertTrue(abs(t - 390) <= 1, "stray at $f: time $t, laps ${r.laps}")
        }
    }

    /**
     * A thumb on the screen in the last lap (found on random trials: 0,68 of the way round): no lap after it to tell it
     * by, it closed the trial 17 s early. The press on the line within half a lap is the line: the lap takes its time.
     * The same when the last laps are slower (a fade), and a press after the line (a touch once over) adds nothing.
     */
    @Test fun aStrayPressInTheLastLapIsPutRightByTheLine() {
        for (f in listOf(0.62, 0.68, 0.8)) {
            val even = run(beeps, strays = mapOf(8 to f))
            assertTrue(abs(assertNotNull(even.testSeconds, "stray at $f") - 365) <= 1, "stray at $f: ${even.testSeconds}, laps ${even.laps}")
            val faded = listOf(24.35) + List(4) { 48.7 } + listOf(54.0, 58.0, 62.0)
            val slow = run(faded, strays = mapOf(8 to f))
            assertTrue(abs(assertNotNull(slow.testSeconds, "faded, stray at $f") - faded.sum().roundToInt()) <= 1, "faded, stray at $f: ${slow.testSeconds}, laps ${slow.laps}")
        }
        val t = RunTracker(w)
        t.start(0)
        val start = 12 * 60 * 1000L
        t.press(start)
        var at = start
        beeps.forEach { s -> at += (s * 1000).roundToInt(); t.press(at) }
        // A touch 12 s after the finish: a lap of the cool-down at most, the trial's time as it was.
        t.press(at + 12_000)
        t.finish(at + 20_000)
        assertEquals(365, t.result(at + 20_000, 9).testSeconds)
    }

    /** The time typed differs from the laps' by a few seconds (his watch): the typed one is the trial, the laps follow it. */
    @Test fun aTimeTypedOverTheLapsIsTheTrial() {
        val a = after(run(beeps, typed = 360))
        assertEquals(vdot(1538.0, 360.0), a.state.vdot)
        assertTrue(a.outcomes.any { it is RunOutcome.TestTime && it.seconds == 360 }, "${a.outcomes}")
        assertTrue(a.outcomes.none { it is RunOutcome.TestFromPart }, "${a.outcomes}")
    }

    /** GPS measured his loop at 195 m, not 205: the laps are read on it — fewer metres, a lower fitness, the goal no nearer. */
    @Test fun theLapsCountedAgainOnTheLoopGpsMeasured() {
        val r = run(beeps).copy(loopM = 195.0)
        val a = after(r)
        val loop = a.outcomes.filterIsInstance<RunOutcome.LoopShorter>().single()
        assertEquals(195, loop.loop)
        assertEquals(1463, a.outcomes.filterIsInstance<RunOutcome.TestTime>().single().meters)
        assertEquals(vdot(1463.0, 365.0), a.state.vdot)
        assertTrue(a.state.vdot!! < 45.0)
        assertTrue(RunOutcome.GoalReached !in a.outcomes)
        val (plan, read) = RunPlan.onLoop(w, r, 195)
        assertTrue("1463 м за 6:05" in review(read, a, plan), review(read, a, plan))
        // 5:35 for the 7,5 laps reaches the goal on 205 m (1500 m in 5:27) and not on 195 (5:44): read on the loop as run.
        val near = listOf(22.3) + List(7) { 44.67 }
        assertTrue(RunOutcome.GoalReached in after(run(near)).outcomes)
        val short = after(run(near).copy(loopM = 195.0))
        assertTrue(RunOutcome.GoalReached !in short.outcomes, "${short.outcomes}")
    }

    /** At the goal to the end: the goal reached, said so, and no stretch talked about. */
    @Test fun theGoalRunToTheEndIsReached() {
        val r = run(listOf(22.0) + List(7) { 44.0 })
        assertEquals(330, r.testSeconds)
        val a = after(r)
        assertTrue(RunOutcome.GoalReached in a.outcomes, "${a.outcomes}")
        assertTrue("Это цель" in review(r, a))
    }

    /** Every word of every outcome of every scenario above has a number in it, never «null» or «NaN». */
    @Test fun noScenarioSaysNothingBroken() {
        val runs = listOf(
            run(beeps), run(beeps.take(5) + List(3) { 75.0 }), run(beeps.take(5) + listOf(65.0, 90.0, 80.0)), run(beeps.take(5)),
            run(beeps.take(4)), run(beeps, missed = setOf(6)), run(beeps, missed = setOf(3)), run(beeps, strays = mapOf(5 to 0.5)),
            run(beeps, typed = 360), run(beeps).copy(loopM = 195.0), run(listOf(22.0) + List(7) { 44.0 }),
        )
        for (r in runs) {
            val a = after(r)
            val text = review(r, a)
            assertTrue(listOf("NaN", "null", "Infinity", "–:––").none { it in text }, text)
        }
    }

    /** The laps as the plan reads them never lose a lap pressed on the line: eight presses are eight laps. */
    @Test fun eightPressesOnTheLineAreEightLaps() {
        val r = run(beeps)
        val laps = r.laps!!.filter { it.rep == 1 }
        assertEquals((1..8).toList(), laps.map { it.number })
        assertEquals(0.5, laps.first().part)
        assertTrue(laps.all { l -> l.of == 8 })
        assertEquals(Lap::class, laps.first()::class)
    }
}
