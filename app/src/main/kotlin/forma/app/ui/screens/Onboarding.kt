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
import forma.core.model.Aim
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.model.Sex
import forma.core.plan.ProgramGenerator
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** Profile editor shared by the first launch and the profile screen. */
/** The answers as plain values, so a half-done questionnaire survives rotation and the like. */
@kotlinx.serialization.Serializable
private data class DraftState(
    val lift: LiftFocus, val run: RunFocus, val emphasis: forma.core.model.Emphasis, val experience: Experience,
    val runLevel: RunLevel, val days: Set<Int>, val minutes: Int, val sex: Sex, val birthYear: String, val weight: String,
    val height: String, val restingHr: String, val raceKm: Double?, val raceTime: String,
    val goalMeters: Int? = null, val goalTime: String = "", val stadium: Boolean = false, val lapMeters: String = "400",
    val doubleDays: Boolean = false, val gymDays: Set<Int>? = null, val runDays: Set<Int>? = null,
)

class ProfileDraft(p: Profile) {
    companion object {
        val Saver: androidx.compose.runtime.saveable.Saver<ProfileDraft, String> = androidx.compose.runtime.saveable.Saver(
            save = { d ->
                forma.app.data.AppJson.encodeToString(
                    DraftState.serializer(),
                    DraftState(
                        d.lift, d.run, d.emphasis, d.experience, d.runLevel, d.days, d.minutes, d.sex, d.birthYear, d.weight,
                        d.height, d.restingHr, d.raceKm, d.raceTime, d.goalMeters, d.goalTime, d.stadium, d.lapMeters, d.doubleDays,
                        d.gymDays, d.runDays,
                    ),
                )
            },
            restore = { text ->
                runCatching { forma.app.data.AppJson.decodeFromString(DraftState.serializer(), text) }.getOrNull()?.let { s ->
                    ProfileDraft(Profile()).apply {
                        lift = s.lift; run = s.run; emphasis = s.emphasis; experience = s.experience; runLevel = s.runLevel
                        days = s.days; minutes = s.minutes; sex = s.sex; birthYear = s.birthYear; weight = s.weight
                        height = s.height; restingHr = s.restingHr; raceKm = s.raceKm; raceTime = s.raceTime
                        goalMeters = s.goalMeters; goalTime = s.goalTime; stadium = s.stadium; lapMeters = s.lapMeters; doubleDays = s.doubleDays
                        gymDays = s.gymDays; runDays = s.runDays
                    }
                }
            },
        )
    }

    var lift by mutableStateOf(p.aim.lift)
    var run by mutableStateOf(p.aim.run)
    var emphasis by mutableStateOf(p.aim.emphasis)
    var experience by mutableStateOf(p.experience)
    var runLevel by mutableStateOf(p.runLevel)
    var days by mutableStateOf(p.trainingDays)
    var minutes by mutableStateOf(p.sessionMinutes)
    var sex by mutableStateOf(p.sex)
    var birthYear by mutableStateOf(p.birthYear.toString())
    var weight by mutableStateOf(forma.core.text.Ru.kg(p.bodyWeightKg))
    var height by mutableStateOf(p.heightCm?.toString() ?: "")
    var restingHr by mutableStateOf(p.restingHr?.toString() ?: "")
    var raceKm by mutableStateOf(p.raceMeters?.let { it / 1000.0 })
    var raceTime by mutableStateOf(p.raceSeconds?.let { forma.core.text.Ru.clock(it.toInt()) } ?: "")
    var goalMeters by mutableStateOf(p.runGoal?.meters)
    var goalTime by mutableStateOf(p.runGoal?.let { forma.core.text.Ru.clock(it.seconds) } ?: "")
    var stadium by mutableStateOf(p.lapMeters != null)
    var lapMeters by mutableStateOf((p.lapMeters ?: 400).toString())
    var doubleDays by mutableStateOf(p.doubleDays)
    /** The week day by day (the profile screen): the gym and running days; null — laid out by the app over [days]. */
    var gymDays by mutableStateOf(p.gymDays)
    var runDays by mutableStateOf(p.runDays)

    /** Some day is chosen for what the aim trains (a row the aim hides does not count). */
    val anyDay: Boolean
        get() {
            val g = gymDays
            val r = runDays
            if (g == null && r == null) return days.isNotEmpty()
            val a = aim
            return (a.lifts && g.orEmpty().isNotEmpty()) || (a.runs && r.orEmpty().isNotEmpty())
        }

    val aim: Aim get() = Aim(lift, run, emphasis).normalized()

    /** A race goal needs hard runs: the speed or endurance focus. */
    val racing: Boolean get() = run == RunFocus.SPEED || run == RunFocus.ENDURANCE

    /** The stadium lap, if running on one and the length makes sense. */
    val lapOrNull: Int? get() = if (stadium) lapMeters.toIntOrNull()?.takeIf { it in 100..1000 } else null

    /** The race goal, if a distance is picked and its time is a believable one (2:10–15:00 a km). */
    val runGoal: RunGoal? get() {
        val m = goalMeters ?: return null
        val sec = parseClock(goalTime)?.roundToInt()?.takeIf { it > 0 } ?: return null
        return RunGoal(m, sec).takeIf { it.pace in 130.0..900.0 }
    }

    /**
     * The recent race, if its time is a believable one: minutes and seconds ("25:30", not "2530" — 42 hours), at a pace
     * someone runs (2:10–15:00 a km). A typo would give paces of minus several hours and a session that ends itself.
     */
    val race: Pair<Double, Double>? get() {
        val km = raceKm ?: return null
        if (':' !in raceTime) return null
        val sec = parseClock(raceTime)?.takeIf { it > 0 } ?: return null
        return (km * 1000 to sec).takeIf { sec / km in forma.core.run.RunPlan.PLAUSIBLE_PACE }
    }

