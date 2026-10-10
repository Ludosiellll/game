package forma.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.Kind
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.components.BarChart
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.LineChart
import forma.app.ui.components.RangeBar
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.Segmented
import forma.app.ui.components.Series
import forma.app.ui.components.Sparkline
import forma.app.ui.components.StatTile
import forma.app.ui.components.NumText
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.core.analytics.Point
import forma.core.analytics.SetRow
import forma.core.analytics.Stats
import forma.core.load.TrainingLoad
import forma.core.model.Catalog
import forma.core.model.LiftFocus
import forma.core.model.Muscle
import forma.core.run.PaceTable
import forma.core.run.RunPlan
import forma.core.run.Vdot
import forma.core.text.Ru
import java.time.LocalDate
import kotlin.math.roundToInt

private fun monthLabels(points: List<Point>): List<String> {
    if (points.size < 2) return emptyList()
    val a = LocalDate.ofEpochDay(points.first().epochDay)
    val b = LocalDate.ofEpochDay(points.last().epochDay)
    val m = LocalDate.ofEpochDay((points.first().epochDay + points.last().epochDay) / 2)
    return listOf(shortDate(a), shortDate(m), shortDate(b))
}

@Composable
fun ProgressScreen(initialTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    ScrollScreen {
        Text(if (F.type.capsTitles) "ПРОГРЕСС" else "Прогресс", style = T.screenTitle(), modifier = Modifier.padding(top = 8.dp))
        Segmented(listOf("Сила", "Бег", "Нагрузка", "Тело"), tab, { tab = it })
        when (tab) {
            0 -> StrengthTab()
            1 -> RunTab()
            2 -> LoadTab()
            else -> BodyTab()
        }
    }
}

