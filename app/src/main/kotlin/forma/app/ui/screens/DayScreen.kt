package forma.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.LiftPlan
import forma.app.data.Settings
import forma.app.data.WorkoutEntity
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.NumText
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.TopBar
import kotlinx.coroutines.launch
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.core.plan.DayReasons
import forma.core.plan.Focus
import forma.core.plan.PlannedSession
import forma.core.plan.Program
import forma.core.plan.ProgramGenerator
import forma.core.run.RunExplain
import forma.core.run.RunType
import forma.core.run.RunWorkout
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * A day of the plan in full: each session as it will be (the run's parts, laps and paces; the gym's exercises and
 * weights), what it is for, what it trains, how not to spoil it, how the plan moves on from it — and why it sits on this
 * day. Opened from the plan's week and from the days on the main screen.
 */
@Composable
fun DayScreen(date: LocalDate) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val today = forma.app.ui.rememberToday(repo)
    val program by repo.store.program.collectAsState(initial = null)
    val profile by repo.store.profile.collectAsState(initial = null)
    val settings by repo.store.settings.collectAsState(initial = Settings())
    val monday = remember(date) { date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    val week by remember(monday) { repo.weekFlow(monday) }.collectAsState(initial = emptyList())
    val p = program ?: return
    val item = week.firstOrNull { it.date == date }
    val paused = repo.paused(settings, date)
    // A pause day holds no sessions: the plan stands.
    val sessions = if (paused) emptyList() else item?.planned ?: p.week.on(date.dayOfWeek.value)
    val trialOn by produceState<LocalDate?>(null, p, today) { value = repo.trialDay(today) }
    val runState by repo.store.runState.collectAsState(initial = null)
    // A missed session of this week that today (a free day) can take: offered on its own day too.
    val catchUp by produceState<forma.core.plan.CatchUp.Suggestion?>(null, week, settings, today) { value = repo.catchUp(today) }
    fun doToday(s: PlannedSession) {
        scope.launch {
            if (s.isRun) nav.push(Screen.RunSetup(repo.runFor(s.kind, today), s.kind))
            else {
                // Never over an unfinished session: that one opens instead.
                nav.push(Screen.Workout(repo.db.workouts().active()?.id ?: repo.startLift(s.templateId!!, today)))
            }
        }
    }
    // A hard run as things stand: after a long break the hard sessions are easy runs for a while, and before running
    // continuously every run is run/walk (as runFor builds them).
    val hard: (PlannedSession) -> Boolean = { s ->
        val st = runState?.let { forma.core.run.RunPlan.withBreak(it, date.toEpochDay()) }
        s.isHardRun && (st == null || (st.canRunContinuously && !(s.kind == forma.core.plan.SessionKind.RUN_QUALITY && st.returnEasy > 0)))
    }
    val title = when (date) {
        today -> "Сегодня"
        today.plusDays(1) -> "Завтра"
        today.minusDays(1) -> "Вчера"
        else -> null
    }
    ScrollScreen {
        // "Завтра" over "Четверг, 15 октября"; a day further off is its name over its date.
        TopBar(title ?: DAY_FULL[date.dayOfWeek.value - 1], if (title != null) dateLine(date) else shortDate(date), onBack = { nav.back() })
        if (paused) {
            forma.app.ui.components.Banner("Пауза: в этот день тренировок по плану нет, план стоит.", F.c.warn)
        } else if (sessions.isEmpty() && item?.offPlan.isNullOrEmpty()) {
            FCard(gap = 6.dp) {
                SectionLabel("Отдых")
                Text("По плану — отдых", style = T.title(20.sp))
                Text(
                    "Мышцы, сердце и связки становятся сильнее не во время тренировки, а в отдыхе после неё. Прогулка или " +
                        "растяжка не помешают.",
                    style = T.secondary(14.sp),
                )
            }
        }
        sessions.forEach { s ->
            val done = item?.doneFor(s)
            val later = item?.doneLaterFor(s)
            // Missed: a day already over, of the plan as it was then (not a day before the plan was made).
            val missed = done == null && later == null && date.isBefore(today) && p.createdEpochDay?.let { date.toEpochDay() >= it } != false
            when {
                later != null || missed -> NotOnItsDayCard(
                    s, p, later,
                    // Only this week's: catch-up does not reach back into the weeks before.
                    canCatchUp = missed && catchUp?.let { it.session == s && it.plannedOn == date.dayOfWeek.value && monday.plusDays(6) >= today } == true,
                    thisWeek = !today.isAfter(monday.plusDays(6)),
                ) { doToday(s) }
                s.isRun -> DayRunCard(s, date, done, trial = date == trialOn, goal = profile?.runGoal)
                else -> DayLiftCard(s, p, date, done, hardRun = sessions.any(hard))
            }
        }
        // Done beyond the plan of the day: a missed session caught up, or one extra — on a pause day too.
        item?.offPlan?.forEach { w -> OffPlanCard(w, item.catchUps[w.id]) }
        val reasons = if (paused) emptyList() else DayReasons.of(p, date.dayOfWeek.value, hard)
        if (reasons.isNotEmpty()) {
            SectionLabel("Почему в этот день")
            FCard(gap = 8.dp) { reasons.forEach { Text(it, style = T.body(15.sp)) } }
        }
        // The other days of the week, one tap away.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("← ${DAY_SHORT[date.minusDays(1).dayOfWeek.value - 1]}", { nav.replaceTop(Screen.Day(date.minusDays(1).toEpochDay())) }, Modifier.weight(1f))
            GhostButton("${DAY_SHORT[date.plusDays(1).dayOfWeek.value - 1]} →", { nav.replaceTop(Screen.Day(date.plusDays(1).toEpochDay())) }, Modifier.weight(1f))
        }
    }
}

