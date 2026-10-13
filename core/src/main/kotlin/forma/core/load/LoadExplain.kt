package forma.core.load

import forma.core.load.LoadStory.Level
import forma.core.load.LoadStory.Zone
import forma.core.text.Ru
import kotlin.math.roundToInt

/** The load numbers in words, with this person's own numbers in them. */
object LoadExplain {
    /** What one unit of load is. */
    const val UNITS = "Нагрузка тренировки = её тяжесть (1–10, ставишь в конце) × минуты. Час лёгкого бега на 4 — 240, " +
        "час зала на 7 — 420. Отдельно по ней ничего не скажешь, важно, как она соотносится с привычной."

    private fun r(x: Double) = x.roundToInt()

    /** Thousands with a space: "1 240". */
    fun n(x: Double): String {
        val s = r(x).toString()
        return if (s.length > 3) s.dropLast(3) + " " + s.takeLast(3) else s
    }

    fun fitness(s: LoadStory.Story): String {
        val f = s.today.fitness
        val trend = when {
            s.fitnessChange == null -> ""
            s.fitnessChange > 5 -> " За 4 недели ${Ru.pct(s.fitnessChange)} — растёт: тело привыкает к большей работе."
            s.fitnessChange < -5 -> " За 4 недели ${Ru.pct(s.fitnessChange)} — снижается: тренировок меньше, чем раньше (разгрузка, пропуски, болезнь)."
            else -> " За 4 недели почти не изменилась (${Ru.pct(s.fitnessChange)}) — нагрузка ровная."
        }
        val early = if (!s.history) " Пока тренировок мало — число приблизительное, оно уточнится за 2–3 недели." else ""
        return "Форма ${r(f)} — сколько нагрузки ты привык переносить: средняя за последние ~6 недель, около ${n(f * 7)} в неделю. " +
            "Растёт медленно, от регулярных тренировок, и так же медленно уходит.$trend$early"
    }

    fun fatigue(s: LoadStory.Story): String {
        val d = s.today
        val vs = when {
            d.fitness < 1e-6 -> ""
            d.fatigue > d.fitness * 1.05 -> " Сейчас она выше формы: последняя неделя тяжелее привычной."
            d.fatigue < d.fitness * 0.95 -> " Сейчас она ниже формы: последняя неделя легче привычной — тело восстанавливается."
            else -> " Сейчас она примерно равна форме: неделя как обычно."
        }
        return "Усталость ${r(d.fatigue)} — нагрузка последних ~7 дней, около ${n(d.fatigue * 7)} в неделю. " +
            "Быстро растёт после тяжёлых дней и уходит за 2–3 дня отдыха.$vs"
    }

    fun freshness(s: LoadStory.Story): String =
        if (!s.history) "Свежесть появится через две недели после первой тренировки и минимум после четырёх тренировок: до этого одна тяжёлая тренировка показывает «−100 %»."
        else "Свежесть ${Ru.pct(s.today.formPct)} = (форма − усталость) / форма. Минус — устал относительно привычного, плюс — отдохнул. " +
            "Немного в минусе — нормально: так форма и растёт."

    fun zoneTitle(z: Zone): String = when (z) {
        Zone.REST -> "Нужен отдых"
        Zone.BUILDING -> "Идёт нагрузка"
        Zone.BALANCE -> "Равновесие"
        Zone.FRESH -> "Свежий"
        Zone.DETRAINING -> "Долгий отдых"
    }

    fun zoneText(s: LoadStory.Story): String {
        val line = Ru.pct(s.personal.restBelow)
        return when (s.zone ?: return "") {
            Zone.REST -> "Свежесть ниже твоей линии отдыха ($line). В таком состоянии у тебя тренировки чаще не получаются: " +
                "день-два отдыха или лёгкая тренировка, потом как обычно." + since(s)
            Zone.BUILDING -> "Ты устал, но выше линии отдыха ($line): это рабочая усталость, от неё форма и растёт. Тренируйся по плану."
            Zone.BALANCE -> "Нагрузка и восстановление уравновешены — тренируйся по плану."
            Zone.FRESH -> "Ты отдохнул: хорошее время для тяжёлой тренировки, контрольного забега или попытки рекорда."
            Zone.DETRAINING -> "Отдыхаешь дольше обычного — форма начинает уходить." + since(s) + " Пора вернуться к тренировкам."
        }
    }

