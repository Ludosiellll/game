package forma.core

import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
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
        val mix = Scheduler.mix(Goal.HYBRID, 4, Experience.NOVICE, RunLevel.BASE)
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
        for (t in Templates.forWeek(Goal.STRENGTH, Experience.ADVANCED, 4)) {
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
