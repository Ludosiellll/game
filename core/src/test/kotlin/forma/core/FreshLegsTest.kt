package forma.core

import forma.core.model.Catalog
import forma.core.model.RunGoal
import forma.core.plan.CatchUp
import forma.core.plan.PlannedSession
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.run.RunCaps
import forma.core.run.RunLog
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunType
import forma.core.run.SegmentKind
import forma.core.strength.DoneSet
import forma.core.strength.HoldReason
import forma.core.strength.Outcome
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The third check of the review: race pace on the fresher hard day, tempo that never shrinks a step up, "too fast"
 * against the goal itself, the app's own lighter week as the block's last one, sessions done ahead of their day.
 */
class FreshLegsTest {
    private val goal = RunGoal(1500, 330)
    private val caps = RunCaps(45, 90)

    @Test fun racePaceDueOnTiredLegsGoesToTheFresherDayAndTheCycleStaysTheSame() {
        var st = RunState(walkStage = -1, vdot = 49.0, runs = 10, qualityCount = 0)
        val types = mutableListOf<RunType>()
        // Six weeks of Monday (legs two days before) and Thursday (fresh): race pace moves to Thursday.
        repeat(12) { i ->
            val monday = i % 2 == 0
            val w = RunPlan.quality(st, goal = goal, lapMeters = 230, goalLater = monday)
            types += w.type
            st = RunPlan.after(st, w, RunResult(1.0, 6, w.totalSeconds), caps, day = 100L + i * 3, goal = goal).state
        }
        assertTrue(types.filterIndexed { i, _ -> i % 2 == 1 }.all { it == RunType.GOAL_PACE }, types.toString())
        assertTrue(types.filterIndexed { i, _ -> i % 2 == 0 }.none { it == RunType.GOAL_PACE }, types.toString())
        // The same mix as before: three race pace, two tempo, one intervals in six.
        assertEquals(6, types.count { it == RunType.GOAL_PACE })
        assertEquals(4, types.count { it == RunType.TEMPO })
        assertEquals(2, types.count { it == RunType.INTERVALS })
        assertTrue(!st.goalDeferred)
    }

    @Test fun aTempoStepOverTheWeeksShareIsCutToItNotFarBelow() {
        val recent = (0 until 6).map { RunLog(100L + it * 2, 40) }
        val minutes = (0 until 8).map { stage ->
            RunPlan.tempo(RunState(walkStage = -1, vdot = 49.0, tempoStage = stage, recent = recent, runs = 20))
                .segments.filter { it.kind == SegmentKind.WORK }.sumOf { it.seconds } / 60
        }
        // The 20-minute share of a 120-minute week: 2 × 12 is 2 × 10, not one rep of 12; no step falls far below the share.
        assertTrue(minutes.all { it in 15..20 }, minutes.toString())
        assertEquals(20, minutes[3])
    }

    @Test fun repsAtTheGoalAreNotTooFastWhenTheStepIsSlowerThanTheGoal() {
        // Form for about 5:50: the step is slower than the goal's 220 s/km.
        val st = RunState(walkStage = -1, vdot = 45.0, goalStage = 1, runs = 6)
        val w = RunPlan.goalPace(st, goal, 230)
        val atGoal = RunPlan.after(st, w, RunResult(1.0, 7, w.totalSeconds, workPace = goal.pace), caps, day = 20, goal = goal)
        assertTrue(RunOutcome.TooFastRepeat !in atGoal.outcomes, atGoal.outcomes.toString())
        assertEquals(2, atGoal.state.goalStage)
        // Well under the goal: the step again.
        val fast = RunPlan.after(st, w, RunResult(1.0, 7, w.totalSeconds, workPace = goal.pace * 0.93), caps, day = 20, goal = goal)
        assertTrue(RunOutcome.TooFastRepeat in fast.outcomes)
    }

    @Test fun aRepShortOfTheTargetInTheFirstWeekIsHeldMoreIsAMiss() {
        val squat = Catalog["squat"]
        val spec = SlotSpec("squat", 3, 5, 8, 2, 150, SlotRole.MAIN)
        val st = SlotState(100.0, 6, 0, 0, 5, linear = false)
        val p = Progression.prescribe(squat, spec, st, rirOffset = 1)
        assertEquals(HoldReason.INTRO, (Progression.evaluate(squat, spec, st, p, List(3) { DoneSet(100.0, 5, 3) }).outcome as Outcome.Hold).reason)
        // Half the reps is not "a rep more in reserve".
        val far = Progression.evaluate(squat, spec, st, p, List(3) { DoneSet(100.0, 3, 0) }).outcome
        assertTrue(far !is Outcome.Hold || far.reason != HoldReason.INTRO, far.toString())
    }

