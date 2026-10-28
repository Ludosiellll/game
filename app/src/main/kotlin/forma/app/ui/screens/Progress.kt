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
import forma.core.load.LoadExplain
import forma.core.load.LoadStory
import forma.core.load.TrainingLoad
import forma.core.model.Catalog
import forma.core.model.LiftFocus
import forma.core.model.Muscle
import forma.core.run.PaceTable
import forma.core.run.RunPlan
import forma.core.run.Vdot
import forma.core.text.Ru
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun monthLabels(points: List<Point>): List<String> {
    if (points.size < 2) return emptyList()
    val a = LocalDate.ofEpochDay(points.first().epochDay)
    val b = LocalDate.ofEpochDay(points.last().epochDay)
    val m = LocalDate.ofEpochDay((points.first().epochDay + points.last().epochDay) / 2)
    return listOf(shortDate(a), shortDate(m), shortDate(b))
}

/** The tab Progress opens on next, asked for by a tile of the main screen; taken once. */
internal var progressTabRequest: Int? = null

@Composable
fun ProgressScreen(initialTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(progressTabRequest?.also { progressTabRequest = null } ?: initialTab) }
    ScrollScreen {
        Text(if (F.type.capsTitles) "ПРОГРЕСС" else "Прогресс", style = T.screenTitle(), modifier = Modifier.padding(top = 8.dp))
        ScreenHint(Hints.PROGRESS, Hints.PROGRESS_TEXT)
        Segmented(listOf("Сила", "Бег", "Нагрузка", "Тело"), tab, { tab = it })
        when (tab) {
            0 -> StrengthTab()
            1 -> RunTab()
            2 -> LoadTab()
            else -> BodyTab()
        }
        ReportCard()
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
    val aimLifts = repo.store.profile.collectAsState(initial = null).value?.aim?.lifts
    if (aimLifts == true) GymGoalCard()
    if (rows.isEmpty() && aimLifts == false) {
        Empty("В твоей цели нет зала, поэтому здесь пусто. Захочешь добавить силовые — поменяй цель в «Ещё» → «Цель, дни и данные».")
        return
    }
    if (rows.isEmpty()) {
        Empty("Графики силы появятся после первых тренировок. По каждому подходу приложение считает вес, который ты поднял бы на 1 раз. Так рост видно, даже если вес на штанге тот же.")
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
        Text("100 — твой старт. Считается по весу на 1 раз в каждом упражнении.", style = T.secondary(12.sp))
    }
    val outgrew by androidx.compose.runtime.produceState(false, rows) { value = repo.outgrewNovice() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (outgrew) FCard(gap = 6.dp) {
        Text("Ты уже не новичок", style = T.title(18.sp))
        Text(
            "Основные упражнения уже не растут от тренировки к тренировке. Значит, время новичка закончилось. " +
                "Дальше лучше подойдёт план для среднего уровня: циклы по несколько недель с лёгкой неделей в конце. " +
                "Вес будет расти реже, зато дольше. Прогресс по упражнениям сохранится.",
            style = T.body(14.sp),
        )
        GhostButton("Перейти на средний уровень", {
            scope.launch { repo.setExperience(forma.core.model.Experience.INTERMEDIATE) }
        }, Modifier.fillMaxWidth())
    }
    val bias by androidx.compose.runtime.produceState(0, rows) { value = repo.rirBias() }
    forma.core.strength.RirScale.explain(bias)?.let { text ->
        FCard(gap = 4.dp) {
            Text("Твоя шкала запаса", style = T.secondary(13.sp))
            Text(text, style = T.body(14.sp))
        }
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
                    Text(
                        when {
                            ex.load == forma.core.model.LoadKind.TIMED -> "время удержания"
                            ex.perHand && ex.load == forma.core.model.LoadKind.EXTERNAL -> "вес на 1 раз по расчёту, на гантель"
                            else -> "вес на 1 раз по расчёту"
                        }, style = T.secondary(12.sp),
                    )
                }
                Sparkline(series.map { it.value }, F.c.strength, Modifier.size(64.dp, 24.dp).padding(end = 8.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(80.dp)) {
                    val unit = if (timed) " с" else " кг"
                    NumText(cur?.let { (if (timed) "${it.roundToInt()}" else Ru.kg((it * 2).roundToInt() / 2.0)) + unit } ?: "—", style = T.number(17.sp))
                    if (delta != null) {
                        // Shown as rounded: what reads as no change is not coloured as growth.
                        val shown = if (timed) kotlin.math.abs(delta).roundToInt().toDouble() else (kotlin.math.abs(delta) * 2).roundToInt() / 2.0
                        val zero = shown == 0.0
                        NumText(
                            (if (zero) "= " else if (delta > 0) "▲ +" else "▼ −") + (if (timed) "${shown.roundToInt()}" else Ru.kg(shown)) + unit,
                            style = T.number(11.sp, FontWeight.Bold).copy(color = if (zero) F.c.text2 else if (delta > 0) F.c.good else F.c.warn),
                        )
                    }
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
            "Темп ${Ru.pace(goal.pace)} /км" + (lap?.let { " · " + Ru.nobreak("круг $it м — ${lapTenth(forma.core.run.Track.lapSec(goal.pace, it))}") } ?: ""),
            style = T.body(15.sp, FontWeight.SemiBold),
        )
        when {
            !hard -> Text(
                "В неделе нет тяжёлых пробежек, а без них к забегу не подготовиться. Добавь день или выбери бег «Скорость».",
                style = T.body(14.sp).copy(color = F.c.warn),
            )
            now == null -> Text("Прогноз появится после контрольного забега. Приложение добавит его в план через пару пробежек.", style = T.secondary(13.sp))
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

/**
 * The goal to run a distance without stopping (no time): where the run/walk start is, or how far the longest run has
 * gone; reached — a time can be set next.
 */
@Composable
private fun FinishCard(meters: Int, st: forma.core.run.RunState?, runs: List<forma.app.data.RunEntity>) {
    val best = runs.filter { it.type != forma.core.run.RunType.RUN_WALK.name }.mapNotNull { it.distanceM }.maxOrNull()
    FCard(gap = 6.dp) {
        Text("Цель", style = T.secondary(13.sp))
        Text("Пробежать ${goalDistance(meters)}", style = T.title(26.sp).copy(color = F.c.endurance))
        Text(
            when {
                best != null && best >= meters -> "Взята: самая длинная пробежка — ${goalDistance(best.roundToInt())}. " +
                    "Теперь можно поставить время в профиле, и план пойдёт к нему."
                st != null && !st.canRunContinuously -> "Сейчас бег с ходьбой: этап ${st.walkStage + 1} из ${RunPlan.walkLadderSize}. " +
                    "В конце — 30 минут бега без остановки, это примерно 4–5 км."
                best != null -> "Самая длинная пробежка — ${goalDistance(best.roundToInt())} из ${goalDistance(meters)}. Длинная пробежка недели растёт понемногу."
                else -> "Длинная пробежка недели растёт понемногу. Пробегай её спокойно, чтобы мог говорить."
            },
            style = T.body(14.sp).let { if (best != null && best >= meters) it.copy(color = F.c.good) else it },
        )
    }
}

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
            // Over the goal distance itself: on a 230 m track the trial is 6.5 laps, 1495 m.
            val best = trials.minBy { it.goalSeconds }
            val gap = (best.goalSeconds - goal.seconds).roundToInt()
            val reached = gap <= 0
            Text("Лучший контрольный — ${Ru.clock(best.goalSeconds.roundToInt())}", style = T.title(22.sp).copy(color = if (reached) F.c.good else F.c.text))
            Text(
                if (reached) "Цель взята ${shortDate(LocalDate.ofEpochDay(best.epochDay))}." else "До цели ${if (gap < 60) "$gap с" else Ru.clock(gap)}.",
                style = T.body(14.sp),
            )
            trials.lastOrNull { it.meters != goal.meters }?.let { t ->
                Text(
                    "На стадионе контрольный идёт целыми кругами, у тебя это ${t.meters} м. Время пересчитано на ${goalDistance(goal.meters)}.",
                    style = T.secondary(12.sp),
                )
            }
            if (trials.size >= 2) {
                LineChart(
                    listOf(Series(trials.map { it.goalSeconds }, F.c.endurance)), height = 110.dp, reference = goal.seconds.toDouble(),
                    xLabels = trials.indices.map { if (it == 0 || it == trials.lastIndex) shortDate(LocalDate.ofEpochDay(trials[it].epochDay)) else "" },
                    description = "Контрольные забеги на ${goalDistance(goal.meters)}: время по датам, линия — цель",
                )
                Text("Чем ниже, тем быстрее. Серая линия — цель.", style = T.secondary(12.sp))
            }
            trials.takeLast(4).reversed().forEachIndexed { i, t ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(shortDate(LocalDate.ofEpochDay(t.epochDay)), style = T.body(14.sp), modifier = Modifier.weight(1f))
                    if (t.meters != goal.meters) Text("${Ru.clock(t.seconds)} за ${t.meters} м", style = T.secondary(13.sp), modifier = Modifier.padding(end = 12.dp))
                    NumText(Ru.clock(t.goalSeconds.roundToInt()), style = T.number(15.sp, FontWeight.Bold))
                }
            }
        }
        Hairline()
        Text("Отрезки в темпе цели · круг ${Ru.nobreak(lapTenth(target))}", style = T.secondary(13.sp))
        val sessions = path.sessions
        if (sessions.isEmpty()) {
            Text(
                "После первой такой тренировки здесь появится средний круг отрезков и сколько кругов ты прошёл в темпе. " +
                    "Считается по нажатиям «Круг». Если бежал с часами — по среднему кругу, который впишешь.",
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
                Text("Чем ниже, тем быстрее. Серая линия — круг цели.", style = T.secondary(12.sp))
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
    profile?.finish?.let { FinishCard(it, st, runs) }
    // On a stadium: the trials over the goal distance and how the laps at its pace go.
    val goal = profile?.runGoal
    val lapM = profile?.lapMeters
    if (goal != null && goal.middleDistance && lapM != null) {
        val path = remember(workouts, runs, goal.meters) { forma.app.data.GoalProgress.of(workouts, runs, goal.meters) }
        GoalPathCard(goal, lapM, path)
    }
    if (vdot != null) {
        FCard(gap = 6.dp) {
            Text("Форма бега (VDOT)", style = T.secondary(13.sp))
            NumText(Ru.one(vdot), style = T.number(44.sp).copy(color = F.c.endurance))
            val middle = profile?.runGoal?.middleDistance == true
            Text(
                if (middle) "Чем выше, тем быстрее ты бегаешь. Меняется после контрольных забегов на дистанцию цели. Считается по формулам Дэниелса."
                else "Чем выше, тем быстрее ты бегаешь. Меняется после контрольных забегов и когда ты с запасом держишь темп в тяжёлых пробежках. " +
                    "Считается по формулам Дэниелса.",
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
        Text("Это время, если готовиться именно к этой дистанции. Для длинных нужны длинные пробежки, без них прогноз слишком хороший.", style = T.secondary(12.sp))
        val p = PaceTable.forVdot(vdot)
        SectionLabel("Темпы, мин/км")
        FCard(gap = 0.dp) {
            listOf(
                "Лёгкий" to "${Ru.pace(p.easy.fast.toDouble())}–${Ru.pace(p.easy.slow.toDouble())}",
                "Марафонский" to Ru.pace(p.marathon.toDouble()),
                "Темповый" to Ru.pace(p.threshold.toDouble()),
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
            if (g?.middleDistance == true) "Темпы и прогнозы появятся после контрольного забега на ${goalDistance(g.meters)}. Приложение добавит его в план через пару пробежек."
            else "Темпы и прогнозы появятся после контрольного забега на 12 минут. Приложение добавит его в план через пару пробежек. " +
                "Или впиши недавний забег в «Ещё» → «Цель, дни и данные».",
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
    // The pace of the runs without reps, run after run: the same easy effort getting quicker is the base growing.
    val calm = runs.filter { it.type in setOf("EASY", "LONG", "FREE") && (it.distanceM ?: 0.0) >= 1000 && (it.avgPace ?: 0.0) > 0 }.takeLast(12)
    if (calm.size >= 2) {
        SectionLabel("Темп пробежек")
        FCard(gap = 6.dp) {
            Text("Последняя — ${Ru.pace(calm.last().avgPace!!)} /км", style = T.title(18.sp))
            LineChart(
                listOf(Series(calm.map { it.avgPace!! }, F.c.endurance)), height = 110.dp,
                xLabels = calm.indices.map { i -> if (i == 0 || i == calm.lastIndex) dayOf[calm[i].workoutId]?.let { shortDate(LocalDate.ofEpochDay(it)) } ?: "" else "" },
                description = "Средний темп лёгкого, длинного и свободного бега по пробежкам",
            )
            Text("Лёгкий, длинный и свободный бег, без отрезков и контрольных. Чем ниже, тем быстрее. Если лёгкий бег становится быстрее, а легко так же, значит, растёт выносливость.", style = T.secondary(12.sp))
        }
    }
    if (runs.isNotEmpty()) {
        SectionLabel("Сколько бегал по неделям")
        FCard(gap = 6.dp) {
            val useKm = km.sum() > 0.5
            Text(if (useKm) "${Ru.one(km.last())} км на этой неделе" else "${minutes.last().roundToInt()} мин на этой неделе", style = T.title(20.sp))
            BarChart(
                if (useKm) km.toList() else minutes.toList(), F.c.endurance.copy(alpha = 0.55f), highlight = F.c.endurance,
                xLabels = (0 until weeks).map { if (it == 0 || it == weeks - 1) shortDate(LocalDate.ofEpochDay(monday + it * 7L)) else "" },
                description = "Беговой объём за 8 недель",
            )
            Text("Длинная пробежка растёт не больше чем на 10 % от самой длинной за 30 дней. Так меньше риск травм.", style = T.secondary(12.sp))
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
        Empty(LoadExplain.UNITS + " По нагрузке приложение считает тренированность за последние 6 недель, усталость за неделю и свежесть.")
        return
    }
    val series = remember(loads, today) { TrainingLoad.series(loads, today.minusDays(120), today) }
    val story by remember(today) { repo.loadStory(today) }.collectAsState(initial = null)
    val st = story ?: return
    val last = series.takeLast(56)
    val line = st.personal.restBelow
    // The verdict first: what the numbers mean for today.
    st.zone?.let { z ->
        FCard(gap = 6.dp) {
            Text("Сейчас", style = T.secondary(13.sp))
            Text(
                LoadExplain.zoneTitle(z), style = T.title(24.sp).copy(
                    color = zoneColor(z),
                ),
            )
            Text(LoadExplain.zoneText(st), style = T.body(14.sp))
        }
    }
    FCard(gap = 8.dp) {
        val now = series.last()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric3("Тренированность", now.fitness.roundToInt().toString(), F.c.endurance, Modifier.weight(1f))
            Metric3("Усталость", now.fatigue.roundToInt().toString(), F.c.load, Modifier.weight(1f))
            // A few sessions say nothing about freshness yet (one hard day reads as −100 %).
            if (st.history) Metric3("Свежесть", Ru.pct(now.formPct), zoneColor(LoadStory.zone(now.formPct, line)), Modifier.weight(1f))
            else Metric3("Свежесть", "—", F.c.text3, Modifier.weight(1f))
        }
        LineChart(
            listOf(Series(last.map { it.fitness }, F.c.endurance), Series(last.map { it.fatigue }, F.c.load, width = 2.dp)),
            xLabels = listOf(shortDate(last.first().date), shortDate(last.last().date)), description = "Тренированность и усталость за 8 недель",
        )
        Text("Синяя линия — тренированность, оранжевая — усталость. Если усталость выше, ты нагружаешься больше привычного.", style = T.secondary(12.sp))
        Explained("Тренированность", LoadExplain.fitness(st))
        Explained("Усталость", LoadExplain.fatigue(st))
        Explained("Свежесть", LoadExplain.freshness(st))
    }
    if (st.history) {
        SectionLabel("Свежесть и твоя линия отдыха")
        FCard(gap = 6.dp) {
            // From two weeks after the first session: before that freshness is not yet a number to read.
            val from = loads.filter { it.value > 0 }.keys.minOrNull()?.plusDays(14)
            val fresh = last.filter { from != null && !it.date.isBefore(from) }.ifEmpty { last.takeLast(1) }
            LineChart(
                listOf(Series(fresh.map { it.formPct }, F.c.accent)), height = 110.dp, reference = line,
                xLabels = listOf(shortDate(fresh.first().date), shortDate(fresh.last().date)), description = "Свежесть по дням, линия — линия отдыха",
            )
            Text("Серая линия — ${Ru.pct(line)}. Если свежесть ниже неё, нужен отдых.", style = T.secondary(12.sp))
            Text(LoadExplain.personal(st.personal), style = T.body(14.sp))
        }
        SectionLabel("Когда был нужен отдых")
        FCard(gap = 6.dp) {
            if (st.dips.isEmpty()) Text(LoadExplain.noDips(8), style = T.body(14.sp))
            else st.dips.reversed().forEachIndexed { i, d ->
                if (i > 0) Hairline()
                Text(LoadExplain.dip(d, st.today.formPct), style = T.body(14.sp).copy(color = if (d.recovered == null) F.c.warn else F.c.text))
            }
        }
    }
    val weekly = st.weeks.map { it.load }
    val cur = st.weeks.lastOrNull()
    if (cur != null) SectionLabel("Нагрузка по неделям")
    if (cur != null) FCard(gap = 6.dp) {
        Text("${LoadExplain.n(cur.load)} на этой неделе · ${LoadExplain.level(cur)}", style = T.title(18.sp))
        BarChart(
            weekly, F.c.load.copy(alpha = 0.5f), highlight = F.c.load, description = "Нагрузка за 8 недель",
            xLabels = st.weeks.indices.map { if (it == 0 || it == st.weeks.lastIndex) shortDate(st.weeks[it].monday) else "" },
        )
        LoadExplain.usual(st)?.let { Text(it, style = T.secondary(12.sp)) }
        st.weeks.reversed().forEach { w ->
            Hairline()
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Ru.dates(w.monday, w.monday.plusDays(6)), style = T.body(14.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    Text(
                        LoadExplain.level(w), style = T.body(13.sp, FontWeight.SemiBold).copy(
                            color = when (w.level) {
                                LoadStory.Level.SPIKE -> F.c.warn
                                LoadStory.Level.MORE -> F.c.load
                                LoadStory.Level.NONE, LoadStory.Level.LIGHT, LoadStory.Level.ONGOING -> F.c.text2
                                else -> F.c.text
                            },
                        ),
                    )
                }
                Text(LoadExplain.week(w), style = T.secondary(12.sp))
                LoadExplain.levelNote(w)?.let { Text(it, style = T.secondary(12.sp)) }
            }
        }
    }
    FCard(gap = 4.dp) {
        Text("Откуда числа", style = T.secondary(13.sp))
        Text(LoadExplain.UNITS, style = T.body(13.sp))
    }
    val sets = remember(rows, today) { Stats.setsLast7Days(rows, today) }
    // Weekly hard sets per muscle that suit the gym focus (more for hypertrophy, fewer heavy ones for strength).
    val target = when (profile?.aim?.lift) {
        LiftFocus.MUSCLE -> 10.0 to 20.0
        LiftFocus.POWERBUILDING -> 8.0 to 16.0
        LiftFocus.STRENGTH -> 6.0 to 12.0
        else -> 6.0 to 14.0
    }
    // Nothing lifted lately: no table of zeros.
    if (sets.values.none { it > 0 }) return
    SectionLabel("Подходы на мышцу за 7 дней")
    FCard(gap = 10.dp) {
        Text("Считаются последние 7 дней вместе с сегодня. Полоса — сколько подходов в неделю хорошо для твоей цели: ${target.first.roundToInt()}–${target.second.roundToInt()}. Если мышца в упражнении только помогает, подход идёт за половину.", style = T.secondary(12.sp))
        Muscle.entries.forEach { m ->
            val v = sets[m] ?: 0.0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(m.ru, style = T.body(13.sp), modifier = Modifier.width(110.dp))
                RangeBar(v, 24.0, target.first, target.second, F.c.strength, Modifier.weight(1f))
                NumText(Ru.one(v), style = T.number(13.sp, FontWeight.Bold), modifier = Modifier.width(36.dp))
            }
        }
    }
}

/** A term and what it means here. */
@Composable
private fun Explained(term: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(term, style = T.body(14.sp, FontWeight.SemiBold))
        Text(text, style = T.secondary(13.sp))
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
        Empty("Записывай вес тела раз в неделю. Приложение учтёт его, когда считает силу в подтягиваниях и силу относительно твоего веса.")
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
