package forma.app.data

import forma.app.ui.Texts
import forma.core.load.LoadExplain
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.LoadKind
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.model.Sex
import forma.core.plan.SessionKind
import forma.core.run.PaceTable
import forma.core.run.RunOutcome
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import forma.core.run.Vdot
import forma.core.strength.SlotRole
import forma.core.text.Ru
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Everything the app knows, as a text a person or an AI reads: who, what for, the plan, where things stand and every
 * session with every set and lap, with what the app concluded from it. Not a backup (that is the JSON one): made to be
 * read and sent for an analysis.
 */
object TrainingReport {
    private val DAYS = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    private val MONTHS = listOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")
    private val zone = ZoneId.systemDefault()

    fun fileName(today: LocalDate) = "forma-otchet-$today.md"

    /** The stored feel of the days ([Feel] names by epoch day) as said in the file. */
    fun feelWords(feels: Map<Long, String>): Map<Long, String> = feels.mapNotNull { (day, name) ->
        when (name) {
            Feel.GOOD.name -> "хорошо"
            Feel.LOW.name -> "так себе"
            Feel.SICK.name -> "болею"
            else -> null
        }?.let { day to it }
    }.toMap()

    /** "18 лет", "21 год", "22 года". */
    fun years(n: Int) = "$n ${Ru.plural(n, "год", "года", "лет")}"

