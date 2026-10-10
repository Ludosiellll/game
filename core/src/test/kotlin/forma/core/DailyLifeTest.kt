package forma.core

import forma.core.body.Body
import forma.core.load.TrainingLoad
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.model.Sex
import forma.core.plan.CatchUp
import forma.core.plan.DayPlan
import forma.core.plan.Focus
import forma.core.plan.PlannedSession
import forma.core.plan.Program
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.plan.Template
import forma.core.plan.WeekPlan
import forma.core.run.RunCaps
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Body estimates, bad days, missed sessions: the parts of the plan that meet daily life. */
class DailyLifeTest {
    // ---------- body ----------

    @Test fun bodyEstimates() {
        assertEquals(24.69, Body.bmi(80.0, 180), 0.01)
        assertEquals("норма", Body.bmiClass(24.7))
        assertEquals("ожирение", Body.bmiClass(31.0))
        // Mifflin–St Jeor: 10·80 + 6.25·180 − 5·30 + 5.
        assertEquals(1780.0, Body.bmr(80.0, 180, 30, Sex.MALE), 1e-9)
        assertEquals(1614.0, Body.bmr(80.0, 180, 30, Sex.FEMALE), 1e-9)
        assertEquals(800.0, Body.runKcal(80.0, 10.0), 1e-9)
        assertEquals(300.0, Body.liftKcal(80.0, 50.0), 1e-9)
        val muscle = Body.protein(80.0, LiftFocus.MUSCLE)
        assertEquals(128.0, muscle.start, 1e-9)
        assertEquals(176.0, muscle.endInclusive, 1e-9)
        assertTrue(Body.protein(80.0, LiftFocus.GENERAL).endInclusive < muscle.endInclusive)
    }

    @Test fun heavierNoviceRunnersStartShorter() {
        val heavy = ProgramGenerator.initialRunState(Profile(bodyWeightKg = 95.0, heightCm = 170, runLevel = RunLevel.BASE))
        val usual = ProgramGenerator.initialRunState(Profile(bodyWeightKg = 75.0, heightCm = 180, runLevel = RunLevel.BASE))
        assertEquals(20 to 30, heavy.easyMinutes to heavy.longMinutes)
        assertEquals(25 to 40, usual.easyMinutes to usual.longMinutes)
        // Without a height nothing is assumed.
        assertEquals(25, ProgramGenerator.initialRunState(Profile(bodyWeightKg = 95.0, runLevel = RunLevel.BASE)).easyMinutes)
    }

    @Test fun freshnessNeedsHistory() {
        val d0 = java.time.LocalDate.of(2026, 10, 5)
        // One hard session would read as "−100 %, rest": not enough to say anything.
        assertTrue(!TrainingLoad.hasHistory(mapOf(d0 to 300.0), d0.plusDays(2)))
        assertTrue(!TrainingLoad.hasHistory((0L until 4L).associate { d0.plusDays(it) to 300.0 }, d0.plusDays(5)))
        assertTrue(TrainingLoad.hasHistory((0L until 28L step 2).associate { d0.plusDays(it) to 300.0 }, d0.plusDays(28)))
    }

    // ---------- a bad day ----------

    @Test fun aBadDayMakesAnyRunAnEasyOneThatMovesNoLadder() {
        val st = RunState(vdot = 48.0, walkStage = -1, easyMinutes = 40, longMinutes = 70, intervalStage = 3, returnEasy = 1)
        val caps = RunCaps(45, 90)
        val light = RunPlan.lighter(st, RunPlan.intervals(st))
        assertEquals(RunType.EASY, light.type)
        assertTrue(light.light)
        assertTrue(light.totalSeconds <= 40 * 60)
        val after = RunPlan.after(st, light, RunResult(1.0, 3, light.totalSeconds), caps, day = 10).state
        assertEquals(3, after.intervalStage)
        assertEquals(40, after.easyMinutes)
        assertEquals(1, after.runs)
        assertEquals(0, after.returnEasy)
        // The long run becomes three quarters of it at most, never longer than an easy run.
        assertEquals(40 * 60, RunPlan.lighter(st, RunPlan.long(st)).totalSeconds)
        assertEquals(15 * 60, RunPlan.lighter(st.copy(easyMinutes = 20), RunPlan.easy(st.copy(easyMinutes = 20))).totalSeconds)
        // Run/walk: last week's session, and the ladder does not move either way.
        val walk = RunState(walkStage = 4)
        val easier = RunPlan.lighter(walk, RunPlan.runWalk(walk))
        assertTrue(easier.light)
        assertEquals(1, easier.stage)
        assertEquals(4, RunPlan.after(walk, easier, RunResult(1.0, 4, easier.totalSeconds), caps, day = 10).state.walkStage)
        assertEquals(0, RunPlan.lighter(RunState(walkStage = 1), RunPlan.runWalk(RunState(walkStage = 1))).stage)
    }

    // ---------- missed sessions ----------

