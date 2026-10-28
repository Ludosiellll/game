package forma.core.load

import forma.core.load.LoadStory.Level
import forma.core.load.LoadStory.Zone
import forma.core.text.Ru
import kotlin.math.roundToInt

/** The load numbers in words, with this person's own numbers in them. */
object LoadExplain {
    /** What one unit of load is. */
    const val UNITS = "Нагрузка тренировки = её тяжесть (от 1 до 10, ставишь в конце) × минуты. Час лёгкого бега на 4 — это 240, " +
        "час в зале на 7 — 420. Само число мало что говорит. Важно, больше оно или меньше твоей обычной нагрузки."

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
            s.fitnessChange > 5 -> " За 4 недели ${Ru.pct(s.fitnessChange)}: растёт, тело привыкает к большей нагрузке."
            s.fitnessChange < -5 -> " За 4 недели ${Ru.pct(s.fitnessChange)}: снижается, тренировок меньше, чем раньше. Так бывает из-за разгрузки, пропусков или болезни."
            else -> " За 4 недели почти не изменилась (${Ru.pct(s.fitnessChange)}), нагрузка ровная."
        }
        val early = if (!s.history) " Пока тренировок мало, число примерное. Оно уточнится за 2–3 недели." else ""
        return "Тренированность ${r(f)} — сколько нагрузки ты привык переносить. Это средняя нагрузка за последние ~6 недель, около ${n(f * 7)} в неделю. " +
            "Растёт медленно, от регулярных тренировок, и так же медленно уходит.$trend$early"
    }

    /**
     * Fatigue against fitness by the same lines as the zones (freshness below −10 % / above +10 %), so the words never say
     * "heavier than usual" while the zone says "balance".
     */
    fun fatigue(s: LoadStory.Story): String {
        val d = s.today
        val vs = when {
            d.fitness < 1e-6 -> ""
            d.formPct < -10 -> " Сейчас она выше тренированности: последняя неделя тяжелее привычной."
            d.formPct > LoadStory.FRESH -> " Сейчас она ниже тренированности: последняя неделя легче привычной, тело отдыхает."
            else -> " Сейчас она примерно равна тренированности: неделя как обычно."
        }
        return "Усталость ${r(d.fatigue)} — нагрузка за последние ~7 дней, около ${n(d.fatigue * 7)} в неделю. " +
            "Быстро растёт после тяжёлых дней и уходит за 2–3 дня отдыха.$vs"
    }

    fun freshness(s: LoadStory.Story): String =
        if (!s.history) "Свежесть появится через две недели после первой тренировки, когда тренировок будет хотя бы четыре. Раньше одна тяжёлая тренировка показала бы «−100 %»."
        else "Свежесть ${Ru.pct(s.today.formPct)} = (тренированность − усталость) / тренированность. Минус — ты устал сильнее " +
            "обычного, плюс — отдохнул. Немного в минусе — нормально: так тренированность и растёт."

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
            Zone.REST -> "Свежесть ниже твоей линии отдыха ($line). В таком состоянии тренировки у тебя чаще не получаются. " +
                "Отдохни день-два или сделай лёгкую тренировку, потом — как обычно." + since(s)
            Zone.BUILDING -> "Ты устал, но выше линии отдыха ($line). Это рабочая усталость: от неё тренированность и растёт. Тренируйся по плану."
            Zone.BALANCE -> "Нагрузка и отдых в равновесии. Тренируйся по плану."
            Zone.FRESH -> "Ты отдохнул: хорошее время для тяжёлой тренировки, контрольного забега или попытки рекорда."
            Zone.DETRAINING -> "Отдыхаешь дольше обычного, и тренированность начинает уходить." + since(s) + " Пора вернуться к тренировкам."
        }
    }

    private fun since(s: LoadStory.Story) = when (val d = s.daysSinceTraining) {
        null, 0 -> ""
        else -> " Последняя тренировка — ${Ru.days(d)} назад."
    }

    /** Where the rest line comes from. */
    fun personal(p: LoadStory.Personal): String = when {
        p.learned -> "Линия отдыха — твоя, ${Ru.pct(p.restBelow)}. Когда ты начинал тренировку более уставшим, не получались " +
            "${p.badBelow} из ${p.judgedBelow}. Когда свежее — только ${p.badAbove} из ${p.judgedAbove}. Так что ниже этой линии лучше отдохнуть."
        p.bad >= 3 && p.good >= 3 -> "Твои неудачные тренировки (${p.bad} из ${p.bad + p.good}) не связаны со свежестью: уставшим ты срываешься не чаще, " +
            "чем отдохнувшим. Значит, дело в другом: сон, еда или слишком большой вес. Линия отдыха остаётся общей: ${Ru.pct(p.restBelow)}."
        else -> "Линия отдыха — свежесть, ниже которой лучше отдохнуть. У тебя она пока общая: ${Ru.pct(p.restBelow)}. Когда наберётся " +
            "хотя бы по 3 удачных и неудачных тренировки (сейчас ${p.good} и ${p.bad}), приложение посмотрит, при какой свежести у " +
            "тебя не получается. И поставит линию под тебя."
    }

    fun level(w: LoadStory.Week): String = when (w.level) {
        Level.NONE -> "без тренировок"
        Level.LIGHT -> "легче обычной"
        Level.USUAL -> "обычная"
        Level.MORE -> "выше обычной"
        Level.SPIKE -> "резкий скачок"
        Level.ONGOING -> "неделя идёт"
    }

    fun week(w: LoadStory.Week): String = buildString {
        append(n(w.load))
        if (w.trainingDays > 0) append(" · ${Ru.days(w.trainingDays)}")
        w.ratio?.takeIf { w.level != Level.NONE && w.level != Level.ONGOING }?.let {
            append(" · ${r(it * 100)} % от обычной" + if (w.soFar) " к этому дню" else "")
        }
        w.lowestPct?.let { append(" · самая низкая свежесть ${Ru.pct(it)}") }
    }

    /** What the week level means; null when there is nothing to say. */
    fun levelNote(w: LoadStory.Week): String? = when (w.level) {
        Level.SPIKE -> "Нагрузка резко выросла, больше чем на 30 % от обычной недели. Так чаще всего и перегружаются. Следующую неделю лучше не наращивать."
        Level.LIGHT -> "Неделя легче обычной: разгрузка, пропуски или болезнь. Тренированность за неё почти не теряется."
        // Monday's one session, or a Thursday of someone who trains at weekends: either way the usual days are still ahead.
        Level.ONGOING -> "Неделя ещё идёт. Сравнить её с обычной можно, когда пройдут дни, в которые ты обычно тренируешься."
        else -> null
    }

    fun usual(s: LoadStory.Story): String? = s.usualWeek?.let {
        "Твоя обычная неделя — около ${n(it)}. Это середина последних четырёх недель с тренировками. «Выше обычной» — до +30 %, больше — уже скачок."
    }

    /**
     * A dip in words. Its lowest is a morning's (the day before's numbers), dated so: not to be read as the freshness now,
     * which [now] says (today's, as «Сегодня» shows it) while the dip goes on.
     */
    fun dip(d: LoadStory.Dip, now: Double? = null): String {
        val low = "самая низкая — ${Ru.pct(d.lowestPct)} (утром ${Ru.date(d.lowestDay)})"
        val rest = if (d.restDays > 0) "дней без тренировок: ${d.restDays}" else "без дня отдыха"
        return if (d.recovered == null) {
            when {
                // Today's own freshness is the lowest: no morning to name, and no "lowest" above "now".
                d.lowestNow && d.from == d.lowestDay -> "Сегодня свежесть ушла ниже линии отдыха: ${Ru.pct(d.lowestPct)}."
                d.lowestNow -> "С ${Ru.date(d.from)} ты ниже линии отдыха, и свежесть ещё падает: сейчас ${Ru.pct(d.lowestPct)}, ниже, чем за эти дни."
                else -> "С ${Ru.date(d.from)} ты ниже линии отдыха: $low" + (now?.let { ", сейчас ${Ru.pct(it)}" } ?: "") + "."
            } + " Нужен отдых: день-два или лёгкая тренировка" + (if (d.from == d.lowestDay && d.lowestNow) "." else " ($rest).")
        } else "${Ru.dates(d.from, d.to)} свежесть падала ниже линии отдыха, $low — нужен был отдых. Пришёл в норму к ${Ru.date(d.recovered)}, $rest."
    }

    fun noDips(weeks: Int) = "За ${Ru.weeks(weeks)} ни разу не опускался ниже линии отдыха — нагрузка была по силам."
}
