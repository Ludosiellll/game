package forma.core

import forma.core.model.RunGoal
import forma.core.run.Fix
import forma.core.run.Lap
import forma.core.run.PaceAlarm
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
import forma.core.run.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The run engine's findings of the audit of 24 November (group D), each fixed: a test per finding, named after what must
 * hold. Denis's goal (1500 m in 5:30) on his 205 m loop, unless said otherwise.
 */
class AuditFixRunEngineTest {
    private val goal = RunGoal(1500, 330)
    private val caps = RunCaps(45, 90)
    private val loop = 205

    /** As on 23 November: no trial counted, the last hard run missed (see RepPaceShownTest). */
    private val denis = RunState(walkStage = -1, easyMinutes = 30, qualityCount = 3, qualityFails = 1, runs = 12, lastTrialTryDay = 20755)

    private fun work(w: RunWorkout) = w.segments.filter { it.kind == SegmentKind.WORK }
    private fun lapOf(w: RunWorkout) = assertNotNull(work(w).first().lapSec)
    private fun target(w: RunWorkout) = work(w).first().pace!!.let { (it.fast + it.slow) / 2.0 }

    /** run-lap-verdict-vs-too-fast */
    @Test fun racePaceLapsTheVoiceCallsOnPaceAreNotRepeatedAsTooFast() {
        val st = RunState(walkStage = -1, estVdot = 40.0, goalRepPace = 220.0, goalStage = 1, runs = 12)
        val w = RunPlan.goalPace(st, goal, loop)
        val p = assertNotNull(work(w).first().pace)
        val fast = Track.lapSec(p.fast.toDouble(), loop)
        val slow = Track.lapSec(p.slow.toDouble(), loop)
        fun laps(sec: Double) = List(w.reps) { k -> Lap(sec, fast, slow, 1, 1, k + 1) }
        fun ran(sec: Double) = RunPlan.after(
            st, w, RunResult(1.0, 6, w.totalSeconds, workPace = sec / (loop / 1000.0), reps = w.reps, laps = laps(sec)), caps, day = 20800, goal = goal,
        )
        // 8 × 43,6 s for «Круг 44–46 с»: «44. В темпе.» on every lap, «8 из 8 в темпе» in the debrief — and the next step.
        val said = RunReview.of(RunReview.Run(w, laps(43.6), rpe = 6, workPace = 43.6 / 0.205), goal).first().text
        assertTrue("8 из 8 в темпе" in said, said)
        val a = ran(43.6)
        assertEquals(2, a.state.goalStage, "${a.outcomes}")
        assertTrue(RunOutcome.TooFastRepeat !in a.outcomes && RunOutcome.TooFast !in a.outcomes, "${a.outcomes}")
        // 44,0 s a lap: on pace by every lap, and not «быстрее нужного» by the plan alone.
        assertTrue(RunOutcome.TooFast !in ran(44.0).outcomes)
        // Laps the voice calls fast («43. Быстро»): the step again, as before.
        val b = ran(42.6)
        assertTrue(RunOutcome.TooFastRepeat in b.outcomes, "${b.outcomes}")
        assertEquals(1, b.state.goalStage)
    }

    /** run-trial-typo-with-estimate */
    @Test fun aTypedTrialTimeFarAboveTheEstimateAndTheGoalIsATypo() {
        val st = RunPlan.estimated(RunState(walkStage = -1, runs = 12, testSoon = true), goal)
        val w = RunPlan.goalTest(st, goal, loop)
        assertEquals(1435, w.testMeters)
        // «4:52» typed for «5:52»: a fitness of 53 from an estimate of 41 — not counted, and no «цель достигнута».
        val typo = RunPlan.after(st, w, RunResult(1.0, 9, 1700, testSeconds = 292), caps, day = 100, goal = goal)
        assertTrue(typo.outcomes.any { it is RunOutcome.TestDoubtful }, "${typo.outcomes}")
        assertNull(typo.state.vdot)
        assertEquals(st.estVdot, typo.state.estVdot)
        assertTrue(RunOutcome.GoalReached !in typo.outcomes)
        // The time he ran counts, and so does one at the goal.
        assertEquals(43.0, RunPlan.after(st, w, RunResult(1.0, 9, 1700, testSeconds = 352), caps, day = 100, goal = goal).state.vdot)
        assertNotNull(RunPlan.after(st, w, RunResult(1.0, 9, 1700, testSeconds = 316), caps, day = 100, goal = goal).state.vdot)
        // A time the presses bear out is no typo, however far from the estimate.
        val pressed = List(7) { k -> Lap(292 / 7.0, number = k + 1, of = 7, rep = 1) }
        val laps = RunPlan.after(st, w, RunResult(1.0, 9, 1700, laps = pressed, testSeconds = 292), caps, day = 100, goal = goal)
        assertEquals(53.0, laps.state.vdot, "${laps.outcomes}")
    }

