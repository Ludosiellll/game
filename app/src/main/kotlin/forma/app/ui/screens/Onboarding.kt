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
import forma.app.ui.theme.titleText
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

/** The answers as plain values, so a half-done questionnaire survives rotation and the like. */
@kotlinx.serialization.Serializable
private data class DraftState(
    val lift: LiftFocus, val run: RunFocus, val emphasis: forma.core.model.Emphasis, val experience: Experience,
    val runLevel: RunLevel, val days: Set<Int>, val minutes: Int, val sex: Sex, val birthYear: String, val weight: String,
    val height: String, val restingHr: String, val raceKm: Double?, val raceTime: String,
    val goalMeters: Int? = null, val goalTime: String = "", val stadium: Boolean = false, val lapMeters: String = "400",
    val doubleDays: Boolean = false, val gymDays: Set<Int>? = null, val runDays: Set<Int>? = null,
    val goalDate: String = "",
    val gear: forma.core.model.Gear = forma.core.model.Gear.GYM, val pullupBar: Boolean = false,
)

/**
 * Profile editor shared by the first launch and the profile screen. [blankBody]: the first launch — birth year and
 * weight start empty (the fields show an example), so nothing is saved that the person did not type.
 */
class ProfileDraft(p: Profile, blankBody: Boolean = false) {
    companion object {
        val Saver: androidx.compose.runtime.saveable.Saver<ProfileDraft, String> = androidx.compose.runtime.saveable.Saver(
            save = { d ->
                forma.app.data.AppJson.encodeToString(
                    DraftState.serializer(),
                    DraftState(
                        d.lift, d.run, d.emphasis, d.experience, d.runLevel, d.days, d.minutes, d.sex, d.birthYear, d.weight,
                        d.height, d.restingHr, d.raceKm, d.raceTime, d.goalMeters, d.goalTime, d.stadium, d.lapMeters, d.doubleDays,
                        d.gymDays, d.runDays, d.goalDate, d.gear, d.pullupBar,
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
                        gymDays = s.gymDays; runDays = s.runDays; goalDate = s.goalDate; gear = s.gear; pullupBar = s.pullupBar
                    }
                }
            },
        )

        /** Race distances a recent result can be typed for, km. */
        val RACES = listOf(0.8 to "800 м", 1.0 to "1 км", 1.5 to "1,5 км", 3.0 to "3 км", 5.0 to "5 км", 10.0 to "10 км", 21.0975 to "21,1 км")
    }

