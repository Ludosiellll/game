package forma.core.run

/**
 * Auto-pause for runs on the road (easy, long, free): stopped for [HOLD_MS] (a traffic light, a shoelace) — the clock
 * stops from the moment the stop began; moving again (walking on counts) for two fixes — it goes on. Judged on the
 * satellites' speed, which is near zero when standing, unlike positions that wander a few metres. A pause or resume by
 * hand cancels the automatic one ([reset]).
 *
 * The speed is trusted only from a good fix (≤ [MAX_ACCURACY] m): under trees or between tall buildings a fix 99 m off
 * reads "standing" while the runner runs. And the step counter has the last word: steps coming means moving, whatever
 * GPS says, so a pause never starts while they come, and a pause ends once they come again ([steps]) — with GPS lost too.
 */
class AutoPause {
    sealed interface Act {
        /** Pause the run from [atMs] (when the stop began). */
        data class Pause(val atMs: Long) : Act
        data object Resume : Act
    }

    private var slowSince: Long? = null
    private var moving = 0

    /** Step counter readings (time, total) of the last seconds, kept while paused too: they are what resumes it. */
    private val stepLog = ArrayDeque<Pair<Long, Long>>()

    /** When the step counter last went up. */
    private var lastStepAt: Long? = null

    /** Paused by this, not by hand. */
    var paused = false
        private set

    /**
     * A fix with [speed] at [nowMs] and its [accuracy], metres; [running]: the run is going (not paused by hand, not
     * finished).
     */
    fun update(speed: Float?, nowMs: Long, running: Boolean, accuracy: Float = 0f): Act? {
        val v = speed ?: return null
        val good = accuracy <= MAX_ACCURACY
        if (!paused) {
            if (!running || !good) {
                slowSince = null
                return null
            }
            if (v < STOP) {
                val since = slowSince ?: nowMs.also { slowSince = it }
                // Standing is standing with no steps either: the stop counts from the last of them.
                val from = maxOf(since, lastStepAt ?: since)
                if (nowMs - from >= HOLD_MS) return pause(from)
            } else {
                slowSince = null
            }
            return null
        }
        if (good && v > GO) {
            if (++moving >= 2) return resume()
        } else {
            moving = 0
        }
        return null
    }

    /**
     * The phone's step counter ([total] since it started counting) at [nowMs]. While paused by this, steps coming again
     * for a few seconds go on with the run — GPS speed or not.
     */
    fun steps(total: Long, nowMs: Long): Act? {
        val last = stepLog.lastOrNull()
        // The counter starts over after a reboot: the log too.
        if (last != null && total < last.second) stepLog.clear()
        if (last != null && total > last.second) lastStepAt = nowMs
        stepLog.addLast(nowMs to total)
        while (stepLog.size > 1 && nowMs - stepLog.first().first > STEP_WINDOW_MS) stepLog.removeFirst()
        return if (paused && stepping(nowMs)) resume() else null
    }

    /** Steps came in the last [STEP_WINDOW_MS]: at least [STEP_MIN] of them, over a few seconds (not a shuffle in place). */
    private fun stepping(nowMs: Long): Boolean {
        val recent = stepLog.filter { nowMs - it.first <= STEP_WINDOW_MS }
        if (recent.size < 2) return false
        val (t0, s0) = recent.first()
        val (t1, s1) = recent.last()
        return s1 - s0 >= STEP_MIN && t1 - t0 >= STEP_SPAN_MS
    }

    private fun pause(since: Long): Act {
        paused = true
        slowSince = null
        moving = 0
        stepLog.clear()
        return Act.Pause(since)
    }

    private fun resume(): Act {
        paused = false
        moving = 0
        slowSince = null
        return Act.Resume
    }

    fun reset() {
        paused = false
        slowSince = null
        moving = 0
    }

    companion object {
        const val STOP = 0.8f
        const val GO = 1.2f
        const val HOLD_MS = 6_000L

        /** A fix worse than this says nothing about standing or moving. */
        const val MAX_ACCURACY = 20f

        /** Steps that mean moving: this many within [STEP_WINDOW_MS], spread over [STEP_SPAN_MS] at least. */
        const val STEP_MIN = 8L
        const val STEP_WINDOW_MS = 8_000L
        const val STEP_SPAN_MS = 4_000L
    }
}
