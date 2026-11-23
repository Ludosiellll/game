package forma.core.analytics

import kotlin.math.pow

/**
 * Body weight read as a trend, not as the day's number: a weigh-in moves by half a kilo or more from water and food, so
 * the scale's last number says little (23 November: «74,45 кг, +1,45 кг» from two noisy weigh-ins).
 */
object BodyTrend {
    /** A tenth a day: a weigh-in counts a tenth into the trend, more after a gap (1 − 0.9 per day). */
    private const val DAILY = 0.1

    /** The trend at each weigh-in, oldest first: exponential smoothing by the days between them. */
    fun trend(weighIns: List<Point>): List<Point> {
        var t: Double? = null
        var last = 0L
        return weighIns.sortedBy { it.epochDay }.map { p ->
            val prev = t
            val now = if (prev == null) p.value else prev + (1 - (1 - DAILY).pow((p.epochDay - last).coerceAtLeast(1).toDouble())) * (p.value - prev)
            t = now
            last = p.epochDay
            Point(p.epochDay, now)
        }
    }

    /**
     * Kilos a week over the last four weeks to [today], a straight line through the weigh-ins in them; null with fewer than
     * three of them, or spanning less than two weeks.
     */
    fun perWeek(weighIns: List<Point>, today: Long): Double? {
        val recent = weighIns.filter { it.epochDay in (today - 27)..today }.sortedBy { it.epochDay }
        if (recent.size < 3 || recent.last().epochDay - recent.first().epochDay < 14) return null
        val mx = recent.map { it.epochDay.toDouble() }.average()
        val my = recent.map { it.value }.average()
        val sxx = recent.sumOf { (it.epochDay - mx).pow(2) }
        if (sxx <= 0) return null
        val sxy = recent.sumOf { (it.epochDay - mx) * (it.value - my) }
        return sxy / sxx * 7
    }
}