    var lift by mutableStateOf(p.aim.lift)
    var run by mutableStateOf(p.aim.run)
    var emphasis by mutableStateOf(p.aim.emphasis)
    var experience by mutableStateOf(p.experience)
    // The first launch starts from "can't run 20 minutes yet": tapped through, a beginner gets the run/walk start,
    // not the runs of someone who runs already (a runner says so in one tap).
    var runLevel by mutableStateOf(if (blankBody) RunLevel.NONE else p.runLevel)
    var days by mutableStateOf(p.trainingDays)
    var minutes by mutableStateOf(p.sessionMinutes)
    var sex by mutableStateOf(p.sex)
    var birthYear by mutableStateOf(if (blankBody) "" else p.birthYear.toString())
    var weight by mutableStateOf(if (blankBody) "" else forma.core.text.Ru.kg(p.bodyWeightKg))
    var height by mutableStateOf(p.heightCm?.toString() ?: "")
    var restingHr by mutableStateOf(p.restingHr?.toString() ?: "")
    var raceKm by mutableStateOf(p.raceMeters?.let { it / 1000.0 })
    var raceTime by mutableStateOf(p.raceSeconds?.let { forma.core.text.Ru.clock(it.toInt()) } ?: "")
    var goalMeters by mutableStateOf(p.runGoal?.meters ?: p.finish)
    var goalTime by mutableStateOf(p.runGoal?.let { forma.core.text.Ru.clock(it.seconds) } ?: "")
    /** The race's day, when there is one: "15.06" or "15.06.2027". */
    var goalDate by mutableStateOf(p.runGoal?.date?.let { LocalDate.ofEpochDay(it).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")) } ?: "")
    var stadium by mutableStateOf(p.lapMeters != null)
    var lapMeters by mutableStateOf((p.lapMeters ?: 400).toString())
    var doubleDays by mutableStateOf(p.doubleDays)
    /** Where the gym part is done, and a pull-up bar at home. */
    var gear by mutableStateOf(p.gear)
    var pullupBar by mutableStateOf(p.pullupBar)
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

    /** Running is in the aim: its level, the stadium and the race fields are on the screen. */
    val runsShown: Boolean get() = run != RunFocus.NONE

    /** The recent race is asked of those who run already (not of those starting with run/walk). */
    val raceShown: Boolean get() = runsShown && runLevel != RunLevel.NONE

    /** The stadium lap, if running on one and the length makes sense. */
    val lapOrNull: Int? get() = if (stadium) lapMeters.toIntOrNull()?.takeIf { it in 100..1000 } else null

    /** The race goal, if a distance is picked and its time is a believable one (2:10–15:00 a km). */
    val runGoal: RunGoal? get() {
        val m = goalMeters ?: return null
        val sec = parseClock(goalTime)?.roundToInt()?.takeIf { it > 0 } ?: return null
        return RunGoal(m, sec, raceDay?.toEpochDay()).takeIf { it.pace in 130.0..900.0 }
    }

    /** The race's day as typed (the year may be left out: this one, or the next if the day has passed), or null. */
    val raceDay: LocalDate? get() = parseRaceDay(goalDate, LocalDate.now())

    /** A race day typed that is not a date, or one already past (asked only with a time). */
    val goalDateBad: Boolean get() = racing && goalMeters != null && goalTime.isNotBlank() && goalDate.isNotBlank() && raceDay == null

    /**
     * A distance from 3 km picked without a time: the goal is to run it without stopping (a beginner's «пробежать 5 км»).
     * A middle distance is a race against the clock: it needs the time.
     */
    val finish: Int? get() = goalMeters?.takeIf { racing && goalTime.isBlank() && it >= 3000 }

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

    /** A race distance picked and a time typed that is not one (only while the race is on the screen). */
    val raceTimeBad: Boolean get() = raceShown && raceKm != null && raceTime.isNotBlank() && race == null

    /** A goal time typed that is not a believable one: saving would quietly drop the goal and its progress. */
    val goalBad: Boolean get() = racing && goalMeters != null && runGoal == null && finish == null

    /** On a stadium with a lap length that is not one (only while running is in the aim). */
    val lapBad: Boolean get() = runsShown && stadium && lapOrNull == null

    val birthYearValue: Int? get() = birthYear.trim().toIntOrNull()?.takeIf { it in 1930..LocalDate.now().year - 10 }
    val weightValue: Double? get() = parseNum(weight)?.takeIf { it in 30.0..250.0 }
    private val heightValue: Int? get() = height.trim().toIntOrNull()?.takeIf { it in 120..230 }
    private val restingHrValue: Int? get() = restingHr.trim().toIntOrNull()?.takeIf { it in 30..110 }

    /** Typed and not believable: 1880 or 08 for a year, 7 kg (a weight of 7 would have quietly become 30). */
    val birthYearBad: Boolean get() = birthYear.isNotBlank() && birthYearValue == null
    val weightBad: Boolean get() = weight.isNotBlank() && weightValue == null
    val heightBad: Boolean get() = height.isNotBlank() && heightValue == null
    val restingHrBad: Boolean get() = restingHr.isNotBlank() && restingHrValue == null

    /** The goal step: the race goal and the stadium as shown. */
    val goalProblem: String? get() = when {
        goalBad && goalTime.isBlank() -> "Для ${goalMeters} м впиши, за какое время хочешь пробежать. Или сними выбор дистанции."
        goalBad -> "Время цели не похоже на время. Пиши минуты и секунды через двоеточие, например 5:30."
        goalDateBad -> "Дата забега: число и месяц через точку, например 15.06. Прошедшая дата не подойдёт. Или оставь поле пустым."
        lapBad -> "Впиши длину круга от 100 до 1000 м или выбери «Улица, парк»."
        else -> null
    }

    /** The recent race, when it is on the screen. */
    val raceProblem: String? get() = if (raceTimeBad) "Время забега не похоже на время. Впиши минуты и секунды через двоеточие или сними выбор дистанции." else null

    /** The week laid out day by day: each side in the aim needs a day. */
    val weekProblem: String? get() {
        val g = gymDays
        val r = runDays
        val a = aim
        return when {
            g == null || r == null -> if (days.isEmpty()) "Отметь хотя бы один день." else null
            a.lifts && g.isEmpty() -> "Отметь хотя бы один день зала или выбери в цели «Только бег»."
            a.runs && r.isEmpty() -> "Отметь хотя бы один день бега или выбери в цели «Только зал»."
            else -> null
        }
    }

    /** About oneself: birth year and weight are needed, height and resting pulse may stay empty. */
    val aboutProblem: String? get() = when {
        birthYear.isBlank() -> "Впиши год рождения."
        birthYearBad -> "Год рождения — четыре цифры, например 2008."
        weight.isBlank() -> if (aim.lifts) "Впиши вес: по нему подберём первые веса в зале." else "Впиши вес: по нему считаем, сколько калорий ты тратишь."
        weightBad -> "Вес — от 30 до 250 кг."
        heightBad -> "Рост — от 120 до 230 см, или оставь поле пустым."
        restingHrBad -> "Пульс в покое — от 30 до 110 ударов, или оставь поле пустым."
        else -> null
    }

    /** What stops saving, of everything on the screen: hidden fields are neither checked nor saved. */
    val problem: String? get() = goalProblem ?: raceProblem ?: weekProblem ?: aboutProblem

    /** Nothing typed that would be lost or misread on saving. */
    val fieldsOk: Boolean get() = problem == null

    fun toProfile(base: Profile): Profile {
        val race = if (raceShown) race else null
        val aim = aim
        return base.copy(
            goal = aim.nearestGoal(), aim = aim, experience = experience, runLevel = runLevel, trainingDays = days.ifEmpty { setOf(1, 3, 5) },
            sessionMinutes = minutes, sex = sex,
            birthYear = birthYearValue ?: base.birthYear,
            bodyWeightKg = weightValue ?: base.bodyWeightKg,
            heightCm = if (height.isBlank()) null else heightValue ?: base.heightCm,
            restingHr = restingHrValue,
            raceMeters = race?.first, raceSeconds = race?.second,
            runGoal = if (racing) runGoal else null, finishMeters = finish, lapMeters = if (runsShown) lapOrNull else null,
            doubleDays = doubleDays && aim.lifts && aim.runs,
            gear = if (aim.lifts) gear else base.gear, pullupBar = pullupBar && gear != forma.core.model.Gear.GYM,
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
    val draft = androidx.compose.runtime.saveable.rememberSaveable(saver = ProfileDraft.Saver) { ProfileDraft(Profile(), blankBody = true) }
    var step by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val last = 4
    val cloud = LocalCloud.current
    // Each step checks only what it shows: a field of another step (or one hidden by a choice) never greys out "Дальше".
    val problem = when (step) {
        0 -> draft.goalProblem
        1 -> draft.raceProblem
        2 -> draft.weekProblem
        3 -> draft.aboutProblem
        else -> draft.problem
    }
    ScrollScreen(gap = 18.dp, bottomInset = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            repeat(last + 1) { i ->
                androidx.compose.foundation.layout.Box(
                    Modifier.weight(1f).height(4.dp).background(if (i <= step) F.c.accent else F.c.line),
                )
            }
        }
        if (step == 0 && cloud?.configured == true) {
            Banner("Уже тренировался с «Формой»? Войди, и твои данные вернутся из облака", F.c.accent, icon = forma.app.ui.theme.Ic.cloud) {
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
        // Why the button is grey, always in sight next to it.
        if (problem != null) Text(problem, style = T.secondary(13.sp).copy(color = F.c.bad))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step > 0) GhostButton("Назад", { step-- }, Modifier.weight(1f), height = 54.dp)
            PrimaryButton(
                if (step < last) "Дальше" else "Начать", {
                    if (step < last) step++ else scope.launch {
                        repo.completeOnboarding(draft.toProfile(Profile()))
                        // A first look round the app before its main screen: what is where and what to press.
                        nav.replaceAll(Screen.Tour(first = true))
                    }
                },
                Modifier.weight(2f), enabled = problem == null,
            )
        }
    }
}

@Composable
private fun StepTitle(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(titleText(title), style = T.screenTitle())
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
    StepTitle("Цель", "Выбери зал, бег или всё вместе и зачем тебе это. Под это приложение соберёт твою неделю.")
    AimPicker(d)
    RunGoalPicker(d)
}

@Composable
private fun ExperienceStep(d: ProfileDraft) {
    StepTitle("Опыт", "От опыта зависят первые веса, сколько подходов делать и как часто нужна лёгкая неделя.")
    if (d.lift != LiftFocus.NONE) {
        SectionLabel("Зал")
        Option("Новичок", "Регулярно тренируюсь меньше полугода или только начинаю", d.experience == Experience.NOVICE) { d.experience = Experience.NOVICE }
        Option("Средний", "От полугода до двух лет", d.experience == Experience.INTERMEDIATE) { d.experience = Experience.INTERMEDIATE }
        Option("Опытный", "Больше двух лет, знаю свои рабочие веса", d.experience == Experience.ADVANCED) { d.experience = Experience.ADVANCED }
        GearPicker(d)
    }
    if (d.run == RunFocus.NONE) return
    SectionLabel("Бег", Modifier.padding(top = 6.dp))
    Option("Пока не пробегу 20 минут", "Начнём с бега вперемешку с ходьбой", d.runLevel == RunLevel.NONE) { d.runLevel = RunLevel.NONE }
    Option("Пробегу 20–30 минут", "Бегаю спокойно, быстрых отрезков ещё не делал", d.runLevel == RunLevel.BASE) { d.runLevel = RunLevel.BASE }
    Option("Бегаю регулярно", "Делаю быстрые отрезки и длинные пробежки", d.runLevel == RunLevel.TRAINED) { d.runLevel = RunLevel.TRAINED }
    if (d.raceShown) {
        SectionLabel("Лучшее время за последние 2 месяца (необязательно)", Modifier.padding(top = 6.dp))
        RaceFields(d, "Время")
    }
}

/** The recent race: its distance (800 m to the half marathon) and time. */
@Composable
fun RaceFields(d: ProfileDraft, label: String) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProfileDraft.RACES.forEach { (km, name) ->
            Chip(name, d.raceKm == km, { d.raceKm = if (d.raceKm == km) null else km })
        }
    }
    if (d.raceKm != null) {
        NumberField(d.raceTime, { d.raceTime = it }, label, "мм:сс", keyboard = KeyboardType.Text, placeholder = raceExample(d.raceKm!!))
        RaceTimeHint(d)
    }
}

/** A believable time for the example in the empty field. */
private fun raceExample(km: Double): String = when {
    km <= 0.8 -> "2:40"
    km <= 1.0 -> "3:30"
    km <= 1.5 -> "5:40"
    km <= 3.0 -> "12:30"
    km <= 5.0 -> "25:30"
    km <= 10.0 -> "52:00"
    else -> "1:55:00"
}

/** Under the race time: what a typo there would do. */
@Composable
fun RaceTimeHint(d: ProfileDraft) {
    if (d.raceTimeBad) Text("Не похоже на время забега. Впиши минуты и секунды через двоеточие, например ${raceExample(d.raceKm ?: 5.0)}.", style = T.secondary(13.sp).copy(color = F.c.bad))
}

/** A red line under a field whose value is not believable. */
@Composable
fun BadHint(text: String) {
    Text(text, style = T.secondary(13.sp).copy(color = F.c.bad))
}

@Composable
private fun DaysStep(d: ProfileDraft) {
    StepTitle("Дни", "Отметь дни, когда можешь тренироваться. Приложение само решит, где зал, где бег, а где отдых.")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        DAY_SHORT.forEachIndexed { i, name ->
            val day = i + 1
            Chip(name, day in d.days, {
                d.days = if (day in d.days) d.days - day else d.days + day
                // Other days: the week laid out by hand before is laid out again.
                d.gymDays = null
                d.runDays = null
            }, Modifier.weight(1f), minWidth = 0.dp, kind = forma.app.ui.components.ChipKind.TOGGLE)
        }
    }
    Text("Выбрано дней: ${d.days.size}", style = T.secondary(14.sp))
    if (d.gymDays == null) DoubleDaysToggle(d)
    SessionMinutes(d)
}

