package forma.app.ui.screens

import forma.app.BuildConfig
import forma.app.data.AppJson
import forma.app.data.BackupManager
import forma.app.data.CoachActions
import forma.app.data.Kind
import forma.app.data.LiftPlan
import forma.app.data.RunSummary
import forma.app.data.TrainingReport
import forma.app.data.TrainingRepository
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
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
- Бег мешает силовой тем больше, чем чаще и дольше (Wilson и др., 2012)."""

/** The summary sent with a question: today, the training as the readable report has it, and how the app works. */
suspend fun coachContext(repo: TrainingRepository, today: LocalDate = repo.today(), reportChars: Int = 28_000): String {
    val program = repo.store.programNow()
    val settings = repo.store.settingsNow()
    val todayLines = program?.week?.on(today.dayOfWeek.value).orEmpty().map { s ->
        if (s.kind == SessionKind.LIFT) {
            val plan = runCatching { repo.liftPlan(s.templateId!!, today) }.getOrNull()
            "- Силовая «${plan?.title ?: s.templateId}»" + (plan?.let { p -> ": " + p.items.joinToString("; ") { "${it.name} ${it.prescription.sets}×${it.prescription.reps}" + (it.prescription.weight?.let { w -> " ${Ru.kg(w)} кг" } ?: "") } } ?: "")
        } else {
            val w = runCatching { repo.runFor(s.kind, today) }.getOrNull()
            "- Бег «${w?.title}»: ${w?.note.orEmpty()}"
        }
    }
    val b = BackupManager(repo).snapshot(points = false)
    val feel = TrainingReport.feelWords(settings.feels)
    val report = withContext(Dispatchers.Default) { TrainingReport.build(b, today, BuildConfig.VERSION_NAME, feel) }
    return buildString {
        appendLine("Сегодня ${Ru.date(today)} ${today.year}.")
        appendLine(if (todayLines.isEmpty()) "По плану сегодня отдых." else "По плану сегодня:\n" + todayLines.joinToString("\n"))
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
