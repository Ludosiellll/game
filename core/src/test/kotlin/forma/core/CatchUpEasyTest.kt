package forma.core

import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.plan.CatchUp
import forma.core.plan.DayPlan
import forma.core.plan.Focus
import forma.core.plan.Template
import forma.core.plan.PlannedSession
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.plan.WeekPlan
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Denis, 4 November: Monday's tempo run missed, the gym done on Tuesday, Wednesday free, a tempo run again on Thursday.
 * The hard run cannot be on Wednesday (two hard days in a row); it is offered as an easy run, and done, it is Monday's —
 * not Saturday's easy run done ahead.
 */
class CatchUpEasyTest {
    private val quality = PlannedSession(SessionKind.RUN_QUALITY)
    private val easy = PlannedSession(SessionKind.RUN_EASY)
    private val upperB = PlannedSession(SessionKind.LIFT, "upper_b")
    private val upperA = PlannedSession(SessionKind.LIFT, "upper_a")
    private val lower = PlannedSession(SessionKind.LIFT, "lower_a")
    private val plan = mapOf(1 to listOf(quality), 2 to listOf(upperB), 4 to listOf(quality, upperA), 6 to listOf(lower, easy))

    private val program = ProgramGenerator.generate(
        Profile(
            goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
            bodyWeightKg = 71.0, aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
        ),
        LocalDate.of(2026, 11, 2),
    ).let { p ->
        // His templates: the upper days do not work the legs.
        p.copy(
            week = WeekPlan(plan.map { (d, s) -> DayPlan(d, s) }, p.week.notes),
            templates = listOf(
                Template("upper_a", "Верх · А", Focus.UPPER, emptyList()), Template("upper_b", "Верх · Б", Focus.UPPER, emptyList()),
                Template("lower_a", "Низ", Focus.LOWER, emptyList()),
            ),
        )
    }

    private fun week(done: Map<Int, List<PlannedSession>>) = (1..7).map { CatchUp.Day(it, plan[it].orEmpty(), done[it].orEmpty()) }

    @Test fun theMissedTempoIsOfferedEasy() {
        val s = assertNotNull(CatchUp.suggest(program, 3, week(mapOf(2 to listOf(upperB)))))
        assertEquals(quality, s.session)
        assertEquals(1, s.plannedOn)
        assertTrue(s.easy, "a hard run with Thursday's ahead: as an easy one")
    }

    @Test fun aHardRunThatFitsIsOfferedAsItIs() {
        // Nothing hard on Thursday: the tempo itself fits on Wednesday.
        val free = plan + (4 to listOf(upperA))
        val p = program.copy(week = WeekPlan(free.map { (d, s) -> DayPlan(d, s) }, program.week.notes))
        val s = assertNotNull(CatchUp.suggest(p, 3, (1..7).map { CatchUp.Day(it, free[it].orEmpty(), if (it == 2) listOf(upperB) else emptyList()) }))
        assertEquals(false, s.easy)
    }

    @Test fun theEasyRunDoneForItIsMondays() {
        val w = week(mapOf(2 to listOf(upperB), 3 to listOf(easy))).map { if (it.day == 3) it.copy(doneInstead = listOf(true)) else it }
        assertEquals(1, CatchUp.closes(w, today = 3).first { it.doneDay == 3 }.day, "Wednesday's easy run closes Monday's tempo")
    }

    @Test fun anEasyRunOfHisOwnIsSaturdaysAhead() {
        // Not started for the missed tempo: as before, Saturday's easy run done ahead.
        val c = CatchUp.closes(week(mapOf(2 to listOf(upperB), 3 to listOf(easy))), today = 3).first { it.doneDay == 3 }
        assertEquals(6, c.day)
    }
}
