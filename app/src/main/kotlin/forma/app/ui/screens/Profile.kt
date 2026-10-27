package forma.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import forma.core.text.Ru
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.LocalNav
import forma.app.ui.Screen
import forma.app.ui.LocalRepo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import forma.app.ui.components.Chip
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.TopBar
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.RunLevel
import forma.core.model.Sex
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val profile by repo.store.profile.collectAsState(initial = null)
    val program by repo.store.program.collectAsState(initial = null)
    val base = profile ?: return
    // Kept through rotation and the system closing the app in the background.
    val d = androidx.compose.runtime.saveable.rememberSaveable(base, saver = ProfileDraft.Saver) { ProfileDraft(base) }
    // The week as the plan has it now, shown to change from. Saved untouched it stays the app's to lay out (the same
    // days laid out as the person's own may come out another way): only a tap on a day makes the week theirs.
    val week = program?.week
    val planGym = androidx.compose.runtime.remember(week) {
        week?.days?.filter { day -> day.sessions.any { it.kind == forma.core.plan.SessionKind.LIFT } }?.map { it.day }?.toSet().orEmpty()
    }
    val planRun = androidx.compose.runtime.remember(week) { week?.days?.filter { day -> day.sessions.any { it.isRun } }?.map { it.day }?.toSet().orEmpty() }
    var confirmCopied by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val settings by repo.store.settings.collectAsState(initial = forma.app.data.Settings())
    fun save() {
        // The app's own days tapped back to as they were: still the app's week (the same days as one's own may lay out
        // another way).
        if (base.gymDays == null && d.gymDays == planGym && d.runDays == planRun) {
            d.gymDays = null
            d.runDays = null
        }
        scope.launch {
            repo.updateProfile(d.toProfile(base))
            nav.back()
        }
    }
    val copiedFrom = program?.copiedFrom
    ScrollScreen {
        TopBar("Цель, дни и данные", "План пересчитается, прогресс в упражнениях останется", onBack = { nav.back() })
        if (copiedFrom != null) {
            FCard(gap = 8.dp) {
                Text("Сейчас ты тренируешься по программе друга ($copiedFrom)", style = T.title(18.sp))
                Text(
                    if (settings.ownPlan != null) "Твоя прежняя программа сохранена: с заменами упражнений, подходами и отдыхом. Её можно вернуть."
                    else "Можно вернуться к своей программе. Она соберётся заново по твоим данным.",
                    style = T.secondary(13.sp),
                )
                GhostButton("Вернуть мою программу", { confirmRestore = true }, Modifier.fillMaxWidth())
            }
        }
        AimPicker(d, base.liftGoal)
        WeekGrid(d, planGym, planRun)
        if (d.gymDays == null) DoubleDaysToggle(d)
        SessionMinutes(d)
        if (d.lift != LiftFocus.NONE) {
            SectionLabel("Опыт в зале")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Новичок", d.experience == Experience.NOVICE, { d.experience = Experience.NOVICE }, Modifier.weight(1f))
                Chip("Средний", d.experience == Experience.INTERMEDIATE, { d.experience = Experience.INTERMEDIATE }, Modifier.weight(1f))
                Chip("Опытный", d.experience == Experience.ADVANCED, { d.experience = Experience.ADVANCED }, Modifier.weight(1f))
            }
            // The goal in the gym has its own screen: the way to it and the plan's changes are worked out as it is typed.
            FCard(gap = 0.dp) {
                forma.app.ui.components.ListRow(
                    "Цель в зале",
                    base.liftGoal?.let { g -> "${forma.core.model.Catalog.find(g.exerciseId)?.name ?: g.exerciseId} ${forma.core.text.Ru.kg(g.kg)} кг" + if (g.reps > 1) " × ${g.reps}" else "" }
                        ?: "Не задана: например, пожать 100 кг",
                ) { nav.push(Screen.GymGoal) }
            }
        }
        if (d.runsShown) {
            SectionLabel("Бег")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("С ходьбой", d.runLevel == RunLevel.NONE, { d.runLevel = RunLevel.NONE }, Modifier.weight(1f))
                Chip("20–30 мин", d.runLevel == RunLevel.BASE, { d.runLevel = RunLevel.BASE }, Modifier.weight(1f))
                Chip("Регулярно", d.runLevel == RunLevel.TRAINED, { d.runLevel = RunLevel.TRAINED }, Modifier.weight(1f))
            }
        }
        if (d.raceShown) {
            SectionLabel("Недавний забег на время (необязательно)", Modifier.padding(top = 6.dp))
            RaceFields(d, "Время недавнего забега")
        }
        RunGoalPicker(d)
        SectionLabel("О себе")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Мужчина", d.sex == Sex.MALE, { d.sex = Sex.MALE }, Modifier.weight(1f))
            Chip("Женщина", d.sex == Sex.FEMALE, { d.sex = Sex.FEMALE }, Modifier.weight(1f))
        }
        BodyFields(d, onboarding = false)
        // Why the button is grey, right above it: only the fields on the screen are checked.
        d.problem?.let { Text(it, style = T.secondary(13.sp).copy(color = F.c.bad)) }
        PrimaryButton("Сохранить и пересчитать план", {
            if (copiedFrom != null) confirmCopied = true else save()
        }, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = d.anyDay && d.fieldsOk)
    }
    if (confirmCopied && copiedFrom != null) {
        AlertDialog(
            onDismissRequest = { confirmCopied = false },
            containerColor = F.c.surface,
            title = { Text("Сменить программу друга ($copiedFrom) на свою?", style = T.title(20.sp)) },
            text = {
                Text(
                    "Сейчас ты занимаешься по программе друга. После сохранения её не будет. Если цель и дни те же, вернётся твоя программа " +
                        "с её днями и заменами. Если поменял, план соберётся по новым. Прогресс в упражнениях сохранится.",
                    style = T.secondary(14.sp),
                )
            },
            confirmButton = { TextButton({ confirmCopied = false; save() }) { Text("Сохранить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) } },
            dismissButton = { TextButton({ confirmCopied = false }) { Text("Отмена", style = T.body(15.sp)) } },
        )
    }
    if (confirmRestore && copiedFrom != null) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            containerColor = F.c.surface,
            title = { Text("Вернуть мою программу?", style = T.title(20.sp)) },
            text = {
                Text(
                    "Программа друга ($copiedFrom) уйдёт. Вернутся твои цель, дни и тренировки такими, как были до копирования: " +
                        "с заменами упражнений, подходами и отдыхом. Прогресс в упражнениях сохранится.",
                    style = T.secondary(14.sp),
                )
            },
            confirmButton = {
                TextButton({
                    confirmRestore = false
                    scope.launch {
                        repo.restoreOwnProgram()
                        nav.back()
                    }
                }) { Text("Вернуть", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
            },
            dismissButton = { TextButton({ confirmRestore = false }) { Text("Отмена", style = T.body(15.sp)) } },
        )
    }
}

/**
 * The week day by day: the days of the gym and the days of running; a day with both holds both (the gym and a run in
 * one go). Which session goes on which day the app decides by the rules (no hard run on a legs day, the long run at
 * the weekend) — and says why on the plan screen.
 */
@Composable
fun WeekGrid(d: ProfileDraft, planGym: Set<Int>, planRun: Set<Int>) {
    val aim = d.aim
    val gym = d.gymDays ?: planGym
    val run = d.runDays ?: planRun
    // The first change makes the week the person's own: both rows as shown, with the change.
    fun own() {
        if (d.gymDays == null) d.gymDays = gym
        if (d.runDays == null) d.runDays = run
    }
    SectionLabel("Неделя", Modifier.padding(top = 6.dp))
    Text(
        when {
            aim.lifts && aim.runs -> "Отметь, в какие дни зал и в какие бег. Можно отметить оба в один день: тогда зал и бег пойдут подряд."
            aim.lifts -> "Отметь дни зала."
            else -> "Отметь дни бега."
        },
        style = T.secondary(13.sp),
    )
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.foundation.layout.Spacer(Modifier.width(44.dp))
            DAY_SHORT.forEach { Text(it, style = T.secondary(12.sp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        if (aim.lifts) {
            DayRow("Зал", gym, F.c.strength) { day ->
                own()
                d.gymDays = if (day in gym) gym - day else gym + day
            }
        }
        if (aim.runs) {
            DayRow("Бег", run, F.c.endurance) { day ->
                own()
                d.runDays = if (day in run) run - day else run + day
            }
        }
    }
    val g = if (aim.lifts) gym.size else 0
    val r = if (aim.runs) run.size else 0
    val both = if (aim.lifts && aim.runs) (gym intersect run).size else 0
    val rest = 7 - (gym.takeIf { aim.lifts }.orEmpty() + run.takeIf { aim.runs }.orEmpty()).size
    Text(
        listOfNotNull(
            if (aim.lifts) "зал ${Ru.plural(g, "раз", "раза", "раз").let { "$g $it" }}" else null,
            if (aim.runs) "бег ${Ru.plural(r, "раз", "раза", "раз").let { "$r $it" }}" else null,
            if (both > 0) "вместе — ${Ru.plural(both, "день", "дня", "дней").let { "$both $it" }}" else null,
            "отдых — ${Ru.plural(rest, "день", "дня", "дней").let { "$rest $it" }}",
        ).joinToString(" · ").replaceFirstChar { it.uppercase() },
        style = T.secondary(13.sp).copy(color = if (rest == 0) F.c.warn else F.c.text2),
    )
    if (rest == 0) Text("Без дня отдыха прогресс встаёт. Оставь хотя бы один свободный день.", style = T.secondary(13.sp).copy(color = F.c.warn))
    // The plan holds up to 5 lifts and 6 runs a week (Scheduler.mixFixed): a day beyond that stays without a session.
    if (aim.lifts && gym.size > 5 || aim.runs && run.size > 6) {
        Text("В плане максимум 5 дней зала и 6 дней бега в неделю. В лишние отмеченные дни тренировки не будет.", style = T.secondary(13.sp))
    }
    // Until 30 minutes of running are possible every run is run/walk, three a week at most (Scheduler.limitHard).
    if (aim.runs && d.runLevel == RunLevel.NONE && run.size > 3) {
        Text("Пока ты бегаешь с ходьбой, бег будет не чаще 3 раз в неделю. В остальные отмеченные дни бега не будет.", style = T.secondary(13.sp))
    }
}

@Composable
private fun DayRow(label: String, on: Set<Int>, color: androidx.compose.ui.graphics.Color, onToggle: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, style = T.body(14.sp, FontWeight.SemiBold), modifier = Modifier.width(44.dp))
        (1..7).forEach { day ->
            val sel = day in on
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(F.shape.radiusSmall)
            androidx.compose.foundation.layout.Box(
                Modifier.weight(1f).height(48.dp).clip(shape)
                    .background(if (sel) color else F.c.surface)
                    .border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, if (sel) color else F.c.line, shape)
                    .toggleable(value = sel, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = { onToggle(day) })
                    .semantics { contentDescription = "$label, ${DAY_FULL_LOWER[day - 1]}" },
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                if (sel) androidx.compose.material3.Icon(forma.app.ui.theme.Ic.check, null, tint = F.c.onData, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private val DAY_FULL_LOWER = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