/**
 * A planned session of a day already over that was not done on it: caught up later in the week ([later]: "Сделано в ср"
 * with what was done), or missed — said so, never as if still ahead; [canCatchUp]: today is a free day it fits.
 */
@Composable
private fun NotOnItsDayCard(s: PlannedSession, p: Program, later: WorkoutEntity?, canCatchUp: Boolean, thisWeek: Boolean, onToday: () -> Unit) {
    val color = if (s.isRun) F.c.endurance else F.c.strength
    FCard(gap = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (s.isRun) Ic.run else Ic.dumbbell, null, tint = color, modifier = Modifier.size(18.dp))
            Text(forma.app.ui.theme.labelText("  ${kindLabel(s)}"), style = T.label().copy(color = color), modifier = Modifier.weight(1f))
        }
        Text(sessionTitle(s, p), style = T.title(22.sp))
        if (later != null) {
            DoneLaterLine(later)
            if (later.kind == forma.app.data.Kind.LIFT) DoneLiftDetails(later, max = 3) else DoneRunDetails(later)
            return@FCard
        }
        SectionLabel("Пропущено", color = F.c.bad)
        when {
            canCatchUp -> {
                Text("Сегодня свободный день, и эта тренировка в него помещается: соседним тренировкам она не помешает.", style = T.secondary(14.sp))
                forma.app.ui.components.PrimaryButton("Сделать в свободный день", onToday, Modifier.fillMaxWidth(), color = color, textColor = F.c.onData)
            }
            thisWeek -> Text(
                "Пропущенное можно сделать в свободный день этой недели, если оно не помешает соседним тренировкам, — " +
                    "главный экран предложит его сам.",
                style = T.secondary(14.sp),
            )
            else -> Text("Неделя прошла — план идёт дальше, догонять не нужно.", style = T.secondary(14.sp))
        }
    }
}

/**
 * A run of the day: as it will be, and the words behind it. Done already — the run as it was run that day, one tap from
 * its story; a free run in its place — said as it is.
 */
