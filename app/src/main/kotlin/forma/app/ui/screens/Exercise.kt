package forma.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.components.FCard
import forma.app.ui.components.Hairline
import forma.app.ui.components.LineChart
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.Series
import forma.app.ui.components.StatTile
import forma.app.ui.components.TopBar
import forma.app.ui.components.NumText
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.core.analytics.Stats
import forma.core.model.Catalog
import forma.core.model.LoadKind
import forma.core.strength.OneRm
import forma.core.text.Ru
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ExerciseScreen(exerciseId: String) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val ex = Catalog.find(exerciseId) ?: return
    val rows by remember { repo.setRows() }.collectAsState(initial = emptyList())
    val bw by remember { repo.bodyWeightLookup() }.collectAsState(initial = { _: Long -> 75.0 })
    val stats by remember { repo.db.state().observeStats() }.collectAsState(initial = emptyList())
    val slots by remember { repo.db.state().observeSlots() }.collectAsState(initial = emptyList())
    val mine = rows.filter { it.exerciseId == exerciseId }
    val series = remember(rows, bw) { Stats.e1rmSeries(rows, ex, bw) }
    val rec = remember(rows, bw) { Stats.records(rows, ex, bw) }
    val e1rm = stats.firstOrNull { it.exerciseId == exerciseId }?.e1rm
    ScrollScreen {
        TopBar(ex.name, "${ex.equipment.ru} · ${ex.primary.joinToString { it.ru.lowercase() }}", onBack = { nav.back() })
        if (ex.load != LoadKind.TIMED && series.isNotEmpty()) {
            FCard(gap = 4.dp) {
                Text("Максимум на 1 раз, расчёт", style = T.secondary(13.sp))
                NumText("${Ru.kg(((e1rm ?: series.last().value) * 2).roundToInt() / 2.0)} кг", style = T.number(40.sp).copy(color = F.c.strength))
                if (series.size >= 2) LineChart(
                    listOf(Series(series.map { it.value }, F.c.strength, fill = true)),
                    xLabels = listOf(shortDate(LocalDate.ofEpochDay(series.first().epochDay)), shortDate(LocalDate.ofEpochDay(series.last().epochDay))),
                    description = "Расчётный максимум по тренировкам",
                )
                Text("Точка — лучший подход тренировки, пересчитанный в один повтор с учётом запаса.", style = T.secondary(12.sp))
            }
        }
        if (rec != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Лучший расчёт", if (ex.load == LoadKind.TIMED) "—" else Ru.kg((rec.bestE1rm * 2).roundToInt() / 2.0), "кг", F.c.strength, Modifier.weight(1f))
                StatTile("Тяжелее всего", Ru.kg(rec.heaviest), if (ex.load == LoadKind.BODYWEIGHT_PLUS) "доп. кг" else "кг", F.c.text, Modifier.weight(1f))
                StatTile("Тренировок", "${rec.sessions}", null, F.c.text, Modifier.weight(1f))
            }
        }
        val slot = slots.filter { it.exerciseId == exerciseId }.maxByOrNull { it.updatedAt }
        if (slot?.weight != null) {
            FCard(gap = 4.dp) {
                SectionLabel("Следующая цель")
                Text(if (ex.load == LoadKind.TIMED) "${slot.reps} с" else "${forma.app.ui.Texts.weight(ex, slot.weight!!)} × ${slot.reps}", style = T.title(22.sp))
                if (slot.stalls > 0) Text("Без роста: ${slot.stalls} ${Ru.plural(slot.stalls, "тренировка", "тренировки", "тренировок")} подряд. После трёх — шаг назад на 10 %.", style = T.secondary(13.sp))
            }
        }
        if (e1rm != null && ex.load == LoadKind.EXTERNAL) {
            SectionLabel("Сколько раз сможешь (расчёт)")
            FCard(gap = 0.dp) {
                listOf(1, 3, 5, 8, 10, 12).forEachIndexed { i, r ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                        Text("${Ru.reps(r)} до отказа", style = T.body(14.sp), modifier = Modifier.weight(1f))
                        // Down to the step: "you can do it" must not promise more than the estimate.
                        NumText("${Ru.kg(kotlin.math.floor(OneRm.loadFor(e1rm, r) / ex.step + 1e-9) * ex.step)} кг", style = T.number(14.sp, FontWeight.Bold))
                    }
                }
            }
        }
        if (ex.aliases.isNotEmpty()) Text("Ещё называют: ${ex.aliases.joinToString(", ")}", style = T.secondary(13.sp))
        val similar = remember(exerciseId) { repo.alternativesFor(exerciseId).take(5) }
        if (similar.isNotEmpty()) {
            SectionLabel("Похожие")
            FCard(gap = 0.dp) {
                similar.forEachIndexed { i, o ->
                    if (i > 0) Hairline()
                    forma.app.ui.components.ListRow(o.name, (o.primary.map { it.ru } + o.equipment.ru).joinToString(" · ")) {
                        nav.push(forma.app.ui.Screen.Exercise(o.id))
                    }
                }
            }
        }
        if (mine.isNotEmpty()) {
            SectionLabel("Последние тренировки")
            FCard(gap = 0.dp) {
                mine.groupBy { it.workoutId }.entries.reversed().take(8).forEachIndexed { i, (_, sets) ->
                    if (i > 0) Hairline()
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(shortDate(LocalDate.ofEpochDay(sets.first().epochDay)), style = T.secondary(12.sp))
                        NumText(sets.joinToString("  ") { if (ex.load == LoadKind.TIMED) "${it.reps} с" else "${Ru.kg(it.weight)}×${it.reps}" }, style = T.number(15.sp, FontWeight.SemiBold))
                    }
                }
            }
        }
        if (ex.custom && !ex.archived) {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            forma.app.ui.components.GhostButton("Убрать из списка", {
                scope.launch { repo.archiveCustom(ex.id); nav.back() }
            }, Modifier.fillMaxWidth())
            Text("История останется, упражнение просто перестанет предлагаться.", style = T.secondary(12.sp))
        }
    }
}
