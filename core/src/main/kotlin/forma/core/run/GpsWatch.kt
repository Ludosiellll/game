package forma.core.run

/**
 * Whether GPS is there on the road, for the voice: lost — no usable fix for [LOST_MS] while running; back — usable fixes
 * again for [BACK_MS]. Said once each way: a signal that comes and goes under trees is not announced every few seconds
 * (after "back" the next "lost" waits [QUIET_MS]; a "lost" not said has no "back" either). Nothing before the first
 * fix (the satellites are still being found) and nothing while the run stands.
 */
class GpsWatch {
    enum class Say { LOST, BACK }

    private var lastGood: Long? = null
    private var goodSince: Long? = null
    private var lost = false
    private var saidLost = false
    private var backAt: Long? = null

    /** A fix at [nowMs]; [usable]: accurate enough to count the way. */
    fun fix(nowMs: Long, usable: Boolean) {
        if (!usable) {
            goodSince = null
            return
        }
        // A streak is fixes a second or so apart: after a silence it starts over.
        val prev = lastGood
        if (goodSince == null || prev == null || nowMs - prev > 3_000) goodSince = nowMs
        lastGood = nowMs
    }

    /** Called every moment of the run; [running]: the clock goes (not paused). */
    fun tick(nowMs: Long, running: Boolean): Say? {
        val good = lastGood ?: return null
        if (!running) {
            // A stop is no loss: the time standing does not count toward one.
            if (!lost) lastGood = nowMs
            return null
        }
        if (!lost) {
            if (nowMs - good < LOST_MS) return null
            lost = true
            val quiet = backAt?.let { nowMs - it < QUIET_MS } == true
            saidLost = !quiet
            return if (saidLost) Say.LOST else null
        }
        val since = goodSince ?: return null
        if (nowMs - good > 2_000 || nowMs - since < BACK_MS) return null
        lost = false
        if (!saidLost) return null
        saidLost = false
        backAt = nowMs
        return Say.BACK
    }

    companion object {
        const val LOST_MS = 15_000L
        const val BACK_MS = 5_000L
        const val QUIET_MS = 60_000L
    }
}
