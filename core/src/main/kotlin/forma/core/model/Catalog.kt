package forma.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Muscle(val ru: String) {
    CHEST("Грудь"), BACK("Спина"), SHOULDERS("Плечи"), BICEPS("Бицепс"), TRICEPS("Трицепс"),
    QUADS("Квадрицепс"), HAMSTRINGS("Бицепс бедра"), GLUTES("Ягодицы"), CALVES("Икры"), CORE("Пресс и кор"),
}

@Serializable
enum class Equipment(val ru: String) {
    BARBELL("Штанга"), DUMBBELL("Гантели"), MACHINE("Тренажёр"), CABLE("Блок"), BODYWEIGHT("Свой вес"), KETTLEBELL("Гиря"),
}

/** How the load is counted: external weight, body weight plus extra weight, or seconds of holding. */
@Serializable
enum class LoadKind { EXTERNAL, BODYWEIGHT_PLUS, TIMED }

/** Movement pattern: exercises with the same pattern can stand in for each other. */
@Serializable
enum class Pattern(val ru: String) {
    SQUAT("Приседания"), HINGE("Тяги и наклоны"), LUNGE("Выпады"), HORIZONTAL_PUSH("Жимы лёжа"), INCLINE_PUSH("Жимы на наклонной"),
    VERTICAL_PUSH("Жимы вверх"), DIP("Брусья"), HORIZONTAL_PULL("Тяги к поясу"), VERTICAL_PULL("Тяги сверху"),
    CHEST_FLY("Разведения и сведения"), LATERAL_RAISE("Махи"), REAR_DELT("Задняя дельта"), SHRUG("Шраги"),
    CURL("Сгибания на бицепс"), TRICEPS_EXT("Разгибания на трицепс"), KNEE_EXTENSION("Разгибания ног"),
    KNEE_FLEXION("Сгибания ног"), GLUTE("Ягодицы"), CALF("Икры"), CORE("Пресс"), OTHER("Другое"),
}

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
    /** Bar weight to show plates for, if this is a barbell lift on a standard bar. */
    val bar: Double? = null,
    /** Finer step below [fineBelow] kg (light dumbbells usually go 1 kg at a time). */
    val fineStep: Double? = null,
    val fineBelow: Double = 0.0,
    /** Body-weight exercises: share of body weight that is moved (push-ups ≈ 0.65, pull-ups ≈ 1). */
    val bodyShare: Double = 1.0,
    /** Body-weight exercises that can be made easier with an assisted machine or a band (negative extra weight). */
    val assisted: Boolean = false,
    val pattern: Pattern = Pattern.OTHER,
    /** Other names people search by: short forms, gym slang, English. */
    val aliases: List<String> = emptyList(),
    /** Made by the user. */
    val custom: Boolean = false,
    /** A removed own exercise: its history still opens, it is no longer offered. */
    val archived: Boolean = false,
) {
    val perHand: Boolean get() = equipment == Equipment.DUMBBELL

    /** The smallest practical change of the load around [weight]. */
    fun stepAt(weight: Double): Double = if (fineStep != null && weight < fineBelow - 1e-9) fineStep else step
}

object Catalog {
    private val CH = Muscle.CHEST
    private val BK = Muscle.BACK
    private val SH = Muscle.SHOULDERS
    private val BI = Muscle.BICEPS
    private val TR = Muscle.TRICEPS
    private val QU = Muscle.QUADS
    private val HA = Muscle.HAMSTRINGS
    private val GL = Muscle.GLUTES
    private val CA = Muscle.CALVES
    private val CO = Muscle.CORE

    private fun aka(s: String) = if (s.isBlank()) emptyList() else s.split('|').map { it.trim() }

    /** Barbell lifts; [bar] null for bars of no standard weight (EZ, trap bar, T-bar). */
    private fun bb(
        id: String, name: String, pt: Pattern, p: List<Muscle>, s: List<Muscle>, lower: Boolean, ratio: Double,
        aliases: String = "", bar: Double? = 20.0, compound: Boolean = true,
    ) = Exercise(
        id, name, Equipment.BARBELL, p, s, step = 2.5, load = LoadKind.EXTERNAL, lowerBody = lower, compound = compound,
        startRatio = ratio, bar = bar, pattern = pt, aliases = aka(aliases),
    )

    private fun db(
        id: String, name: String, pt: Pattern, p: List<Muscle>, s: List<Muscle> = emptyList(), lower: Boolean = false,
        compound: Boolean = false, ratio: Double, aliases: String = "",
    ) = Exercise(
        id, name, Equipment.DUMBBELL, p, s, step = 2.0, load = LoadKind.EXTERNAL, lowerBody = lower, compound = compound,
        startRatio = ratio, fineStep = 1.0, fineBelow = 10.0, pattern = pt, aliases = aka(aliases),
    )

