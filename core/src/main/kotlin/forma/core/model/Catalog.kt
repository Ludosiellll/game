package forma.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Muscle(val ru: String) {
    CHEST("Грудь"), BACK("Спина"), SHOULDERS("Плечи"), BICEPS("Бицепс"), TRICEPS("Трицепс"),
    QUADS("Квадрицепс"), HAMSTRINGS("Бицепс бедра"), GLUTES("Ягодицы"), CALVES("Икры"), CORE("Пресс и кор"),
}

@Serializable
enum class Equipment(val ru: String) { BARBELL("Штанга"), DUMBBELL("Гантели"), MACHINE("Тренажёр"), CABLE("Блок"), BODYWEIGHT("Свой вес") }

/** How the load is counted: external weight, body weight plus extra weight, or seconds of holding. */
@Serializable
enum class LoadKind { EXTERNAL, BODYWEIGHT_PLUS, TIMED }

@Serializable
data class Exercise(
    val id: String,
    val name: String,
    val equipment: Equipment,
    val primary: List<Muscle>,
    val secondary: List<Muscle> = emptyList(),
    /** Smallest practical change of the load, kg (per dumbbell for dumbbells). */
    val step: Double = 2.5,
    val load: LoadKind = LoadKind.EXTERNAL,
    val lowerBody: Boolean = false,
    val compound: Boolean = false,
    /** Conservative first working weight for ~8–10 reps as a share of body weight (novice man). */
    val startRatio: Double = 0.3,
    /** Bar weight to show plates for, if this is a barbell lift. */
    val bar: Double? = null,
    /** Finer step below [fineBelow] kg (light dumbbells usually go 1 kg at a time). */
    val fineStep: Double? = null,
    val fineBelow: Double = 0.0,
    /** Body-weight exercises: share of body weight that is moved (push-ups ≈ 0.65, pull-ups ≈ 1). */
    val bodyShare: Double = 1.0,
    /** Body-weight exercises that can be made easier with an assisted machine or a band (negative extra weight). */
    val assisted: Boolean = false,
) {
    val perHand: Boolean get() = equipment == Equipment.DUMBBELL

    /** The smallest practical change of the load around [weight]. */
    fun stepAt(weight: Double): Double = if (fineStep != null && weight < fineBelow - 1e-9) fineStep else step
}

object Catalog {
    private fun bb(id: String, name: String, p: List<Muscle>, s: List<Muscle>, lower: Boolean, ratio: Double) =
        Exercise(id, name, Equipment.BARBELL, p, s, 2.5, LoadKind.EXTERNAL, lower, true, ratio, 20.0)

    private fun db(id: String, name: String, p: List<Muscle>, s: List<Muscle> = emptyList(), lower: Boolean = false, compound: Boolean = false, ratio: Double) =
        Exercise(id, name, Equipment.DUMBBELL, p, s, 2.0, LoadKind.EXTERNAL, lower, compound, ratio, fineStep = 1.0, fineBelow = 10.0)

    private fun mc(id: String, name: String, eq: Equipment, p: List<Muscle>, s: List<Muscle> = emptyList(), step: Double = 5.0, lower: Boolean = false, compound: Boolean = false, ratio: Double) =
        Exercise(id, name, eq, p, s, step, LoadKind.EXTERNAL, lower, compound, ratio)

    private fun bw(
        id: String, name: String, p: List<Muscle>, s: List<Muscle> = emptyList(), kind: LoadKind = LoadKind.BODYWEIGHT_PLUS,
        lower: Boolean = false, compound: Boolean = true, share: Double = 1.0, assisted: Boolean = false,
    ) = Exercise(id, name, Equipment.BODYWEIGHT, p, s, 2.5, kind, lower, compound, 0.0, bodyShare = share, assisted = assisted)

