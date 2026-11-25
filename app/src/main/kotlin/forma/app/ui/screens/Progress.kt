package forma.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
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
import androidx.compose.runtime.mutableStateOf
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
import forma.app.ui.components.FitTitle
import forma.app.ui.components.BarChart
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.LineChart
import forma.app.ui.components.RangeBar
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.Tone
import forma.app.ui.components.TrendChart
import forma.app.ui.components.WeekBars
import forma.app.ui.components.Series
import forma.app.ui.components.Sparkline
import forma.app.ui.components.StatTile
import forma.app.ui.components.NumText
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.app.ui.theme.labelText
import forma.app.ui.theme.titleText
import forma.core.analytics.Point
import forma.core.analytics.SetRow
import forma.core.analytics.BodyTrend
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

/** The section Progress brings into view next, asked for by a tile of the main screen (2: load); taken once. */
internal var progressTabRequest: Int? = null

/** The window every chart here covers. */
private const val WEEKS = 8

/**
 * «Прогресс» as one page of answers (23 November, Denis: «графики … перегруженные и непонятные»; a review counted 35
 * blocks in four tabs for four questions). Each section opens with its answer in large type — stronger, faster, where the
 * weight is going, how hard the weeks are — then one chart over the same eight weeks, then «Подробнее» with what the old
 * tabs showed. [initialTab]: the section to bring into view (0 strength, 1 running, 2 load, 3 weight), its details shut:
 * the answer and the chart first, as everywhere on the page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProgressScreen(initialTab: Int = 0) {
    val repo = LocalRepo.current
    val section = remember { progressTabRequest?.also { progressTabRequest = null } ?: initialTab }
    val today = forma.app.ui.rememberToday(repo)
    val from = today.minusWeeks(WEEKS.toLong()).toEpochDay()
    val workouts by remember { repo.db.workouts().observeHistory() }.collectAsState(initial = emptyList())
    val inWindow = workouts.count { it.epochDay > from }
    val first = workouts.minOfOrNull { it.epochDay }
    val requesters = remember { List(4) { BringIntoViewRequester() } }
    LaunchedEffect(section) { if (section in 1..3) { kotlinx.coroutines.delay(300); requesters[section].bringIntoView() } }
    ScrollScreen(gap = 20.dp) {
        FitTitle(titleText("Прогресс"), style = T.screenTitle(), modifier = Modifier.padding(top = 8.dp))
        if (first != null) Text(
            "$inWindow ${Ru.plural(inWindow, "тренировка", "тренировки", "тренировок")} " +
                if (first > from) "с ${shortDate(LocalDate.ofEpochDay(first))}" else "за $WEEKS недель",
            style = T.secondary(14.sp),
        )
        Alerts(today)
        Column(Modifier.bringIntoViewRequester(requesters[0]), verticalArrangement = Arrangement.spacedBy(12.dp)) { StrengthSection(today, from) }
        Column(Modifier.bringIntoViewRequester(requesters[1]), verticalArrangement = Arrangement.spacedBy(12.dp)) { RunSection(today, from) }
        Column(Modifier.bringIntoViewRequester(requesters[3]), verticalArrangement = Arrangement.spacedBy(12.dp)) { WeightSection(today, from) }
        Column(Modifier.bringIntoViewRequester(requesters[2]), verticalArrangement = Arrangement.spacedBy(12.dp)) { LoadSection(today) }
        MusclesSection(today)
        // Not progress: the export sits a tap away at the bottom.
        if (workouts.isNotEmpty()) { Hairline(); More("Выгрузить тренировки в файл") { ReportCard() } }
    }
}

/** A section's head: what it is about (small), the answer (large), one line under it. */
@Composable
private fun Answer(about: String, answer: String, line: String? = null, color: androidx.compose.ui.graphics.Color = F.c.text) {
    Hairline()
    Text(about, style = T.secondary(14.sp))
    // A number large; an answer in words a size down, so a sentence does not take the screen.
    Text(answer, style = T.title(if (answer.length > 14) 22.sp else 28.sp).copy(color = color))
    line?.let { Text(it, style = T.body(15.sp).copy(color = F.c.text2)) }
}

