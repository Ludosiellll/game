package forma.core.run

/**
 * Whether GPS is there on the road, for the voice: lost — no usable fix for [LOST_MS] while running; back — usable fixes
 * again for [BACK_MS]. Said once each way: a signal that comes and goes under trees is not announced every few seconds
 * (after "back" the next "lost" waits [QUIET_MS]; a "lost" not said has no "back" either). Nothing before the first
 * fix (the satellites are still being found) and nothing while the run stands — unless a first fix is awaited ([await]).
 */
class GpsWatch {
    enum class Say { LOST, BACK, NONE }

    private var lastGood: Long? = null
    private var goodSince: Long? = null
    private var lost = false
    private var saidLost = false
    private var backAt: Long? = null

    /** Awaiting a first fix: the last tick, and the running time waited so far. */
    private var awaitAt: Long? = null
    private var waited = 0L

    /**
     * A first fix is awaited from [nowMs]: a run the system brought back may get no GPS at all (Android may not give it
     * to an app started in the background). None in [NONE_MS] of running is said once ([Say.NONE]); its coming — "back".
     */
    fun await(nowMs: Long) {
        if (lastGood != null) return
        awaitAt = nowMs
        waited = 0
    }

    /** A fix at [nowMs]; [usable]: accurate enough to count the way. */
    fun fix(nowMs: Long, usable: Boolean) {
        // Any fix at all: GPS answers, weak or not.
        awaitAt = null
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
        val good = lastGood ?: return none(nowMs, running)
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

    /** No fix yet: said once when one is awaited and [NONE_MS] of running passed without it. */
    private fun none(nowMs: Long, running: Boolean): Say? {
        val at = awaitAt ?: return null
        if (running) waited += nowMs - at
        awaitAt = nowMs
        if (waited < NONE_MS) return null
        awaitAt = null
        lost = true
        saidLost = true
        return Say.NONE
    }

    companion object {
        const val LOST_MS = 15_000L
        const val BACK_MS = 5_000L
        const val QUIET_MS = 60_000L
        const val NONE_MS = 60_000L
    }
}