    /** run-205-duplicate-rung */
    @Test fun onA205MetreLoopEveryRacePaceStepIsAStepUpAndALighterWeekIsLighter() {
        val st = RunState(walkStage = -1, estVdot = 40.0, goalStage = 3)
        fun reps(stage: Int, cutback: Boolean = false) = RunPlan.goalPace(st.copy(goalStage = stage), goal, loop, cutback)
        // 5 × 400 and 6 × 400 were both 5 × 2 laps (410 m reps under the cap of 1.6 races): «шаг 5 из 9» the step just passed.
        assertEquals("Темп цели · 5 × 2 круга", reps(3).title)
        assertEquals("Темп цели · 6 × 2 круга", reps(4).title)
        assertNotEquals(work(reps(3)).map { it.laps to it.seconds }, work(reps(4)).map { it.laps to it.seconds })
        // A lighter week is lighter on every step: fewer reps than the full one, and counted so.
        for (stage in 0 until RunPlan.goalLadderSize - 1) {
            val full = reps(stage)
            val light = reps(stage, cutback = true)
            assertTrue(light.cutback && light.reps < full.reps, "step $stage: ${full.title} / ${light.title}")
        }
    }

    /** run-goal-lighter-week-wipes-miss */
    @Test fun aLighterWeekOfRacePaceDoesNotWipeAMissOfTheFullStep() {
        val st = RunState(walkStage = -1, vdot = 46.0, goalStage = 4, runs = 20)
        val miss = RunResult(0.5, 9, 1500)
        val first = RunPlan.after(st, RunPlan.goalPace(st, goal, null), miss, caps, day = 100, goal = goal).state
        assertEquals(1, first.goalFails)
        val light = RunPlan.goalPace(first, goal, null, cutback = true)
        assertTrue(light.cutback)
        val lighter = RunPlan.after(first, light, RunResult(1.0, 7, light.totalSeconds), caps, day = 104, goal = goal)
        assertTrue(RunOutcome.LighterWeek("goal") in lighter.outcomes, "${lighter.outcomes}")
        assertEquals(1, lighter.state.goalFails)
        // The full step missed again: a step back.
        val again = RunPlan.after(lighter.state, RunPlan.goalPace(lighter.state, goal, null), miss, caps, day = 108, goal = goal)
        assertTrue(RunOutcome.StageDown("goal", 3) in again.outcomes, "${again.outcomes}")
        assertEquals(3, again.state.goalStage)
    }

    /** run-road-trial-race-from-estimate */
    @Test fun theRaceIsPacedAtTheGoalAndARoadTrialRunToItsDistanceIsWhole() {
        val st = RunPlan.estimated(RunState(walkStage = -1, runs = 12), goal)
        val race = RunPlan.race(st, goal)
        val rep = work(race).single()
        val range = assertNotNull(rep.pace)
        assertTrue(goal.pace.toInt() in range.fast..range.slow, "$range")
        assertEquals(goal.seconds, rep.seconds)
        // Run at the goal's pace from start to finish: no «Быстрее цели, чуть сбавь».
        val alarm = PaceAlarm()
        val said = (0..rep.seconds).mapNotNull { sec ->
            alarm.update(sec * 1000L, goal.pace, PaceAlarm.fastLimit(race, rep, sec), range.slow.toDouble(), sec * 1000L)
        }
        assertEquals(emptyList(), said)
        // A trial's second half is run as fast as is left: no fast limit there.
        val trial = RunPlan.goalTest(st, goal, null)
        val t = work(trial).single()
        assertNotNull(PaceAlarm.fastLimit(trial, t, 60))
        assertNull(PaceAlarm.fastLimit(trial, t, t.seconds / 2 + 1))
        // A road trial run to its distance before the estimate's time, stopped at the finish: the whole trial, not a stretch.
        val a = RunPlan.after(st, trial, RunResult(340 / 395.0, 9, 1700, workDistanceM = 1500.0, workSec = 340), caps, day = 100, goal = goal)
        assertTrue(a.outcomes.any { it is RunOutcome.VdotChanged }, "${a.outcomes}")
        assertTrue(a.outcomes.none { it is RunOutcome.TestFromPart }, "${a.outcomes}")
        assertFalse(a.state.testSoon)
        assertEquals(47.5, a.state.vdot)
    }

