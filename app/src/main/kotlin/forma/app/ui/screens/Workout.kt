package forma.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

/** Values being edited for the next set of one exercise. */
/** Weight step around [w]: the user's own step if set in settings, else the exercise's (finer for light dumbbells). */
private fun stepOf(ex: forma.core.model.Exercise, item: forma.app.data.LiftItem, w: Double): Double =
    if (item.step != ex.step) item.step else ex.stepAt(w)

private class SetInput(weight: Double, reps: Int) {
    var weight by mutableStateOf(weight)
    var reps by mutableIntStateOf(reps)
    var rir by mutableStateOf<Int?>(null)
}

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

    val inputs = remember { mutableStateMapOf<Int, SetInput>() }
    fun input(item: LiftItem): SetInput = inputs.getOrPut(item.position) {
        val p = item.prescription
        val ex = Catalog[item.exerciseId]
        val start = p.weight ?: p.startHint ?: if (ex.load == LoadKind.EXTERNAL) (ex.bar ?: item.step * 4) else 0.0
        SetInput(start, p.reps)
    }
    val doneBy = sets.groupBy { it.position }
    var current by remember { mutableIntStateOf(-1) }
    if (current < 0 && plan.items.isNotEmpty()) {
        current = plan.items.firstOrNull { (doneBy[it.position]?.size ?: 0) < it.prescription.sets }?.position ?: 0
    }
    val item = plan.items.firstOrNull { it.position == current } ?: plan.items.first()
    val ex = Catalog[item.exerciseId]
    val inp = input(item)
    val adjustments = remember { mutableStateMapOf<Int, Adjustment>() }

    var restEnd by remember { mutableLongStateOf(0L) }
    var restTotal by remember { mutableLongStateOf(1L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(restEnd) {
        while (restEnd > 0) {
            now = System.currentTimeMillis()
            if (now >= restEnd) {
                forma.app.run.Feedback.restOver(context, settings.beeps, settings.vibration)
                restEnd = 0
                break
            }
            delay(200)
        }
    }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { tick = System.currentTimeMillis(); delay(1000) } }

    var finishing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SetEntity?>(null) }
    var typing by remember { mutableStateOf(false) }
    val lastReport by produceState<Pair<List<SetEntity>, SlotReport?>?>(null, item.exerciseId) {
        value = repo.lastReport(item.exerciseId)?.takeIf { it.first.firstOrNull()?.workoutId != workoutId }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val elapsed = ((tick - w.startedAt) / 1000).toInt().coerceAtLeast(0)
            TopBar(plan.title, "${Ru.clock(elapsed)} · упражнение ${plan.items.indexOf(item) + 1} из ${plan.items.size}", onBack = { nav.back() }) {
                GhostButton("Завершить", { finishing = true }, height = 38.dp)
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
                        Modifier.height(36.dp).clip(shape)
                            .background(if (sel) F.c.text else if (complete) F.c.surface2 else Color.Transparent)
                            .border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, if (sel) F.c.text else F.c.line, shape)
                            .clickable { current = it2.position }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (complete) Icon(Ic.check, null, tint = if (sel) F.c.bg else F.c.good, modifier = Modifier.size(14.dp))
                        Text(it2.name, style = T.body(13.sp, FontWeight.Bold).copy(color = if (sel) F.c.bg else F.c.text), maxLines = 1)
                    }
                }
            }

            // Header.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.name, style = T.title(if (F.type.capsTitles) 34.sp else 24.sp))
                val p = item.prescription
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ex.load != LoadKind.TIMED) {
                        Pill(if (p.weight != null) Texts.weight(ex, p.weight!!) else "вес подбираем", filled = true)
                        Pill("${p.sets} × ${p.reps}")
                    } else Pill("${p.sets} × ${p.reps} с", filled = true)
                    Pill("запас ${p.rir}")
                }
                if (ex.equipment == Equipment.BARBELL && ex.bar != null && inp.weight > ex.bar!!) PlatesLine(inp.weight, ex.bar!!)
                val last = lastReport
                when {
                    p.calibrate -> Hint("Первый раз: начни с ${Texts.weight(ex, inp.weight)} и сделай ${p.reps} повторов. После подхода подскажу рабочий вес.", F.c.accent)
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
            val mine = doneBy[item.position].orEmpty()
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
                            if (s.rir != null) Text("запас ${s.rir}", style = T.secondary(13.sp), modifier = Modifier.padding(end = 10.dp))
                            Box(Modifier.size(24.dp).clip(CircleShape).background(F.c.strength), contentAlignment = Alignment.Center) {
                                Icon(Ic.check, null, tint = F.c.onData, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }

            adjustments[item.position]?.let { adj ->
                val text = if (adj.heavier) "Подход был заметно легче плана. Следующий: ${Texts.weight(ex, adj.weight)} × ${adj.reps}?"
                else "Подход был тяжелее плана. Следующий: ${Texts.weight(ex, adj.weight)} × ${adj.reps}?"
                Banner(text, if (adj.heavier) F.c.good else F.c.warn, icon = if (adj.heavier) Ic.up else Ic.down) {
                    inp.weight = adj.weight
                    inp.reps = adj.reps
                    adjustments.remove(item.position)
                }
            }

            // Next set.
            val n = mine.size
            val planned = item.prescription.sets
            FCard(outline = F.c.strength) {
                SectionLabel(if (n < planned) "Подход ${n + 1} из $planned" else "Дополнительный подход", color = F.c.strength)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ex.load != LoadKind.TIMED) {
                        Stepper(
                            Ru.kg(inp.weight), if (ex.load == LoadKind.BODYWEIGHT_PLUS) "доп. кг" else if (ex.perHand) "кг × 2" else "кг",
                            { inp.weight = max(0.0, inp.weight - stepOf(ex, item, inp.weight - 1e-6)) }, { inp.weight += stepOf(ex, item, inp.weight) }, Modifier.weight(1f),
                            "Меньше вес", "Больше вес", onLongValue = { typing = true },
                        )
                    }
                    Stepper(
                        inp.reps.toString(), if (ex.load == LoadKind.TIMED) "сек" else "повт",
                        { inp.reps = max(1, inp.reps - if (ex.load == LoadKind.TIMED) 5 else 1) },
                        { inp.reps += if (ex.load == LoadKind.TIMED) 5 else 1 }, Modifier.weight(1f), "Меньше повторов", "Больше повторов",
                    )
                }
                if (ex.load != LoadKind.TIMED) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Сколько ещё мог?", style = T.secondary(13.sp), modifier = Modifier.weight(1f))
                        listOf(0, 1, 2, 3).forEach { v ->
                            Chip(if (v == 3) "3+" else "$v", inp.rir == v, { inp.rir = if (inp.rir == v) null else v }, minWidth = 44.dp)
                        }
                    }
                }
                PrimaryButton("Подход сделан", {
                    val doneSet = DoneSet(inp.weight, inp.reps, inp.rir)
                    scope.launch {
                        repo.logSet(workoutId, item, inp.weight, inp.reps, inp.rir)
                        val adj = Progression.adjustAfterSet(ex, item.prescription, doneSet, bw, index = n)
                        if (adj != null) adjustments[item.position] = adj else adjustments.remove(item.position)
                        inp.rir = null
                        if (settings.autoRest) {
                            restTotal = item.slot.restSec * 1000L
                            restEnd = System.currentTimeMillis() + restTotal
                        }
                        if (n + 1 >= planned) {
                            plan.items.firstOrNull { it.position > item.position && (doneBy[it.position]?.size ?: 0) < it.prescription.sets }
                                ?.let { current = it.position }
                        }
                    }
                }, Modifier.fillMaxWidth(), color = F.c.strength, textColor = F.c.onData, icon = Ic.check)
            }
            Spacer(Modifier.height(if (restEnd > 0) 110.dp else 24.dp))
        }

        if (restEnd > 0) {
            val left = ((restEnd - now) / 1000).coerceAtLeast(0)
            val next = plan.items.firstOrNull { (doneBy[it.position]?.size ?: 0) < it.prescription.sets }
            RestBar(
                left.toInt(), (restEnd - now).toFloat() / restTotal, next?.name,
                onMinus = { restEnd -= 15_000 }, onPlus = { restEnd += 15_000; restTotal += 15_000 }, onSkip = { restEnd = 0 },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (finishing) {
        FinishDialog(
            hasSets = sets.isNotEmpty(),
            onDismiss = { finishing = false },
            onCancelWorkout = {
                finishing = false
                scope.launch { repo.cancelWorkout(workoutId); nav.back() }
            },
            onFinish = { rpe ->
                finishing = false
                scope.launch {
                    repo.finishLift(workoutId, rpe)
                    nav.replaceTop(Screen.LiftDone(workoutId))
                }
            },
        )
    }
    editing?.let { s ->
        EditSetDialog(s, ex.load, onDismiss = { editing = null }, onSave = { ns -> scope.launch { repo.updateSet(ns) }; editing = null }, onDelete = {
            scope.launch { repo.deleteSet(s.id) }
            editing = null
        })
    }
    if (typing) {
        WeightDialog(inp.weight, onDismiss = { typing = false }) { inp.weight = it; typing = false }
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

@Composable
private fun Hint(text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Ic.up, null, tint = color, modifier = Modifier.size(16.dp).padding(top = 2.dp))
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
    var m = modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().clip(shape).background(F.c.surface2)
    if (F.shape.border > 0.dp || F.c.dark) m = m.border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, F.c.line, shape)
    Row(m.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Ring(fraction, F.c.strength, Modifier.size(52.dp), stroke = 5.dp)
        Column(Modifier.weight(1f)) {
            Text(labelText("Отдых"), style = T.label())
            NumText(Ru.clock(left), style = T.number(28.sp))
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

@Composable
private fun EditSetDialog(s: SetEntity, load: LoadKind, onDismiss: () -> Unit, onSave: (SetEntity) -> Unit, onDelete: () -> Unit) {
    var weight by remember { mutableStateOf(Ru.kg(s.weight)) }
    var reps by remember { mutableStateOf(s.reps.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = F.c.surface,
        title = { Text("Исправить подход", style = T.title(20.sp)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (load != LoadKind.TIMED) NumberField(weight, { weight = it }, "Вес", "кг", Modifier.weight(1f))
                NumberField(reps, { reps = it }, if (load == LoadKind.TIMED) "Секунды" else "Повторы", modifier = Modifier.weight(1f), keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            }
        },
        confirmButton = {
            TextButton({
                onSave(s.copy(weight = parseNum(weight) ?: s.weight, reps = reps.toIntOrNull()?.coerceAtLeast(0) ?: s.reps))
            }) { Text("Сохранить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
        },
        dismissButton = { TextButton(onDelete) { Text("Удалить подход", style = T.body(15.sp).copy(color = F.c.bad)) } },
    )
}

@Composable
private fun WeightDialog(value: Double, onDismiss: () -> Unit, onSet: (Double) -> Unit) {
    var text by remember { mutableStateOf(Ru.kg(value)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = F.c.surface,
        title = { Text("Вес", style = T.title(20.sp)) },
        text = { NumberField(text, { text = it }, "Точный вес", "кг") },
        confirmButton = { TextButton({ parseNum(text)?.let(onSet) ?: onDismiss() }) { Text("Готово", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена", style = T.body(15.sp)) } },
    )
}

