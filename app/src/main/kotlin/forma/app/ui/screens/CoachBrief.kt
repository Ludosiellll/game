package forma.app.ui.screens

import forma.app.BuildConfig
import forma.app.data.AppJson
import forma.app.data.BackupManager
import forma.app.data.CoachActions
import forma.app.data.Feel
import forma.app.data.Kind
import forma.app.data.LiftItem
import forma.app.data.LiftPlan
import forma.app.data.RunSummary
import forma.app.data.TrainingReport
import forma.app.data.TrainingRepository
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.strength.DoneSet
import forma.core.strength.Progression
import forma.core.text.Ru
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * How the app decides, said for the coach: the coach explains the app's own reasons and changes things within them. Kept
 * to what the code does ([forma.core.strength.Progression], [forma.core.run.RunPlan], the repository's rules).
 */
internal const val COACH_RULES = """## Как решает приложение
Зал:
- У упражнения подходы × диапазон повторов и «запас» — сколько повторов ещё мог бы сделать. Вес на следующую тренировку — рабочий вес упражнения в этом диапазоне; он всегда кратен шагу веса упражнения (вниз).
- В первую неделю блока запас на 1 больше, в предпоследнюю — на 1 меньше; последняя неделя блока — разгрузка (меньше подходов, вес на 10 % легче). «Так себе» сегодня — тоже облегчённая тренировка.
- Последний подход каждого упражнения с весом (2+ подходов, не в разгрузку и не при подборе) — «N+»: сделать цель, потом ещё, пока идёт, и остановиться с 1 в запасе (с 2 — ноги в день тяжёлого бега и за 1–2 дня до него). Лишние повторы показывают, насколько стал сильнее.
- Получилось: новичок в базовых добавляет вес каждую тренировку (двойной шаг при 2+ лишних в «+»); остальные сначала поднимаются по диапазону повторов, потом добавляют вес шагом. 4+ лишних в «+» — сразу к весу по расчётному максимуму (не больше +10 %, новичку +15 %). Ничего лишнего в «+» — вес держится.
- Не получилось: если в подходах остановился сам с запасом — вес и цель держатся. Иначе, пока есть куда, сначала снижается цель повторов до сделанного (вес тот же); потом почти — тот же вес; явно — вес на полпути к показанному (не больше −10 % за раз); три тренировки подряд без прогресса — −10 % и с низа диапазона. Если вес уже снижали, а повторы не выросли, — дело не в весе: вес держится, цель — сделанные повторы. Никогда ниже 85 % лучшего расчётного максимума последних недель.
- После 2+ недель без упражнения — на 5–15 % легче. Первый раз — подбор веса: подходы с 1–2 в запасе.
- Поэтому вес «меньше, чем могу» бывает: цель — не отказ, а запас; рост идёт через повторы и «+». Если человек стабильно делает больше — можно предложить рабочий вес (set_working_weight, он подтверждает) или поправить шаг под его зал (set_weight_step).
- Во время тренировки (раздел «Идёт сейчас»): в обычном подходе — цель повторов и честный запас. По запасу приложение само поправит вес следующего подхода: запас на 2+ больше плана — тяжелее, меньше — легче (до ±10 % от плана). Лишние повторы — только в последнем подходе «+»: в обычных они утомляют следующие подходы, а запас и так покажет силу. Вес следующего подхода человек вписывает сам в поле на экране.
- Шаг веса меняется сразу, и в идущей тренировке: кнопки «−/+» и подсказки идут по новому шагу.
- Цель в зале с датой: за 9 дней до даты у упражнения цели вдвое меньше подходов, вес тот же, без «+» (подводка). В день цели оно первое — проверка максимума: разминка и 2 попытки по 1 разу. Подводка и проверка рабочий вес не меняют.
Бег:
- Темпы — от формы бега (VDOT) по контрольному забегу или результату.
- Лёгкий: пробежал целиком и тяжесть до 5 — следующий на 3 мин длиннее; пробежал дольше плана — на половину лишнего (3–8 мин); тяжесть 7+ — на 5 мин короче. Потолок под цель; неделя бега не больше +10 % к прошлой; одна пробежка не длиннее 110 % самой длинной за 30 дней.
- Длинный: тяжесть до 6 и целиком — растёт на 5–10 мин; 8+ или не добежал — на 5 мин короче.
- Тяжёлые (отрезки в темпе цели, интервалы, темповый) идут по лестнице: три подряд в темпе — форма +1 (не чаще раза в 4 недели; с целью до 3 км форму меняет только контрольный забег); три неудачи — форма −1. Контрольный забег раз в несколько недель.
- Ускорения: после лёгкого бега в неделю с тяжёлым бегом, 4 (6, когда бег регулярный), по 80 м за 15–20 с — быстро, но расслабленно и ровно, не в полную силу: скорость и техника без усталости.
- После «Болею»: 2 пробежки лёгкие, контрольного нет неделю, первая силовая облегчённая. После перерыва больше недели первая пробежка лёгкая.
- Оценка тяжести (1–10) после тренировки решает следующую: её можно исправить."""

/** The research the rules stand on (as in the project's notes), for the coach's "why". */
internal const val COACH_RESEARCH = """## Исследования, на которых стоят правила
- Запас повторов (RIR) как мера усилия — Helms и др., 2016/2018. «N+» подход — APRE, Mann и др., 2010.
- Рост силы за ~12 недель: ≈+40 % у нетренированных, +20 % у тренированных, +10 % у продвинутых (ACSM, 2009).
- Белок: прирост мышц выходит на плато около 1,6 г/кг в сутки (до ≈2,2) — Morton и др., 2018.
- Темпы бега по VDOT — Дэниелс. 80 % тренировок у сильных бегунов — лёгкие (Seiler, 2010).
- Одна пробежка не длиннее 110 % самой длинной за 30 дней — меньше травм (Frandsen и др., 2025).
- Без тренировок VO2max падает ≈7 % за 2–3 недели и ≈15 % к 8–12 неделям (Coyle и др., 1984).
- 1500 м — около 84 % аэробной работы (Spencer и Gastin, 2001): база и порог важнее быстрых кругов.
- Ровная раскладка выгоднее рывка в начале на дистанциях длиннее ~2 минут (Abbiss и Laursen, 2008).
- Бег мешает силовой тем больше, чем чаще и дольше (Wilson и др., 2012).
- Икры (камбаловидная и икроножная) — главные мышцы толчка в беге на обычных скоростях (Dorn и др., 2012); слабые икры — риск перегрузки ахилла (Mahieu и др., 2006). Присед и жим ногами их почти не нагружают. Практический минимум для бегуна без них в плане — 2–3 подхода подъёмов на носки 1–2 раза в неделю (это совет, не число из исследования)."""

/** The summary sent with a question: today, the training as the readable report has it, and how the app works. */
suspend fun coachContext(repo: TrainingRepository, today: LocalDate = repo.today(), reportChars: Int = 28_000): String {
    val program = repo.store.programNow()
    val settings = repo.store.settingsNow()
    // The day as the Today screen has it: a pause has no sessions, «Так себе» makes them lighter, «Болею» none.
    val feel = settings.feelOn(today)
    val paused = repo.paused(settings, today)
    val light = feel == Feel.LOW
    // What of today is done or going on: the person asks «я начал?», and the plan alone does not say.
    val active = repo.db.workouts().active()
    val doneToday = repo.db.workouts().allDone().filter { it.epochDay == today.toEpochDay() }
    val todayLines = if (paused) emptyList() else program?.week?.on(today.dayOfWeek.value).orEmpty().map { s ->
        if (s.kind == SessionKind.LIFT) {
            val state = when {
                active?.kind == Kind.LIFT && active.templateId == s.templateId -> " — НАЧАТА, идёт сейчас (раздел «Идёт сейчас»)"
                doneToday.any { it.kind == Kind.LIFT && it.templateId == s.templateId } -> " — сделана сегодня"
                else -> " — ещё не начата"
            }
            val plan = runCatching { repo.liftPlan(s.templateId!!, today, forceDeload = light) }.getOrNull()
            "- Силовая «${plan?.title ?: s.templateId}»$state" + (plan?.let { p -> ": " + p.items.joinToString("; ") { "${it.name} ${it.prescription.sets}×${it.prescription.reps}" + (it.prescription.weight?.let { w -> " ${Ru.kg(w)} кг" } ?: "") } } ?: "")
        } else {
            val w = runCatching { repo.runFor(s.kind, today, light) }.getOrNull()
            "- Бег «${w?.title}»" + (if (doneToday.any { it.kind == Kind.RUN }) " — пробежка сегодня уже есть" else "") + ": ${w?.note.orEmpty()}"
        }
    }
    val easyLeft = repo.easyAfterSick(today)
    val liftAfterSick = repo.liftAfterSick(today)
    val b = BackupManager(repo).snapshot(points = false)
    val feels = TrainingReport.feelWords(settings.feels)
    val report = withContext(Dispatchers.Default) { TrainingReport.build(b, today, BuildConfig.VERSION_NAME, feels) }
    return buildString {
        appendLine("Сегодня ${Ru.date(today)} ${today.year}.")
        when {
            paused -> appendLine("Сегодня пауза в плане до ${Ru.date(LocalDate.ofEpochDay(settings.pauseUntil!!))}: тренировок по плану нет.")
            feel == Feel.SICK -> appendLine("Сегодня человек отметил «Болею»: тренироваться не нужно, приложение предлагает отдых или паузу.")
            feel == Feel.LOW -> appendLine("Сегодня самочувствие «Так себе»: тренировки дня облегчены — в силовой вдвое меньше подходов и вес на 10 % легче, бег только лёгкий (ниже уже облегчённые).")
            feel == Feel.GOOD -> appendLine("Сегодня самочувствие «Хорошо».")
            else -> {}
        }
        if (easyLeft > 0 || liftAfterSick) {
            appendLine(
                "После болезни: " + listOfNotNull(
                    if (easyLeft > 0) "ещё $easyLeft ${Ru.plural(easyLeft, "пробежка", "пробежки", "пробежек")} только лёгкие" else null,
                    if (liftAfterSick) "первая силовая облегчённая" else null,
                ).joinToString(", ") + ".",
            )
        }
        if (!paused) {
            val head = if (feel == Feel.SICK) "По плану было (человек болеет, не делает):" else "По плану сегодня:"
            appendLine(if (todayLines.isEmpty()) "По плану сегодня отдых." else head + "\n" + todayLines.joinToString("\n"))
        }
        val now = inProgress(repo)
        if (now.isNotEmpty()) {
            appendLine()
            append(now)
        } else {
            appendLine("Сейчас тренировка не идёт (ничего не начато).")
        }
        program?.week?.let { wk ->
            val names = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
            fun days(f: (forma.core.plan.PlannedSession) -> Boolean) = wk.days.filter { d -> d.sessions.any(f) }.map { names[it.day - 1] }
            val gym = days { it.kind == SessionKind.LIFT }
            val run = days { it.isRun }
            appendLine("Неделя: " + (if (gym.isEmpty()) "без зала" else "зал — ${gym.joinToString(", ")}") + "; " + (if (run.isEmpty()) "без бега" else "бег — ${run.joinToString(", ")}") + ".")
        }
        appendLine()
        appendLine("## Цели человека — каждое изменение сверяй с ними")
        val profile = repo.store.profileNow()
        appendLine("- Бег: " + (profile?.runGoal?.let { g -> "${g.meters} м за ${Ru.clock(g.seconds)}" + (g.date?.let { ", забег ${Ru.date(LocalDate.ofEpochDay(it))}" } ?: "") } ?: "цели нет") + ".")
        appendLine("- Зал: " + (forma.app.data.GymGoals.words(repo) ?: "цели нет (поставить — функция set_gym_goal)."))
        appendLine()
        if (settings.coachNotes.isNotEmpty()) {
            appendLine("## Что ты знаешь о человеке (запомнено)")
            settings.coachNotes.forEach { appendLine("- $it") }
            appendLine()
        }
        appendLine(exercises(repo, today))
        appendLine(running(repo, today))
        changes(repo, today).takeIf { it.isNotEmpty() }?.let { appendLine(it) }
        // The report's legend is at its end, past what the cut keeps once there are weeks of workouts: it goes first.
        val legend = report.indexOf("## Обозначения").takeIf { it >= 0 }?.let { from ->
            val to = report.indexOf("\n## ", from + 1).takeIf { it > 0 } ?: report.length
            report.substring(from, to)
        }
        legend?.let { appendLine(it.trim()); appendLine() }
        appendLine((if (legend != null) report.replace(legend, "") else report).take(reportChars))
        appendLine()
        appendLine(COACH_RULES)
        appendLine()
        appendLine(COACH_RESEARCH)
        appendLine()
        appendLine("## Как устроено приложение")
        SCREENS.forEach { (name, text) -> appendLine("- $name: $text") }
        appendLine("## Словарь приложения")
        GLOSSARY.forEach { (word, text) -> appendLine("- $word: $text") }
    }
}

/**
 * The gym session going on now, as its screen has it: each exercise's sets done, the one now, the next set the app
 * gives. Empty when none is. The coach answers «какой сейчас подход» from it and changes it with the workout_* levers.
 */
internal suspend fun inProgress(repo: TrainingRepository): String = buildString {
    val w = repo.db.workouts().active()?.takeIf { it.kind == Kind.LIFT } ?: return@buildString
    val plan = repo.planOf(w) ?: return@buildString
    val sets = repo.db.sets().forWorkout(w.id).sortedBy { it.doneAt }
    val settings = repo.store.settingsNow()
    val bw = repo.store.profileNow()?.bodyWeightKg ?: 75.0
    val bias = repo.rirBias(before = w.id)
    val zone = java.time.ZoneId.systemDefault()
    val start = java.time.Instant.ofEpochMilli(w.startedAt).atZone(zone)
    val minutes = ((repo.nowMs() - w.startedAt) / 60_000).coerceAtLeast(0)
    val started = if (start.toLocalDate() == repo.today()) "%02d:%02d".format(start.hour, start.minute) else "${Ru.date(start.toLocalDate())}, %02d:%02d".format(start.hour, start.minute)
    appendLine("## Идёт сейчас: силовая «${plan.title}»")
    appendLine(
        "Начата в $started ($minutes мин назад)" + (if (plan.deload) ", облегчённая" else "") + ". Подходы человек отмечает на экране тренировки, " +
            "вес следующего подхода вписывает там же; оценку всей тренировки ставит кнопкой «Закончить». Менять её: функции workout_*.",
    )
    val by = sets.groupBy { it.position }
    fun count(i: LiftItem) = by[i.position]?.size ?: 0
    // Now: the exercise of the last set while it has sets left, else the first one with sets left in the day's order.
    val last = sets.lastOrNull()
    val current = plan.items.firstOrNull { it.position == last?.position && count(it) < it.prescription.sets }
        ?: plan.items.firstOrNull { count(it) < it.prescription.sets }
    for (i in plan.items) {
        val ex = forma.core.model.Catalog[i.exerciseId].let { e -> settings.steps[e.id]?.let { e.copy(step = it, fineStep = null) } ?: e }
        val plate = ex.load == forma.core.model.LoadKind.BODYWEIGHT_PLUS
        fun kg(x: Double) = when {
            plate && x < 0 -> "помощь ${Ru.kg(-x)} кг"
            plate -> "+${Ru.kg(x)} кг"
            else -> "${Ru.kg(x)} кг" + if (ex.perHand) " (каждая гантель)" else ""
        }
        val p = i.prescription
        val planned = when (p.phase) {
            forma.core.strength.Phase.TEST -> "проверка максимума цели: попытки по 1 разу — " + p.attempts.orEmpty().joinToString(", потом ") { kg(it) }
            else -> "${p.sets} × ${p.reps}" + (p.weight?.let { " по ${kg(it)}" } ?: if (p.calibrate) " (вес подбирается)" else "") +
                ", запас ${p.rir}" + (if (p.top) ", последний подход «${p.reps}+»" else "") +
                (if (p.phase == forma.core.strength.Phase.TAPER) " (подводка к цели)" else "") + ", отдых ${Ru.clock(i.slot.restSec)}"
        }
        val done = by[i.position].orEmpty()
        val doneText = if (done.isEmpty()) "ещё не начато" else
            "сделано ${done.size} из ${p.sets}: " + done.joinToString(", ") { "${kg(it.weight)} × ${it.reps}" + (it.rir?.let { r -> " (запас $r)" } ?: "") }
        val mark = when {
            i === current -> " ← СЕЙЧАС"
            done.size >= p.sets -> " — готово"
            else -> ""
        }
        appendLine("- ${i.name} [${i.exerciseId}]: план $planned; $doneText$mark")
        if (i === current && done.isNotEmpty()) {
            val ns = Progression.nextSet(ex, p, done.map { DoneSet(it.weight, it.reps, forma.core.strength.RirScale.correct(it.rir, bias)) }, bw)
            ns?.let { appendLine("  Подход ${done.size + 1}: приложение даёт ${kg(it.weight)} × ${it.reps}" + (if (it.plus) "+" else "") + ".") }
        }
    }
    val left = plan.items.count { count(it) < it.prescription.sets }
    appendLine(if (left == 0) "Все подходы сделаны — осталось нажать «Закончить» и оценить тренировку." else "Упражнений с подходами впереди: $left.")
}

/** The gym plan's exercises with what the functions need: ids, numbers now, the next weight, the step, the estimate. */
private suspend fun exercises(repo: TrainingRepository, today: LocalDate): String = buildString {
    val program = repo.store.programNow() ?: return@buildString
    if (program.templates.isEmpty()) return@buildString
    appendLine("## Упражнения в плане (id — для функций)")
    for (t in program.templates) {
        val plan = runCatching { repo.liftPlan(t.id, today) }.getOrNull() ?: continue
        appendLine("### «${t.title}» (id ${t.id})" + if (plan.deload) " — сейчас облегчённая" else "")
        for (i in plan.items) {
            val p = i.prescription
            val stat = repo.db.state().stat(i.exerciseId)
            // The working weight the levers reason from (set_working_weight compares with it): in a lighter week, after
            // being sick or on a «Так себе» day the session's weight is less, and that is not the weight to raise or cut.
            val work = repo.db.state().slot(i.slot.key)?.weight
            val ex = forma.core.model.Catalog.find(i.exerciseId)
            // A plate on pull-ups is on top of the body; the estimate is of the whole load: said so, or 0 vs 85 kg reads as room.
            val plate = ex?.load == forma.core.model.LoadKind.BODYWEIGHT_PLUS
            val each = if (ex?.perHand == true) " (каждая гантель)" else ""
            val parts = listOfNotNull(
                "${p.sets} × ${i.slot.repMin}–${i.slot.repMax}" + (if (p.reps != i.slot.repMin) " (цель сейчас ${p.reps})" else ""),
                "запас ${p.rir}",
                when {
                    p.calibrate -> "вес ещё подбирается"
                    p.weight != null && plate -> if (p.weight!! < 0) "помощь ${Ru.kg(-p.weight!!)} кг" else "следующий вес +${Ru.kg(p.weight!!)} кг к весу тела"
                    p.weight != null -> "следующий вес ${Ru.kg(p.weight!!)} кг$each"
                    else -> null
                },
                work?.takeIf { w -> p.weight != null && kotlin.math.abs(w - p.weight!!) > 1e-6 }?.let { w ->
                    "рабочий вес ${if (plate && w > 0) "+" else ""}${Ru.kg(w)} кг$each (на этой тренировке легче)"
                },
                if (p.weight != null || p.calibrate) "шаг ${Ru.kg(i.step)} кг$each" else null,
                "отдых ${Ru.clock(i.slot.restSec)}",
                stat?.let { "расчётный максимум ${Ru.kg(it.e1rm)} кг" + if (plate) " (вместе с весом тела)" else each },
                if (i.addedForGood) "добавлено человеком" else null,
            )
            appendLine("- ${i.name} [${i.exerciseId}]: ${parts.joinToString(", ")}")
        }
    }
}

/** Running now: the lengths, their caps, the fitness, the goal and the runs of the week ahead. */
private suspend fun running(repo: TrainingRepository, today: LocalDate): String = buildString {
    val program = repo.store.programNow() ?: return@buildString
    if (program.week.days.none { d -> d.sessions.any { it.isRun } }) return@buildString
    val st = repo.store.runStateNow()
    val profile = repo.store.profileNow()
    val caps = ProgramGenerator.runCaps(program, profile?.runGoal?.middleDistance == true)
    appendLine("## Бег сейчас")
    appendLine(
        "- Лёгкий бег ${st.easyMinutes} мин (потолок ${caps.easyMax}), длинный ${st.longMinutes} мин (потолок ${caps.longMax})" +
            (st.longestRecent(today.toEpochDay() + 1)?.let { ", самая длинная за 30 дней $it мин" } ?: "") + ".",
    )
    st.vdot?.let { appendLine("- Форма бега (VDOT) ${Ru.one(it)}.") }
    profile?.runGoal?.let { g -> appendLine("- Цель: ${g.meters} м за ${Ru.clock(g.seconds)}" + (g.date?.let { ", забег ${Ru.date(LocalDate.ofEpochDay(it))}" } ?: "") + ".") }
    appendLine("- " + (profile?.lapMeters?.let { "Бегает на стадионе, круг $it м." } ?: "Бегает не на стадионе (по GPS)."))
    appendLine("- Ближайшие пробежки:")
    for (d in 0L..6L) {
        val date = today.plusDays(d)
        if (repo.paused(repo.store.settingsNow(), date)) continue
        program.week.on(date.dayOfWeek.value).filter { it.isRun }.forEach { s ->
            runCatching { repo.runFor(s.kind, date) }.getOrNull()?.let { appendLine("  - ${Ru.date(date)}: ${it.title}") }
        }
    }
}

/** The coach's own changes in force and the last ones made, so it neither repeats nor forgets them. */
private suspend fun changes(repo: TrainingRepository, today: LocalDate): String = buildString {
    val s = repo.store.settingsNow()
    val terms = listOfNotNull(
        s.coachStrides?.takeIf { it.on(today) }?.let { "ускорений после лёгкого — ${it.value}, до ${Ru.date(LocalDate.ofEpochDay(it.until))}" },
        s.coachLong?.takeIf { it.on(today) }?.let { "последний лёгкий бег недели — длинный, до ${Ru.date(LocalDate.ofEpochDay(it.until))}" },
    )
    val log = repo.store.coachLogNow().takeLast(12)
    if (terms.isEmpty() && log.isEmpty()) return@buildString
    appendLine("## Твои изменения")
    terms.forEach { appendLine("- Действует: $it.") }
    val zone = java.time.ZoneId.systemDefault()
    log.forEach { e ->
        val day = java.time.Instant.ofEpochMilli(e.at).atZone(zone).toLocalDate()
        appendLine("- ${Ru.date(day)}: ${e.text}" + if (e.undone) " (человек вернул как было)" else "")
    }
}

/** One finished workout for the coach's review: what was planned and done, the app's own debrief, the rests taken. */
suspend fun coachWorkoutFacts(repo: TrainingRepository, workoutId: Long): String? {
    val w = repo.db.workouts().get(workoutId) ?: return null
    val sets = repo.db.sets().forWorkout(workoutId)
    val run = if (w.kind == Kind.RUN) repo.db.runs().get(workoutId) else null
    val day = LocalDate.ofEpochDay(w.epochDay)
    return buildString {
        appendLine("## Разбираемая тренировка: ${Ru.date(day)} ${day.year} — ${w.title}")
        append(TrainingReport.workout(w, sets, run))
        if (w.kind == Kind.LIFT) {
            val plan = w.planJson?.let { runCatching { AppJson.decodeFromString(LiftPlan.serializer(), it) }.getOrNull() }
            val rests = sets.groupBy { it.position }.toSortedMap().mapNotNull { (pos, list) ->
                val done = list.sortedBy { it.doneAt }
                if (done.size < 2) return@mapNotNull null
                val gaps = done.zipWithNext { a, b -> ((b.doneAt - a.doneAt) / 1000).toInt() }
                val planned = plan?.items?.firstOrNull { it.position == pos }?.slot?.restSec
                "- ${plan?.items?.firstOrNull { it.position == pos }?.name ?: done.first().exerciseId}: " +
                    gaps.joinToString(", ") { Ru.clock(it) } + (planned?.let { " (план отдыха ${Ru.clock(it)})" } ?: "")
            }
            if (rests.isNotEmpty()) {
                appendLine("Время между отметками подходов (отдых и сам подход):")
                rests.forEach { appendLine(it) }
            }
        } else if (run != null) {
            val plan = runCatching { AppJson.decodeFromString(forma.core.run.RunWorkout.serializer(), run.planJson) }.getOrNull()
            val summary = w.summaryJson?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() }
            if (plan != null) {
                val trial = summary?.outcomes?.filterIsInstance<forma.core.run.RunOutcome.TestTime>()?.firstOrNull()?.let { it.meters to it.seconds }
                val reached = if (trial != null) summary?.outcomes?.any { it is forma.core.run.RunOutcome.GoalReached } == true else null
                val profile = repo.store.profileNow()
                val items = forma.core.run.RunReview.of(
                    forma.core.run.RunReview.Run(plan, summary?.laps.orEmpty(), w.rpe, run.movingSec, run.avgPace, run.workPace, trial, run.completed, reached),
                    profile?.runGoal, gym = profile?.aim?.lifts != false,
                )
                appendLine("Разбор приложения:")
                items.forEach { appendLine("- ${it.title}: ${it.text}") }
                appendLine("Как бежать было по плану: ${plan.note}")
            }
        }
    }
}

/** The question the review card asks about a workout. */
internal fun reviewQuestion(title: String, day: LocalDate) =
    "Разбери мою тренировку ${Ru.date(day)} «$title»: что получилось, что не так и что сделать в следующий раз. Коротко, по моим цифрам."

/** The functions the coach is offered with a question. */
internal val coachTools get() = CoachActions.declarations
