package forma.core.run

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Running on a stadium: distances in laps, paces as seconds per lap. */
object Track {
    /** Seconds per lap of [lapMeters] at [secPerKm]. */
    fun lapSec(secPerKm: Double, lapMeters: Int): Double = secPerKm * lapMeters / 1000.0

    /** [meters] in laps at a step of [step] laps (1, 0.5, 0.25), at least one step. */
    private fun round(meters: Double, lapMeters: Int, step: Double): Double =
        max(step, (meters / lapMeters / step).roundToInt() * step)

    private fun off(laps: Double, lapMeters: Int, meters: Double) = abs(laps * lapMeters - meters) / meters

    /**
     * A rep of about [meters] in laps: whole laps (start and finish on the line) when that is less than a fifth off, else
     * half laps (the rep starts half a lap before the line) when that is within a quarter; null when no lap count fits
     * (a long loop for a short rep: the rep stays timed).
     */
    fun repLaps(meters: Double, lapMeters: Int): Double? {
        val whole = round(meters, lapMeters, 1.0)
        if (off(whole, lapMeters, meters) < 0.2) return whole
        val half = round(meters, lapMeters, 0.5)
        return when {
            off(half, lapMeters, meters) <= 0.25 -> half
            off(whole, lapMeters, meters) <= 0.25 -> whole
            else -> null
        }
    }

    /**
     * A race of [meters] in laps, as close as the line allows: whole or half laps when within 3 %, else quarter laps
     * (1500 m on a 400 m track is 3¾ laps: the start is three quarters of a lap before the line).
     */
    fun raceLaps(meters: Double, lapMeters: Int): Double {
        for (step in listOf(1.0, 0.5)) {
            val laps = round(meters, lapMeters, step)
            if (off(laps, lapMeters, meters) <= 0.03) return laps
        }
        return round(meters, lapMeters, 0.25)
    }
}
