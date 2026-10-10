package forma.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.LiftItem
import forma.app.data.SetEntity
import forma.app.data.SlotReport
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.LocalSettings
import forma.app.ui.Screen
import forma.app.ui.Texts
import forma.app.ui.components.Banner
import forma.app.ui.components.Chip
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.Ring
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.Stepper
import forma.app.ui.components.StepperGroup
import forma.app.ui.components.StepperSlot
import forma.app.ui.components.TopBar
import forma.app.ui.components.NumText
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.app.ui.theme.labelText
import forma.core.model.Catalog
import forma.core.model.Equipment
import forma.core.model.LoadKind
import forma.core.strength.Adjustment
import forma.core.strength.DoneSet
import forma.core.strength.Plates
import forma.core.strength.Progression
import forma.core.text.Ru
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * The set just logged, for "Отменить": its id, where it was, which set it was and when, the hint shown before it
 * and whether it started the rest.
 */
private data class Undo(val setId: Long, val position: Int, val number: Int, val at: Long, val adjBefore: Adjustment?, val startedRest: Boolean)

/** How long "Отменить" stays after a set. */
private const val UNDO_MS = 8_000L

/** Values being edited for the next set of one exercise. */
/** Weight step around [w]: the user's own step if set in settings, else the exercise's (finer for light dumbbells). */
private fun stepOf(ex: forma.core.model.Exercise, item: forma.app.data.LiftItem, w: Double): Double =
    if (item.step != ex.step) item.step else ex.stepAt(w)

/** [exerciseId]: the exercise the values were typed for (a swap puts another one at the same position). */
private class SetInput(val exerciseId: String, weight: Double, reps: Int) {
    var weight by mutableStateOf(weight)
    var reps by mutableIntStateOf(reps)
    var rir by mutableStateOf<Int?>(null)
}

/** Typed values per exercise, kept while another screen is on top and through rotation. */
private val InputsSaver = listSaver<SnapshotStateMap<Int, SetInput>, Any>(
    save = { m -> m.flatMap { (k, v) -> listOf(k, v.exerciseId, v.weight, v.reps, v.rir ?: -1) } },
    restore = { l ->
        mutableStateMapOf<Int, SetInput>().apply {
            l.chunked(5).forEach { (k, id, w, r, rir) ->
                put(k as Int, SetInput(id as String, w as Double, r as Int).also { it.rir = (rir as Int).takeIf { x -> x >= 0 } })
            }
        }
    },
)

/** In-session weight hints, kept the same way. */
private val AdjustmentsSaver = listSaver<SnapshotStateMap<Int, Adjustment>, Any>(
    save = { m -> m.flatMap { (k, a) -> listOf(k, a.weight, a.reps, a.heavier) } },
    restore = { l ->
        mutableStateMapOf<Int, Adjustment>().apply {
            l.chunked(4).forEach { (k, w, r, h) -> put(k as Int, Adjustment(w as Double, r as Int, h as Boolean)) }
        }
    },
)