@Composable
private fun StrengthTab() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val rows by remember { repo.setRows() }.collectAsState(initial = emptyList())
    val bw by remember { repo.bodyWeightLookup() }.collectAsState(initial = { _: Long -> 75.0 })
    val stats by remember { repo.db.state().observeStats() }.collectAsState(initial = emptyList())
    val index = remember(rows, bw) { Stats.strengthIndex(rows, bw) }
    if (rows.isEmpty()) {
        Empty("Графики силы появятся после первых тренировок. Каждый подход превращается в расчётный максимум на 1 повтор — по нему видно рост, даже если вес на штанге тот же.")
        return
    }
    FCard(gap = 4.dp) {
        Text("Индекс силы · все упражнения", style = T.secondary(13.sp))
        val ch = Stats.changePct(index)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumText(ch?.let { (if (it >= 0) "+" else "") + it.roundToInt() + "%" } ?: "старт", style = T.number(44.sp).copy(color = F.c.strength))
            if (index.size >= 2) Text("с ${shortDate(LocalDate.ofEpochDay(index.first().epochDay))}", style = T.secondary(13.sp), modifier = Modifier.padding(bottom = 8.dp))
        }
        if (index.size >= 2) LineChart(listOf(Series(index.map { it.value }, F.c.strength, fill = true)), xLabels = monthLabels(index), reference = 100.0, description = "Индекс силы по неделям")
        Text("100 — твой старт. Считается по расчётному максимуму каждого упражнения.", style = T.secondary(12.sp))
    }
    SectionLabel("Упражнения")
    FCard(gap = 0.dp) {
        val exercises = rows.map { it.exerciseId }.distinct().mapNotNull { Catalog.find(it) }
        exercises.forEachIndexed { i, ex ->
            if (i > 0) Hairline()
            val timed = ex.load == forma.core.model.LoadKind.TIMED
            // Holds are measured in seconds: the longest hold of each session.
            val series = if (timed) {
                rows.filter { it.exerciseId == ex.id && it.reps > 0 }.groupBy { it.workoutId }
                    .map { (_, s) -> forma.core.analytics.Point(s.first().epochDay, s.maxOf { it.reps }.toDouble()) }.sortedBy { it.epochDay }
            } else Stats.e1rmSeries(rows, ex, bw)
            val cur = if (timed) series.maxOfOrNull { it.value } else stats.firstOrNull { it.exerciseId == ex.id }?.e1rm?.takeIf { it > 0 } ?: series.lastOrNull()?.value
            val delta = if (series.size >= 2) series.last().value - series.first().value else null
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp).then(Modifier.clickableRow { nav.push(Screen.Exercise(ex.id)) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(ex.name, style = T.body(15.sp, FontWeight.SemiBold))
                    Text(if (ex.load == forma.core.model.LoadKind.TIMED) "время удержания" else "максимум на 1 раз, расчёт", style = T.secondary(12.sp))
                }
                Sparkline(series.map { it.value }, F.c.strength, Modifier.size(64.dp, 24.dp).padding(end = 8.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(64.dp)) {
                    val unit = if (timed) " с" else ""
                    NumText(cur?.let { (if (timed) "${it.roundToInt()}" else Ru.kg((it * 2).roundToInt() / 2.0)) + unit } ?: "—", style = T.number(17.sp))
                    if (delta != null) NumText(
                        (if (delta >= 0) "▲ +" else "▼ ") + (if (timed) "${kotlin.math.abs(delta).roundToInt()}" else Ru.kg((kotlin.math.abs(delta) * 2).roundToInt() / 2.0)) + unit,
                        style = T.number(11.sp, FontWeight.Bold).copy(color = if (delta >= 0) F.c.good else F.c.warn),
                    )
                }
            }
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit) = this.clickable(onClick = onClick)

/** The race goal: its pace (and lap time on a stadium), the forecast from the current form, the race-pace ladder. */
@Composable
private fun GoalCard(goal: forma.core.model.RunGoal, st: forma.core.run.RunState?, lap: Int?, hard: Boolean) {
    val now = st?.vdot?.let { Vdot.predictSeconds(it, goal.meters.toDouble()) }
    FCard(gap = 6.dp) {
        Text("Цель", style = T.secondary(13.sp))
        Text("${goalDistance(goal.meters)} за ${Ru.clock(goal.seconds)}", style = T.title(26.sp).copy(color = F.c.endurance))
        Text(
            "Темп ${Ru.pace(goal.pace)} /км" + (lap?.let { " · " + Ru.nobreak("круг $it м — ${Ru.lap(forma.core.run.Track.lapSec(goal.pace, it))}") } ?: ""),
            style = T.body(15.sp, FontWeight.SemiBold),
        )
        when {
            !hard -> Text(
                "В неделе нет тяжёлых пробежек — без них к забегу не подготовиться. Добавь день или выбери бег «Скорость».",
                style = T.body(14.sp).copy(color = F.c.warn),
            )
            now == null -> Text("Прогноз появится после контрольного забега — он встанет в план после пары пробежек.", style = T.secondary(13.sp))
            now <= goal.seconds + 0.5 -> Text(
                "Прогноз сейчас: ${Ru.clock(now.roundToInt())} — цель по силам. Подтверди её на контрольном забеге.",
                style = T.body(14.sp).copy(color = F.c.good),
            )
            else -> Text("Прогноз сейчас: ${Ru.clock(now.roundToInt())}, до цели ${Ru.clock((now - goal.seconds).roundToInt())}.", style = T.body(14.sp))
        }
        if (goal.middleDistance && st != null && st.canRunContinuously) {
            Text(
                "Отрезки в темпе цели: шаг ${st.goalStage + 1} из ${RunPlan.goalLadderSize}" +
                    if (st.testSoon) ". Следующая тяжёлая пробежка — контрольный забег." else ".",
                style = T.secondary(13.sp),
            )
        }
    }
}

/** A lap time to a tenth: "51,4 с" (the goal is often a few tenths a lap away). */
private fun lapTenth(sec: Double): String = if (sec < 59.95) "${Ru.one(sec)} с" else Ru.clock(sec.roundToInt())

/** The way to the goal: trials over its distance, then the average lap and the laps on pace in the goal-pace sessions. */
@Composable
private fun GoalPathCard(goal: forma.core.model.RunGoal, lapM: Int, path: forma.app.data.GoalProgress.Result) {
    val target = forma.core.run.Track.lapSec(goal.pace, lapM)
    FCard(gap = 8.dp) {
        Text("Путь к цели", style = T.secondary(13.sp))
        val trials = path.trials
        if (trials.isEmpty()) {
            Text("Контрольных на ${goalDistance(goal.meters)} ещё не было.", style = T.body(15.sp, FontWeight.SemiBold))
        } else {
            val best = trials.minBy { it.seconds }
            val reached = best.seconds <= goal.seconds
            Text("Лучший контрольный — ${Ru.clock(best.seconds)}", style = T.title(22.sp).copy(color = if (reached) F.c.good else F.c.text))
            Text(
                if (reached) "Цель взята ${shortDate(LocalDate.ofEpochDay(best.epochDay))}."
                else (best.seconds - goal.seconds).let { d -> "До цели ${if (d < 60) "$d с" else Ru.clock(d)}." },
                style = T.body(14.sp),
            )
            if (trials.size >= 2) {
                LineChart(
                    listOf(Series(trials.map { it.seconds.toDouble() }, F.c.endurance)), height = 110.dp, reference = goal.seconds.toDouble(),
                    xLabels = trials.indices.map { if (it == 0 || it == trials.lastIndex) shortDate(LocalDate.ofEpochDay(trials[it].epochDay)) else "" },
                    description = "Контрольные забеги на ${goalDistance(goal.meters)}: время по датам, линия — цель",
                )
                Text("Ниже — быстрее; серая линия — цель.", style = T.secondary(12.sp))
            }
            trials.takeLast(4).reversed().forEachIndexed { i, t ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(shortDate(LocalDate.ofEpochDay(t.epochDay)), style = T.body(14.sp), modifier = Modifier.weight(1f))
                    NumText(Ru.clock(t.seconds), style = T.number(15.sp, FontWeight.Bold))
                }
            }
        }
        Hairline()
        Text("Отрезки в темпе цели · круг ${Ru.nobreak(lapTenth(target))}", style = T.secondary(13.sp))
        val sessions = path.sessions
        if (sessions.isEmpty()) {
            Text(
                "После первой такой тренировки здесь будут средний круг отрезков и сколько кругов прошло в темпе — по нажатиям «Круг».",
                style = T.secondary(13.sp),
            )
        } else {
            fun lapOf(s: forma.app.data.GoalProgress.Session) = s.paceSecKm * lapM / 1000.0
            val last = sessions.last()
            val gap = lapOf(last) - target
            Text(
                "Средний круг ${lapTenth(lapOf(last))}" + when {
                    gap > 0.25 -> ", на ${Ru.one(gap)} с медленнее цели"
                    gap < -0.25 -> ", на ${Ru.one(-gap)} с быстрее цели"
                    else -> " — ровно в цель"
                },
                style = T.title(18.sp),
            )
            if (last.judged > 0) Text("В темпе ${last.onPace} из ${last.judged} ${Ru.plural(last.judged, "круга", "кругов", "кругов")}", style = T.body(14.sp))
            if (sessions.size >= 2) {
                LineChart(
                    listOf(Series(sessions.map { lapOf(it) }, F.c.endurance)), height = 110.dp, reference = target,
                    xLabels = sessions.indices.map { if (it == 0 || it == sessions.lastIndex) shortDate(LocalDate.ofEpochDay(sessions[it].epochDay)) else "" },
                    description = "Средний круг отрезков в темпе цели по тренировкам, линия — круг цели",
                )
                Text("Ниже — быстрее; серая линия — круг цели.", style = T.secondary(12.sp))
            }
            sessions.takeLast(4).reversed().forEachIndexed { i, s ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(shortDate(LocalDate.ofEpochDay(s.epochDay)), style = T.body(14.sp), modifier = Modifier.weight(1f))
                    if (s.judged > 0) Text("${s.onPace}/${s.judged} в темпе", style = T.secondary(13.sp), modifier = Modifier.padding(end = 12.dp))
                    NumText(lapTenth(lapOf(s)), style = T.number(15.sp, FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
private fun RunTab() {
    val repo = LocalRepo.current
    val runState by repo.store.runState.collectAsState(initial = null)
    val runs by remember { repo.db.runs().observeAll() }.collectAsState(initial = emptyList())
    val workouts by remember { repo.db.workouts().observeHistory() }.collectAsState(initial = emptyList())
    val today = forma.app.ui.rememberToday(repo)
    val st = runState?.let { RunPlan.withBreak(it, today.toEpochDay()) }
    val vdot = st?.vdot
    val profile by repo.store.profile.collectAsState(initial = null)
    val program by repo.store.program.collectAsState(initial = null)
    val hard = program?.week?.days?.any { d -> d.sessions.any { it.kind == forma.core.plan.SessionKind.RUN_QUALITY } } != false
    profile?.runGoal?.let { GoalCard(it, st, profile?.lapMeters, hard) }
    // On a stadium: the trials over the goal distance and how the laps at its pace go.
    val goal = profile?.runGoal
    val lapM = profile?.lapMeters
    if (goal != null && goal.middleDistance && lapM != null) {
        val path = remember(workouts, runs, goal.meters) { forma.app.data.GoalProgress.of(workouts, runs, goal.meters) }
        GoalPathCard(goal, lapM, path)
    }
    if (vdot != null) {
        FCard(gap = 6.dp) {
            Text("VDOT — беговая форма", style = T.secondary(13.sp))
            NumText(Ru.one(vdot), style = T.number(44.sp).copy(color = F.c.endurance))
            val middle = profile?.runGoal?.middleDistance == true
            Text(
                if (middle) "По формулам Дэниелса. Меняется по контрольным забегам на дистанцию цели."
                else "По формулам Дэниелса. Меняется по контрольным забегам и когда тяжёлые пробежки идут в темпе с запасом.",
                style = T.secondary(12.sp),
            )
        }
        SectionLabel("Прогноз на забег")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5000.0 to "5 км", 10000.0 to "10 км", 21097.5 to "21,1 км").forEach { (m, label) ->
                StatTile(label, Ru.clock(Vdot.predictSeconds(vdot, m).roundToInt()), null, F.c.text, Modifier.weight(1f))
            }
        }
        // A forecast from short races for long ones assumes training for them: say so instead of promising it.
        Text("Если готовиться к этой дистанции: на длинные нужны длинные пробежки, без них прогноз оптимистичен.", style = T.secondary(12.sp))
        val p = PaceTable.forVdot(vdot)
        SectionLabel("Темпы, мин/км")
        FCard(gap = 0.dp) {
            listOf(
                "Лёгкий" to "${Ru.pace(p.easy.fast.toDouble())}–${Ru.pace(p.easy.slow.toDouble())}",
                "Марафонский" to Ru.pace(p.marathon.toDouble()),
                "Пороговый (темповый)" to Ru.pace(p.threshold.toDouble()),
                "Интервальный" to Ru.pace(p.interval.toDouble()),
                "Повторный" to Ru.pace(p.repetition.toDouble()),
            ).forEachIndexed { i, (name, v) ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(name, style = T.body(15.sp), modifier = Modifier.weight(1f))
                    NumText(v, style = T.number(15.sp, FontWeight.Bold))
                }
            }
        }
    } else if (st != null && !st.canRunContinuously) {
        FCard(gap = 6.dp) {
            Text("Бег с ходьбой", style = T.secondary(13.sp))
            Text("Неделя ${st.walkStage / 3 + 1} из 9", style = T.title(26.sp).copy(color = F.c.endurance))
            Text("Цель программы — 30 минут бега без остановки. Потом приложение рассчитает темпы.", style = T.secondary(13.sp))
        }
    } else {
        val g = profile?.runGoal
        Empty(
            if (g?.middleDistance == true) "Темпы и прогнозы появятся после контрольного забега на ${goalDistance(g.meters)} — он встанет в план после пары пробежек."
            else "Темпы и прогнозы появятся после контрольного забега (12 минут) — он встанет в план после пары пробежек. Можно указать недавний результат в профиле.",
        )
    }
    // Weekly distance and time.
    val dayOf = workouts.associate { it.id to it.epochDay }
    val weeks = 8
    val monday = Stats.monday(today).minusWeeks((weeks - 1).toLong()).toEpochDay()
    val km = DoubleArray(weeks)
    val minutes = DoubleArray(weeks)
    runs.forEach { r ->
        val d = dayOf[r.workoutId] ?: return@forEach
        val w = ((d - monday) / 7).toInt()
        if (d >= monday && w in 0 until weeks) {
            km[w] += (r.distanceM ?: 0.0) / 1000
            minutes[w] += r.movingSec / 60.0
        }
    }
    if (runs.isNotEmpty()) {
        SectionLabel("Объём бега по неделям")
        FCard(gap = 6.dp) {
            val useKm = km.sum() > 0.5
            Text(if (useKm) "${Ru.one(km.last())} км на этой неделе" else "${minutes.last().roundToInt()} мин на этой неделе", style = T.title(20.sp))
            BarChart(
                if (useKm) km.toList() else minutes.toList(), F.c.endurance.copy(alpha = 0.55f), highlight = F.c.endurance,
                xLabels = (0 until weeks).map { if (it == 0 || it == weeks - 1) shortDate(LocalDate.ofEpochDay(monday + it * 7L)) else "" },
                description = "Беговой объём за 8 недель",
            )
            Text("Длинная пробежка растёт не больше чем на 10 % от самой длинной за 30 дней — так меньше риск травм.", style = T.secondary(12.sp))
        }
    }
}

@Composable
private fun LoadTab() {
    val repo = LocalRepo.current
    val loads by remember { repo.dailyLoads() }.collectAsState(initial = emptyMap())
    val rows by remember { repo.setRows() }.collectAsState(initial = emptyList())
    val profile by repo.store.profile.collectAsState(initial = null)
    val today = forma.app.ui.rememberToday(repo)
    if (loads.isEmpty()) {
        Empty("Нагрузка = оценка тяжести тренировки (1–10) × минуты. Из неё считаются форма (42 дня) и усталость (7 дней).")
        return
    }
    val series = remember(loads, today) { TrainingLoad.series(loads, today.minusDays(120), today) }
    val history = remember(loads, today) { TrainingLoad.hasHistory(loads, today) }
    val last = series.takeLast(56)
    FCard(gap = 6.dp) {
        val now = series.last()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric3("Форма", now.fitness.roundToInt().toString(), F.c.endurance, Modifier.weight(1f))
            Metric3("Усталость", now.fatigue.roundToInt().toString(), F.c.load, Modifier.weight(1f))
            // A few sessions say nothing about freshness yet (one hard day reads as −100 %).
            if (history) Metric3("Свежесть", (if (now.formPct >= 0) "+" else "") + now.formPct.roundToInt() + "%", if (now.formPct >= -30) F.c.good else F.c.warn, Modifier.weight(1f))
            else Metric3("Свежесть", "—", F.c.text3, Modifier.weight(1f))
        }
        LineChart(
            listOf(Series(last.map { it.fitness }, F.c.endurance), Series(last.map { it.fatigue }, F.c.load, width = 2.dp)),
            xLabels = listOf(shortDate(last.first().date), shortDate(last.last().date)), description = "Форма и усталость за 8 недель", yMin = 0.0,
        )
        Text("Форма — средняя нагрузка за 6 недель, усталость — за неделю. Свежесть = (форма − усталость) / форма. Ниже −30 % — пора дать телу отдых, выше +10 % — готов к тяжёлой неделе.", style = T.secondary(12.sp))
    }
    val weekly = (0 until 8).map { w -> series.filter { (today.toEpochDay() - it.date.toEpochDay()) / 7 == (7 - w).toLong() }.sumOf { it.load } }
    SectionLabel("Нагрузка по неделям")
    FCard(gap = 6.dp) {
        val prev = weekly.dropLast(1).takeLast(4).filter { it > 0 }.average().takeIf { !it.isNaN() }
        val change = prev?.let { (weekly.last() / it - 1) * 100 }
        Text("${weekly.last().roundToInt()} за 7 дней" + (change?.let { " · ${if (it >= 0) "+" else ""}${it.roundToInt()} % к среднему" } ?: ""), style = T.title(18.sp))
        BarChart(weekly, F.c.load.copy(alpha = 0.5f), highlight = F.c.load, description = "Нагрузка за 8 недель")
    }
    val sets = remember(rows, today) { Stats.weeklySets(rows, today, 1) }
    // Weekly hard sets per muscle that suit the gym focus (more for hypertrophy, fewer heavy ones for strength).
    val target = when (profile?.aim?.lift) {
        LiftFocus.MUSCLE -> 10.0 to 20.0
        LiftFocus.POWERBUILDING -> 8.0 to 16.0
        LiftFocus.STRENGTH -> 6.0 to 12.0
        else -> 6.0 to 14.0
    }
    SectionLabel("Подходы на мышцу за неделю")
    FCard(gap = 10.dp) {
        Text("Полоса — разумный диапазон для твоей цели (${target.first.roundToInt()}–${target.second.roundToInt()}). Подход для вспомогательной мышцы считается за половину.", style = T.secondary(12.sp))
        Muscle.entries.forEach { m ->
            val v = sets[m]?.last() ?: 0.0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(m.ru, style = T.body(13.sp), modifier = Modifier.width(110.dp))
                RangeBar(v, 24.0, target.first, target.second, F.c.strength, Modifier.weight(1f))
                NumText(Ru.one(v), style = T.number(13.sp, FontWeight.Bold), modifier = Modifier.width(36.dp))
            }
        }
    }
}

@Composable
private fun Metric3(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        NumText(value, style = T.number(24.sp).copy(color = color))
        Text(label, style = T.secondary(12.sp))
    }
}

