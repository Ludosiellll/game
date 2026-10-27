package forma.core.plan

import forma.core.model.LiftGoal
import forma.core.model.RunGoal
import forma.core.text.Ru

/**
 * What a change costs the person's goals, said before it is made — with what would keep the goal instead. The change
 * stays theirs to make; only it is not made blind.
 *
 * - Without running VO2max drops about 7 % in 2–3 weeks and 15 % by 2–3 months (Coyle et al. 1984).
 * - The least that keeps what was built: endurance with two sessions a week at the same intensity, strength (young people)
 *   with one session a week, a set per exercise, as heavy as before (Spiering et al. 2021, JSCR). Keeping is not
 *   gaining: the goal does not come nearer on it.
 */
object GoalRisk {
    private fun runGoalWords(g: RunGoal) = "${g.meters} м за ${Ru.clock(g.seconds)}"

    private fun liftGoalWords(g: LiftGoal, name: String) = "$name ${Ru.kg(g.kg)} кг"

    /** A week of [runs] runs and [lifts] gym sessions against the goals: what it costs, or null when it keeps them. */
    fun week(runGoal: RunGoal?, liftGoal: LiftGoal?, liftName: String?, runs: Int, lifts: Int, goalLiftDays: Int? = null): String? {
        val out = mutableListOf<String>()
        if (runGoal != null && runs == 0) {
            out += "Цель по бегу (${runGoalWords(runGoal)}) останется без бега: за 2–3 недели без бега форма падает примерно на 7 %, " +
                "к 2–3 месяцам — на 15 %. Если бегать некогда, лучше оставить 2 пробежки в неделю: форму они удержат, хоть к цели и не приблизят."
        } else if (runGoal?.middleDistance == true && runs == 1) {
            out += "Одна пробежка в неделю для ${runGoal.meters} м — мало: в неё не уместить и отрезки в темпе цели, и лёгкий бег, прогресс почти встанет. Минимум — 2."
        }
        if (liftGoal != null && liftName != null) {
            if (lifts == 0) {
                out += "Цель в зале (${liftGoalWords(liftGoal, liftName)}) останется без тренировок: сила через несколько недель начнёт уходить. " +
                    "Если некогда, одна силовая в неделю с тяжёлым подходом удержит силу, хоть к цели и не приблизит."
            } else if (goalLiftDays == 0) {
                out += "В новой неделе нет тренировки с упражнением цели (${liftGoalWords(liftGoal, liftName)}): к ней не приблизиться."
            }
        }
        return out.joinToString(" ").ifEmpty { null }
    }

    /** The goal's lift taken out of the plan, or swapped for another ([to]) — at [left] places it stays in. */
    fun liftGone(g: LiftGoal, name: String, left: Int, to: String? = null): String? {
        if (left > 0) return null
        return "На «$name» стоит цель в зале (${Ru.kg(g.kg)} кг): " + (if (to != null) "после замены на «$to» " else "без него ") +
            "в плане её не тренировать. Если упражнение не подходит — " + (if (to != null) "поставь цель на «$to» после замены." else "лучше заменить его похожим и перенести цель.")
    }

    /** Fewer sets of the goal's lift than the goal's plan has ([from] → [to]). */
    fun liftCut(g: LiftGoal, name: String, from: Int, to: Int): String? {
        if (to >= from) return null
        return "Меньше подходов «$name» — медленнее к цели ${Ru.kg(g.kg)} кг: сила растёт от количества тяжёлых подходов. " +
            "Если тренировка слишком длинная, лучше убрать подход у второстепенного упражнения."
    }

    /** The goal's lift moved to reps of [repMin] and more: no longer heavy work, the max grows slower. */
    fun liftReps(g: LiftGoal, name: String, repMin: Int): String? {
        if (repMin <= 6) return null
        return "Повторы от $repMin у «$name» — это уже не тяжёлая работа: максимум растёт медленнее всего от лёгких подходов. " +
            "Для цели ${Ru.kg(g.kg)} кг оставь хотя бы в одной тренировке 3–6 повторов."
    }

    /** Shorter rests between the goal lift's sets ([sec]). */
    fun liftRest(g: LiftGoal, name: String, sec: Int): String? {
        if (sec >= 120) return null
        return "Отдых меньше 2 минут между тяжёлыми подходами «$name» — следующие подходы выйдут слабее, к цели ${Ru.kg(g.kg)} кг медленнее. Для силы — 2–3 минуты и больше."
    }

    /** The goal lift's working weight down [from] → [to] by a tenth or more. */
    fun liftDown(g: LiftGoal, name: String, from: Double, to: Double): String? {
        if (from <= 0 || to >= from * 0.9) return null
        val pct = ((1 - to / from) * 100).toInt()
        return "Вес «$name» ниже на $pct % — это откат от цели ${Ru.kg(g.kg)} кг на несколько недель. Если тяжело из-за усталости, лучше «Так себе» сегодня или лёгкая неделя: вес останется."
    }

    /** A pause of [days] days with the goal's date [daysToDate] days on. */
    fun pause(days: Int, runGoal: RunGoal?, runDaysToDate: Long?, liftGoal: LiftGoal?, liftDaysToDate: Long?): String? {
        if (days < 7) return null
        val out = mutableListOf<String>()
        if (runGoal != null && runDaysToDate != null && runDaysToDate in 0..(days + 28L)) {
            out += "До забега (${runGoalWords(runGoal)}) ${runDaysToDate} ${Ru.plural(runDaysToDate, "день", "дня", "дней")}: после паузы на подготовку останется мало времени."
        }
        if (liftGoal != null && liftDaysToDate != null && liftDaysToDate in 0..(days + 28L)) {
            out += "До даты цели в зале ${liftDaysToDate} ${Ru.plural(liftDaysToDate, "день", "дня", "дней")}: паузу лучше короче или дату позже."
        }
        return out.joinToString(" ").ifEmpty { null }
    }
}
