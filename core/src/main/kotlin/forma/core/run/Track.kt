package forma.core.run

import kotlin.math.max
import kotlin.math.roundToInt

/** Running on a stadium: distances in laps, paces as seconds per lap. */
object Track {
    /** Seconds per lap of [lapMeters] at [secPerKm]. */
    fun lapSec(secPerKm: Double, lapMeters: Int): Double = secPerKm * lapMeters / 1000.0

    /** [meters] as laps, to half a lap (a race of 6.5 laps starts half a lap before the line). */
    fun laps(meters: Double, lapMeters: Int): Double = max(0.5, (meters / lapMeters * 2).roundToInt() / 2.0)

    /** [meters] as whole laps, at least one: reps start and finish on the line. */
    fun wholeLaps(meters: Double, lapMeters: Int): Int = max(1, (meters / lapMeters).roundToInt())
}
