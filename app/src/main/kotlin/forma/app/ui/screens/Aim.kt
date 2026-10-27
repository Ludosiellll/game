package forma.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.components.Chip
import forma.app.ui.components.SectionLabel
import forma.app.ui.theme.T
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.LiftFocus
import forma.core.model.RunFocus
import forma.core.run.Track
import forma.core.text.Ru
import forma.app.ui.theme.F

/** One option: a short chip label and a line about what it means. */
data class Choice<T>(val value: T, val label: String, val text: String)

val LIFT_FOCUS = listOf(
    Choice(LiftFocus.STRENGTH, "Сила", "Тяжёлые веса и мало повторов. Растут веса в главных упражнениях: присед, жим, тяга."),
    Choice(LiftFocus.POWERBUILDING, "Сила и масса", "Тяжёлые главные упражнения и подходы на рост мышц. Растут и веса, и мышцы."),
    Choice(LiftFocus.MUSCLE, "Мышцы", "Больше подходов по 6–15 повторов, почти до отказа. Так быстрее растут мышцы."),
    Choice(LiftFocus.GENERAL, "Тонус", "Средние веса, 8–15 повторов. Крепкое тело без гонки за весами."),
    Choice(LiftFocus.NONE, "Без зала", "Только бег."),
)

val RUN_FOCUS = listOf(
    Choice(RunFocus.NONE, "Без бега", "Только зал."),
    Choice(RunFocus.HEALTH, "Для здоровья", "Спокойный бег для сердца и восстановления. Без быстрых отрезков."),
    Choice(RunFocus.ENDURANCE, "Выносливость", "Учишься бегать долго: 10 км, полумарафон и дальше."),
    Choice(RunFocus.SPEED, "Скорость", "Быстрые отрезки, темповый бег и бег в темпе цели. Цель — бегать быстрее на 1,5–10 км."),
)

val EMPHASIS = listOf(
    Choice(Emphasis.GYM, "Зал", "Бег 1–2 раза в неделю, остальное — зал."),
    Choice(Emphasis.BALANCED, "Поровну", "Зал и бег поровну. Тяжёлые ноги и тяжёлый бег ставим в разные дни."),
    Choice(Emphasis.RUN, "Бег", "Главное — бег. Зал 1–2 раза в неделю: сильные ноги и спина, бегать легче."),
)

/** "Мышцы + выносливость · поровну", "Сила", "Бег · скорость". */
fun aimTitle(aim: Aim): String {
    val a = aim.normalized()
    val lift = LIFT_FOCUS.first { it.value == a.lift }.label
    val run = when (a.run) {
        RunFocus.NONE -> null
        RunFocus.HEALTH -> "бег для здоровья"
        RunFocus.ENDURANCE -> "выносливость"
        RunFocus.SPEED -> "скорость"
    }
    return when {
        !a.lifts -> "Бег · $run"
        run == null -> lift
        else -> "$lift + $run · " + when (a.emphasis) {
            Emphasis.GYM -> "упор на зал"
            Emphasis.BALANCED -> "поровну"
            Emphasis.RUN -> "упор на бег"
        }
    }
}

/** What the week holds: the gym and running, the gym alone, running alone. */
enum class Sides(val label: String, val text: String) {
    BOTH("Зал и бег", "В неделе и зал, и бег. Приложение само решит, что в какой день."),
    GYM("Только зал", "В неделе только тренировки в зале, без бега."),
    RUN("Только бег", "В неделе только пробежки, без зала."),
}

/** The sides an aim trains. */
fun sidesOf(aim: Aim): Sides = when {
    aim.lifts && aim.runs -> Sides.BOTH
    aim.lifts -> Sides.GYM
    else -> Sides.RUN
}

/**
 * What the week holds first — the gym and running, the gym alone or running alone, said as such (a "Без бега" chip among
 * the running aims was not seen as the way to drop running) — then the focus of each side kept, and, with both, which
 * comes first. A side dropped and taken back gets its focus of before.
 */
