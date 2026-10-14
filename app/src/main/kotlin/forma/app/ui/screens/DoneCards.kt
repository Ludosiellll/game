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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.AppJson
import forma.app.data.Kind
import forma.app.data.RunEntity
import forma.app.data.SetEntity
import forma.app.data.WorkoutEntity
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.Texts
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.NumText
import forma.app.ui.components.SectionLabel
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.core.model.Catalog
import forma.core.model.Exercise
import forma.core.model.LoadKind
import forma.core.run.RunWorkout
import forma.core.text.Ru
import java.time.DayOfWeek
import java.time.LocalDate

/** "в пн", "во вт", "в ср": a day of the week after «в». */
fun onDayShort(d: DayOfWeek): String = (if (d == DayOfWeek.TUESDAY) "во " else "в ") + dayShort(d).lowercase()

/** "в понедельник", "во вторник", "в среду": a day of the week after «в». */
fun onDayFull(d: DayOfWeek): String = listOf(
    "в понедельник", "во вторник", "в среду", "в четверг", "в пятницу", "в субботу", "в воскресенье",
)[d.value - 1]

/**
 * The sets of one exercise as done, short: "3×8 · 80 кг", "8, 8, 7 · 80 кг" (one weight, reps differ), "4 подх. · до
 * 85 кг"; holds in seconds.
 */
fun doneShort(ex: Exercise?, sets: List<SetEntity>): String {
    if (sets.isEmpty()) return "—"
    if (ex?.load == LoadKind.TIMED) return sets.joinToString(", ") { "${it.reps} с" }
    val weights = sets.map { it.weight }.distinct()
    val reps = sets.map { it.reps }
    return when {
        weights.size == 1 && reps.distinct().size == 1 -> "${sets.size}×${reps.first()} · ${Texts.weight(ex, weights.first())}"
        weights.size == 1 -> "${reps.joinToString(", ")} · ${Texts.weight(ex, weights.first())}"
        else -> "${sets.size} подх. · до ${Texts.weight(ex, weights.max())}"
    }
}

/**
 * A finished gym session as it was done: each exercise with its sets (from the session's own plan for the order and
 * names), [max] rows at most, and the session's breakdown one tap away.
 */
@Composable
fun DoneLiftDetails(w: WorkoutEntity, max: Int = Int.MAX_VALUE) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val sets by produceState<List<SetEntity>>(emptyList(), w.id) { value = repo.db.sets().forWorkout(w.id) }
    val plan = repo.planOf(w)
    val byPos = sets.groupBy { it.position }
    // In the order of the session; an exercise the plan does not name (added during it) by its sets.
    val rows = (plan?.items?.map { it.position to (Catalog.find(it.exerciseId) to it.name) }.orEmpty() +
        byPos.keys.filter { p -> plan?.items?.none { it.position == p } != false }.map { p ->
            val ex = Catalog.find(byPos.getValue(p).first().exerciseId)
            p to (ex to (ex?.name ?: byPos.getValue(p).first().exerciseId))
        }).filter { (p, _) -> byPos[p].orEmpty().isNotEmpty() }
    Column {
        rows.take(max).forEach { (p, x) ->
            Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(x.second, style = T.body(15.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1)
                NumText(doneShort(x.first, byPos[p].orEmpty()), style = T.number(14.sp, FontWeight.Bold))
            }
        }
        if (rows.size > max) {
            Hairline()
            Text("+ ещё ${rows.size - max} ${Ru.plural(rows.size - max, "упражнение", "упражнения", "упражнений")}", style = T.secondary(13.sp), modifier = Modifier.padding(top = 10.dp))
        }
    }
    GhostButton("Разбор тренировки", { nav.push(Screen.HistoryItem(w.id)) }, Modifier.fillMaxWidth(), height = 48.dp)
}

/** A finished run as it was run: distance, time and the average lap (on a stadium) or pace; its debrief a tap away. */
@Composable
fun DoneRunDetails(w: WorkoutEntity) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val run by produceState<RunEntity?>(null, w.id) { value = repo.db.runs().get(w.id) }
    val lapOwn = repo.store.profile.collectAsState(initial = null).value?.lapMeters
    run?.let { r -> NumText(runResult(r, lapOwn), style = T.number(14.sp, FontWeight.Bold).copy(color = F.c.text)) }
    GhostButton("Как прошло — разбор", { nav.push(Screen.HistoryItem(w.id)) }, Modifier.fillMaxWidth(), height = 48.dp)
}

/**
 * "4,00 км · 25:12 · круг 52,3 с": what a run came to. A session of reps shows the reps' lap (the whole session's, with
 * the warm-up and the rests in it, is far slower); a road run its pace a km. [lapM]: the stadium when the run's plan
 * cannot be read.
 */
fun runResult(r: RunEntity, lapM: Int?): String {
    val plan = runCatching { AppJson.decodeFromString(RunWorkout.serializer(), r.planJson) }.getOrNull()
    val lap = if (plan != null) plan.lapMeters else lapM
    val pace = (if (plan != null && plan.reps > 0) r.workPace else r.avgPace)?.takeIf { it > 0 }
    return listOfNotNull(
        r.distanceM?.takeIf { it > 0 }?.let { Ru.distance(it) },
        r.movingSec.takeIf { it > 0 }?.let { Ru.clock(it) },
        pace?.let { if (lap != null) "круг ${lapTenth(it * lap / 1000)}" else "${Ru.pace(it)} /км" },
    ).joinToString(" · ")
}

/**
 * A workout done beyond the plan of its day: a missed session caught up or one done ahead ([forDay]: the day it was
 * planned on), or one extra — shown as done, with its result.
 */
@Composable
fun OffPlanCard(w: WorkoutEntity, forDay: LocalDate?) {
    val lift = w.kind == Kind.LIFT
    FCard(gap = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (lift) Ic.dumbbell else Ic.run, null, tint = if (lift) F.c.strength else F.c.endurance, modifier = Modifier.size(18.dp))
            // In place of a missed one, or beyond the plan: the header says which, the line under the title that it is done.
            SectionLabel(
                "  " + when {
                    forDay == null -> "Вне плана"
                    // Done ahead of its day (that day then passed without it).
                    forDay.toEpochDay() > w.epochDay -> "Заранее — за ${dayShort(forDay.dayOfWeek).lowercase()}"
                    else -> "Вместо пропущенной ${onDayShort(forDay.dayOfWeek)}"
                },
                color = if (lift) F.c.strength else F.c.endurance,
            )
        }
        Text(w.title, style = T.title(22.sp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Ic.check, null, tint = F.c.good, modifier = Modifier.size(18.dp))
            Text("Сделано", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.good))
        }
        if (lift) DoneLiftDetails(w, max = 3) else DoneRunDetails(w)
    }
}

/** A planned session caught up on a later day of the week: "Сделано в ср", with what was done. */
@Composable
fun DoneLaterLine(w: WorkoutEntity) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Ic.check, null, tint = F.c.good, modifier = Modifier.size(18.dp))
        Text(
            "Сделано ${onDayShort(LocalDate.ofEpochDay(w.epochDay).dayOfWeek)}: ${w.title}",
            style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.good),
        )
    }
}

/** A lap time to a tenth: "50,6 с" under a minute, "1:28,3" from a minute up (the goal is often tenths a lap away). */
fun lapTenth(sec: Double): String = Ru.lapTenth(sec)
