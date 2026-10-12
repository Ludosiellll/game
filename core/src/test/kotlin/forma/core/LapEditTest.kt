package forma.core

import forma.core.run.Lap
import forma.core.run.LapEdit
import forma.core.run.Splits
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Laps put right after the run, and kilometre splits from the track. */
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

    @Test fun lapsOfRepsStayAsTheyWere() {
        val laps = listOf(Lap(52.0, rep = 1, number = 1, of = 1), Lap(150.0, number = 1))
        assertEquals(laps, LapEdit.split(laps, 0))
        assertEquals(laps, LapEdit.merge(laps, 0))
        assertTrue(!LapEdit.editable(laps, 0) && LapEdit.editable(laps, 1))
    }

    @Test fun kilometreSplitsFromTheTrack() {
        // 3.3 km east at 5:00, 5:20 and 4:40 a km, a fix a second (and one thrown 300 m off).
        val lat = 53.9
        val mPerDegLon = 111_195.0 * cos(Math.toRadians(lat))
        val paces = listOf(300.0, 320.0, 280.0, 300.0)
        val pts = mutableListOf<Splits.Point>()
        var x = 0.0
        var t = 0L
        var k = 0
        while (x <= 3300) {
            val pace = paces[(x / 1000).toInt().coerceAtMost(3)]
            pts += Splits.Point(t, lat, 27.5 + x / mPerDegLon, 5f)
            if (k++ == 700) pts += Splits.Point(t + 500, lat + 0.003, 27.5 + x / mPerDegLon, 5f)
            x += 1000.0 / pace
            t += 1000
        }
        val splits = Splits.byKm(pts)
        assertEquals(3, splits.size, splits.toString())
        assertTrue(splits.zip(paces.take(3)).all { (s, p) -> abs(s - p) < 3 }, splits.toString())
    }
}
