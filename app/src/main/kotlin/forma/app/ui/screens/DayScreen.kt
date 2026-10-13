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
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.core.plan.DayReasons
import forma.core.plan.Focus
import forma.core.plan.PlannedSession
import forma.core.plan.Program
import forma.core.plan.ProgramGenerator
import forma.core.run.RunExplain
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
    val today = forma.app.ui.rememberToday(repo)
    val program by repo.store.program.collectAsState(initial = null)
    val profile by repo.store.profile.collectAsState(initial = null)
    val settings by repo.store.settings.collectAsState(initial = Settings())
    val monday = remember(date) { date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    val week by remember(monday) { repo.weekFlow(monday) }.collectAsState(initial = emptyList())
    val p = program ?: return
    val item = week.firstOrNull { it.date == date }
    val sessions = p.week.on(date.dayOfWeek.value)
    val trialOn by produceState<LocalDate?>(null, p, today) { value = repo.trialDay(today) }
    val title = when (date) {
        today -> "Сегодня"
        today.plusDays(1) -> "Завтра"
        today.minusDays(1) -> "Вчера"
        else -> null
    }
    ScrollScreen {
        // "Завтра" over "Четверг, 15 октября"; a day further off is its name over its date.
        TopBar(title ?: DAY_FULL[date.dayOfWeek.value - 1], if (title != null) dateLine(date) else shortDate(date), onBack = { nav.back() })
        if (repo.paused(settings, date)) {
            forma.app.ui.components.Banner("Пауза: в этот день тренировок нет, план стоит.", F.c.warn)
        }
        if (sessions.isEmpty()) {
            FCard(gap = 6.dp) {
                SectionLabel("Отдых")
                Text("В этот день по плану отдыха", style = T.title(20.sp))
                Text(
                    "Мышцы, сердце и связки становятся сильнее не во время тренировки, а в отдыхе после неё. Прогулка или " +
                        "растяжка не помешают.",
                    style = T.secondary(14.sp),
                )
            }
        }
        sessions.forEach { s ->
            val done = item?.doneFor(s)
            if (s.isRun) {
                DayRunCard(s, date, done, trial = date == trialOn, goal = profile?.runGoal)
            } else {
                DayLiftCard(s, p, date, done)
            }
        }
        val reasons = DayReasons.of(p, date.dayOfWeek.value)
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

/** A run of the day: as it will be, and the words behind it. Done already — what was done, one tap from its story. */
@Composable
private fun DayRunCard(s: PlannedSession, date: LocalDate, done: WorkoutEntity?, trial: Boolean, goal: forma.core.model.RunGoal?) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val w by produceState<RunWorkout?>(null, s.kind, date, trial) { value = repo.runFor(s.kind, date, trial = trial) }
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
                Text(if (done.templateId == workout.type.name) "Сделано" else "Сделано: ${done.title}", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.good))
            }
            GhostButton("Как прошло — разбор", { nav.push(Screen.HistoryItem(done.id)) }, Modifier.fillMaxWidth(), height = 48.dp)
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
        Explained("Как бежать", listOf(workout.note, why.how).filter { it.isNotBlank() }.joinToString(" "))
        if (why.next.isNotBlank()) Explained("Как дальше", why.next)
        Explained("Восстановление", "Тело обычно восстанавливается после неё за ${RunExplain.recoveryWords(why.recoveryHours)}.")
    }
}

/** The gym session of the day: its exercises and weights, what the session is for, where the cycle is. */
@Composable
private fun DayLiftCard(s: PlannedSession, p: Program, date: LocalDate, done: WorkoutEntity?) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val plan by produceState<LiftPlan?>(null, s.templateId, date) {
        value = runCatching { repo.liftPlan(s.templateId!!, date) }.getOrNull()
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
                Text("Сделано", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.good))
            }
            GhostButton("Открыть тренировку", { nav.push(Screen.HistoryItem(done.id)) }, Modifier.fillMaxWidth(), height = 48.dp)
        }
        plan?.let { lp ->
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
        val hardRun = p.week.on(date.dayOfWeek.value).any { it.isHardRun }
        template?.let { Explained("Зачем", focusWhy(it.focus, hardRun)) }
        Explained("Цели в зале", LIFT_FOCUS.firstOrNull { it.value == p.aim.normalized().lift }?.let { "${it.label} — ${it.text.replaceFirstChar { c -> c.lowercase() }}" } ?: "")
        cycleWords(p, date)?.let { Explained("Неделя цикла", it) }
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

/** Where the lifting cycle is in the week of [date]: the same words as the plan's cycle card. */
private fun cycleWords(p: Program, date: LocalDate): String? {
    if (p.blockWeeks <= 0) return null
    val pos = (ProgramGenerator.weekIndex(p, date) - 1) % p.blockWeeks + 1
    val off = ProgramGenerator.rirOffset(p, date)
    return "Неделя $pos из ${p.blockWeeks}: " + when {
        pos == p.blockWeeks -> "разгрузка — вдвое меньше подходов, вес −10 %. Дальше новый цикл с тех же весов."
        off > 0 -> "вход в цикл — в каждом подходе оставляй на 1 повтор больше запаса."
        off < 0 -> "пик цикла — запаса на 1 повтор меньше. Следующая неделя — разгрузка."
        else -> "рабочая неделя — запас как в плане."
    }
}
