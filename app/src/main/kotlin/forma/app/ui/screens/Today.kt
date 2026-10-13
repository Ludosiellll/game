package forma.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.DayItem
import forma.app.data.LiftPlan
import forma.app.data.LiftState
import forma.app.run.RunSession
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.components.Banner
import forma.app.ui.components.Dot
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.StatTile
import forma.app.ui.components.NumText
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.app.ui.theme.labelText
import forma.core.analytics.Stats
import forma.core.load.TrainingLoad
import forma.core.plan.CatchUp
import forma.core.plan.PlannedSession
import forma.core.plan.Program
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.plan.Templates
import forma.core.run.RunPlan
import forma.core.run.RunTracker
import forma.core.run.RunWorkout
import forma.core.run.Vdot
import forma.core.text.Ru
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

@Composable
fun TodayScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val today = forma.app.ui.rememberToday(repo)
    val monday = remember(today) { today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    val program by repo.store.program.collectAsState(initial = null)
    val runState by repo.store.runState.collectAsState(initial = null)
    val me = repo.store.profile.collectAsState(initial = null).value
    val runGoal = me?.runGoal
    val liftState by repo.store.liftState.collectAsState(initial = LiftState())
    val week by remember(monday) { repo.weekFlow(monday) }.collectAsState(initial = emptyList())
    val active by remember { repo.db.workouts().observeActive() }.collectAsState(initial = null)
    val run by RunSession.ui.collectAsState()
    val loads by remember { repo.dailyLoads() }.collectAsState(initial = emptyMap())
    val rows by remember { repo.setRows() }.collectAsState(initial = emptyList())
    val bw by remember { repo.bodyWeightLookup() }.collectAsState(initial = { _: Long -> 75.0 })
    var pickTemplate by remember { mutableStateOf(false) }
    // One start at a time (a double tap must not open two sessions), and never silently over an unfinished one.
    var starting by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    fun open(templateId: String, light: Boolean = false, replace: Long? = null) {
        if (starting) return
        starting = true
        scope.launch {
            try {
                if (replace != null) repo.cancelWorkout(replace)
                nav.push(Screen.Workout(repo.startLift(templateId, today, forceDeload = light)))
            } finally {
                starting = false
            }
        }
    }
    fun startLift(templateId: String, light: Boolean = false) {
        val a = active
        when {
            a == null -> open(templateId, light)
            a.templateId == templateId -> nav.push(Screen.Workout(a.id))
            else -> pending = templateId to light
        }
    }
    val settings by repo.store.settings.collectAsState(initial = forma.app.data.Settings())
    var feel by rememberSaveable(today) { mutableStateOf(Feel.UNSET) }
    val catchUp by produceState<CatchUp.Suggestion?>(null, week, settings, today) { value = repo.catchUp(today) }

    val p = program ?: return
    val weekNo = ProgramGenerator.weekIndex(p, today)
    val deload = ProgramGenerator.isDeloadWeek(p, today) ||
        (liftState.reactiveDeloadStart?.let { today.toEpochDay() in it until it + 7 } == true)
    val todayItem = week.firstOrNull { it.date == today }

    ScrollScreen {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(dateLine(today), style = T.secondary(13.sp))
                Text(if (F.type.capsTitles) "СЕГОДНЯ" else "Сегодня", style = T.screenTitle())
            }
            Text(labelText("Неделя $weekNo"), style = T.label())
        }

        val a = active
        if (a != null) Banner("Тренировка «${a.title}» не закончена — продолжить", F.c.strength, icon = Ic.dumbbell) { nav.push(Screen.Workout(a.id)) }
        val r = run
        when {
            r == null -> Unit
            r.state == RunTracker.State.FINISHED ->
                Banner("Пробежка не сохранена · ${Ru.clock(r.elapsedSec)} — сохранить", F.c.endurance, icon = Ic.run) { nav.push(Screen.RunDone(0)) }
            r.restored ->
                Banner("Пробежка прервалась на ${Ru.clock(r.elapsedSec)} — продолжить или закончить", F.c.warn, icon = Ic.run) { nav.push(Screen.RunActive) }
            else -> Banner("Идёт пробежка · ${Ru.clock(r.elapsedSec)}", F.c.endurance, icon = Ic.run) { nav.push(Screen.RunActive) }
        }
        if (deload) Banner("Разгрузочная неделя: вдвое меньше подходов и вес −10 %. Восстановление — тоже тренировка.", F.c.warn)

        WeekStrip(week, today, p)

        // The same window as on Progress → Нагрузка, so "Свежесть" reads the same number on both screens.
        val loadSeries = remember(loads, today) {
            TrainingLoad.series(loads, today.minusDays(120), today)
        }
        val history = TrainingLoad.hasHistory(loads, today)
        val form = loadSeries.lastOrNull()?.takeIf { history }?.formPct
        val todays = todayItem?.planned.orEmpty()
        when {
            repo.paused(settings, today) -> PauseCard(
                java.time.LocalDate.ofEpochDay(settings.pauseUntil!!),
                restarts = settings.pauseUntil!! - (settings.pauseFrom ?: settings.pauseUntil!!) + 1 >= 7,
            ) { scope.launch { repo.resume(today) } }
            todays.isEmpty() -> RestCard(
                week, today, p, catchUp, onFree = { nav.push(Screen.RunSetup(repo.freeRun(me?.lapMeters), null)) }, onOther = { pickTemplate = true },
                onCatchUp = { s ->
                    scope.launch {
                        if (s.kind == SessionKind.LIFT) startLift(s.templateId!!)
                        else nav.push(Screen.RunSetup(repo.runFor(s.kind, today), s.kind))
                    }
                },
            )
            else -> {
                if (a == null && todays.any { todayItem?.isDone(it) != true }) FeelRow(feel, tired = (form ?: 0.0) < -30) { feel = it }
                if (feel == Feel.SICK) {
                    SickCard { feel = Feel.UNSET }
                } else todays.forEach { s ->
                    val done = todayItem?.isDone(s) == true
                    val light = feel == Feel.LOW
                    if (s.kind == SessionKind.LIFT) {
                        LiftCard(s, p, today, done, light) {
                            scope.launch {
                                startLift(s.templateId!!, light)
                            }
                        }
                    } else {
                        RunCard(s, today, todayItem?.doneFor(s), light) { w -> nav.push(Screen.RunSetup(w, s.kind)) }
                    }
                }
                // All of today done: what comes next and how to run it.
                if (feel != Feel.SICK && todays.all { todayItem?.isDone(it) == true }) NextPlannedCard(today)
            }
        }
        val strength = remember(rows, bw) { Stats.changePct(Stats.strengthIndex(rows, bw)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                "Свежесть", form?.let { (if (it >= 0) "+" else "") + it.roundToInt() + "%" } ?: "—",
                when {
                    form == null -> if (loads.isEmpty()) "нет данных" else "копим данные"
                    form > 10 -> "можно грузиться"
                    form < -30 -> "нужен отдых"
                    else -> "рабочий режим"
                },
                when {
                    form == null -> F.c.text2
                    form < -30 -> F.c.warn
                    else -> F.c.good
                }, Modifier.weight(1f),
            )
            StatTile("Сила", strength?.let { (if (it >= 0) "+" else "") + it.roundToInt() + "%" } ?: "—", if (strength == null) "после 2 недель" else "с начала", F.c.strength, Modifier.weight(1f))
            val rs = runState
            val vdot = rs?.vdot
            StatTile(
                if (vdot != null) "VDOT" else "Бег",
                when {
                    vdot != null -> Ru.one(vdot)
                    rs != null && !rs.canRunContinuously -> "${rs.walkStage / 3 + 1}/9"
                    else -> "—"
                },
                when {
                    vdot != null && runGoal != null ->
                        "${goalDistance(runGoal.meters)} ≈ ${Ru.clock(Vdot.predictSeconds(vdot, runGoal.meters.toDouble()).roundToInt())}"
                    vdot != null -> "5 км ≈ ${Ru.clock(Vdot.predictSeconds(vdot, 5000.0).roundToInt())}"
                    rs != null && !rs.canRunContinuously -> "неделя бега с ходьбой"
                    else -> "после теста"
                },
                F.c.endurance, Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Свободный бег", { nav.push(Screen.RunSetup(repo.freeRun(me?.lapMeters), null)) }, Modifier.weight(1f))
            GhostButton("Другая силовая", { pickTemplate = true }, Modifier.weight(1f))
        }
    }

    if (pickTemplate) {
        AlertDialog(
            onDismissRequest = { pickTemplate = false },
            title = { Text("Какую силовую сделать?", style = T.title(20.sp)) },
            text = {
                Column {
                    p.templates.forEach { t ->
                        Text(
                            t.title, style = T.body(17.sp, FontWeight.SemiBold),
                            modifier = Modifier.fillMaxWidth().clickable {
                                pickTemplate = false
                                startLift(t.id)
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton({ pickTemplate = false }) { Text("Отмена", style = T.body(15.sp)) } },
            containerColor = F.c.surface,
        )
    }

    val a = active
    val next = pending
    if (next != null && a != null) {
        val title = p.templates.firstOrNull { it.id == next.first }?.title ?: "новую"
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("«${a.title}» не закончена", style = T.title(20.sp)) },
            text = { Text("Продолжить её или удалить и начать «$title»? Подходы начатой тренировки при удалении пропадут.", style = T.secondary(14.sp)) },
            confirmButton = { TextButton({ pending = null; nav.push(Screen.Workout(a.id)) }) { Text("Продолжить начатую", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) } },
            dismissButton = { TextButton({ pending = null; open(next.first, next.second, replace = a.id) }) { Text("Удалить и начать", style = T.body(15.sp).copy(color = F.c.bad)) } },
            containerColor = F.c.surface,
        )
    }
}

@Composable
private fun WeekStrip(week: List<DayItem>, today: LocalDate, program: Program) {
    val nav = LocalNav.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        week.forEach { d ->
            val isToday = d.date == today
            val shape = RoundedCornerShape(if (F.shape.pill) 14.dp else F.shape.radiusSmall)
            val doneAll = d.planned.isNotEmpty() && d.planned.all { d.isDone(it) }
            // A tap opens the day in full: the session as it will be, what it is for, why on this day.
            Column(
                Modifier.weight(1f).clip(shape).clickable(onClickLabel = "Подробнее о дне") { nav.push(Screen.Day(d.date.toEpochDay())) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(labelText(dayShort(d.date.dayOfWeek)), style = T.label().copy(color = if (isToday) F.c.accent else F.c.text3))
                var m = Modifier.fillMaxWidth().height(56.dp).clip(shape)
                m = when {
                    isToday -> m.background(F.c.accent)
                    d.planned.isNotEmpty() -> m.background(F.c.surface).border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, F.c.line, shape)
                    else -> m.border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, F.c.line, shape)
                }
                Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    NumText(
                        d.date.dayOfMonth.toString(),
                        style = T.number(16.sp).copy(color = if (isToday) F.c.onAccent else if (d.planned.isEmpty()) F.c.text3 else F.c.text),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 4.dp).height(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (doneAll) {
                            Icon(Ic.check, "Сделано", tint = if (isToday) F.c.onAccent else F.c.good, modifier = Modifier.size(12.dp))
                        } else {
                            d.planned.forEach { s ->
                                val c = if (s.kind == SessionKind.LIFT) F.c.strength else F.c.endurance
                                Dot(if (isToday) F.c.onAccent.copy(alpha = if (s.isRun) 0.5f else 1f) else c)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiftCard(s: PlannedSession, program: Program, today: LocalDate, done: Boolean, light: Boolean, onStart: () -> Unit) {
    val repo = LocalRepo.current
    val plan by produceState<LiftPlan?>(null, s.templateId, today, light) {
        value = runCatching { repo.liftPlan(s.templateId!!, today, forceDeload = light) }.getOrNull()
    }
    val t = program.templates.firstOrNull { it.id == s.templateId }
    FCard(outline = if (done) null else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.dumbbell, null, tint = F.c.strength, modifier = Modifier.size(18.dp))
            Text(labelText("  Силовая"), style = T.label().copy(color = F.c.strength), modifier = Modifier.weight(1f))
            if (t != null) NumText("~${Templates.minutes(t)} мин", style = T.number(13.sp, FontWeight.Medium).copy(color = F.c.text2))
        }
        Text(t?.title ?: "Силовая", style = T.title(26.sp))
        val items = plan?.items.orEmpty()
        Column {
            items.take(3).forEach { item ->
                forma.app.ui.components.Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, style = T.body(15.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1)
                    NumText(prescriptionShort(item.prescription.sets, item.prescription.reps, item.prescription.weight, item.slot.exerciseId), style = T.number(14.sp, FontWeight.Bold))
                }
            }
            if (items.size > 3) {
                forma.app.ui.components.Hairline()
                Text("+ ещё ${items.size - 3} ${Ru.plural(items.size - 3, "упражнение", "упражнения", "упражнений")}", style = T.secondary(13.sp), modifier = Modifier.padding(top = 10.dp))
            }
        }
        val calib = items.count { it.prescription.calibrate }
        if (calib > 0 && !done) Text("Первая тренировка с этими упражнениями: начни с подсказанного веса — после первого подхода приложение подберёт рабочий.", style = T.secondary(13.sp))
        if (done) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Ic.check, null, tint = F.c.good, modifier = Modifier.size(18.dp))
                Text("Сделано", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.good))
            }
        } else {
            PrimaryButton(if (light) "Начать лёгкую тренировку" else "Начать тренировку", onStart, Modifier.fillMaxWidth(), color = F.c.strength, textColor = F.c.onData)
        }
    }
}