    private fun mc(
        id: String, name: String, eq: Equipment, pt: Pattern, p: List<Muscle>, s: List<Muscle> = emptyList(), step: Double = 5.0,
        lower: Boolean = false, compound: Boolean = false, ratio: Double, aliases: String = "",
    ) = Exercise(
        id, name, eq, p, s, step = step, load = LoadKind.EXTERNAL, lowerBody = lower, compound = compound, startRatio = ratio,
        pattern = pt, aliases = aka(aliases),
    )

    private fun kb(id: String, name: String, pt: Pattern, p: List<Muscle>, s: List<Muscle>, lower: Boolean, ratio: Double, aliases: String = "") =
        Exercise(
            id, name, Equipment.KETTLEBELL, p, s, step = 4.0, load = LoadKind.EXTERNAL, lowerBody = lower, compound = true,
            startRatio = ratio, pattern = pt, aliases = aka(aliases),
        )

    private fun bw(
        id: String, name: String, pt: Pattern, p: List<Muscle>, s: List<Muscle> = emptyList(), kind: LoadKind = LoadKind.BODYWEIGHT_PLUS,
        lower: Boolean = false, compound: Boolean = true, share: Double = 1.0, assisted: Boolean = false, aliases: String = "",
    ) = Exercise(
        id, name, Equipment.BODYWEIGHT, p, s, step = 2.5, load = kind, lowerBody = lower, compound = compound, startRatio = 0.0,
        bodyShare = share, assisted = assisted, pattern = pt, aliases = aka(aliases),
    )

