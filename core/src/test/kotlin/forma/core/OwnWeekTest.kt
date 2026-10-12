package forma.core

import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.Focus
import forma.core.plan.Program
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The week laid out by the person day by day: the gym on its days, running on its days, the rules inside them. */
class OwnWeekTest {
    private val monday = LocalDate.of(2026, 10, 12)

    private fun denis(gym: Set<Int>, run: Set<Int>) = Profile(
        name = "Денис", aim = Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
        runLevel = RunLevel.BASE, trainingDays = gym + run, bodyWeightKg = 80.0, birthYear = 2008, doubleDays = true,
        runGoal = RunGoal(1500, 330), lapMeters = 230, gymDays = gym, runDays = run,
    )

    private fun Program.daysOf(kind: (SessionKind) -> Boolean) = week.days.filter { d -> d.sessions.any { kind(it.kind) } }.map { it.day }.toSet()
    private fun Program.legsOn(day: Int) = week.on(day).any { s -> s.kind == SessionKind.LIFT && template(s.templateId!!).legs }

    @Test fun mondayCanBeARunningDay() {
        val p = ProgramGenerator.generate(denis(setOf(2, 4, 6), setOf(1, 2, 4, 6)), monday)
        assertEquals(setOf(2, 4, 6), p.daysOf { it == SessionKind.LIFT })
        assertEquals(setOf(1, 2, 4, 6), p.daysOf { it != SessionKind.LIFT })
        // 1500 m: race pace and intervals, the rest easy; no long run for a 1500 m runner.
        val runs = p.week.days.flatMap { it.sessions }.filter { it.isRun }
        assertEquals(2, runs.count { it.kind == SessionKind.RUN_QUALITY })
        assertEquals(0, runs.count { it.kind == SessionKind.RUN_LONG })
        // A hard run never shares a day with the legs.
        for (d in 1..7) if (p.legsOn(d)) assertTrue(p.week.on(d).none { it.isHardRun }, "day $d: ${p.week.on(d)}")
        assertEquals(ProgramGenerator.PLANNER, p.planner)
    }

    @Test fun runningAndTheGymOnTheirOwnDays() {
        val p = ProgramGenerator.generate(denis(setOf(2, 4, 6), setOf(1, 3, 5)), monday)
        // Nothing shares a day.
        assertTrue(p.week.days.all { d -> d.sessions.size == 1 }, p.week.toString())
        assertEquals(setOf(1, 3, 5), p.daysOf { it != SessionKind.LIFT })
        // No hard run the day after heavy legs when another run day is free of it.
        val hard = p.week.days.filter { d -> d.sessions.any { it.isHardRun } }.map { it.day }
        assertEquals(2, hard.size)
        assertTrue(hard.count { p.legsOn(it - 1) } <= 1, "hard on $hard, week ${p.week}")
    }

    @Test fun hardRunsNeedADayAwayFromHeavyLegs() {
        // One full-body day, the only run on it: the run is easy.
        val p = ProgramGenerator.generate(denis(setOf(3), setOf(3)), monday)
        assertTrue(p.week.on(3).none { it.isHardRun }, p.week.on(3).toString())
        assertTrue(p.templates.single().focus == Focus.FULL)
    }

    @Test fun theAutomaticWeekStaysAsItWas() {
        val auto = denis(setOf(2, 4, 6), setOf(1, 2, 4, 6)).copy(gymDays = null, runDays = null, trainingDays = setOf(2, 4, 6))
        val p = ProgramGenerator.generate(auto, monday)
        assertEquals(setOf(2, 4, 6), p.daysOf { true })
        assertEquals(ProgramGenerator.PLANNER, p.planner)
    }

    /**
     * Whatever days are ticked, heavy legs and a hard run never share a day: a hard run turns easy when the days leave
     * no room (gym Tue/Wed/Fri/Sat with runs Tue–Sat used to put the long run on the legs day).
     */
    @Test fun noHardRunOnALegsDayWhateverTheDays() {
        val rnd = kotlin.random.Random(7)
        val sample = listOf(setOf(2, 3, 5, 6) to setOf(2, 3, 4, 5, 6), setOf(1, 2, 3, 4, 5) to setOf(1, 2, 4)) +
            List(150) { (1..7).filter { rnd.nextBoolean() }.toSet() to (1..7).filter { rnd.nextBoolean() }.toSet() }
        for ((gym, run) in sample) {
            if ((gym + run).isEmpty()) continue
            val p = ProgramGenerator.generate(denis(gym, run), monday)
            for (d in 1..7) assertTrue(!(p.legsOn(d) && p.week.on(d).any { it.isHardRun }), "gym $gym run $run: ${p.week.days}")
        }
    }
}