    /** run-shorter-makes-longer */
    @Test fun aHardRunNeverMakesTheNextOneLonger() {
        val st = RunState(walkStage = -1, easyMinutes = 15, longMinutes = 25, runs = 10)
        val easy = RunPlan.after(st, RunPlan.easy(st), RunResult(1.0, 7, 900), caps, day = 100)
        assertEquals(15, easy.state.easyMinutes)
        assertEquals(listOf<RunOutcome>(RunOutcome.Same(15)), easy.outcomes)
        // At the floor «короче» was said and nothing changed.
        val twenty = st.copy(easyMinutes = 20)
        assertEquals(listOf<RunOutcome>(RunOutcome.Same(20)), RunPlan.after(twenty, RunPlan.easy(twenty), RunResult(1.0, 7, 1200), caps, day = 100).outcomes)
        // A long run of 25 minutes stopped early: not 30 the next time.
        val long = RunPlan.after(st, RunPlan.long(st), RunResult(0.6, 6, 900), caps, day = 100)
        assertEquals(25, long.state.longMinutes)
        assertEquals(listOf<RunOutcome>(RunOutcome.Same(25)), long.outcomes)
        // Above the floor it shortens as before.
        val more = st.copy(easyMinutes = 25)
        assertEquals(listOf<RunOutcome>(RunOutcome.Shorter(20)), RunPlan.after(more, RunPlan.easy(more), RunResult(1.0, 7, 1500), caps, day = 100).outcomes)
    }

    /** run-ahead-streak-survives-break */
    @Test fun hardRunsAheadBeforeABreakDoNotRaiseTheEstimateAfterIt() {
        val st = RunState(walkStage = -1, estVdot = 40.0, qualityAhead = 2, qualityCount = 1, lastRunDay = 80, runs = 20)
        // Back after 20 days: an easy run first, and the break cuts the estimate to 38,5.
        val easy = RunPlan.after(st, RunPlan.easy(st), RunResult(1.0, 4, 1800), caps, day = 100)
        assertEquals(38.5, easy.state.estVdot)
        assertEquals(0, easy.state.qualityAhead)
        // A tempo run on its pace with effort to spare: one ahead, not the third of a streak from before the break.
        val tempo = RunPlan.tempo(easy.state)
        val a = RunPlan.after(easy.state, tempo, RunResult(1.0, 5, tempo.totalSeconds, workPace = target(tempo)), caps, day = 103)
        assertEquals(1, a.state.qualityAhead)
        assertTrue(a.outcomes.none { it is RunOutcome.EstChanged }, "${a.outcomes}")
        assertEquals(38.5, a.state.estVdot)
    }

    /** run-01 */
    @Test fun gpsOnTheLineAtTheStartLeavesTheStartPressAStart() {
        val easy = RunPlan.onTrack(RunPlan.easy(RunState(walkStage = -1, vdot = 40.0)), loop)
        val lat = 53.9
        val lon = 27.56
        fun tracker(gps: Boolean) = RunTracker(easy).apply {
            setLine(lat, lon)
            start(0)
            // «Старт» on the line; GPS finds it 10 s later, still standing there.
            if (gps) onFix(Fix(10_000, lat, lon, 8f, 0f), 10_000)
        }
        val t = tracker(gps = true)
        // The press as he sets off is his start, not a lap of 15 s dropped without a word.
        assertEquals(Press.Start, t.press(25_000))
        // «Этот круг» counts from it.
        assertEquals(0, t.lapClockSec(25_000))
        assertEquals(10, t.lapClockSec(35_000))
        assertEquals(76.0, assertIs<Press.Done>(t.press(101_000)).lap.seconds, 1e-9)
        // A first press later than a start: the same lap as without GPS — where GPS had him decides nothing.
        val late = assertIs<Press.Done>(tracker(gps = true).press(50_000)).lap
        val blind = assertIs<Press.Done>(tracker(gps = false).press(50_000)).lap
        assertEquals(blind.seconds, late.seconds, 1e-9)
    }

    /** run-07 */
    @Test fun theGpsAccuracyIsNotShownOnceTheFixesStop() {
        val t = RunTracker(RunPlan.easy(RunState(walkStage = -1, vdot = 40.0)))
        t.start(0)
        assertNull(t.accuracyNow(1_000))
        assertFalse(t.gpsLost(1_000))
        t.onFix(Fix(5_000, 53.9, 27.56, 5f, 3f), 5_000)
        assertEquals(5f, t.accuracyNow(10_000))
        assertFalse(t.gpsLost(10_000))
        // Under a bridge: no fix for 20 s.
        assertNull(t.accuracyNow(25_000))
        assertTrue(t.gpsLost(25_000))
    }