/** How long one session may be, with what the choice is about. */
@Composable
fun SessionMinutes(d: ProfileDraft) {
    SectionLabel("Сколько времени на одну тренировку", Modifier.padding(top = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(45, 60, 75, 90).forEach { m -> Chip("$m мин", d.minutes == m, { d.minutes = m }, Modifier.weight(1f)) }
    }
}

/** Birth year, weight, height and resting pulse: examples in the empty fields, a red line under what is not believable. */
@Composable
fun BodyFields(d: ProfileDraft, onboarding: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberField(d.birthYear, { d.birthYear = it.filter(Char::isDigit) }, "Год рождения", modifier = Modifier.weight(1f), keyboard = KeyboardType.Number, placeholder = "2005")
        NumberField(d.weight, { d.weight = it }, "Вес", "кг", Modifier.weight(1f), placeholder = "70")
    }
    if (d.birthYearBad) BadHint("Год рождения — четыре цифры, например 2008.")
    if (d.weightBad) BadHint("Вес — от 30 до 250 кг.")
    NumberField(d.height, { d.height = it.filter(Char::isDigit) }, if (onboarding) "Рост — для калорий и ИМТ (необязательно)" else "Рост", "см", keyboard = KeyboardType.Number, placeholder = "178")
    if (d.heightBad) BadHint("Рост — от 120 до 230 см, или оставь поле пустым.")
    NumberField(
        d.restingHr, { d.restingHr = it.filter(Char::isDigit) },
        if (onboarding) "Пульс утром в покое — для калькулятора пульса (необязательно)" else "Пульс в покое — для калькулятора пульса",
        "уд/мин", keyboard = KeyboardType.Number, placeholder = "60",
    )
    if (d.restingHrBad) BadHint("Пульс в покое — от 30 до 110 ударов, или оставь поле пустым.")
}

