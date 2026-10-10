package forma.app.ui

import forma.core.model.Exercise
import forma.core.model.LoadKind
import forma.core.run.RunOutcome
import forma.core.strength.HoldReason
import forma.core.strength.Outcome
import forma.core.text.Ru

/** Short Russian sentences that explain what the app changed and why. */
object Texts {
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
            HoldReason.LIGHTER -> "Подходы были легче плана — цель прежняя: ${target(ex, o.weight, o.reps)}."
            HoldReason.CONSOLIDATE -> "Всё сделано, но без запаса — закрепляем ${target(ex, o.weight, o.reps)}."
            HoldReason.NOT_THE_WEIGHT -> "Вес уже снижали, а повторов не прибавилось — дело не в весе. Оставляем его, цель ${target(ex, o.weight, o.reps)}."
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

    fun run(o: RunOutcome): String = when (o) {
        is RunOutcome.StageUp -> when (o.ladder) {
            "walk" -> "Следующая тренировка: неделя ${o.to / 3 + 1}, №${o.to % 3 + 1} — бега становится больше."
            "intervals" -> "Интервалы растут: в следующий раз чуть больше работы."
            else -> "Темповый бег растёт: в следующий раз чуть длиннее."
        }
        is RunOutcome.StageRepeat -> "Повторим эту тренировку — закрепим."
        is RunOutcome.StageDown -> "Шаг назад, чтобы набрать запас сил."
        RunOutcome.Graduated -> "30 минут без остановки! Дальше — обычные пробежки и темп."
        is RunOutcome.Longer -> "Следующая пробежка этого типа: ${o.minutes} мин."
        is RunOutcome.Shorter -> "Было тяжело — следующая будет короче: ${o.minutes} мин."
        is RunOutcome.Same -> "Длительность пока прежняя: ${o.minutes} мин."
        is RunOutcome.VdotChanged -> {
            val from = o.from
            if (from == null) "Темпы рассчитаны: VDOT ${Ru.one(o.to)}."
            else if (o.to > from) "Форма выросла: VDOT ${Ru.one(from)} → ${Ru.one(o.to)}, темпы станут быстрее."
            else "VDOT ${Ru.one(from)} → ${Ru.one(o.to)}: темпы чуть спокойнее."
        }
    }

    val RPE = listOf(
        1 to "Очень легко", 2 to "Легко", 3 to "Легко", 4 to "Умеренно", 5 to "Умеренно",
        6 to "Ощутимо", 7 to "Тяжело", 8 to "Тяжело", 9 to "Очень тяжело", 10 to "Предел",
    )
}
