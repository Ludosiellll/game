package forma.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.CoachActions
import forma.app.data.GymGoals
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.components.Chip
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.TopBar
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.core.model.Catalog
import forma.core.model.LiftGoal
import forma.core.strength.GymGoal
import forma.core.text.Ru
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * «Цель в зале»: one lift to a weight. The way to it from the person's own sessions as it is typed, and what the plan
 * changes for it — made only with the person's «Поставить цель и подстроить план»; «Только цель» leaves the plan alone.
 */
@Composable
fun GymGoalScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val profile by repo.store.profile.collectAsState(initial = null)
    val program by repo.store.program.collectAsState(initial = null)
    val settings by repo.store.settings.collectAsState(initial = forma.app.data.Settings())
    val p = profile ?: return
    val old = p.liftGoal
    val candidates by produceState(emptyList<String>(), program, settings) { value = GymGoals.candidates(repo) }
    var id by rememberSaveable { mutableStateOf(old?.exerciseId) }
    var kgText by rememberSaveable { mutableStateOf(old?.kg?.let { Ru.kg(it) } ?: "") }
    var reps by rememberSaveable { mutableStateOf(old?.reps ?: 1) }
    var dateText by rememberSaveable {
        mutableStateOf(old?.date?.let { LocalDate.ofEpochDay(it).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")) } ?: "")
    }
    val chosen = id ?: candidates.firstOrNull()
    val kg = kgText.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it in 1.0..500.0 }
    val today = repo.today()
    val date = if (dateText.isBlank()) null else parseRaceDay(dateText, today)?.takeIf { it.isAfter(today) }
    val dateBad = dateText.isNotBlank() && date == null
    val draft = if (chosen != null && kg != null && !dateBad) LiftGoal(chosen, kg, reps, date?.toEpochDay()) else null
    val path by produceState<GymGoal.Path?>(null, draft) { value = draft?.let { GymGoals.path(repo, it) } }
    val words by produceState<String?>(null, draft) { value = draft?.let { GymGoals.words(repo, it) } }
    val proposal by produceState<GymGoals.Proposal?>(null, chosen, program, settings) {
        val prog = program
        value = if (chosen != null && prog != null) GymGoals.propose(CoachActions.places(repo), prog, p.experience, chosen) else null
    }
    fun save(adjust: Boolean) {
        val g = draft ?: return
        scope.launch {
            repo.setLiftGoal(g, adjust)
            nav.back()
        }
    }
    ScrollScreen {
        TopBar("Цель в зале", "Один вес, к которому идёшь", onBack = { nav.back() })
        if (candidates.isEmpty()) {
            Text(
                "В плане нет упражнения, на которое можно поставить цель: нужно упражнение на несколько мышц с весом — жим, присед, тяга.",
                style = T.body(15.sp),
            )
            return@ScrollScreen
        }
        SectionLabel("Упражнение")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            candidates.forEach { c -> Chip(Catalog[c].name, c == chosen, { id = c }) }
        }
        val each = chosen?.let { if (Catalog[it].perHand) " (каждая гантель)" else "" }.orEmpty()
        NumberField(kgText, { kgText = it }, "Вес$each", "кг", keyboard = KeyboardType.Decimal, placeholder = "100")
        SectionLabel("На сколько раз")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "1 раз", 3 to "3 раза", 5 to "5 раз").forEach { (n, label) -> Chip(label, reps == n, { reps = n }, Modifier.weight(1f)) }
        }
        NumberField(dateText, { dateText = it }, "Дата (если есть)", "дд.мм", keyboard = KeyboardType.Text, placeholder = "15.03")
        Text(
            when {
                dateBad -> "Не похоже на дату или она уже прошла. Пиши число и месяц через точку, например 15.03."
                date != null -> "За ${GymGoal.TAPER_DAYS} дней до ${Ru.date(date)} подходов станет вдвое меньше при том же весе — сила проявится полностью. В сам день — проверка максимума."
                else -> "Есть дата (соревнование, спор) — впиши: перед ней будет подводка и проверка максимума. Нет — просто иди к цели."
            },
            style = T.secondary(13.sp).let { if (dateBad) it.copy(color = F.c.warn) else it },
        )
        if (draft != null) {
            FCard(gap = 8.dp) {
                Text("Путь к цели", style = T.secondary(13.sp))
                path?.share?.let { GoalBar(it) }
                words?.let { Text(it, style = T.body(15.sp)) }
            }
        }
        val pr = proposal
        if (pr != null) {
            SectionLabel("Что поменяется в плане")
            FCard(gap = 8.dp) {
                pr.lines.forEach { Text(it, style = T.body(14.sp)) }
                Text("Твои подходы и повторы у этого упражнения заменятся этими. Снимешь цель — вернутся, если ты их не менял.", style = T.secondary(12.sp))
            }
        }
        val done = path?.verdict == GymGoal.Verdict.DONE
        PrimaryButton("Поставить цель и подстроить план", { save(adjust = true) }, Modifier.fillMaxWidth(), enabled = draft != null && !done && pr != null)
        GhostButton("Только цель, план не трогать", { if (draft != null && !done) save(adjust = false) }, Modifier.fillMaxWidth())
        if (old != null) {
            GhostButton("Снять цель", {
                scope.launch {
                    repo.setLiftGoal(null, adjust = false)
                    nav.back()
                }
            }, Modifier.fillMaxWidth(), color = F.c.warn)
        }
    }
}

