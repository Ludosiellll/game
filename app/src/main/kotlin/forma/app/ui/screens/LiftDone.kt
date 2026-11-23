package forma.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.AppJson
import forma.app.data.LiftPlan
import forma.app.data.LiftSummary
import forma.app.data.SetEntity
import forma.app.data.SlotReport
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.Texts
import forma.app.ui.components.FitTitle
import forma.app.ui.components.FCard
import forma.app.ui.components.Hairline
import forma.app.ui.components.NumText
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.StatTile
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.app.ui.theme.titleText
import forma.core.model.Catalog
import forma.core.model.Exercise
import forma.core.model.LoadKind
import forma.core.text.Ru

@Composable
fun LiftDoneScreen(workoutId: Long) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val w by remember(workoutId) { repo.db.workouts().observe(workoutId) }.collectAsState(initial = null)
    val workout = w ?: return
    val summary = remember(workout.summaryJson) {
        workout.summaryJson?.let { runCatching { AppJson.decodeFromString(LiftSummary.serializer(), it) }.getOrNull() }
    } ?: return
    ScrollScreen(bottomInset = true) {
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FitTitle(titleText("Тренировка сохранена"), style = T.screenTitle())
            Text(workout.title, style = T.secondary(15.sp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Время", "${summary.minutes}", Ru.plural(summary.minutes, "минута", "минуты", "минут"), F.c.text, Modifier.weight(1f))
            StatTile("Подходы", "${summary.sets}", Ru.plural(summary.sets, "рабочий", "рабочих", "рабочих"), F.c.text, Modifier.weight(1f))
            StatTile("Поднято", Ru.one(summary.volumeKg / 1000), "тонны", F.c.strength, Modifier.weight(1f))
        }
        val records = summary.reports.filter { it.record }
        if (records.isNotEmpty()) {
            FCard(outline = F.c.strength) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Ic.trophy, null, tint = F.c.strength, modifier = Modifier.size(22.dp))
                    Text("Новый рекорд силы", style = T.title(18.sp))
                }
                records.forEach { r ->
                    Text("${r.name}: около ${Texts.oneRm(Catalog.find(r.exerciseId), r.e1rm ?: 0.0)} на 1 раз", style = T.body(15.sp))
                }
            }
        }
        // What was done, exercise by exercise in the order of the day, and under each what changes next time: one list
        // (23 November: the six names twice, then one sentence six times). Null until the sets are read.
        val loaded by remember(workoutId) { repo.db.sets().observeFor(workoutId) }.collectAsState(initial = null)
        val plan = remember(workout.planJson) { repo.planOf(workout) }
        val sets = loaded
        if (sets != null) {
            val rows = doneRows(sets, plan, summary.reports)
            // The same line for every exercise (a lighter session): said once, under the list.
            val once = rows.map { r -> r.report?.let { Texts.outcome(it.outcome, Catalog.find(it.exerciseId)) } }
                .distinct().singleOrNull()?.takeIf { rows.size > 1 }
            if (rows.isNotEmpty()) {
                SectionLabel("Сделано")
                FCard(gap = 0.dp) {
                    rows.forEachIndexed { i, row ->
                        if (i > 0) Hairline()
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(row.name, style = T.body(15.sp, FontWeight.Bold))
                            row.sets?.let { NumText(it, style = T.number(15.sp)) }
                            val r = row.report
                            if (r != null && once == null) OutcomeLine(Texts.outcome(r.outcome, Catalog.find(r.exerciseId)), Texts.tone(r.outcome))
                        }
                    }
                }
                once?.let { line -> OutcomeLine(line, rows.firstNotNullOfOrNull { it.report }?.let { Texts.tone(it.outcome) } ?: 0) }
            }
        }
        // No «Нагрузка тренировки: 345. Это твоя оценка × минуты…» here: the number is in «История», how it adds up in «Прогресс».
        CoachReviewCard(workout.id, workout.title, java.time.LocalDate.ofEpochDay(workout.epochDay))
        NextToday { nav.tab(Screen.Today) }
    }
}

/** One exercise of the summary: its name, its sets in a row (null for a report without sets) and its report. */
private class DoneRow(val name: String, val sets: String?, val report: SlotReport?)

/**
 * The exercises done, in the order of the day, each with its report. A report has no position: it goes to the first row
 * of its exercise that has none yet (an exercise done twice is judged by its first). A report with no sets comes last.
 */
private fun doneRows(sets: List<SetEntity>, plan: LiftPlan?, reports: List<SlotReport>): List<DoneRow> {
    val byPosition = sets.groupBy { it.position }
    val order = plan?.items?.filter { it.position in byPosition }?.map { it.position to it.name }.orEmpty() +
        byPosition.keys.filter { p -> plan?.items?.none { it.position == p } != false }
            .map { p -> p to (Catalog.find(byPosition.getValue(p).first().exerciseId)?.name ?: "") }
    val taken = BooleanArray(reports.size)
    val rows = order.map { (position, name) ->
        val done = byPosition.getValue(position)
        val i = reports.indices.firstOrNull { !taken[it] && reports[it].exerciseId == done.first().exerciseId }
        if (i != null) taken[i] = true
        DoneRow(name, setsLine(done), i?.let { reports[it] })
    }
    return rows + reports.indices.filter { !taken[it] }.map { DoneRow(reports[it].name, null, reports[it]) }
}

/** What changes next time, under the exercise's sets: grey, with an arrow only for a step up or down. */
@Composable
private fun OutcomeLine(text: String, tone: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tone != 0) Icon(
            if (tone > 0) Ic.up else Ic.down, null,
            tint = if (tone > 0) F.c.good else F.c.warn, modifier = Modifier.padding(top = 2.dp).size(16.dp),
        )
        Text(text, style = T.secondary(14.sp), modifier = Modifier.weight(1f))
    }
}

/** A number and its unit on one line: «82,5 кг» never leaves «кг» alone at the start of the next. */
private val SET_UNIT = Regex("(\\d) (кг|с)(?![а-яё])")

/**
 * "80 кг × 5, 5, 4 · 85 кг × 3" (dumbbells "по 20 кг × 10, 10", "+10 кг × 8", "помощь 15 кг × 8"): sets in a row at one
 * weight together, the weight once; holds in seconds. [sets]: the weight and reps of each, in order. The one way sets are
 * written: the summary, the exercise's page and «Прошлый раз» on the workout screen.
 */
internal fun setsLine(ex: Exercise?, sets: List<Pair<Double, Int>>): String {
    val line = if (ex?.load == LoadKind.TIMED) sets.joinToString(", ") { "${it.second} с" } else {
        val runs = mutableListOf<MutableList<Pair<Double, Int>>>()
        for (s in sets) {
            val run = runs.lastOrNull()
            if (run != null && run.first().first == s.first) run += s else runs += mutableListOf(s)
        }
        runs.joinToString(" · ") { run -> Texts.weightReps(ex, run.first().first, run.joinToString(", ") { it.second.toString() }) }
    }
    return line.replace(SET_UNIT, "$1\u00A0$2")
}

/** [setsLine] of one exercise's logged sets. */
internal fun setsLine(sets: List<SetEntity>): String =
    if (sets.isEmpty()) "" else setsLine(Catalog.find(sets.first().exerciseId), sets.map { it.weight to it.reps })
