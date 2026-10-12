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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
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

/** Text input in the look's style (email and the like). */
@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    placeholder: String = "",
    maxLength: Int = 254,
    enabled: Boolean = true,
    secret: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = T.secondary(13.sp))
        val shape = RoundedCornerShape(F.shape.radiusSmall + 2.dp)
        Box(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(F.c.surface)
                .border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, F.c.line, shape).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = T.body(17.sp).copy(color = F.c.text3))
            BasicTextField(
                value, { onChange(it.take(maxLength)) }, singleLine = true, enabled = enabled,
                textStyle = T.body(17.sp), cursorBrush = SolidColor(F.c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
                visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
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
    SessionKind.RUN_QUALITY -> "Тяжёлый бег"
    SessionKind.RUN_LONG -> "Длинный бег"
}

/**
 * After a session on a day with two (the gym and the stadium): the other one, ready to start — "Готово" becomes the
 * second button. Nothing when the day is done.
 */
@Composable
fun NextToday(onDone: () -> Unit) {
    val repo = forma.app.ui.LocalRepo.current
    val nav = forma.app.ui.LocalNav.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val today = forma.app.ui.rememberToday(repo)
    val monday = androidx.compose.runtime.remember(today) { today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    val week by androidx.compose.runtime.remember(monday) { repo.weekFlow(monday) }.collectAsState(initial = emptyList())
    val program by repo.store.program.collectAsState(initial = null)
    val active by androidx.compose.runtime.remember { repo.db.workouts().observeActive() }.collectAsState(initial = null)
    val day = week.firstOrNull { it.date == today }
    val next = day?.planned?.firstOrNull { !day.isDone(it) }
    // The run is named as it will be (race pace, intervals, a test), not by its kind.
    val runTitle by androidx.compose.runtime.produceState<String?>(null, next) {
        value = next?.takeIf { it.isRun }?.let { repo.runFor(it.kind, today).title }
    }
    var starting by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (next == null || next.isRun && runTitle == null) {
        forma.app.ui.components.PrimaryButton("Готово", onDone, Modifier.fillMaxWidth())
        return
    }
    val title = runTitle ?: sessionTitle(next, program)
    forma.app.ui.components.PrimaryButton("Дальше: $title", {
        if (starting) return@PrimaryButton
        starting = true
        scope.launch {
            try {
                if (next.isRun) {
                    nav.replaceTop(forma.app.ui.Screen.RunSetup(repo.runFor(next.kind, today), next.kind))
                } else {
                    val a = active
                    val id = if (a != null && a.templateId == next.templateId) a.id else repo.startLift(next.templateId!!, today)
                    nav.replaceTop(forma.app.ui.Screen.Workout(id))
                }
            } finally {
                starting = false
            }
        }
    }, Modifier.fillMaxWidth())
    forma.app.ui.components.GhostButton("Готово на сегодня", onDone, Modifier.fillMaxWidth())
}

/**
 * The next day of the plan after [today] and what it holds: the gym session, and each run with how to run it (the lap
 * time or pace to keep) — so after a run, or on a day off, the next one is never a guess.
 */
@Composable
fun NextPlannedCard(today: LocalDate) {
    val repo = forma.app.ui.LocalRepo.current
    val program by repo.store.program.collectAsState(initial = null)
    val settings by repo.store.settings.collectAsState(initial = forma.app.data.Settings())
    val p = program ?: return
    val next = androidx.compose.runtime.remember(p, today, settings) {
        (1..14).map { today.plusDays(it.toLong()) }.firstOrNull { d -> !repo.paused(settings, d) && p.week.on(d.dayOfWeek.value).isNotEmpty() }
    } ?: return
    val sessions = p.week.on(next.dayOfWeek.value)
    val runs by androidx.compose.runtime.produceState<Map<PlannedSession, forma.core.run.RunWorkout>>(emptyMap(), p, next) {
        value = sessions.filter { it.isRun }.associateWith { repo.runFor(it.kind, next) }
    }
    forma.app.ui.components.FCard(gap = 8.dp) {
        forma.app.ui.components.SectionLabel("Дальше по плану")
        Text(
            (if (next == today.plusDays(1)) "Завтра, " + DAY_FULL[next.dayOfWeek.value - 1].lowercase() else dateLine(next)),
            style = forma.app.ui.theme.T.title(20.sp),
        )
        sessions.forEach { s ->
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.Icon(
                    if (s.isRun) forma.app.ui.theme.Ic.run else forma.app.ui.theme.Ic.dumbbell, null,
                    tint = if (s.isRun) forma.app.ui.theme.F.c.endurance else forma.app.ui.theme.F.c.strength,
                    modifier = Modifier.padding(top = 2.dp).size(18.dp),
                )
                androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
                    val w = runs[s]
                    Text(w?.title ?: sessionTitle(s, p), style = forma.app.ui.theme.T.body(15.sp, androidx.compose.ui.text.font.FontWeight.SemiBold))
                    if (w != null) Text(forma.app.ui.RunHow.short(w), style = forma.app.ui.theme.T.secondary(13.sp))
                }
            }
        }
    }
}