    val builtIn: List<Exercise> = listOf(
        // ---------- squats ----------
        bb("squat", "Приседания со штангой", Pattern.SQUAT, listOf(QU, GL), listOf(HA, CO), true, 0.5,
            "присед|приседания|приседы|присед со штангой на спине|back squat|squat"),
        bb("front_squat", "Фронтальные приседания", Pattern.SQUAT, listOf(QU), listOf(GL, CO), true, 0.4,
            "фронтальный присед|присед со штангой на груди|front squat"),
        bb("pause_squat", "Приседания с паузой", Pattern.SQUAT, listOf(QU, GL), listOf(HA, CO), true, 0.4, "присед с паузой|pause squat"),
        bb("box_squat", "Приседания на ящик", Pattern.SQUAT, listOf(QU, GL), listOf(HA, CO), true, 0.45, "присед на бокс|box squat"),
        bb("zercher_squat", "Присед Зерхера", Pattern.SQUAT, listOf(QU, GL), listOf(CO, BK), true, 0.35, "зерхер|zercher"),
        mc("smith_squat", "Приседания в Смите", Equipment.MACHINE, Pattern.SQUAT, listOf(QU, GL), listOf(HA), step = 2.5, lower = true,
            compound = true, ratio = 0.45, aliases = "смит|машина смита|smith squat"),
        mc("hack_squat", "Гакк-присед", Equipment.MACHINE, Pattern.SQUAT, listOf(QU), listOf(GL), lower = true, compound = true,
            ratio = 0.6, aliases = "гак|гакк|hack squat"),
        mc("pendulum_squat", "Маятниковый присед", Equipment.MACHINE, Pattern.SQUAT, listOf(QU), listOf(GL), lower = true,
            compound = true, ratio = 0.5, aliases = "маятник|pendulum squat"),
        mc("belt_squat", "Присед с поясом в тренажёре", Equipment.MACHINE, Pattern.SQUAT, listOf(QU, GL), lower = true, compound = true,
            ratio = 0.5, aliases = "белт сквот|belt squat"),
        mc("leg_press", "Жим ногами", Equipment.MACHINE, Pattern.SQUAT, listOf(QU, GL), lower = true, compound = true, ratio = 1.0,
            aliases = "платформа|жим платформы|leg press"),
        db("goblet_squat", "Гоблет-присед", Pattern.SQUAT, listOf(QU, GL), lower = true, compound = true, ratio = 0.22,
            aliases = "кубковый присед|присед с гантелью у груди|присед с гирей|goblet squat"),
        db("db_squat", "Приседания с гантелями", Pattern.SQUAT, listOf(QU, GL), listOf(HA), lower = true, compound = true, ratio = 0.15,
            aliases = "присед с гантелями|dumbbell squat"),
        bw("air_squat", "Приседания без веса", Pattern.SQUAT, listOf(QU, GL), lower = true, share = 0.7,
            aliases = "приседания с собственным весом|присед без веса|bodyweight squat|air squat"),
        bw("pistol", "Пистолетик", Pattern.SQUAT, listOf(QU, GL), listOf(CO), lower = true, share = 0.75,
            aliases = "пистолет|присед на одной ноге|pistol squat"),
        bw("sissy_squat", "Сисси-присед", Pattern.KNEE_EXTENSION, listOf(QU), lower = true, compound = false, share = 0.6,
            aliases = "сисси|sissy squat"),

        // ---------- hinges ----------
        bb("deadlift", "Становая тяга", Pattern.HINGE, listOf(HA, GL, BK), listOf(QU, CO), true, 0.7,
            "становая|становуха|тяга|классическая становая|deadlift"),
        bb("sumo_deadlift", "Становая тяга сумо", Pattern.HINGE, listOf(GL, QU, HA), listOf(BK, CO), true, 0.7, "сумо|sumo deadlift"),
        bb("trap_bar_deadlift", "Тяга с трэп-грифом", Pattern.HINGE, listOf(QU, GL, HA), listOf(BK, CO), true, 0.75,
            "трэп гриф|трап гриф|гексагональный гриф|hex bar|trap bar deadlift", bar = null),
        bb("deficit_deadlift", "Становая с дефицитом", Pattern.HINGE, listOf(HA, GL, BK), listOf(QU), true, 0.6, "становая с подставки|deficit deadlift"),
        bb("rack_pull", "Тяга с плинтов", Pattern.HINGE, listOf(BK, GL, HA), listOf(CO), true, 0.8, "рэк пул|тяга со стоек|rack pull"),
        bb("rdl", "Румынская тяга", Pattern.HINGE, listOf(HA, GL), listOf(BK), true, 0.5, "румынка|rdl|romanian deadlift"),
        bb("sldl", "Становая на прямых ногах", Pattern.HINGE, listOf(HA, GL), listOf(BK), true, 0.45, "мёртвая тяга|stiff leg deadlift|sldl"),
        bb("good_morning", "Гуд монинг", Pattern.HINGE, listOf(HA, GL), listOf(BK), true, 0.3, "наклоны со штангой|доброе утро|good morning"),
        bb("power_clean", "Взятие на грудь", Pattern.HINGE, listOf(GL, QU, BK), listOf(HA, SH), true, 0.35, "взятие|power clean|clean"),
        db("db_rdl", "Румынская тяга с гантелями", Pattern.HINGE, listOf(HA, GL), listOf(BK), lower = true, compound = true, ratio = 0.2,
            aliases = "румынка с гантелями|dumbbell rdl"),
        db("single_leg_rdl", "Румынская тяга на одной ноге", Pattern.HINGE, listOf(HA, GL), listOf(CO), lower = true, compound = true,
            ratio = 0.12, aliases = "тяга на одной ноге|single leg rdl"),
        kb("kb_swing", "Махи гирей", Pattern.HINGE, listOf(GL, HA), listOf(BK, CO), true, 0.2, "свинг|swing|kettlebell swing"),
        mc("pull_through", "Протяжка с нижнего блока", Equipment.CABLE, Pattern.HINGE, listOf(GL, HA), step = 2.5, lower = true,
            compound = true, ratio = 0.25, aliases = "протяжка|pull through"),
        bw("back_ext", "Гиперэкстензия", Pattern.HINGE, listOf(HA, GL), listOf(BK), lower = true, share = 0.5,
            aliases = "гипер|гиперэкстензия|разгибание спины|back extension"),
        bw("reverse_hyper", "Обратная гиперэкстензия", Pattern.GLUTE, listOf(GL, HA), lower = true, compound = false, share = 0.35,
            aliases = "обратная гипер|reverse hyper"),

        // ---------- lunges ----------
        db("db_lunge", "Выпады с гантелями", Pattern.LUNGE, listOf(QU, GL), listOf(HA), lower = true, compound = true, ratio = 0.1,
            aliases = "выпады|lunge"),
        db("bulgarian", "Болгарские сплит-приседания", Pattern.LUNGE, listOf(QU, GL), lower = true, compound = true, ratio = 0.08,
            aliases = "болгарские выпады|болгарка|bulgarian split squat"),
        db("walking_lunge", "Выпады в ходьбе", Pattern.LUNGE, listOf(QU, GL), listOf(HA), lower = true, compound = true, ratio = 0.1,
            aliases = "шагающие выпады|walking lunge"),
        db("reverse_lunge", "Обратные выпады", Pattern.LUNGE, listOf(GL, QU), listOf(HA), lower = true, compound = true, ratio = 0.1,
            aliases = "выпады назад|reverse lunge"),
        db("split_squat", "Сплит-приседания", Pattern.LUNGE, listOf(QU, GL), lower = true, compound = true, ratio = 0.1,
            aliases = "статические выпады|split squat"),
        db("step_up", "Зашагивания на тумбу", Pattern.LUNGE, listOf(QU, GL), lower = true, compound = true, ratio = 0.1,
            aliases = "зашагивания|степ ап|step up"),
        bb("bb_lunge", "Выпады со штангой", Pattern.LUNGE, listOf(QU, GL), listOf(HA, CO), true, 0.25, "выпады со штангой на плечах|barbell lunge"),
        bw("bw_lunge", "Выпады без веса", Pattern.LUNGE, listOf(QU, GL), lower = true, share = 0.6, aliases = "выпады с собственным весом"),

        // ---------- presses: chest ----------
        bb("bench", "Жим штанги лёжа", Pattern.HORIZONTAL_PUSH, listOf(CH), listOf(TR, SH), false, 0.45,
            "жим лёжа|жим|скамья|bench|bench press"),
        bb("close_grip_bench", "Жим лёжа узким хватом", Pattern.HORIZONTAL_PUSH, listOf(TR, CH), listOf(SH), false, 0.4,
            "узкий жим|жим узким|close grip bench"),
        bb("pause_bench", "Жим лёжа с паузой", Pattern.HORIZONTAL_PUSH, listOf(CH), listOf(TR, SH), false, 0.4, "жим с паузой|pause bench"),
        bb("floor_press", "Жим лёжа с пола", Pattern.HORIZONTAL_PUSH, listOf(CH, TR), listOf(SH), false, 0.4, "жим с пола|floor press"),
        bb("decline_bench", "Жим лёжа головой вниз", Pattern.HORIZONTAL_PUSH, listOf(CH), listOf(TR), false, 0.45,
            "жим на скамье с отрицательным наклоном|decline bench"),
        mc("smith_bench", "Жим лёжа в Смите", Equipment.MACHINE, Pattern.HORIZONTAL_PUSH, listOf(CH), listOf(TR, SH), step = 2.5,
            compound = true, ratio = 0.4, aliases = "смит жим|smith bench"),
        mc("chest_press", "Жим в тренажёре", Equipment.MACHINE, Pattern.HORIZONTAL_PUSH, listOf(CH), listOf(TR), compound = true,
            ratio = 0.4, aliases = "жим от груди в тренажёре|хаммер|chest press"),
        db("db_bench", "Жим гантелей лёжа", Pattern.HORIZONTAL_PUSH, listOf(CH), listOf(TR, SH), compound = true, ratio = 0.16,
            aliases = "жим гантелей|dumbbell bench press"),
        bw("pushup", "Отжимания от пола", Pattern.HORIZONTAL_PUSH, listOf(CH), listOf(TR, SH), share = 0.65,
            aliases = "отжимания|отжимания от пола|push up|pushup"),
        bw("knee_pushup", "Отжимания с колен", Pattern.HORIZONTAL_PUSH, listOf(CH), listOf(TR, SH), share = 0.5, aliases = "отжимания с коленей|knee push up"),
        bw("incline_pushup", "Отжимания от скамьи", Pattern.HORIZONTAL_PUSH, listOf(CH), listOf(TR, SH), share = 0.45,
            aliases = "отжимания с упором на скамью|incline push up"),
        bw("decline_pushup", "Отжимания с ногами на возвышении", Pattern.INCLINE_PUSH, listOf(CH, SH), listOf(TR), share = 0.7,
            aliases = "отжимания ноги на скамье|decline push up"),
        bw("diamond_pushup", "Отжимания узким упором", Pattern.HORIZONTAL_PUSH, listOf(TR, CH), listOf(SH), share = 0.65,
            aliases = "алмазные отжимания|отжимания на трицепс|diamond push up"),

        // ---------- presses: incline ----------
        bb("incline_bench", "Жим штанги на наклонной", Pattern.INCLINE_PUSH, listOf(CH, SH), listOf(TR), false, 0.35,
            "наклонный жим|жим на наклонной скамье|incline bench"),
        db("db_incline", "Жим гантелей на наклонной", Pattern.INCLINE_PUSH, listOf(CH, SH), listOf(TR), compound = true, ratio = 0.13,
            aliases = "наклонный жим гантелей|incline dumbbell press"),
        mc("smith_incline", "Жим на наклонной в Смите", Equipment.MACHINE, Pattern.INCLINE_PUSH, listOf(CH, SH), listOf(TR), step = 2.5,
            compound = true, ratio = 0.35, aliases = "наклонный жим в смите|smith incline"),
        mc("incline_machine_press", "Жим на наклонной в тренажёре", Equipment.MACHINE, Pattern.INCLINE_PUSH, listOf(CH, SH), listOf(TR),
            compound = true, ratio = 0.3, aliases = "наклонный жим в тренажёре"),

        // ---------- presses: overhead ----------
        bb("ohp", "Жим штанги стоя", Pattern.VERTICAL_PUSH, listOf(SH), listOf(TR, CO), false, 0.3,
            "армейский жим|жим стоя|жим над головой|ohp|overhead press|military press"),
        bb("seated_ohp", "Жим штанги сидя", Pattern.VERTICAL_PUSH, listOf(SH), listOf(TR), false, 0.28, "жим сидя|seated press"),
        bb("push_press", "Швунг жимовой", Pattern.VERTICAL_PUSH, listOf(SH), listOf(TR, QU), false, 0.35, "швунг|push press"),
        db("db_shoulder", "Жим гантелей сидя", Pattern.VERTICAL_PUSH, listOf(SH), listOf(TR), compound = true, ratio = 0.11,
            aliases = "жим гантелей вверх|жим гантелей над головой|dumbbell shoulder press"),
        db("arnold_press", "Жим Арнольда", Pattern.VERTICAL_PUSH, listOf(SH), listOf(TR), compound = true, ratio = 0.1, aliases = "арнольд|arnold press"),
        mc("machine_shoulder_press", "Жим вверх в тренажёре", Equipment.MACHINE, Pattern.VERTICAL_PUSH, listOf(SH), listOf(TR),
            compound = true, ratio = 0.3, aliases = "жим на плечи в тренажёре|shoulder press machine"),
        bw("pike_pushup", "Отжимания пикой", Pattern.VERTICAL_PUSH, listOf(SH), listOf(TR), share = 0.55, aliases = "пайк|pike push up"),
        bw("handstand_pushup", "Отжимания в стойке на руках", Pattern.VERTICAL_PUSH, listOf(SH, TR), share = 0.9,
            aliases = "отжимания в стойке|hspu|handstand push up"),

        // ---------- dips ----------
        bw("dip", "Отжимания на брусьях", Pattern.DIP, listOf(CH, TR), listOf(SH), share = 0.95, assisted = true,
            aliases = "брусья|отжимания на брусьях в гравитроне|гравитрон|dips"),
        bw("bench_dip", "Обратные отжимания от скамьи", Pattern.DIP, listOf(TR), listOf(CH, SH), share = 0.5,
            aliases = "отжимания от скамьи сзади|bench dip"),

        // ---------- rows ----------
        bb("row", "Тяга штанги в наклоне", Pattern.HORIZONTAL_PULL, listOf(BK), listOf(BI), false, 0.4,
            "тяга в наклоне|тяга штанги к поясу|barbell row|bent over row"),
        bb("pendlay_row", "Тяга Пендли", Pattern.HORIZONTAL_PULL, listOf(BK), listOf(BI), false, 0.4, "пендли|pendlay row"),
        bb("tbar_row", "Тяга Т-грифа", Pattern.HORIZONTAL_PULL, listOf(BK), listOf(BI), false, 0.35, "т гриф|т-гриф|t bar row", bar = null),
        db("db_row", "Тяга гантели в наклоне", Pattern.HORIZONTAL_PULL, listOf(BK), listOf(BI), compound = true, ratio = 0.2,
            aliases = "тяга гантели одной рукой|dumbbell row|one arm row"),
        db("chest_supported_row", "Тяга гантелей с упором грудью", Pattern.HORIZONTAL_PULL, listOf(BK), listOf(BI, SH), compound = true,
            ratio = 0.15, aliases = "тяга лёжа на наклонной|chest supported row|seal row"),
        mc("cable_row", "Тяга горизонтального блока", Equipment.CABLE, Pattern.HORIZONTAL_PULL, listOf(BK), listOf(BI), compound = true,
            ratio = 0.45, aliases = "горизонтальная тяга|тяга нижнего блока|тяга к поясу на блоке|seated cable row"),
        mc("machine_row", "Тяга в рычажном тренажёре", Equipment.MACHINE, Pattern.HORIZONTAL_PULL, listOf(BK), listOf(BI), compound = true,
            ratio = 0.4, aliases = "рычажная тяга|хаммер тяга|machine row"),
        bw("inverted_row", "Австралийские подтягивания", Pattern.HORIZONTAL_PULL, listOf(BK), listOf(BI), share = 0.6,
            aliases = "австралийские|горизонтальные подтягивания|inverted row"),

        // ---------- pull-ups and pulldowns ----------
        bw("pullup", "Подтягивания", Pattern.VERTICAL_PULL, listOf(BK), listOf(BI), share = 1.0, assisted = true,
            aliases = "подтягивания прямым хватом|турник|подтягивания в гравитроне|гравитрон|pull up|pullup"),
        bw("chinup", "Подтягивания обратным хватом", Pattern.VERTICAL_PULL, listOf(BK, BI), share = 1.0, assisted = true,
            aliases = "обратный хват|chin up|chinup"),
        bw("neutral_pullup", "Подтягивания параллельным хватом", Pattern.VERTICAL_PULL, listOf(BK), listOf(BI), share = 1.0,
            assisted = true, aliases = "нейтральный хват|neutral grip pull up"),
        mc("lat_pulldown", "Тяга верхнего блока", Equipment.CABLE, Pattern.VERTICAL_PULL, listOf(BK), listOf(BI), compound = true,
            ratio = 0.45, aliases = "верхний блок|вертикальная тяга|тяга к груди|lat pulldown"),
        mc("close_pulldown", "Тяга верхнего блока узким хватом", Equipment.CABLE, Pattern.VERTICAL_PULL, listOf(BK), listOf(BI),
            compound = true, ratio = 0.45, aliases = "узкая тяга верхнего блока|close grip pulldown"),
        mc("straight_arm_pulldown", "Пуловер на верхнем блоке", Equipment.CABLE, Pattern.VERTICAL_PULL, listOf(BK), step = 2.5, ratio = 0.2,
            aliases = "тяга прямыми руками|straight arm pulldown"),
        db("db_pullover", "Пуловер с гантелью", Pattern.VERTICAL_PULL, listOf(BK, CH), ratio = 0.12, aliases = "пуловер|pullover"),

        // ---------- flyes ----------
        mc("pec_deck", "Сведение рук в тренажёре", Equipment.MACHINE, Pattern.CHEST_FLY, listOf(CH), ratio = 0.25,
            aliases = "бабочка|баттерфляй|pec deck|butterfly"),
        db("db_fly", "Разведение гантелей лёжа", Pattern.CHEST_FLY, listOf(CH), ratio = 0.06, aliases = "разводка|разводы|dumbbell fly"),
        db("incline_fly", "Разведение гантелей на наклонной", Pattern.CHEST_FLY, listOf(CH, SH), ratio = 0.05, aliases = "наклонная разводка|incline fly"),
        mc("cable_fly", "Сведение рук в кроссовере", Equipment.CABLE, Pattern.CHEST_FLY, listOf(CH), step = 2.5, ratio = 0.1,
            aliases = "кроссовер|сведения на блоках|cable fly|crossover"),

        // ---------- shoulders ----------
        db("lateral_raise", "Махи гантелями в стороны", Pattern.LATERAL_RAISE, listOf(SH), ratio = 0.05,
            aliases = "махи|махи в стороны|разведения в стороны|боковые махи|lateral raise"),
        mc("cable_lateral", "Отведение руки на нижнем блоке", Equipment.CABLE, Pattern.LATERAL_RAISE, listOf(SH), step = 2.5, ratio = 0.05,
            aliases = "махи на блоке|cable lateral raise"),
        mc("machine_lateral", "Махи в стороны в тренажёре", Equipment.MACHINE, Pattern.LATERAL_RAISE, listOf(SH), step = 2.5, ratio = 0.12,
            aliases = "махи в тренажёре|lateral raise machine"),
        db("front_raise", "Подъём гантелей перед собой", Pattern.LATERAL_RAISE, listOf(SH), ratio = 0.06, aliases = "махи вперёд|front raise"),
        bb("upright_row", "Тяга штанги к подбородку", Pattern.LATERAL_RAISE, listOf(SH), listOf(BK), false, 0.25,
            "протяжка|тяга к подбородку|upright row", compound = false),
        mc("face_pull", "Тяга каната к лицу", Equipment.CABLE, Pattern.REAR_DELT, listOf(SH, BK), step = 2.5, ratio = 0.15,
            aliases = "фейс пул|тяга к лицу|face pull"),
        db("reverse_fly", "Разведение гантелей в наклоне", Pattern.REAR_DELT, listOf(SH), listOf(BK), ratio = 0.04,
            aliases = "задняя дельта|махи в наклоне|reverse fly"),
        mc("reverse_pec_deck", "Обратная бабочка", Equipment.MACHINE, Pattern.REAR_DELT, listOf(SH), listOf(BK), step = 2.5, ratio = 0.15,
            aliases = "обратные разведения в тренажёре|reverse pec deck"),
        bb("bb_shrug", "Шраги со штангой", Pattern.SHRUG, listOf(BK), emptyList(), false, 0.6, "шраги|трапеции|shrug", compound = false),
        db("db_shrug", "Шраги с гантелями", Pattern.SHRUG, listOf(BK), ratio = 0.3, aliases = "шраги гантели|dumbbell shrug"),

        // ---------- biceps ----------
        db("db_curl", "Подъём гантелей на бицепс", Pattern.CURL, listOf(BI), ratio = 0.08, aliases = "бицепс гантели|сгибания рук|curl"),
        db("hammer_curl", "Молотки", Pattern.CURL, listOf(BI), ratio = 0.09, aliases = "молоток|хаммер|hammer curl"),
        db("incline_curl", "Сгибания на наклонной скамье", Pattern.CURL, listOf(BI), ratio = 0.06, aliases = "бицепс на наклонной|incline curl"),
        db("concentration_curl", "Концентрированные сгибания", Pattern.CURL, listOf(BI), ratio = 0.07, aliases = "концентрированный подъём|concentration curl"),
        bb("bb_curl", "Подъём штанги на бицепс", Pattern.CURL, listOf(BI), emptyList(), false, 0.25, "бицепс со штангой|barbell curl", compound = false),
        bb("ez_curl", "Подъём EZ-штанги на бицепс", Pattern.CURL, listOf(BI), emptyList(), false, 0.22, "изогнутый гриф|ez curl",
            bar = null, compound = false),
        bb("preacher_curl", "Сгибания на скамье Скотта", Pattern.CURL, listOf(BI), emptyList(), false, 0.18, "скотт|скамья скотта|preacher curl",
            bar = null, compound = false),
        mc("cable_curl", "Сгибание рук на блоке", Equipment.CABLE, Pattern.CURL, listOf(BI), step = 2.5, ratio = 0.15, aliases = "бицепс на блоке|cable curl"),
        mc("machine_curl", "Сгибание рук в тренажёре", Equipment.MACHINE, Pattern.CURL, listOf(BI), step = 2.5, ratio = 0.15,
            aliases = "бицепс в тренажёре|machine curl"),

        // ---------- triceps ----------
        mc("pushdown", "Разгибание рук на блоке", Equipment.CABLE, Pattern.TRICEPS_EXT, listOf(TR), step = 2.5, ratio = 0.2,
            aliases = "трицепс на блоке|разгибания на трицепс|pushdown"),
        mc("rope_pushdown", "Разгибание с канатом на блоке", Equipment.CABLE, Pattern.TRICEPS_EXT, listOf(TR), step = 2.5, ratio = 0.15,
            aliases = "канат трицепс|rope pushdown"),
        mc("cable_overhead_ext", "Разгибание из-за головы на блоке", Equipment.CABLE, Pattern.TRICEPS_EXT, listOf(TR), step = 2.5,
            ratio = 0.15, aliases = "французский жим на блоке|overhead cable extension"),
        db("db_overhead_ext", "Французский жим гантели", Pattern.TRICEPS_EXT, listOf(TR), ratio = 0.1,
            aliases = "разгибание гантели из-за головы|french press|overhead extension"),
        bb("skullcrusher", "Французский жим лёжа", Pattern.TRICEPS_EXT, listOf(TR), emptyList(), false, 0.2,
            "французский жим|французский жим ez|skull crusher|skullcrusher", bar = null, compound = false),
        db("kickback", "Разгибание гантели в наклоне", Pattern.TRICEPS_EXT, listOf(TR), ratio = 0.05, aliases = "кикбэк|kickback"),

        // ---------- legs: isolation ----------
        mc("leg_ext", "Разгибание ног", Equipment.MACHINE, Pattern.KNEE_EXTENSION, listOf(QU), lower = true, ratio = 0.3,
            aliases = "разгибания ног в тренажёре|leg extension"),
        mc("leg_curl", "Сгибание ног лёжа", Equipment.MACHINE, Pattern.KNEE_FLEXION, listOf(HA), lower = true, ratio = 0.25,
            aliases = "сгибание ног|сгибания ног в тренажёре|leg curl|lying leg curl"),
        mc("seated_leg_curl", "Сгибание ног сидя", Equipment.MACHINE, Pattern.KNEE_FLEXION, listOf(HA), lower = true, ratio = 0.25,
            aliases = "сидячие сгибания|seated leg curl"),
        bw("nordic_curl", "Нордические сгибания", Pattern.KNEE_FLEXION, listOf(HA), lower = true, compound = false, share = 0.5,
            aliases = "нордик|nordic curl|nordic hamstring"),
        bw("glute_ham_raise", "Сгибания на станке GHR", Pattern.KNEE_FLEXION, listOf(HA, GL), lower = true, share = 0.5,
            aliases = "ghr|glute ham raise"),
        bb("hip_thrust", "Ягодичный мост со штангой", Pattern.GLUTE, listOf(GL), listOf(HA), true, 0.6,
            "ягодичный мост|хип траст|мостик|hip thrust"),
        mc("machine_hip_thrust", "Ягодичный мост в тренажёре", Equipment.MACHINE, Pattern.GLUTE, listOf(GL), listOf(HA), lower = true,
            compound = true, ratio = 0.6, aliases = "хип траст в тренажёре|glute drive"),
        bw("glute_bridge", "Ягодичный мостик без веса", Pattern.GLUTE, listOf(GL), listOf(HA), lower = true, share = 0.5,
            aliases = "мостик|glute bridge"),
        mc("cable_kickback", "Отведение ноги назад на блоке", Equipment.CABLE, Pattern.GLUTE, listOf(GL), step = 2.5, lower = true, ratio = 0.08,
            aliases = "отведение ноги|махи ногой назад|cable kickback"),
        mc("hip_abduction", "Разведение ног в тренажёре", Equipment.MACHINE, Pattern.GLUTE, listOf(GL), lower = true, ratio = 0.3,
            aliases = "отведение бедра|разведения ног|abduction"),
        mc("calf_raise", "Подъём на носки стоя", Equipment.MACHINE, Pattern.CALF, listOf(CA), lower = true, ratio = 0.6,
            aliases = "икры|подъём на носки|calf raise"),
        mc("seated_calf", "Подъём на носки сидя", Equipment.MACHINE, Pattern.CALF, listOf(CA), lower = true, ratio = 0.3,
            aliases = "икры сидя|seated calf raise"),
        mc("leg_press_calf", "Подъём на носки в жиме ногами", Equipment.MACHINE, Pattern.CALF, listOf(CA), lower = true, ratio = 0.8,
            aliases = "икры в платформе|calf press"),
        db("db_calf", "Подъём на носки с гантелью", Pattern.CALF, listOf(CA), lower = true, ratio = 0.15, aliases = "икры с гантелью"),

        // ---------- core ----------
        bw("plank", "Планка", Pattern.CORE, listOf(CO), kind = LoadKind.TIMED, compound = false, aliases = "plank"),
        bw("side_plank", "Боковая планка", Pattern.CORE, listOf(CO), kind = LoadKind.TIMED, compound = false, aliases = "side plank"),
        bw("hollow_hold", "Лодочка", Pattern.CORE, listOf(CO), kind = LoadKind.TIMED, compound = false, aliases = "hollow hold|hollow body"),
        bw("leg_raise", "Подъём ног в висе", Pattern.CORE, listOf(CO), compound = false, share = 0.33,
            aliases = "подъём ног|подъёмы ног на турнике|hanging leg raise"),
        bw("knee_raise", "Подъём коленей в висе", Pattern.CORE, listOf(CO), compound = false, share = 0.2,
            aliases = "подъём коленей|колени к груди|knee raise|captain's chair"),
        bw("crunch", "Скручивания", Pattern.CORE, listOf(CO), compound = false, share = 0.3, aliases = "пресс|скручивания на полу|crunch"),
        bw("ab_wheel", "Ролик для пресса", Pattern.CORE, listOf(CO), listOf(BK), compound = false, share = 0.5, aliases = "ролик|колесо|ab wheel"),
        mc("cable_crunch", "Скручивания на блоке", Equipment.CABLE, Pattern.CORE, listOf(CO), step = 2.5, ratio = 0.3,
            aliases = "молитва|скручивания стоя на коленях|cable crunch"),
        mc("pallof_press", "Жим Паллофа", Equipment.CABLE, Pattern.CORE, listOf(CO), step = 2.5, ratio = 0.08, aliases = "паллоф|pallof press"),
    )

