package forma.core

import forma.core.load.LoadBase
import forma.core.load.LoadExplain
import forma.core.load.LoadStory
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Research spike-label, Denis's decision of 25 November («резкий скачок» без пугалок). His weeks from the cloud: 2118,
 * 1813, then his lighter week by plan (257) and a week of two sessions of six (769) — a usual week of 1291, so his next
 * full week by plan (≈1900) read 147 % and «резкий скачок … Так чаще всего и перегружаются», and so would the one after.
 * Now the plan's week is the floor of the usual whenever the weeks before fell short of it, lighter weeks by plan stay out
 * of the usual, a week no heavier than the plan's full week is never a jump, and one far above both is said as what
 * happened: by how much, with «следующую неделю лучше не наращивать» as plain advice.
 */
class ResearchLoadWordsTest {
    private fun d(s: String) = LocalDate.parse(s)

    // His week by plan: a hard run Monday, gym Tuesday, gym and a hard run Thursday, gym and an easy run Saturday: 1900.
    private val week = listOf(300.0, 560.0, 0.0, 600.0, 0.0, 440.0, 0.0)
    private val plan = LoadBase(planned = week)

    private val before = mapOf(
        // 26.10–1.11: 2118.
        d("2026-10-26") to 250.0, d("2026-10-27") to 644.0, d("2026-10-29") to 599.0, d("2026-10-31") to 625.0,
        // 2–8.11: 1813.
        d("2026-11-02") to 300.0, d("2026-11-03") to 560.0, d("2026-11-05") to 503.0, d("2026-11-07") to 450.0,
        // 9–15.11, his lighter week by plan: 257.
        d("2026-11-10") to 257.0,
        // 16–22.11, two sessions of six: 769.
        d("2026-11-16") to 300.0, d("2026-11-19") to 469.0,
    )
    private val lighterWeek = (0 until 7L).map { d("2026-11-09").plusDays(it) }.toSet()
    private val monday = d("2026-11-23")

    /** The week of 23 November as planned, day by day, and [extra] on top. */
    private fun asPlanned(vararg extra: Pair<LocalDate, Double>) =
        before + (0 until 7).filter { week[it] > 0 }.associate { monday.plusDays(it.toLong()) to week[it] } + extra

    /** The story on [day], with nothing after it logged yet. */
    private fun on(loads: Map<LocalDate, Double>, day: LocalDate, base: LoadBase) =
        LoadStory.of(loads.filterKeys { !it.isAfter(day) }, day, base = base)

    private fun weekOf(st: LoadStory.Story) = st.weeks.first { it.monday == monday }

    @Test fun theOwnersNumbersReadAsAJumpWithoutAPlan() {
        // The research's arithmetic, kept: the median of his four weeks is 1291, and his full week reads ~147 % against it.
        val st = on(asPlanned(), monday.plusWeeks(1), LoadBase())
        val w = weekOf(st)
        assertEquals(1.47, w.ratio!!, 0.01)
        assertEquals(LoadStory.Level.SPIKE, w.level)
    }

    @Test fun aWeekAsPlannedAfterTwoThinWeeksIsNoJump() {
        // Before: the plan was the floor only with fewer than four weeks to go by, so his four (two of them thin) gave 1291.
        val loads = asPlanned()
        val done = weekOf(on(loads, monday.plusWeeks(1), plan))
        assertEquals(LoadStory.Level.USUAL, done.level, "ratio ${done.ratio}")
        assertEquals(1.0, done.ratio!!, 0.01)
        // Each day of it too: the week under way against the same days (the 14 October rule), the plan's days the floor.
        for (i in 0..6L) {
            val st = on(loads, monday.plusDays(i), plan)
            val cur = st.weeks.last()
            assertEquals(LoadStory.Level.USUAL, cur.level, "day $i: ratio ${cur.ratio}")
            assertFalse(st.warn, "day $i")
            assertNull(LoadExplain.alert(st), "day $i")
        }
        // The usual week the screen draws its line at is the plan's, and says so.
        val st = on(loads, monday.plusDays(6), plan)
        assertEquals(1900.0, st.usualWeek!!, 1.0)
        assertTrue(st.usualFromPlan)
        assertTrue("столько просит твой план" in LoadExplain.usual(st)!!, LoadExplain.usual(st))
    }

    @Test fun aFullWeekDoneAheadOfItsDaysIsNoJump() {
        // Saturday's gym done on Wednesday: more than usual by Wednesday, but never more than the plan's whole week.
        val loads = before + mapOf(
            monday to 300.0, monday.plusDays(1) to 560.0, monday.plusDays(2) to 440.0, monday.plusDays(3) to 600.0,
        )
        for (i in 2..6L) {
            val st = on(loads, monday.plusDays(i), plan)
            val cur = st.weeks.last()
            assertNotEquals(LoadStory.Level.SPIKE, cur.level, "day $i: ratio ${cur.ratio}")
            assertNull(LoadExplain.alert(st), "day $i")
        }
        assertEquals(LoadStory.Level.USUAL, weekOf(on(loads, monday.plusWeeks(1), plan)).level)
    }