    @Test fun theAppsOwnLighterWeekIsTheBlocksLast() {
        val p = ProgramGenerator.generate(
            forma.core.model.Profile(experience = forma.core.model.Experience.ADVANCED, trainingDays = setOf(2, 4, 6)), LocalDate.of(2026, 10, 12),
        )
        assertEquals(4, p.blockWeeks)
        val next = LocalDate.of(2026, 10, 26)
        val moved = ProgramGenerator.lighterWeekAt(p, next)
        assertTrue(ProgramGenerator.isDeloadWeek(moved, next))
        assertTrue(ProgramGenerator.isRunCutbackWeek(moved, next))
        assertTrue(!ProgramGenerator.isDeloadWeek(moved, next.plusWeeks(1)))
        assertEquals(1, ProgramGenerator.rirOffset(moved, next.plusWeeks(1)), "a new block after it")
        // "Неделя N" counts from when the program was made, not from the block.
        assertEquals(3, ProgramGenerator.programWeek(moved, next))
    }

    @Test fun aSessionDoneAheadOfItsDayClosesItOnceTheDayIsOver() {
        val lift = PlannedSession(SessionKind.LIFT, "upper_a")
        val week = listOf(
            CatchUp.Day(3, emptyList(), listOf(lift)),
            CatchUp.Day(4, listOf(lift), emptyList()),
        )
        assertEquals(listOf(CatchUp.Close(4, 0, 3, 0)), CatchUp.closes(week, today = 5))
        // Thursday itself: still to do, nothing closed yet.
        assertEquals(emptyList(), CatchUp.closes(week, today = 4))
        // Done on its day too: the earlier one stays extra.
        val both = listOf(CatchUp.Day(3, emptyList(), listOf(lift)), CatchUp.Day(4, listOf(lift), listOf(lift)))
        assertEquals(listOf(CatchUp.Close(4, 0, 4, 0)), CatchUp.closes(both, today = 5))
    }

    @Test fun theHardRunClosesTheHardRunOfItsDayNotTheWarmUpJog() {
        val hard = PlannedSession(SessionKind.RUN_QUALITY)
        val jog = PlannedSession(SessionKind.RUN_EASY)
        val week = listOf(CatchUp.Day(4, listOf(hard), listOf(jog, hard)))
        assertEquals(listOf(CatchUp.Close(4, 0, 4, 1)), CatchUp.closes(week))
    }

    /** Legs before a hard run: the "+" set stops with 2 in reserve, and the same strength moves the weights the same. */
    @Test fun aPlusSetStoppedEarlierBeforeAHardRunGrowsTheWeightsAsFast() {
        val squat = Catalog["squat"]
        val spec = SlotSpec("squat", 3, 5, 8, 2, 150, SlotRole.MAIN)
        val st = SlotState(100.0, 6, 0, 0, 5, linear = false)
        val usual = Progression.prescribe(squat, spec, st)
        val beforeRun = Progression.prescribe(squat, spec, st, topRir = 2)
        assertEquals(1, usual.topRir)
        assertEquals(2, beforeRun.topRir)
        assertEquals(usual.copy(topRir = 2), beforeRun)
        // The same lifter: 6 + 2 with one in reserve, or 6 + 1 with two in reserve.
        val a = Progression.evaluate(squat, spec, st, usual, listOf(DoneSet(100.0, 6, 2), DoneSet(100.0, 6, 2), DoneSet(100.0, 8, 1)))
        val b = Progression.evaluate(squat, spec, st, beforeRun, listOf(DoneSet(100.0, 6, 2), DoneSet(100.0, 6, 2), DoneSet(100.0, 7, 2)))
        assertEquals(a.outcome, b.outcome)
        assertEquals(a.state.weight, b.state.weight)
        // Not rated: a "+" set before a hard run is taken as stopped with 2 in reserve.
        assertEquals(2, Progression.assumedRir(DoneSet(100.0, 7), 2, beforeRun))
    }
}
