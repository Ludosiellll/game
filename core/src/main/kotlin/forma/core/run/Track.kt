package forma.core.run

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Running on a stadium: distances in laps, paces as seconds per lap. */
object Track {
    /** Seconds per lap of [lapMeters] at [secPerKm]. */
    fun lapSec(secPerKm: Double, lapMeters: Int): Double = secPerKm * lapMeters / 1000.0

    /** [meters] in whole laps, at least one. */
    private fun whole(meters: Double, lapMeters: Int): Double = max(1, (meters / lapMeters).roundToInt()).toDouble()

    private fun off(laps: Double, lapMeters: Int, meters: Double) = abs(laps * lapMeters - meters) / meters

    /**
     * A rep of about [meters] in whole laps: it starts and ends on the runner's line, where the runner presses «Круг».
     * Null when no whole lap count is within a quarter (a long loop for a short rep: the rep stays timed).
     */
    fun repLaps(meters: Double, lapMeters: Int): Double? {
        return whole(meters, lapMeters).takeIf { off(it, lapMeters, meters) <= 0.25 }
    }

    /**
     * A race of [meters] in whole laps, the nearest count: started and finished on the runner's line (1500 m on a 205 m
     * loop is 7 laps, 1435 m; its time gives the fitness all the same). A start part of a lap before the line was lost
     * on a loop with no marks (Denis, 29.10: he started on the line, and his first lap was taken for the half).
     */
    fun raceLaps(meters: Double, lapMeters: Int): Double = whole(meters, lapMeters)
}
