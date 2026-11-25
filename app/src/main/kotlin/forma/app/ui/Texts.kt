package forma.app.ui

import forma.core.model.Exercise
import forma.core.model.LoadKind
import forma.core.run.RunOutcome
import forma.core.strength.HoldReason
import forma.core.strength.Outcome
import forma.core.text.Ru
import kotlin.math.roundToInt

/** Short Russian sentences that explain what the app changed and why. */
object Texts {
    /**
     * After a set: why the next one is as it is (the reserve against the plan). Only the verdict: the next set's numbers
     * are in the fields right under the line (23 November: «Следующий подход: 82,5 кг × 6.» said them twice).
     */
    fun nextSet(ex: Exercise, n: forma.core.strength.NextSet): String {
        val r = n.reserve
        return when {
            // A hold: no weight and no reserve to mark, its time grows from one session to the next.
            ex.load == LoadKind.TIMED -> "Удержишь во всех подходах, в следующий раз будет дольше."
            n.calibrate && n.change > 0 -> "Легко, можно тяжелее."
            n.calibrate && n.change < 0 -> "Тяжеловато, чуть легче."
            // The set done is asked about right under this line (one tap); the chips lower down are for the next set.
            n.calibrate && r == null -> "Запас не отмечен, вес пока тот же."
            n.calibrate -> "Вес подходящий."
            n.change > 0 -> "Было легко, прибавляем."
            n.change < 0 && r != null && r < 0 -> "Не хватило повторов, убавляем."
            n.change < 0 -> "Было тяжело, убавляем."
            r == null -> "Запас не отмечен, вес тот же."
            r < 0 -> "Чуть не хватило. Вес тот же, попробуй добрать."
            r > n.plannedReserve -> "Легковато, но вес пока тот же."
            r < n.plannedReserve -> "Тяжеловато, но вес тот же."
            else -> "Как надо, вес тот же."
        }
    }

    /** "3 × 8 · 80 кг". */
    fun prescription(ex: Exercise, p: forma.core.strength.Prescription): String = when {
        ex.load == LoadKind.TIMED -> "${p.sets} × ${p.reps} с"
        p.calibrate || p.weight == null -> "${p.sets} ${Ru.plural(p.sets, "подход", "подхода", "подходов")}, вес подбираем"
        else -> "${p.sets} × ${p.reps}${if (p.top) "+" else ""} · ${weight(ex, p.weight!!)}"
    }

    /** The exercise's place in the day. */
    fun roleLine(role: forma.core.strength.SlotRole, ex: Exercise): String = when (role) {
        forma.core.strength.SlotRole.MAIN -> "Главное упражнение дня"
        forma.core.strength.SlotRole.SECONDARY -> "Второе по важности упражнение"
        forma.core.strength.SlotRole.ACCESSORY -> if (ex.compound) "Дополнительное упражнение" else "Упражнение на одну мышцу"
    }

    /** Why it stands where it does in the order: said after [roleLine] and a space, one sentence with it. */
    fun orderWhy(role: forma.core.strength.SlotRole, ex: Exercise): String = when (role) {
        // «в начале», not «первым»: most days have two main lifts (squat and bench), and the second is not first.
        forma.core.strength.SlotRole.MAIN -> "идёт в начале, пока силы свежие."
        forma.core.strength.SlotRole.SECONDARY -> "идёт после главного."
        forma.core.strength.SlotRole.ACCESSORY -> "идёт в конце" + if (ex.compound) " и добирает подходы." else ": усталость ему почти не мешает."
    }

    /** "Работают: грудь · немного трицепс, плечи" (one helper muscle reads right too: «спина · немного бицепс»). */
    fun muscles(ex: Exercise): String =
        "Работают: " + ex.primary.joinToString(", ") { it.ru.lowercase() } +
            if (ex.secondary.isNotEmpty()) " · немного " + ex.secondary.joinToString(", ") { it.ru.lowercase() } else ""

