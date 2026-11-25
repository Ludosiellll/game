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

    @Test fun whoStopsShortByChoiceLeavesNoPair() {
        // Seven of eight every set "with 3 to spare": never reached the target, a choice, not running out.
        assertNull(RirScale.pairOf(listOf(DoneSet(80.0, 7, 3), DoneSet(80.0, 7, 3), DoneSet(80.0, 7, 3)), 8))
        // Short after the target was reached is running out, however it was rated.
        assertEquals(RirScale.Pair(8, 3, 6, 1), RirScale.pairOf(listOf(DoneSet(80.0, 8, 3), DoneSet(80.0, 6, 2)), 8))
        // A rep over the rating is noise.
        assertEquals(0, RirScale.bias(List(8) { RirScale.Pair(8, 2, 8, 1) }))
        // Another weight says nothing about this one.
        assertNull(RirScale.pairOf(listOf(DoneSet(80.0, 8, 4), DoneSet(85.0, 6, 0)), 8))
    }

    /**
     * Denis, 25 November: from 22.11 the weight stopped going up right after a set rated «5+». His fourth "pair" was a set
     * rated 0 (30 × 10 to failure, then 30 × 6): a set done to failure promises no more reps, and the shorter set after
     * it is the fatigue of that failure, not a reserve rated too high. Such a set says nothing of how the person rates.
     */
    @Test fun aSetRatedToFailureIsNoEvidenceOfOverRating() {
        assertNull(RirScale.pairOf(listOf(DoneSet(30.0, 10, 0), DoneSet(30.0, 6, 1)), 10))
        // A rated set before it still pairs with the set that ran out.
        assertEquals(RirScale.Pair(10, 2, 6, 2), RirScale.pairOf(listOf(DoneSet(30.0, 10, 2), DoneSet(30.0, 10, 0), DoneSet(30.0, 6, 1)), 10))
        // His real pairs up to 22.11 (pull-ups 7 at 0 then 6; 11 and 10 at «ещё 2» then 10 and 9; 10 at 0 then 6): no
        // correction comes of them.
        val his = listOfNotNull(
            RirScale.pairOf(listOf(DoneSet(30.0, 7, 0), DoneSet(30.0, 6, 0)), 7),
            RirScale.pairOf(listOf(DoneSet(29.0, 11, 2), DoneSet(29.0, 10, 0)), 11),
            RirScale.pairOf(listOf(DoneSet(22.5, 10, 2), DoneSet(22.5, 9, 0)), 10),
            RirScale.pairOf(listOf(DoneSet(30.0, 10, 0), DoneSet(30.0, 6, 1)), 10),
        )
        assertEquals(0, RirScale.bias(his))
    }
}
