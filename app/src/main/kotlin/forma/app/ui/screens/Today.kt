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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.DayItem
import forma.app.data.Feel
import forma.app.data.WorkoutEntity
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
    // One start at a time, and never silently into an unfinished one of another session or another day.
    val startLift = rememberLiftStart(today, nav::push)
    val settings by repo.store.settings.collectAsState(initial = forma.app.data.Settings())
    // Kept for the day in the settings: the second session of the day and a return to this screen follow it too.
    val feel = settings.feelOn(today)
    fun setFeel(f: Feel) {
        scope.launch { repo.updateSettings { it.withFeel(today, f) } }
    }
    val catchUp by produceState<CatchUp.Suggestion?>(null, week, settings, today) { value = repo.catchUp(today) }
    val trialOn by produceState<LocalDate?>(null, program, today, week) { value = repo.trialDay(today) }

    val p = program ?: return
    val weekNo = ProgramGenerator.programWeek(p, today)
    val deload = ProgramGenerator.isDeloadWeek(p, today) ||
        (liftState.reactiveDeloadStart?.let { today.toEpochDay() in it until it + 7 } == true)
    val gym = p.week.days.any { d -> d.sessions.any { it.kind == SessionKind.LIFT } }
    val runs = p.week.days.any { d -> d.sessions.any { it.isRun } }
    val runCutback = runs && ProgramGenerator.isRunCutbackWeek(p, today)
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
        deloadText(deload && gym, runCutback, ladder = p.week.days.any { d -> d.sessions.any { it.kind == SessionKind.RUN_QUALITY } })?.let { Banner(it, F.c.warn) }

        WeekStrip(week, today, p.createdEpochDay)

        // The goal race ahead: how far, what the days before it hold; on its day the start.
        me?.runGoal?.takeIf { it.date != null }?.let { g ->
            val raceDone = todayItem?.done?.any { it.kind == forma.app.data.Kind.RUN && it.templateId == forma.core.run.RunType.TEST.name } == true
            // A run planned today becomes the race (its card starts it); on a day without one the race card does.
            val runToday = todayItem?.planned.orEmpty().any { it.isRun }
            RaceCard(g, today, raceDone, runToday) { scope.launch { nav.push(Screen.RunSetup(repo.runFor(SessionKind.RUN_QUALITY, today), SessionKind.RUN_QUALITY)) } }
        }

        // The same window as on Progress → Нагрузка, so "Свежесть" reads the same number on both screens.
        val loadSeries = remember(loads, today) {
            TrainingLoad.series(loads, today.minusDays(120), today)
        }
        val history = TrainingLoad.hasHistory(loads, today)
        val form = loadSeries.lastOrNull()?.takeIf { history }?.formPct
        // The rest line is the person's own once their sessions show where things stop going well.
        val story by remember(today) { repo.loadStory(today) }.collectAsState(initial = null)
        val restLine = story?.personal?.restBelow ?: forma.core.load.LoadStory.COMMON_REST
        val todays = todayItem?.planned.orEmpty()
        val extra = todayItem?.offPlan.orEmpty()
        when {
            repo.paused(settings, today) -> {
                PauseCard(
                    java.time.LocalDate.ofEpochDay(settings.pauseUntil!!),
                    restarts = settings.pauseUntil!! - (settings.pauseFrom ?: settings.pauseUntil!!) + 1 >= 7,
                ) { scope.launch { repo.resume(today) } }
                // Trained on a pause day anyway: what was done shows, as it counts in the week.
                extra.forEach { w -> OffPlanCard(w, todayItem?.catchUps?.get(w.id)) }
            }
            todays.isEmpty() && extra.isNotEmpty() -> {
                // A rest day trained on anyway (a missed session caught up, or one extra): said as done, not as rest.
                extra.forEach { w -> OffPlanCard(w, todayItem?.catchUps?.get(w.id)) }
                NextPlannedCard(today)
            }
            todays.isEmpty() -> RestCard(
                week, today, p, catchUp, onFree = { nav.push(Screen.RunSetup(repo.freeRun(me?.lapMeters), null)) }, onOther = { pickTemplate = true },
                onCatchUp = { s ->
                    scope.launch {
                        if (s.kind == SessionKind.LIFT) startLift(s.templateId!!, false)
                        else nav.push(Screen.RunSetup(repo.runFor(s.kind, today), s.kind))
                    }
                },
            )
            else -> {
                if (a == null && todays.any { todayItem?.isDone(it) != true }) FeelRow(feel, tired = (form ?: 0.0) < restLine) { setFeel(it) }
                // The gym and a run today: which first and why (how tired, the exercises, the legs of the last days).
                val order by produceState<forma.app.data.DayOrder?>(null, todays, feel, week) { value = repo.dayOrder(today, feel == Feel.LOW) }
                if (feel == Feel.SICK) {
                    SickCard(today, onTrainAnyway = { setFeel(Feel.UNSET) }, onPause = { scope.launch { repo.pause(today, today.plusDays(SICK_PAUSE_DAYS - 1L)) } })
                } else order?.let { o -> OrderCard(o, runDone = todayItem?.isDone(o.run) == true, liftDone = todayItem?.isDone(o.lift) == true) }
                if (feel != Feel.SICK) (order?.ordered(todays) ?: todays).forEach { s ->
                    val light = feel == Feel.LOW
                    if (s.kind == SessionKind.LIFT) {
                        LiftCard(s, p, today, todayItem?.doneFor(s), light) {
                            scope.launch {
                                startLift(s.templateId!!, light)
                            }
                        }
                    } else {
                        RunCard(s, today, todayItem?.doneFor(s), light) { w -> nav.push(Screen.RunSetup(w, s.kind)) }
                    }
                }
                extra.forEach { w -> OffPlanCard(w, todayItem?.catchUps?.get(w.id)) }
                // All of today done: what comes next and how to run it.
                if (feel != Feel.SICK && todays.all { todayItem?.isDone(it) == true }) NextPlannedCard(today)
            }
        }
        val strength = remember(rows, bw) { Stats.changePct(Stats.strengthIndex(rows, bw)) }
        fun toProgress(tab: Int) {
            progressTabRequest = tab
            nav.tab(Screen.Progress)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val zone = form?.let { forma.core.load.LoadStory.zone(it, restLine) }
            StatTile(
                "Свежесть", form?.let { forma.core.text.Ru.pct(it) } ?: "—",
                when (zone) {
                    null -> if (loads.isEmpty()) "нет данных" else "копим данные"
                    forma.core.load.LoadStory.Zone.REST -> "нужен отдых"
                    forma.core.load.LoadStory.Zone.BUILDING -> "в работе"
                    forma.core.load.LoadStory.Zone.BALANCE -> "равновесие"
                    forma.core.load.LoadStory.Zone.FRESH -> "можно грузиться"
                    forma.core.load.LoadStory.Zone.DETRAINING -> "долгий отдых"
                },
                zoneColor(zone), Modifier.weight(1f).clickable(onClickLabel = "Нагрузка и свежесть") { toProgress(2) },
            )
            // Without the gym or without running in the aim, the week's sessions in place of an empty tile.
            val aim = me?.aim
            val planned = week.sumOf { it.planned.size }
            val doneCount = week.sumOf { d -> d.planned.count { d.isDone(it) } }
            val extras = week.sumOf { d -> d.offPlan.count { it.id !in d.catchUps } }
            @Composable fun weekTile() = StatTile(
                "Неделя", "$doneCount/$planned", if (extras > 0) "+$extras вне плана" else "сделано", F.c.text,
                Modifier.weight(1f).clickable(onClickLabel = "План недели") { nav.tab(Screen.Plan) },
            )
            if (aim?.lifts == false) weekTile()
            else StatTile(
                "Сила", strength?.let { (if (it >= 0) "+" else "") + it.roundToInt() + "%" } ?: "—", if (strength == null) "после 2 недель" else "с начала",
                F.c.strength, Modifier.weight(1f).clickable(onClickLabel = "Прогресс силы") { toProgress(0) },
            )
            val rs = runState
            val vdot = rs?.vdot
            if (aim?.runs == false) weekTile()
            else StatTile(
                if (vdot != null) "VDOT" else "Бег",
                when {
                    vdot != null -> Ru.one(vdot)
                    rs != null && !rs.canRunContinuously -> "${rs.walkStage / 3 + 1}/9"
                    else -> "—"
                },
                when {
                    vdot != null && runGoal != null -> goalGap(Vdot.predictSeconds(vdot, runGoal.meters.toDouble()), runGoal.seconds)
                    vdot != null -> "5 км ≈ ${Ru.clock(Vdot.predictSeconds(vdot, 5000.0).roundToInt())}"
                    rs != null && !rs.canRunContinuously -> "неделя бега с ходьбой"
                    else -> trialOn?.let { "тест ${soonDay(it, today)}" } ?: "после теста"
                },
                F.c.endurance, Modifier.weight(1f).clickable(onClickLabel = "Прогресс бега") { toProgress(1) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Свободный бег", { nav.push(Screen.RunSetup(repo.freeRun(me?.lapMeters), null)) }, Modifier.weight(1f))
            if (p.templates.isNotEmpty()) GhostButton("Другая силовая", { pickTemplate = true }, Modifier.weight(1f))
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
                                startLift(t.id, feel == Feel.LOW)
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton({ pickTemplate = false }) { Text("Отмена", style = T.body(15.sp)) } },
            containerColor = F.c.surface,
        )
    }
}

