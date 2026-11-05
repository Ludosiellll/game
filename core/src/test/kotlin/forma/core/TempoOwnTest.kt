package forma.core

import forma.core.model.RunGoal
import forma.core.run.Lap
import forma.core.run.ManualLaps
import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.SegmentKind
import forma.core.run.TempoShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis, 5 November: the tempo run 3 × 5 min was far too hard (he stopped in the second rep, effort 8) — no pace was set
 * (no trial counted yet), so he ran it at about 4:50 a km by feel, and below 3 × 5 the ladder had nothing easier. He asked
 * for 2.5-minute reps with a minute's rest, and for the coach to be able to change the tempo run.
 */
class TempoOwnTest {
    private val caps = RunCaps(45, 90)
    private val goal = RunGoal(1500, 330)
    private val denis = RunState(walkStage = -1, easyMinutes = 25, qualityCount = 1, runs = 20)

    private fun work(w: forma.core.run.RunWorkout) = w.segments.filter { it.kind == SegmentKind.WORK }

    @Test fun beforeTheFirstTrialTheTempoHasAPaceFromTheGoal() {
        val guess = denis.copy(vdot = RunPlan.provisionalVdot(goal))
        val w = RunPlan.quality(denis, goal = null, lapMeters = 205, guess = guess)
        assertEquals(RunType.TEMPO, w.type)
        val pace = assertNotNull(work(w).first().pace, "a tempo run without a trial still has a pace")
        // Threshold at the guessed fitness (41): about 4:59 a km, 61 s a 205 m lap.
        assertTrue((pace.fast + pace.slow) / 2 in 295..303, "threshold ≈ 4:59/km, got ${pace.fast}–${pace.slow}")
        assertTrue(w.note.contains("примерн"), "the pace is said to be approximate: ${w.note}")
        // Without a guess (no goal, no fitness), still none.
        assertNull(work(RunPlan.quality(denis, lapMeters = 205)).first().pace)
    }

    @Test fun aRunFarTooHardSplitsTheRepsInHalfAndTheyGrowBack() {
        val w = RunPlan.tempo(denis)
        assertEquals(3, work(w).size)
        // As on 5 November: 52 % of the work done, effort 8.
        val a = RunPlan.after(denis, w, RunResult(0.52, 8, 1132), caps, day = 20397)
        assertEquals(TempoShape(6, 150, 60), a.state.tempoOwn)
        assertEquals(0, a.state.tempoStage)
        assertTrue(a.outcomes.contains(RunOutcome.TempoNext(6, 150, split = true)), "${a.outcomes}")

        val short = RunPlan.tempo(a.state)
        assertEquals(6, work(short).size)
        assertTrue(work(short).all { it.seconds == 150 })
        assertTrue(short.segments.filter { it.kind == SegmentKind.RECOVERY }.all { it.seconds == 60 })
        assertEquals("Темповый бег · 6 × 2,5 мин", short.title)

        // Done well: a step towards 3 × 5 — 4 × 4 min.
        val b = RunPlan.after(a.state, short, RunResult(1.0, 7, short.totalSeconds), caps, day = 20404)
        assertEquals(TempoShape(4, 240, 60), b.state.tempoOwn)
        assertTrue(b.outcomes.contains(RunOutcome.TempoNext(4, 240)), "${b.outcomes}")
        assertEquals(0, b.state.tempoStage)
        // And again: back on the plan's step, 3 × 5.
        val mid = RunPlan.tempo(b.state)
        val c = RunPlan.after(b.state, mid, RunResult(1.0, 7, mid.totalSeconds), caps, day = 20411)
        assertNull(c.state.tempoOwn)
        assertEquals(0, c.state.tempoStage)
        assertTrue(c.outcomes.contains(RunOutcome.TempoNext(3, 300)), "${c.outcomes}")
        assertEquals("Темповый бег · 3 × 5 мин", RunPlan.tempo(c.state).title)
    }

