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

/**
 * "25:30" or "1:45:00" → seconds. Null for what is not a time: a part that is not a whole number, a negative one, or
 * minutes or seconds after a colon of 60 and more ("5:75" is a typo, not 6:15).
 */
fun parseClock(s: String): Double? {
    val parts = s.trim().split(':').map { p -> p.trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null }
    if (parts.drop(1).any { it >= 60 }) return null
    return when (parts.size) {
        1 -> parts[0] * 60.0
        2 -> parts[0] * 60.0 + parts[1]
        3 -> parts[0] * 3600.0 + parts[1] * 60 + parts[2]
        else -> null
    }
}

/**
 * A lap time as typed: "83", "83,4", "1:23", "1:23,4" → seconds. Null for anything else — two digits of seconds after a
 * colon, under 60 ("1:5" and "1:75" are typos).
 */
fun parseLapTime(s: String): Double? {
    val parts = s.trim().replace(',', '.').split(':')
    val sec = when (parts.size) {
        1 -> parts[0].toDoubleOrNull()
        2 -> {
            val m = parts[0].toIntOrNull()?.takeIf { it >= 0 }
            val rest = parts[1].takeIf { it.substringBefore('.').length == 2 }?.toDoubleOrNull()?.takeIf { it < 60 }
            if (m == null || rest == null) null else m * 60 + rest
        }
        else -> null
    }
    return sec?.takeIf { it > 0 && it.isFinite() }
}

val DAY_FULL = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")
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

/** The session left open ([open]) is the one to go on with: the same session of the same day. */
internal fun sameLift(open: forma.app.data.WorkoutEntity, templateId: String, today: LocalDate): Boolean =
    open.templateId == templateId && open.epochDay == today.toEpochDay()

/** A session left open ([open]) when another one was asked for: [templateId], lighter or not ([light]). */
private data class LiftAsk(val open: forma.app.data.WorkoutEntity, val templateId: String, val light: Boolean)

/**
 * Starts the gym session [templateId] for [today] and opens it with [go]: from the main screen, a day of the plan and
 * "Дальше" after a run. One left open of another session or from another day is never taken silently (Tuesday's
 * «Верх Б» must not get Thursday's sets): it is asked about first — go on with it, finish it, or delete it and start.
 * The same session of the same day just opens. One start at a time: a double tap does not open two. The question is a
 * dialog of the screen that calls this.
 */
@Composable
fun rememberLiftStart(today: LocalDate, go: (forma.app.ui.Screen) -> Unit): (templateId: String, light: Boolean) -> Unit {
    val repo = forma.app.ui.LocalRepo.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val program by repo.store.program.collectAsState(initial = null)
    var starting by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var asked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<LiftAsk?>(null) }
    fun once(block: suspend () -> Unit) {
        if (starting) return
        starting = true
        scope.launch {
            try {
                block()
            } finally {
                starting = false
            }
        }
    }
    asked?.let { (a, templateId, light) ->
        val title = program?.templates?.firstOrNull { it.id == templateId }?.title?.let { "«$it»" } ?: "новую"
        val since = if (a.epochDay != today.toEpochDay()) "Начата ${shortDate(LocalDate.ofEpochDay(a.epochDay))}. " else ""
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asked = null },
            containerColor = F.c.surface,
            title = { Text("«${a.title}» не закончена", style = T.title(20.sp)) },
            text = {
                Text(
                    since + "Можно продолжить её, закончить или удалить и начать $title. Если удалить, её подходы пропадут.",
                    style = T.secondary(14.sp),
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    androidx.compose.material3.TextButton({ asked = null; go(forma.app.ui.Screen.Workout(a.id)) }) {
                        Text("Продолжить «${a.title}»", style = T.body(15.sp, androidx.compose.ui.text.font.FontWeight.Bold).copy(color = F.c.accent))
                    }
                    // Opens it with "Завершить" asked: its effort, or its deletion when it has no sets.
                    androidx.compose.material3.TextButton({ asked = null; finishOnOpen = a.id; go(forma.app.ui.Screen.Workout(a.id)) }) {
                        Text("Закончить «${a.title}»", style = T.body(15.sp).copy(color = F.c.accent))
                    }
                    androidx.compose.material3.TextButton({
                        asked = null
                        once {
                            forma.app.ui.RestTimer.stopFor(context, a.id)
                            repo.cancelWorkout(a.id)
                            go(forma.app.ui.Screen.Workout(repo.startLift(templateId, today, forceDeload = light)))
                        }
                    }) { Text("Удалить и начать", style = T.body(15.sp).copy(color = F.c.bad)) }
                }
            },
        )
    }
    return { templateId, light ->
        once {
            val a = repo.db.workouts().active()
            when {
                a == null -> go(forma.app.ui.Screen.Workout(repo.startLift(templateId, today, forceDeload = light)))
                sameLift(a, templateId, today) -> go(forma.app.ui.Screen.Workout(a.id))
                else -> asked = LiftAsk(a, templateId, light)
            }
        }
    }
}

