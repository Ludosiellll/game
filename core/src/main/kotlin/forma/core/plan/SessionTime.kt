package forma.core.plan

import forma.core.strength.SlotSpec

/**
 * How long a gym session takes this person. The plan's own estimate is work and rest per set plus a warm-up; how
 * long the person's sessions really take against that estimate (they rest longer, chat, wait for a bench) is learned
 * from their finished sessions.
 */
object SessionTime {
    /** At least this many sessions before trusting the person's pace. */
    const val MIN_SESSIONS = 3

    /** The plan's estimate for [sets] of each slot, minutes. */
    fun estimate(slots: List<Pair<SlotSpec, Int>>): Double = 10 + slots.sumOf { (s, sets) -> sets * (s.restSec + 45) } / 60.0

    /**
     * Real over estimated, the median of the last 8 sessions ([samples]: estimate to real minutes), between 0.6 and
     * 2; 1 until [MIN_SESSIONS]. Sessions under 10 minutes (a mistap, a session closed later) do not count.
     */
    fun ratio(samples: List<Pair<Double, Double>>): Double {
        val r = samples.filter { (e, a) -> e > 0 && a >= 10 && a <= 300 }.takeLast(8).map { (e, a) -> a / e }.sorted()
        if (r.size < MIN_SESSIONS) return 1.0
        val m = if (r.size % 2 == 1) r[r.size / 2] else (r[r.size / 2 - 1] + r[r.size / 2]) / 2
        return m.coerceIn(0.6, 2.0)
    }

    /** Minutes rounded to 5, as said. */
    fun round(min: Double): Int = (Math.round(min / 5.0) * 5).toInt().coerceAtLeast(5)
}