@Composable
private fun DayRunCard(s: PlannedSession, date: LocalDate, done: WorkoutEntity?, trial: Boolean, goal: forma.core.model.RunGoal?) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    // A free run done in its place (on a hard day): said as it is, as on the main screen — the planned one was not run,
    // and which one it would have been (the trial or what follows it) has moved on with the plan.
    if (done != null && done.templateId == RunType.FREE.name) {
        FCard(gap = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.run, null, tint = F.c.endurance, modifier = Modifier.size(18.dp))
                Text(forma.app.ui.theme.labelText("  ${kindLabel(s)}"), style = T.label().copy(color = F.c.endurance), modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Ic.check, null, tint = F.c.good, modifier = Modifier.size(18.dp))
                Text("Сделано: ${done.title}", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.good))
            }
            if (s.kind == forma.core.plan.SessionKind.RUN_QUALITY) {
                Text("Тяжёлая тренировка перешла на следующий тяжёлый день: план бега сдвигается только по самой тренировке.", style = T.secondary(14.sp))
            }
            GhostButton("Как прошло — разбор", { nav.push(Screen.HistoryItem(done.id)) }, Modifier.fillMaxWidth(), height = 48.dp)
        }
        return
    }
    // Done: the run as planned that day (the plan has moved on since — it holds the next session now). Ahead: as the plan
    // holds it now.
    val w by produceState<RunWorkout?>(null, s.kind, date, trial, done?.id) {
        value = done?.let { repo.runPlanOf(it.id) } ?: repo.runFor(s.kind, date, trial = trial)
    }
    val workout = w ?: return
    val why = remember(workout, goal) { RunExplain.of(workout, goal) }
    FCard(gap = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.run, null, tint = F.c.endurance, modifier = Modifier.size(18.dp))
            Text(forma.app.ui.theme.labelText("  ${kindLabel(s)}"), style = T.label().copy(color = F.c.endurance), modifier = Modifier.weight(1f))
            NumText("${workout.totalSeconds / 60} мин", style = T.number(13.sp, FontWeight.Medium).copy(color = F.c.text2))
        }
        Text(workout.title, style = T.title(22.sp))
        if (done != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Ic.check, null, tint = F.c.good, modifier = Modifier.size(18.dp))
                Text("Сделано", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.good))
            }
            DoneRunDetails(done)
        }
        if (!workout.isOpenEnded) {
            Column {
                groupSegments(workout).forEach { (name, detail, target) ->
                    Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = T.body(15.sp, FontWeight.SemiBold))
                            if (target != null) NumText(target, style = T.secondary(13.sp))
                        }
                        NumText(detail, style = T.number(14.sp, FontWeight.Bold).copy(color = F.c.text2))
                    }
                }
            }
        }
        Explained("Зачем", why.purpose)
        Explained("Что даёт телу", why.body)
        // How to run it and what follows: for a run ahead; a done one has its debrief and the plan's decisions.
        if (done == null) {
            Explained("Как бежать", listOf(workout.note, why.how).filter { it.isNotBlank() }.joinToString(" "))
            if (why.next.isNotBlank()) Explained("Как дальше", why.next)
        }
        Explained("Восстановление", "Тело обычно восстанавливается после этой тренировки за ${RunExplain.recoveryWords(why.recoveryHours)}.")
    }
}