@Composable
fun WorkoutScreen(workoutId: Long) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val workout by remember(workoutId) { repo.db.workouts().observe(workoutId) }.collectAsState(initial = null)
    val sets by remember(workoutId) { repo.db.sets().observeFor(workoutId) }.collectAsState(initial = emptyList())
    val profile by repo.store.profile.collectAsState(initial = null)
    val w = workout ?: return
    val plan = remember(w.planJson) { repo.planOf(w) } ?: return
    val bw = profile?.bodyWeightKg ?: 75.0

    // Keep the screen on in the gym.
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val inputs = rememberSaveable(saver = InputsSaver) { mutableStateMapOf() }
    val adjustments = rememberSaveable(saver = AdjustmentsSaver) { mutableStateMapOf() }
    fun input(item: LiftItem): SetInput {
        inputs[item.position]?.takeIf { it.exerciseId == item.exerciseId }?.let { return it }
        // Another exercise at this position (swapped in, or added after one was taken out): its own numbers and hints,
        // never the weight typed for the one before.
        adjustments.remove(item.position)
        val p = item.prescription
        val ex = Catalog[item.exerciseId]
        val start = p.weight ?: p.startHint ?: if (ex.load == LoadKind.EXTERNAL) (ex.bar ?: item.step * 4) else 0.0
        return SetInput(item.exerciseId, start, p.reps).also { inputs[item.position] = it }
    }
    val doneBy = sets.groupBy { it.position }
    var current by rememberSaveable { mutableIntStateOf(-1) }
    if (current < 0 && plan.items.isNotEmpty()) {
        current = plan.items.firstOrNull { (doneBy[it.position]?.size ?: 0) < it.prescription.sets }?.position ?: 0
    }
    val item = plan.items.firstOrNull { it.position == current } ?: plan.items.first()
    val ex = Catalog[item.exerciseId]
    val inp = input(item)

    // The rest goes on (and beeps) while the library or another screen is open on top.
    val restState by forma.app.ui.RestTimer.rest.collectAsState()
    val rest = restState?.takeIf { it.workoutId == workoutId }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rest) {
        while (rest != null) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { tick = System.currentTimeMillis(); delay(1000) } }

    var finishing by remember { mutableStateOf(false) }
    var lastLogAt by remember { mutableLongStateOf(Long.MIN_VALUE / 2) }
    var undo by remember { mutableStateOf<Undo?>(null) }
    var removing by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<SetEntity?>(null) }
    var typing by remember { mutableStateOf(false) }
    val lastReport by produceState<Pair<List<SetEntity>, SlotReport?>?>(null, item.exerciseId) {
        value = repo.lastReport(item.exerciseId)?.takeIf { it.first.firstOrNull()?.workoutId != workoutId }
    }

    val mine = doneBy[item.position].orEmpty()
    val n = mine.size
    val planned = item.prescription.sets
    val plusSet = item.prescription.top && n == planned - 1

    // The set panel stays at the bottom: weight, reps, reserve and "done" are always at hand, the rest is inside it.
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        // A small phone with a large font: the panel scrolls inside rather than push the top bar off the screen.
        val panelMax = maxHeight * 0.75f
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val elapsed = ((tick - w.startedAt) / 1000).toInt().coerceAtLeast(0)
                TopBar(plan.title, "${Ru.clock(elapsed)} · ${plan.items.indexOf(item) + 1} из ${plan.items.size}", onBack = { nav.back() }) {
                    GhostButton("Завершить", { finishing = true }, height = 48.dp)
                }
                if (plan.deload) Banner("Разгрузка: меньше подходов и вес легче. Не дожимай до отказа.", F.c.warn)

                // Exercise switcher.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    plan.items.forEach { it2 ->
                        val n = doneBy[it2.position]?.size ?: 0
                        val complete = n >= it2.prescription.sets
                        val sel = it2.position == item.position
                        val shape = if (F.shape.pill) RoundedCornerShape(50) else RoundedCornerShape(F.shape.radiusSmall)
                        Row(
                            Modifier.height(44.dp).clip(shape)
                                .background(if (sel) F.c.text else if (complete) F.c.surface2 else Color.Transparent)
                                .border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, if (sel) F.c.text else F.c.line, shape)
                                .selectable(selected = sel, role = Role.Tab) { current = it2.position }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (complete) Icon(Ic.check, null, tint = if (sel) F.c.bg else F.c.good, modifier = Modifier.size(14.dp))
                            Text(it2.name, style = T.body(13.sp, FontWeight.Bold).copy(color = if (sel) F.c.bg else F.c.text), maxLines = 1)
                        }
                    }
                    val shape = if (F.shape.pill) RoundedCornerShape(50) else RoundedCornerShape(F.shape.radiusSmall)
                    Box(
                        Modifier.size(44.dp).clip(shape).border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, F.c.line, shape)
                            .clickable(onClickLabel = "Добавить упражнение") { nav.push(Screen.Library(forma.app.ui.LibraryMode.AddToWorkout(workoutId))) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Ic.plus, "Добавить упражнение", tint = F.c.text, modifier = Modifier.size(16.dp)) }
                }

                // Header.
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(item.name, style = T.title(if (F.type.capsTitles) 34.sp else 24.sp), modifier = Modifier.weight(1f))
                        // Another exercise in its place, or none: only before its first set (the sets belong to the exercise).
                        if (doneBy[item.position].isNullOrEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GhostButton("Заменить", {
                                    nav.push(Screen.Library(forma.app.ui.LibraryMode.SwapInWorkout(workoutId, item.position, item.exerciseId)))
                                }, height = 44.dp)
                                if (plan.items.size > 1) GhostButton("Убрать", { removing = item.position }, height = 44.dp, color = F.c.bad)
                            }
                        }
                    }
                    val p = item.prescription
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (ex.load != LoadKind.TIMED) {
                            Pill(if (p.weight != null) Texts.weight(ex, p.weight!!) else "вес подбираем", filled = true)
                            Pill(if (p.calibrate) "${p.sets} ${Ru.plural(p.sets, "подход", "подхода", "подходов")}" else "${p.sets} × ${p.reps}")
                            if (p.top) Pill("последний ${p.reps}+")
                        } else Pill("${p.sets} × ${p.reps} с", filled = true)
                        if (!p.calibrate) Pill("запас ${p.rir}")
                    }
                    if (ex.equipment == Equipment.BARBELL && ex.bar != null && inp.weight > ex.bar!!) PlatesLine(inp.weight, ex.bar!!)
                    val last = lastReport
                    when {
                        p.calibrate -> Hint(
                            "Первый раз подбираем рабочий вес. Начни с ${Texts.weight(ex, inp.weight)} и в каждом подходе делай " +
                                "столько повторов, сколько выходит чисто, оставляя 2 в запасе (не больше 15). После подхода подскажу вес.",
                            F.c.accent, Ic.info,
                        )
                        last != null -> {
                            val s = last.first.joinToString(", ") { it.reps.toString() }
                            val wDone = last.first.maxOf { it.weight }
                            val text = if (ex.load == LoadKind.TIMED) "Прошлый раз: $s с." else "Прошлый раз ${Texts.weight(ex, wDone)} × $s."
                            val why = last.second?.outcome?.let { " " + Texts.outcome(it, ex) } ?: ""
                            Hint(text + why, if ((last.second?.outcome?.let { Texts.tone(it) } ?: 0) < 0) F.c.warn else F.c.strength)
                        }
                    }
                }

                // Warm-up.
                if (item.warmups.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Разминка", Modifier.width(92.dp))
                        NumText(item.warmups.joinToString(" · ") { "${Ru.kg(it.weight)}×${it.reps}" }, style = T.number(13.sp, FontWeight.Medium).copy(color = F.c.text2))
                    }
                }

                // Logged sets.
                if (mine.isNotEmpty()) {
                    Column {
                        mine.forEachIndexed { i, s ->
                            Hairline()
                            Row(
                                Modifier.fillMaxWidth().clickable { editing = s }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NumText("${i + 1}", style = T.number(16.sp).copy(color = F.c.text2), modifier = Modifier.width(32.dp))
                                NumText(
                                    if (ex.load == LoadKind.TIMED) "${s.reps} с" else "${Texts.weight(ex, s.weight)} × ${s.reps}",
                                    style = T.number(18.sp), modifier = Modifier.weight(1f),
                                )
                                if (s.rir != null) Text("запас ${if (s.rir >= 5) "5+" else s.rir}", style = T.secondary(13.sp), modifier = Modifier.padding(end = 10.dp))
                                Box(Modifier.size(24.dp).clip(CircleShape).background(F.c.strength), contentAlignment = Alignment.Center) {
                                    Icon(Ic.check, null, tint = F.c.onData, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }

                // What the "+" set is for, under the sets it follows.
                if (plusSet) {
                    Hint(
                        "Последний подход — ${item.prescription.reps}+: сделай ${item.prescription.reps} и продолжай, пока идёт чисто. " +
                            "Остановись, когда останется 1 повтор в запасе: по лишним повторам видно, сколько прибавлять.",
                        F.c.strength, Ic.info,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            Column(
                Modifier.fillMaxWidth().heightIn(max = panelMax).background(F.c.bg).navigationBarsPadding()
                    .padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 10.dp),
            ) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hairline()
                    if (rest != null) {
                        val left = ((rest.endAt - now) / 1000).coerceAtLeast(0)
                        val next = plan.items.firstOrNull { (doneBy[it.position]?.size ?: 0) < it.prescription.sets }
                        RestBar(
                            left.toInt(), ((rest.endAt - now).toFloat() / rest.totalMs).coerceIn(0f, 1f), next?.name,
                            onMinus = { forma.app.ui.RestTimer.adjust(context, -15_000, settings.beeps, settings.vibration) },
                            onPlus = { forma.app.ui.RestTimer.adjust(context, 15_000, settings.beeps, settings.vibration) },
                            onSkip = { forma.app.ui.RestTimer.stop() },
                            modifier = Modifier,
                        )
                    }
                    // The set just logged can be taken back for a few seconds (a tap too early, the wrong numbers).
                    val u = undo
                    if (u != null && tick - u.at < UNDO_MS) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Подход ${u.number} записан", style = T.secondary(14.sp), modifier = Modifier.weight(1f))
                            TextButton({
                                undo = null
                                if (u.startedRest) forma.app.ui.RestTimer.stop()
                                if (u.adjBefore != null) adjustments[u.position] = u.adjBefore else adjustments.remove(u.position)
                                current = u.position
                                scope.launch { repo.deleteSet(u.setId) }
                            }) { Text("Отменить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
                        }
                    }
                    adjustments[item.position]?.let { adj ->
                        val next = "${Texts.weight(ex, adj.weight)} × ${adj.reps}"
                        val text = when {
                            item.prescription.calibrate && adj.heavier -> "Можно тяжелее. Следующий подход: $next?"
                            item.prescription.calibrate -> "Тяжеловато. Следующий подход: $next?"
                            adj.heavier -> "Подход был заметно легче плана. Следующий: $next?"
                            else -> "Подход был тяжелее плана. Следующий: $next?"
                        }
                        Banner(text, if (adj.heavier) F.c.good else F.c.warn, icon = if (adj.heavier) Ic.up else Ic.down) {
                            inp.weight = adj.weight
                            inp.reps = adj.reps
                            adjustments.remove(item.position)
                        }
                    }
                    SectionLabel(
                        if (n < planned) "Подход ${n + 1} из $planned" + (if (plusSet) " · ${item.prescription.reps}+" else "") else "Дополнительный подход",
                        color = F.c.strength,
                    )
                    val timed = ex.load == LoadKind.TIMED
                    val repsText = inp.reps.toString()
                    val repsUnit = if (timed) "сек" else "повт"
                    val repsSlot = StepperSlot(maxOf(repsText, "00", compareBy { it.length }), repsUnit) { m ->
                        Stepper(
                            repsText, repsUnit,
                            { inp.reps = max(1, inp.reps - if (timed) 5 else 1) },
                            { inp.reps += if (timed) 5 else 1 }, m, "Меньше повторов", "Больше повторов",
                        )
                    }
                    if (timed) {
                        StepperGroup(listOf(repsSlot))
                    } else {
                        // Assisted pull-ups and dips go below zero: negative weight is help from a machine or band.
                        val lowest = if (ex.assisted) -Progression.maxAssist(ex, bw) else 0.0
                        val weightText = Ru.kg(kotlin.math.abs(inp.weight))
                        val weightUnit = when {
                            inp.weight < 0 -> "помощь, кг"
                            ex.load == LoadKind.BODYWEIGHT_PLUS -> "доп. кг"
                            ex.perHand -> "кг × 2"
                            else -> "кг"
                        }
                        // Sized for "102,5" and the longest unit this exercise can show, not for today's number.
                        val weightSlot = StepperSlot(maxOf(weightText, "000,0", compareBy { it.length }), if (ex.assisted) "помощь, кг" else weightUnit) { m ->
                            Stepper(
                                weightText, weightUnit,
                                { inp.weight = max(lowest, inp.weight - stepOf(ex, item, inp.weight - 1e-6)) }, { inp.weight += stepOf(ex, item, inp.weight) }, m,
                                "Меньше вес", "Больше вес", onLongValue = { typing = true },
                            )
                        }
                        StepperGroup(listOf(weightSlot, repsSlot))
                    }
                    if (!timed) {
                        Text("Сколько ещё повторов мог сделать?", style = T.secondary(12.sp))
                        RirChips(inp.rir) { inp.rir = it }
                    }
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Подход сделан", {
                    // A double tap of a sweaty finger is one set, not two.
                    val tap = android.os.SystemClock.elapsedRealtime()
                    if (tap - lastLogAt < 1500) return@PrimaryButton
                    lastLogAt = tap
                    val doneSet = DoneSet(inp.weight, inp.reps, inp.rir)
                    val logged = item
                    scope.launch {
                        val id = repo.logSet(workoutId, logged, inp.weight, inp.reps, inp.rir)
                        undo = Undo(id, logged.position, n + 1, System.currentTimeMillis(), adjustments[logged.position], settings.autoRest)
                        val adj = Progression.adjustAfterSet(ex, logged.prescription, doneSet, bw, index = n)
                        if (adj != null) adjustments[logged.position] = adj else adjustments.remove(logged.position)
                        inp.rir = null
                        if (settings.autoRest) {
                            forma.app.ui.RestTimer.start(context, workoutId, logged.slot.restSec * 1000L, settings.beeps, settings.vibration)
                        }
                        if (n + 1 >= planned) {
                            plan.items.firstOrNull { it.position > logged.position && (doneBy[it.position]?.size ?: 0) < it.prescription.sets }
                                ?.let { current = it.position }
                        }
                    }
                }, Modifier.fillMaxWidth(), color = F.c.strength, textColor = F.c.onData, icon = Ic.check)
            }
        }
    }

    // The exercise to show once the one at [pos] is gone: the next unfinished one, else any other.
    fun nextAfterRemoving(pos: Int): Int =
        plan.items.firstOrNull { it.position != pos && (doneBy[it.position]?.size ?: 0) < it.prescription.sets }?.position
            ?: plan.items.firstOrNull { it.position != pos }?.position ?: -1
    removing?.let { pos ->
        val target = plan.items.firstOrNull { it.position == pos }
        // "For good" only for an exercise that comes from the plan (not one added to this session).
        val fromPlan by produceState(false, workoutId, pos) { value = repo.inPlan(workoutId, pos) }
        if (target == null) {
            removing = null
        } else {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { removing = null },
                containerColor = F.c.surface,
                title = { Text("Убрать «${target.name}»?", style = T.title(20.sp)) },
                text = {
                    Text(
                        if (fromPlan) "Только из этой тренировки — или из плана насовсем (вернуть можно на экране «План»)."
                        else "Упражнение уйдёт из этой тренировки.",
                        style = T.secondary(14.sp),
                    )
                },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        if (fromPlan) {
                            TextButton({
                                removing = null
                                current = nextAfterRemoving(pos)
                                scope.launch { repo.removeFromWorkout(workoutId, pos, forever = true) }
                            }) { Text("Из плана насовсем", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.bad)) }
                        }
                        TextButton({
                            removing = null
                            current = nextAfterRemoving(pos)
                            scope.launch { repo.removeFromWorkout(workoutId, pos, forever = false) }
                        }) { Text("Только сегодня", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
                        TextButton({ removing = null }) { Text("Отмена", style = T.body(15.sp)) }
                    }
                },
            )
        }
    }

    if (finishing) {
        FinishDialog(
            hasSets = sets.isNotEmpty(),
            onDismiss = { finishing = false },
            onCancelWorkout = {
                finishing = false
                forma.app.ui.RestTimer.stop()
                scope.launch { repo.cancelWorkout(workoutId); nav.back() }
            },
            onFinish = { rpe ->
                finishing = false
                forma.app.ui.RestTimer.stop()
                scope.launch {
                    repo.finishLift(workoutId, rpe)
                    nav.replaceTop(Screen.LiftDone(workoutId))
                }
            },
        )
    }
    editing?.let { s ->
        EditSetDialog(s, ex, onDismiss = { editing = null }, onSave = { ns -> scope.launch { repo.updateSet(ns) }; editing = null }, onDelete = {
            scope.launch { repo.deleteSet(s.id) }
            editing = null
        })
    }
    if (typing) {
        WeightDialog(inp.weight, ex.assisted, onDismiss = { typing = false }) { inp.weight = it; typing = false }
    }
}