    fun build(b: Backup, today: LocalDate, version: String, feel: Map<Long, String> = emptyMap()): String = buildString {
        val p = b.profile
        val done = b.workouts.filter { it.status == Status.DONE }.sortedByDescending { it.startedAt }
        appendLine("# Форма — все тренировки и прогресс")
        appendLine()
        appendLine("Выгрузка от ${Ru.date(today)} ${today.year}, приложение «Форма» $version. Тренировок: ${done.size}" +
            (done.lastOrNull()?.let { ", первая — ${Ru.date(LocalDate.ofEpochDay(it.epochDay))} ${LocalDate.ofEpochDay(it.epochDay).year}" } ?: "") + ".")
        appendLine()
        appendLine("> Для разбора с ИИ: отправь этот файл и попроси, например: «Разбери мои тренировки: что идёт хорошо, где застой, " +
            "не перегружаюсь ли я, что поменять в плане, чтобы быстрее прийти к цели». Обозначения — в конце файла.")
        appendLine()

        months(b, done, today)

        if (p != null) {
            appendLine("## Кто тренируется")
            appendLine()
            appendLine("- ${if (p.sex == Sex.MALE) "Мужчина" else "Женщина"}, ${years(p.age(today.year))}" +
                (p.heightCm?.let { ", рост $it см" } ?: "") + ", вес ${Ru.kg(b.bodyWeights.maxByOrNull { it.epochDay }?.kg ?: p.bodyWeightKg)} кг.")
            // The gym's experience only of who lifts, the running level only of who runs.
            listOfNotNull(
                "Опыт в зале: ${experience(p.experience)}".takeIf { p.aim.lifts },
                "Бег: ${runLevel(p.runLevel)}".takeIf { p.aim.runs },
            ).takeIf { it.isNotEmpty() }?.let { appendLine("- " + it.joinToString(". ") + ".") }
            appendLine("- Цель: зал — ${lift(p.aim.lift)}, бег — ${run(p.aim.run)}, приоритет — ${emphasis(p.aim.emphasis)}.")
            p.runGoal?.let { g -> appendLine("- Беговая цель: ${dist(g.meters)} за ${Ru.clock(g.seconds)} (темп ${Ru.pace(g.pace)} /км).") }
            p.lapMeters?.let { appendLine("- Бегает на стадионе, круг $it м.") }
            if (p.ownWeek) appendLine("- Своя неделя: " + listOfNotNull(
                p.gymDays.orEmpty().takeIf { it.isNotEmpty() }?.let { "зал — ${days(it)}" },
                p.runDays.orEmpty().takeIf { it.isNotEmpty() }?.let { "бег — ${days(it)}" },
            ).joinToString(", ") + ".")
            else appendLine("- Дни тренировок: ${days(p.trainingDays)}, до ${p.sessionMinutes} мин.")
            val rm = p.raceMeters
            val rs = p.raceSeconds
            if (rm != null && rs != null) appendLine("- Известный забег: ${Ru.distance(rm)} за ${Ru.clock(rs.roundToInt())}.")
            appendLine()
            goalPath(b, p)
        }
        b.program?.let { prog ->
            appendLine("## План")
            appendLine()
            prog.copiedFrom?.let { appendLine("Скопирован у: $it.") }
            // After a pause the block starts again from the week of return, which may be ahead.
            val start = LocalDate.ofEpochDay(prog.startEpochDay)
            appendLine((if (start.isAfter(today)) "Новый блок — с ${Ru.date(start)}." else "Начат ${Ru.date(start)}.") +
                if (prog.blockWeeks > 0) " Блок ${prog.blockWeeks} нед., последняя — разгрузка." else " Разгрузка — по необходимости.")
            appendLine()
            prog.week.days.sortedBy { it.day }.forEach { d ->
                if (d.sessions.isEmpty()) return@forEach
                appendLine("- ${DAYS[d.day - 1]}: " + d.sessions.joinToString(" + ") { s ->
                    when (s.kind) {
                        SessionKind.LIFT -> "зал «${prog.templates.firstOrNull { it.id == s.templateId }?.title ?: s.templateId}»"
                        SessionKind.RUN_EASY -> "лёгкий бег"
                        SessionKind.RUN_QUALITY -> "тяжёлая пробежка"
                        SessionKind.RUN_LONG -> "длинная пробежка"
                    }
                })
            }
            appendLine()
            prog.templates.forEach { t ->
                appendLine("### ${t.title}")
                appendLine()
                // The plan as this person does it: own swaps, removed and added exercises, sets and reps.
                val spec = TrainingRepository.shareable(prog, b.settings).templates.firstOrNull { it.id == t.id } ?: t
                spec.slots.forEach { s ->
                    val ex = Catalog.find(s.exerciseId)
                    val reps = if (s.repMin == s.repMax) "${s.repMin}" else "${s.repMin}–${s.repMax}"
                    val unit = if (ex?.load == LoadKind.TIMED) " с" else ""
                    appendLine("- ${name(s.exerciseId)}: ${s.sets} × $reps$unit, запас ${s.rir}, отдых ${Ru.clock(s.restSec)} — ${role(s.role)}")
                }
                appendLine()
            }
        }

        appendLine("## Где сейчас")
        appendLine()
        // Running only of who runs; the long run and the ladders only when the week holds them.
        val sessions = b.program?.week?.days?.flatMap { it.sessions }.orEmpty()
        b.runState?.takeIf { p?.aim?.runs != false }?.let { st ->
            appendLine("### Бег")
            appendLine()
            val v = st.vdot
            if (v != null) {
                val t = PaceTable.forVdot(v)
                // The goal distance first (800 m, 3 km...), then the usual ones.
                val forecast = listOf(b.profile?.runGoal?.meters ?: 1500, 5000, 10000).distinct()
                appendLine("- VDOT ${Ru.one(v)}. Прогноз: " + forecast.joinToString(", ") { m -> "${dist(m)} — ${Ru.clock(Vdot.predictSeconds(v, m.toDouble()).roundToInt())}" } + ".")
                appendLine("- Темпы, мин/км: лёгкий ${Ru.pace(t.easy.fast.toDouble())}–${Ru.pace(t.easy.slow.toDouble())}, " +
                    "пороговый ${Ru.pace(t.threshold.toDouble())}, интервальный ${Ru.pace(t.interval.toDouble())}, повторный ${Ru.pace(t.repetition.toDouble())}.")
            } else appendLine("- VDOT ещё не рассчитан (нет контрольного забега).")
            if (!st.canRunContinuously) appendLine("- Программа бега с ходьбой: неделя ${st.walkStage / 3 + 1} из 9.")
            appendLine("- Лёгкий бег — ${st.easyMinutes} мин" +
                (if (sessions.any { it.kind == SessionKind.RUN_LONG }) ", длинный — ${st.longMinutes} мин." else ".") +
                (if (sessions.none { it.kind == SessionKind.RUN_QUALITY }) "" else " Шаги лестниц: интервалы ${st.intervalStage + 1}, " +
                    "темповый ${st.tempoStage + 1}" + (b.profile?.runGoal?.takeIf { it.middleDistance }?.let { ", темп цели ${st.goalStage + 1} из ${forma.core.run.RunPlan.goalLadderSize}" } ?: "") + "."))
            if (st.rpeOffset > 0) appendLine("- Своя шкала тяжести: лёгкий бег оценивается строже нормы, пороги на ${st.rpeOffset} выше.")
            appendLine()
        }
        if (b.stats.isNotEmpty()) {
            appendLine("### Зал: расчётный максимум на 1 раз")
            appendLine()
            forma.core.strength.RirScale.explain(TrainingRepository.rirBiasOf(b.workouts.filter { it.status == Status.DONE }, b.sets))?.let { appendLine(it); appendLine() }
            b.stats.sortedByDescending { it.e1rm }.forEach { s ->
                val ex = Catalog.find(s.exerciseId)
                if (ex?.load == LoadKind.TIMED) return@forEach
                val next = b.slots.filter { it.exerciseId == s.exerciseId }.maxByOrNull { it.updatedAt }
                // Pull-ups and dips: the maximum is of the body with the extra weight; the next target is the extra weight ("+12,5 кг").
                val withBody = if (ex?.load == LoadKind.BODYWEIGHT_PLUS) ", с весом тела" else ""
                appendLine("- ${name(s.exerciseId)}: ${half(s.e1rm)} кг (лучший ${half(s.best)}$withBody)" +
                    (next?.weight?.let { w -> "; следующая цель ${weight(ex, w)} × ${next.reps}" } ?: ""))
            }
            appendLine()
        }
        TrainingRepository.storyOf(done, today)?.let { st ->
            appendLine("### Нагрузка и восстановление")
            appendLine()
            st.zone?.let { appendLine("**${LoadExplain.zoneTitle(it)}.** ${LoadExplain.zoneText(st)}"); appendLine() }
            appendLine("- ${LoadExplain.fitness(st)}")
            appendLine("- ${LoadExplain.fatigue(st)}")
            appendLine("- ${LoadExplain.freshness(st)}")
            appendLine("- ${LoadExplain.personal(st.personal)}")
            LoadExplain.usual(st)?.let { appendLine("- $it") }
            appendLine()
            appendLine("Недели (сначала новые):")
            st.weeks.reversed().forEach { w -> appendLine("- ${Ru.dates(w.monday, w.monday.plusDays(6))}: ${LoadExplain.level(w)} — ${LoadExplain.week(w)}") }
            appendLine()
            if (st.history) {
                if (st.dips.isEmpty()) appendLine(LoadExplain.noDips(8))
                else {
                    appendLine("Провалы свежести (сначала новые):")
                    st.dips.sortedByDescending { it.from }.forEach { appendLine("- ${LoadExplain.dip(it)}") }
                }
                appendLine()
            }
        }
        planWeeks(b, done, today)

        appendLine("## Тренировки (сначала новые)")
        appendLine()
        val setsBy = b.sets.groupBy { it.workoutId }
        val runsBy = b.runs.associateBy { it.workoutId }
        done.forEach { w ->
            val day = LocalDate.ofEpochDay(w.epochDay)
            val start = Instant.ofEpochMilli(w.startedAt).atZone(zone).toLocalTime()
            appendLine("### ${Ru.date(day)} ${day.year}, ${DAYS[day.dayOfWeek.value - 1]}, ${"%02d:%02d".format(start.hour, start.minute)} — ${w.title}")
            appendLine()
            val head = listOfNotNull(
                if (w.kind == Kind.LIFT) "зал" else "бег",
                w.durationSec?.let { "${(it / 60.0).roundToInt()} мин" },
                w.rpe?.let { "тяжесть $it/10" },
                w.load?.let { "нагрузка ${it.roundToInt()}" },
                if (w.deload) "разгрузка" else null,
                feel[w.epochDay]?.let { "самочувствие: $it" },
                TrainingRepository.verdict(w)?.let { if (it) "получилось" else "не всё получилось" },
            )
            appendLine(head.joinToString(" · "))
            appendLine()
            if (w.kind == Kind.LIFT) lift(w, setsBy[w.id].orEmpty()) else runsBy[w.id]?.let { run(w, it) }
            w.note?.takeIf { it.isNotBlank() }?.let { appendLine("Заметка: $it"); appendLine() }
        }

        if (b.bodyWeights.isNotEmpty()) {
            appendLine("## Вес тела")
            appendLine()
            b.bodyWeights.sortedBy { it.epochDay }.forEach { appendLine("- ${LocalDate.ofEpochDay(it.epochDay)}: ${Ru.kg(it.kg)} кг") }
            appendLine()
        }

        appendLine("## Обозначения")
        appendLine()
        appendLine("- «80 × 8, запас 2» — 80 кг на 8 повторов, и ещё 2 повтора можно было сделать (RIR). «план» — что приложение предлагало.")
        appendLine("- Тяжесть 1–10 — оценка тренировки в конце (RPE). ${LoadExplain.UNITS}")
        appendLine("- Расчётный максимум — вес на 1 повтор по формуле из веса и повторов (e1RM). У подтягиваний и отжиманий на брусьях " +
            "он вместе с весом тела (вес тела + отягощение), а «+12,5 кг» в цели — только отягощение.")
        appendLine("- VDOT — беговая форма по таблицам Дэниелса. Темп — мин:с на километр.")
        appendLine("- «Итог» — что приложение решило после тренировки: вес, повторы, шаг лестницы, длительность следующей.")
    }

