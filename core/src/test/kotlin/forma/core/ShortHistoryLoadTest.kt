package forma.core

import forma.core.load.LoadBase
import forma.core.load.LoadStory
import forma.core.load.TrainingLoad
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Denis, 1 November: «приложение считает что мой объем увеличился в 2 раза». His first two weeks in the app were short
 * (started 12.10, ill from 15.10, «Болею» not pressed): 1210 and 776. His first full week by plan, 2118, read as 213 % of
 * the usual and a jump, freshness −37 % («нужен отдых»). With few weeks the usual is not below what the plan asks, the
 * starting fitness is the mean of the first six weeks (not two), and days ill count for neither.
 */
class ShortHistoryLoadTest {
    private fun d(s: String) = LocalDate.parse(s)

    // His sessions (load = rating × minutes), as in the cloud.
    private val loads = mapOf(
        d("2026-10-12") to 182.0, d("2026-10-13") to 560.0, d("2026-10-15") to 468.0,
        d("2026-10-22") to 473.0, d("2026-10-23") to 303.0,
        d("2026-10-26") to 250.0, d("2026-10-27") to 644.0, d("2026-10-29") to 599.0, d("2026-10-31") to 625.0,
    )
    private val sunday = d("2026-11-01")

    // His week by plan: a hard run Monday, gym Tuesday, gym and a hard run Thursday, gym and an easy run Saturday.
    private val plan = LoadBase(planned = listOf(250.0, 600.0, 0.0, 645.0, 0.0, 470.0, 0.0))

    @Test fun withoutAPlanHisFullWeekStillReadsAsAJump() {
        // Two short weeks are all there is to compare with: the plan is what sets the usual right.
        assertEquals(LoadStory.Level.SPIKE, LoadStory.of(loads, sunday).weeks.last().level)
    }

    @Test fun theStartIsHisFirstWeeksNotTwo() {
        // Two weeks as the start read −37 % on Sunday («нужен отдых»); his first weeks together: loaded, not run down.
        val st = LoadStory.of(loads, sunday)
        assertTrue(st.today.formPct > LoadStory.COMMON_REST && st.today.formPct < -10, "freshness ${st.today.formPct}")
    }

    @Test fun withThePlanHisFullWeekIsUsual() {
        val st = LoadStory.of(loads, sunday, base = plan)
        val week = st.weeks.last()
        assertEquals(LoadStory.Level.USUAL, week.level, "ratio ${week.ratio}")
        // Loaded, not run down: a hard week, his usual one by plan.
        assertEquals(LoadStory.Zone.BUILDING, st.zone, "freshness ${st.today.formPct}")
        assertTrue(st.usualFromPlan)
        assertEquals(1965.0, st.usualWeek!!, 1.0)
        // The week ill reads as lighter than usual, not as the usual.
        assertEquals(LoadStory.Level.LIGHT, st.weeks.first { it.monday == d("2026-10-19") }.level)
    }

    @Test fun moreThanThePlanStillShows() {
        // A week far above the plan is a jump with the plan too.
        val heavy = loads + (d("2026-10-28") to 900.0) + (d("2026-10-30") to 900.0)
        assertEquals(LoadStory.Level.SPIKE, LoadStory.of(heavy, sunday, base = plan).weeks.last().level)
    }

    @Test fun weeksIllAreNotTheUsual() {
        // Four weeks of 2000, one of them ill (500): the usual stays 2000 without a plan to lean on.
        val m = d("2026-09-28")
        val week = listOf(0L, 1L, 3L, 5L)
        val l = (0..4).flatMap { w -> week.map { m.plusWeeks(w.toLong()).plusDays(it) to (if (w == 3) 125.0 else 500.0) } }.toMap()
        val ill = (0 until 7L).map { m.plusWeeks(3).plusDays(it) }.toSet()
        val st = LoadStory.of(l, m.plusWeeks(4).plusDays(6), base = LoadBase(off = ill))
        assertEquals(LoadStory.Level.USUAL, st.weeks.last().level)
        assertEquals(2000.0, st.usualWeek!!, 1.0)
    }

    @Test fun sickDaysDoNotLowerTheStart() {
        val first = d("2026-10-12")
        val l = mapOf(first to 700.0, first.plusDays(2) to 700.0)
        val off = (3 until 14L).map { first.plusDays(it) }.toSet()
        val withOff = TrainingLoad.series(l, first, first.plusDays(13), LoadBase(off = off)).first()
        val without = TrainingLoad.series(l, first, first.plusDays(13)).first()
        assertTrue(withOff.fitness > without.fitness * 2, "${withOff.fitness} vs ${without.fitness}")
    }
}
