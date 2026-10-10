package forma.core

import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.model.aim
import forma.core.run.RunCaps
import forma.core.strength.SlotRole
import forma.core.plan.ProgramGenerator
import forma.core.plan.Scheduler
import forma.core.plan.SessionKind
import forma.core.plan.Templates
import forma.core.plan.WeekPlan
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedulerTest {
    private val today = LocalDate.of(2026, 10, 10)

    private fun plan(goal: Goal, days: Set<Int>, exp: Experience = Experience.NOVICE, run: RunLevel = RunLevel.BASE) =
        ProgramGenerator.generate(Profile(goal = goal, trainingDays = days, experience = exp, runLevel = run), today)

    private fun WeekPlan.count(kind: SessionKind) = days.sumOf { d -> d.sessions.count { it.kind == kind } }

    private fun describe(w: WeekPlan): String {
        val names = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        return w.days.joinToString("  ") { d -> names[d.day - 1] + ":" + d.sessions.joinToString("+") { it.templateId ?: it.kind.name } }
    }

    @Test fun hybridFourDaysPlacesEverythingSafely() {
        val p = plan(Goal.HYBRID, setOf(1, 3, 5, 6))
        println("hybrid 4: " + describe(p.week) + " | " + p.week.notes)
        val mix = Scheduler.mix(Goal.HYBRID.aim(), 4, Experience.NOVICE, RunLevel.BASE)
        assertEquals(mix.lifts, p.week.count(SessionKind.LIFT))
        assertEquals(mix.runs, p.week.count(SessionKind.RUN_EASY) + p.week.count(SessionKind.RUN_QUALITY) + p.week.count(SessionKind.RUN_LONG))
        assertTrue(p.week.days.all { it.day in setOf(1, 3, 5, 6) })
        assertSafe(p.week, p)
    }

    private fun assertSafe(w: WeekPlan, p: forma.core.plan.Program) {
        for (d in w.days) {
            assertTrue(d.sessions.count { it.kind == SessionKind.LIFT } <= 1, "two lifts on ${d.day}")
            assertTrue(d.sessions.count { it.isRun } <= 1, "two runs on ${d.day}")
            val lift = d.sessions.firstOrNull { it.kind == SessionKind.LIFT }
            val hard = d.sessions.firstOrNull { it.isHardRun }
            if (lift != null && hard != null) assertTrue(!p.template(lift.templateId!!).legs, "legs and a hard run on ${d.day}")
        }
        assertTrue(w.days.size < 7, "no rest day")
    }

    @Test fun everyGoalAndDayCountWorks() {
        for (goal in Goal.entries) for (n in 1..7) for (exp in Experience.entries) for (run in RunLevel.entries) {
            val days = (1..7).take(n).toSet()
            val p = plan(goal, days, exp, run)
            assertSafe(p.week, p)
            if (run == RunLevel.NONE) {
                assertEquals(0, p.week.count(SessionKind.RUN_QUALITY))
                assertEquals(0, p.week.count(SessionKind.RUN_LONG))
            }
        }
    }

    // ---------- combinations ----------

    private fun aimPlan(aim: Aim, days: Set<Int>, exp: Experience = Experience.INTERMEDIATE, run: RunLevel = RunLevel.TRAINED) =
        ProgramGenerator.generate(Profile(aim = aim, trainingDays = days, experience = exp, runLevel = run), today)

    private val WeekPlan.runs get() = count(SessionKind.RUN_EASY) + count(SessionKind.RUN_QUALITY) + count(SessionKind.RUN_LONG)

    @Test fun everyCombinationAndDayCountWorks() {
        for (lift in LiftFocus.entries) for (run in RunFocus.entries) for (emphasis in Emphasis.entries) for (n in 1..7) {
            val aim = Aim(lift, run, emphasis)
            for ((exp, level) in listOf(Experience.NOVICE to RunLevel.NONE, Experience.INTERMEDIATE to RunLevel.TRAINED)) {
                val p = aimPlan(aim, (1..7).take(n).toSet(), exp, level)
                assertSafe(p.week, p)
                val a = aim.normalized()
                if (!a.lifts) assertEquals(0, p.week.count(SessionKind.LIFT), "$aim $n")
                if (!a.runs) assertEquals(0, p.week.runs, "$aim $n")
                if (a.run == RunFocus.HEALTH || level == RunLevel.NONE) {
                    assertEquals(0, p.week.count(SessionKind.RUN_QUALITY) + p.week.count(SessionKind.RUN_LONG), "$aim $n")
                }
                assertTrue(p.week.count(SessionKind.LIFT) + p.week.runs > 0, "$aim $n: empty week")
            }
        }
    }

    @Test fun muscleAndRunningEnduranceGetsBoth() {
        // "Great hypertrophy and big running endurance": four days, both sides equally.
        val p = aimPlan(Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.BALANCED), setOf(1, 2, 4, 6))
        println("muscle+endurance 4: " + describe(p.week) + " | " + p.week.notes)
        assertEquals(2, p.week.count(SessionKind.LIFT))
        assertEquals(1, p.week.count(SessionKind.RUN_LONG))
        assertEquals(1, p.week.count(SessionKind.RUN_QUALITY))
        // Hypertrophy rep ranges, accessories trimmed to two sets for the running.
        val main = p.templates.flatMap { it.slots }.filter { it.role == SlotRole.MAIN }
        assertTrue(main.all { it.repMin == 6 && it.repMax == 10 }, "$main")
        assertTrue(p.templates.flatMap { it.slots }.filter { it.role == SlotRole.ACCESSORY && it.exerciseId != "plank" }.all { it.sets == 2 })
        assertEquals(RunCaps(50, 110), p.runCaps)
        assertTrue(p.week.notes.any { it.contains("почти не мешает") })
        // The long run on the weekend, never on a lifting day; two leg sessions at most.
        val long = p.week.days.single { d -> d.sessions.any { it.kind == SessionKind.RUN_LONG } }
        assertTrue(long.day >= 6 && long.sessions.size == 1, describe(p.week))
        assertTrue(p.templates.count { it.legs } <= 2)
        // With a free day between them, heavy legs never come the day before a hard run.
        val roomy = aimPlan(Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.BALANCED), setOf(1, 2, 3, 5, 6, 7))
        println("muscle+endurance 6: " + describe(roomy.week))
        for (d in roomy.week.days) {
            val legs = d.sessions.any { it.kind == SessionKind.LIFT && roomy.template(it.templateId!!).legs }
            if (legs) assertTrue(roomy.week.on(d.day % 7 + 1).none { it.isHardRun }, describe(roomy.week))
        }
    }

    @Test fun priorityMovesTheDays() {
        val days = setOf(1, 2, 3, 5, 6)
        val gym = aimPlan(Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.GYM), days).week
        val both = aimPlan(Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.BALANCED), days).week
        val run = aimPlan(Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.RUN), days).week
        println("gym: " + describe(gym)); println("both: " + describe(both)); println("run: " + describe(run))
        assertEquals(listOf(4, 3, 2), listOf(gym, both, run).map { it.count(SessionKind.LIFT) })
        assertEquals(listOf(2, 3, 4), listOf(gym, both, run).map { it.runs })
        // Runners run first on shared days, lifters lift first.
        run.days.filter { it.sessions.size == 2 }.forEach { assertTrue(it.sessions.first().isRun) }
        gym.days.filter { it.sessions.size == 2 }.forEach { assertTrue(!it.sessions.first().isRun) }
    }

    @Test fun runFocusDecidesWhatTheRunsAre() {
        val days = (1..6).toSet()
        val health = aimPlan(Aim(LiftFocus.GENERAL, RunFocus.HEALTH, Emphasis.RUN), days).week
        val endurance = aimPlan(Aim(LiftFocus.GENERAL, RunFocus.ENDURANCE, Emphasis.RUN), days).week
        val speed = aimPlan(Aim(LiftFocus.GENERAL, RunFocus.SPEED, Emphasis.RUN), days).week
        assertEquals(health.runs, health.count(SessionKind.RUN_EASY))
        assertEquals(1, endurance.count(SessionKind.RUN_LONG))
        assertEquals(2, speed.count(SessionKind.RUN_QUALITY))
        assertTrue(ProgramGenerator.caps(Aim(LiftFocus.NONE, RunFocus.ENDURANCE)).longMax > ProgramGenerator.caps(Aim(LiftFocus.NONE, RunFocus.SPEED)).longMax)
    }

    @Test fun oneSideOnly() {
        val runner = aimPlan(Aim(LiftFocus.NONE, RunFocus.ENDURANCE, Emphasis.GYM), setOf(1, 3, 5, 6)).week
        assertEquals(0, runner.count(SessionKind.LIFT))
        assertEquals(4, runner.runs)
        val lifter = aimPlan(Aim(LiftFocus.STRENGTH, RunFocus.NONE, Emphasis.RUN), setOf(1, 3, 5)).week
        assertEquals(3, lifter.count(SessionKind.LIFT))
        assertEquals(0, lifter.runs)
    }

    @Test fun classicGoalsAreCombinations() {
        for (g in Goal.entries) assertEquals(g, g.aim().nearestGoal(), "$g")
        assertEquals(Goal.HYBRID, Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.BALANCED).nearestGoal())
        // Profiles saved before combinations existed keep their goal's plan.
        val old = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(Profile.serializer(), """{"goal":"MUSCLE","experience":"INTERMEDIATE"}""")
        assertEquals(Goal.MUSCLE.aim(), old.aim)
    }

    @Test fun printSomeWeeks() {
        println("strength 3: " + describe(plan(Goal.STRENGTH, setOf(1, 3, 5)).week))
        println("strength 4 int: " + describe(plan(Goal.STRENGTH, setOf(1, 2, 4, 5), Experience.INTERMEDIATE).week))
        println("hybrid 3: " + describe(plan(Goal.HYBRID, setOf(2, 4, 6)).week))
        println("hybrid 5: " + describe(plan(Goal.HYBRID, setOf(1, 2, 3, 5, 6)).week))
        println("hybrid 6: " + describe(plan(Goal.HYBRID, setOf(1, 2, 3, 4, 5, 6)).week))
        println("hybrid 7: " + describe(plan(Goal.HYBRID, (1..7).toSet()).week))
        println("endurance 5: " + describe(plan(Goal.ENDURANCE, setOf(1, 2, 4, 6, 7)).week))
        println("muscle 5 int: " + describe(plan(Goal.MUSCLE, setOf(1, 2, 3, 4, 5), Experience.INTERMEDIATE).week))
        println("fitness 3 none: " + describe(plan(Goal.FITNESS, setOf(1, 3, 5), run = RunLevel.NONE).week))
    }

    @Test fun templatesFitTheTimeBudget() {
        for (t in Templates.forWeek(Goal.STRENGTH.aim(), Experience.ADVANCED, 4)) {
            val fitted = Templates.fit(t, 60)
            assertTrue(Templates.minutes(fitted) <= 60 || fitted.slots.size <= 3, "${t.id}: ${Templates.minutes(fitted)}")
        }
    }

    @Test fun blockWeeksAndRir() {
        val p = plan(Goal.HYBRID, setOf(1, 3, 5), Experience.INTERMEDIATE)
        assertEquals(5, p.blockWeeks)
        val monday = LocalDate.ofEpochDay(p.startEpochDay)
        assertEquals(1, ProgramGenerator.rirOffset(p, monday))
        assertEquals(-1, ProgramGenerator.rirOffset(p, monday.plusWeeks(3)))
        assertTrue(ProgramGenerator.isDeloadWeek(p, monday.plusWeeks(4)))
        assertTrue(!ProgramGenerator.isDeloadWeek(p, monday.plusWeeks(3)))
    }
}
