package forma.core

import forma.core.load.LoadExplain
import forma.core.load.LoadStory
import forma.core.load.TrainingLoad
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Review of a2dc0a7 (the coach says the freshness «Сегодня» shows). */
class FreshnessReviewTest {
    private val start = LocalDate.of(2026, 7, 6)

    /** Four sessions a week of [each], Mon/Tue/Thu/Sat, for [weeks] weeks. */
    private fun steady(weeks: Int, each: Double = 300.0, from: LocalDate = start) = buildMap {
        repeat(weeks) { w -> listOf(0, 1, 3, 5).forEach { d -> put(from.plusDays(w * 7L + d), each) } }
    }

    /** Eight steady weeks, then harder days every day; today is the first day whose own value is under the rest line. */
    private fun firstDayUnder(): Pair<Map<LocalDate, Double>, LocalDate> {
        val loads = steady(8).toMutableMap()
        val spike = start.plusWeeks(8)
        (0..13).forEach { loads[spike.plusDays(it.toLong())] = 480.0 }
        val series = TrainingLoad.series(loads, start, spike.plusDays(13))
        val i = series.indices.first { it > 0 && series[it].formPct < LoadStory.COMMON_REST && series[it - 1].formPct >= LoadStory.COMMON_REST }
        val today = series[i].date
        return loads.filterKeys { !it.isAfter(today) } to today
    }

    /**
     * A dip that starts today: «Сегодня» shows e.g. −32 % and «нужен отдых», the story's zone is REST — and the same
     * brief / «Прогресс» / report then say «За 8 недель ни разу не опускался ниже линии отдыха — нагрузка была по силам».
     * Two sentences of one section contradict each other; the coach is fed both.
     */
    @Test fun aDipThatStartsTodayIsNotDeniedNextToNeedsRest() {
        val (loads, today) = firstDayUnder()
        val s = LoadStory.of(loads, today)
        assertTrue(s.history)
        val shown = TrainingLoad.series(loads, today.minusDays(120), today).last().formPct
        assertEquals(shown, s.today.formPct, 1e-9)
        assertEquals(LoadStory.Zone.REST, s.zone, "today ${Ru(shown)} is under the line")
        val zone = LoadExplain.zoneText(s)
        val dips = if (s.dips.isEmpty()) LoadExplain.noDips(8) else s.dips.joinToString { LoadExplain.dip(it, s.today.formPct) }
        assertTrue("ниже твоей линии отдыха" in zone, zone)
        assertTrue(
            "ни разу не опускался ниже линии отдыха" !in dips,
            "«Сегодня»: ${Ru(shown)}, zone: «$zone» — and the dips say: «$dips»",
        )
    }

    /** Yesterday's end under the line, today's above: the dip closes today (the commit's fix) and reads as past. */
    @Test fun anOpenDipClosesWhenTodayIsAboveTheLine() {
        val loads = steady(8).toMutableMap()
        val spike = start.plusWeeks(8)
        (0..5).forEach { loads[spike.plusDays(it.toLong())] = 700.0 }
        // Find the first day after the spike whose own value is back above the line while the morning was below.
        val series = TrainingLoad.series(loads, start, spike.plusDays(20))
        val i = series.indices.first { it > 0 && series[it].formPct >= LoadStory.COMMON_REST && series[it - 1].formPct < LoadStory.COMMON_REST && series[it].date > spike }
        val today = series[i].date
        val s = LoadStory.of(loads.filterKeys { !it.isAfter(today) }, today)
        val d = s.dips.lastOrNull() ?: fail("no dip")
        assertNotNull(d.recovered)
        assertEquals(today, d.recovered)
        assertTrue(s.zone != LoadStory.Zone.REST)
        val text = LoadExplain.dip(d, s.today.formPct)
        assertTrue("нужен был отдых" in text && "сейчас" !in text, text)
    }

    /**
     * A dip still going on and getting deeper: the dip's «самая низкая» is a morning value (yesterday's end), while
     * «сейчас» is today's end — lower. The sentence then reads «самая низкая — −40 %, сейчас −45 %»: the lowest is not
     * the lowest. The week line says a third number («самая низкая свежесть», by end-of-day values).
     */
    @Test fun theLowestOfAnOpenDipIsNotAboveNow() {
        // Eight usual weeks (4 × 300), then two weeks twice as hard on the same days (4 × 600: a spike, as the app calls it).
        val loads = steady(8).toMutableMap()
        val spike = start.plusWeeks(8)
        steady(2, 600.0, spike).forEach { (d, l) -> loads[d] = l }
        // The first day of a dip still going on whose own value is under the dip's «lowest» (a training day after one).
        val found = (0..20).map { spike.plusDays(it.toLong()) }.firstNotNullOfOrNull { today ->
            val s = LoadStory.of(loads.filterKeys { !it.isAfter(today) }, today)
            val d = s.dips.lastOrNull()?.takeIf { it.recovered == null } ?: return@firstNotNullOfOrNull null
            if (s.today.formPct < d.lowestPct - 0.5) Triple(today, s, d) else null
        }
        // No such day: the claim is dropped.
        val (today, s, d) = found ?: return
        val text = LoadExplain.dip(d, s.today.formPct)
        val week = LoadExplain.week(s.weeks.last())
        assertTrue(d.lowestPct <= s.today.formPct + 1e-9, "$today — dip: «$text»; this week: «$week»")
    }

    /** A day exactly on the line: the zone says not REST and no dip stays open. */
    @Test fun aDayOnTheLineIsConsistent() {
        assertEquals(LoadStory.Zone.BUILDING, LoadStory.zone(LoadStory.COMMON_REST, LoadStory.COMMON_REST))
    }

    private fun Ru(x: Double) = forma.core.text.Ru.pct(x)
}