    @Test fun aLighterWeekByPlanDoesNotPullTheUsualDown() {
        // No plan to lean on, so only leaving the lighter week out keeps the usual where his weeks are.
        val lighter = LoadBase(lighter = lighterWeek)
        val loads = asPlanned()
        val sunday = on(loads, monday.plusDays(6), lighter)
        // 2118, 1813 and 769: the lighter week's 257 is not among them (with it the usual was 1291).
        assertEquals(1813.0, sunday.usualWeek!!, 1.0)
        val done = weekOf(on(loads, monday.plusWeeks(1), lighter))
        assertEquals(LoadStory.Level.USUAL, done.level, "ratio ${done.ratio}")
        assertEquals(1900.0 / 1813.0, done.ratio!!, 0.01)
        // The lighter week itself still reads lighter than usual: it stays a lighter week.
        assertEquals(LoadStory.Level.LIGHT, on(loads, monday.plusWeeks(1), lighter).weeks.first { it.monday == d("2026-11-09") }.level)
        // Freshness is the same with or without it: the lighter week's sessions count for the fitness and the fatigue as any.
        val without = on(loads, monday.plusDays(6), LoadBase())
        assertEquals(without.today.formPct, sunday.today.formPct, 1e-9)
        assertEquals(without.today.fitness, sunday.today.fitness, 1e-9)
    }

    @Test fun aWeekAboveBothTheUsualAndThePlanIsAJumpSaidByHowMuch() {
        // An extra 800 on Wednesday: 2700 against a usual of 1900 (the plan's).
        val loads = asPlanned(monday.plusDays(2) to 800.0)
        val st = on(loads, monday.plusWeeks(1), plan)
        val w = weekOf(st)
        assertEquals(LoadStory.Level.SPIKE, w.level, "ratio ${w.ratio}")
        val by = ((w.ratio!! - 1) * 100).roundToInt()
        assertEquals(42, by)
        val note = LoadExplain.levelNote(w)!!
        assertTrue(note.startsWith("На $by\u00A0% больше обычной недели"), note)
        assertTrue("следующую неделю лучше не наращивать" in note, note)
        val words = listOf(note, LoadExplain.level(w), LoadExplain.usual(st)!!, LoadExplain.week(w))
        words.forEach { t -> listOf("перегруж", "резк", "Так чаще", "скачок").forEach { assertFalse(it in t, t) } }
    }

    @Test fun theAlertIsOnlyForAWeekAboveBothAndInTheWeeksWords() {
        val loads = asPlanned(monday.plusDays(2) to 800.0)
        // Wednesday evening: 1660 is far more than usual by Wednesday, but below the plan's full week: no jump, no alert.
        val wed = on(loads, monday.plusDays(2), plan)
        assertNotEquals(LoadStory.Level.SPIKE, wed.weeks.last().level, "ratio ${wed.weeks.last().ratio}")
        assertFalse(wed.warn)
        assertNull(LoadExplain.alert(wed))
        // Thursday evening: 2260, above the usual week and the plan's full week already.
        val thu = on(loads, monday.plusDays(3), plan)
        val cur = thu.weeks.last()
        assertEquals(LoadStory.Level.SPIKE, cur.level, "ratio ${cur.ratio}")
        assertTrue(thu.warn)
        val alert = assertNotNull(LoadExplain.alert(thu))
        val by = ((cur.ratio!! - 1) * 100).roundToInt()
        // The same number and the same advice as the week's own line.
        val note = LoadExplain.levelNote(cur)!!
        assertTrue("$by\u00A0% больше обычной" in alert, alert)
        assertTrue("$by\u00A0% больше обычной" in note, note)
        assertTrue("к этому дню" in note, note)
        assertTrue("следующую неделю лучше не наращивать" in alert, alert)
        listOf("перегруж", "резк", "Так чаще").forEach { assertFalse(it in alert, alert) }
    }

    @Test fun aWeekAboveTheUsualWithoutAPlanIsStillAJump() {
        // Nothing to lean on: far above his own weeks is said so, and warned of once above the whole usual week.
        val steady = (0 until 8).flatMap { w -> listOf(0L, 1L, 3L, 5L).map { d("2026-09-28").plusWeeks(w.toLong()).plusDays(it) to 500.0 } }.toMap()
        val heavy = steady + (d("2026-11-23") to 900.0) + (d("2026-11-24") to 900.0) + (d("2026-11-26") to 900.0)
        val st = LoadStory.of(heavy, d("2026-11-26"))
        assertEquals(LoadStory.Level.SPIKE, st.weeks.last().level)
        assertTrue(st.warn)
        assertNotNull(LoadExplain.alert(st))
    }
}