/**
 * After a session on a day with two (the gym and the stadium): the other one, ready to start — "Готово" becomes the
 * second button. Nothing when the day is done. The day's "so-so" holds for it too: the lighter gym, the lighter run.
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
    val settings by repo.store.settings.collectAsState(initial = forma.app.data.Settings())
    val light = settings.feelOn(today) == forma.app.data.Feel.LOW
    val day = week.firstOrNull { it.date == today }
    val next = day?.planned?.firstOrNull { !day.isDone(it) }
    // The run is named as it will be (race pace, intervals, a test), not by its kind.
    val runTitle by androidx.compose.runtime.produceState<String?>(null, next, light) {
        value = next?.takeIf { it.isRun }?.let { repo.runFor(it.kind, today, light).title }
    }
    var starting by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // The gym after the stadium: never into a session left open from another day.
    val startLift = rememberLiftStart(today, nav::replaceTop)
    if (next == null || next.isRun && runTitle == null) {
        forma.app.ui.components.PrimaryButton("Готово", onDone, Modifier.fillMaxWidth())
        return
    }
    val title = runTitle ?: sessionTitle(next, program)
    forma.app.ui.components.PrimaryButton("Дальше: $title", {
        if (!next.isRun) {
            startLift(next.templateId!!, light)
            return@PrimaryButton
        }
        if (starting) return@PrimaryButton
        starting = true
        scope.launch {
            try {
                nav.replaceTop(forma.app.ui.Screen.RunSetup(repo.runFor(next.kind, today, light), next.kind))
            } finally {
                starting = false
            }
        }
    }, Modifier.fillMaxWidth())
    forma.app.ui.components.GhostButton("Готово на сегодня", onDone, Modifier.fillMaxWidth())
}

/**
 * The next day of the plan after [today] and what it holds: the gym session, and each run with how to run it (the lap
 * time or pace to keep) — so after a run, or on a day off, the next one is never a guess. A session done ahead is not
 * next.
 */
@Composable
fun NextPlannedCard(today: LocalDate) {
    val repo = forma.app.ui.LocalRepo.current
    val program by repo.store.program.collectAsState(initial = null)
    val settings by repo.store.settings.collectAsState(initial = forma.app.data.Settings())
    // Sessions of this week done ahead (Saturday's «Низ» on Friday) are not next: what is left of the plan is.
    val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    val week by androidx.compose.runtime.remember(monday) { repo.weekFlow(monday) }.collectAsState(initial = emptyList())
    val p = program ?: return
    fun left(d: LocalDate) = p.week.on(d.dayOfWeek.value).filter { s -> week.firstOrNull { it.date == d }?.isDone(s) != true }
    val next = androidx.compose.runtime.remember(p, today, settings, week) {
        (1..14).map { today.plusDays(it.toLong()) }.firstOrNull { d -> !repo.paused(settings, d) && left(d).isNotEmpty() }
    } ?: return
    val sessions = left(next)
    val runs by androidx.compose.runtime.produceState<Map<PlannedSession, forma.core.run.RunWorkout>>(emptyMap(), p, next, sessions) {
        // A due trial only if that day is the one it falls on (not one today still holds).
        val trialOn = repo.trialDay(today)
        value = sessions.filter { it.isRun }.associateWith { repo.runFor(it.kind, next, trial = next == trialOn) }
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
