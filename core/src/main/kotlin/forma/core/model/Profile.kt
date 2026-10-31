package forma.core.model

import kotlinx.serialization.Serializable
import java.time.DayOfWeek

@Serializable
enum class Sex { MALE, FEMALE }

/**
 * Where the gym part is done: a gym, at home with dumbbells and a bench, or at home with the body alone. The plan is
 * made of what there is ([fits]).
 */
@Serializable
enum class Gear(val ru: String) {
    GYM("Зал"), DUMBBELLS("Дома: гантели и скамья"), BODY("Дома: свой вес");

    /**
     * [ex] can be done here; [bar]: a pull-up bar at home (pull-ups, hanging leg raises). Without one, nothing that needs
     * a rig: dip bars, a back-extension bench, a hanging bar.
     */
    fun fits(ex: Exercise, bar: Boolean): Boolean = when (this) {
        GYM -> true
        DUMBBELLS -> (ex.equipment == Equipment.DUMBBELL || ex.equipment == Equipment.BODYWEIGHT) && rig(ex, bar)
        BODY -> ex.equipment == Equipment.BODYWEIGHT && rig(ex, bar)
    }

    private fun rig(ex: Exercise, bar: Boolean) = ex.id !in RIG && (bar || ex.id !in BAR)

    companion object {
        /** Body-weight exercises that need a gym's rig, not a home. */
        private val RIG = setOf("dip", "back_ext", "reverse_hyper", "glute_ham_raise", "handstand_pushup")
        /** Done on a pull-up bar. */
        private val BAR = setOf("pullup", "chinup", "neutral_pullup", "leg_raise", "knee_raise")
    }
}

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
data class RunGoal(
    val meters: Int,
    val seconds: Int,
    /** The race's day (epoch day), when there is one: the weeks before it are a taper, the day itself the race. */
    val date: Long? = null,
) {
    val middleDistance: Boolean get() = meters <= 3000

    /** Race pace, seconds per km. */
    val pace: Double get() = seconds / (meters / 1000.0)
}

/**
 * A goal in the gym: [exerciseId] lifted [kg] for [reps] ("пожать 150" is `LiftGoal("bench", 150.0)`), by [date] when
 * there is one. [fromE1rm] and [setDay]: the estimated max and the day when it was set, to say how far it has come.
 */
@Serializable
data class LiftGoal(
    val exerciseId: String,
    val kg: Double,
    val reps: Int = 1,
    /** The day to have it by (epoch day): the days before are lighter, the day itself is the test. */
    val date: Long? = null,
    val fromE1rm: Double? = null,
    val setDay: Long? = null,
)

/**
 * How the gym week is divided (the program wizard, 31.10): the app's own choice by the days and the running, or the
 * person's — every session the whole body, upper and lower body days, or push, pull and legs days.
 */
@Serializable
enum class Split(val ru: String, val note: String) {
    AUTO("Подберём сами", "По числу дней и по бегу. Советую, если не знаешь, что выбрать"),
    FULL("Всё тело", "Каждая тренировка — всё тело. Хорошо при 2–3 днях"),
    UPPER_LOWER("Верх / низ", "День верха, день низа. Хорошо при 4 днях"),
    PPL("Тяни / толкай / ноги", "Жимы, тяги и ноги в разные дни. Хорошо при 3, 5 и 6 днях"),
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
    /**
     * A distance to run without stopping, no time set (a beginner's «пробежать 5 км»): the plan builds up to it as it
     * would anyway, the progress shows how far it is. Counts only while there is no [runGoal].
     */
    val finishMeters: Int? = null,
    /** Stadium lap in metres when running on a track: sessions in laps, lap times, a lap button. Null — outside, GPS. */
    val lapMeters: Int? = null,
    /** The gym and a run may share a day (the run after the gym): three days then hold three of each. */
    val doubleDays: Boolean = false,
    /**
     * The week as the person laid it out, day by day: the gym on [gymDays], running on [runDays] (a day in both holds
     * both). Null: the app lays the week out over [trainingDays] itself.
     */
    val gymDays: Set<Int>? = null,
    val runDays: Set<Int>? = null,
    /** A goal in the gym, if any: one lift to a weight (see [forma.core.strength.GymGoal]). */
    val liftGoal: LiftGoal? = null,
    /** Where the gym part is done; the plan is made of what is there. */
    val gear: Gear = Gear.GYM,
    /** At home: a pull-up bar is there. */
    val pullupBar: Boolean = false,
    /**
     * The aim with both sides as it was before one was dropped («Только зал» / «Только бег»): what the side was for and
     * which came first, to come back with it. Null while both are in the week.
     */
    val keptAim: Aim? = null,
    /** How the gym week is divided; [Split.AUTO] — by the app. */
    val split: Split = Split.AUTO,
    /** Up to two muscle groups worked more than evenly (the program wizard's «акценты»). */
    val accents: Set<Muscle> = emptySet(),
) {
    /** The person chose the gym and running days themselves. */
    val ownWeek: Boolean get() = gymDays != null && runDays != null

    fun age(year: Int): Int = (year - birthYear).coerceIn(10, 100)

    /** The distance to run without stopping, when that is the running goal (no race goal, running in the plan). */
    val finish: Int? get() = finishMeters.takeIf { runGoal == null && aim.normalized().runs }

    val days: Set<DayOfWeek> get() = trainingDays.map { DayOfWeek.of(it) }.toSet()
}
