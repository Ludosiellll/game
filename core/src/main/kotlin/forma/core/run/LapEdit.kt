package forma.core.run

/**
 * Laps put right after the run: a press forgotten on the line left one lap of two laps' time ("2:21") — [split] makes it
 * two; a double press left two halves — [merge] makes them one; a lap timed wrong gets its time by hand — [setTime]; a
 * lap that was none goes — [remove]. Outside the reps (a free, easy or long run, a warm-up) all of them; in a rep a lap
 * is split only while the rep has fewer laps than planned (a press missed: never more laps than the rep has), two of its
 * laps merged (a press doubled), any timed again — never one removed (its time would be lost). The last run is decided
 * again on the reps' laps put right.
 */
object LapEdit {
    /** Can lap [index] be put right (it is outside the reps)? */
    fun editable(laps: List<Lap>, index: Int): Boolean = laps.getOrNull(index)?.rep == null

    /**
     * Lap [index] was [parts] laps (a press forgotten): as many laps of equal time, marked as roughly timed. A lap of a rep
     * only while the rep, with them, has no more laps than planned, and only a whole lap (not a rep's first half lap).
     */
    fun split(laps: List<Lap>, index: Int, parts: Int = 2): List<Lap> {
        val l = laps.getOrNull(index)?.takeIf { parts >= 2 } ?: return laps
        if (l.rep != null) {
            val of = l.of ?: return laps
            if (l.part < 1.0 - 1e-9 || laps.count { it.rep == l.rep } + parts - 1 > of) return laps
        }
        val each = l.seconds / parts
        return renumber(laps.take(index) + List(parts) { l.copy(seconds = each, auto = true, exact = false) } + laps.drop(index + 1))
    }

    /** Laps [index] and [index] + 1 were one (a press too many): one lap of both times — both outside the reps or in one rep. */
    fun merge(laps: List<Lap>, index: Int): List<Lap> {
        val a = laps.getOrNull(index) ?: return laps
        val b = laps.getOrNull(index + 1)?.takeIf { it.rep == a.rep } ?: return laps
        return renumber(laps.take(index) + a.copy(seconds = a.seconds + b.seconds, auto = a.auto || b.auto, exact = !(a.rough || b.rough) && (a.exact || b.exact)) + laps.drop(index + 2))
    }

    /**
     * Lap [index] took [seconds] as the runner timed it (a press late or early, a watch): that time, exact. The other laps
     * stay as they were. A time out of reason (under 5 s or over an hour) changes nothing.
     */
    fun setTime(laps: List<Lap>, index: Int, seconds: Double): List<Lap> {
        val l = laps.getOrNull(index) ?: return laps
        if (seconds.isNaN() || seconds < 5 || seconds > 3600) return laps
        return laps.toMutableList().also { it[index] = l.copy(seconds = seconds, auto = false, exact = false) }
    }

    /**
     * Lap [index] was no lap (a touch, a key pressed by mistake in a jog): gone, the others as they were. Outside the reps
     * only.
     */
    fun remove(laps: List<Lap>, index: Int): List<Lap> {
        laps.getOrNull(index)?.takeIf { it.rep == null } ?: return laps
        return renumber(laps.take(index) + laps.drop(index + 1))
    }

    /** Laps numbered again in order: each run of them outside the reps from 1, each rep's own from 1. */
    private fun renumber(laps: List<Lap>): List<Lap> {
        var n = 0
        var rep: Int? = null
        return laps.map { l ->
            if (l.rep != rep) n = 0
            rep = l.rep
            n++
            l.copy(number = n)
        }
    }
}
