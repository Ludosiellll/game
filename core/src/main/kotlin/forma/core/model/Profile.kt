package forma.core.model

import kotlinx.serialization.Serializable
import java.time.DayOfWeek

@Serializable
enum class Sex { MALE, FEMALE }

/** The five classic goals. Kept as presets and for profiles saved before [Aim] existed. */
@Serializable
enum class Goal { STRENGTH, MUSCLE, HYBRID, ENDURANCE, FITNESS }

/** What the gym part is for. */
@Serializable
enum class LiftFocus { STRENGTH, POWERBUILDING, MUSCLE, GENERAL, NONE }

/** What the running part is for. */
@Serializable
enum class RunFocus { NONE, HEALTH, ENDURANCE, SPEED }

/** Which side gets the days and the recovery when the two compete. */
@Serializable
enum class Emphasis { GYM, BALANCED, RUN }

/**
 * What the plan is built for: the gym focus, the running focus and which of them comes first.
 * "Muscle and running endurance, both equally" is `Aim(MUSCLE, ENDURANCE, BALANCED)`.
 */
@Serializable
data class Aim(val lift: LiftFocus, val run: RunFocus, val emphasis: Emphasis = Emphasis.BALANCED) {
    val lifts: Boolean get() = lift != LiftFocus.NONE
    val runs: Boolean get() = run != RunFocus.NONE

    /** Running worth planning around (intervals, long runs, fresh legs for them) and not a side dish of the gym. */
    val seriousRunning: Boolean get() = (run == RunFocus.ENDURANCE || run == RunFocus.SPEED) && emphasis != Emphasis.GYM

    /** One side alone has nothing to share the week with; neither side means general fitness. */
    fun normalized(): Aim = when {
        !lifts && !runs -> Aim(LiftFocus.GENERAL, RunFocus.HEALTH, Emphasis.BALANCED)
        !lifts -> copy(emphasis = Emphasis.RUN)
        !runs -> copy(emphasis = Emphasis.GYM)
        else -> this
    }

    /** The closest classic goal, stored next to the aim. */
    fun nearestGoal(): Goal {
        val a = normalized()
        val bigRunning = a.run == RunFocus.ENDURANCE || a.run == RunFocus.SPEED
        return when {
            !a.lifts || a.emphasis == Emphasis.RUN && bigRunning -> Goal.ENDURANCE
            bigRunning && a.lift != LiftFocus.GENERAL -> Goal.HYBRID
            a.lift == LiftFocus.STRENGTH || a.lift == LiftFocus.POWERBUILDING -> Goal.STRENGTH
            a.lift == LiftFocus.MUSCLE -> Goal.MUSCLE
            else -> Goal.FITNESS
        }
    }
}

/** Each classic goal as a combination. */
fun Goal.aim(): Aim = when (this) {
    Goal.STRENGTH -> Aim(LiftFocus.STRENGTH, RunFocus.HEALTH, Emphasis.GYM)
    Goal.MUSCLE -> Aim(LiftFocus.MUSCLE, RunFocus.HEALTH, Emphasis.GYM)
    Goal.HYBRID -> Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED)
    // Runners lift heavy and little: strength work improves running economy without much fatigue.
    Goal.ENDURANCE -> Aim(LiftFocus.STRENGTH, RunFocus.ENDURANCE, Emphasis.RUN)
    Goal.FITNESS -> Aim(LiftFocus.GENERAL, RunFocus.HEALTH, Emphasis.BALANCED)
}

@Serializable
enum class Experience { NOVICE, INTERMEDIATE, ADVANCED }

/**
 * A race to train for: [meters] in [seconds] (1500 m in 5:35 is `RunGoal(1500, 335)`). Middle distances (up to 3 km)
 * get race-pace sessions and are tested over the distance itself.
 */
@Serializable
data class RunGoal(val meters: Int, val seconds: Int) {
    val middleDistance: Boolean get() = meters <= 3000

    /** Race pace, seconds per km. */
    val pace: Double get() = seconds / (meters / 1000.0)
}

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
    /** What the plan is built for; profiles saved before it existed get the one of their goal. */
    val aim: Aim = goal.aim(),
    /** The race to train for, if any. */
    val runGoal: RunGoal? = null,
    /** Stadium lap in metres when running on a track: sessions in laps, lap times, a lap button. Null — outside, GPS. */
    val lapMeters: Int? = null,
    /** The gym and a run may share a day (the run after the gym): three days then hold three of each. */
    val doubleDays: Boolean = false,
) {
    fun age(year: Int): Int = (year - birthYear).coerceIn(10, 100)
    val days: Set<DayOfWeek> get() = trainingDays.map { DayOfWeek.of(it) }.toSet()
}