    /** A race distance picked and a time typed that is not one. */
    val raceTimeBad: Boolean get() = raceKm != null && raceTime.isNotBlank() && race == null

    /** A goal distance picked without a believable time: saving would quietly drop the goal and its progress. */
    val goalBad: Boolean get() = racing && goalMeters != null && runGoal == null

    /** On a stadium with a lap length that is not one. */
    val lapBad: Boolean get() = stadium && lapOrNull == null

    /** Nothing typed that would be lost or misread on saving. */
    val fieldsOk: Boolean get() = !raceTimeBad && !goalBad && !lapBad

    fun toProfile(base: Profile): Profile {
        val race = race
        val aim = aim
        return base.copy(
            goal = aim.nearestGoal(), aim = aim, experience = experience, runLevel = runLevel, trainingDays = days.ifEmpty { setOf(1, 3, 5) },
            sessionMinutes = minutes, sex = sex,
            birthYear = birthYear.toIntOrNull()?.coerceIn(1930, LocalDate.now().year - 10) ?: base.birthYear,
            bodyWeightKg = parseNum(weight)?.coerceIn(30.0, 250.0) ?: base.bodyWeightKg,
            heightCm = if (height.isBlank()) null else height.toIntOrNull()?.takeIf { it in 120..230 } ?: base.heightCm,
            restingHr = restingHr.toIntOrNull()?.takeIf { it in 30..110 },
            raceMeters = race?.first, raceSeconds = race?.second,
            runGoal = if (racing) runGoal else null, lapMeters = lapOrNull, doubleDays = doubleDays && aim.lifts && aim.runs,
        ).let { p ->
            val gym = gymDays
            val run = runDays
            if (gym == null || run == null) p
            else {
                // Only what the aim trains: a week without running keeps no running days, and the other way round.
                val g = if (aim.lifts) gym else emptySet()
                val r = if (aim.runs) run else emptySet()
                p.copy(gymDays = g, runDays = r, trainingDays = (g + r).ifEmpty { setOf(1, 3, 5) }, doubleDays = (g intersect r).isNotEmpty())
            }
        }
    }
}

@Composable
fun OnboardingScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val draft = androidx.compose.runtime.saveable.rememberSaveable(saver = ProfileDraft.Saver) { ProfileDraft(Profile()) }
    var step by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
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
                Modifier.weight(2f), enabled = draft.fieldsOk && (step != 2 || draft.days.isNotEmpty()),
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
    StepTitle("Цель", "Выбери, зачем тебе зал и зачем бег — неделя соберётся под это сочетание: сколько чего и в какие дни.")
    AimPicker(d)
    RunGoalPicker(d)
}

@Composable
private fun ExperienceStep(d: ProfileDraft) {
    StepTitle("Опыт", "От этого зависят стартовые веса, объём и как часто нужна разгрузка.")
    if (d.lift != LiftFocus.NONE) {
        SectionLabel("Зал")
        Option("Новичок", "Меньше полугода регулярных тренировок", d.experience == Experience.NOVICE) { d.experience = Experience.NOVICE }
        Option("Средний", "От полугода до двух лет", d.experience == Experience.INTERMEDIATE) { d.experience = Experience.INTERMEDIATE }
        Option("Опытный", "Больше двух лет, знаю свои рабочие веса", d.experience == Experience.ADVANCED) { d.experience = Experience.ADVANCED }
    }
    if (d.run == RunFocus.NONE) return
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
        if (d.raceKm != null) {
            NumberField(d.raceTime, { d.raceTime = it }, "Время", "мм:сс", keyboard = KeyboardType.Text, placeholder = "25:30")
            RaceTimeHint(d)
        }
    }
}

/** Under the race time: what a typo there would do. */
@Composable
fun RaceTimeHint(d: ProfileDraft) {
    if (d.raceTimeBad) Text("Не похоже на время забега: впиши минуты и секунды через двоеточие, например 25:30.", style = T.secondary(13.sp).copy(color = F.c.bad))
}

@Composable
private fun DaysStep(d: ProfileDraft) {
    StepTitle("Дни", "Отметь дни, когда можешь тренироваться. Приложение само решит, где зал, где бег, а где отдых.")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        DAY_SHORT.forEachIndexed { i, name ->
            val day = i + 1
            Chip(name, day in d.days, { d.days = if (day in d.days) d.days - day else d.days + day }, Modifier.weight(1f), minWidth = 0.dp, kind = forma.app.ui.components.ChipKind.TOGGLE)
        }
    }
    Text("Выбрано дней: ${d.days.size}", style = T.secondary(14.sp))
    DoubleDaysToggle(d)
    SectionLabel("Сколько времени на одну тренировку", Modifier.padding(top = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(45, 60, 75, 90).forEach { m -> Chip("$m мин", d.minutes == m, { d.minutes = m }, Modifier.weight(1f)) }
    }
}

@Composable
private fun AboutStep(d: ProfileDraft) {
    StepTitle("О себе", "Для стартовых весов, пульсовых зон, расхода энергии и относительной силы.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Мужчина", d.sex == Sex.MALE, { d.sex = Sex.MALE }, Modifier.weight(1f))
        Chip("Женщина", d.sex == Sex.FEMALE, { d.sex = Sex.FEMALE }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberField(d.birthYear, { d.birthYear = it }, "Год рождения", modifier = Modifier.weight(1f), keyboard = KeyboardType.Number)
        NumberField(d.weight, { d.weight = it }, "Вес", "кг", Modifier.weight(1f))
    }
    NumberField(d.height, { d.height = it }, "Рост (для расхода энергии и ИМТ)", "см", keyboard = KeyboardType.Number, placeholder = "178")
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
