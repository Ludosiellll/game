package forma.app.ui

import forma.core.model.Exercise
import forma.core.model.LoadKind
import forma.core.run.RunOutcome
import forma.core.strength.HoldReason
import forma.core.strength.Outcome
import forma.core.text.Ru

/** Short Russian sentences that explain what the app changed and why. */
object Texts {
    /** After a set: the next one, with its numbers, and why (the reserve against the plan). */
    fun nextSet(ex: Exercise, n: forma.core.strength.NextSet): String {
        val target = if (ex.load == LoadKind.TIMED) "${n.reps} с" else "${weight(ex, n.weight)} × ${n.reps}"
        val r = n.reserve
        val why = when {
            n.calibrate && n.change > 0 -> "Пошло легко — значит, можно тяжелее."
            n.calibrate && n.change < 0 -> "Тяжеловато — чуть легче."
            n.calibrate && r == null -> "Отметь «сколько ещё мог» — по запасу подберу вес."
            n.calibrate -> "Вес подходящий."
            n.change > 0 -> "Запас $r при плане ${n.plannedReserve} — вес лёгкий, прибавляем."
            n.change < 0 && r != null && r < 0 -> "До цели не хватило ${-r} — вес тяжёлый, убавляем."
            n.change < 0 -> "Запаса меньше плана — вес тяжёлый, убавляем."
            r == null -> "Отметь «сколько ещё мог» — по запасу подскажу вес."
            r < 0 -> "Чуть не хватило — тот же вес, попробуй добрать."
            r > n.plannedReserve -> "Запас $r — чуть легче плана, для прибавки мало: так же."
            r < n.plannedReserve -> "Запас $r — чуть тяжелее плана, но в норме: так же."
            else -> "Запас $r — как задумано: так же."
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
        forma.core.strength.SlotRole.MAIN -> "— в начале, пока силы свежие: на нём растёт сила, ему нужна полная свежесть."
        forma.core.strength.SlotRole.SECONDARY -> "— после главных: добирает объём, свежесть ему важна меньше."
        forma.core.strength.SlotRole.ACCESSORY -> "— в конце: " + if (ex.compound) "добирает объём, когда главное сделано." else "работает одна мышца, усталость всего тела ему не мешает."
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
        else -> "${Ru.kg(w)} кг"
    }

    private fun target(ex: Exercise?, w: Double, reps: Int): String =
        if (ex?.load == LoadKind.TIMED) "$reps с" else "${weight(ex, w)} × $reps"

    fun outcome(o: Outcome, ex: Exercise?): String = when (o) {
        is Outcome.Calibrated -> "Рабочий вес подобран: ${target(ex, o.weight, o.reps)}."
        is Outcome.AddReps ->
            if (ex?.load == LoadKind.TIMED) "Всё выдержал — в следующий раз ${o.to} с."
            else "Все повторы сделаны — в следующий раз ${target(ex, o.weight, o.to)}."
        is Outcome.LowerReps ->
            if (ex?.load == LoadKind.TIMED) "Два раза подряд не удалось удержать ${o.from} с — цель ${o.to} с, от неё снова растём."
            else "Повторов не хватило — цель ${o.to} вместо ${o.from}, вес прежний: ${target(ex, o.weight, o.to)}."
        is Outcome.AddWeight -> "Все повторы сделаны — прибавляем ${Ru.kg(o.to - o.from)} кг: ${target(ex, o.to, o.reps)}."
        is Outcome.Jump -> "Запас был большой — сразу ${target(ex, o.to, o.reps)} (+${Ru.kg(o.to - o.from)} кг)."
        is Outcome.Hold -> when (o.reason) {
            HoldReason.NEAR_MISS -> "Почти получилось — повторяем ${target(ex, o.weight, o.reps)}."
            HoldReason.FIRST_MISS -> "Не добрал повторы — повторяем ${target(ex, o.weight, o.reps)}. Один неудачный день ничего не значит."
            HoldReason.FEWER_SETS -> "Сделаны не все подходы — цель прежняя: ${target(ex, o.weight, o.reps)}."
            HoldReason.LIGHTER -> "Подходы были легче плана — цель прежняя: ${target(ex, o.weight, o.reps)}. Нет такого веса в зале — поставь шаг веса: «План» → упражнение."
            HoldReason.CONSOLIDATE -> "Всё сделано, но без запаса — закрепляем ${target(ex, o.weight, o.reps)}."
            HoldReason.NOT_THE_WEIGHT -> "Вес уже снижали, а повторов не прибавилось — дело не в весе. Оставляем его, цель ${target(ex, o.weight, o.reps)}."
            HoldReason.STOPPED_EARLY -> "Повторов меньше цели, но запас был — значит, вес по силам. Оставляем ${target(ex, o.weight, o.reps)}: сделай все повторы."
            HoldReason.AT_TOP -> "Это верх диапазона — держим ${target(ex, o.weight, o.reps)}. Хочешь дальше — замени на вариант сложнее."
        }
        is Outcome.Reduce -> "Вес оказался тяжёлым — ${target(ex, o.to, o.reps)} (−${Ru.kg(o.from - o.to)} кг). С этого веса снова растём."
        is Outcome.Reset -> "Три тренировки без роста — шаг назад до ${target(ex, o.to, o.reps)}, чтобы снова пойти вверх."
        Outcome.NotDone -> "Не выполнялось — цель прежняя."
        Outcome.Deload -> "Разгрузка: прогресс сохранён, после неё продолжим с тех же весов."
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
        manual && o == RunOutcome.PaceUnknown -> "Средний круг не вписан: шаг решён по ощущениям. Впиши его в следующий раз — тогда видно, держится ли темп цели."
        manual && o == RunOutcome.TestNotCounted -> "Время контрольного не вписано — не засчитан. Следующий — через две недели."
        o is RunOutcome.Same && plan != null && plan.workSeconds / 60 < o.minutes ->
            "Короткая пробежка длительность не меняет; обычная " +
                (if (plan.type == forma.core.run.RunType.LONG) "длинная" else "лёгкая") + " — ${o.minutes} мин."
        else -> run(o)
    }

    private fun run(o: RunOutcome): String = when (o) {
        is RunOutcome.StageUp -> when (o.ladder) {
            "walk" -> "Следующая тренировка: неделя ${o.to / 3 + 1}, №${o.to % 3 + 1} — бега становится больше."
            "intervals" -> "Интервалы получились — в следующий раз следующий шаг лестницы."
            "goal" -> "Отрезки в темпе цели получились — следующий шаг: ${o.to + 1} из ${forma.core.run.RunPlan.goalLadderSize}."
            else -> "Темповый бег получился — в следующий раз следующий шаг лестницы."
        }
        is RunOutcome.StageRepeat ->
            if (o.ladder == "goal") "Темп цели пока не держится — повторим эту тренировку, второй раз подряд — шаг назад. Главное — ровные круги."
            else "Повторим эту тренировку; не получится второй раз подряд — шаг назад."
        is RunOutcome.StageDown -> "Шаг назад, чтобы набрать запас сил."
        is RunOutcome.LadderTop -> "Это верх лестницы — закрепляем."
        is RunOutcome.LighterWeek -> "Облегчённая неделя: тренировка короче, шаг лестницы не двигаем — полная версия впереди."
        RunOutcome.TooFast -> "Отрезки были быстрее цели. Шаг вперёд один, не два: на 1500 м решают ровные круги, держи их по времени круга."
        is RunOutcome.OwnScale -> "Лёгкий бег ты обычно оцениваешь на ${5 + o.offset}–${6 + o.offset}, хотя он должен ощущаться до 5: твоя шкала строже. План учитывает это — пороги тяжести на ${o.offset} выше (в тяжёлых — только когда темп держался)."
        is RunOutcome.EasyPaces ->
            if (o.to > o.from) "Пять из шести последних лёгких пробежек ты бежал заметно быстрее лёгкого темпа — и легко. Контрольного давно не было, " +
                "поэтому темпы обновлены по ним: VDOT ${Ru.one(o.from)} → ${Ru.one(o.to)}."
            else "Пять из шести последних лёгких пробежек шли медленнее лёгкого темпа и тяжело. Темпы спокойнее: VDOT ${Ru.one(o.from)} → ${Ru.one(o.to)}. " +
                "Контрольный забег уточнит."
        RunOutcome.TooSlow -> "Отрезки медленнее целевого темпа больше чем на 3 %: шаг вперёд — когда темп будет держаться, как бы ни ощущалась тяжесть."
        RunOutcome.PaceUnknown -> "Темп не измерен: нажимай «Круг» на линии — тогда видно, держится ли темп."
        is RunOutcome.TestDoubtful -> "Результат контрольного слишком далёк от твоей формы (вышло бы VDOT ${Ru.one(o.vdot)}) — похоже на опечатку или сбой, не засчитан. Следующий контрольный — через две недели."
        RunOutcome.TestNotCounted -> "Время контрольного неизвестно (круги пропущены, время не вписано) — не засчитан. Следующий — через две недели."
        RunOutcome.Graduated -> "30 минут без остановки! Дальше — обычные пробежки и темп."
        is RunOutcome.Longer -> "Следующая пробежка этого типа: ${o.minutes} мин."
        is RunOutcome.Shorter -> "Было тяжело — следующая будет короче: ${o.minutes} мин."
        is RunOutcome.Same -> "Длительность пока прежняя: ${o.minutes} мин."
        is RunOutcome.VdotChanged -> {
            val from = o.from
            if (from == null) "Темпы рассчитаны: VDOT ${Ru.one(o.to)}."
            else if (o.to > from && o.easier) "Три тяжёлые пробежки в темпе и с запасом — темпы на шаг быстрее: VDOT ${Ru.one(from)} → ${Ru.one(o.to)}. Проверим на контрольном."
            else if (o.to > from) "Форма выросла: VDOT ${Ru.one(from)} → ${Ru.one(o.to)}, темпы станут быстрее."
            else "VDOT ${Ru.one(from)} → ${Ru.one(o.to)}: темпы чуть спокойнее."
        }
        is RunOutcome.TestTime -> "Контрольный: ${o.meters} м за ${Ru.clock(o.seconds)}."
        RunOutcome.TestNext -> "Гонка по частям в темпе цели получилась — следующая тяжёлая тренировка будет контрольным забегом целиком."
        RunOutcome.GoalReached -> "Цель достигнута! Можно поставить новую в профиле."
        RunOutcome.TrialLater -> "Гонка по частям в темпе цели получилась. Контрольный забег — не чаще раза в три недели, до него закрепляем."
    }

    val RPE = listOf(
        1 to "Очень легко", 2 to "Легко", 3 to "Легко", 4 to "Умеренно", 5 to "Умеренно",
        6 to "Ощутимо", 7 to "Тяжело", 8 to "Тяжело", 9 to "Очень тяжело", 10 to "Предел",
    )
}
