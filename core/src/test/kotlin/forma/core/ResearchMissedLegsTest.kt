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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Research one-leg-day+D1 (c), approved by Denis on 25 November: a missed «Низ» comes back. His week: a hard run on
 * Monday and Thursday, «Верх · Б» on Tuesday, «Верх · А» on Thursday, «Низ» and an easy run on Saturday. A Saturday
 * «Низ» missed was never offered on Sunday (Monday's hard run), and his legs went from 7.11 to 28.11 without a full
 * session. Now, after 7 days or more without legs, the missed «Низ» comes first; on a free day before a hard run it is
 * offered with that run made easy for the day, or moved to the day after it when that day keeps 48 h from the next hard
 * run and holds no legs. Otherwise «legs, then a hard run within about 48 h» stays blocked.
 */
class ResearchMissedLegsTest {
    private val quality = PlannedSession(SessionKind.RUN_QUALITY)
    private val easy = PlannedSession(SessionKind.RUN_EASY)
    private val upperB = PlannedSession(SessionKind.LIFT, "upper_b")
    private val upperA = PlannedSession(SessionKind.LIFT, "upper_a")
    private val lower = PlannedSession(SessionKind.LIFT, "lower_a")
    private val his = mapOf(1 to listOf(quality), 2 to listOf(upperB), 4 to listOf(quality, upperA), 6 to listOf(lower, easy))

    private fun program(plan: Map<Int, List<PlannedSession>>): Program = ProgramGenerator.generate(
        Profile(
            goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
            bodyWeightKg = 71.0, aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
        ),
        LocalDate.of(2026, 11, 16),
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

    /** His week of 16–21 November: everything done but Saturday. */
    private val hisDone = mapOf(1 to listOf(quality), 2 to listOf(upperB), 4 to listOf(quality, upperA))

    @Test fun theMissedLowerIsOfferedOnSundayWithBothChoices() {
        val s = assertNotNull(CatchUp.suggest(program(his), 7, week(his, hisDone), legsDaysAgo = 15))
        assertEquals(lower, s.session, "«Низ» first, before Saturday's easy run")
        assertEquals(6, s.plannedOn)
        assertTrue(s.beforeHard, "only with Monday's hard run eased or moved")
        // Tuesday: 48 h after the legs, no legs on it, and Wednesday free (48 h before Thursday's hard run).
        assertTrue(s.moveOk)
        assertFalse(s.easy)
        assertEquals(15, s.legsDaysAgo)
    }

    @Test fun underAWeekWithoutLegsSundayKeepsTheRule() {
        for (days in listOf(3, null)) {
            val s = CatchUp.suggest(program(his), 7, week(his, hisDone), legsDaysAgo = days)
            // Saturday's easy run, as before: no legs and no hard run the day before Monday's hard run.
            assertEquals(easy, s?.session, "legs $days days ago")
            assertFalse(s!!.beforeHard)
        }
    }

    @Test fun nothingOnSundayBreaksTheRuleWithLegsThreeDaysAgo() {
        // Saturday's easy run done too: what is left (the «Низ») does not fit before Monday's hard run.
        val done = hisDone + (6 to listOf(easy))
        assertEquals(null, CatchUp.suggest(program(his), 7, week(his, done), legsDaysAgo = 3))
    }

    @Test fun owedLegsComeFirstWhereTheyFit() {
        // «Низ» on Monday and «Верх · Б» on Tuesday both missed; Wednesday free, Thursday upper only: both fit.
        val plan = mapOf(1 to listOf(lower), 2 to listOf(upperB), 4 to listOf(upperA), 6 to listOf(quality))
        assertEquals(upperB, CatchUp.suggest(program(plan), 3, week(plan, emptyMap()), legsDaysAgo = 3)?.session, "as before: the later one")
        val s = assertNotNull(CatchUp.suggest(program(plan), 3, week(plan, emptyMap()), legsDaysAgo = 9))
        assertEquals(lower, s.session)
        assertFalse(s.beforeHard, "nothing hard tomorrow: offered as it is")
    }

    @Test fun noMoveOntoADayBeforeAnotherHardRun() {
        // A hard run on Wednesday too: Monday's moved to Tuesday would be two hard days in a row.
        val plan = his + (3 to listOf(quality))
        val s = assertNotNull(CatchUp.suggest(program(plan), 7, week(plan, hisDone + (3 to listOf(quality))), legsDaysAgo = 15))
        assertEquals(lower, s.session)
        assertTrue(s.beforeHard)
        assertFalse(s.moveOk, "only the easy Monday")
    }

    @Test fun noMoveOntoALegDay() {
        // Legs on Tuesday: the hard run cannot go there.
        val plan = his + (2 to listOf(lower))
        val s = assertNotNull(CatchUp.suggest(program(plan), 7, week(plan, hisDone - 2), legsDaysAgo = 15))
        assertTrue(s.beforeHard)
        assertFalse(s.moveOk)
    }

    @Test fun noMoveIntoTheNextWeek() {
        // «Низ» on Friday missed, a hard run on Sunday: the day after it is next Monday, whose week books apart.
        val plan = mapOf(2 to listOf(upperB), 5 to listOf(lower), 7 to listOf(quality))
        val s = assertNotNull(CatchUp.suggest(program(plan), 6, week(plan, mapOf(2 to listOf(upperB))), legsDaysAgo = 10))
        assertEquals(lower, s.session)
        assertTrue(s.beforeHard)
        assertFalse(s.moveOk)
    }

    @Test fun notTheDayAfterAHardRun() {
        // Thursday's hard run done, Friday free, Saturday a hard run: legs between two hard days stay blocked.
        val plan = mapOf(1 to listOf(lower), 4 to listOf(quality), 6 to listOf(quality))
        val s = CatchUp.suggest(program(plan), 5, week(plan, mapOf(4 to listOf(quality))), legsDaysAgo = 12)
        assertTrue(s == null || s.session != lower, "$s")
    }

    @Test fun anEasedOrMovedMondayIsSeen() {
        // Monday's hard run made easy for the day (Settings.easyRunDays): the legs fit on Sunday as they are.
        val eased = assertNotNull(
            CatchUp.suggest(program(his), 7, week(his, hisDone), legsDaysAgo = 15, next = listOf(listOf(easy), listOf(upperB), emptyList())),
        )
        assertEquals(lower, eased.session)
        assertFalse(eased.beforeHard)
        // Moved to Tuesday: Monday holds nothing hard.
        val moved = assertNotNull(
            CatchUp.suggest(program(his), 7, week(his, hisDone), legsDaysAgo = 15, next = listOf(emptyList(), listOf(quality, upperB), emptyList())),
        )
        assertEquals(lower, moved.session)
        assertFalse(moved.beforeHard)
    }

    @Test fun aMissedHardRunFitsBeforeAnEasedDay() {
        // Monday's hard run missed, Thursday's made easy: on Wednesday it fits as a hard run (it read the plan before).
        val w = week(his, mapOf(2 to listOf(upperB)))
        assertTrue(assertNotNull(CatchUp.suggest(program(his), 3, w)).easy)
        val s = assertNotNull(CatchUp.suggest(program(his), 3, w, next = listOf(listOf(easy, upperA), emptyList(), listOf(lower, easy))))
        assertEquals(quality, s.session)
        assertFalse(s.easy)
    }
}
