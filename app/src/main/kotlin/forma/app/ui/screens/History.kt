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
            // «Тяжесть», as «Разбор» calls the rating (23 November: «тяжесть N из 10»); a tap puts it right.
            StatTile(
                "Тяжесть", "${workout.rpe ?: "—"}", "из 10", F.c.text,
                Modifier.weight(1f).clickable(onClickLabel = "Изменить оценку") { rating = true },
            )
            // Not in a warning colour: a load is a number to read, not a mistake.
            StatTile("Нагрузка", "${workout.load?.roundToInt() ?: "—"}", null, F.c.text, Modifier.weight(1f))
        }
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
                SectionLabel("Что изменилось в плане")
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
            val summary = workout.summaryJson?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() }
            val planned = remember(r?.planJson) {
                r?.let { runCatching { AppJson.decodeFromString(forma.core.run.RunWorkout.serializer(), it.planJson) }.getOrNull() }
            }
            val trial = summary?.outcomes?.any {
                it is forma.core.run.RunOutcome.TestTime || it is forma.core.run.RunOutcome.TestFromPart || it is forma.core.run.RunOutcome.TestStopped
            } == true
            if (r != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Дистанция", r.distanceM?.let { Ru.distance(it) } ?: "—", null, F.c.endurance, Modifier.weight(1f))
                    // A session of reps: their own pace. The whole run's, warm-up and rests in it, reads as a failed one
                    // (23 November: 5:43 under «Темповый бег» for reps at 4:58).
                    val reps = planned?.takeIf {
                        it.type == forma.core.run.RunType.GOAL_PACE || it.type == forma.core.run.RunType.INTERVALS || it.type == forma.core.run.RunType.TEMPO
                    }?.reps ?: 0
                    if (reps > 0) StatTile(repsLabel(reps), r.workPace?.let { Ru.pace(it) } ?: "—", "мин/км", F.c.text, Modifier.weight(1f))
                    else StatTile("Темп", r.avgPace?.let { Ru.pace(it) } ?: "—", "мин/км", F.c.text, Modifier.weight(1f))
                    StatTile("Выполнено", "${(r.completed * 100).roundToInt()}%", "плана", F.c.text, Modifier.weight(1f))
                }
                // The goal's time by the fitness, only on the run that set it: every run keeps the last trial's, and under
                // an easy run it read as if that run had set it.
                val setIt = summary?.outcomes?.any { it is forma.core.run.RunOutcome.TestTime || it is forma.core.run.RunOutcome.VdotChanged } == true
                if (setIt && r.vdotAfter != null) formLine(r.vdotAfter, profile?.runGoal)?.let { Text(it, style = T.secondary(14.sp)) }
            }
            if (summary?.manual == true) Text("Внесено вручную: бег без телефона.", style = T.secondary(14.sp))
            summary?.cadence?.let { Text("Шагов в минуту: $it", style = T.secondary(14.sp)) }
            if (r != null) RunDebrief(workout, r, summary)
            if (summary != null) LapsCard(summary.laps, test = trial)
            if (summary != null && summary.laps.isNotEmpty()) LapsEditor(workout.id, summary.laps, test = trial)
            // On the road: the time of each kilometre, as the voice said it (active time, the tracker's distance).
            val splits = summary?.kmSplits.orEmpty()
            if (summary?.laps.isNullOrEmpty() && splits.size >= 2) KmSplitsCard(splits.map { it.toDouble() })
            if (summary != null && summary.outcomes.isNotEmpty()) {
                SectionLabel("Что изменилось в плане")
                FCard(gap = 0.dp) {
                    summary.outcomes.forEachIndexed { i, o ->
                        if (i > 0) Hairline()
                        Text(Texts.run(o, summary.manual, planned), style = T.body(14.sp), modifier = Modifier.padding(vertical = 10.dp))
                    }
                }
            }
        }
        CoachReviewCard(workout.id, workout.title, LocalDate.ofEpochDay(workout.epochDay))
        // The rating put right, at the bottom with the other change to the workout: between the tiles it split them in two
        // (the «Тяжесть» tile opens the same dialog).
        GhostButton("Изменить оценку", { rating = true }, Modifier.fillMaxWidth())
        rated?.let { Text(it, style = T.secondary(13.sp)) }
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
 * The laps of a saved run put right, closed by default (most runs need nothing, and the laps are in «Круги» right
 * above): tap a lap — a press forgotten on the line (one lap of two laps' time) splits into two, a press too many merges
 * two into one, a press late or early gets the lap's time typed by hand. The laps grouped as in «Круги»: each rep's
 * under its name, the rest by their numbers. A rep's lap gets its time only (the reps stay as many); the last run is
 * then decided again on it. The buttons say what each does: no manual above them (23 November: five sentences of it).
 */
@Composable
private fun LapsEditor(workoutId: Long, laps: List<forma.core.run.Lap>, test: Boolean) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var shown by androidx.compose.runtime.saveable.rememberSaveable(workoutId) { mutableStateOf(false) }
    var open by remember { mutableStateOf<Int?>(null) }
    Text(
        if (shown) "Поправить круги" else "Поправить круги ›",
        style = T.body(15.sp, FontWeight.SemiBold).copy(color = F.c.accent),
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = if (shown) "Свернуть" else "Развернуть") {
            shown = !shown
            open = null
        }.padding(vertical = 4.dp),
    )
    if (!shown) return
    val indexed = laps.withIndex().toList()
    val reps = indexed.filter { it.value.rep != null }.groupBy { it.value.rep!! }
    val free = indexed.filter { it.value.rep == null }
    fun repName(rep: Int) = if (test) "Забег" else "Отрезок $rep"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Нажми на круг, который записался неправильно.", style = T.secondary(13.sp))
        reps.forEach { (rep, group) ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(repName(rep), style = T.body(14.sp, FontWeight.SemiBold))
                LapChips(group, numbered = false) { open = it }
            }
        }
        if (free.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (reps.isNotEmpty()) Text("Между отрезками", style = T.body(14.sp, FontWeight.SemiBold))
                // Outside the reps a lap keeps its number: a warm-up on a short loop has some twenty of them.
                LapChips(free, numbered = true) { open = it }
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
    // Which lap: a rep's by its place in the rep («Отрезок 1, круг 2»), any other by its number.
    val name = l.rep?.let { rep ->
        val inRep = laps.count { it.rep == rep }
        if (inRep > 1) "${repName(rep)}, круг ${laps.take(i + 1).count { it.rep == rep }}" else repName(rep)
    } ?: "Круг ${l.number}"
    // On the screen under the laps, not in a dialog: the keyboard for the time has room, and the laps stay in sight.
    FCard(gap = 8.dp) {
        Text("$name: ${Ru.nobreak(Ru.lapTenth(l.seconds))}", style = T.title(20.sp))
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
        if (l.rep != null) {
            TextButton({ open = null }) { Text("Отмена", style = T.body(15.sp)) }
            return@FCard
        }
        TextButton({ save(forma.core.run.LapEdit.split(laps, i)) }) { Text("Это 2 круга — разделить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
        if (forma.core.run.LapEdit.editable(laps, i + 1)) {
            TextButton({ save(forma.core.run.LapEdit.merge(laps, i)) }) { Text("Соединить со следующим", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
        }
        if (i > 0 && forma.core.run.LapEdit.editable(laps, i - 1)) {
            TextButton({ save(forma.core.run.LapEdit.merge(laps, i - 1)) }) { Text("Соединить с предыдущим", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
        }
        // A touch or a key in a jog between reps: no neighbour to join it to, and it is no lap at all.
        TextButton({ save(forma.core.run.LapEdit.remove(laps, i)) }) { Text("Это был не круг — убрать", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.warn)) }
        TextButton({ open = null }) { Text("Отмена", style = T.body(15.sp)) }
    }
}

/** Laps to tap, each with its index in the run's laps: «49 с» in a rep, «5 · 2:21» with its number outside the reps. */
@Composable
private fun LapChips(group: List<IndexedValue<forma.core.run.Lap>>, numbered: Boolean, onOpen: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        group.forEach { (i, l) ->
            val time = (if (l.rough) "≈" else "") + Ru.lap(l.seconds)
            forma.app.ui.components.Chip(
                if (numbered) "${l.number} · $time" else time, false, { onOpen(i) },
                minWidth = 0.dp, kind = forma.app.ui.components.ChipKind.ACTION,
            )
        }
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
 * The fitness a trial set, in what it means for the goal: «Прогноз на 1500 м: 5:31». Null without a goal: the fitness
 * number itself is no word for the screen (30 October: no VDOT on the main screens). Not «форма»: that word is the
 * app's name. The distance as «Прогресс» and the trial's own line write the goal: metres up to 3000, then «10 км».
 */
internal fun formLine(vdot: Double, goal: forma.core.model.RunGoal?): String? {
    goal ?: return null
    val m = goal.meters
    val dist = Ru.nobreak(if (m <= 3000) "$m м" else goalDistance(m))
    return "Прогноз на $dist: ${Ru.clock(forma.core.run.Vdot.predictSeconds(vdot, m.toDouble()).roundToInt())}"
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
    val items = remember(plan, run, summary, workout.rpe, profile?.runGoal) {
        val trial = summary?.outcomes?.filterIsInstance<forma.core.run.RunOutcome.TestTime>()?.firstOrNull()?.let { it.meters to it.seconds }
        // Reached or not, as the plan counted it (fitness against the goal's, not rounded seconds).
        val reached = if (trial != null) summary?.outcomes?.any { it is forma.core.run.RunOutcome.GoalReached } == true else null
        val held = summary?.outcomes?.filterIsInstance<forma.core.run.RunOutcome.TestFromPart>()?.firstOrNull()?.let { it.meters to it.seconds }
        // The rating read on the runner's own scale where the plan read it so (else «Тяжесть» and the plan disagree).
        val own = summary?.outcomes?.filterIsInstance<forma.core.run.RunOutcome.OwnScale>()?.firstOrNull()?.offset ?: 0
        forma.core.run.RunReview.of(
            forma.core.run.RunReview.Run(
                plan, summary?.laps.orEmpty(), workout.rpe, run.movingSec, run.avgPace, run.workPace, trial, run.completed, reached, held, summary?.strides, summary?.jog,
                ownScale = own,
            ),
            profile?.runGoal,
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
