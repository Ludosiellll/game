package forma.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.AppJson
import forma.app.data.Kind
import forma.app.data.LiftSummary
import forma.app.data.RunEntity
import forma.app.data.RunSummary
import forma.app.data.SetEntity
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.Texts
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.ListRow
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.StatTile
import forma.app.ui.components.TopBar
import forma.app.ui.components.NumText
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.core.model.Catalog
import forma.core.model.LoadKind
import forma.core.text.Ru
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** Which workouts the history shows. */
enum class HistoryFilter(val label: String) { ALL("Все"), GYM("Зал"), RUN("Бег"), TRIALS("Контрольные") }

/** A trial: a test run, or one whose outcome is a trial time. */
fun isTrial(w: forma.app.data.WorkoutEntity): Boolean = w.kind == Kind.RUN && (
    w.templateId == forma.core.run.RunType.TEST.name ||
        w.summaryJson?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() }
            ?.outcomes?.any { it is forma.core.run.RunOutcome.TestTime } == true
    )

/** A gym session with a record of any exercise in it. */
fun hasRecord(w: forma.app.data.WorkoutEntity): Boolean = w.kind == Kind.LIFT &&
    w.summaryJson?.let { runCatching { AppJson.decodeFromString(LiftSummary.serializer(), it) }.getOrNull() }?.reports?.any { it.record } == true

/** "12 октября · 45 мин · нагрузка 342 · рекорд": a line of the history. */
fun historyLine(w: forma.app.data.WorkoutEntity): String = listOfNotNull(
    shortDate(LocalDate.ofEpochDay(w.epochDay)),
    "${(w.durationSec ?: 0) / 60} мин",
    w.load?.takeIf { it > 0 }?.let { "нагрузка ${it.roundToInt()}" },
    if (isTrial(w)) "контрольный" else null,
    if (hasRecord(w)) "рекорд" else null,
).joinToString(" · ")

@Composable
fun HistoryScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val all by remember { repo.db.workouts().observeHistory() }.collectAsState(initial = emptyList())
    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    val list = remember(all, filter) {
        when (filter) {
            HistoryFilter.ALL -> all
            HistoryFilter.GYM -> all.filter { it.kind == Kind.LIFT }
            HistoryFilter.RUN -> all.filter { it.kind == Kind.RUN }
            HistoryFilter.TRIALS -> all.filter(::isTrial)
        }
    }
    ScrollScreen {
        TopBar("История", "${all.size} ${Ru.plural(all.size, "тренировка", "тренировки", "тренировок")}", onBack = { nav.back() })
        if (all.isEmpty()) Empty("Здесь будут все тренировки и пробежки.")
        else androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryFilter.entries.forEach { f -> forma.app.ui.components.Chip(f.label, filter == f, { filter = f }) }
        }
        if (all.isNotEmpty() && list.isEmpty()) Empty(if (filter == HistoryFilter.TRIALS) "Контрольных забегов ещё не было." else "Таких тренировок ещё не было.")
        list.groupBy { LocalDate.ofEpochDay(it.epochDay).withDayOfMonth(1) }.forEach { (month, items) ->
            SectionLabel("${MONTH_SHORT[month.monthValue - 1]} ${month.year}")
            FCard(gap = 0.dp) {
                items.forEachIndexed { i, w ->
                    if (i > 0) Hairline()
                    ListRow(
                        w.title, historyLine(w),
                        icon = if (w.kind == Kind.LIFT) Ic.dumbbell else Ic.run,
                        iconTint = if (w.kind == Kind.LIFT) F.c.strength else F.c.endurance,
                    ) { nav.push(Screen.HistoryItem(w.id)) }
                }
            }
        }
    }
}

