package forma.core

import forma.core.strength.RirScale
import kotlin.test.Test
import kotlin.test.assertEquals

class RirScaleTest {
    @Test fun anOverRaterIsReadDown() {
        // Says "4 more" at 8 reps, then the "+" set at the same weight goes to 9 with 1 left: 12 rated, 10 real, 1 for fatigue.
        val pairs = List(6) { RirScale.Pair(8, 4, 9, 1) }
        assertEquals(1, RirScale.bias(pairs))
        assertEquals(3, RirScale.correct(4, 1))
        assertEquals(0, RirScale.correct(0, 1))
    }

    @Test fun anUnderRaterIsReadUp() {
        val pairs = List(6) { RirScale.Pair(8, 1, 11, null) }
        assertEquals(-2, RirScale.bias(pairs))
        assertEquals(3, RirScale.correct(1, -2))
    }

    @Test fun fewPairsOrAnAccurateRaterChangeNothing() {
        assertEquals(0, RirScale.bias(List(4) { RirScale.Pair(8, 4, 9, 1) }))
        assertEquals(0, RirScale.bias(List(8) { RirScale.Pair(8, 2, 8, 1) }))
        // A "+" set stopped well short of failure says nothing about the ceiling.
        assertEquals(0, RirScale.bias(List(8) { RirScale.Pair(8, 4, 9, 3) }))
        assertEquals(null, RirScale.correct(null, 2))
    }

    @Test fun itIsCapped() {
        assertEquals(2, RirScale.bias(List(6) { RirScale.Pair(5, 5, 5, 0) }))
    }
}
