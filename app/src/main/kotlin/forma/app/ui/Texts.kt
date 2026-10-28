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
    /** After a set: the next one, with its numbers, and why (the reserve against the plan). */
    fun nextSet(ex: Exercise, n: forma.core.strength.NextSet): String {
        val target = weightReps(ex, n.weight, n.reps)
        val r = n.reserve
        val why = when {
            // A hold: no weight and no reserve to mark, its time grows from one session to the next.
            ex.load == LoadKind.TIMED -> "Удержишь во всех — в следующий раз дольше."
            n.calibrate && n.change > 0 -> "Легко — можно тяжелее."
            n.calibrate && n.change < 0 -> "Тяжеловато — чуть легче."
            n.calibrate && r == null -> "Отметь, сколько ещё мог: так подберу вес."
            n.calibrate -> "Вес подходящий."
            n.change > 0 -> "Запас $r, а по плану ${n.plannedReserve} — прибавляем."
            n.change < 0 && r != null && r < 0 -> "Не хватило ${-r} до цели — убавляем."
            n.change < 0 -> "Запас меньше плана — убавляем."
            r == null -> "Отметь, сколько ещё мог: так подскажу вес."
            r < 0 -> "Чуть не хватило — тот же вес, попробуй добрать."
            r > n.plannedReserve -> "Запас $r — больше, чем надо (${n.plannedReserve}). Пока так же."
            r < n.plannedReserve -> "Запас $r — меньше, чем по плану (${n.plannedReserve}), это нормально. Так же."
            else -> "Запас $r — как надо. Так же."
        }
        return "Следующий подход: $target. $why"
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
        forma.core.strength.SlotRole.SECONDARY -> "Вспомогательное"
        forma.core.strength.SlotRole.ACCESSORY -> if (ex.compound) "Добивающее" else "Добивающее, на одну мышцу"
    }

    /** Why it stands where it does in the order. */
    fun orderWhy(role: forma.core.strength.SlotRole, ex: Exercise): String = when (role) {
        forma.core.strength.SlotRole.MAIN -> "— первым, пока силы свежие: на нём растёт сила."
        forma.core.strength.SlotRole.SECONDARY -> "— после главного: добирает подходы, свежесть ему не так важна."
        forma.core.strength.SlotRole.ACCESSORY -> "— в конце: " + if (ex.compound) "добирает подходы, когда главное сделано." else "общая усталость ему почти не мешает."
    }

    /** "Работают: грудь · помогают: трицепс, плечи". */
    fun muscles(ex: Exercise): String =
        "Работают: " + ex.primary.joinToString(", ") { it.ru.lowercase() } +
            if (ex.secondary.isNotEmpty()) " · помогают: " + ex.secondary.joinToString(", ") { it.ru.lowercase() } else ""

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

    private fun target(ex: Exercise?, w: Double, reps: Int): String = weightReps(ex, w, reps)

    fun outcome(o: Outcome, ex: Exercise?): String = when (o) {
        is Outcome.Calibrated -> "Рабочий вес подобран: ${target(ex, o.weight, o.reps)}."
        is Outcome.AddReps ->
            if (ex?.load == LoadKind.TIMED) "Всё выдержал — в следующий раз ${o.to} с."
            else "Все повторы сделаны — в следующий раз ${target(ex, o.weight, o.to)}."
        is Outcome.LowerReps ->
            if (ex?.load == LoadKind.TIMED) "Два раза подряд не вышло удержать ${o.from} с. Цель теперь ${o.to} с, от неё снова растём."
            else "Повторов не хватило. Цель — ${o.to} вместо ${o.from}, вес тот же: ${target(ex, o.weight, o.to)}."
        is Outcome.AddWeight -> "Все повторы сделаны — прибавляем ${Ru.kg(o.to - o.from)} кг: ${target(ex, o.to, o.reps)}."
        is Outcome.Jump -> "Запас был большой — сразу ${target(ex, o.to, o.reps)} (+${Ru.kg(o.to - o.from)} кг)."
        is Outcome.Hold -> when (o.reason) {
            HoldReason.NEAR_MISS -> "Почти получилось — повторяем ${target(ex, o.weight, o.reps)}."
            HoldReason.FIRST_MISS -> "Не добрал повторы — повторяем ${target(ex, o.weight, o.reps)}. Один неудачный день ничего не значит."
            HoldReason.FEWER_SETS -> "Сделаны не все подходы — цель та же: ${target(ex, o.weight, o.reps)}."
            HoldReason.LIGHTER -> "Вес был легче плана — цель та же: ${target(ex, o.weight, o.reps)}. Нет такого веса в зале? Поставь шаг веса: «План» → упражнение."
            HoldReason.CONSOLIDATE -> "Всё сделано, но без запаса — закрепляем ${target(ex, o.weight, o.reps)}."
            HoldReason.NOT_THE_WEIGHT -> "Вес уже снижали, а повторов больше не стало. Значит, дело не в весе. Оставляем ${target(ex, o.weight, o.reps)}."
            HoldReason.STOPPED_EARLY -> "Повторов меньше цели, но запас был. Значит, вес по силам. Оставляем ${target(ex, o.weight, o.reps)} — сделай все повторы."
            HoldReason.INTRO -> "Первая неделя цикла: запас больше, недобор не считается. Цель та же: ${target(ex, o.weight, o.reps)}."
            HoldReason.AT_TOP -> "Это максимум по плану — держим ${target(ex, o.weight, o.reps)}. Хочешь расти дальше — замени на вариант сложнее."
            HoldReason.TAPER -> "Подводка к цели: подходов меньше, вес тот же. После дня цели продолжим с ${target(ex, o.weight, o.reps)}."
            HoldReason.TEST -> "Проверка максимума засчитана. План упражнения не меняется: дальше ${target(ex, o.weight, o.reps)}."
        }
        is Outcome.Reduce -> "Вес оказался тяжёлым — ${target(ex, o.to, o.reps)} (−${Ru.kg(o.from - o.to)} кг). С этого веса снова растём."
        is Outcome.Reset -> "Три тренировки без роста — шаг назад: ${target(ex, o.to, o.reps)}, чтобы снова пойти вверх."
        Outcome.NotDone -> "Не делал — цель та же."
        Outcome.Deload -> "Разгрузка — лёгкая неделя. Ничего не теряешь: после неё продолжим с тех же весов."
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
        manual && o == RunOutcome.PaceUnknown -> "Средний круг не вписан, поэтому шаг выбран по ощущениям. В следующий раз впиши его — так видно, держишь ли темп цели."
        manual && o == RunOutcome.TestNotCounted -> "Время контрольного забега не вписано, поэтому он не засчитан. Следующий — через две недели."
        o is RunOutcome.Same && plan != null && plan.workSeconds / 60 < o.minutes ->
            "Короткая пробежка длительность не меняет. Обычная " +
                (if (plan.type == forma.core.run.RunType.LONG) "длинная" else "лёгкая") + " — ${o.minutes} мин."
        else -> run(o)
    }

    private fun run(o: RunOutcome): String = when (o) {
        is RunOutcome.StageUp -> when (o.ladder) {
            "walk" -> "Следующая тренировка: неделя ${o.to / 3 + 1}, №${o.to % 3 + 1} — бега становится больше."
            "intervals" -> "Интервалы получились — в следующий раз чуть сложнее."
            "goal" -> "Отрезки в темпе цели получились — следующий шаг: ${o.to + 1} из ${forma.core.run.RunPlan.goalLadderSize}."
            else -> "Темповый бег получился — в следующий раз чуть сложнее."
        }
        is RunOutcome.StageRepeat ->
            if (o.ladder == "goal") "Темп цели пока не держится — повторим эту тренировку. Не выйдет второй раз подряд — шаг назад. Главное — ровные круги."
            else "Повторим эту тренировку. Не получится второй раз подряд — шаг назад."
        is RunOutcome.StageDown -> "Шаг назад, чтобы набрать запас сил."
        is RunOutcome.LadderTop -> "Это самый сложный шаг — закрепляем."
        is RunOutcome.LighterWeek -> "Лёгкая неделя: тренировка короче, шаг не двигаем. Полная версия будет позже."
        is RunOutcome.TempoPace ->
            if (o.softer) "Темповый бег был очень тяжёлым, и темп не держался. Ты скорее быстрый, чем выносливый: долгий темп даётся тебе хуже, чем обещает короткий забег. " +
                "Темп темповых пробежек теперь на ${o.seconds} с/км спокойнее."
            else if (o.seconds > 0) "Темповый бег дался легко и в темпе — ты подрос. Теперь темп всего на ${o.seconds} с/км спокойнее расчётного."
            else "Темповый бег дался легко и в темпе. Темп снова как по расчёту."
        RunOutcome.TooFastRepeat -> "Отрезки были заметно быстрее цели. Скорость у тебя есть, теперь учимся бежать ровно. Этот шаг повторим: " +
            "держи время круга или темп на км, не быстрее. На средних дистанциях решает ровный бег."
        RunOutcome.TooFast -> "Отрезки были быстрее цели. Шаг вперёд — только один: на 1500 м решают ровные круги. Держи время круга."
        is RunOutcome.OwnScale -> "Лёгкий бег ты обычно оцениваешь на ${5 + o.offset}–${6 + o.offset}, хотя он должен быть до 5. Значит, ты оцениваешь строже. План это учитывает: границы тяжести для тебя на ${o.offset} выше. В тяжёлых пробежках — только когда темп держался."
        is RunOutcome.EasyPaces ->
            if (o.to > o.from) "Пять из шести последних лёгких пробежек ты бежал заметно быстрее лёгкого темпа, и было легко. Контрольного забега давно не было, " +
                "поэтому темпы обновлены по этим пробежкам: VDOT ${Ru.one(o.from)} → ${Ru.one(o.to)}."
            else "Пять из шести последних лёгких пробежек шли медленнее лёгкого темпа, и было тяжело. Темпы теперь спокойнее: VDOT ${Ru.one(o.from)} → ${Ru.one(o.to)}. " +
                "Контрольный забег уточнит."
        RunOutcome.TooSlow -> "Отрезки медленнее темпа цели больше чем на 3 %. Шаг вперёд будет, когда темп начнёт держаться, даже если тяжело."
        RunOutcome.PaceUnknown -> "Темп не измерен: круги не засчитаны. С GPS на стадионе они засчитываются сами, без него — жми «Круг» на линии."
        is RunOutcome.LoopShorter -> "Круг по GPS — около ${o.loop} м, а в настройках ${o.set} м. Поэтому круги посчитаны по ${o.loop} м: и темп отрезков, и дистанция контрольного. " +
            "Поставь длину круга — тогда и план будет по настоящему кругу."
        is RunOutcome.TestDoubtful -> "Результат контрольного слишком далёк от твоей формы: вышло бы VDOT ${Ru.one(o.vdot)}. Похоже на опечатку или сбой, поэтому он не засчитан. Следующий контрольный — через две недели."
        RunOutcome.TestNotCounted -> "Время контрольного неизвестно: круги пропущены, время не вписано. Поэтому он не засчитан. Следующий — через две недели."
        is RunOutcome.TestFromPart ->
            if (o.finished && o.capped) "Первые ${o.meters} м ты держал темп (${Ru.clock(o.seconds)}), потом сильно сбавил. Форма выше, чем по всему времени, но ниже, чем по этим метрам: держать такой темп до конца пока не хватает сил."
            else if (o.finished) "Первые ${o.meters} м ты держал темп (${Ru.clock(o.seconds)}), потом сбавил. Форма посчитана по ним: по всему времени она вышла бы ниже, а сил, как видно, больше."
            else if (o.kept) "Контрольный не добежан в темпе до конца. Ровно от старта — ${o.meters} м за ${Ru.clock(o.seconds)}: по ним форма не выше твоей, поэтому она прежняя. Полный контрольный — через две недели."
            else if (o.capped) "Контрольный не добежан в темпе до конца: ${o.meters} м за ${Ru.clock(o.seconds)} от старта ты держал, дальше сильно сбавил. Форма выше, чем по всему времени, но ниже, чем по этим метрам. Это оценка: полный контрольный — через две недели."
            else "Контрольный не добежан в темпе до конца, но ${o.meters} м за ${Ru.clock(o.seconds)} от старта ты держал — форма посчитана по ним. Это оценка: полный контрольный — через две недели."
        is RunOutcome.TestStopped -> "Ровно от старта — ${o.meters} м: меньше половины контрольного или трёх минут, по такому куску форму не оценить. Темпы прежние, следующий контрольный — через две недели."
        RunOutcome.Graduated -> "30 минут без остановки! Дальше — обычные пробежки и темп."
        is RunOutcome.Longer -> "Следующая пробежка этого типа: ${o.minutes} мин."
        is RunOutcome.Shorter -> "Было тяжело — следующая будет короче: ${o.minutes} мин."
        is RunOutcome.Same -> "Длительность пока прежняя: ${o.minutes} мин."
        is RunOutcome.VdotChanged -> {
            val from = o.from
            if (from == null) "Темпы рассчитаны: VDOT ${Ru.one(o.to)}."
            else if (o.to > from && o.easier) "Три тяжёлые пробежки прошли в темпе и с запасом. Темпы на шаг быстрее: VDOT ${Ru.one(from)} → ${Ru.one(o.to)}. Проверим на контрольном."
            else if (o.to > from) "Форма выросла: VDOT ${Ru.one(from)} → ${Ru.one(o.to)}, темпы станут быстрее."
            else "VDOT ${Ru.one(from)} → ${Ru.one(o.to)}: темпы чуть спокойнее."
        }
        is RunOutcome.TestTime -> "Контрольный: ${o.meters} м за ${Ru.clock(o.seconds)}."
        RunOutcome.TestNext -> "Гонка по частям в темпе цели получилась. Следующая тяжёлая тренировка — контрольный забег целиком."
        RunOutcome.GoalReached -> "Цель достигнута! Можно поставить новую в профиле."
        RunOutcome.TrialLater -> "Гонка по частям в темпе цели получилась. Контрольный забег — не чаще раза в три недели. До него закрепляем."
    }

    val RPE = listOf(
        1 to "Очень легко", 2 to "Легко", 3 to "Легко", 4 to "Умеренно", 5 to "Умеренно",
        6 to "Ощутимо", 7 to "Тяжело", 8 to "Тяжело", 9 to "Очень тяжело", 10 to "Предел",
    )
}
