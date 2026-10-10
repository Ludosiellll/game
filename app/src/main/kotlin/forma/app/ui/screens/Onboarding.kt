package forma.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.LocalCloud
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.components.Banner
import forma.app.ui.components.Chip
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.SectionLabel
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.model.Sex
import forma.core.plan.ProgramGenerator
import kotlinx.coroutines.launch
import java.time.LocalDate

data class GoalInfo(val goal: Goal, val title: String, val text: String)

val GOALS = listOf(
    GoalInfo(Goal.HYBRID, "Сила и выносливость", "Зал и бег поровну: растут и веса, и темп"),
    GoalInfo(Goal.STRENGTH, "Сила", "Больше вес в базовых упражнениях, бег для здоровья"),
    GoalInfo(Goal.MUSCLE, "Мышцы", "Объём и форма, больше подходов"),
    GoalInfo(Goal.ENDURANCE, "Бег и выносливость", "Бег в приоритете, зал для крепких ног и спины"),
    GoalInfo(Goal.FITNESS, "Форма и здоровье", "Умеренно и регулярно, минус лишний вес"),
)

/** Profile editor shared by the first launch and the profile screen. */
class ProfileDraft(p: Profile) {
    var goal by mutableStateOf(p.goal)
    var experience by mutableStateOf(p.experience)
    var runLevel by mutableStateOf(p.runLevel)
    var days by mutableStateOf(p.trainingDays)
    var minutes by mutableStateOf(p.sessionMinutes)
    var sex by mutableStateOf(p.sex)
    var birthYear by mutableStateOf(p.birthYear.toString())
    var weight by mutableStateOf(forma.core.text.Ru.kg(p.bodyWeightKg))
    var restingHr by mutableStateOf(p.restingHr?.toString() ?: "")
    var raceKm by mutableStateOf(p.raceMeters?.let { it / 1000.0 })
    var raceTime by mutableStateOf(p.raceSeconds?.let { forma.core.text.Ru.clock(it.toInt()) } ?: "")

    fun toProfile(base: Profile): Profile {
        val race = raceKm?.let { km -> parseClock(raceTime)?.takeIf { it > 0 }?.let { km * 1000 to it } }
        return base.copy(
            goal = goal, experience = experience, runLevel = runLevel, trainingDays = days.ifEmpty { setOf(1, 3, 5) },
            sessionMinutes = minutes, sex = sex,
            birthYear = birthYear.toIntOrNull()?.coerceIn(1930, LocalDate.now().year - 10) ?: base.birthYear,
            bodyWeightKg = parseNum(weight)?.coerceIn(30.0, 250.0) ?: base.bodyWeightKg,
            restingHr = restingHr.toIntOrNull()?.takeIf { it in 30..110 },
            raceMeters = race?.first, raceSeconds = race?.second,
        )
    }
}

@Composable
fun OnboardingScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val draft = remember { ProfileDraft(Profile()) }
    var step by remember { mutableIntStateOf(0) }
    val last = 4
    val cloud = LocalCloud.current
    ScrollScreen(gap = 18.dp, bottomInset = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            repeat(last + 1) { i ->
                androidx.compose.foundation.layout.Box(
                    Modifier.weight(1f).height(4.dp).background(if (i <= step) F.c.accent else F.c.line),
                )
            }
        }
        if (step == 0 && cloud?.configured == true) {
            Banner("Уже тренировался с «Формой»? Войди и восстанови данные из облака", F.c.accent, icon = forma.app.ui.theme.Ic.cloud) {
                nav.push(Screen.CloudSignIn(restore = true))
            }
        }
        when (step) {
            0 -> GoalStep(draft)
            1 -> ExperienceStep(draft)
            2 -> DaysStep(draft)
            3 -> AboutStep(draft)
            else -> SummaryStep(draft)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step > 0) GhostButton("Назад", { step-- }, Modifier.weight(1f), height = 54.dp)
            PrimaryButton(
                if (step < last) "Дальше" else "Начать", {
                    if (step < last) step++ else scope.launch {
                        repo.completeOnboarding(draft.toProfile(Profile()))
                        nav.replaceAll(Screen.Today)
                    }
                },
                Modifier.weight(2f), enabled = step != 2 || draft.days.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun StepTitle(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = T.screenTitle())
        Text(text, style = T.secondary(15.sp))
    }
}

@Composable
private fun Option(title: String, text: String?, selected: Boolean, onClick: () -> Unit) {
    FCard(onClick = onClick, outline = if (selected) F.c.accent else null, gap = 4.dp) {
        Text(title, style = T.body(17.sp, FontWeight.ExtraBold).copy(color = if (selected) F.c.accent else F.c.text))
        if (text != null) Text(text, style = T.secondary(13.sp))
    }
}