/** «Подробнее ›» that opens what follows it in place. */
@Composable
private fun More(label: String = "Подробнее", open: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var shown by rememberSaveable { mutableStateOf(open) }
    Text(
        if (shown) "Скрыть" else "$label ›", style = T.body(15.sp, FontWeight.SemiBold).copy(color = F.c.accent),
        modifier = Modifier.clickable(onClickLabel = if (shown) "Скрыть" else label) { shown = !shown }.padding(vertical = 6.dp),
    )
    if (shown) Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** What needs doing, said once at the top, only when it is so. */
@Composable
private fun Alerts(today: LocalDate) {
    val repo = LocalRepo.current
    val profile by repo.store.profile.collectAsState(initial = null)
    val program by repo.store.program.collectAsState(initial = null)
    val story by remember(today) { repo.loadStory(today) }.collectAsState(initial = null)
    val hard = program?.week?.days?.any { d -> d.sessions.any { it.kind == forma.core.plan.SessionKind.RUN_QUALITY } } != false
    val lines = listOfNotNull(
        "В неделе нет тяжёлых пробежек. К забегу так не подготовиться: добавь день в «Плане».".takeIf { profile?.runGoal != null && !hard },
        // Only a week above both the usual week and the plan's full week, said as what happened (research spike-label,
        // Denis's decision of 25 November: this line met his first full week by plan after a lighter and a thin one).
        story?.let { LoadExplain.alert(it) },
    )
    lines.forEach { Text(it, style = T.body(15.sp, FontWeight.SemiBold).copy(color = F.c.warn)) }
}

/**
 * A row: a name (and a [note] under it), its number, and beside it a change or a note in a column of its own; [onClick]:
 * what the row opens.
 */
@Composable
private fun SetsRow(name: String, value: String, change: String?, note: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { m -> if (onClick == null) m else m.clickable(onClickLabel = "Открыть: $name", onClick = onClick) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = T.body(15.sp))
            note?.let { Text(it, style = T.secondary(12.sp)) }
        }
        NumText(value, style = T.number(16.sp))
        if (change != null) Text(change, style = T.secondary(13.sp), maxLines = 1, softWrap = false, modifier = Modifier.width(84.dp).padding(start = 10.dp))
    }
}

/** A run of a list: its date, what was run (muted), the number that counts. */
@Composable
private fun RunRow(epochDay: Long, what: String?, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(shortDate(LocalDate.ofEpochDay(epochDay)), style = T.body(15.sp), modifier = Modifier.weight(1f))
        what?.let { Text(it, style = T.secondary(13.sp), modifier = Modifier.padding(end = 12.dp)) }
        NumText(value, style = T.number(16.sp))
    }
}

@Composable
private fun StrengthSection(today: LocalDate, from: Long) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val rows by remember { repo.setRows() }.collectAsState(initial = emptyList())
    val bw by remember { repo.bodyWeightLookup() }.collectAsState(initial = { _: Long -> 75.0 })
    val profile by repo.store.profile.collectAsState(initial = null)
    if (rows.isEmpty() && profile?.aim?.lifts == false) return
    if (rows.isEmpty()) {
        Answer("Сила", "Пока нет силовых", "После первой силовой здесь будет видно, как растут веса.")
        return
    }
    val index = remember(rows, bw) { Stats.indexSince(Stats.strengthIndex(rows, bw), from) }
    val change = index.lastOrNull()?.value
    val lastLift = rows.maxOf { it.epochDay }
    // Since a date, not «за N недель»: the index starts on the Sunday before the window, and a count of weeks came out
    // 7 or 9 under a header of 8.
    val since = index.firstOrNull()?.let { shortDate(LocalDate.ofEpochDay(it.epochDay)) }
    when {
        index.size >= 2 && change != null -> {
            Answer("Сила", Ru.pct(change), "С $since, в среднем по упражнениям")
            TrendChart(
                index, index.first().epochDay, today.toEpochDay(), "Сила с $since: ${Ru.pct(change)}",
                endLabel = Ru.pct(change), reference = 0.0, minSpan = 10.0, startLabel = since,
            )
        }
        lastLift <= from -> Answer("Сила", "Перерыв", "Последняя силовая ${shortDate(LocalDate.ofEpochDay(lastLift))}.")
        else -> Answer("Сила", "Первая неделя", "Рост будет виден со второй недели.")
    }
    // The lifts by how often they were done in the window, multi-joint first: the main ones.
    val lifts = remember(rows, bw, from) {
        rows.filter { it.epochDay > from }.groupBy { it.exerciseId }.mapNotNull { (id, r) ->
            val ex = Catalog.find(id) ?: return@mapNotNull null
            Triple(ex, r.map { it.workoutId }.distinct().size, liftRow(ex, rows.filter { it.exerciseId == id }, bw, from, today.toEpochDay()))
        }.filter { it.third != null }.sortedWith(compareBy({ if (it.first.compound) 0 else 1 }, { -it.second }))
    }
    if (lifts.isNotEmpty()) Text("Вес на 1 раз", style = T.secondary(13.sp))
    @Composable fun lift(ex: forma.core.model.Exercise, row: Pair<String, String?>) =
        SetsRow(ex.name, row.first, row.second, note = "на одну гантель".takeIf { ex.perHand }) { nav.push(Screen.Exercise(ex.id)) }
    lifts.take(3).forEach { (ex, _, row) -> lift(ex, row!!) }
    if (lifts.size > 3) More("Все упражнения") { lifts.drop(3).forEach { (ex, _, row) -> lift(ex, row!!) } }
    // The reserve read with a correction: said where the weights it chooses are seen (decision of 13 October: nothing changes silently).
    val bias by produceState(0, rows) { value = repo.rirBias() }
    if (bias > 0) Text("Ты обычно называешь запас на $bias больше, чем есть. Веса подбираются с учётом этого.", style = T.secondary(13.sp))
    if (profile?.liftGoal != null) GymGoalCard()
    else Text(
        "Поставить цель в зале ›", style = T.body(15.sp, FontWeight.SemiBold).copy(color = F.c.accent),
        modifier = Modifier.clickable(onClickLabel = "Поставить цель в зале") { nav.push(Screen.GymGoal) }.padding(vertical = 6.dp),
    )
    val outgrew by produceState(false, rows) { value = repo.outgrewNovice() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (outgrew) FCard(gap = 6.dp) {
        Text("Ты уже не новичок", style = T.title(18.sp))
        Text(
            "Основные упражнения перестали расти от тренировки к тренировке. Дальше лучше план среднего уровня: циклы по " +
                "несколько недель, последняя неделя в цикле лёгкая.",
            style = T.body(14.sp),
        )
        GhostButton("Перейти на средний уровень", { scope.launch { repo.setExperience(forma.core.model.Experience.INTERMEDIATE) } }, Modifier.fillMaxWidth())
    }
}

