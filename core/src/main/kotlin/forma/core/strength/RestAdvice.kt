package forma.core.strength

/**
 * Rest between sets for this person. The plan's rest suits most; when someone's reps keep falling by three or more
 * from the first set to the last at one weight, the rest is too short for them: 30 seconds more (longer rest costs
 * nothing for strength and keeps the later sets' reps).
 */
object RestAdvice {
    const val MORE_SEC = 30

    /** [sessions]: the sets of the last sessions of an exercise, oldest first. True: three of the last four lost 3+ reps. */
    fun longer(sessions: List<List<DoneSet>>): Boolean {
        val judged = sessions.filter { it.size >= 3 }.takeLast(4)
        if (judged.size < 3) return false
        val dropped = judged.count { sets ->
            val w = sets.first().weight
            val same = sets.filter { kotlin.math.abs(it.weight - w) < 1e-6 }
            same.size >= 3 && same.first().reps - same.last().reps >= 3
        }
        return dropped >= 3
    }
}
