package forma.core

import forma.core.run.PaceAlarm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Off the pace on the road: said after a while, not at every wobble, and again only if it goes on. */
class PaceAlarmTest {
    private fun run(a: PaceAlarm, from: Int, to: Int, pace: Double, fast: Double?, slow: Double?, segStart: Int = 0): List<Pair<Int, PaceAlarm.Alert>> =
        (from..to).mapNotNull { sec -> a.update(sec * 1000L, pace, fast, slow, (sec - segStart) * 1000L)?.let { sec to it } }

    @Test fun tooSlowInARepIsSaidAfterTwentySeconds() {
        val a = PaceAlarm()
        // A 4:30 rep run at 4:50 from the start: nothing the first half minute, then once, again a minute later.
        val said = run(a, 0, 120, pace = 290.0, fast = 265.0, slow = 275.0)
        assertEquals(listOf(50 to PaceAlarm.Alert.SLOW, 110 to PaceAlarm.Alert.SLOW), said)
    }

    @Test fun aMomentOffThePaceIsNothing() {
        val a = PaceAlarm()
        run(a, 0, 40, 270.0, 265.0, 275.0)
        assertEquals(emptyList(), run(a, 41, 55, 300.0, 265.0, 275.0))
        assertEquals(emptyList(), run(a, 56, 120, 270.0, 265.0, 275.0))
    }

    @Test fun easyRunningHasOnlyAFastLimit() {
        val a = PaceAlarm()
        // Slower than the easy pace is fine.
        assertEquals(emptyList(), run(a, 0, 200, 420.0, fast = 330.0, slow = null))
        val b = PaceAlarm()
        assertEquals(PaceAlarm.Alert.FAST, run(b, 0, 80, 300.0, fast = 330.0, slow = null).first().second)
        assertNull(PaceAlarm().update(0, null, 300.0, 310.0, 60_000))
    }
}
