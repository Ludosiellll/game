package forma.core.run

import kotlin.math.roundToInt

data class HrZone(val number: Int, val name: String, val low: Int, val high: Int)

object HeartRate {
    /** Tanaka, Monahan & Seals (2001): HRmax = 208 − 0.7 × age. */
    fun maxTanaka(age: Int): Int = (208 - 0.7 * age).roundToInt()

    /**
     * Five zones. With a resting pulse the bounds use heart-rate reserve (Karvonen),
     * otherwise a share of HRmax.
     */
    fun zones(hrMax: Int, hrRest: Int? = null): List<HrZone> {
        val names = listOf("Восстановление", "Лёгкая аэробная", "Темповая", "Пороговая", "Максимальная")
        return if (hrRest != null && hrRest in 30 until hrMax) {
            val bounds = listOf(0.50, 0.60, 0.70, 0.80, 0.90, 1.00)
            (0 until 5).map { i ->
                HrZone(i + 1, names[i], karvonen(hrMax, hrRest, bounds[i]), karvonen(hrMax, hrRest, bounds[i + 1]))
            }
        } else {
            val bounds = listOf(0.50, 0.60, 0.70, 0.80, 0.90, 1.00)
            (0 until 5).map { i ->
                HrZone(i + 1, names[i], (hrMax * bounds[i]).roundToInt(), (hrMax * bounds[i + 1]).roundToInt())
            }
        }
    }

    fun karvonen(hrMax: Int, hrRest: Int, fraction: Double): Int = (hrRest + fraction * (hrMax - hrRest)).roundToInt()
}
