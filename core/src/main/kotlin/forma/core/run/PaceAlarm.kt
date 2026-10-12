package forma.core.run

/**
 * Off the pace for a while on the road: too fast or too slow for [HOLD_MS] (not a moment at a crossing, not the first
 * half minute of a rep while getting going) is said once, and again every [REPEAT_MS] while it goes on. Easy running has
 * only a fast limit: slower is fine.
 */
class PaceAlarm {
    enum class Alert { FAST, SLOW }

    private var kind: Alert? = null
    private var since = 0L
    private val said = mutableMapOf<Alert, Long>()

    /**
     * The pace now ([pace], s/km) against [fast]..[slow] (either may be null: no limit that way), [segmentMs] into the
     * segment. Returns what to say, if anything.
     */
    fun update(nowMs: Long, pace: Double?, fast: Double?, slow: Double?, segmentMs: Long): Alert? {
        val now = when {
            pace == null || segmentMs < SETTLE_MS -> null
            fast != null && pace < fast * 0.97 -> Alert.FAST
            slow != null && pace > slow * 1.03 -> Alert.SLOW
            else -> null
        }
        if (now != kind) {
            kind = now
            since = nowMs
            if (now == null) said.clear()
            return null
        }
        if (now == null || nowMs - since < HOLD_MS) return null
        if (said[now]?.let { nowMs - it < REPEAT_MS } == true) return null
        said[now] = nowMs
        return now
    }

    companion object {
        const val SETTLE_MS = 30_000L
        const val HOLD_MS = 20_000L
        const val REPEAT_MS = 60_000L
    }
}