    /** gaps:rep-pace-loop — reps of a new length start at the pace held in shorter ones (Denis, 23.11), not slower. */
    @Test fun longerRepsRunTheFirstTimeTakeThePaceHeldInShorterOnes() {
        // His phone: 45,1 s a lap held in one-lap reps (16.11), the estimate 40.
        val st = RunPlan.estimated(denis, goal).copy(goalRepPace = goal.pace)
        assertEquals(Track.lapSec(goal.pace, loop), lapOf(RunPlan.goalPace(st, goal, loop)), 0.1)
        // Two-lap reps, nothing held in them yet: still his 45,1 s, not the fitness's step (about 52,7 s a lap).
        val two = RunPlan.goalPace(st.copy(goalStage = 3), goal, loop)
        assertEquals(Track.lapSec(goal.pace, loop), lapOf(two), 0.1)
    }

    /** gaps:rep-pace-loop — the loop with a pace carried over from shorter reps. */
    @Test fun aPaceCarriedToLongerRepsAndMissedThereIsEasedForThemAlone() {
        // Two-lap reps at the one-lap reps' 45,1 s, missed twice: a step back, and the two-lap reps get their own,
        // slower pace; the one-lap pace stays.
        var st = RunPlan.estimated(denis, goal).copy(goalRepPace = goal.pace, goalStage = 3, qualityFails = 0)
        for (day in listOf(20780L, 20787L)) {
            val w = RunPlan.goalPace(st, goal, loop)
            st = RunPlan.after(st, w, RunResult(0.6, 9, 1200, workPace = target(w) * 1.06), caps, day = day, goal = goal).state
        }
        assertEquals(2, st.goalStage)
        assertEquals(goal.pace, st.goalRepPace)
        val eased = assertNotNull(st.goalRepPaces[RunPlan.repLength(3)])
        assertEquals(goal.pace * (1 + RunPlan.STEP_AHEAD), eased, 1e-9)
        // One-lap reps run quicker than asked, with ease: the step up again, and the two-lap reps at their eased pace.
        val short = RunPlan.goalPace(st, goal, loop)
        val a = RunPlan.after(st, short, RunResult(1.0, 6, short.totalSeconds, workPace = 44.0 / 0.205, reps = short.reps), caps, day = 20794, goal = goal)
        assertEquals(3, a.state.goalStage)
        assertEquals(Track.lapSec(eased, loop), lapOf(RunPlan.goalPace(a.state, goal, loop)), 0.1)
    }

    /** gaps:rep-pace-loop */
    @Test fun quickShortRepsDoNotTakeBackTheEasedPaceOfLongerOnes() {
        var st = RunPlan.estimated(denis, goal).copy(goalStage = 5, goalRepPaces = mapOf(RunPlan.repLength(5) to goal.pace), qualityFails = 0)
        assertEquals("Темп цели · 3 × 3 круга", RunPlan.goalPace(st, goal, loop).title)
        // 3 × 3 laps at the goal's 45,1 s, missed twice: a step back, and their pace a step slower.
        for (day in listOf(20780L, 20787L)) {
            val w = RunPlan.goalPace(st, goal, loop)
            st = RunPlan.after(st, w, RunResult(0.6, 9, 1500, workPace = target(w) * 1.06), caps, day = day, goal = goal).state
        }
        assertEquals(4, st.goalStage)
        val eased = assertNotNull(st.goalRepPaces[RunPlan.repLength(5)])
        assertEquals(goal.pace * (1 + RunPlan.STEP_AHEAD), eased, 1e-9)
        // 6 × 2 laps run well quicker than asked, with ease: the next step.
        val short = RunPlan.goalPace(st, goal, loop)
        val a = RunPlan.after(st, short, RunResult(1.0, 6, short.totalSeconds, workPace = 44.0 / 0.205, reps = short.reps), caps, day = 20794, goal = goal)
        assertEquals(5, a.state.goalStage)
        // 3 × 3 laps again: at their eased pace, not at the 2-lap reps' quicker one.
        assertEquals(Track.lapSec(eased, loop), lapOf(RunPlan.goalPace(a.state, goal, loop)), 0.1)
    }

    /** gaps:tight-loop-one-way */
    @Test fun stridesAfterAnEasyRunFitTheStraightOfASmallLoop() {
        val w = RunPlan.easy(RunState(walkStage = -1, vdot = 40.0, runs = 12), strides = 6)
        assertTrue("секунд по 10, по прямой" in w.note, w.note)
        assertTrue("80 м" !in w.note, w.note)
        // The warm-up's strides stay by the clock.
        assertTrue("4 ускорения по 15 с" in RunPlan.goalPace(denis, goal, loop).note)
    }
}
