package forma.core.run

/**
 * Auto-pause for runs on the road (easy, long, free): stopped for [HOLD_MS] (a traffic light, a shoelace) — the clock
 * stops from the moment the stop began; running again for two fixes — it goes on. Judged on the satellites' speed, which
 * is near zero when standing, unlike positions that wander a few metres. A pause or resume by hand cancels the automatic
 * one ([reset]).
 */
class AutoPause {
    sealed interface Act {
        /** Pause the run from [atMs] (when the stop began). */
        data class Pause(val atMs: Long) : Act
        data object Resume : Act
    }

    private var slowSince: Long? = null
    private var moving = 0

    /** Paused by this, not by hand. */
    var paused = false
        private set

    /** A fix with [speed] at [nowMs]; [running]: the run is going (not paused by hand, not finished). */
    fun update(speed: Float?, nowMs: Long, running: Boolean): Act? {
        val v = speed ?: return null
        if (!paused) {
            if (!running) {
                slowSince = null
                return null
            }
            if (v < STOP) {
                val since = slowSince ?: nowMs.also { slowSince = it }
                if (nowMs - since >= HOLD_MS) {
                    paused = true
                    slowSince = null
                    moving = 0
                    return Act.Pause(since)
                }
            } else {
                slowSince = null
            }
            return null
        }
        if (v > GO) {
            if (++moving >= 2) {
                paused = false
                moving = 0
                return Act.Resume
            }
        } else {
            moving = 0
        }
        return null
    }

    fun reset() {
        paused = false
        slowSince = null
        moving = 0
    }

    companion object {
        const val STOP = 0.8f
        const val GO = 1.6f
        const val HOLD_MS = 6_000L
    }
}