@Composable
fun AimPicker(d: ProfileDraft, liftGoal: forma.core.model.LiftGoal? = null) {
    var keptLift by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(if (d.lift == LiftFocus.NONE) LiftFocus.POWERBUILDING else d.lift) }
    var keptRun by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(if (d.run == RunFocus.NONE) RunFocus.HEALTH else d.run) }
    val sides = sidesOf(Aim(d.lift, d.run, d.emphasis).normalized())
    SectionLabel("Что будешь делать")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Sides.entries.forEach { s ->
            Chip(s.label, sides == s, {
                if (d.lift != LiftFocus.NONE) keptLift = d.lift
                if (d.run != RunFocus.NONE) keptRun = d.run
                d.lift = if (s == Sides.RUN) LiftFocus.NONE else keptLift
                d.run = if (s == Sides.GYM) RunFocus.NONE else keptRun
            }, Modifier.weight(1f), minWidth = 0.dp)
        }
    }
    Text(sides.text, style = T.secondary(13.sp))
    // A side dropped while it has a goal: what that costs, said before saving.
    val risk = forma.core.plan.GoalRisk.week(
        d.runGoal.takeIf { sides == Sides.GYM }, liftGoal.takeIf { sides == Sides.RUN },
        liftGoal?.let { forma.core.model.Catalog.find(it.exerciseId)?.name }, runs = if (sides == Sides.GYM) 0 else 2, lifts = if (sides == Sides.RUN) 0 else 2,
    )
    risk?.let { Text(it, style = T.body(14.sp).copy(color = F.c.warn)) }
    if (sides != Sides.RUN) {
        SectionLabel("Зал — зачем", Modifier.padding(top = 6.dp))
        ChoiceChips(LIFT_FOCUS.filter { it.value != LiftFocus.NONE }, d.lift) { v -> d.lift = v }
        Text(LIFT_FOCUS.first { it.value == d.lift }.text, style = T.secondary(13.sp))
    }
    if (sides != Sides.GYM) {
        SectionLabel("Бег — зачем", Modifier.padding(top = 6.dp))
        ChoiceChips(RUN_FOCUS.filter { it.value != RunFocus.NONE }, d.run) { v -> d.run = v }
        Text(RUN_FOCUS.first { it.value == d.run }.text, style = T.secondary(13.sp))
    }
    if (sides == Sides.BOTH) {
        SectionLabel("Что важнее", Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EMPHASIS.forEach { c -> Chip(c.label, d.emphasis == c.value, { d.emphasis = c.value }, Modifier.weight(1f)) }
        }
        Text(EMPHASIS.first { it.value == d.emphasis }.text, style = T.secondary(13.sp))
    }
}

@Composable
private fun <T> ChoiceChips(items: List<Choice<T>>, selected: T, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { c -> Chip(c.label, c.value == selected, { onPick(c.value) }) }
    }
}

/** Race distances a goal can be set for. */
private val GOAL_DISTANCES = listOf(800 to "800 м", 1000 to "1 км", 1500 to "1,5 км", 3000 to "3 км", 5000 to "5 км", 10000 to "10 км")

/** A believable goal time to show as the example. */
private fun goalExample(meters: Int): String = when (meters) {
    800 -> "2:40"
    1000 -> "3:45"
    1500 -> "5:35"
    3000 -> "12:00"
    5000 -> "22:00"
    else -> "45:00"
}

/**
 * The race to train for (optional) and where the running happens: on a stadium the sessions are in laps of its length,
 * with a beep on every lap and the lap button.
 */
