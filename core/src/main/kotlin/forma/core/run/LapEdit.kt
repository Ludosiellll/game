package forma.core.run

/**
 * Laps put right after the run, outside the reps (a free, easy or long run, a warm-up): a press forgotten on the line
 * left one lap of two laps' time ("2:21") — [split] makes it two; a double press left two halves — [merge] makes them
 * one; a lap timed wrong gets its time by hand — [setTime]. Laps of reps stay as they were: the plan's next step was
 * judged on them.
 */
object LapEdit {
    /** Can lap [index] be put right (it is outside the reps)? */
    fun editable(laps: List<Lap>, index: Int): Boolean = laps.getOrNull(index)?.rep == null

    /** Lap [index] was [parts] laps (a press forgotten): as many laps of equal time, marked as roughly timed. */
    fun split(laps: List<Lap>, index: Int, parts: Int = 2): List<Lap> {
        val l = laps.getOrNull(index)?.takeIf { it.rep == null && parts >= 2 } ?: return laps
        val each = l.seconds / parts
        return renumber(laps.take(index) + List(parts) { l.copy(seconds = each, auto = true, exact = false) } + laps.drop(index + 1))
    }

    /** Laps [index] and [index] + 1 were one (a press too many): one lap of both times. */
    fun merge(laps: List<Lap>, index: Int): List<Lap> {
        val a = laps.getOrNull(index)?.takeIf { it.rep == null } ?: return laps
        val b = laps.getOrNull(index + 1)?.takeIf { it.rep == null } ?: return laps
        return renumber(laps.take(index) + a.copy(seconds = a.seconds + b.seconds, auto = a.auto || b.auto, exact = !(a.rough || b.rough) && (a.exact || b.exact)) + laps.drop(index + 2))
    }

    /**
     * Lap [index] took [seconds] as the runner timed it (a press late or early, a watch): that time, exact. The other laps
     * stay as they were. A time out of reason (under 5 s or over an hour) changes nothing.
     */
    fun setTime(laps: List<Lap>, index: Int, seconds: Double): List<Lap> {
        val l = laps.getOrNull(index)?.takeIf { it.rep == null } ?: return laps
        if (seconds.isNaN() || seconds < 5 || seconds > 3600) return laps
        return laps.toMutableList().also { it[index] = l.copy(seconds = seconds, auto = false, exact = false) }
    }

    /** Laps outside the reps numbered again in order (each run of them from 1). */
    private fun renumber(laps: List<Lap>): List<Lap> {
        var n = 0
        return laps.map { l ->
            if (l.rep != null) {
                n = 0
                l
            } else {
                n++
                l.copy(number = n)
            }
        }
    }
}