    private fun summaryOf(w: WorkoutEntity): RunSummary? =
        w.summaryJson?.takeIf { w.kind == Kind.RUN }?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() }

    private fun liftSummaryOf(w: WorkoutEntity): LiftSummary? =
        w.summaryJson?.takeIf { w.kind == Kind.LIFT }?.let { runCatching { AppJson.decodeFromString(LiftSummary.serializer(), it) }.getOrNull() }

    /**
     * Month by month, newest first, at most a year: sessions done, kilometres, sets, the best time trial, the one-rep
     * estimates of the main lifts (the best of the month) and the last body weight of the month.
     */
    private fun StringBuilder.months(b: Backup, done: List<WorkoutEntity>, today: LocalDate) {
        if (done.isEmpty()) return
        val main = b.program?.let { prog ->
            TrainingRepository.shareable(prog, b.settings).templates.flatMap { t -> t.slots.filter { it.role == SlotRole.MAIN }.map { it.exerciseId } }
        }.orEmpty().distinct().filter { Catalog.find(it)?.load != LoadKind.TIMED }.take(3)
        val runsBy = b.runs.associateBy { it.workoutId }
        val setsBy = b.sets.groupBy { it.workoutId }
        val byMonth = done.groupBy { LocalDate.ofEpochDay(it.epochDay).withDayOfMonth(1) }
        val first = byMonth.keys.min()
        val months = generateSequence(today.withDayOfMonth(1)) { it.minusMonths(1) }.takeWhile { !it.isBefore(first) }.take(12).toList()
        appendLine("## Сводка по месяцам (сначала новые)")
        appendLine()
        appendLine("| Месяц | Тренировок (зал / бег) | Км | Подходов | Лучший контрольный | " +
            main.joinToString("") { "${name(it)}, макс. | " } + "Вес тела |")
        appendLine("|" + " --- |".repeat(6 + main.size))
        months.forEach { m ->
            val ws = byMonth[m].orEmpty()
            val lifts = ws.count { it.kind == Kind.LIFT }
            val runs = ws.count { it.kind == Kind.RUN }
            val km = ws.mapNotNull { runsBy[it.id]?.distanceM }.sum() / 1000
            val sets = ws.sumOf { setsBy[it.id].orEmpty().size }
            val trial = ws.flatMap { w -> summaryOf(w)?.outcomes.orEmpty().filterIsInstance<RunOutcome.TestTime>() }
                .filter { it.meters > 0 && it.seconds > 0 }
                .maxByOrNull { Vdot.fromRace(it.meters.toDouble(), it.seconds.toDouble()) }
            val best = main.map { id ->
                ws.mapNotNull { w -> liftSummaryOf(w)?.reports?.firstOrNull { it.exerciseId == id }?.e1rm }.maxOrNull()
            }
            val end = m.plusMonths(1).toEpochDay()
            val body = b.bodyWeights.filter { it.epochDay in m.toEpochDay() until end }.maxByOrNull { it.epochDay }
            appendLine(
                "| ${MONTHS[m.monthValue - 1]} ${m.year} | ${ws.size} ($lifts / $runs) | ${if (km > 0) Ru.one(km) else "—"} | $sets | " +
                    (trial?.let { "${dist(it.meters)} за ${Ru.clock(it.seconds)}" } ?: "—") + " | " +
                    best.joinToString("") { e -> (e?.let { "${half(it)} кг" } ?: "—") + " | " } +
                    (body?.let { "${Ru.kg(it.kg)} кг" } ?: "—") + " |",
            )
        }
        appendLine()
    }

    /**
     * The way to the race goal: the best trial over its distance against the goal time, and the latest session of reps
     * at the goal pace — its average lap against the goal lap (on the person's stadium, or per 400 m).
     */
    private fun StringBuilder.goalPath(b: Backup, p: forma.core.model.Profile) {
        val g = p.runGoal ?: return
        val path = GoalProgress.of(b.workouts, b.runs, g.meters)
        appendLine("## Путь к цели: ${dist(g.meters)} за ${Ru.clock(g.seconds)}")
        appendLine()
        val best = path.trials.minByOrNull { it.goalSeconds }
        if (best == null) appendLine("- Контрольного забега на ${dist(g.meters)} ещё не было.")
        else {
            val gap = best.goalSeconds - g.seconds
            appendLine("- Лучший контрольный: ${Ru.clock(best.goalSeconds.roundToInt())} (${Ru.date(LocalDate.ofEpochDay(best.epochDay))})" +
                (if (best.meters != g.meters) ", пробежано ${best.meters} м за ${Ru.clock(best.seconds)}" else "") + " — " +
                (if (gap <= 0.5) "цель достигнута." else "до цели ${lapTenthsText(gap)}.") +
                " Контрольных всего: ${path.trials.size}.")
        }
        val lap = p.lapMeters ?: 400
        val goalLap = forma.core.run.Track.lapSec(g.pace, lap)
        val last = path.sessions.lastOrNull()
        // Sessions at the goal pace are in the plan only for a goal of up to 3 km: a 5 or 10 km goal never has them.
        if (last == null) { if (g.middleDistance) appendLine("- Отрезков в темпе цели ещё не было.") }
        else {
            val avg = forma.core.run.Track.lapSec(last.paceSecKm, lap)
            appendLine("- Последние отрезки в темпе цели (${Ru.date(LocalDate.ofEpochDay(last.epochDay))}): средний круг $lap м — ${lapTenthsText(avg)} " +
                "при цели ${lapTenthsText(goalLap)}" + (if (last.judged > 0) ", в темпе ${last.onPace} из ${last.judged} кругов" else "") +
                ". Отрезков в темпе цели всего: ${path.sessions.size}.")
        }
        appendLine()
    }

    /** "50,6 с", "1:28,4": tenths matter in a lap of the goal (6,5 laps × a second is a miss). */
    private fun lapTenthsText(sec: Double): String {
        val t = kotlin.math.round(sec * 10).toLong()
        val whole = t / 10
        return if (whole < 60) "${Ru.one(t / 10.0)} с" else "${whole / 60}:${"%02d".format(whole % 60)},${t % 10}"
    }

    /**
     * Plan against what was done, week by week (newest first, at most 8): sessions of the gym and runs planned up to
     * today (today's once done) and done, the planned ones skipped, the days of the pauses. Weeks before the current
     * plan was made are left out: what they held is not known.
     */
    private fun StringBuilder.planWeeks(b: Backup, done: List<WorkoutEntity>, today: LocalDate) {
        val prog = b.program ?: return
        val start = LocalDate.ofEpochDay(prog.createdEpochDay ?: prog.startEpochDay)
        val thisMonday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        fun paused(d: LocalDate) = b.settings.pausedOn(d)
        val weeks = (0..7).map { thisMonday.minusWeeks(it.toLong()) }.filter { !it.plusDays(6).isBefore(start) }
        if (weeks.isEmpty()) return
        val doneToday = done.filter { it.epochDay == today.toEpochDay() }
        appendLine("### План и сделанное по неделям (сначала новые)")
        appendLine()
        weeks.forEach { monday ->
            val dates = (0..6L).map { monday.plusDays(it) }.filter { !it.isBefore(start) && !it.isAfter(today) }
            // Today's sessions count once done: in the morning they are not missed yet.
            val planned = dates.filter { !paused(it) }.flatMap { d ->
                val on = prog.week.on(d.dayOfWeek.value)
                if (d != today) on
                else on.filter { !it.isRun }.take(doneToday.count { it.kind == Kind.LIFT }) + on.filter { it.isRun }.take(doneToday.count { it.kind == Kind.RUN })
            }
            val pLift = planned.count { it.kind == SessionKind.LIFT }
            val pRun = planned.count { it.kind != SessionKind.LIFT }
            val ws = done.filter { it.epochDay in monday.toEpochDay()..monday.toEpochDay() + 6 }
            val dLift = ws.count { it.kind == Kind.LIFT }
            val dRun = ws.count { it.kind == Kind.RUN }
            val pause = (0..6L).count { paused(monday.plusDays(it)) }
            val parts = listOfNotNull(
                "зал $dLift из $pLift".takeIf { pLift > 0 || dLift > 0 },
                "бег $dRun из $pRun".takeIf { pRun > 0 || dRun > 0 },
                listOfNotNull(
                    (pLift - dLift).takeIf { it > 0 }?.let { "зал $it" },
                    (pRun - dRun).takeIf { it > 0 }?.let { "бег $it" },
                ).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "пропущено: "),
                if (pause > 0) "пауза ${Ru.days(pause)}" else null,
                if (monday == thisMonday) "неделя идёт" else null,
            )
            appendLine("- ${Ru.dates(monday, monday.plusDays(6))}: " + parts.ifEmpty { listOf("ничего не было запланировано") }.joinToString("; "))
        }
        appendLine()
    }

    private fun StringBuilder.lift(w: WorkoutEntity, sets: List<SetEntity>) {
        val plan = w.planJson?.let { runCatching { AppJson.decodeFromString(LiftPlan.serializer(), it) }.getOrNull() }
        val summary = w.summaryJson?.let { runCatching { AppJson.decodeFromString(LiftSummary.serializer(), it) }.getOrNull() }
        sets.groupBy { it.position }.toSortedMap().forEach { (_, list) ->
            val id = list.first().exerciseId
            val ex = Catalog.find(id)
            val item = plan?.items?.firstOrNull { it.exerciseId == id }
            val planned = item?.prescription?.let { pr ->
                val pw = pr.weight
                if (pr.calibrate || pw == null) " (план: подбор веса)" else " (план ${weight(ex, pw)} × ${pr.reps}, ${pr.sets} подх., запас ${pr.rir})"
            } ?: ""
            val done = list.sortedBy { it.doneAt }.joinToString(", ") { s ->
                (if (ex?.load == LoadKind.TIMED) "${s.reps} с" else "${weight(ex, s.weight)} × ${s.reps}") + (s.rir?.let { " (запас $it)" } ?: "")
            }
            appendLine("- ${name(id)}$planned: $done")
            summary?.reports?.firstOrNull { it.exerciseId == id }?.let { r ->
                appendLine("  - Итог: ${Texts.outcome(r.outcome, ex)}" + (r.e1rm?.let { " Расчётный максимум ${half(it)} кг." } ?: "") + if (r.record) " Рекорд!" else "")
            }
        }
        summary?.let { appendLine("- Всего: ${it.sets} подх., объём ${Ru.kg(it.volumeKg)} кг.") }
        appendLine()
    }

    private fun StringBuilder.run(w: WorkoutEntity, r: RunEntity) {
        val plan = runCatching { AppJson.decodeFromString(RunWorkout.serializer(), r.planJson) }.getOrNull()
        val summary = w.summaryJson?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() }
        plan?.let { appendLine("- План: ${type(it.type)}${planLine(it)}") }
        val facts = listOfNotNull(
            r.distanceM?.takeIf { it > 0 }?.let { Ru.distance(it) },
            "в движении ${Ru.clock(r.movingSec)}",
            r.avgPace?.takeIf { it > 0 }?.let { "средний темп ${Ru.pace(it)} /км" },
            r.workPace?.takeIf { it > 0 }?.let { "темп отрезков ${Ru.pace(it)} /км" + (r.workDistanceM?.let { d -> " (${Ru.distance(d)})" } ?: "") },
            if (r.completed < 0.999) "сделано ${(r.completed * 100).roundToInt()} %" else null,
            summary?.cadence?.let { "каденс $it шаг/мин" },
            if (summary?.manual == true) "вписано вручную" else null,
        )
        appendLine("- Факт: ${facts.joinToString(", ")}.")
        summary?.laps?.takeIf { it.isNotEmpty() }?.let { laps ->
            appendLine("- Круги: " + laps.joinToString(", ") { l -> Ru.one(l.seconds) + (l.rep?.let { " (отр. $it)" } ?: "") })
        }
        summary?.kmSplits?.takeIf { it.isNotEmpty() }?.let { appendLine("- По километрам: " + it.joinToString(", ") { s -> Ru.clock(s) }) }
        r.vdotAfter?.let { appendLine("- VDOT после: ${Ru.one(it)}") }
        summary?.outcomes?.forEach { appendLine("  - Итог: ${Texts.run(it, summary.manual, plan)}") }
        appendLine()
    }

    private fun planLine(p: RunWorkout): String {
        val work = p.segments.filter { it.kind == SegmentKind.WORK }
        val pace = (work.firstOrNull()?.pace ?: p.segments.firstOrNull { it.kind == SegmentKind.RUN }?.pace ?: p.targetPace)
            ?.let { ", темп ${Ru.pace(it.fast.toDouble())}–${Ru.pace(it.slow.toDouble())} /км" } ?: ""
        val reps = if (work.isNotEmpty()) ", ${work.size} × ${work.first().laps?.let { Ru.laps(it) } ?: Ru.clock(work.first().seconds)}" else ""
        val total = if (p.totalSeconds > 0) ", всего ${Ru.clock(p.totalSeconds)}" else ""
        return reps + pace + total + (p.testMeters?.let { ", контрольный $it м" } ?: "") + (if (p.cutback) ", облегчённая" else "") +
            ", целевая тяжесть ${p.targetRpe}"
    }

    private fun weight(ex: forma.core.model.Exercise?, w: Double) = Texts.weight(ex, w).ifEmpty { "${Ru.kg(w)} кг" }
    private fun half(x: Double) = Ru.kg((x * 2).roundToInt() / 2.0)
    private fun dist(m: Int) = if (m < 3000 || m % 1000 != 0) "$m м" else "${m / 1000} км"
    private fun name(id: String) = Catalog.find(id)?.name ?: id
    private fun days(d: Set<Int>) = d.sorted().joinToString(", ") { DAYS[it - 1] }
    private fun experience(e: Experience) = when (e) { Experience.NOVICE -> "новичок"; Experience.INTERMEDIATE -> "средний"; Experience.ADVANCED -> "опытный" }
    private fun runLevel(l: RunLevel) = when (l) { RunLevel.NONE -> "с нуля"; RunLevel.BASE -> "бегает 20+ минут"; RunLevel.TRAINED -> "бегает регулярно" }
    private fun lift(f: LiftFocus) = when (f) {
        LiftFocus.STRENGTH -> "сила"; LiftFocus.POWERBUILDING -> "сила и масса"; LiftFocus.MUSCLE -> "масса"
        LiftFocus.GENERAL -> "общая форма"; LiftFocus.NONE -> "нет"
    }
    private fun run(f: RunFocus) = when (f) { RunFocus.NONE -> "нет"; RunFocus.HEALTH -> "для здоровья"; RunFocus.ENDURANCE -> "выносливость"; RunFocus.SPEED -> "скорость" }
    private fun emphasis(e: Emphasis) = when (e) { Emphasis.GYM -> "зал"; Emphasis.BALANCED -> "поровну"; Emphasis.RUN -> "бег" }
    private fun role(r: SlotRole) = when (r) { SlotRole.MAIN -> "основное"; SlotRole.SECONDARY -> "второе"; else -> "подсобное" }
    private fun type(t: RunType) = when (t) {
        RunType.RUN_WALK -> "бег с ходьбой"; RunType.EASY -> "лёгкий"; RunType.LONG -> "длинный"; RunType.INTERVALS -> "интервалы"
        RunType.TEMPO -> "темповый"; RunType.TEST -> "контрольный"; RunType.FREE -> "свободный"; RunType.GOAL_PACE -> "отрезки в темпе цели"
    }
}