/** The gym session of the day: its exercises and weights, what the session is for, where the cycle is. */
@Composable
private fun DayLiftCard(s: PlannedSession, p: Program, date: LocalDate, done: WorkoutEntity?, hardRun: Boolean) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val plan by produceState<LiftPlan?>(null, s.templateId, date, done?.id) {
        value = if (done != null) null else runCatching { repo.liftPlan(s.templateId!!, date) }.getOrNull()
    }
    val template = p.templates.firstOrNull { it.id == s.templateId }
    FCard(gap = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.dumbbell, null, tint = F.c.strength, modifier = Modifier.size(18.dp))
            Text(forma.app.ui.theme.labelText("  Силовая"), style = T.label().copy(color = F.c.strength), modifier = Modifier.weight(1f))
        }
        Text(sessionTitle(s, p), style = T.title(22.sp))
        if (done != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Ic.check, null, tint = F.c.good, modifier = Modifier.size(18.dp))
                Text(
                    if (done.templateId != s.templateId) "Сделано: ${done.title}" else "Сделано",
                    style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.good),
                )
            }
            // What was done that day (its own plan and sets), not the session the plan holds now.
            DoneLiftDetails(done)
        }
        if (done == null) plan?.let { lp ->
            Column {
                lp.items.forEach { item ->
                    Hairline()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(item.name, style = T.body(15.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                        NumText(prescriptionShort(item.prescription.sets, item.prescription.reps, item.prescription.weight, item.slot.exerciseId), style = T.number(14.sp, FontWeight.Bold))
                    }
                }
            }
        }
        template?.let { Explained("Зачем", focusWhy(it.focus, hardRun)) }
        Explained("Цели в зале", LIFT_FOCUS.firstOrNull { it.value == p.aim.normalized().lift }?.let { "${it.label} — ${it.text.replaceFirstChar { c -> c.lowercase() }}" } ?: "")
        val lifting by LocalRepo.current.store.liftState.collectAsState(initial = forma.app.data.LiftState())
        cycleWords(p, date, lifting.reactiveDeloadStart)?.let { Explained("Неделя цикла", "Неделя ${it.first} из ${p.blockWeeks}: ${it.second}") }
        Explained("Как дальше", "После каждой тренировки вес и повторы пересчитываются по тому, как прошли подходы: все повторы с запасом — прибавка, не хватило — повтор или шаг назад.")
    }
}

@Composable
private fun Explained(title: String, text: String) {
    if (text.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = T.body(14.sp, FontWeight.Bold))
        Text(text, style = T.secondary(14.sp))
    }
}

/** What a gym session works; [hardRun]: the day also has a hard run. */
private fun focusWhy(f: Focus, hardRun: Boolean): String = when (f) {
    Focus.UPPER -> "Верх: грудь, спина, плечи, руки. Бегу почти не мешает" + if (hardRun) " — поэтому стоит в день тяжёлого бега." else "."
    Focus.LOWER -> "Низ: ноги, ягодицы, корпус. Сила ног делает шаг экономнее — это показано на бегунах; тяжёлые ноги стоят " +
        "подальше от тяжёлого бега."
    Focus.FULL -> "Всё тело: главные движения за одну тренировку."
}

/**
 * Where the lifting cycle is in the week of [date]: the week of the block and what it asks, the same words as the plan's
 * cycle card. [reactive]: the Monday of a lighter week the app set itself (weights stepping back).
 */
internal fun cycleWords(p: Program, date: LocalDate, reactive: Long?): Pair<Int, String>? {
    if (p.blockWeeks <= 0) return null
    val pos = (ProgramGenerator.weekIndex(p, date) - 1) % p.blockWeeks + 1
    val off = ProgramGenerator.rirOffset(p, date)
    val d = date.toEpochDay()
    return pos to when {
        pos == p.blockWeeks && reactive != null && d in reactive until reactive + 7 ->
            "разгрузка раньше плана — веса откатывались. Вдвое меньше подходов, вес −10 %, потом новый цикл."
        pos == p.blockWeeks -> "разгрузка — вдвое меньше подходов, вес −10 %. Дальше новый цикл с тех же весов."
        reactive != null && d in reactive - 7 until reactive -> "рабочая неделя — запас как в плане. Следующая — разгрузка: веса откатывались."
        off > 0 -> "вход в цикл — в каждом подходе оставляй на 1 повтор больше запаса."
        off < 0 -> "пик цикла — запаса на 1 повтор меньше. Следующая неделя — разгрузка."
        else -> "рабочая неделя — запас как в плане."
    }
}