    /** «Начни с 40 кг», «Начни со своего веса», «Начни с помощью 20 кг»: a weight to start from, said as Russian has it. */
    fun startWith(ex: Exercise?, w: Double): String = when {
        ex?.load == LoadKind.BODYWEIGHT_PLUS && w == 0.0 -> "Начни со своего веса"
        ex?.load == LoadKind.BODYWEIGHT_PLUS && w < 0 -> "Начни с помощью ${Ru.kg(-w)} кг"
        else -> "Начни с ${weight(ex, w)}"
    }

    fun weight(ex: Exercise?, w: Double): String = when (ex?.load) {
        LoadKind.BODYWEIGHT_PLUS -> when {
            w > 0 -> "+${Ru.kg(w)} кг"
            w < 0 -> "помощь ${Ru.kg(-w)} кг"
            else -> "свой вес"
        }
        LoadKind.TIMED -> ""
        // Dumbbells: the weight of one, two of them ("20 кг ×2").
        else -> "${Ru.kg(w)} кг" + if (ex?.perHand == true) " ×2" else ""
    }

    /**
     * A weight with its reps: "80 кг × 5", "+10 кг × 8"; dumbbells "по 20 кг × 10" (not "20 кг ×2 × 10"); a hold in
     * seconds, "30 с". [reps]: one number or a row of them ("10, 10, 9").
     */
    fun weightReps(ex: Exercise?, w: Double, reps: String): String = when {
        ex?.load == LoadKind.TIMED -> "$reps с"
        ex?.perHand == true && ex.load == LoadKind.EXTERNAL -> "по ${Ru.kg(w)} кг × $reps"
        else -> "${weight(ex, w)} × $reps"
    }

    fun weightReps(ex: Exercise?, w: Double, reps: Int): String = weightReps(ex, w, reps.toString())

    /** A one-rep estimate to half a kilo; dumbbells per hand, as [weight] says them ("30 кг ×2"). */
    fun oneRm(ex: Exercise?, e1rm: Double): String =
        "${Ru.kg((e1rm * 2).roundToInt() / 2.0)} кг" + if (ex?.perHand == true && ex.load == LoadKind.EXTERNAL) " ×2" else ""

    /**
     * A one-rep estimate as the weight to put on: for a lift with the body, the estimate less the share of the body it moves
     * (all of it on pull-ups, a third on leg raises), to half a kilo, as [weight] says it — «+32 кг», «помощь 19 кг» on an
     * assisted one, «свой вес». The estimate counts the body in: «112 кг на 1 раз» for pull-ups read as 112 kg on the belt,
     * and less the whole body, leg raises always read «свой вес». Other lifts as [oneRm] without the body.
     */
    fun oneRm(ex: Exercise?, e1rm: Double, bodyWeight: Double): String {
        if (ex == null || ex.load != LoadKind.BODYWEIGHT_PLUS) return oneRm(ex, e1rm)
        val extra = ((e1rm - ex.bodyShare * bodyWeight) * 2).roundToInt() / 2.0
        // Below the body on a lift without help (a lighter day's weigh-in): the body alone, not «помощь».
        return weight(ex, if (extra < 0 && !ex.assisted) 0.0 else extra)
    }

    private fun target(ex: Exercise?, w: Double, reps: Int): String = weightReps(ex, w, reps)

