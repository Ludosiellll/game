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
import forma.core.plan.PlannedSession
import forma.core.plan.Program
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.plan.Template
import forma.core.plan.WeekPlan
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The audit of 24 November, the week (group C): the catch-up offered is the one the week shows missed and the one it closes. */
class AuditFixWeekLighterTest {
    private val quality = PlannedSession(SessionKind.RUN_QUALITY)
    private val easy = PlannedSession(SessionKind.RUN_EASY)
    private val upperA = PlannedSession(SessionKind.LIFT, "upper_a")
    private val upperB = PlannedSession(SessionKind.LIFT, "upper_b")
    private val lower = PlannedSession(SessionKind.LIFT, "lower_a")

    /** Denis's program with [plan] as its week: the upper days do not work the legs. */
    private fun program(plan: Map<Int, List<PlannedSession>>): Program = ProgramGenerator.generate(
        Profile(
            goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
            bodyWeightKg = 71.0, aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
        ),
        LocalDate.of(2026, 11, 23),
    ).let { p ->
        p.copy(
            week = WeekPlan(plan.map { (d, s) -> DayPlan(d, s) }, p.week.notes),
            templates = listOf(
                Template("upper_a", "Верх · А", Focus.UPPER, emptyList()), Template("upper_b", "Верх · Б", Focus.UPPER, emptyList()),
                Template("lower_a", "Низ", Focus.LOWER, emptyList()),
            ),
        )
    }

    private fun week(plan: Map<Int, List<PlannedSession>>, done: Map<Int, List<PlannedSession>>) =
        (1..7).map { CatchUp.Day(it, plan[it].orEmpty(), done[it].orEmpty()) }

    /**
     * catchup-suggest-cuts-week: Tuesday's «Верх · Б» missed, Saturday's «Низ» done on Wednesday (he cannot make Saturday),
     * Thursday as planned. The week shows Tuesday missed; on Friday the catch-up offers it — Wednesday's «Низ» is
     * Saturday's, not Tuesday's made up.
     */
    @Test fun theCatchUpOffersWhatTheWeekShowsMissed() {
        val plan = mapOf(1 to listOf(quality), 2 to listOf(upperB), 4 to listOf(upperA, quality), 6 to listOf(lower, easy))
        val w = week(plan, mapOf(1 to listOf(quality), 3 to listOf(lower), 4 to listOf(upperA, quality)))
        assertTrue(CatchUp.closes(w, today = 5).none { it.day == 2 }, "the week shows Tuesday missed")
        assertEquals(CatchUp.Suggestion(upperB, 2), CatchUp.suggest(program(plan), 5, w))
    }

    /**
     * catchup-books-earliest-missed: Monday's and Thursday's hard runs missed, legs on Saturday. On Friday the card offers
     * Thursday's as an easy run («засчитается за четверг»); run so, it is Thursday that turns green, not Monday.
     */
    @Test fun theEasyRunCountsForTheDayTheCardNames() {
        val plan = mapOf(1 to listOf(quality), 2 to listOf(upperB), 4 to listOf(quality, upperA), 6 to listOf(lower, easy))
        val done = mapOf(2 to listOf(upperB), 4 to listOf(upperA))
        val s = CatchUp.suggest(program(plan), 5, week(plan, done))
        assertEquals(CatchUp.Suggestion(quality, 4, easy = true), s)
        val ran = week(plan, done + (5 to listOf(easy))).map { if (it.day == 5) it.copy(doneInstead = listOf(true)) else it }
        val c = CatchUp.closes(ran, today = 5)
        assertTrue(CatchUp.Close(4, 0, 5, 0) in c, "$c")
        assertTrue(c.none { it.day == 1 }, "Monday's stays missed: $c")
    }

    /** catchup-books-earliest-missed: the same when the hard run fits and is done in full — the one offered is the one closed. */
    @Test fun aHardRunMadeUpInFullCountsForTheDayTheCardNames() {
        val plan = mapOf(1 to listOf(quality), 2 to listOf(upperB), 4 to listOf(quality, upperA), 6 to listOf(easy))
        val done = mapOf(2 to listOf(upperB), 4 to listOf(upperA))
        val s = CatchUp.suggest(program(plan), 5, week(plan, done))
        assertEquals(CatchUp.Suggestion(quality, 4), s)
        val c = CatchUp.closes(week(plan, done + (5 to listOf(quality))), today = 5)
        assertTrue(CatchUp.Close(4, 0, 5, 0) in c, "$c")
        assertTrue(c.none { it.day == 1 }, "Monday's stays missed: $c")
    }
}
