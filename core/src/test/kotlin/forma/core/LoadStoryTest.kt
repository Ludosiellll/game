package forma.core

import forma.core.load.LoadExplain
import forma.core.load.LoadStory
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoadStoryTest {
    private val start = LocalDate.of(2026, 7, 6)

    /** Four sessions a week of [each], Mon/Tue/Thu/Sat, for [weeks] weeks. */
    private fun steady(weeks: Int, each: Double = 300.0, from: LocalDate = start) = buildMap {
        repeat(weeks) { w -> listOf(0, 1, 3, 5).forEach { d -> put(from.plusDays(w * 7L + d), each) } }
    }

    @Test fun aSteadyWeekIsUsualAndBalanced() {
        val loads = steady(10)
        val today = start.plusDays(69)
        val s = LoadStory.of(loads, today)
        assertTrue(s.history)
        assertEquals(1200.0, s.usualWeek)
        assertTrue(s.weeks.dropLast(1).all { it.level == LoadStory.Level.USUAL }, s.weeks.toString())
        assertTrue(s.dips.isEmpty())
        assertTrue(LoadExplain.fitness(s).contains("в неделю"))
        assertFalse(s.personal.learned)
        assertEquals(LoadStory.COMMON_REST, s.personal.restBelow)
    }

    @Test fun aSpikeIsCalledOutAndTheDipAfterIt() {
        val loads = steady(8).toMutableMap()
        val spike = start.plusWeeks(8)
        (0..5).forEach { loads[spike.plusDays(it.toLong())] = 700.0 }
        val today = spike.plusDays(12)
        val s = LoadStory.of(loads, today)
        val week = s.weeks.first { it.monday == spike }
        assertEquals(LoadStory.Level.SPIKE, week.level)
        assertNotNull(LoadExplain.levelNote(week))
        val dip = s.dips.single()
        assertTrue(dip.lowestPct < LoadStory.COMMON_REST)
        // Days without training after it, and it ended.
        assertNotNull(dip.recovered)
        assertTrue(dip.restDays > 0)
        assertTrue(LoadExplain.dip(dip).contains("нужен был отдых"))
    }

    @Test fun stillDownIsSaidAsNow() {
        val loads = steady(8).toMutableMap()
        val spike = start.plusWeeks(8)
        (0..5).forEach { loads[spike.plusDays(it.toLong())] = 700.0 }
        val s = LoadStory.of(loads, spike.plusDays(5))
        assertEquals(LoadStory.Zone.REST, s.zone)
        assertNull(s.dips.last().recovered)
        assertTrue(LoadExplain.dip(s.dips.last()).startsWith("С "))
    }

    @Test fun longRestFadesAndSaysSo() {
        val loads = steady(8)
        val s = LoadStory.of(loads, start.plusWeeks(8).plusDays(12))
        assertEquals(LoadStory.Zone.DETRAINING, s.zone)
        assertTrue(LoadExplain.zoneText(s).contains("назад"))
        assertTrue(s.fitnessChange!! < -5)
    }

    @Test fun theRestLineIsLearnedFromOwnBadSessions() {
        // Hard and easy weeks in turn; the sessions that went badly were the ones in tired weeks.
        val loads = mutableMapOf<LocalDate, Double>()
        repeat(12) { w -> (if (w % 2 == 0) listOf(0, 2, 4) else listOf(0, 1, 2, 3, 4, 5)).forEach { d -> loads[start.plusDays(w * 7L + d)] = if (w % 2 == 0) 200.0 else 600.0 } }
        val today = start.plusDays(83)
        val days = loads.keys.filter { it.isAfter(start.plusDays(20)) }
        val tired = days.filter { ((it.toEpochDay() - start.toEpochDay()) / 7) % 2 == 1L && it.dayOfWeek.value >= 3 }.toSet()
        val fine = days.toSet() - tired
        val s = LoadStory.of(loads, today, bad = tired, good = fine)
        assertTrue(s.personal.learned, s.personal.toString())
        assertTrue(s.personal.restBelow in -45.0..-20.0, s.personal.toString())
        assertTrue(s.personal.badBelow * s.personal.judgedAbove >= 1.5 * s.personal.badAbove * s.personal.judgedBelow, s.personal.toString())
        assertTrue(LoadExplain.personal(s.personal).startsWith("Линия отдыха — твоя"))
        // Bad days with no link to freshness: the line stays common, and it says so.
        val random = days.filterIndexed { i, _ -> i % 3 == 0 }.toSet()
        val s2 = LoadStory.of(loads, today, bad = random, good = days.toSet() - random)
        if (!s2.personal.learned) assertTrue(LoadExplain.personal(s2.personal).contains("не связаны"))
    }

    @Test fun aNewUserHasNoFreshnessYet() {
        val loads = mapOf(start to 400.0, start.plusDays(1) to 300.0)
        val s = LoadStory.of(loads, start.plusDays(2))
        assertFalse(s.history)
        assertNull(s.zone)
        assertTrue(LoadExplain.freshness(s).contains("через две недели"))
        assertTrue(LoadExplain.personal(s.personal).contains("пока общая"))
    }
}