    val all: List<Exercise> = listOf(
        bb("squat", "Приседания со штангой", listOf(Muscle.QUADS, Muscle.GLUTES), listOf(Muscle.HAMSTRINGS, Muscle.CORE), true, 0.5),
        bb("front_squat", "Фронтальные приседания", listOf(Muscle.QUADS), listOf(Muscle.GLUTES, Muscle.CORE), true, 0.4),
        bb("deadlift", "Становая тяга", listOf(Muscle.HAMSTRINGS, Muscle.GLUTES, Muscle.BACK), listOf(Muscle.QUADS, Muscle.CORE), true, 0.7),
        bb("rdl", "Румынская тяга", listOf(Muscle.HAMSTRINGS, Muscle.GLUTES), listOf(Muscle.BACK), true, 0.5),
        bb("bench", "Жим штанги лёжа", listOf(Muscle.CHEST), listOf(Muscle.TRICEPS, Muscle.SHOULDERS), false, 0.45),
        bb("incline_bench", "Жим штанги на наклонной", listOf(Muscle.CHEST, Muscle.SHOULDERS), listOf(Muscle.TRICEPS), false, 0.35),
        bb("ohp", "Жим штанги стоя", listOf(Muscle.SHOULDERS), listOf(Muscle.TRICEPS, Muscle.CORE), false, 0.3),
        bb("row", "Тяга штанги в наклоне", listOf(Muscle.BACK), listOf(Muscle.BICEPS), false, 0.4),
        bb("hip_thrust", "Ягодичный мост со штангой", listOf(Muscle.GLUTES), listOf(Muscle.HAMSTRINGS), true, 0.6),

        db("db_bench", "Жим гантелей лёжа", listOf(Muscle.CHEST), listOf(Muscle.TRICEPS, Muscle.SHOULDERS), compound = true, ratio = 0.16),
        db("db_incline", "Жим гантелей на наклонной", listOf(Muscle.CHEST, Muscle.SHOULDERS), listOf(Muscle.TRICEPS), compound = true, ratio = 0.13),
        db("db_shoulder", "Жим гантелей сидя", listOf(Muscle.SHOULDERS), listOf(Muscle.TRICEPS), compound = true, ratio = 0.11),
        db("db_row", "Тяга гантели в наклоне", listOf(Muscle.BACK), listOf(Muscle.BICEPS), compound = true, ratio = 0.2),
        db("goblet_squat", "Гоблет-присед", listOf(Muscle.QUADS, Muscle.GLUTES), lower = true, compound = true, ratio = 0.22),
        db("db_lunge", "Выпады с гантелями", listOf(Muscle.QUADS, Muscle.GLUTES), listOf(Muscle.HAMSTRINGS), lower = true, compound = true, ratio = 0.1),
        db("bulgarian", "Болгарские сплит-приседания", listOf(Muscle.QUADS, Muscle.GLUTES), lower = true, compound = true, ratio = 0.08),
        db("db_curl", "Подъём гантелей на бицепс", listOf(Muscle.BICEPS), ratio = 0.08),
        db("hammer_curl", "Молотки", listOf(Muscle.BICEPS), ratio = 0.09),
        db("lateral_raise", "Махи гантелями в стороны", listOf(Muscle.SHOULDERS), ratio = 0.05),
        db("db_overhead_ext", "Французский жим гантели", listOf(Muscle.TRICEPS), ratio = 0.1),

        mc("leg_press", "Жим ногами", Equipment.MACHINE, listOf(Muscle.QUADS, Muscle.GLUTES), lower = true, compound = true, ratio = 1.0),
        mc("hack_squat", "Гакк-присед", Equipment.MACHINE, listOf(Muscle.QUADS), listOf(Muscle.GLUTES), lower = true, compound = true, ratio = 0.6),
        mc("leg_ext", "Разгибание ног", Equipment.MACHINE, listOf(Muscle.QUADS), lower = true, ratio = 0.3),
        mc("leg_curl", "Сгибание ног", Equipment.MACHINE, listOf(Muscle.HAMSTRINGS), lower = true, ratio = 0.25),
        mc("calf_raise", "Подъём на носки", Equipment.MACHINE, listOf(Muscle.CALVES), lower = true, ratio = 0.6),
        mc("lat_pulldown", "Тяга верхнего блока", Equipment.CABLE, listOf(Muscle.BACK), listOf(Muscle.BICEPS), compound = true, ratio = 0.45),
        mc("cable_row", "Тяга горизонтального блока", Equipment.CABLE, listOf(Muscle.BACK), listOf(Muscle.BICEPS), compound = true, ratio = 0.45),
        mc("chest_press", "Жим в тренажёре", Equipment.MACHINE, listOf(Muscle.CHEST), listOf(Muscle.TRICEPS), compound = true, ratio = 0.4),
        mc("pec_deck", "Сведение рук в тренажёре", Equipment.MACHINE, listOf(Muscle.CHEST), ratio = 0.25),
        mc("pushdown", "Разгибание рук на блоке", Equipment.CABLE, listOf(Muscle.TRICEPS), step = 2.5, ratio = 0.2),
        mc("face_pull", "Тяга каната к лицу", Equipment.CABLE, listOf(Muscle.SHOULDERS, Muscle.BACK), step = 2.5, ratio = 0.15),
        mc("cable_curl", "Сгибание рук на блоке", Equipment.CABLE, listOf(Muscle.BICEPS), step = 2.5, ratio = 0.15),

        // Shares of body weight: pull-ups and dips move nearly all of it, push-ups about 65 % (Suprak et al. 2011),
        // back extensions the upper body, hanging leg raises the legs.
        bw("pullup", "Подтягивания", listOf(Muscle.BACK), listOf(Muscle.BICEPS), share = 1.0, assisted = true),
        bw("dip", "Отжимания на брусьях", listOf(Muscle.CHEST, Muscle.TRICEPS), listOf(Muscle.SHOULDERS), share = 0.95, assisted = true),
        bw("pushup", "Отжимания от пола", listOf(Muscle.CHEST), listOf(Muscle.TRICEPS, Muscle.SHOULDERS), share = 0.65),
        bw("back_ext", "Гиперэкстензия", listOf(Muscle.HAMSTRINGS, Muscle.GLUTES), listOf(Muscle.BACK), lower = true, share = 0.5),
        bw("leg_raise", "Подъём ног в висе", listOf(Muscle.CORE), compound = false, share = 0.33),
        bw("plank", "Планка", listOf(Muscle.CORE), kind = LoadKind.TIMED, compound = false),
    )

    private val byId = all.associateBy { it.id }

    operator fun get(id: String): Exercise = byId[id] ?: error("Unknown exercise $id")
    fun find(id: String): Exercise? = byId[id]
}