/** How much of the way is behind: a bar. */
@Composable
private fun GoalBar(share: Double) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(F.c.line)) {
        Box(Modifier.fillMaxWidth(share.toFloat().coerceIn(0.02f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(F.c.strength))
    }
}

/** «Прогресс → Сила»: the goal in the gym and the way to it, or a word on setting one. */
@Composable
fun GymGoalCard() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val profile by repo.store.profile.collectAsState(initial = null)
    val rows by remember { repo.setRows() }.collectAsState(initial = emptyList())
    val goal = profile?.liftGoal
    val path by produceState<GymGoal.Path?>(null, goal, rows) { value = goal?.let { GymGoals.path(repo, it) } }
    val words by produceState<String?>(null, goal, rows) { value = goal?.let { GymGoals.words(repo, it) } }
    FCard(gap = 6.dp) {
        Text("Цель в зале", style = T.secondary(13.sp))
        if (goal == null) {
            Text("Поставь цель — например, пожать 100 кг. Приложение покажет, когда примерно её возьмёшь, и подстроит план.", style = T.body(14.sp))
            GhostButton("Поставить цель", { nav.push(Screen.GymGoal) }, Modifier.fillMaxWidth())
        } else {
            val ex = Catalog.find(goal.exerciseId)
            Text(
                "${ex?.name ?: goal.exerciseId} ${Ru.kg(goal.kg)} кг" + if (goal.reps > 1) " × ${goal.reps}" else "",
                style = T.title(24.sp).copy(color = F.c.strength),
            )
            path?.share?.let { GoalBar(it) }
            words?.let { Text(it, style = T.body(14.sp)) }
            GhostButton("Изменить цель", { nav.push(Screen.GymGoal) }, Modifier.fillMaxWidth())
        }
    }
}

/**
 * The goal's day on «Сегодня»: the test of the max — warm-ups to an opener, then the goal — first thing, fresh. Nothing
 * on other days.
 */
@Composable
fun GoalTestDayCard(today: LocalDate) {
    val repo = LocalRepo.current
    val profile by repo.store.profile.collectAsState(initial = null)
    val goal = profile?.liftGoal?.takeIf { it.date == today.toEpochDay() } ?: return
    val e1rm by produceState<Double?>(null, goal) { value = repo.db.state().stat(goal.exerciseId)?.e1rm }
    val ex = Catalog.find(goal.exerciseId) ?: return
    FCard(gap = 6.dp, outline = F.c.strength.copy(alpha = 0.6f)) {
        Text("Сегодня день цели", style = T.title(20.sp))
        Text("${ex.name} ${Ru.kg(goal.kg)} кг" + if (goal.reps > 1) " × ${goal.reps}" else "", style = T.body(16.sp, FontWeight.Bold))
        val max = e1rm
        if (max == null) {
            Text("Подходов с этим упражнением ещё не было — проверять нечего. Цель можно перенести.", style = T.body(14.sp))
            return@FCard
        }
        val day = GymGoal.testDay(max, GymGoal.target(goal), ex.step)
        Text("Проверка максимума — первым упражнением, на свежую голову, со страховкой:", style = T.body(14.sp))
        Text("Разминка: " + day.warmups.joinToString(", ") { "${Ru.kg(it.first)} × ${it.second}" } + ".", style = T.body(14.sp))
        Text(
            "Попытки на 1 раз: " + day.attempts.joinToString(", потом ") { "${Ru.kg(it)} кг" } +
                ". Между ними 3–5 минут. Первая пошла тяжело — вторую не ставь больше." +
                if (goal.reps > 1) " Цель на ${goal.reps} раза — здесь её максимум на один раз." else "",
            style = T.body(14.sp),
        )
    }
}
