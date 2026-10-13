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
    private val zone = ZoneId.systemDefault()

    fun fileName(today: LocalDate) = "forma-otchet-$today.md"

    fun build(b: Backup, today: LocalDate, version: String): String = buildString {
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

        if (p != null) {
            appendLine("## Кто тренируется")
            appendLine()
            appendLine("- ${if (p.sex == Sex.MALE) "Мужчина" else "Женщина"}, ${p.age(today.year)} лет" +
                (p.heightCm?.let { ", рост $it см" } ?: "") + ", вес ${Ru.kg(b.bodyWeights.maxByOrNull { it.epochDay }?.kg ?: p.bodyWeightKg)} кг.")
            appendLine("- Опыт в зале: ${experience(p.experience)}. Бег: ${runLevel(p.runLevel)}.")
            appendLine("- Цель: зал — ${lift(p.aim.lift)}, бег — ${run(p.aim.run)}, приоритет — ${emphasis(p.aim.emphasis)}.")
            p.runGoal?.let { g -> appendLine("- Беговая цель: ${dist(g.meters)} за ${Ru.clock(g.seconds)} (темп ${Ru.pace(g.pace)} /км).") }
            p.lapMeters?.let { appendLine("- Бегает на стадионе, круг $it м.") }
            if (p.ownWeek) appendLine("- Своя неделя: зал — ${days(p.gymDays!!)}, бег — ${days(p.runDays!!)}.")
            else appendLine("- Дни тренировок: ${days(p.trainingDays)}, до ${p.sessionMinutes} мин.")
            val rm = p.raceMeters
            val rs = p.raceSeconds
            if (rm != null && rs != null) appendLine("- Известный забег: ${Ru.distance(rm)} за ${Ru.clock(rs.roundToInt())}.")
            appendLine()
        }

        b.program?.let { prog ->
            appendLine("## План")
            appendLine()
            prog.copiedFrom?.let { appendLine("Скопирован у: $it.") }
            appendLine("Начат ${Ru.date(LocalDate.ofEpochDay(prog.startEpochDay))}." +
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
        b.runState?.let { st ->
            appendLine("### Бег")
            appendLine()
            val v = st.vdot
            if (v != null) {
                val t = PaceTable.forVdot(v)
                appendLine("- VDOT ${Ru.one(v)}. Прогноз: 1500 м — ${Ru.clock(Vdot.predictSeconds(v, 1500.0).roundToInt())}, " +
                    "5 км — ${Ru.clock(Vdot.predictSeconds(v, 5000.0).roundToInt())}, 10 км — ${Ru.clock(Vdot.predictSeconds(v, 10000.0).roundToInt())}.")
                appendLine("- Темпы, мин/км: лёгкий ${Ru.pace(t.easy.fast.toDouble())}–${Ru.pace(t.easy.slow.toDouble())}, " +
                    "пороговый ${Ru.pace(t.threshold.toDouble())}, интервальный ${Ru.pace(t.interval.toDouble())}, повторный ${Ru.pace(t.repetition.toDouble())}.")
            } else appendLine("- VDOT ещё не рассчитан (нет контрольного забега).")
            if (!st.canRunContinuously) appendLine("- Программа бега с ходьбой: неделя ${st.walkStage / 3 + 1} из 9.")
            appendLine("- Лёгкий бег — ${st.easyMinutes} мин, длинный — ${st.longMinutes} мин. Шаги лестниц: интервалы ${st.intervalStage + 1}, " +
                "темповый ${st.tempoStage + 1}" + (b.profile?.runGoal?.takeIf { it.middleDistance }?.let { ", темп цели ${st.goalStage + 1} из ${forma.core.run.RunPlan.goalLadderSize}" } ?: "") + ".")
            if (st.rpeOffset > 0) appendLine("- Своя шкала тяжести: лёгкий бег оценивается строже нормы, пороги на ${st.rpeOffset} выше.")
            appendLine()
        }
        if (b.stats.isNotEmpty()) {
            appendLine("### Зал: расчётный максимум на 1 раз")
            appendLine()
            b.stats.sortedByDescending { it.e1rm }.forEach { s ->
                val ex = Catalog.find(s.exerciseId)
                if (ex?.load == LoadKind.TIMED) return@forEach
                val next = b.slots.filter { it.exerciseId == s.exerciseId }.maxByOrNull { it.updatedAt }
                appendLine("- ${name(s.exerciseId)}: ${half(s.e1rm)} кг (лучший ${half(s.best)})" +
                    (next?.weight?.let { w -> "; следующая цель ${Ru.kg(w)} кг × ${next.reps}" } ?: ""))
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
            appendLine("Недели:")
            st.weeks.reversed().forEach { w -> appendLine("- ${Ru.dates(w.monday, w.monday.plusDays(6))}: ${LoadExplain.level(w)} — ${LoadExplain.week(w)}") }
            appendLine()
            if (st.history) {
                if (st.dips.isEmpty()) appendLine(LoadExplain.noDips(8)) else st.dips.forEach { appendLine("- ${LoadExplain.dip(it)}") }
                appendLine()
            }
        }

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
        appendLine("- Расчётный максимум — вес на 1 повтор по формуле из веса и повторов (e1RM).")
        appendLine("- VDOT — беговая форма по таблицам Дэниелса. Темп — мин:с на километр.")
        appendLine("- «Итог» — что приложение решило после тренировки: вес, повторы, шаг лестницы, длительность следующей.")
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
        summary?.outcomes?.forEach { appendLine("  - Итог: ${Texts.run(it, summary.manual)}") }
        appendLine()
    }

    private fun planLine(p: RunWorkout): String {
        val work = p.segments.filter { it.kind == SegmentKind.WORK }
        val pace = (work.firstOrNull()?.pace ?: p.segments.firstOrNull { it.kind == SegmentKind.RUN }?.pace ?: p.targetPace)
            ?.let { ", темп ${Ru.pace(it.fast.toDouble())}–${Ru.pace(it.slow.toDouble())} /км" } ?: ""
        val reps = if (work.isNotEmpty()) ", ${work.size} × ${work.first().laps?.let { "${Ru.laps(it)} кр." } ?: Ru.clock(work.first().seconds)}" else ""
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
