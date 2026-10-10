package forma.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.components.NumText
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.core.plan.PlannedSession
import forma.core.plan.Program
import forma.core.plan.SessionKind
import java.time.DayOfWeek
import java.time.LocalDate

/** Scrolling screen body with the status bar inset. */
@Composable
fun ScrollScreen(gap: Dp = 16.dp, bottomInset: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var m = Modifier.fillMaxSize().statusBarsPadding()
    if (bottomInset) m = m.navigationBarsPadding()
    Column(
        m.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        content()
        Spacer(Modifier.height(12.dp))
    }
}

/** Numeric text input in the look's style. */
@Composable
fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    suffix: String = "",
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Decimal,
    placeholder: String = "",
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = T.secondary(13.sp))
        val shape = RoundedCornerShape(F.shape.radiusSmall + 2.dp)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(F.c.surface)
                .border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, F.c.line, shape).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) NumText(placeholder, style = T.number(20.sp).copy(color = F.c.text3))
                BasicTextField(
                    value, { onChange(it.take(8)) }, singleLine = true,
                    textStyle = T.number(20.sp), cursorBrush = SolidColor(F.c.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth(),
                )
            }
            if (suffix.isNotEmpty()) Text(suffix, style = T.secondary(14.sp))
        }
    }
}

fun parseNum(s: String): Double? = s.replace(',', '.').trim().toDoubleOrNull()

/** "25:30" or "1:45:00" → seconds. */
fun parseClock(s: String): Double? {
    val parts = s.trim().split(':').map { it.toIntOrNull() ?: return null }
    return when (parts.size) {
        1 -> parts[0] * 60.0
        2 -> parts[0] * 60.0 + parts[1]
        3 -> parts[0] * 3600.0 + parts[1] * 60 + parts[2]
        else -> null
    }
}

private val DAY_FULL = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")
val DAY_SHORT = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
private val MONTH_GEN = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
val MONTH_SHORT = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

fun dateLine(d: LocalDate) = "${DAY_FULL[d.dayOfWeek.value - 1]}, ${d.dayOfMonth} ${MONTH_GEN[d.monthValue - 1]}"
fun shortDate(d: LocalDate) = "${d.dayOfMonth} ${MONTH_GEN[d.monthValue - 1]}"
fun dayShort(d: DayOfWeek) = DAY_SHORT[d.value - 1]

/** Human title of a planned session. */
fun sessionTitle(s: PlannedSession, program: Program?): String = when (s.kind) {
    SessionKind.LIFT -> program?.templates?.firstOrNull { it.id == s.templateId }?.title ?: "Силовая"
    SessionKind.RUN_EASY -> "Лёгкий бег"
    SessionKind.RUN_QUALITY -> "Интервалы или темп"
    SessionKind.RUN_LONG -> "Длинная пробежка"
}

fun kindLabel(s: PlannedSession): String = when (s.kind) {
    SessionKind.LIFT -> "Силовая"
    SessionKind.RUN_EASY -> "Бег"
    SessionKind.RUN_QUALITY -> "Качественный бег"
    SessionKind.RUN_LONG -> "Длинный бег"
}