/**
 * One exercise as a row: its one-rep estimate now and the change over the window with its unit, by the index's own rule
 * ([Stats.liftChange]); a hold in seconds. Null when it was not done in the last four weeks.
 */
internal fun liftRow(ex: forma.core.model.Exercise, rows: List<SetRow>, bw: (Long) -> Double, from: Long, today: Long): Pair<String, String?>? {
    val timed = ex.load == forma.core.model.LoadKind.TIMED
    val series = if (timed) rows.filter { it.reps > 0 }.groupBy { it.workoutId }
        .map { (_, s) -> Point(s.first().epochDay, s.maxOf { it.reps }.toDouble()) }.sortedBy { it.epochDay }
    else Stats.e1rmSeries(rows, ex, bw)
    val (now, delta) = Stats.liftChange(series, from, today) ?: return null
    fun kg(v: Double) = Ru.kg((v * 2).roundToInt() / 2.0)
    val value = when {
        timed -> "${now.roundToInt()} с"
        // A lift with the body: what is added to it, not the body counted in — less the share of the body it moves, not
        // all of it (leg raises move a third: «свой вес» whatever was done; help on pull-ups read «свой вес»).
        ex.load == forma.core.model.LoadKind.BODYWEIGHT_PLUS -> forma.app.ui.Texts.oneRm(ex, now, bw(today))
        else -> "${kg(now)} кг"
    }
    val shown = if (timed) delta.roundToInt().toDouble() else (delta * 2).roundToInt() / 2.0
    val sign = if (shown > 0) "+" else if (shown < 0) "−" else ""
    val change = if (timed) "$sign${kotlin.math.abs(shown).roundToInt()} с" else "$sign${kg(kotlin.math.abs(shown))} кг"
    return value to change
}

