package forma.core.body

import forma.core.model.LiftFocus
import forma.core.model.Sex
import kotlin.math.pow

/** Estimates from body weight and height. Estimates, not measurements: shown with "≈". */
object Body {
    fun bmi(kg: Double, cm: Int): Double = kg / (cm / 100.0).pow(2)

    /** WHO adult categories. */
    fun bmiClass(bmi: Double): String = when {
        bmi < 18.5 -> "ниже нормы"
        bmi < 25 -> "норма"
        bmi < 30 -> "выше нормы"
        else -> "ожирение"
    }

    /** Resting energy per day, kcal (Mifflin–St Jeor, 1990). */
    fun bmr(kg: Double, cm: Int, age: Int, sex: Sex): Double =
        10 * kg + 6.25 * cm - 5 * age + if (sex == Sex.MALE) 5.0 else -161.0

    /** Energy per day: resting × 1.3 for everyday life plus the day's share of training, kcal. */
    fun dailyEnergy(bmr: Double, trainingKcalPerWeek: Double): Double = bmr * 1.3 + trainingKcalPerWeek / 7

    /** Running costs about 1 kcal per kg of body weight per km. */
    fun runKcal(kg: Double, km: Double): Double = kg * km

    /** Running without a distance: about 9 METs. */
    fun runKcalByTime(kg: Double, minutes: Double): Double = 9.0 * kg * minutes / 60

    /** Lifting with rest between sets: about 4.5 METs (Compendium of Physical Activities). */
    fun liftKcal(kg: Double, minutes: Double): Double = 4.5 * kg * minutes / 60

    /**
     * Protein per day, g. Building muscle or strength: 1.6–2.2 g/kg (gains level off around 1.6 g/kg, Morton et al.
     * 2018); otherwise 1.2–1.6 g/kg.
     */
    fun protein(kg: Double, lift: LiftFocus): ClosedFloatingPointRange<Double> = when (lift) {
        LiftFocus.STRENGTH, LiftFocus.POWERBUILDING, LiftFocus.MUSCLE -> kg * 1.6..kg * 2.2
        LiftFocus.GENERAL, LiftFocus.NONE -> kg * 1.2..kg * 1.6
    }
}