fun prescriptionShort(sets: Int, reps: Int, weight: Double?, exerciseId: String): String {
    val ex = forma.core.model.Catalog.find(exerciseId)
    return when {
        ex?.load == forma.core.model.LoadKind.TIMED -> "$sets×${reps} с"
        weight == null -> "$sets×$reps · подбор"
        ex?.load == forma.core.model.LoadKind.BODYWEIGHT_PLUS -> if (weight > 0) "$sets×$reps · +${Ru.kg(weight)}" else "$sets×$reps"
        else -> "$sets×$reps · ${Ru.kg(weight)} кг"
    }
}

@Composable
private fun RunCard(s: PlannedSession, today: LocalDate, doneAs: forma.app.data.WorkoutEntity?, light: Boolean, onStart: (RunWorkout) -> Unit) {
    val done = doneAs != null
    val repo = LocalRepo.current
    val workout by produceState<RunWorkout?>(null, s.kind, today, light) { value = repo.runFor(s.kind, today, light) }
    val w = workout ?: return
    // Another run done today in its place (a free run for a trial): said as it is, not as the plan's.
    val other = doneAs != null && doneAs.templateId != w.type.name
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.run, null, tint = F.c.endurance, modifier = Modifier.size(18.dp))
            Text(labelText("  ${kindLabel(s)}"), style = T.label().copy(color = F.c.endurance), modifier = Modifier.weight(1f))
            NumText("${w.totalSeconds / 60} мин", style = T.number(13.sp, FontWeight.Medium).copy(color = F.c.text2))
        }
        Text(w.title, style = T.title(22.sp))
        if (!other) Text(w.note, style = T.secondary(14.sp))
        val pace = w.segments.firstOrNull { it.kind == forma.core.run.SegmentKind.WORK || it.kind == forma.core.run.SegmentKind.RUN }?.pace
        val lapM = w.lapMeters
        if (pace != null && !other) NumText(
            // On a stadium the lap time is what the runner checks.
            if (lapM != null) "Круг ${Ru.lapRange(forma.core.run.Track.lapSec(pace.fast.toDouble(), lapM), forma.core.run.Track.lapSec(pace.slow.toDouble(), lapM))}"
            else "Темп ${Ru.pace(pace.fast.toDouble())}–${Ru.pace(pace.slow.toDouble())} /км",
            style = T.number(14.sp, FontWeight.Bold).copy(color = F.c.text2),
        )
        if (doneAs != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Ic.check, null, tint = F.c.good, modifier = Modifier.size(18.dp))
                Text(if (other) "Сделано: ${doneAs.title}" else "Сделано", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.good))
            }
            if (other && s.kind == SessionKind.RUN_QUALITY) {
                Text("«${w.title}» — в следующий тяжёлый день: план бега сдвигается только по самой тренировке.", style = T.secondary(13.sp))
            }
        } else {
            PrimaryButton("Начать пробежку", { onStart(w) }, Modifier.fillMaxWidth(), color = F.c.endurance, textColor = F.c.onData, icon = Ic.play)
        }
    }
}

