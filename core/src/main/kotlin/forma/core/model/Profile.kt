package forma.core.model

import kotlinx.serialization.Serializable
import java.time.DayOfWeek

@Serializable
enum class Sex { MALE, FEMALE }

/** What the person wants most. Drives the weekly mix and rep ranges. */
@Serializable
enum class Goal { STRENGTH, MUSCLE, HYBRID, ENDURANCE, FITNESS }

@Serializable
enum class Experience { NOVICE, INTERMEDIATE, ADVANCED }

/** Running background: NONE — can't run 20 minutes in a row yet; BASE — can; TRAINED — runs regularly. */
@Serializable
enum class RunLevel { NONE, BASE, TRAINED }

@Serializable
data class Profile(
    val name: String = "",
    val sex: Sex = Sex.MALE,
    val birthYear: Int = 1995,
    val bodyWeightKg: Double = 75.0,
    val heightCm: Int? = null,
    val goal: Goal = Goal.HYBRID,
    val experience: Experience = Experience.NOVICE,
    val runLevel: RunLevel = RunLevel.BASE,
    /** ISO days 1 (Monday) .. 7 (Sunday) the person can train. */
    val trainingDays: Set<Int> = setOf(1, 3, 5, 6),
    val sessionMinutes: Int = 75,
    /** Best recent race or time trial, if any. */
    val raceMeters: Double? = null,
    val raceSeconds: Double? = null,
    val restingHr: Int? = null,
    val maxHr: Int? = null,
) {
    fun age(year: Int): Int = (year - birthYear).coerceIn(10, 100)
    val days: Set<DayOfWeek> get() = trainingDays.map { DayOfWeek.of(it) }.toSet()
}