/** Days a pause from the sick card lasts: today and the two after. */
const val SICK_PAUSE_DAYS = 3

/**
 * The lighter week's banner: the gym's deload ([gym]) and the shorter runs ([runs]) said together when they fall on the
 * same week, each alone otherwise; null in an ordinary week. [ladder]: the week holds hard runs, whose ladder step waits.
 */
fun deloadText(gym: Boolean, runs: Boolean, ladder: Boolean = true): String? = when {
    gym && runs -> "Разгрузочная неделя: в зале вдвое меньше подходов и вес −10 %, пробежки короче. Восстановление — тоже тренировка."
    gym -> "Разгрузочная неделя: вдвое меньше подходов и вес −10 %. Восстановление — тоже тренировка."
    runs -> "Облегчённая неделя бега: пробежки короче" + (if (ladder) ", шаг лестницы не двигается" else "") + ". Восстановление — тоже тренировка."
    else -> null
}

/** The colour of a freshness zone, the same on the main screen and in Progress: rest and a long break warn. */
@Composable
fun zoneColor(z: forma.core.load.LoadStory.Zone?): Color = when (z) {
    null -> F.c.text2
    forma.core.load.LoadStory.Zone.REST, forma.core.load.LoadStory.Zone.DETRAINING -> F.c.warn
    forma.core.load.LoadStory.Zone.FRESH -> F.c.good
    else -> F.c.text
}