@Composable
private fun GoalStep(d: ProfileDraft) {
    StepTitle("Цель", "Под неё приложение соберёт неделю: сколько зала, сколько бега и в какие дни.")
    GOALS.forEach { g -> Option(g.title, g.text, d.goal == g.goal) { d.goal = g.goal } }
}

@Composable
private fun ExperienceStep(d: ProfileDraft) {
    StepTitle("Опыт", "От этого зависят стартовые веса, объём и как часто нужна разгрузка.")
    SectionLabel("Зал")
    Option("Новичок", "Меньше полугода регулярных тренировок", d.experience == Experience.NOVICE) { d.experience = Experience.NOVICE }
    Option("Средний", "От полугода до двух лет", d.experience == Experience.INTERMEDIATE) { d.experience = Experience.INTERMEDIATE }
    Option("Опытный", "Больше двух лет, знаю свои рабочие веса", d.experience == Experience.ADVANCED) { d.experience = Experience.ADVANCED }
    SectionLabel("Бег", Modifier.padding(top = 6.dp))
    Option("Пока не пробегу 20 минут", "Начнём с бега чередуя с ходьбой", d.runLevel == RunLevel.NONE) { d.runLevel = RunLevel.NONE }
    Option("Пробегу 20–30 минут", "Лёгкий бег есть, интервалов не было", d.runLevel == RunLevel.BASE) { d.runLevel = RunLevel.BASE }
    Option("Бегаю регулярно", "Есть интервалы, длинные пробежки", d.runLevel == RunLevel.TRAINED) { d.runLevel = RunLevel.TRAINED }
    if (d.runLevel != RunLevel.NONE) {
        SectionLabel("Лучший результат за последние 2 месяца (необязательно)", Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3.0 to "3 км", 5.0 to "5 км", 10.0 to "10 км", 21.0975 to "21,1").forEach { (km, label) ->
                Chip(label, d.raceKm == km, { d.raceKm = if (d.raceKm == km) null else km }, Modifier.weight(1f))
            }
        }
        if (d.raceKm != null) NumberField(d.raceTime, { d.raceTime = it }, "Время", "мм:сс", keyboard = KeyboardType.Text, placeholder = "25:30")
    }
}

@Composable
private fun DaysStep(d: ProfileDraft) {
    StepTitle("Дни", "Отметь дни, когда можешь тренироваться. Приложение само решит, где зал, где бег, а где отдых.")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        DAY_SHORT.forEachIndexed { i, name ->
            val day = i + 1
            Chip(name, day in d.days, { d.days = if (day in d.days) d.days - day else d.days + day }, Modifier.weight(1f), minWidth = 0.dp)
        }
    }
    Text("Выбрано дней: ${d.days.size}", style = T.secondary(14.sp))
    SectionLabel("Сколько времени на одну тренировку", Modifier.padding(top = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(45, 60, 75, 90).forEach { m -> Chip("$m мин", d.minutes == m, { d.minutes = m }, Modifier.weight(1f)) }
    }
}

@Composable
private fun AboutStep(d: ProfileDraft) {
    StepTitle("О себе", "Для стартовых весов, пульсовых зон и относительной силы. Хранится только на телефоне.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Мужчина", d.sex == Sex.MALE, { d.sex = Sex.MALE }, Modifier.weight(1f))
        Chip("Женщина", d.sex == Sex.FEMALE, { d.sex = Sex.FEMALE }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberField(d.birthYear, { d.birthYear = it }, "Год рождения", modifier = Modifier.weight(1f), keyboard = KeyboardType.Number)
        NumberField(d.weight, { d.weight = it }, "Вес", "кг", Modifier.weight(1f))
    }
    NumberField(d.restingHr, { d.restingHr = it }, "Пульс в покое утром (необязательно)", "уд/мин", keyboard = KeyboardType.Number, placeholder = "60")
}

@Composable
private fun SummaryStep(d: ProfileDraft) {
    val profile = d.toProfile(Profile())
    val program = remember(profile) { ProgramGenerator.generate(profile, LocalDate.now()) }
    StepTitle("Твоя неделя", "Так выглядит план. Веса подберём на первых тренировках, дальше приложение будет подстраивать их само.")
    FCard(gap = 0.dp) {
        (1..7).forEach { day ->
            val sessions = program.week.on(day)
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(DAY_SHORT[day - 1], style = T.body(15.sp, FontWeight.ExtraBold).copy(color = if (sessions.isEmpty()) F.c.text3 else F.c.text), modifier = Modifier.padding(end = 4.dp))
                Text(
                    if (sessions.isEmpty()) "Отдых" else sessions.joinToString(" + ") { sessionTitle(it, program) },
                    style = T.body(15.sp).copy(color = if (sessions.isEmpty()) F.c.text3 else F.c.text),
                )
            }
        }
    }
    program.week.notes.forEach { Text("• $it", style = T.secondary(14.sp)) }
}
