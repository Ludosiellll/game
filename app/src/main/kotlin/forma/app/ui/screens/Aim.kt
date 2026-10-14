package forma.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** One option: a short chip label and a line about what it means. */
data class Choice<T>(val value: T, val label: String, val text: String)

val LIFT_FOCUS = listOf(
    Choice(LiftFocus.STRENGTH, "Сила", "Тяжело и мало повторов: растут рабочие веса в базовых упражнениях."),
    Choice(LiftFocus.POWERBUILDING, "Сила и масса", "Тяжёлая база и объём на мышцы: и веса, и форма."),
    Choice(LiftFocus.MUSCLE, "Мышцы", "Гипертрофия: больше подходов по 6–15 повторов почти до отказа."),
    Choice(LiftFocus.GENERAL, "Тонус", "Умеренно, 8–15 повторов: крепкое тело без гонки за весами."),
    Choice(LiftFocus.NONE, "Без зала", "Только бег."),
)

val RUN_FOCUS = listOf(
    Choice(RunFocus.NONE, "Без бега", "Только зал."),
    Choice(RunFocus.HEALTH, "Для здоровья", "Лёгкий бег для сердца и восстановления, без интервалов."),
    Choice(RunFocus.ENDURANCE, "Выносливость", "Растут длинные пробежки: 10 км, полумарафон и дальше."),
    Choice(RunFocus.SPEED, "Скорость", "Интервалы, темповый бег и отрезки в темпе цели: быстрее на 1,5–10 км."),
)

val EMPHASIS = listOf(
    Choice(Emphasis.GYM, "Зал", "Бег 1–2 раза в неделю, остальное — зал."),
    Choice(Emphasis.BALANCED, "Поровну", "Зал и бег поровну; тяжёлые ноги и тяжёлый бег — в разные дни."),
    Choice(Emphasis.RUN, "Бег", "Бег в приоритете, зал 1–2 раза: сила для ног, спины и экономичного бега."),
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

/** Gym focus, running focus and, when there are both, which comes first. At least one side always stays. */
@Composable
fun AimPicker(d: ProfileDraft) {
    SectionLabel("Зал")
    ChoiceChips(LIFT_FOCUS, d.lift) { v ->
        d.lift = v
        if (v == LiftFocus.NONE && d.run == RunFocus.NONE) d.run = RunFocus.HEALTH
    }
    Text(LIFT_FOCUS.first { it.value == d.lift }.text, style = T.secondary(13.sp))
    SectionLabel("Бег", Modifier.padding(top = 6.dp))
    ChoiceChips(RUN_FOCUS, d.run) { v ->
        d.run = v
        if (v == RunFocus.NONE && d.lift == LiftFocus.NONE) d.lift = LiftFocus.GENERAL
    }
    Text(RUN_FOCUS.first { it.value == d.run }.text, style = T.secondary(13.sp))
    if (d.lift != LiftFocus.NONE && d.run != RunFocus.NONE) {
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
        SectionLabel("Забег-цель (необязательно)", Modifier.padding(top = 6.dp))
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
                d.goalTime.isBlank() -> "Например, ${goalExample(meters)}. По цели строятся отрезки в её темпе и контрольные забеги."
                goal == null -> "Не похоже на время: впиши минуты и секунды, например ${goalExample(meters)}."
                else -> buildString {
                    append("Темп ${Ru.pace(goal.pace)} на км")
                    if (lap != null) append(", " + Ru.nobreak("круг $lap м — за ${lapTenth(Track.lapSec(goal.pace, lap))}"))
                    append(". ")
                    if (goal.middleDistance) {
                        // "Endurance" for 1500 m is built by race pace and intervals: the goal plans the running.
                        if (d.run != RunFocus.SPEED) append("С такой целью бег строится от неё: выносливость на ${goalDistance(meters)} растёт от отрезков и интервалов, а не от длинных пробежек. ")
                        append("Каждая вторая тяжёлая пробежка — отрезки в этом темпе, контрольный забег на эту дистанцию — после первых пробежек и дальше раз в 5 недель.")
                    }
                    else append("Темпы пробежек — по текущей форме; прогноз к цели — в «Прогрессе».")
                }
            },
            style = T.secondary(13.sp),
        )
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
            else "Отрезки — в кругах. На каждом круге пищит, когда пора быть на линии; кнопка «Круг» скажет время круга и в темпе ли ты.",
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
        "Зал и бег за один заход: в 3 дня помещаются 3 тренировки в зале и до 3 пробежек. Тяжёлый бег — в дни верха и первым, лёгкий — после ног.",
        d.doubleDays,
    ) { d.doubleDays = it }
}

/** "800 м", "1,5 км", "10 км". */
fun goalDistance(meters: Int): String = if (meters < 1000) "$meters м" else Ru.one(meters / 1000.0).removeSuffix(",0") + " км"