@Composable
private fun AboutStep(d: ProfileDraft) {
    StepTitle(
        "О себе",
        if (d.aim.lifts) "По весу подберём первые веса в зале. Год рождения и рост нужны, чтобы считать калории."
        else "Вес, год рождения и рост нужны, чтобы считать, сколько калорий ты тратишь.",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Мужчина", d.sex == Sex.MALE, { d.sex = Sex.MALE }, Modifier.weight(1f))
        Chip("Женщина", d.sex == Sex.FEMALE, { d.sex = Sex.FEMALE }, Modifier.weight(1f))
    }
    BodyFields(d, onboarding = true)
}

@Composable
private fun SummaryStep(d: ProfileDraft) {
    val profile = d.toProfile(Profile())
    val program = remember(profile) { runCatching { ProgramGenerator.generate(profile, LocalDate.now()) }.getOrNull() }
    var editWeek by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(d.gymDays != null) }
    StepTitle(
        "Твоя неделя",
        if (profile.aim.lifts) "Так выглядит твой план. Веса подберём на первых тренировках. Дальше приложение будет менять их само."
        else "Так выглядит твой план. Темп и длину пробежек приложение будет менять само, по тому, как они идут.",
    )
    if (program != null) {
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
        if ((1..7).all { program.week.on(it).isNotEmpty() }) {
            Text("В неделе нет дня отдыха, а без него прогресс встаёт. Оставь хотя бы один свободный день.", style = T.secondary(14.sp).copy(color = F.c.warn))
        }
        program.week.notes.forEach { Text("• $it", style = T.secondary(14.sp)) }
    }
    if (!editWeek) {
        GhostButton("Поменять дни зала и бега", { editWeek = true }, Modifier.fillMaxWidth())
    } else if (program != null) {
        val planGym = remember(program) { program.week.days.filter { day -> day.sessions.any { it.kind == forma.core.plan.SessionKind.LIFT } }.map { it.day }.toSet() }
        val planRun = remember(program) { program.week.days.filter { day -> day.sessions.any { it.isRun } }.map { it.day }.toSet() }
        WeekGrid(d, planGym, planRun)
    }
}