@Composable
private fun Pill(text: String, filled: Boolean = false) {
    val shape = if (F.shape.pill) RoundedCornerShape(50) else RoundedCornerShape(F.shape.radiusSmall)
    var m = Modifier.clip(shape)
    m = if (filled) m.background(F.c.strength) else m.border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, F.c.line, shape)
    NumText(
        text, style = T.number(13.sp, FontWeight.Bold).copy(color = if (filled) F.c.onData else F.c.text),
        modifier = m.padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

/** "How many more reps could you do": 0–4 and 5+, tap again to clear. */
@Composable
private fun RirChips(value: Int?, onChange: (Int?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (0..5).forEach { v ->
            Chip(if (v == 5) "5+" else "$v", value == v, { onChange(if (value == v) null else v) }, Modifier.weight(1f), minWidth = 0.dp)
        }
    }
}

@Composable
private fun Hint(text: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector = Ic.up) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Text(text, style = T.body(13.sp, FontWeight.SemiBold).copy(color = if (F.c.dark) color else F.c.text))
    }
}

@Composable
private fun PlatesLine(total: Double, bar: Double) {
    val l = Plates.loadout(total, bar)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("На сторону", style = T.secondary(12.sp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            l.perSide.forEach { p ->
                val h = when {
                    p >= 20 -> 34.dp
                    p >= 10 -> 28.dp
                    p >= 5 -> 22.dp
                    else -> 16.dp
                }
                val c = when (p) {
                    25.0 -> Color(0xFFD94A3F)
                    20.0 -> Color(0xFF3D7EDE)
                    15.0 -> Color(0xFFE2B33C)
                    10.0 -> Color(0xFF3FA165)
                    else -> F.c.text2
                }
                Box(Modifier.size(width = if (p >= 10) 8.dp else 5.dp, height = h).clip(RoundedCornerShape(2.dp)).background(c))
            }
            Box(Modifier.size(width = 22.dp, height = 3.dp).background(F.c.text3))
        }
        NumText(
            if (l.perSide.isEmpty()) "только гриф" else l.perSide.joinToString(" + ") { Ru.kg(it) },
            style = T.number(14.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (!l.isExact(total)) Text("≈", style = T.secondary(13.sp))
    }
}

@Composable
private fun RestBar(left: Int, fraction: Float, next: String?, onMinus: () -> Unit, onPlus: () -> Unit, onSkip: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(F.shape.radius + 2.dp)
    var m = modifier.fillMaxWidth().clip(shape).background(F.c.surface2)
    if (F.shape.border > 0.dp || F.c.dark) m = m.border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, F.c.line, shape)
    Row(m.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Ring(fraction, F.c.strength, Modifier.size(40.dp), stroke = 4.dp)
        Column(Modifier.weight(1f)) {
            Text(labelText("Отдых"), style = T.label())
            NumText(Ru.clock(left), style = T.number(22.sp))
            if (next != null) Text("далее: $next", style = T.secondary(11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SmallButton("−15", onMinus)
        SmallButton("+15", onPlus)
        SmallButton("›", onSkip, "Пропустить отдых")
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit, label: String = text) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.size(width = 44.dp, height = 40.dp).clip(shape).background(if (F.c.dark) F.c.line else F.c.surface)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { NumText(text, style = T.number(13.sp, FontWeight.Bold)) }
}

@Composable
private fun FinishDialog(hasSets: Boolean, onDismiss: () -> Unit, onCancelWorkout: () -> Unit, onFinish: (Int) -> Unit) {
    var rpe by remember { mutableIntStateOf(7) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = F.c.surface,
        title = { Text(if (hasSets) "Как тяжело было в целом?" else "Ни одного подхода", style = T.title(20.sp)) },
        text = {
            if (hasSets) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Оценка всей тренировки от 1 до 10. По ней считается нагрузка и усталость.", style = T.secondary(14.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..5).forEach { v -> Chip("$v", rpe == v, { rpe = v }, Modifier.weight(1f), minWidth = 0.dp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (6..10).forEach { v -> Chip("$v", rpe == v, { rpe = v }, Modifier.weight(1f), minWidth = 0.dp) }
                    }
                    Text(Texts.RPE.first { it.first == rpe }.second, style = T.body(15.sp, FontWeight.Bold))
                }
            } else {
                Text("Удалить начатую тренировку? Прогресс не изменится.", style = T.secondary(14.sp))
            }
        },
        confirmButton = {
            if (hasSets) TextButton({ onFinish(rpe) }) { Text("Сохранить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
            else TextButton(onCancelWorkout) { Text("Удалить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.bad)) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Продолжить тренировку", style = T.body(15.sp)) } },
    )
}

/**
 * Weight typed as a number. For assisted exercises the number is how much help (shown without a minus) or how much
 * extra weight, chosen next to it: typing "15" for 15 kg of help must not log 15 kg on a belt.
 */
@Composable
private fun SignedWeightField(text: String, onText: (String) -> Unit, assisted: Boolean, help: Boolean, onHelp: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (assisted) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("помощь", help, { onHelp(true) }, Modifier.weight(1f), minWidth = 0.dp)
                Chip("доп. вес", !help, { onHelp(false) }, Modifier.weight(1f), minWidth = 0.dp)
            }
        }
        NumberField(text, onText, if (assisted && help) "Помощь" else "Вес", "кг")
    }
}