    fun outcome(o: Outcome, ex: Exercise?): String = when (o) {
        is Outcome.Calibrated -> "Рабочий вес подобран: ${target(ex, o.weight, o.reps)}."
        is Outcome.AddReps ->
            if (ex?.load == LoadKind.TIMED) "Все подходы по ${o.from} с получились. Теперь ${o.to} с."
            else "Все повторы сделаны. Теперь ${target(ex, o.weight, o.to)}."
        is Outcome.LowerReps ->
            if (ex?.load == LoadKind.TIMED) "Два раза подряд не получилось удержать ${o.from} с. Теперь ${o.to} с."
            else "Повторов не хватило. Теперь ${target(ex, o.weight, o.to)} вместо ${o.from}."
        is Outcome.AddWeight -> "Все повторы сделаны. Теперь ${target(ex, o.to, o.reps)} (+${Ru.kg(o.to - o.from)} кг)."
        is Outcome.Jump -> "Было очень легко. Сразу ${target(ex, o.to, o.reps)} (+${Ru.kg(o.to - o.from)} кг)."
        is Outcome.Hold -> when (o.reason) {
            HoldReason.NEAR_MISS -> "Почти получилось. Снова ${target(ex, o.weight, o.reps)}."
            HoldReason.FIRST_MISS -> "Повторов не хватило. Снова ${target(ex, o.weight, o.reps)}."
            HoldReason.FEWER_SETS -> "Сделаны не все подходы. Снова ${target(ex, o.weight, o.reps)}."
            HoldReason.LIGHTER -> "Вес был легче плана. Снова ${target(ex, o.weight, o.reps)}. Нет такого веса в зале? Поставь шаг веса: «План» → упражнение."
            HoldReason.CONSOLIDATE -> "Всё сделано, но на пределе. Снова ${target(ex, o.weight, o.reps)}."
            HoldReason.NOT_THE_WEIGHT -> "Меньший вес не помог. Оставляем ${target(ex, o.weight, o.reps)}."
            HoldReason.STOPPED_EARLY -> "Запас ещё был, а повторов меньше плана. Снова ${target(ex, o.weight, o.reps)}, сделай все повторы."
            HoldReason.INTRO -> "В первую неделю цикла недобор не в счёт. Снова ${target(ex, o.weight, o.reps)}."
            HoldReason.AT_TOP -> "Это максимум по плану, держим ${target(ex, o.weight, o.reps)}. Хочешь расти дальше? Замени на вариант сложнее."
            HoldReason.TAPER -> "Перед днём цели подходов меньше, вес тот же. После него продолжим с ${target(ex, o.weight, o.reps)}."
            HoldReason.TEST -> "Проверка максимума засчитана. Дальше прежний план: ${target(ex, o.weight, o.reps)}."
        }
        is Outcome.Reduce -> "Вес оказался тяжёлым. Теперь ${target(ex, o.to, o.reps)} (−${Ru.kg(o.from - o.to)} кг)."
        is Outcome.Reset -> "Три тренировки без роста. Теперь легче: ${target(ex, o.to, o.reps)}."
        Outcome.NotDone -> "Не сделано, план тот же."
        // «Тренировка полегче», as the workout's banner: the same outcome comes on a «Так себе» day, after being sick and
        // for legs before a race, none of them a lighter week; «прежние»: the weights from before it, not today's.
        Outcome.Deload -> "Тренировка полегче. Потом снова прежние рабочие веса."
    }

    /** Positive, neutral or negative tone for colouring. */
    fun tone(o: Outcome): Int = when (o) {
        is Outcome.AddReps, is Outcome.AddWeight, is Outcome.Jump, is Outcome.Calibrated -> 1
        is Outcome.Reduce, is Outcome.Reset, is Outcome.LowerReps -> -1
        else -> 0
    }

    /**
     * What a run changed; [manual]: entered by hand (no lap button to speak of). [plan]: the run as planned — one kept
     * shorter than the usual length (after the gym, a lighter day) moves no length, and says so instead of "still the same".
     */
    fun run(o: RunOutcome, manual: Boolean = false, plan: forma.core.run.RunWorkout? = null): String = when {
        manual && o == RunOutcome.PaceUnknown -> "Средний круг не вписан, темп не измерен. В следующий раз впиши его."
        manual && o == RunOutcome.TestNotCounted -> "Время контрольного не вписано, поэтому он не засчитан. Следующий через две недели."
        o is RunOutcome.RepPace && plan?.lapMeters != null -> {
            val lap = plan.lapMeters!!
            "Отрезки ты держишь с запасом. Дальше круг за ${Ru.one(forma.core.run.Track.lapSec(o.to, lap))} с " +
                "вместо ${Ru.one(forma.core.run.Track.lapSec(o.from, lap))} с."
        }
        o is RunOutcome.Same && plan != null && plan.workSeconds / 60 < o.minutes ->
            "Короткая пробежка длительность не меняет. Обычная " +
                (if (plan.type == forma.core.run.RunType.LONG) "длинная" else "лёгкая") + " — ${o.minutes} мин."
        else -> run(o)
    }