@Composable
private fun RunSection(today: LocalDate, from: Long) {
    val repo = LocalRepo.current
    val runState by repo.store.runState.collectAsState(initial = null)
    val runs by remember { repo.db.runs().observeAll() }.collectAsState(initial = emptyList())
    val workouts by remember { repo.db.workouts().observeHistory() }.collectAsState(initial = emptyList())
    val profile by repo.store.profile.collectAsState(initial = null)
    val st = runState?.let { RunPlan.current(it, profile?.runGoal, today.toEpochDay()) }
    val goal = profile?.runGoal
    val lapM = profile?.lapMeters
    if (goal == null && profile?.finish == null && runs.isEmpty()) return
    val dayOf = workouts.associate { it.id to it.epochDay }
    val finish = profile?.finish
    if (finish != null || (st != null && !st.canRunContinuously)) {
        FinishAnswer(finish, st, runs)
        RunWeeks(runs, dayOf, today)
        return
    }
    val path = remember(workouts, runs, goal?.meters) { goal?.let { forma.app.data.GoalProgress.of(workouts, runs, it.meters) } }
    // The reps at the goal's pace, session by session: the one measured answer to «am I faster?».
    val sessions = path?.sessions.orEmpty().filter { it.epochDay > from }
    val target = if (goal != null && goal.middleDistance && lapM != null) forma.core.run.Track.lapSec(goal.pace, lapM) else null
    fun lap(s: forma.app.data.GoalProgress.Session) = s.paceSecKm * lapM!! / 1000.0
    val forecast = goal?.let { g -> st?.fitness?.let { Vdot.predictSeconds(it, g.meters.toDouble()) } }
    var forecastSaid = false
    if (goal != null) {
        val dist = if (goal.meters <= 3000) "${goal.meters} м" else goalDistance(goal.meters)
        val trial = path?.trials?.filter { it.epochDay > from }?.minByOrNull { it.goalSeconds }
        fun left(sec: Double) = (sec - goal.seconds).roundToInt().let { g -> if (g < 60) "$g с" else Ru.clock(g) }
        // What was run first — the best trial of the window, then the laps of the reps at the goal's pace; the forecast
        // only before either, said as one (a review on 23 November: «≈ 6:26» was the starting guess from the goal itself,
        // unmoved in eight weeks, over a chart of laps getting faster).
        when {
            trial != null -> {
                val reached = trial.goalSeconds <= goal.seconds + 0.5
                Answer(
                    "Бег · лучший контрольный на $dist", Ru.clock(trial.goalSeconds.roundToInt()),
                    "Цель ${Ru.clock(goal.seconds)}" +
                        if (reached) " взята ${shortDate(LocalDate.ofEpochDay(trial.epochDay))}." else ", осталось ${left(trial.goalSeconds)}.",
                    color = if (reached) F.c.good else F.c.text,
                )
            }
            target != null && sessions.isNotEmpty() -> Answer(
                "Бег · $dist за ${Ru.clock(goal.seconds)}", "Круг ${lapTenth(lap(sessions.last()))}",
                "В отрезках в темпе цели. Нужно ${lapTenth(target)}.",
            )
            forecast != null -> {
                forecastSaid = true
                Answer(
                    "Бег · прогноз на $dist", "≈ ${Ru.clock(forecast.roundToInt())}",
                    "Цель ${Ru.clock(goal.seconds)}" + if (forecast <= goal.seconds + 0.5) ", по силам." else ", осталось ${left(forecast)}.",
                )
            }
            else -> Answer("Бег", "$dist за ${Ru.clock(goal.seconds)}", "Прогноз появится после первых пробежек.")
        }
    } else {
        val window = runs.filter { (dayOf[it.workoutId] ?: 0) > from }
        val km = window.sumOf { it.distanceM ?: 0.0 } / 1000
        Answer(
            "Бег", if (km > 0) "${Ru.one(km)} км" else "${window.sumOf { it.movingSec } / 60} мин",
            "За $WEEKS недель, ${window.size} ${Ru.plural(window.size, "пробежка", "пробежки", "пробежек")}",
        )
    }
    if (target != null) {
        if (sessions.size >= 2) TrendChart(
            sessions.map { Point(it.epochDay, lap(it)) }, sessions.first().epochDay, today.toEpochDay(),
            "Средний круг в отрезках в темпе цели, последний ${lapTenth(lap(sessions.last()))}, нужно ${lapTenth(target)}",
            endLabel = lapTenth(lap(sessions.last())), reference = target, referenceLabel = "цель ${Ru.nobreak(lapTenth(target))}",
            invert = true, minSpan = 4.0, startLabel = shortDate(LocalDate.ofEpochDay(sessions.first().epochDay)),
        )
        if (sessions.isEmpty()) Text("За $WEEKS недель отрезков в темпе цели не было.", style = T.secondary(14.sp))
        st?.let { LadderLine(it, today) }
    } else RunWeeks(runs, dayOf, today)
    More("Подробнее о беге") {
        RunDetails(st, goal, lapM, path, runs, dayOf, today, forecast.takeUnless { forecastSaid }, weeks = target != null)
    }
}

/** Where the reps at the goal's pace are on the way to the trial: the step of the ladder, or the trial next. */
@Composable
private fun LadderLine(st: forma.core.run.RunState, today: LocalDate) {
    val day = today.toEpochDay()
    // A trial tried lately (stopped, not counted) comes again only two weeks after it.
    val again = st.lastTrialTryDay?.plus(14)?.takeIf { it > day }
    Text(
        when {
            st.testSoon && again == null -> "Следующая тяжёлая пробежка: контрольный забег."
            st.testSoon -> "Контрольный забег не раньше ${shortDate(LocalDate.ofEpochDay(again!!))}."
            else -> "Шаг ${st.goalStage + 1} из ${RunPlan.goalLadderSize} до контрольного забега."
        },
        style = T.secondary(14.sp),
    )
}

/**
 * Kilometres a week over the window, this week last; minutes when a run in it has no distance (by time, no GPS, or entered
 * by hand with the time alone): in kilometres such a run added nothing, and a week of only such runs read «0,0».
 */
@Composable
private fun RunWeeks(runs: List<forma.app.data.RunEntity>, dayOf: Map<Long, Long>, today: LocalDate, titled: Boolean = false) {
    val monday = Stats.monday(today).minusWeeks((WEEKS - 1).toLong()).toEpochDay()
    val km = DoubleArray(WEEKS)
    val min = DoubleArray(WEEKS)
    var unmeasured = false
    runs.forEach { r ->
        val d = dayOf[r.workoutId] ?: return@forEach
        val w = ((d - monday) / 7).toInt()
        if (d >= monday && w in 0 until WEEKS) {
            km[w] += (r.distanceM ?: 0.0) / 1000
            min[w] += r.movingSec / 60.0
            if ((r.distanceM ?: 0.0) <= 0.0 && r.movingSec > 0) unmeasured = true
        }
    }
    val useKm = km.sum() > 0.0 && !unmeasured
    if (!useKm && min.sum() <= 0.0) return
    val what = if (useKm) "Километры по неделям" else "Минуты по неделям"
    if (titled) Text(what, style = T.secondary(14.sp))
    WeekBars(
        (if (useKm) km else min).toList(),
        "$what, на этой неделе " + if (useKm) Ru.one(km.last()) else min.last().roundToInt().toString(),
        startLabel = shortDate(LocalDate.ofEpochDay(monday)),
    )
}