@Composable
fun RunGoalPicker(d: ProfileDraft) {
    if (d.run == RunFocus.NONE) return
    // Racing needs hard runs: a goal is for the speed and endurance focus (running for health has only easy runs).
    if (d.racing) {
        SectionLabel("Дистанция, к которой идёшь (необязательно)", Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GOAL_DISTANCES.forEach { (m, label) -> Chip(label, d.goalMeters == m, { d.goalMeters = if (d.goalMeters == m) null else m }) }
        }
    }
    val meters = d.goalMeters
    if (meters != null && d.racing) {
        NumberField(d.goalTime, { d.goalTime = it }, "За какое время", "мм:сс", keyboard = KeyboardType.Text, placeholder = goalExample(meters))
        val goal = d.runGoal
        val lap = d.lapOrNull
        Text(
            when {
                d.goalTime.isBlank() && meters >= 3000 -> "Не знаешь время — оставь пустым: цель будет пробежать ${goalDistance(meters)} без остановки, план подведёт к этому. " +
                    "Знаешь — впиши, например ${goalExample(meters)}: под время будут отрезки в его темпе и контрольные забеги."
                d.goalTime.isBlank() -> "Например, ${goalExample(meters)}. Под цель будут отрезки в её темпе и контрольные забеги на время."
                goal == null -> "Не похоже на время. Впиши минуты и секунды, например ${goalExample(meters)}."
                else -> buildString {
                    append("Темп ${Ru.pace(goal.pace)} на км")
                    if (lap != null) append(", " + Ru.nobreak("круг $lap м — за ${lapTenth(Track.lapSec(goal.pace, lap))}"))
                    append(". ")
                    if (goal.middleDistance) {
                        // "Endurance" for 1500 m is built by race pace and intervals: the goal plans the running.
                        if (d.run != RunFocus.SPEED) append("Весь бег строится под эту цель. Выносливость на ${goalDistance(meters)} растёт от отрезков и интервалов. Длинные пробежки для неё не главное. ")
                        append("Каждая вторая тяжёлая пробежка — отрезки в этом темпе. Контрольный забег на эту дистанцию будет после первых пробежек, потом раз в 5 недель.")
                    }
                    else append("Темпы пробежек считаем по твоей форме сейчас. Насколько ты близко к цели, видно в «Прогрессе».")
                }
            },
            style = T.secondary(13.sp),
        )
        // The date is a race's: with a time (the taper before it, the race on the day).
        if (d.goalTime.isNotBlank()) {
            NumberField(d.goalDate, { d.goalDate = it }, "Дата забега (если есть)", "дд.мм", keyboard = KeyboardType.Text, placeholder = "15.06")
            val day = d.raceDay
            Text(
                when {
                    d.goalDate.isBlank() -> "Знаешь дату забега — впиши её. Последние две недели перед ним будут подводкой: тренировки легче, чтобы выйти на старт свежим. В сам день в плане будет забег."
                    day == null -> "Не похоже на дату или она уже прошла. Пиши число и месяц через точку, например 15.06."
                    else -> "Забег ${Ru.date(day)}. За две недели до него начнётся подводка: тренировки станут легче. За 3–5 дней будет короткая пробежка в темпе цели. Накануне — только разминка."
                },
                style = T.secondary(13.sp).let { if (d.goalDate.isNotBlank() && day == null) it.copy(color = F.c.warn) else it },
            )
        }
    }
    SectionLabel("Где бегаешь", Modifier.padding(top = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Улица, парк", !d.stadium, { d.stadium = false }, Modifier.weight(1f))
        Chip("Стадион", d.stadium, { d.stadium = true }, Modifier.weight(1f))
    }
    if (d.stadium) {
        NumberField(d.lapMeters, { d.lapMeters = it.filter(Char::isDigit) }, "Длина круга", "м", keyboard = KeyboardType.Number, placeholder = "400")
        Text(
            if (d.lapOrNull == null) "Впиши длину круга от 100 до 1000 м (обычный стадион — 400)."
            else "Отрезки будут в кругах. Телефон пищит, когда пора быть на линии. Кнопка «Круг» скажет время круга и успеваешь ли ты в темп.",
            style = T.secondary(13.sp),
        )
    }
}

/** The gym and a run on the same day (the run right after the gym), when the plan has both. */
@Composable
fun DoubleDaysToggle(d: ProfileDraft) {
    val aim = d.aim
    if (!aim.lifts || !aim.runs) return
    Toggle(
        "Зал и бег в один день",
        "Зал и бег за один приход. Так в 3 дня влезут 3 тренировки в зале и до 3 пробежек. Тяжёлый бег — в дни верха тела, и его делаешь первым. Лёгкий бег — после ног.",
        d.doubleDays,
    ) { d.doubleDays = it }
}

/** "800 м", "1,5 км", "10 км". */
fun goalDistance(meters: Int): String = if (meters < 1000) "$meters м" else Ru.one(meters / 1000.0).removeSuffix(",0") + " км"