    @Volatile private var custom: Map<String, Exercise> = emptyMap()
    private val byId = builtIn.associateBy { it.id }

    /** Everything that can be logged: the built-in catalog and the user's own exercises (not the removed ones). */
    val all: List<Exercise> get() = builtIn + custom.values.filter { !it.archived }

    /** The user's own exercises (from storage or the cloud). Built-in ids cannot be replaced. */
    fun setCustom(list: List<Exercise>) {
        custom = list.filter { it.id !in byId }.associateBy { it.id }
    }

    val customExercises: List<Exercise> get() = custom.values.filter { !it.archived }

    operator fun get(id: String): Exercise = byId[id] ?: custom[id] ?: unknown(id)
    fun find(id: String): Exercise? = byId[id] ?: custom[id]

    /**
     * An exercise that is not in this catalog (a custom one deleted, or one from a newer version through the cloud):
     * logged history must still open, so it is shown under its id as a generic weighted exercise.
     */
    private fun unknown(id: String) = Exercise(id, id.removePrefix("custom_").replace('_', ' '), Equipment.MACHINE, listOf(Muscle.CORE), custom = true)

    /** An own exercise with sensible defaults from its equipment, main muscle and how the load is counted. */
    fun makeCustom(id: String, name: String, equipment: Equipment, muscle: Muscle, load: LoadKind): Exercise {
        val pattern = when (muscle) {
            Muscle.CHEST -> Pattern.HORIZONTAL_PUSH
            Muscle.BACK -> Pattern.HORIZONTAL_PULL
            Muscle.SHOULDERS -> Pattern.VERTICAL_PUSH
            Muscle.BICEPS -> Pattern.CURL
            Muscle.TRICEPS -> Pattern.TRICEPS_EXT
            Muscle.QUADS -> Pattern.SQUAT
            Muscle.HAMSTRINGS -> Pattern.HINGE
            Muscle.GLUTES -> Pattern.GLUTE
            Muscle.CALVES -> Pattern.CALF
            Muscle.CORE -> Pattern.CORE
        }
        val compound = pattern in setOf(Pattern.SQUAT, Pattern.HINGE, Pattern.HORIZONTAL_PUSH, Pattern.VERTICAL_PUSH, Pattern.HORIZONTAL_PULL)
        val kind = if (equipment == Equipment.BODYWEIGHT && load == LoadKind.EXTERNAL) LoadKind.BODYWEIGHT_PLUS else load
        val dumbbell = equipment == Equipment.DUMBBELL
        return Exercise(
            id, name.trim(), equipment, listOf(muscle),
            step = when (equipment) {
                Equipment.MACHINE -> 5.0
                Equipment.DUMBBELL -> 2.0
                Equipment.KETTLEBELL -> 4.0
                else -> 2.5
            },
            load = kind, lowerBody = muscle in setOf(Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES, Muscle.CALVES),
            compound = compound, startRatio = if (compound) 0.25 else 0.1,
            fineStep = if (dumbbell) 1.0 else null, fineBelow = if (dumbbell) 10.0 else 0.0,
            bodyShare = if (kind == LoadKind.BODYWEIGHT_PLUS) 0.6 else 1.0, pattern = pattern, custom = true,
        )
    }

    /** What can stand in for [ex]: the same movement first, then other exercises for the same main muscle. */
    fun alternatives(ex: Exercise): List<Exercise> {
        val same = all.filter { it.id != ex.id && it.pattern == ex.pattern && ex.pattern != Pattern.OTHER }
        val muscle = all.filter { it.id != ex.id && it !in same && it.primary.firstOrNull() == ex.primary.firstOrNull() }
        fun closeness(o: Exercise) = (if (o.equipment == ex.equipment) 0 else 1) * 2 + (if (o.compound == ex.compound) 0 else 1)
        return same.sortedBy(::closeness) + muscle.sortedBy(::closeness)
    }
}