    private val fullA = Template("full_a", "Всё тело · А", Focus.FULL, emptyList())
    private val upper = Template("upper", "Верх", Focus.UPPER, emptyList())
    private val lift = PlannedSession(SessionKind.LIFT, "full_a")
    private val liftUpper = PlannedSession(SessionKind.LIFT, "upper")
    private val easy = PlannedSession(SessionKind.RUN_EASY)
    private val long = PlannedSession(SessionKind.RUN_LONG)
    private val quality = PlannedSession(SessionKind.RUN_QUALITY)

    private fun program(aim: Aim, vararg days: Pair<Int, List<PlannedSession>>) = Program(
        aim.nearestGoal(), Experience.INTERMEDIATE, listOf(fullA, upper),
        WeekPlan(days.map { DayPlan(it.first, it.second) }, emptyList()), 0, RunCaps(45, 90), 0, aim,
    )

    private fun week(p: Program, done: Map<Int, List<PlannedSession>>) =
        (1..7).map { d -> CatchUp.Day(d, p.week.on(d), done[d].orEmpty()) }

    private val hybrid = Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.BALANCED)

    @Test fun aMissedLiftCanBeDoneOnAFreeDay() {
        val p = program(hybrid, 1 to listOf(lift), 2 to listOf(quality), 6 to listOf(long))
        // Monday's lift was skipped, Tuesday done; Thursday is free and Friday is free too.
        val s = CatchUp.suggest(p, 4, week(p, mapOf(2 to listOf(quality))))
        assertEquals(CatchUp.Suggestion(lift, 1), s)
        // But not on Friday: heavy legs the day before the long run.
        assertNull(CatchUp.suggest(p, 5, week(p, mapOf(2 to listOf(quality)))))
    }

    @Test fun aSessionDoneOnAnotherDayIsNotMissed() {
        val p = program(hybrid, 1 to listOf(lift), 3 to listOf(quality), 6 to listOf(long))
        assertNull(CatchUp.suggest(p, 4, week(p, mapOf(2 to listOf(lift), 3 to listOf(quality)))))
    }

    @Test fun nothingIsSuggestedOnAPlannedDay() {
        val p = program(hybrid, 1 to listOf(lift), 3 to listOf(quality), 6 to listOf(long))
        assertNull(CatchUp.suggest(p, 3, week(p, emptyMap())))
    }

    @Test fun theLayoutRulesDecideWhatFits() {
        // Missed: Monday full body, Tuesday intervals. Today Wednesday; Thursday is a leg day.
        val p = program(hybrid, 1 to listOf(lift), 2 to listOf(quality), 4 to listOf(lift), 6 to listOf(long))
        // Neither heavy legs nor intervals the day before heavy legs: the missed sessions wait.
        assertNull(CatchUp.suggest(p, 3, week(p, emptyMap())))
        // An upper-body day fits anywhere, an easy run too.
        val q = program(hybrid, 1 to listOf(liftUpper, easy), 4 to listOf(lift), 6 to listOf(long))
        assertEquals(CatchUp.Suggestion(liftUpper, 1), CatchUp.suggest(q, 3, week(q, emptyMap())))
    }

    @Test fun noHeavyLegsRightAfterAHardRunAndNoHardRunRightAfterLegs() {
        // Missed: Monday's lift. Tuesday the intervals were done; Wednesday is free.
        val p = program(hybrid, 1 to listOf(lift), 2 to listOf(quality), 6 to listOf(long))
        assertNull(CatchUp.suggest(p, 3, week(p, mapOf(2 to listOf(quality)))))
        // Missed: Tuesday's intervals. Wednesday was a (catch-up) leg day; Thursday is free.
        val q = program(hybrid, 2 to listOf(quality), 6 to listOf(long))
        assertNull(CatchUp.suggest(q, 4, week(q, mapOf(3 to listOf(lift)))))
        // Without the leg day the intervals fit on Thursday.
        assertEquals(CatchUp.Suggestion(quality, 2), CatchUp.suggest(q, 4, week(q, emptyMap())))
    }

    @Test fun onSundayNextMondayCounts() {
        // Missed: Friday's lift; next Monday is a leg day again.
        val p = program(hybrid, 1 to listOf(lift), 5 to listOf(lift))
        assertNull(CatchUp.suggest(p, 7, week(p, mapOf(1 to listOf(lift)))))
        // With an upper-body Monday it fits.
        val q = program(hybrid, 1 to listOf(liftUpper), 5 to listOf(lift))
        assertEquals(CatchUp.Suggestion(lift, 5), CatchUp.suggest(q, 7, week(q, mapOf(1 to listOf(liftUpper)))))
    }

    @Test fun thePriorityPicksBetweenTwoMissedSessions() {
        val days = arrayOf(1 to listOf(lift), 2 to listOf(easy))
        val gym = program(hybrid.copy(emphasis = Emphasis.GYM), *days)
        val run = program(hybrid.copy(emphasis = Emphasis.RUN), *days)
        assertEquals(SessionKind.LIFT, CatchUp.suggest(gym, 4, week(gym, emptyMap()))?.session?.kind)
        assertEquals(SessionKind.RUN_EASY, CatchUp.suggest(run, 4, week(run, emptyMap()))?.session?.kind)
        // The classic presets still work through their aim.
        assertEquals(Goal.HYBRID, hybrid.nearestGoal())
    }
}