    @Test fun anOrdinaryMissKeepsTheRunAndOnlyAFarTooHardOneSplitsIt() {
        val w = RunPlan.tempo(denis)
        // Most of it done at an 8: a miss, repeated — as before.
        val a = RunPlan.after(denis, w, RunResult(0.8, 8, 1700), caps, day = 20397)
        assertNull(a.state.tempoOwn)
        assertTrue(a.outcomes.contains(RunOutcome.StageRepeat("tempo")))
        // A lighter week's shorter run is no measure of the step: not split either.
        val light = RunPlan.tempo(denis, cutback = true)
        assertNull(RunPlan.after(denis, light, RunResult(0.4, 9, 900), caps, day = 20397).state.tempoOwn)
    }

    @Test fun ownRepsMissedTwiceLoseARep() {
        val st = denis.copy(tempoOwn = TempoShape(6, 150, 60))
        val w = RunPlan.tempo(st)
        val a = RunPlan.after(st, w, RunResult(0.7, 8, 1300), caps, day = 20397)
        assertEquals(TempoShape(6, 150, 60), a.state.tempoOwn)
        val b = RunPlan.after(a.state, RunPlan.tempo(a.state), RunResult(0.7, 8, 1300), caps, day = 20404)
        assertEquals(TempoShape(5, 150, 60), b.state.tempoOwn)
        assertEquals(0, b.state.tempoStage)
    }

    @Test fun anOwnRunAsHardAsTheStepCountsForTheStep() {
        // The coach's 2 × 8 min at the first step (3 × 5): done well, the ladder steps up and the own run is over.
        val st = denis.copy(tempoOwn = TempoShape(2, 480, 120))
        val w = RunPlan.tempo(st)
        assertEquals(listOf(480, 480), work(w).map { it.seconds })
        val a = RunPlan.after(st, w, RunResult(1.0, 7, w.totalSeconds), caps, day = 20397)
        assertNull(a.state.tempoOwn)
        assertEquals(1, a.state.tempoStage)
    }

    @Test fun theTempoNowIsTheOwnOrTheStep() {
        assertEquals(TempoShape(3, 300, 60), RunPlan.tempoNow(denis))
        assertEquals(TempoShape(6, 150, 60), RunPlan.tempoNow(denis.copy(tempoOwn = TempoShape(6, 150, 60))))
        assertEquals(15 * 60, TempoShape(6, 150, 60).total)
    }

    @Test fun timedRepsGetTheirPaceFromTheLapsPressedInsideThem() {
        // 5 November: laps pressed through both reps (the first lap of each runs from where the rep began, not the line).
        val laps = listOf(80.8, 83.2, 82.5).mapIndexed { i, s -> Lap(s, number = i + 1) } +
            listOf(67.3, 60.4, 59.8, 59.5, 59.4).mapIndexed { i, s -> Lap(s, number = i + 1, rep = 1) } +
            listOf(58.0, 53.3, 60.5).mapIndexed { i, s -> Lap(s, number = i + 1, rep = 2) }
        val pace = assertNotNull(ManualLaps.repPace(laps, 205))
        val whole = listOf(60.4, 59.8, 59.5, 59.4, 53.3, 60.5)
        assertEquals(whole.sum() / (whole.size * 0.205), pace, 1e-6)
        // One lap inside the reps is too little to say.
        assertNull(ManualLaps.repPace(listOf(Lap(70.0, number = 1, rep = 1), Lap(60.0, number = 2, rep = 1)), 205))
    }

    @Test fun onTheTrackATempoRunIsMeasuredByItsLaps() {
        val w = RunPlan.onTrack(RunPlan.tempo(denis), 205)
        val t = RunTracker(w)
        t.start(0)
        // The first rep runs 600–900 s: its first press 40 s in (from wherever it began), then a lap a minute.
        listOf(640_000L, 700_000L, 760_000L, 820_000L, 880_000L).forEach { t.lap(it) }
        t.finish(900_000)
        val r = t.result(900_000, 8)
        assertEquals(60.0 / 0.205, assertNotNull(r.workPace, "measured by the laps pressed"), 0.5)
    }
}