/** What the running tab showed, as lists: the forecast when not said above, trials, sessions of reps, paces, the weeks. */
@Composable
private fun RunDetails(
    st: forma.core.run.RunState?, goal: forma.core.model.RunGoal?, lapM: Int?, path: forma.app.data.GoalProgress.Result?,
    runs: List<forma.app.data.RunEntity>, dayOf: Map<Long, Long>, today: LocalDate, forecast: Double?, weeks: Boolean,
) {
    if (goal != null && forecast != null) {
        val dist = if (goal.meters <= 3000) "${goal.meters} м" else goalDistance(goal.meters)
        SetsRow("Прогноз на $dist", (if (st?.estimated == true) "≈ " else "") + Ru.clock(forecast.roundToInt()), null)
        if (st?.estimated == true) Text("Это оценка по цели и пробежкам. Уточнится после контрольного забега.", style = T.secondary(13.sp))
    }
    path?.trials?.takeIf { it.isNotEmpty() && goal != null }?.let { trials ->
        // On a track the trial is run in whole laps (1435 m on 205 m): the time run, and the time over the goal distance.
        val laps = trials.any { it.meters != goal!!.meters }
        Text(if (laps) "Контрольные, время на ${goal!!.meters} м" else "Контрольные", style = T.secondary(14.sp))
        trials.takeLast(4).reversed().forEach { t ->
            RunRow(t.epochDay, if (t.meters != goal!!.meters) "${Ru.clock(t.seconds)} за ${t.meters} м" else null, Ru.clock(t.goalSeconds.roundToInt()))
        }
    }
    if (goal != null && lapM != null) path?.sessions?.takeIf { it.isNotEmpty() }?.let { sessions ->
        Text("Отрезки в темпе цели, средний круг", style = T.secondary(14.sp))
        sessions.takeLast(4).reversed().forEach { s ->
            RunRow(s.epochDay, if (s.judged > 0) "${s.onPace} из ${s.judged} в темпе" else null, lapTenth(s.paceSecKm * lapM / 1000.0))
        }
    }
    st?.fitness?.let { v ->
        val p = PaceTable.forVdot(v)
        Text(if (lapM != null) "Темпы плана, круг $lapM м" else "Темпы плана, мин/км", style = T.secondary(14.sp))
        // Whole seconds, as the run screen says them.
        fun lap(secKm: Double) = forma.core.run.Track.lapSec(secKm, lapM!!)
        fun one(secKm: Double) = if (lapM != null) Ru.lap(lap(secKm)) else Ru.pace(secKm)
        SetsRow("Лёгкий", if (lapM != null) Ru.lapRange(lap(p.easy.fast.toDouble()), lap(p.easy.slow.toDouble())) else "${Ru.pace(p.easy.fast.toDouble())}–${Ru.pace(p.easy.slow.toDouble())}", null)
        SetsRow("Темповый", one(p.threshold.toDouble()), null)
        SetsRow("Интервалы", one(p.interval.toDouble()), null)
    }
    if (weeks) RunWeeks(runs, dayOf, today, titled = true)
}

@Composable
private fun WeightSection(today: LocalDate, from: Long) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val list by remember { repo.db.state().observeBodyWeight() }.collectAsState(initial = emptyList())
    if (list.isEmpty()) {
        Answer("Вес тела", "Нет замеров", "Взвешивайся раз в неделю, утром.")
        GhostButton("Записать вес", { nav.push(Screen.Body) }, Modifier.fillMaxWidth(), height = 52.dp)
        return
    }
    val all = remember(list) { list.map { Point(it.epochDay, it.kg) }.sortedBy { it.epochDay } }
    val trend = remember(all) { BodyTrend.trend(all) }
    val rate = remember(all, today) { BodyTrend.perWeek(all, today.toEpochDay()) }
    val now = trend.last().value
    val last = all.last()
    // The big number is the smoothed weight (one weigh-in is ±0,5 kg of water); the weigh-in itself is said under it, as
    // «Записать вес» lists it — else a 74,45 typed comes back as 74,1 with nothing saying why.
    Answer(
        "Вес тела, в среднем", "${Ru.one(now)} кг",
        listOf(
            when {
                rate == null -> "Пока мало замеров, чтобы понять, куда идёт вес."
                kotlin.math.abs(rate * 4) < 0.2 -> "Стоит на месте."
                else -> "${if (rate > 0) "+" else "−"}${Ru.one(kotlin.math.abs(rate * 4))}\u00A0кг за 4 недели."
            },
            "Последний замер ${Ru.kg(last.value)}\u00A0кг" +
                if (today.toEpochDay() - last.epochDay > 14) ", ${shortDate(LocalDate.ofEpochDay(last.epochDay))}." else ".",
        ).joinToString(" "),
    )
    val shownTrend = trend.filter { it.epochDay > from }
    val shownDots = all.filter { it.epochDay > from }
    if (shownDots.size >= 2) TrendChart(
        shownTrend, shownDots.first().epochDay, today.toEpochDay(), "Вес тела, в среднем ${Ru.one(now)} кг, последний замер ${Ru.kg(last.value)} кг",
        dots = shownDots, endLabel = "${Ru.one(now)} кг", minSpan = 3.0, startLabel = shortDate(LocalDate.ofEpochDay(shownDots.first().epochDay)),
    )
    GhostButton("Записать вес", { nav.push(Screen.Body) }, Modifier.fillMaxWidth(), height = 52.dp)
}