/** The typed value with the sign the choice gives it (help is negative weight); null when it is not a number. */
private fun signed(text: String, assisted: Boolean, help: Boolean): Double? {
    val v = parseNum(text)?.let { kotlin.math.abs(it) } ?: return null
    return if (assisted && help) -v else v
}

@Composable
private fun EditSetDialog(s: SetEntity, ex: forma.core.model.Exercise, onDismiss: () -> Unit, onSave: (SetEntity) -> Unit, onDelete: () -> Unit) {
    val load = ex.load
    var weight by remember { mutableStateOf(Ru.kg(kotlin.math.abs(s.weight))) }
    var help by remember { mutableStateOf(s.weight < 0) }
    var reps by remember { mutableStateOf(s.reps.toString()) }
    var rir by remember { mutableStateOf(s.rir) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = F.c.surface,
        title = { Text("Исправить подход", style = T.title(20.sp)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (load != LoadKind.TIMED) SignedWeightField(weight, { weight = it }, ex.assisted, help, { help = it })
                NumberField(reps, { reps = it }, if (load == LoadKind.TIMED) "Секунды" else "Повторы", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                if (load != LoadKind.TIMED) {
                    Text("Сколько ещё мог?", style = T.secondary(13.sp))
                    RirChips(rir) { rir = it }
                }
            }
        },
        confirmButton = {
            TextButton({
                onSave(s.copy(weight = signed(weight, ex.assisted, help) ?: s.weight, reps = reps.toIntOrNull()?.coerceAtLeast(0) ?: s.reps, rir = rir))
            }) { Text("Сохранить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
        },
        dismissButton = { TextButton(onDelete) { Text("Удалить подход", style = T.body(15.sp).copy(color = F.c.bad)) } },
    )
}

@Composable
private fun WeightDialog(value: Double, assisted: Boolean, onDismiss: () -> Unit, onSet: (Double) -> Unit) {
    var text by remember { mutableStateOf(Ru.kg(kotlin.math.abs(value))) }
    var help by remember { mutableStateOf(value < 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = F.c.surface,
        title = { Text("Вес", style = T.title(20.sp)) },
        text = { SignedWeightField(text, { text = it }, assisted, help, { help = it }) },
        confirmButton = { TextButton({ signed(text, assisted, help)?.let(onSet) ?: onDismiss() }) { Text("Готово", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена", style = T.body(15.sp)) } },
    )
}