/**
 * A race day typed as "15.06", "15.06.27" or "15.06.2027" (also with "/" or "-"): without a year this one, or the next when
 * the day has passed. Null for anything else, a day before [today] or more than two years ahead.
 */
internal fun parseRaceDay(text: String, today: LocalDate): LocalDate? {
    val parts = text.trim().split('.', '/', '-', ' ').filter { it.isNotBlank() }
    if (parts.size !in 2..3) return null
    val d = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val y = parts.getOrNull(2)?.toIntOrNull()?.let { if (it < 100) 2000 + it else it }
    fun at(year: Int) = runCatching { LocalDate.of(year, m, d) }.getOrNull()
    val day = if (y != null) at(y) else at(today.year)?.let { if (it.isBefore(today)) at(today.year + 1) else it }
    return day?.takeIf { !it.isBefore(today) && it.isBefore(today.plusYears(2)) }
}


/**
 * Where the gym part is done: a gym, at home with dumbbells and a bench, or with the body alone (and a pull-up bar at
 * home). The plan is made of what is there.
 */
@Composable
fun GearPicker(d: ProfileDraft, goal: forma.core.model.LiftGoal? = null) {
    SectionLabel("Где занимаешься", Modifier.padding(top = 6.dp))
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        forma.core.model.Gear.entries.forEach { g -> Chip(g.ru, d.gear == g, { d.gear = g }) }
    }
    if (d.gear != forma.core.model.Gear.GYM) {
        Chip(if (d.pullupBar) "Есть турник ✓" else "Есть турник", d.pullupBar, { d.pullupBar = !d.pullupBar })
        Text(
            "План соберу из того, что есть дома: гантели, скамья, свой вес" + (if (d.pullupBar) ", турник" else "") +
                ". Что нечем заменить, уберу и скажу об этом.",
            style = T.secondary(13.sp),
        )
        // The gym goal's lift that cannot be done at home: said before saving, with what to do about it.
        goal?.let { forma.core.model.Catalog.find(it.exerciseId) }?.takeIf { !d.gear.fits(it, d.pullupBar) }?.let { ex ->
            Text(
                "Твоя цель в зале — «${ex.name}», а дома его не сделать. Цель можно оставить и делать это упражнение в зале " +
                    "или поменять цель на то, что есть дома.",
                style = T.body(14.sp).copy(color = forma.app.ui.theme.F.c.warn),
            )
        }
    }
}
