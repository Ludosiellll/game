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
    val settings by repo.store.settings.collectAsState(initial = forma.app.data.Settings())
    val program by repo.store.program.collectAsState(initial = null)
    val mine = rows.filter { it.exerciseId == exerciseId }
    val series = remember(rows, bw) { Stats.e1rmSeries(rows, ex, bw) }
    val rec = remember(rows, bw) { Stats.records(rows, ex, bw) }
    val e1rm = stats.firstOrNull { it.exerciseId == exerciseId }?.e1rm
    ScrollScreen {
        TopBar(ex.name, "${ex.equipment.ru} · ${ex.primary.joinToString { it.ru.lowercase() }}", onBack = { nav.back() })
        if (ex.load != LoadKind.TIMED && series.isNotEmpty()) {
            FCard(gap = 4.dp) {
                Text("Максимум на 1 раз, расчёт", style = T.secondary(13.sp))
                NumText(forma.app.ui.Texts.oneRm(ex, e1rm ?: series.last().value), style = T.number(40.sp).copy(color = F.c.strength))
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
                if (ex.load == LoadKind.TIMED) {
                    // Holds: seconds, not kilograms.
                    StatTile("Дольше всего", "${rec.mostReps}", "с", F.c.strength, Modifier.weight(1f))
                } else {
                    // Dumbbells: the weight of one of the two, as everywhere ("30 кг ×2").
                    val kg = if (ex.perHand && ex.load == LoadKind.EXTERNAL) "кг ×2" else "кг"
                    StatTile("Лучший расчёт", if (rec.bestE1rm > 0) Ru.kg((rec.bestE1rm * 2).roundToInt() / 2.0) else "—", kg, F.c.strength, Modifier.weight(1f))
                    val (value, unit) = when {
                        ex.load != LoadKind.BODYWEIGHT_PLUS -> Ru.kg(rec.heaviest) to kg
                        rec.heaviest > 0 -> "+${Ru.kg(rec.heaviest)}" to "доп. кг"
                        rec.heaviest < 0 -> Ru.kg(-rec.heaviest) to "помощь, кг"
                        else -> "0" to "свой вес"
                    }
                    StatTile("Тяжелее всего", value, unit, F.c.text, Modifier.weight(1f))
                }
                StatTile("Тренировок", "${rec.sessions}", null, F.c.text, Modifier.weight(1f))
            }
        }
        // One target per rep range: the same exercise in 5–8 on one day and 8–12 on another has two, each said with its day.
        val targets = slots.filter { it.exerciseId == exerciseId && (it.weight != null || ex.load == LoadKind.TIMED) }.sortedByDescending { it.updatedAt }
        if (targets.isNotEmpty()) {
            FCard(gap = 4.dp) {
                SectionLabel("Следующая цель")
                targets.take(3).forEachIndexed { i, slot ->
                    if (i > 0) Hairline()
                    Text(if (ex.load == LoadKind.TIMED) "${slot.reps} с" else forma.app.ui.Texts.weightReps(ex, slot.weight!!, slot.reps), style = T.title(22.sp))
                    Text(targetWhere(slot.slotKey, ex.load == LoadKind.TIMED, program, settings, repo), style = T.secondary(13.sp))
                    if (slot.stalls > 0) Text("Без роста: ${slot.stalls} ${Ru.plural(slot.stalls, "тренировка", "тренировки", "тренировок")} подряд. После трёх — шаг назад на 10 %.", style = T.secondary(13.sp))
                }
            }
        }
        if (e1rm != null && ex.load == LoadKind.EXTERNAL) {
            SectionLabel("Сколько раз сможешь (расчёт)")
            FCard(gap = 0.dp) {
                listOf(1, 3, 5, 8, 10, 12).forEachIndexed { i, r ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                        Text("${Ru.reps(r)} до отказа", style = T.body(14.sp), modifier = Modifier.weight(1f))
                        // Down to the step of this gym (the user's own, else the exercise's): "you can do it" must not
                        // promise more than the estimate, nor a weight the gym does not have.
                        val load = OneRm.loadFor(e1rm, r)
                        val step = settings.steps[ex.id] ?: ex.stepAt(load)
                        NumText(forma.app.ui.Texts.weight(ex, kotlin.math.floor(load / step + 1e-9) * step), style = T.number(14.sp, FontWeight.Bold))
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
                        // "+10 кг × 8" for a weighted pull-up, "помощь 15 кг × 8" with help: a bare "-15×8" says nothing.
                        NumText(
                            sets.joinToString("  ") {
                                when {
                                    ex.load == LoadKind.TIMED -> "${it.reps} с"
                                    ex.load == LoadKind.BODYWEIGHT_PLUS || ex.assisted -> "${forma.app.ui.Texts.weight(ex, it.weight)} × ${it.reps}"
                                    else -> "${Ru.kg(it.weight)}×${it.reps}"
                                }
                            },
                            style = T.number(15.sp, FontWeight.SemiBold),
                        )
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

/**
 * Which rep range a target is for, and on which day of the plan: "6–10 повторов · «Верх А»". The days are found by the
 * slot as the user set it (sets, swaps, rep ranges of their own).
 */
private fun targetWhere(slotKey: String, timed: Boolean, program: forma.core.plan.Program?, settings: forma.app.data.Settings, repo: forma.app.data.TrainingRepository): String {
    val range = slotKey.substringAfterLast('@', "").takeIf { it.contains('-') }?.replace('-', '–')
    val unit = if (timed) "с" else "повторов"
    val days = program?.templates.orEmpty().filter { t ->
        t.slots.any { forma.app.data.TrainingRepository.tuned(it, settings).key == slotKey } ||
            repo.addedTo(t.id, settings).any { id ->
                forma.app.data.TrainingRepository.overrides(forma.app.data.TrainingRepository.addedSpec(id), settings, forma.app.data.TrainingRepository.addedKey(t.id, id)).key == slotKey
            }
    }.map { "«${it.title}»" }
    val what = if (range != null) "Для $range $unit" else "Цель"
    return when {
        days.isNotEmpty() -> "$what · ${days.joinToString(", ")}"
        else -> "$what · сейчас такого диапазона в плане нет"
    }
}