@Composable
private fun BodyTab() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val list by remember { repo.db.state().observeBodyWeight() }.collectAsState(initial = emptyList())
    if (list.isEmpty()) {
        Empty("Записывай вес раз в неделю — приложение учтёт его в силе подтягиваний и относительной силе.")
    } else {
        FCard(gap = 6.dp) {
            Text("Вес тела", style = T.secondary(13.sp))
            NumText("${Ru.kg(list.last().kg)} кг", style = T.number(40.sp))
            if (list.size >= 2) {
                val d = list.last().kg - list.first().kg
                Text("${if (d >= 0) "+" else ""}${Ru.kg(d)} кг с ${shortDate(LocalDate.ofEpochDay(list.first().epochDay))}", style = T.secondary(13.sp))
                LineChart(listOf(Series(list.map { it.kg }, F.c.text)), description = "Вес тела", xLabels = listOf(shortDate(LocalDate.ofEpochDay(list.first().epochDay)), shortDate(LocalDate.ofEpochDay(list.last().epochDay))))
            }
        }
    }
    GhostButton("Записать вес", { nav.push(Screen.Body) }, Modifier.fillMaxWidth(), height = 52.dp)
}

@Composable
fun Empty(text: String) {
    FCard { Text(text, style = T.secondary(14.sp)) }
}
