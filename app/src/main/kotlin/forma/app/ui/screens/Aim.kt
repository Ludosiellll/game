package forma.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.components.Chip
import forma.app.ui.components.SectionLabel
import forma.app.ui.theme.T
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.LiftFocus
import forma.core.model.RunFocus

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
    Choice(RunFocus.SPEED, "Скорость", "Интервалы и темповый бег: быстрее на 5–10 км."),
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