/** "цель 5:30 · запас 3 с" when the forecast is within the goal, "цель 5:30 · без запаса" right on it, "до цели 9 с" when not yet. */
fun goalGap(forecast: Double, goal: Int): String {
    val gap = (forecast - goal).roundToInt()
    fun sec(x: Int) = if (x < 60) "$x с" else Ru.clock(x)
    return when {
        gap < 0 -> "цель ${Ru.clock(goal)} · запас ${sec(-gap)}"
        gap == 0 -> "цель ${Ru.clock(goal)} · без запаса"
        else -> "до цели ${sec(gap)}"
    }
}

/** "сегодня", "завтра", "16 окт", "16 мая": after a number May is said in full. */
fun soonDay(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "сегодня"
    today.plusDays(1) -> "завтра"
    else -> "${d.dayOfMonth} ${if (d.monthValue == 5) "мая" else MONTH_SHORT[d.monthValue - 1]}"
}

/** A hollow dot: a session that was planned and not done. */
@Composable
private fun Ring(color: Color, size: androidx.compose.ui.unit.Dp = 6.dp) {
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).border(1.5.dp, color, androidx.compose.foundation.shape.CircleShape))
}

/**
 * What a day of the strip says: done, partly, missed, done off plan, paused, ahead. Days before [since] (the plan's first,
 * epoch day) are never "missed".
 */
fun dayState(d: DayItem, today: LocalDate, since: Long? = null): String {
    val over = d.date.isBefore(today) && (since == null || d.date.toEpochDay() >= since)
    return when {
        d.paused -> if (d.offPlan.isNotEmpty()) "пауза, сделано вне плана" else "пауза"
        d.planned.isNotEmpty() && d.planned.all { d.isDone(it) } -> if (d.offPlan.isNotEmpty()) "сделано, и ещё вне плана" else "сделано"
        d.planned.any { d.isDone(it) } -> if (over) "сделано частично, остальное пропущено" else "сделано частично"
        d.planned.isNotEmpty() && over -> "пропущено"
        d.offPlan.isNotEmpty() -> "сделано вне плана"
        d.planned.isNotEmpty() -> if (d.date.isBefore(today)) "до начала плана" else "впереди"
        else -> "отдых"
    }
}