    private fun since(s: LoadStory.Story) = when (val d = s.daysSinceTraining) {
        null, 0 -> ""
        else -> " Последняя тренировка — ${Ru.days(d)} назад."
    }

    /** Where the rest line comes from. */
    fun personal(p: LoadStory.Personal): String = when {
        p.learned -> "Линия отдыха — твоя, ${Ru.pct(p.restBelow)}. Когда ты начинал тренировку уставшим сильнее, не получались " +
            "${p.badBelow} из ${p.judgedBelow}; когда свежее — только ${p.badAbove} из ${p.judgedAbove}. Ниже этой линии отдых окупается."
        p.bad >= 3 && p.good >= 3 -> "Твои неудачные тренировки (${p.bad} из ${p.bad + p.good}) не связаны со свежестью: уставшим ты срываешься не чаще, " +
            "чем отдохнувшим. Значит, дело не в усталости (сон, питание, вес не по силам). Линия отдыха остаётся общей: ${Ru.pct(p.restBelow)}."
        else -> "Линия отдыха пока общая: ${Ru.pct(p.restBelow)}. Когда наберётся хотя бы по 3 удачных и неудачных тренировки " +
            "(сейчас ${p.good} и ${p.bad}), приложение посмотрит, при какой свежести у тебя не получается, и поставит линию под тебя."
    }

    fun level(w: LoadStory.Week): String = when (w.level) {
        Level.NONE -> "без тренировок"
        Level.LIGHT -> "легче обычной"
        Level.USUAL -> "обычная"
        Level.MORE -> "выше обычной"
        Level.SPIKE -> "резкий скачок"
    }

    fun week(w: LoadStory.Week): String = buildString {
        append(n(w.load))
        if (w.trainingDays > 0) append(" · ${Ru.days(w.trainingDays)}")
        w.ratio?.takeIf { w.level != Level.NONE }?.let { append(" · ${r(it * 100)} % от обычной") }
        w.lowestPct?.let { append(" · свежесть до ${Ru.pct(it)}") }
    }

    /** What the week level means; null when there is nothing to say. */
    fun levelNote(w: LoadStory.Week): String? = when (w.level) {
        Level.SPIKE -> "Нагрузка резко выросла: больше чем на 30 % от твоей обычной недели. Так чаще всего и перегружаются — следующую лучше не наращивать."
        Level.LIGHT -> "Неделя легче обычной: разгрузка, пропуски или болезнь. Форма за неё почти не теряется."
        else -> null
    }

    fun usual(s: LoadStory.Story): String? = s.usualWeek?.let {
        "Твоя обычная неделя — около ${n(it)} (середина последних четырёх недель с тренировками). Неделя «выше обычной» — до +30 %, больше — скачок."
    }

    fun dip(d: LoadStory.Dip): String {
        val low = if (d.from == d.to) "до ${Ru.pct(d.lowestPct)}" else "до ${Ru.pct(d.lowestPct)} (${Ru.date(d.lowestDay)})"
        val rest = if (d.restDays > 0) "дней без тренировок: ${d.restDays}" else "без дня отдыха"
        return if (d.recovered == null) "С ${Ru.date(d.from)} ты ниже линии отдыха, $low. Сейчас нужен отдых: день-два или лёгкая тренировка ($rest)."
        else "${Ru.dates(d.from, d.to)} свежесть падала $low — нужен был отдых. Пришёл в норму к ${Ru.date(d.recovered)}, $rest."
    }

    fun noDips(weeks: Int) = "За ${Ru.weeks(weeks)} ни разу не опускался ниже линии отдыха — нагрузка была по силам."
}
