package forma.core

import forma.core.strength.DoneSet
import forma.core.strength.RirScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RirScaleTest {
    @Test fun anOverRaterIsReadDown() {
        // Says "4 more" after 8, and two sets later at the same weight only 7 come: 12 promised, about 8 there.
        val pair = RirScale.pairOf(listOf(DoneSet(80.0, 8, 4), DoneSet(80.0, 8, 2), DoneSet(80.0, 7, null)), 8)
        assertEquals(RirScale.Pair(8, 4, 7, 2), pair)
        assertEquals(2, RirScale.bias(List(5) { pair!! }))
        assertEquals(2, RirScale.correct(4, 2))
        assertEquals(0, RirScale.correct(1, 2))
        assertNotNull(RirScale.explain(2))
    }

    @Test fun honestRatingsAndFewPairsChangeNothing() {
        // "1 more" after 8, then 7 on the next: as rated.
        assertEquals(0, RirScale.bias(List(6) { RirScale.Pair(8, 1, 8, 1) }))
        assertEquals(0, RirScale.bias(List(3) { RirScale.Pair(8, 4, 6, 1) }))
        assertNull(RirScale.correct(null, 2))
        assertNull(RirScale.explain(0))
    }

    @Test fun aSetStoppedWithRepsToSpareIsNotFailure() {
        // Seven reps "with 3 to spare" is a choice to stop, not running out.
        assertNull(RirScale.pairOf(listOf(DoneSet(80.0, 8, 4), DoneSet(80.0, 7, 3)), 8))
        // Another weight says nothing about this one.
        assertNull(RirScale.pairOf(listOf(DoneSet(80.0, 8, 4), DoneSet(85.0, 6, 0)), 8))
    }
}
