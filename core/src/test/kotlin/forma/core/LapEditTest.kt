package forma.core

import forma.core.run.Lap
import forma.core.run.LapEdit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Laps put right after the run. */
class LapEditTest {
    private val free = listOf(76.0, 75.0, 141.0, 80.0).mapIndexed { i, s -> Lap(s, number = i + 1) }

    @Test fun aForgottenPressSplitsIntoTwoLaps() {
        val fixed = LapEdit.split(free, 2)
        assertEquals(listOf(76.0, 75.0, 70.5, 70.5, 80.0), fixed.map { it.seconds })
        assertEquals(listOf(1, 2, 3, 4, 5), fixed.map { it.number })
        // Roughly timed: shown with "≈".
        assertTrue(fixed[2].auto && fixed[3].auto && !fixed[0].auto)
        assertEquals(free.sumOf { it.seconds }, fixed.sumOf { it.seconds }, 1e-9)
    }

    @Test fun aDoublePressMergesTwoLaps() {
        val laps = listOf(76.0, 30.0, 45.0, 75.0).mapIndexed { i, s -> Lap(s, number = i + 1) }
        val fixed = LapEdit.merge(laps, 1)
        assertEquals(listOf(76.0, 75.0, 75.0), fixed.map { it.seconds })
        assertEquals(listOf(1, 2, 3), fixed.map { it.number })
        // The last lap has nothing after it to merge with.
        assertEquals(fixed, LapEdit.merge(fixed, 2))
    }

    @Test fun aLapTimedWrongGetsItsTimeByHand() {
        val fixed = LapEdit.setTime(free, 2, 83.4)
        assertEquals(listOf(76.0, 75.0, 83.4, 80.0), fixed.map { it.seconds })
        assertEquals(free.map { it.number }, fixed.map { it.number })
        // Exact as typed, not roughly timed — even when the lap was a GPS one.
        val auto = free.map { it.copy(auto = true) }
        assertTrue(!LapEdit.setTime(auto, 0, 70.0)[0].auto)
        // A time out of reason changes nothing.
        assertEquals(free, LapEdit.setTime(free, 1, 3.0))
        assertEquals(free, LapEdit.setTime(free, 1, Double.NaN))
        assertEquals(free, LapEdit.setTime(free, 9, 80.0))
    }

    /** Reps stay as many (no split, merge or removal); a rep's lap gets its time put right (a stray touch ended it early). */
    @Test fun lapsOfRepsOnlyGetTheirTime() {
        val laps = listOf(Lap(52.0, rep = 1, number = 1, of = 1), Lap(150.0, number = 1))
        assertEquals(laps, LapEdit.split(laps, 0))
        assertEquals(laps, LapEdit.merge(laps, 0))
        assertEquals(laps, LapEdit.remove(laps, 0))
        assertEquals(listOf(laps[0].copy(seconds = 50.0), laps[1]), LapEdit.setTime(laps, 0, 50.0))
        assertTrue(!LapEdit.editable(laps, 0) && LapEdit.editable(laps, 1))
    }
}