@Composable
private fun LoadSection(today: LocalDate) {
    val repo = LocalRepo.current
    val loads by remember { repo.dailyLoads() }.collectAsState(initial = emptyMap())
    val story by remember(today) { repo.loadStory(today) }.collectAsState(initial = null)
    if (loads.isEmpty()) return
    val st = story ?: return
    Answer("Нагрузка", moodWords(st.zone))
    val weeks = st.weeks.takeLast(WEEKS)
    val current = weeks.lastOrNull()?.monday == Stats.monday(today)
    val usual = st.usualWeek
    if (weeks.isNotEmpty()) WeekBars(
        weeks.map { it.load }, "Нагрузка по неделям", current = current, usual = usual,
        // A spike against the line drawn as well as against the weeks before it: a week as tall as «обычная» is none. Named
        // by how much it is above the line, not «скачок» (research spike-label, 25 November).
        spikes = if (usual == null) emptyMap() else weeks.indices.filter { i ->
            !(current && i == weeks.lastIndex) && weeks[i].level == LoadStory.Level.SPIKE && weeks[i].load > usual * 1.3
        }.associateWith { i -> Ru.pct((weeks[i].load / usual - 1) * 100) },
        startLabel = shortDate(weeks.first().monday),
    )
    More("Подробнее о нагрузке") { LoadDetails(today) }
}

