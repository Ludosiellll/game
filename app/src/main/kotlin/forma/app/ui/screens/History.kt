package forma.app.ui.screens

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

@Composable
fun HistoryScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val list by remember { repo.db.workouts().observeHistory() }.collectAsState(initial = emptyList())
    ScrollScreen {
        TopBar("История", "${list.size} ${Ru.plural(list.size, "тренировка", "тренировки", "тренировок")}", onBack = { nav.back() })
        if (list.isEmpty()) Empty("Здесь будут все тренировки и пробежки.")
        list.groupBy { LocalDate.ofEpochDay(it.epochDay).withDayOfMonth(1) }.forEach { (month, items) ->
            SectionLabel("${MONTH_SHORT[month.monthValue - 1]} ${month.year}")
            FCard(gap = 0.dp) {
                items.forEachIndexed { i, w ->
                    if (i > 0) Hairline()
                    ListRow(
                        w.title, "${shortDate(LocalDate.ofEpochDay(w.epochDay))} · ${(w.durationSec ?: 0) / 60} мин",
                        trailing = w.load?.roundToInt()?.toString(),
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
    val run by produceState<RunEntity?>(null, workoutId) { value = repo.db.runs().get(workoutId) }
    var confirm by remember { mutableStateOf(false) }
    val workout = w ?: return
    ScrollScreen {
        TopBar(workout.title, dateLine(LocalDate.ofEpochDay(workout.epochDay)), onBack = { nav.back() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Время", "${(workout.durationSec ?: 0) / 60}", "мин", F.c.text, Modifier.weight(1f))
            StatTile("Оценка", "${workout.rpe ?: "—"}", "из 10", F.c.text, Modifier.weight(1f))
            StatTile("Нагрузка", "${workout.load?.roundToInt() ?: "—"}", null, F.c.load, Modifier.weight(1f))
        }
        if (workout.kind == Kind.LIFT) {
            sets.groupBy { it.position }.forEach { (_, s) ->
                val ex = Catalog.find(s.first().exerciseId)
                FCard(gap = 4.dp) {
                    Text(ex?.name ?: s.first().exerciseId, style = T.body(16.sp, FontWeight.Bold))
                    NumText(
                        s.joinToString("   ") { if (ex?.load == LoadKind.TIMED) "${it.reps} с" else "${Texts.weight(ex, it.weight)}×${it.reps}" },
                        style = T.number(15.sp, FontWeight.SemiBold),
                    )
                }
            }
            val summary = workout.summaryJson?.let { runCatching { AppJson.decodeFromString(LiftSummary.serializer(), it) }.getOrNull() }
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
                if (r.workPace != null) Text("Темп на рабочих отрезках: ${Ru.pace(r.workPace)} /км", style = T.body(15.sp))
                if (r.vdotAfter != null) Text("VDOT после пробежки: ${Ru.one(r.vdotAfter)}", style = T.secondary(14.sp))
            }
            val summary = workout.summaryJson?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() }
            if (summary != null) LapsCard(summary.laps, test = summary.outcomes.any { it is forma.core.run.RunOutcome.TestTime })
            if (summary != null && summary.outcomes.isNotEmpty()) {
                SectionLabel("Что изменилось после")
                FCard(gap = 0.dp) {
                    summary.outcomes.forEachIndexed { i, o ->
                        if (i > 0) Hairline()
                        Text(Texts.run(o), style = T.body(14.sp), modifier = Modifier.padding(vertical = 10.dp))
                    }
                }
            }
        }
        GhostButton("Удалить из истории", { confirm = true }, Modifier.fillMaxWidth(), color = F.c.bad)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false }, containerColor = F.c.surface,
            title = { Text("Удалить тренировку?", style = T.title(20.sp)) },
            text = { Text("Она исчезнет из истории и графиков. Уже пересчитанные цели не откатятся.", style = T.secondary(14.sp)) },
            confirmButton = { TextButton({ confirm = false; scope.launch { repo.deleteWorkout(workoutId); nav.back() } }) { Text("Удалить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.bad)) } },
            dismissButton = { TextButton({ confirm = false }) { Text("Отмена", style = T.body(15.sp)) } },
        )
    }
}
