package forma.core

import forma.core.analytics.BodyTrend
import forma.core.analytics.Point
import forma.core.analytics.Stats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 23 November, the progress screen as Denis reads it: «СИЛА +5 %» over nine rows of «▼» (the rows set the last session,
 * a light one, against the first; the index takes the better of the last two), and body weight as «74,45 кг, +1,45 кг»
 * from two noisy weigh-ins. One rule for the index and the rows, a window of eight weeks, weight as a trend.
 */
class ProgressRulesTest {
    @Test fun aLightLastSessionDoesNotTurnARowDown() {
        // 100 → 105 kg over the weeks, the last session a light one (estimate 96).
        val squat = listOf(Point(10, 100.0), Point(17, 102.0), Point(24, 105.0), Point(31, 96.0))
        val (now, change) = assertNotNull(Stats.liftChange(squat, fromDay = 9, today = 32))
        assertEquals(105.0, now)
        assertEquals(5.0, change)
        // Not done for more than four weeks: nothing to say about it now.
        assertNull(Stats.liftChange(squat, fromDay = 9, today = 70))
        // Started inside the window: its first session is the start.
        assertEquals(5.0, Stats.liftChange(squat, fromDay = 0, today = 32)!!.second)
    }

    @Test fun theIndexIsCountedFromTheWindowStart() {
        val index = listOf(Point(6, 100.0), Point(13, 104.0), Point(20, 106.0), Point(27, 109.2))
        val since = Stats.indexSince(index, fromDay = 14)
        assertEquals(listOf(13L, 20L, 27L), since.map { it.epochDay })
        assertEquals(0.0, since.first().value, 1e-9)
        assertEquals(5.0, since.last().value, 1e-6)
        // A history shorter than the window starts at its first week.
        assertEquals(9.2, Stats.indexSince(index, fromDay = 0).last().value, 1e-6)
    }

    @Test fun weightIsATrendAndARateAWeek() {
        // Up 0.2 kg a week underneath, ±0.4 kg of water on top, weighed twice a week for six weeks.
        val weighIns = (0 until 12).map { i ->
            val day = 100L + i * 3 + (i / 2)
            Point(day, 73.0 + 0.2 * (day - 100) / 7 + if (i % 2 == 0) 0.4 else -0.4)
        }
        val trend = BodyTrend.trend(weighIns)
        val swingRaw = weighIns.zipWithNext { a, b -> kotlin.math.abs(b.value - a.value) }.max()
        val swingTrend = trend.zipWithNext { a, b -> kotlin.math.abs(b.value - a.value) }.max()
        assertTrue(swingTrend < swingRaw / 2, "trend $swingTrend vs raw $swingRaw")
        val rate = assertNotNull(BodyTrend.perWeek(weighIns, today = weighIns.last().epochDay))
        assertEquals(0.2, rate, 0.12)
        // Two weigh-ins say nothing about a rate.
        assertNull(BodyTrend.perWeek(weighIns.takeLast(2), today = weighIns.last().epochDay))
    }
}
