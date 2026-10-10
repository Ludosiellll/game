package forma.core.analytics

import forma.core.model.Sex

/** Relative strength points for a powerlifting total (squat + bench + deadlift). */
object Points {
    private val MEN = doubleArrayOf(-307.75076, 24.0900756, -0.1918759221, 0.0007391293, -0.0000010930)
    private val WOMEN = doubleArrayOf(-57.96288, 13.6175032, -0.1126655495, 0.0005158568, -0.0000010706)

    /** DOTS (BVDK): total × 500 / polynomial(body weight); body weight clamped as in OpenPowerlifting. */
    fun dots(totalKg: Double, bodyWeightKg: Double, sex: Sex): Double {
        val c = if (sex == Sex.MALE) MEN else WOMEN
        val x = bodyWeightKg.coerceIn(40.0, if (sex == Sex.MALE) 210.0 else 150.0)
        val den = c[0] + c[1] * x + c[2] * x * x + c[3] * x * x * x + c[4] * x * x * x * x
        return totalKg * 500 / den
    }
}

/** Rough energy estimates: kcal = MET × kg × hours (2024 Adult Compendium of Physical Activities). */
object Calories {
    /** Running METs by speed, km/h → MET. */
    private val RUN = listOf(
        6.4 to 6.5, 7.2 to 7.8, 8.0 to 8.5, 9.0 to 9.0, 9.7 to 9.3, 10.8 to 10.5, 11.3 to 11.0, 12.1 to 11.8,
        12.9 to 12.0, 13.8 to 12.5, 14.5 to 13.0, 16.1 to 14.8, 17.7 to 16.8, 19.3 to 18.5, 20.9 to 19.8, 22.5 to 23.0,
    )

    fun runMet(kmh: Double): Double {
        if (kmh <= RUN.first().first) return RUN.first().second * kmh / RUN.first().first
        if (kmh >= RUN.last().first) return RUN.last().second
        val i = RUN.indexOfFirst { it.first >= kmh }
        val (x0, y0) = RUN[i - 1]
        val (x1, y1) = RUN[i]
        return y0 + (y1 - y0) * (kmh - x0) / (x1 - x0)
    }

    fun run(kmh: Double, bodyWeightKg: Double, minutes: Double): Double = runMet(kmh) * bodyWeightKg * minutes / 60

    /** Weight training: 3.5 MET for general sets of 8–15, 5.0 for heavy squats and deadlifts. */
    fun lifting(bodyWeightKg: Double, minutes: Double, heavy: Boolean): Double = (if (heavy) 5.0 else 3.5) * bodyWeightKg * minutes / 60
}