@Composable
fun HistoryItemScreen(workoutId: Long) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val w by remember(workoutId) { repo.db.workouts().observe(workoutId) }.collectAsState(initial = null)
    val sets by produceState<List<SetEntity>>(emptyList(), workoutId) { value = repo.db.sets().forWorkout(workoutId) }
    val run by produceState<RunEntity?>(null, workoutId, w) { value = repo.db.runs().get(workoutId) }
    val profile by repo.store.profile.collectAsState(initial = null)
    var confirm by remember { mutableStateOf(false) }
    // The rating put right: the picker open, and what the plan did about it.
    var rating by remember { mutableStateOf(false) }
    var rated by remember { mutableStateOf<String?>(null) }
    val workout = w ?: return
    ScrollScreen {
        TopBar(workout.title, dateLine(LocalDate.ofEpochDay(workout.epochDay)), onBack = { nav.back() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Время", "${(workout.durationSec ?: 0) / 60}", "мин", F.c.text, Modifier.weight(1f))
            StatTile(
                "Оценка", "${workout.rpe ?: "—"}", "из 10", F.c.text,
                Modifier.weight(1f).clickable(onClickLabel = "Изменить оценку") { rating = true },
            )
            // Not in a warning colour: a load is a number to read, not a mistake.
            StatTile("Нагрузка", "${workout.load?.roundToInt() ?: "—"}", null, F.c.text, Modifier.weight(1f))
        }
        GhostButton("Изменить оценку", { rating = true }, Modifier.fillMaxWidth())
        rated?.let { Text(it, style = T.secondary(13.sp)) }
        if (workout.kind == Kind.LIFT) {
            val summary = workout.summaryJson?.let { runCatching { AppJson.decodeFromString(LiftSummary.serializer(), it) }.getOrNull() }
            val plan = repo.planOf(workout)
            sets.groupBy { it.position }.forEach { (pos, s) ->
                val ex = Catalog.find(s.first().exerciseId)
                val planned = plan?.items?.firstOrNull { it.position == pos }?.takeIf { it.exerciseId == s.first().exerciseId }
                val record = summary?.reports?.any { it.exerciseId == s.first().exerciseId && it.record } == true
                FCard(gap = 4.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(ex?.name ?: s.first().exerciseId, style = T.body(16.sp, FontWeight.Bold), modifier = Modifier.weight(1f))
                        if (record) Text(forma.app.ui.theme.labelText("Рекорд"), style = T.label().copy(color = F.c.good))
                    }
                    // Plan against what was done, each set with the reserve left ("запас 2": two more reps were there).
                    if (planned != null && ex != null) Text("План: ${Texts.prescription(ex, planned.prescription)}", style = T.secondary(13.sp))
                    NumText(
                        s.joinToString("   ") { set ->
                            Texts.weightReps(ex, set.weight, set.reps) +
                                (set.rir?.let { " (запас $it)" } ?: "")
                        },
                        style = T.number(15.sp, FontWeight.SemiBold),
                    )
                }
            }
            if (summary != null) {
                SectionLabel("Что изменилось после")
                FCard(gap = 0.dp) {
                    summary.reports.forEachIndexed { i, r ->
                        if (i > 0) Hairline()
                        Column(Modifier.padding(vertical = 10.dp)) {
                            Text(r.name, style = T.body(15.sp, FontWeight.Bold))
                            Text(Texts.outcome(r.outcome, Catalog.find(r.exerciseId)), style = T.secondary(13.sp))
                        }
                    }
                }
            }
        } else {
            val r = run
            if (r != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Дистанция", r.distanceM?.let { Ru.distance(it) } ?: "—", null, F.c.endurance, Modifier.weight(1f))
                    StatTile("Темп", r.avgPace?.let { Ru.pace(it) } ?: "—", "мин/км", F.c.text, Modifier.weight(1f))
                    StatTile("Выполнено", "${(r.completed * 100).roundToInt()}%", "плана", F.c.text, Modifier.weight(1f))
                }
                if (r.workPace != null) Text("Темп на отрезках, без разминки и отдыха: ${Ru.pace(r.workPace)} /км", style = T.body(15.sp))
                if (r.vdotAfter != null) Text(formLine(r.vdotAfter, profile?.runGoal), style = T.secondary(14.sp))
            }
            val summary = workout.summaryJson?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() }
            if (summary?.manual == true) Text("Внесено вручную: бег без телефона.", style = T.secondary(14.sp))
            summary?.cadence?.let { Text("Шагов в минуту: $it", style = T.secondary(14.sp)) }
            if (r != null) RunDebrief(workout, r, summary)
            if (summary != null) LapsCard(summary.laps, test = summary.outcomes.any { it is forma.core.run.RunOutcome.TestTime })
            if (summary != null && summary.laps.any { it.rep == null }) LapsEditor(workout.id, summary.laps)
            // On the road: the time of each kilometre, as the voice said it (active time, the tracker's distance).
            val splits = summary?.kmSplits.orEmpty()
            if (summary?.laps.isNullOrEmpty() && splits.size >= 2) KmSplitsCard(splits.map { it.toDouble() })
            if (summary != null && summary.outcomes.isNotEmpty()) {
                val planned = r?.let { runCatching { AppJson.decodeFromString(forma.core.run.RunWorkout.serializer(), it.planJson) }.getOrNull() }
                SectionLabel("Что изменилось после")
                FCard(gap = 0.dp) {
                    summary.outcomes.forEachIndexed { i, o ->
                        if (i > 0) Hairline()
                        Text(Texts.run(o, summary.manual, planned), style = T.body(14.sp), modifier = Modifier.padding(vertical = 10.dp))
                    }
                }
            }
        }
        CoachReviewCard(workout.id, workout.title, LocalDate.ofEpochDay(workout.epochDay))
        GhostButton("Удалить из истории", { confirm = true }, Modifier.fillMaxWidth(), color = F.c.bad)
    }
    if (rating) RatingDialog(workout.rpe, run = workout.kind == Kind.RUN, onDismiss = { rating = false }) { v ->
        rating = false
        scope.launch {
            val again = repo.setRating(workoutId, v)
            rated = when {
                workout.kind == Kind.LIFT -> "Оценка исправлена: нагрузка и «Свежесть» пересчитаны."
                again -> "Оценка исправлена, и план заново решил, что делать после этой пробежки. Следующие пойдут уже по-новому."
                else -> "Оценка исправлена, нагрузка пересчитана. План уже не поменяется: после этой пробежки были другие, " +
                    "и они шли от прежнего решения."
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false }, containerColor = F.c.surface,
            title = { Text("Удалить тренировку?", style = T.title(20.sp)) },
            text = { Text("Она пропадёт из истории и графиков. Веса и темпы, которые уже поменялись после неё, останутся как есть.", style = T.secondary(14.sp)) },
            confirmButton = { TextButton({ confirm = false; scope.launch { repo.deleteWorkout(workoutId); nav.back() } }) { Text("Удалить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.bad)) } },
            dismissButton = { TextButton({ confirm = false }) { Text("Отмена", style = T.body(15.sp)) } },
        )
    }
}

/** "How hard was it" again, 1 to 10, as at the end of the workout. */
@Composable
private fun RatingDialog(now: Int?, run: Boolean, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var v by remember { mutableStateOf(now ?: 0) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = F.c.surface,
        title = { Text("Насколько тяжело было?", style = T.title(20.sp)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..5).forEach { x -> forma.app.ui.components.Chip("$x", v == x, { v = x }, Modifier.weight(1f), minWidth = 0.dp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (6..10).forEach { x -> forma.app.ui.components.Chip("$x", v == x, { v = x }, Modifier.weight(1f), minWidth = 0.dp) }
                }
                Text(
                    (Texts.RPE.firstOrNull { it.first == v }?.second?.let { "$it. " } ?: "") +
                        if (run) "По этой оценке план решает, делать ли следующую пробежку тяжелее." else "От оценки зависят нагрузка и «Свежесть».",
                    style = T.secondary(14.sp),
                )
            }
        },
        confirmButton = {
            TextButton({ if (v in 1..10 && v != now) onSave(v) else onDismiss() }) {
                Text("Сохранить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена", style = T.body(15.sp)) } },
    )
}

/**
 * The laps of a saved run put right: tap a lap — a press forgotten on the line (one lap of two laps' time) splits into
 * two, a press too many merges two into one, a press late or early gets the lap's time typed by hand. Laps of reps stay:
 * the plan's next step was judged on them.
 */
@Composable
private fun LapsEditor(workoutId: Long, laps: List<forma.core.run.Lap>) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<Int?>(null) }
    SectionLabel("Поправить круги")
    Text(
        "Забыл нажать «Круг», и круг вышел вдвое длиннее? Нажми на него и раздели. Нажал дважды — соедини. " +
            "Нажал не вовремя — впиши время круга сам.",
        style = T.secondary(13.sp),
    )
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        laps.forEachIndexed { i, l ->
            if (l.rep == null) {
                forma.app.ui.components.Chip(
                    "${l.number} · ${if (l.rough) "≈" else ""}${Ru.lap(l.seconds)}", false, { open = i },
                    minWidth = 0.dp, kind = forma.app.ui.components.ChipKind.ACTION,
                )
            }
        }
    }
    val i = open ?: return
    val l = laps.getOrNull(i) ?: return
    // The lap's time by hand: typed as seconds ("83,4") or as on a watch ("1:23,4").
    var typed by remember(i, l.seconds) { mutableStateOf("") }
    val time = parseLapTime(typed)?.takeIf { it >= 5 && it <= 3600 }
    fun save(next: List<forma.core.run.Lap>) {
        open = null
        scope.launch { repo.editRunLaps(workoutId, next) }
    }
    // On the screen under the laps, not in a dialog: the keyboard for the time has room, and the laps stay in sight.
    FCard(gap = 8.dp) {
        Text("Круг ${l.number} — ${Ru.lapTenth(l.seconds)}", style = T.title(20.sp))
        Text("Что было на самом деле?", style = T.secondary(14.sp))
        NumberField(typed, { typed = it }, "Время круга", "с", placeholder = Ru.one(l.seconds))
        Text(
            if (time != null) "Будет ${Ru.lapTenth(time)}. Остальные круги не меняются."
            else "Впиши секунды, например «83» или «83,4». Или как на часах: «1:23».",
            style = T.secondary(13.sp).copy(color = if (typed.isNotBlank() && time == null) F.c.warn else F.c.text2),
        )
        if (time != null) {
            TextButton({ save(forma.core.run.LapEdit.setTime(laps, i, time)) }) { Text("Поставить ${Ru.lapTenth(time)}", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
        }
        TextButton({ save(forma.core.run.LapEdit.split(laps, i)) }) { Text("Это 2 круга — разделить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
        if (forma.core.run.LapEdit.editable(laps, i + 1)) {
            TextButton({ save(forma.core.run.LapEdit.merge(laps, i)) }) { Text("Соединить со следующим", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
        }
        if (i > 0 && forma.core.run.LapEdit.editable(laps, i - 1)) {
            TextButton({ save(forma.core.run.LapEdit.merge(laps, i - 1)) }) { Text("Соединить с предыдущим", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
        }
        TextButton({ open = null }) { Text("Отмена", style = T.body(15.sp)) }
    }
}

/** The time of each kilometre on the road, as bars and as numbers. */
@Composable
private fun KmSplitsCard(splits: List<Double>) {
    SectionLabel("По километрам")
    FCard(gap = 6.dp) {
        forma.app.ui.components.BarChart(
            splits, F.c.endurance.copy(alpha = 0.55f), highlight = null, floor = splits.min() * 0.85,
            xLabels = splits.indices.map { "${it + 1}" },
            description = "Время каждого километра: ${splits.joinToString(", ") { Ru.pace(it) }}",
        )
        Text(splits.mapIndexed { i, s -> "${i + 1} км — ${Ru.pace(s)}" }.joinToString(" · "), style = T.number(13.sp, FontWeight.SemiBold))
        Text("Чем выше столбик, тем медленнее километр.", style = T.secondary(12.sp))
    }
}

/**
 * The fitness after a run in words that say what it means: "VDOT после пробежки: 46,2 → прогноз на 1,5 км — 5:31" with
 * a goal, the number alone without one. Not «форма»: that word is the app's name.
 */
internal fun formLine(vdot: Double, goal: forma.core.model.RunGoal?): String {
    val head = "VDOT после пробежки: ${Ru.one(vdot)}"
    if (goal == null) return head
    val m = goal.meters
    return "$head → прогноз на ${goalDistance(m)} — ${Ru.clock(forma.core.run.Vdot.predictSeconds(vdot, m.toDouble()).roundToInt())}"
}

private const val BODY = "Что это даёт телу"

/** Read this many times after runs of a type, "what it gives the body" folds away (a tap opens it). */
internal const val BODY_SHOWN = 3

/**
 * The run talked through: how it went against what it was for (laps on target and how even, the easy pace kept, the
 * trial against the goal), the effort against the plan, what it trains and how long before the next hard day.
 */
@Composable
fun RunDebrief(workout: forma.app.data.WorkoutEntity, run: RunEntity, summary: RunSummary?) {
    val repo = LocalRepo.current
    val profile by repo.store.profile.collectAsState(initial = null)
    val plan = remember(run.planJson) { runCatching { AppJson.decodeFromString(forma.core.run.RunWorkout.serializer(), run.planJson) }.getOrNull() } ?: return
    val items = remember(plan, run, summary, workout.rpe, profile?.runGoal, profile?.aim) {
        val trial = summary?.outcomes?.filterIsInstance<forma.core.run.RunOutcome.TestTime>()?.firstOrNull()?.let { it.meters to it.seconds }
        // Reached or not, as the plan counted it (fitness against the goal's, not rounded seconds).
        val reached = if (trial != null) summary?.outcomes?.any { it is forma.core.run.RunOutcome.GoalReached } == true else null
        forma.core.run.RunReview.of(
            forma.core.run.RunReview.Run(
                plan, summary?.laps.orEmpty(), workout.rpe, run.movingSec, run.avgPace, run.workPace, trial, run.completed, reached,
            ),
            profile?.runGoal,
            gym = profile?.aim?.lifts != false,
        )
    }
    // "What it gives the body" says the same after every run of a type: read a few times, it folds away. Counted once
    // per run shown, a turn of the phone or a step back to the screen not again; the count as it was when the screen
    // opened decides (the count going up must not fold it while read).
    val key = plan.type.name
    val seenNow = forma.app.ui.LocalSettings.current.bodySeen[key] ?: 0
    val seen = androidx.compose.runtime.saveable.rememberSaveable(workout.id) { seenNow }
    var counted by androidx.compose.runtime.saveable.rememberSaveable(workout.id) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(workout.id) {
        if (counted) return@LaunchedEffect
        counted = true
        runCatching { repo.updateSettings { it.copy(bodySeen = it.bodySeen + (key to (it.bodySeen[key] ?: 0) + 1)) } }
    }
    var bodyOpen by remember(workout.id) { mutableStateOf(false) }
    if (items.isEmpty()) return
    SectionLabel("Разбор")
    FCard(gap = 0.dp) {
        items.forEachIndexed { i, it ->
            if (i > 0) Hairline()
            val folded = it.title == BODY && seen >= BODY_SHOWN && !bodyOpen
            var m = Modifier.padding(vertical = 10.dp)
            if (it.title == BODY && seen >= BODY_SHOWN) {
                m = Modifier.fillMaxWidth().clickable(onClickLabel = if (bodyOpen) "Свернуть" else "Развернуть") { bodyOpen = !bodyOpen }.then(m)
            }
            Column(m, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    it.title + if (folded) " ›" else "", style = T.body(15.sp, FontWeight.Bold).copy(
                        color = when (it.tone) {
                            1 -> F.c.good
                            -1 -> F.c.warn
                            else -> F.c.text
                        },
                    ),
                )
                if (!folded) Text(it.text, style = T.body(15.sp))
            }
        }
    }
}