/** The old load tab: the three numbers, freshness and the rest line, the dips, the weeks, the units. */
@Composable
private fun LoadDetails(today: LocalDate) {
    val repo = LocalRepo.current
    val loads by remember { repo.dailyLoads() }.collectAsState(initial = emptyMap())
    val base by remember { repo.loadBase() }.collectAsState(initial = forma.core.load.LoadBase())
    val series = remember(loads, today, base) { TrainingLoad.series(loads, today.minusDays(120), today, base) }
    val story by remember(today) { repo.loadStory(today) }.collectAsState(initial = null)
    val st = story ?: return
    val last = series.takeLast(56)
    val line = st.personal.restBelow
    FCard(gap = 8.dp) {
        val now = series.last()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric3("Тренированность", now.fitness.roundToInt().toString(), F.c.endurance, Modifier.weight(1f))
            Metric3("Усталость", now.fatigue.roundToInt().toString(), F.c.load, Modifier.weight(1f))
            // A few sessions say nothing about freshness yet (one hard day reads as −100 %).
            if (st.history) Metric3("Свежесть", Ru.pct(now.formPct), zoneColor(LoadStory.zone(now.formPct, line)), Modifier.weight(1f))
            else Metric3("Свежесть", "—", F.c.text3, Modifier.weight(1f))
        }
        // The three words explained a tap away: on the screen they were three long paragraphs.
        var explain by rememberSaveable { mutableStateOf(false) }
        Text(
            if (explain) "Скрыть объяснения" else "Что значат эти числа ›", style = T.body(14.sp, FontWeight.SemiBold).copy(color = F.c.accent),
            modifier = Modifier.clickable(onClickLabel = if (explain) "Скрыть" else "Показать объяснения") { explain = !explain }.padding(vertical = 4.dp),
        )
        if (explain) {
            Explained("Тренированность", LoadExplain.fitness(st))
            Explained("Усталость", LoadExplain.fatigue(st))
            Explained("Свежесть", LoadExplain.freshness(st))
        }
    }
    if (st.history) {
        Text("Свежесть и линия отдыха", style = T.secondary(14.sp))
        FCard(gap = 6.dp) {
            // From two weeks after the first session: before that freshness is not yet a number to read.
            val from = loads.filter { it.value > 0 }.keys.minOrNull()?.plusDays(14)
            val fresh = last.filter { from != null && !it.date.isBefore(from) }.ifEmpty { last.takeLast(1) }
            LineChart(
                listOf(Series(fresh.map { it.formPct }, F.c.accent)), height = 110.dp, reference = line,
                xLabels = listOf(shortDate(fresh.first().date), shortDate(fresh.last().date)), description = "Свежесть по дням, серая линия — линия отдыха",
            )
            Text(LoadExplain.personal(st.personal), style = T.body(14.sp))
        }
        Text("Когда был нужен отдых", style = T.secondary(14.sp))
        FCard(gap = 6.dp) {
            if (st.dips.isEmpty()) Text(LoadExplain.noDips(8), style = T.body(14.sp))
            else st.dips.reversed().forEachIndexed { i, d ->
                if (i > 0) Hairline()
                Text(LoadExplain.dip(d, st.today.formPct), style = T.body(14.sp).copy(color = if (d.recovered == null) F.c.warn else F.c.text))
            }
        }
    }
    val cur = st.weeks.lastOrNull()
    if (cur != null) Text("Нагрузка по неделям", style = T.secondary(14.sp))
    if (cur != null) FCard(gap = 6.dp) {
        LoadExplain.usual(st)?.let { Text(it, style = T.secondary(12.sp)) }
        // The last three weeks; the rest a tap away.
        var allWeeks by rememberSaveable { mutableStateOf(false) }
        val shownWeeks = st.weeks.reversed().let { if (allWeeks) it else it.take(3) }
        shownWeeks.forEach { w ->
            Hairline()
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Ru.dates(w.monday, w.monday.plusDays(6)), style = T.body(14.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    Text(
                        LoadExplain.level(w), style = T.body(13.sp, FontWeight.SemiBold).copy(
                            color = when (w.level) {
                                // The week under way in the warning colour only when the top of the screen warns of it too.
                                LoadStory.Level.SPIKE -> if (!w.soFar || st.warn) F.c.warn else F.c.load
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
        if (st.weeks.size > 3) Text(
            if (allWeeks) "Скрыть" else "Все недели ›", style = T.body(14.sp, FontWeight.SemiBold).copy(color = F.c.accent),
            modifier = Modifier.clickable(onClickLabel = if (allWeeks) "Скрыть недели" else "Показать все недели") { allWeeks = !allWeeks }.padding(vertical = 4.dp),
        )
    }
    FCard(gap = 4.dp) {
        Text("Откуда числа", style = T.secondary(13.sp))
        Text(LoadExplain.UNITS, style = T.body(13.sp))
    }
}

/**
 * The week on the body: what got its sets by the plan for the same days lit, the rest at rest; one line says which. Today's
 * session counts once it is done — until then the week is the seven days to yesterday, which still hold last week's
 * session of this weekday.
 */
@Composable
private fun MusclesSection(today: LocalDate) {
    val repo = LocalRepo.current
    val rows by remember { repo.setRows() }.collectAsState(initial = emptyList())
    val workouts by remember { repo.db.workouts().observeHistory() }.collectAsState(initial = emptyList())
    val program by repo.store.program.collectAsState(initial = null)
    val profile by repo.store.profile.collectAsState(initial = null)
    val liftToday = program?.week?.on(today.dayOfWeek.value)?.any { it.kind == forma.core.plan.SessionKind.LIFT } == true
    val doneToday = workouts.any { it.epochDay == today.toEpochDay() && it.kind == Kind.LIFT }
    val end = if (liftToday && !doneToday) today.minusDays(1) else today
    val week = remember(rows, end) { Stats.setsLast7Days(rows, end) }
    if (week.values.none { it > 0 }) return
    val planned by produceState<Map<Muscle, Double>?>(null, program, end, rows) { value = repo.plannedSets(end.minusDays(6), end) }
    val plan = planned ?: return
    val hasPlan = plan.values.any { it > 0 }
    val band = setsTarget(profile?.aim?.lift)
    val tones = Muscle.entries.mapNotNull { m ->
        val v = week[m] ?: 0.0
        val p = plan[m] ?: 0.0
        when {
            v <= 0.0 && p <= 0.0 -> null
            v < p * 0.75 -> m to Tone.LOW
            v > maxOf(p, band.second) * 1.25 -> m to Tone.HIGH
            else -> m to Tone.OK
        }
    }.toMap()
    val few = tones.filterValues { it == Tone.LOW }.keys
    val many = tones.filterValues { it == Tone.HIGH }.keys
    fun names(set: Set<Muscle>) = set.joinToString(", ") { it.ru.lowercase() }
    fun groups(n: Int) = "$n ${Ru.plural(n, "группа", "группы", "групп")} мышц из ${tones.size}"
    Answer(
        "Мышцы за 7 дней",
        when {
            !hasPlan -> "В работе ${tones.size} ${Ru.plural(tones.size, "группа", "группы", "групп")} мышц"
            few.isEmpty() && many.isEmpty() -> "Всё по плану"
            few.size >= 4 && tones.size - few.size <= 2 -> "Меньше плана почти везде"
            few.size >= 4 -> "Меньше плана: ${groups(few.size)}"
            few.isNotEmpty() && many.isNotEmpty() -> "Мало: ${names(few)}. Много: ${names(many)}"
            few.isNotEmpty() -> "Мало: ${names(few)}"
            many.size >= 4 -> "Больше плана: ${groups(many.size)}"
            else -> "Много: ${names(many)}"
        },
    )
    forma.app.ui.components.BodyMap(
        emptyMap(), Modifier.fillMaxWidth().padding(horizontal = 24.dp), tones = tones, labels = false,
        description = "Мышцы за 7 дней: " + Muscle.entries.filter { (week[it] ?: 0.0) > 0 }.joinToString(", ") { "${it.ru} ${setsText(week[it]!!)}" },
    )
    if (hasPlan) forma.app.ui.components.BodyMapLegend()
    More("Подходы по мышцам") {
        Muscle.entries.filter { (week[it] ?: 0.0) > 0 || (plan[it] ?: 0.0) > 0 }.sortedByDescending { week[it] ?: 0.0 }.forEach { m ->
            val p = plan[m] ?: 0.0
            SetsRow(m.ru, setsText(week[m] ?: 0.0) + if (p > 0) " из ${setsText(p)}" else "", null)
        }
        Text("Если мышца в упражнении только помогает, подход идёт за половину.", style = T.secondary(13.sp))
    }
}

/** A finish goal (a distance, no time) or the run/walk start: where it stands, in the section's one answer. */
@Composable
private fun FinishAnswer(meters: Int?, st: forma.core.run.RunState?, runs: List<forma.app.data.RunEntity>) {
    val best = runs.filter { it.type != forma.core.run.RunType.RUN_WALK.name }.mapNotNull { it.distanceM }.maxOrNull()
    val walking = st != null && !st.canRunContinuously
    val week = st?.let { it.walkStage / 3 + 1 } ?: 1
    val end = "В конце 30 минут бега без остановки, это примерно 4–5 км."
    if (meters == null) {
        Answer("Бег с ходьбой", "Неделя $week из 9", end)
        return
    }
    val goal = "Пробежать ${goalDistance(meters)}"
    when {
        best != null && best >= meters -> Answer(
            "Бег", goal, "Цель взята: самая длинная пробежка ${goalDistance(best.roundToInt())}. Теперь можно поставить время в профиле, и план пойдёт к нему.",
            color = F.c.good,
        )
        walking -> Answer("Бег", goal, "Сейчас бег с ходьбой, неделя $week из 9. $end")
        best != null -> Answer("Бег", goal, "Самая длинная пробежка ${goalDistance(best.roundToInt())} из ${goalDistance(meters)}. Длинная пробежка недели растёт понемногу.")
        else -> Answer("Бег", goal, "Длинная пробежка недели растёт понемногу. Беги её спокойно, в разговорном темпе.")
    }
}

/** Sets as counted: whole ones as whole numbers («6», not «6,0»), a half from a helping muscle as «2,5». */
internal fun setsText(v: Double): String = if (v % 1.0 == 0.0) v.roundToInt().toString() else Ru.one(v)

/** Weekly hard sets per muscle that suit the gym focus (more for hypertrophy, fewer heavy ones for strength). */
fun setsTarget(lift: LiftFocus?): Pair<Double, Double> = when (lift) {
    LiftFocus.MUSCLE -> 10.0 to 20.0
    LiftFocus.POWERBUILDING -> 8.0 to 16.0
    LiftFocus.STRENGTH -> 6.0 to 12.0
    else -> 6.0 to 14.0
}

/**
 * The week's work on the body: the person lit where it was trained (full at the top of the range for the gym focus), and
 * each group's sets against that range, the most worked first.
 */
@Composable
fun MuscleWeek(sets: Map<Muscle, Double>, target: Pair<Double, Double>, title: String = "Мышцы за неделю", note: String? = null) {
    SectionLabel(title)
    FCard(gap = 10.dp) {
        forma.app.ui.components.BodyMap(
            sets.mapValues { (_, v) -> (v / target.second).toFloat() }, Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            description = "Нагрузка на мышцы: " + sets.entries.sortedByDescending { it.value }.filter { it.value > 0 }.joinToString(", ") { "${it.key.ru} ${setsText(it.value)}" },
        )
        Muscle.entries.sortedByDescending { sets[it] ?: 0.0 }.forEach { m ->
            val v = sets[m] ?: 0.0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(m.ru, style = T.body(13.sp), modifier = Modifier.width(110.dp))
                RangeBar(v, 24.0, target.first, target.second, F.c.strength, Modifier.weight(1f))
                NumText(setsText(v), style = T.number(13.sp, FontWeight.Bold), modifier = Modifier.width(36.dp))
            }
        }
        Text(
            note ?: ("Подходы за 7 дней. Полоса — сколько в неделю хорошо для твоей цели: ${target.first.roundToInt()}–${target.second.roundToInt()}. " +
                "Если мышца в упражнении только помогает, подход идёт за половину."),
            style = T.secondary(12.sp),
        )
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
        // «Тренированность» in a third of a narrow phone: smaller, never broken in the middle of the word.
        androidx.compose.foundation.text.BasicText(
            label, style = T.secondary(12.sp), maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
        )
    }
}


@Composable
fun Empty(text: String) {
    FCard { Text(text, style = T.secondary(14.sp)) }
}