    private fun run(o: RunOutcome): String = when (o) {
        is RunOutcome.StageUp -> when (o.ladder) {
            "walk" -> "Следующая тренировка: неделя ${o.to / 3 + 1}, №${o.to % 3 + 1}. Бега становится больше."
            "intervals" -> "Интервалы получились. В следующий раз чуть сложнее."
            "goal" -> "Отрезки получились. Следующий шаг: ${o.to + 1} из ${forma.core.run.RunPlan.goalLadderSize}."
            else -> "Темповый бег получился. В следующий раз чуть сложнее."
        }
        // One line for every ladder: a repeat comes from slow reps, a rating over 8 or reps short, and the reason is
        // already said by «Тяжесть», «Отрезки были медленнее нужного…» or the reps count.
        is RunOutcome.StageRepeat -> "Пока не вышло. Повторим эту тренировку."
        is RunOutcome.StageDown -> "Шаг назад, в следующий раз будет легче."
        is RunOutcome.LadderTop -> "Это самый сложный шаг, закрепляем."
        is RunOutcome.TempoNext -> {
            val m = if (o.workSec % 60 == 0) "${o.workSec / 60}" else Ru.one(o.workSec / 60.0)
            if (o.split) "Темповый был слишком тяжёлым. В следующем те же минуты, но кусками короче: ${o.reps} × $m мин."
            else "Темповый получился. Следующий: ${o.reps} × $m мин."
        }
        is RunOutcome.LighterWeek -> "Лёгкая неделя: тренировка короче, шаг тот же."
        is RunOutcome.TempoPace ->
            if (o.softer) "Темповый бег был очень тяжёлым, и темп не держался. Теперь темповые пробежки на ${o.seconds} с/км спокойнее."
            else if (o.seconds > 0) "Темповый бег дался легко и в темпе. Теперь темп всего на ${o.seconds}\u00A0с/км спокойнее расчётного."
            else "Темповый бег дался легко и в темпе. Темп снова как по расчёту."
        RunOutcome.TooFastRepeat -> "Отрезки были заметно быстрее нужного. Повторим этот шаг, держи время круга или темп на км."
        RunOutcome.TooFast -> "Отрезки были быстрее нужного, поэтому шаг вперёд только один. Держи время круга."
        is RunOutcome.OwnScale -> "Лёгкий бег ты обычно оцениваешь на тяжесть ${5 + o.offset}–${6 + o.offset} из 10, хотя она должна быть до 5. " +
            "У тебя своя шкала, поэтому границы тяжести для тебя на ${o.offset} выше."
        is RunOutcome.EasyPaces ->
            if (o.to > o.from) "Пять из шести последних лёгких пробежек прошли заметно быстрее лёгкого темпа, и было легко. " +
                "Теперь темпы быстрее: форма ${Ru.one(o.from)} → ${Ru.one(o.to)}."
            else "Пять из шести последних лёгких пробежек шли медленнее лёгкого темпа, и было тяжело. " +
                "Теперь темпы спокойнее: форма ${Ru.one(o.from)} → ${Ru.one(o.to)}."
        RunOutcome.TooSlow -> "Отрезки были медленнее нужного больше чем на 3 %. Шаг вперёд будет, когда темп начнёт держаться, даже если тяжело."
        RunOutcome.PaceUnknown -> "Темп не измерен: кругов нет. На стадионе жми «Круг» на каждой линии."
        is RunOutcome.LoopShorter -> "Круг по GPS около ${o.loop} м, а в настройках ${o.set} м. Круги посчитаны по ${o.loop} м. " +
            "Поставь длину круга, и план будет по настоящему кругу."
        is RunOutcome.TestDoubtful -> "Контрольный дал бы форму ${Ru.one(o.vdot)}, слишком далеко от твоей. Похоже на опечатку или сбой, поэтому он не засчитан. Следующий контрольный через две недели."
        RunOutcome.TestNotCounted -> "Круги пропущены, а время не вписано, поэтому контрольный не засчитан. Следующий через две недели."
        is RunOutcome.TestFromPart ->
            if (o.finished && o.capped) "Первые ${o.meters} м темп держался (${Ru.clock(o.seconds)}), потом сильно упал. Форма посчитана с учётом этого."
            else if (o.finished) "Первые ${o.meters} м темп держался (${Ru.clock(o.seconds)}), потом упал. Форма посчитана по ним."
            else if (o.kept) "Контрольный не добежан в темпе до конца. Первые ${o.meters} м темп держался (${Ru.clock(o.seconds)}), форма прежняя. Полный контрольный через две недели."
            else if (o.capped) "Контрольный не добежан в темпе до конца. Первые ${o.meters} м темп держался (${Ru.clock(o.seconds)}), потом сильно упал. Полный контрольный через две недели."
            else "Контрольный не добежан в темпе до конца. Первые ${o.meters} м темп держался (${Ru.clock(o.seconds)}), форма посчитана по ним. Полный контрольный через две недели."
        is RunOutcome.TestStopped -> "В темпе получилось только ${o.meters} м, меньше половины контрольного или трёх минут. Он не засчитан, темпы прежние. Следующий контрольный через две недели."
        RunOutcome.Graduated -> "Получилось 30 минут бега без остановки. Дальше обычные пробежки и темп."
        is RunOutcome.Longer -> "Следующая пробежка этого типа: ${o.minutes} мин."
        is RunOutcome.Shorter -> "Было тяжело, следующая будет короче: ${o.minutes} мин."
        is RunOutcome.Same -> "Длительность пока прежняя: ${o.minutes} мин."
        is RunOutcome.VdotChanged -> {
            // In words: the number behind the form (VDOT) is in the run's own figures, not in what it changed.
            val from = o.from
            if (from == null) "Темпы рассчитаны."
            else if (o.to > from && o.easier) "Три тяжёлые пробежки прошли в темпе и с запасом. Форма выросла, темпы станут быстрее."
            else if (o.to > from) "Форма выросла, темпы станут быстрее."
            // A trial that gives the form known to the half point: nothing moves, and «чуть спокойнее» would be untrue.
            else if (o.to == from) "Форма подтвердилась, темпы прежние."
            else "Форма чуть ниже, темпы чуть спокойнее."
        }
        is RunOutcome.TestTime -> "Контрольный: ${o.meters} м за ${Ru.clock(o.seconds)}."
        RunOutcome.TestNext -> "Забег из двух частей получился. Следующая тяжёлая тренировка — контрольный забег целиком."
        is RunOutcome.EstChanged ->
            if (o.to > o.from) "Тяжёлые пробежки идут с запасом, темпы теперь чуть быстрее. Форма по тренировкам: ${Ru.one(o.from)} → ${Ru.one(o.to)}."
            else "Было слишком тяжело, темпы теперь спокойнее во всех пробежках. Форма по тренировкам: ${Ru.one(o.from)} → ${Ru.one(o.to)}."
        is RunOutcome.RepPace -> "Отрезки ты держишь с запасом. Дальше ${Ru.pace(o.to)} на км вместо ${Ru.pace(o.from)}."
        RunOutcome.TrialEarned -> "По тренировкам ты уже рядом с целью. Контрольный забег будет в следующий тяжёлый день. Не хочешь? Скажи тренеру «без контрольного»."
        RunOutcome.AheadOfPaces -> "Третью тяжёлую тренировку подряд темп даётся легче, чем задуман. Похоже, форма выросла. Следующая тяжёлая тренировка — контрольный."
        RunOutcome.GoalReached -> "Цель достигнута. Можно поставить новую в профиле."
        RunOutcome.TrialLater -> "Забег из двух частей получился. Контрольный бывает не чаще раза в три недели, до него закрепляем."
    }

    val RPE = listOf(
        1 to "Очень легко", 2 to "Легко", 3 to "Легко", 4 to "Умеренно", 5 to "Умеренно",
        6 to "Ощутимо", 7 to "Тяжело", 8 to "Тяжело", 9 to "Очень тяжело", 10 to "Предел",
    )
}
