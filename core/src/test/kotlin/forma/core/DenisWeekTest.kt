package forma.core

import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.run.RunCaps
import forma.core.run.RunLog
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
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
 * Denis's week as it is (runs Mon/Thu/Sat on a 230 m track, the gym Tue/Thu/Sat, 1500 m in 5:30) and the review's
 * fixes for it: race-pace volume on whole laps, even laps over fast ones, the ladder after a missed trial, tempo more
 * often, hard work kept to a share of the week, the one easy run as the aerobic base, the block's first week.
 */
class DenisWeekTest {
    private val goal = RunGoal(1500, 330)
    private val lap = 230
    private val caps = RunCaps(45, 90)

    @Test fun racePaceVolumeOnWholeLapsStaysWithinOneAndAHalfRaces() {
        for (stage in 0 until RunPlan.goalLadderSize - 1) {
            val w = RunPlan.goalPace(RunState(walkStage = -1, vdot = 49.0, goalStage = stage), goal, lap)
            val work = w.segments.filter { it.kind == SegmentKind.WORK }
            val meters = work.sumOf { it.laps!! * lap }
            assertTrue(meters <= 1.6 * 1500 + 1e-6, "step $stage: ${w.title} = $meters m")
            assertTrue(!w.cutback, "step $stage is not a lighter week")
        }
        // The two steps that were 2760 m (1.84 races): 5 × 2 laps and 3 × 3 laps, the rest growing with the rep.
        val four = RunPlan.goalPace(RunState(walkStage = -1, vdot = 49.0, goalStage = 4), goal, lap)
        assertEquals("Темп цели · 5 × 2 круга", four.title)
        val five = RunPlan.goalPace(RunState(walkStage = -1, vdot = 49.0, goalStage = 5), goal, lap)
        assertEquals("Темп цели · 3 × 3 круга", five.title)
        assertEquals(210, five.segments.first { it.kind == SegmentKind.RECOVERY }.seconds)
    }

    @Test fun lapsMuchFasterThanTheGoalRepeatTheStep() {
        val st = RunState(walkStage = -1, vdot = 49.0, goalStage = 1, runs = 6)
        val w = RunPlan.goalPace(st, goal, lap)
        val target = w.segments.first { it.kind == SegmentKind.WORK }.pace!!.let { (it.fast + it.slow) / 2.0 }
        // 46 s laps instead of 50.6: 9 % fast — the step again, said why.
        val fast = RunPlan.after(st, w, RunResult(1.0, 6, w.totalSeconds, workPace = target * 0.91), caps, day = 20, goal = goal)
        assertEquals(1, fast.state.goalStage)
        assertTrue(RunOutcome.TooFastRepeat in fast.outcomes)
        // 2.5 % fast: on, with the hint to keep the lap time.
        val near = RunPlan.after(st, w, RunResult(1.0, 7, w.totalSeconds, workPace = target * 0.975), caps, day = 20, goal = goal)
        assertEquals(2, near.state.goalStage)
        assertTrue(RunOutcome.TooFast in near.outcomes)
    }

    @Test fun aMissedTrialStartsTheLadderAgainFromTheFourHundreds() {
        val top = RunState(walkStage = -1, vdot = 47.0, goalStage = RunPlan.goalLadderSize - 1, testSoon = true, runs = 30)
        val trial = RunPlan.goalTest(top, goal, lap)
        // 7 laps (1610 m) in 6:20: 1500 m in about 5:52, short of 5:30.
        val a = RunPlan.after(top, trial, RunResult(1.0, 9, 1700, testSeconds = 380), caps, day = 125, goal = goal)
        assertEquals(3, a.state.goalStage)
        assertEquals("Темп цели · 4 × 2 круга", RunPlan.goalPace(a.state, goal, lap).title)
    }

    @Test fun tempoComesTwiceForEveryIntervals() {
        val types = (0 until 6).map { RunPlan.quality(RunState(vdot = 49.0, walkStage = -1, qualityCount = it), goal = goal, lapMeters = lap).type }
        assertEquals(2, types.count { it == forma.core.run.RunType.TEMPO })
        assertEquals(1, types.count { it == forma.core.run.RunType.INTERVALS })
        assertEquals(3, types.count { it == forma.core.run.RunType.GOAL_PACE })
    }

    @Test fun hardWorkIsKeptToAShareOfTheWeek() {
        // About 150 minutes a week (three runs of 50): the top of the ladders is cut to it.
        val recent = (0 until 6).map { RunLog(100L + it * 2, 50) }
        val st = RunState(walkStage = -1, vdot = 49.0, intervalStage = 7, tempoStage = 6, recent = recent, runs = 20)
        val i = RunPlan.intervals(st)
        assertTrue(i.segments.filter { it.kind == SegmentKind.WORK }.sumOf { it.seconds } <= 15 * 60, i.title)
        assertTrue(!i.cutback)
        val t = RunPlan.tempo(st)
        assertTrue(t.segments.filter { it.kind == SegmentKind.WORK }.sumOf { it.seconds } <= 20 * 60, t.title)
        assertTrue(!t.cutback)
        // Without a known week: the ladder as it is.
        assertEquals(5 * 300, RunPlan.intervals(st.copy(recent = emptyList())).segments.filter { it.kind == SegmentKind.WORK }.sumOf { it.seconds })
    }

    private val denis = Profile(
        aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
        runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6), runGoal = goal, lapMeters = lap, doubleDays = true,
        gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
    )

    @Test fun theOneEasyRunOfHisWeekMayGrowToAnHour() {
        val p = ProgramGenerator.generate(denis, LocalDate.of(2026, 10, 12))
        val runs = p.week.days.flatMap { it.sessions }.filter { it.isRun }
        assertEquals(1, runs.count { it.kind == SessionKind.RUN_EASY }, p.week.toString())
        assertEquals(0, runs.count { it.kind == SessionKind.RUN_LONG })
        assertEquals(60, ProgramGenerator.runCaps(p, middleGoal = true).easyMax)
        // His gym plan is the same as before: three days, upper / lower / upper.
        assertEquals(listOf("upper_a", "lower_a", "upper_b"), p.templates.map { it.id })
    }

    @Test fun inTheBlocksFirstWeekARepShortIsWhatWasAsked() {
        val squat = Catalog["squat"]
        val spec = SlotSpec("squat", 3, 5, 8, 2, 150, SlotRole.MAIN)
        val st = SlotState(100.0, 6, 0, 0, 5, linear = false)
        val p = Progression.prescribe(squat, spec, st, rirOffset = 1)
        assertTrue(p.intro)
        assertEquals(100.0, p.weight)
        // Told to keep a rep more in reserve, he did 5 with 3 left: the weight and the target stay.
        val ev = Progression.evaluate(squat, spec, st, p, List(3) { DoneSet(100.0, 5, 3) })
        assertEquals(Outcome.Hold(100.0, 6, HoldReason.INTRO), ev.outcome)
        assertEquals(100.0, ev.state.weight)
        assertEquals(6, ev.state.reps)
        // An ordinary week: the same session is a miss.
        val normal = Progression.prescribe(squat, spec, st)
        assertTrue(!normal.intro)
        assertTrue(Progression.evaluate(squat, spec, st, normal, List(3) { DoneSet(100.0, 5, 0) }).outcome !is Outcome.Hold ||
            (Progression.evaluate(squat, spec, st, normal, List(3) { DoneSet(100.0, 5, 0) }).outcome as Outcome.Hold).reason != HoldReason.INTRO)
    }
}