@Composable
private fun WeekStrip(week: List<DayItem>, today: LocalDate, since: Long?) {
    val nav = LocalNav.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        week.forEach { d ->
            val isToday = d.date == today
            val past = d.date.isBefore(today) && (since == null || d.date.toEpochDay() >= since)
            val shape = RoundedCornerShape(if (F.shape.pill) 14.dp else F.shape.radiusSmall)
            val doneAll = d.planned.isNotEmpty() && d.planned.all { d.isDone(it) }
            val trained = d.planned.isNotEmpty() || d.offPlan.isNotEmpty()
            // A tap opens the day in full: the session as it will be, what it is for, why on this day.
            Column(
                Modifier.weight(1f).clip(shape).clickable(onClickLabel = "Подробнее о дне") { nav.push(Screen.Day(d.date.toEpochDay())) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(labelText(dayShort(d.date.dayOfWeek)), style = T.label().copy(color = if (isToday) F.c.accent else F.c.text3))
                var m = Modifier.fillMaxWidth().height(56.dp).clip(shape)
                m = when {
                    isToday -> m.background(F.c.accent)
                    trained -> m.background(F.c.surface).border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, F.c.line, shape)
                    else -> m.border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, F.c.line, shape)
                }
                Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    NumText(
                        d.date.dayOfMonth.toString(),
                        style = T.number(16.sp).copy(color = if (isToday) F.c.onAccent else if (!trained) F.c.text3 else F.c.text),
                    )
                    val state = dayState(d, today, since)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp).height(12.dp).semantics { contentDescription = state },
                    ) {
                        val runColor = if (isToday) F.c.onAccent.copy(alpha = 0.5f) else F.c.endurance
                        val liftColor = if (isToday) F.c.onAccent else F.c.strength
                        fun kindColor(run: Boolean) = if (run) runColor else liftColor
                        when {
                            d.paused -> if (d.offPlan.isEmpty()) Text("—", style = T.secondary(11.sp))
                            doneAll -> Icon(Ic.check, null, tint = if (isToday) F.c.onAccent else F.c.good, modifier = Modifier.size(12.dp))
                            else -> d.planned.forEach { s ->
                                when {
                                    d.isDone(s) -> Icon(Ic.check, null, tint = if (isToday) F.c.onAccent else F.c.good, modifier = Modifier.size(10.dp))
                                    // Missed: a hollow red dot, never like one still ahead.
                                    past -> Ring(F.c.bad)
                                    else -> Dot(kindColor(s.isRun))
                                }
                            }
                        }
                        // Done beyond the plan of the day (caught up, extra, on a pause day): a tick in the colour of its kind.
                        d.offPlan.forEach { w ->
                            Icon(Ic.check, null, tint = kindColor(w.kind == forma.app.data.Kind.RUN), modifier = Modifier.size(10.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiftCard(s: PlannedSession, program: Program, today: LocalDate, doneAs: WorkoutEntity?, light: Boolean, onStart: () -> Unit) {
    val repo = LocalRepo.current
    val done = doneAs != null
    // Done: what was done (its own plan and sets), not what the plan holds now — that is the next session.
    val plan by produceState<LiftPlan?>(null, s.templateId, today, light, done) {
        value = if (done) null else runCatching { repo.liftPlan(s.templateId!!, today, forceDeload = light) }.getOrNull()
    }
    val t = program.templates.firstOrNull { it.id == s.templateId }
    // The time at this person's own pace in the gym, learned from their sessions.
    val ratio by produceState(1.0) { value = repo.timeRatio() }
    val budget = repo.store.profile.collectAsState(initial = null).value?.sessionMinutes
    val minutes = if (doneAs != null) doneAs.durationSec?.let { it / 60 } else plan?.let { forma.core.plan.SessionTime.round(forma.app.data.TrainingRepository.estimateOf(it) * ratio) } ?: t?.let { Templates.minutes(it) }
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.dumbbell, null, tint = F.c.strength, modifier = Modifier.size(18.dp))
            Text(labelText("  Силовая"), style = T.label().copy(color = F.c.strength), modifier = Modifier.weight(1f))
            if (minutes != null) NumText(if (done) "$minutes мин" else "~$minutes мин", style = T.number(13.sp, FontWeight.Medium).copy(color = F.c.text2))
        }
        Text(t?.title ?: "Силовая", style = T.title(26.sp))
        if (doneAs != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Ic.check, null, tint = F.c.good, modifier = Modifier.size(18.dp))
                Text(
                    if (doneAs.templateId != s.templateId) "Сделано: ${doneAs.title}" else "Сделано",
                    style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.good),
                )
            }
            DoneLiftDetails(doneAs, max = 3)
            return@FCard
        }
        if (minutes != null && budget != null && ratio != 1.0 && minutes > budget * 1.15) Text(
            "У тебя тренировки идут дольше расчёта (×${forma.core.text.Ru.one(ratio)}): выйдет около $minutes мин при твоих $budget. " +
                "Не успеваешь — убери подсобное упражнение в «План» или сократи отдых.",
            style = T.secondary(12.sp).copy(color = F.c.warn),
        )
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
        if (calib > 0) Text("Первая тренировка с этими упражнениями: начни с подсказанного веса — после первого подхода приложение подберёт рабочий.", style = T.secondary(13.sp))
        PrimaryButton(if (light) "Начать лёгкую тренировку" else "Начать тренировку", onStart, Modifier.fillMaxWidth(), color = F.c.strength, textColor = F.c.onData)
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
    // A free run done today in its place (for a trial): said as it is, not as the plan's. Any other run done is the
    // plan's own (the planned one, lighter for a bad day, entered by hand): it shows as it was run, for the plan has
    // moved on since — what it holds now is the next session.
    val other = doneAs != null && doneAs.templateId == forma.core.run.RunType.FREE.name
    val workout by produceState<RunWorkout?>(null, s.kind, today, light, doneAs?.id) {
        value = doneAs?.takeIf { !other }?.let { repo.runPlanOf(it.id) } ?: repo.runFor(s.kind, today, light)
    }
    val w = workout ?: return
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
            DoneRunDetails(doneAs)
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
private fun SickCard(today: LocalDate, onTrainAnyway: () -> Unit, onPause: () -> Unit) {
    FCard {
        SectionLabel("Отдых")
        Text("Сегодня выздоравливаем", style = T.title(22.sp))
        Text(
            "При температуре, ломоте или кашле — никаких тренировок, пока не пройдёт. Пропущенное можно будет сделать в свободный " +
                "день, а после перерыва первые тренировки станут легче сами. Болезнь на несколько дней — поставь паузу: " +
                "пропуски не будут висеть.",
            style = T.secondary(14.sp),
        )
        PrimaryButton(
            "Пауза на $SICK_PAUSE_DAYS дня — до ${shortDate(today.plusDays(SICK_PAUSE_DAYS - 1L))}", onPause, Modifier.fillMaxWidth(),
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

/** The goal race: the days to it and what they hold, the day before what to do, on its day the start. */
@Composable
private fun RaceCard(goal: forma.core.model.RunGoal, today: LocalDate, raceDone: Boolean, runToday: Boolean, onStart: () -> Unit) {
    val day = goal.date ?: return
    val date = LocalDate.ofEpochDay(day)
    val days = day - today.toEpochDay()
    if (days !in 0..21) return
    val name = goalDistance(goal.meters)
    FCard(gap = 8.dp) {
        SectionLabel(if (days == 0L) "Забег сегодня" else "Забег-цель", color = F.c.endurance)
        Text(
            when (days) {
                0L -> "$name за ${Ru.clock(goal.seconds)}"
                1L -> "Завтра · $name за ${Ru.clock(goal.seconds)}"
                else -> "Через $days ${Ru.plural(days.toInt(), "день", "дня", "дней")} · ${Ru.date(date)}"
            },
            style = T.title(20.sp),
        )
        Text(
            when {
                days == 0L && raceDone -> "Забег пройден — время в истории. Два дня — отдых или совсем лёгкий бег, потом план дальше."
                days == 0L && runToday -> "Пробежка по плану сегодня — это забег: старт в её карточке ниже."
                days == 0L -> "Разминка 12 минут и 3–4 ускорения. Первая половина — ровно по темпу цели, без рывка со старта. После финиша впиши время с табло."
                days == 1L -> "Сегодня — 20 минут легко с 4 ускорениями или отдых. Ужин как обычно, ляг пораньше. Завтрак — за 2–3 часа до старта."
                days <= 7 -> "Неделя забега: бег короче, за 3–5 дней — короткая тренировка в темпе цели, дальше только легко. Ноги в зале за 3 дня до забега — облегчённо."
                days <= 14 -> "Подводка: последние две недели тренировки легче, шаги лестниц не двигаются — копится свежесть."
                else -> "Контрольного до забега не будет: забег и есть проверка. С ${Ru.date(date.minusDays(14))} — подводка, тренировки легче."
            },
            style = T.secondary(14.sp),
        )
        if (days == 0L && !raceDone && !runToday) PrimaryButton("К старту", onStart, Modifier.fillMaxWidth())
    }
}