@Composable
private fun RestCard(
    week: List<DayItem>, today: LocalDate, program: Program, catchUp: CatchUp.Suggestion?,
    onFree: () -> Unit, onOther: () -> Unit, onCatchUp: (PlannedSession) -> Unit,
) {
    val next = week.firstOrNull { it.date.isAfter(today) && it.planned.isNotEmpty() }
    if (catchUp != null) {
        val s = catchUp.session
        FCard(outline = if (s.isRun) F.c.endurance else F.c.strength) {
            SectionLabel("Пропущено", color = if (s.isRun) F.c.endurance else F.c.strength)
            Text(sessionTitle(s, program), style = T.title(22.sp))
            Text(
                "Стояло в плане на ${DAY_ACCUSATIVE[catchUp.plannedOn - 1]}. Можно сделать сегодня вместо отдыха — " +
                    "завтрашнему плану это не помешает.",
                style = T.secondary(14.sp),
            )
            PrimaryButton(
                "Сделать сегодня", { onCatchUp(s) }, Modifier.fillMaxWidth(),
                color = if (s.isRun) F.c.endurance else F.c.strength, textColor = F.c.onData,
            )
        }
    }
    FCard {
        SectionLabel("Отдых")
        Text("Сегодня день восстановления", style = T.title(22.sp))
        if (next == null) Text("На этой неделе тренировок больше нет. Новая неделя — с понедельника.", style = T.secondary(14.sp))
        Text("Лёгкая прогулка или растяжка не помешают. Если очень хочется — можно потренироваться вне плана.", style = T.secondary(13.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Пробежка", onFree, Modifier.weight(1f))
            GhostButton("Силовая", onOther, Modifier.weight(1f))
        }
    }
    NextPlannedCard(today)
}

/** "на понедельник", "на среду": day names after «на». */
private val DAY_ACCUSATIVE = listOf("понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье")

/** How the person feels today: decides between the planned session, a lighter one and rest. */
enum class Feel { UNSET, GOOD, LOW, SICK }

@Composable
private fun FeelRow(feel: Feel, tired: Boolean, onPick: (Feel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Как ты сегодня?", style = T.body(15.sp, FontWeight.Bold))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Feel.GOOD to "Хорошо", Feel.LOW to "Так себе", Feel.SICK to "Болею").forEach { (f, label) ->
                forma.app.ui.components.Chip(label, feel == f, { onPick(if (feel == f) Feel.UNSET else f) }, Modifier.weight(1f))
            }
        }
        val text = when {
            feel == Feel.LOW -> "Лёгкий вариант: в силовой вдвое меньше подходов и вес −10 %, бег — только лёгкий. Прогресс и план не сдвинутся."
            feel == Feel.UNSET && tired -> "Нагрузка последних дней высокая. Если плохо спал или чувствуешь усталость — выбери «Так себе»."
            else -> null
        }
        if (text != null) Text(text, style = T.secondary(13.sp))
    }
}

@Composable
private fun SickCard(onTrainAnyway: () -> Unit) {
    FCard {
        SectionLabel("Отдых")
        Text("Сегодня выздоравливаем", style = T.title(22.sp))
        Text(
            "При температуре, ломоте или кашле — никаких тренировок, пока не пройдёт. Пропущенное можно будет сделать в свободный " +
                "день, а после перерыва первые тренировки станут легче сами.",
            style = T.secondary(14.sp),
        )
        GhostButton("Всё равно тренироваться", onTrainAnyway, Modifier.fillMaxWidth())
    }
}

@Composable
private fun PauseCard(until: LocalDate, restarts: Boolean, onBack: () -> Unit) {
    FCard {
        SectionLabel("Пауза")
        Text("Отдыхаем до ${shortDate(until)}", style = T.title(22.sp))
        Text(
            "Пропусков за эти дни не будет. После возвращения первые силовые будут легче, бег — короче и спокойнее" +
                if (restarts) ", а цикл начнётся заново." else ".",
            style = T.secondary(14.sp),
        )
        GhostButton("Вернуться сегодня", onBack, Modifier.fillMaxWidth())
    }
}
