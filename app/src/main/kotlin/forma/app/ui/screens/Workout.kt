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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.toMutableStateList
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
import forma.core.strength.DoneSet
import forma.core.strength.Plates
import forma.core.strength.Progression
import forma.core.text.Ru
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import androidx.core.net.toUri

/** The fields and the hint of one exercise as they were before a set was logged or deleted: "Отменить" brings them back. */
private data class Fields(val weight: Double, val reps: Int, val rir: Int?, val advice: Pair<String, Int>?)

/**
 * What "Отменить" takes back: the set just logged ([setId]) or the one just deleted ([deleted]), at [position] of the
 * exercise [key]; the fields as they were before it, the rest it started, and the "Дальше — …" hint it put on the next
 * exercise ([nextKey], with what was there before).
 */
private data class Undo(
    val text: String, val at: Long, val position: Int, val key: String, val before: Fields,
    val setId: Long? = null, val deleted: SetEntity? = null, val startedRest: Boolean = false,
    val nextKey: String? = null, val nextBefore: Pair<String, Int>? = null,
)

/** How long "Отменить" stays after a set. */
private const val UNDO_MS = 8_000L

/** The session to open with "Завершить" asked at once: "Закончить «…»" when another one is started over it. */
internal var finishOnOpen: Long? = null

/** Weight step around [w]: the user's own step if set in settings, else the exercise's (finer for light dumbbells). */
private fun stepOf(ex: forma.core.model.Exercise, item: forma.app.data.LiftItem, w: Double): Double =
    if (item.step != ex.step) item.step else ex.stepAt(w)

/** Values being edited for the next set of one exercise. [exerciseId]: the exercise they were typed for (a swap puts another one at the same position). */
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

/** In-session hints by exercise ("position:exerciseId": a swap at the same position starts clean), kept the same way. */
private val AdviceSaver = listSaver<SnapshotStateMap<String, Pair<String, Int>>, Any>(
    save = { m -> m.flatMap { (k, v) -> listOf(k, v.first, v.second) } },
    restore = { l -> mutableStateMapOf<String, Pair<String, Int>>().apply { l.chunked(3).forEach { (k, t, c) -> put(k as String, (t as String) to (c as Int)) } } },
)

/** The exercise at its place in the session: hints and warm-up marks belong to it, not to the position alone. */
private fun keyOf(item: LiftItem) = "${item.position}:${item.exerciseId}"

/**
 * The next exercise with sets left after the one at [position] in the order of the day, then from the start; null
 * when every other one is done. [count]: sets logged at a position.
 */
private fun nextOpen(items: List<LiftItem>, position: Int, count: (Int) -> Int): LiftItem? {
    val i = items.indexOfFirst { it.position == position }
    val order = if (i < 0) items else items.drop(i + 1) + items.take(i)
    return order.firstOrNull { it.position != position && count(it.position) < it.prescription.sets }
}

/**
 * The best one-rep estimate of the session if [set] raised it above both the exercise's best before ([best]) and the
 * sets before it today ([before]); null otherwise. Not for holds, a first-time search or an exercise never done.
 */
internal fun recordAfter(ex: forma.core.model.Exercise, p: forma.core.strength.Prescription, before: List<DoneSet>, set: DoneSet, best: Double?, bw: Double): Double? {
    if (best == null || best <= 0 || p.calibrate || ex.load == LoadKind.TIMED) return null
    val now = Progression.e1rmOf(ex, before + set, p, bw) ?: return null
    val earlier = Progression.e1rmOf(ex, before, p, bw) ?: 0.0
    return now.takeIf { it > best + 0.05 && it > earlier + 0.05 }
}

@Composable
fun WorkoutScreen(workoutId: Long) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Opened to be finished («Закончить»): taken at once, so it is never left set for a later opening.
    val finishAsked = remember(workoutId) { (finishOnOpen == workoutId).also { if (it) finishOnOpen = null } }
    // A rest saved before the system closed the app comes back with its time.
    LaunchedEffect(Unit) { forma.app.ui.RestTimer.restore(context) }
    // Back from the system's settings with exact alarms allowed: the end of the rest going on is set again, on time.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        forma.app.ui.RestTimer.reschedule(context)
        onPauseOrDispose {}
    }
    val workout by remember(workoutId) { repo.db.workouts().observe(workoutId) }.collectAsState(initial = null)
    // Null until read: the fields of an exercise with sets start from its last set, so they wait for the sets.
    val loaded by remember(workoutId) { repo.db.sets().observeFor(workoutId) }.collectAsState(initial = null)
    val profile by repo.store.profile.collectAsState(initial = null)
    val stored by repo.store.settings.collectAsState(initial = null)
    val w = workout ?: return
    val sets = loaded ?: return
    val plan = remember(w.planJson) { repo.planOf(w) } ?: return
    val bw = profile?.bodyWeightKg ?: 75.0
    // How far this person's "reps left" run off, learned from their "+" sets.
    val rirBias by produceState(0, workoutId) { value = repo.rirBias(before = workoutId) }
    fun corrected(s: SetEntity) = DoneSet(s.weight, s.reps, forma.core.strength.RirScale.correct(s.rir, rirBias))

    // Keep the screen on in the gym.
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val inputs = rememberSaveable(saver = InputsSaver) { mutableStateMapOf() }
    // What to do next, said after every set (and where the next exercise starts) — not only when the weight changes.
    val advice = rememberSaveable(saver = AdviceSaver) { mutableStateMapOf() }
    // Warm-up sets ticked off ("position:exercise:i") or skipped ("…:skip"): not working sets, so not in the log or the progression.
    val warmMarks = rememberSaveable(saver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>({ it.toList() }, { it.toMutableStateList() })) { mutableStateListOf() }
    val doneBy = sets.groupBy { it.position }
    fun count(position: Int) = doneBy[position]?.size ?: 0

    /** Where the fields of [item] start before its first set: the plan's weight, the start guess, or the empty bar. */
    fun planStart(item: LiftItem): Double {
        val p = item.prescription
        val e = Catalog[item.exerciseId]
        return p.weight ?: p.startHint ?: if (e.load == LoadKind.EXTERNAL) (e.bar ?: item.step * 4) else 0.0
    }

    /**
     * The fields and the hint of [item] after [logged] (its sets so far): the next set as [Progression.nextSet] works it
     * out from them, or the last set again once the planned ones are done; the plan's start with none.
     */
    fun resume(inp: SetInput, item: LiftItem, logged: List<SetEntity>) {
        val e = Catalog[item.exerciseId]
        val last = logged.lastOrNull()
        inp.rir = null
        if (last == null) {
            inp.weight = planStart(item)
            inp.reps = item.prescription.reps
            advice.remove(keyOf(item))
            return
        }
        val ns = Progression.nextSet(e, item.prescription, logged.map(::corrected), bw)
        inp.weight = ns?.weight ?: last.weight
        inp.reps = ns?.reps ?: last.reps
        if (ns != null) advice[keyOf(item)] = Texts.nextSet(e, ns) to ns.change else advice.remove(keyOf(item))
    }

    fun input(item: LiftItem): SetInput {
        val had = inputs[item.position]
        had?.takeIf { it.exerciseId == item.exerciseId }?.let { return it }
        // Another exercise at this position (swapped in, or added after one was taken out): its own numbers, hints and
        // warm-up, never those of the one before.
        if (had != null) {
            val prefix = "${item.position}:"
            advice.keys.filter { it.startsWith(prefix) }.forEach { advice.remove(it) }
            warmMarks.removeAll { it.startsWith(prefix) }
        }
        val inp = SetInput(item.exerciseId, planStart(item), item.prescription.reps).also { inputs[item.position] = it }
        // Back to an exercise with sets: from the last of them, with the next set worked out again.
        val logged = doneBy[item.position].orEmpty()
        if (logged.isNotEmpty()) resume(inp, item, logged)
        return inp
    }
    var current by rememberSaveable { mutableIntStateOf(-1) }
    if (current < 0 && plan.items.isNotEmpty()) {
        current = plan.items.firstOrNull { count(it.position) < it.prescription.sets }?.position ?: plan.items.first().position
    }
    val item = plan.items.firstOrNull { it.position == current } ?: plan.items.first()
    val ex = Catalog[item.exerciseId]
    val inp = input(item)
    val note = stored?.notes?.get(item.exerciseId)

    // The rest goes on (and beeps) while the library or another screen is open on top, and with the screen off.
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

    var finishing by remember { mutableStateOf(finishAsked) }
    var lastLogAt by remember { mutableLongStateOf(Long.MIN_VALUE / 2) }
    var undo by remember { mutableStateOf<Undo?>(null) }
    var removing by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<SetEntity?>(null) }
    var typing by remember { mutableStateOf(false) }
    var typingReps by remember { mutableStateOf(false) }
    var noting by remember { mutableStateOf(false) }
    var hintOff by remember { mutableStateOf(forma.app.ui.RestTimer.hintOff(context)) }
    val lastReport by produceState<Pair<List<SetEntity>, SlotReport?>?>(null, item.exerciseId) {
        value = repo.lastReport(item.exerciseId)?.takeIf { it.first.firstOrNull()?.workoutId != workoutId }
    }
    // The best one-rep estimate of this exercise before today: a set above it is a record, said right away.
    val best by produceState<Double?>(null, item.exerciseId) { value = repo.db.state().stat(item.exerciseId)?.best }

    val mine = doneBy[item.position].orEmpty()
    val n = mine.size
    val planned = item.prescription.sets
    val plusSet = item.prescription.top && n == planned - 1

    fun fieldsOf(i: LiftItem, x: SetInput) = Fields(x.weight, x.reps, x.rir, advice[keyOf(i)])

    /** Takes back what [u] did: the set (logged or deleted), the rest it started and the hints it changed. */
    fun takeBack(u: Undo) {
        undo = null
        if (u.startedRest) forma.app.ui.RestTimer.stop()
        inputs[u.position]?.let { x ->
            x.weight = u.before.weight
            x.reps = u.before.reps
            x.rir = u.before.rir
        }
        if (u.before.advice != null) advice[u.key] = u.before.advice else advice.remove(u.key)
        u.nextKey?.let { k -> if (u.nextBefore != null) advice[k] = u.nextBefore else advice.remove(k) }
        current = u.position
        scope.launch {
            u.setId?.let { repo.deleteSet(it) }
            u.deleted?.let { repo.db.sets().insert(it) }
        }
    }

    val notifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { askExactAlarms(context) }

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
                ScreenHint(Hints.WORKOUT, Hints.WORKOUT_TEXT)
                if (plan.deload) Banner(plan.why ?: "Разгрузка — лёгкая неделя: меньше подходов и вес легче. Не доводи до отказа.", F.c.warn)

                // Exercise switcher.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    plan.items.forEach { it2 ->
                        val complete = count(it2.position) >= it2.prescription.sets
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
                        if (mine.isEmpty()) {
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
                    // The person's own note on the exercise: the seat, the grip — what is forgotten from one week to the next.
                    if (note != null) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(F.shape.radiusSmall)).background(F.c.surface2)
                                .clickable(onClickLabel = "Изменить заметку") { noting = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Ic.pin, null, tint = F.c.text2, modifier = Modifier.size(14.dp))
                            Text(note, style = T.body(14.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                        }
                    }
                    // What it is in this day, what works, why here in the order: one tap away, not in the way of the sets.
                    var about by remember(item.position) { mutableStateOf(false) }
                    Text(
                        if (about) "Зачем и какие мышцы ▴" else "Зачем и какие мышцы ▾", style = T.body(13.sp, FontWeight.Bold).copy(color = F.c.text2),
                        modifier = Modifier.clickable(onClickLabel = if (about) "Свернуть" else "Показать, зачем упражнение") { about = !about }.padding(vertical = 4.dp),
                    )
                    if (about) {
                        Text(Texts.roleLine(item.slot.role, ex) + " " + Texts.orderWhy(item.slot.role, ex), style = T.secondary(13.sp))
                        item.restWhy?.let { Text(it, style = T.secondary(13.sp)) }
                        Text(Texts.muscles(ex), style = T.secondary(13.sp))
                    }
                    if (note == null) {
                        Text(
                            "Заметка к упражнению", style = T.body(13.sp, FontWeight.Bold).copy(color = F.c.accent),
                            modifier = Modifier.clickable(onClickLabel = "Добавить заметку") { noting = true }.padding(vertical = 4.dp),
                        )
                    }
                    if (ex.equipment == Equipment.BARBELL && ex.bar != null && inp.weight > ex.bar!!) PlatesLine(inp.weight, ex.bar!!)
                    val last = lastReport
                    when {
                        p.calibrate -> Hint(
                            "Подбираем рабочий вес. Начни с ${Texts.weight(ex, inp.weight)}. Делай повторы чисто и остановись, " +
                                "когда сможешь ещё 2 (максимум 15). После подхода подскажу вес.",
                            F.c.accent, Ic.info,
                        )
                        last != null -> {
                            val s = last.first.joinToString(", ") { it.reps.toString() }
                            val wDone = last.first.maxOf { it.weight }
                            val text = if (ex.load == LoadKind.TIMED) "Прошлый раз: $s с." else "Прошлый раз ${Texts.weightReps(ex, wDone, s)}."
                            val why = last.second?.outcome?.let { " " + Texts.outcome(it, ex) } ?: ""
                            Hint(text + why, if ((last.second?.outcome?.let { Texts.tone(it) } ?: 0) < 0) F.c.warn else F.c.strength)
                        }
                    }
                }

                // Warm-up: before the first working set, ticked off or skipped — not working sets, so not counted.
                if (mine.isEmpty()) {
                    val skipKey = "${keyOf(item)}:skip"
                    when {
                        item.warmups.isNotEmpty() && skipKey in warmMarks -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Разминка пропущена", style = T.secondary(13.sp), modifier = Modifier.weight(1f))
                            TextButton({ warmMarks.remove(skipKey) }) { Text("Вернуть", style = T.body(14.sp, FontWeight.Bold).copy(color = F.c.accent)) }
                        }
                        item.warmups.isNotEmpty() -> Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionLabel("Разминка", Modifier.weight(1f))
                                TextButton({ warmMarks.add(skipKey) }) { Text("Без разминки", style = T.body(14.sp, FontWeight.Bold).copy(color = F.c.accent)) }
                            }
                            Text("Перед рабочими подходами, на вес в плане не влияет. Отмечай касанием.", style = T.secondary(12.sp))
                            item.warmups.forEachIndexed { i, s ->
                                val key = "${keyOf(item)}:$i"
                                val ticked = key in warmMarks
                                Hairline()
                                Row(
                                    Modifier.fillMaxWidth().clickable(onClickLabel = if (ticked) "Снять отметку" else "Отметить разминочный подход") {
                                        if (ticked) warmMarks.remove(key) else warmMarks.add(key)
                                    }.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    NumText("Р${i + 1}", style = T.number(14.sp).copy(color = F.c.text3), modifier = Modifier.width(36.dp))
                                    NumText(Texts.weightReps(ex, s.weight, s.reps), style = T.number(16.sp).copy(color = if (ticked) F.c.text3 else F.c.text2), modifier = Modifier.weight(1f))
                                    Box(
                                        Modifier.size(24.dp).clip(CircleShape).background(if (ticked) F.c.good else F.c.surface2),
                                        contentAlignment = Alignment.Center,
                                    ) { if (ticked) Icon(Ic.check, null, tint = F.c.onData, modifier = Modifier.size(14.dp)) }
                                }
                            }
                        }
                        item.noWarmUp != null -> Text(item.noWarmUp, style = T.secondary(13.sp))
                    }
                }

                // Logged sets.
                if (mine.isNotEmpty()) {
                    Column {
                        mine.forEachIndexed { i, s ->
                            Hairline()
                            Row(
                                Modifier.fillMaxWidth().clickable(onClickLabel = "Исправить подход") { editing = s }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NumText("${i + 1}", style = T.number(16.sp).copy(color = F.c.text2), modifier = Modifier.width(32.dp))
                                NumText(Texts.weightReps(ex, s.weight, s.reps), style = T.number(18.sp), modifier = Modifier.weight(1f))
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
                            (if (item.prescription.topRir >= 2) "Остановись, когда сможешь ещё 2: впереди тяжёлый бег. "
                            else "Остановись, когда сможешь ещё 1: ") +
                            (if (item.prescription.topRir >= 2) "Веса будут расти так же." else "по лишним повторам видно, сколько прибавить."),
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
                        val leftMs = rest.endAt - now
                        // While the current exercise has sets left it comes next, else the next one with sets left.
                        val next = if (n < planned) item else nextOpen(plan.items, item.position, ::count)
                        RestBar(
                            leftMs, (leftMs.toFloat() / rest.totalMs).coerceIn(0f, 1f), next?.name,
                            onMinus = { forma.app.ui.RestTimer.adjust(context, -15_000, settings.beeps, settings.vibration) },
                            onPlus = { forma.app.ui.RestTimer.adjust(context, 15_000, settings.beeps, settings.vibration) },
                            onSkip = { forma.app.ui.RestTimer.stop() },
                            modifier = Modifier,
                        )
                        // The end of the rest with the screen off needs notifications and, on Android 12+, exact alarms.
                        val blocked = !forma.app.ui.RestTimer.notificationsAllowed(context) || !forma.app.ui.RestTimer.exactAllowed(context)
                        if (blocked && !hintOff) {
                            Column {
                                Text(
                                    "Чтобы слышать конец отдыха даже с выключенным экраном, разреши уведомления и точные будильники.",
                                    style = T.secondary(12.sp),
                                )
                                Row {
                                    TextButton({
                                        if (!forma.app.ui.RestTimer.notificationsAllowed(context)) {
                                            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                        } else askExactAlarms(context)
                                    }) { Text("Разрешить", style = T.body(14.sp, FontWeight.Bold).copy(color = F.c.accent)) }
                                    TextButton({
                                        hintOff = true
                                        forma.app.ui.RestTimer.setHintOff(context)
                                    }) { Text("Не надо", style = T.body(14.sp)) }
                                }
                            }
                        }
                    }
                    // The set just logged (or deleted) can be taken back for a few seconds: a tap too early, the wrong numbers.
                    val u = undo
                    if (u != null && tick - u.at < UNDO_MS) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(u.text, style = T.secondary(14.sp), modifier = Modifier.weight(1f))
                            TextButton({ takeBack(u) }) { Text("Отменить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
                        }
                    }
                    advice[keyOf(item)]?.let { (text, change) ->
                        Hint(text, when { change > 0 -> F.c.good; change < 0 -> F.c.warn; else -> F.c.strength }, when { change > 0 -> Ic.up; change < 0 -> Ic.down; else -> Ic.info })
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
                            onLongValue = { typingReps = true },
                        )
                    }
                    if (timed) {
                        StepperGroup(listOf(repsSlot))
                        // A plank held to the stopwatch: the seconds go into the field. A stopwatch of its own for each
                        // exercise at its place: one started on a plank does not carry over to the side plank.
                        androidx.compose.runtime.key(keyOf(item)) {
                            HoldTimer(item.prescription.reps, settings.beeps, settings.vibration) { inp.reps = max(1, it) }
                        }
                    } else {
                        // Assisted pull-ups and dips go below zero: negative weight is help from a machine or band.
                        val lowest = if (ex.assisted) -Progression.maxAssist(ex, bw) else 0.0
                        val weightText = Ru.kg(kotlin.math.abs(inp.weight))
                        val weightUnit = when {
                            inp.weight < 0 -> "помощь, кг"
                            ex.load == LoadKind.BODYWEIGHT_PLUS -> "доп. кг"
                            ex.perHand -> "кг ×2"
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
                    // The advice reads the reserve on the person's own scale; the set is saved as rated.
                    val doneSet = DoneSet(inp.weight, inp.reps, forma.core.strength.RirScale.correct(inp.rir, rirBias))
                    val logged = item
                    val key = keyOf(logged)
                    val before = fieldsOf(logged, inp)
                    val prior = mine.map(::corrected)
                    val record = recordAfter(ex, logged.prescription, prior, doneSet, best, bw)
                    scope.launch {
                        val id = repo.logSet(workoutId, logged, inp.weight, inp.reps, inp.rir)
                        // The next set, with the numbers already in the fields — or, the exercise done, where to go on.
                        val ns = Progression.nextSet(ex, logged.prescription, prior + doneSet, bw)
                        val closes = n + 1 >= planned
                        val nx = if (closes) nextOpen(plan.items, logged.position, ::count) else null
                        val recordText = record?.let {
                            "Рекорд! " + if (ex.load == LoadKind.EXTERNAL) "Вес на 1 раз — около ${Texts.oneRm(ex, it)}. " else "Сильнее, чем когда-либо. "
                        } ?: ""
                        var nextKey: String? = null
                        var nextBefore: Pair<String, Int>? = null
                        if (ns != null) {
                            inp.weight = ns.weight
                            inp.reps = ns.reps
                            advice[key] = recordText + Texts.nextSet(ex, ns) to ns.change
                        } else {
                            advice.remove(key)
                            if (nx != null) {
                                val nxEx = Catalog[nx.exerciseId]
                                nextKey = keyOf(nx)
                                nextBefore = advice[nextKey]
                                advice[nextKey] = recordText + "«${logged.name}» сделано. Дальше — ${nx.name}: ${Texts.prescription(nxEx, nx.prescription)}. " +
                                    Texts.roleLine(nx.slot.role, nxEx) + " " + Texts.orderWhy(nx.slot.role, nxEx) to 0
                            } else if (record != null) {
                                // The very last set of the session: no next set or exercise to say it with, so it stays here.
                                advice[key] = recordText.trim() to 1
                            }
                        }
                        inp.rir = null
                        // The very last set of the session: nothing to rest for.
                        val lastOfAll = closes && nx == null
                        // Rest from this set: how hard it was, how heavy, the exercise, how this person recovers.
                        val restAdvice = forma.core.strength.RestAdvice.after(
                            logged.slot.restSec, doneSet.reps, doneSet.rir, logged.prescription.rir, ex.compound, plan.deload, logged.restShift, logged.restOwn,
                            target = if (logged.prescription.calibrate) null else logged.prescription.reps,
                        )
                        if (ns != null && ex.load != LoadKind.TIMED) {
                            val why = forma.core.strength.RestAdvice.words(restAdvice)
                            advice[key] = (advice[key]?.first ?: "") +
                                " Отдых ${Ru.clock(restAdvice.sec)}" + (if (why.isNotEmpty() && restAdvice.sec != logged.slot.restSec) ": $why." else ".") to ns.change
                        }
                        val startsRest = settings.autoRest && !lastOfAll
                        if (startsRest) {
                            forma.app.ui.RestTimer.start(context, workoutId, restAdvice.sec * 1000L, settings.beeps, settings.vibration)
                        } else if (lastOfAll) forma.app.ui.RestTimer.stop()
                        undo = Undo(
                            "Подход ${n + 1} записан", System.currentTimeMillis(), logged.position, key, before,
                            setId = id, startedRest = startsRest, nextKey = nextKey, nextBefore = nextBefore,
                        )
                        if (nx != null) current = nx.position
                    }
                }, Modifier.fillMaxWidth(), color = F.c.strength, textColor = F.c.onData, icon = Ic.check)
            }
        }
    }

    // The exercise to show once the one at [pos] is gone: the next unfinished one, else any other.
    fun nextAfterRemoving(pos: Int): Int =
        nextOpen(plan.items, pos, ::count)?.position ?: plan.items.firstOrNull { it.position != pos }?.position ?: -1
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
                        if (fromPlan) "Только сегодня или из плана насовсем. Вернуть можно на экране «План»."
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
        // Left open after the last set (off to the stadium): the time is counted to the last set, said so.
        val nowMs = System.currentTimeMillis()
        val lastSetAt = sets.maxOfOrNull { it.doneAt }
        val end = forma.app.data.TrainingRepository.liftEnd(w.startedAt, lastSetAt, nowMs)
        FinishDialog(
            hasSets = sets.isNotEmpty(),
            notStarted = plan.items.filter { count(it.position) == 0 }.map { it.name },
            endNote = if (lastSetAt != null && end != nowMs) {
                "Последний подход был в ${timeOfDay(lastSetAt)}. Время тренировки считаю до ${timeOfDay(end)}, " +
                    "чтобы пауза не добавила лишней нагрузки."
            } else null,
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
        val at = mine.indexOfFirst { it.id == s.id }
        EditSetDialog(
            s, ex, onDismiss = { editing = null },
            onSave = { fixed ->
                editing = null
                scope.launch { repo.updateSet(fixed) }
                // The next set follows the sets as they are now.
                resume(inp, item, mine.map { if (it.id == fixed.id) fixed else it })
            },
            onDelete = {
                editing = null
                val before = fieldsOf(item, inp)
                scope.launch { repo.deleteSet(s.id) }
                resume(inp, item, mine.filter { it.id != s.id })
                undo = Undo("Подход ${at + 1} удалён", System.currentTimeMillis(), item.position, keyOf(item), before, deleted = s)
            },
        )
    }
    if (typing) {
        WeightDialog(inp.weight, ex.assisted, onDismiss = { typing = false }) { inp.weight = it; typing = false }
    }
    if (typingReps) {
        RepsDialog(inp.reps, ex.load == LoadKind.TIMED, onDismiss = { typingReps = false }) { inp.reps = it; typingReps = false }
    }
    if (noting) {
        NoteDialog(note.orEmpty(), item.name, onDismiss = { noting = false }) { text ->
            noting = false
            val id = item.exerciseId
            scope.launch { repo.updateSettings { s -> s.copy(notes = if (text.isBlank()) s.notes - id else s.notes + (id to text.trim())) } }
        }
    }
}

/** "18:40" on this phone's clock. */
private fun timeOfDay(ms: Long): String {
    val t = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    return "%d:%02d".format(t.hour, t.minute)
}

/** The system screen where exact alarms are allowed (Android 12+); nothing to ask before that. */
private fun askExactAlarms(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT < 31 || forma.app.ui.RestTimer.exactAllowed(context)) return
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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

/** The rest: time left, then, past the end, how long it has run over ("+0:40"). */
@Composable
private fun RestBar(leftMs: Long, fraction: Float, next: String?, onMinus: () -> Unit, onPlus: () -> Unit, onSkip: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(F.shape.radius + 2.dp)
    var m = modifier.fillMaxWidth().clip(shape).background(F.c.surface2)
    if (F.shape.border > 0.dp || F.c.dark) m = m.border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, F.c.line, shape)
    val over = leftMs <= 0
    Row(m.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Ring(if (over) 1f else fraction, if (over) F.c.warn else F.c.strength, Modifier.size(40.dp), stroke = 4.dp)
        Column(Modifier.weight(1f)) {
            Text(labelText(if (over) "Отдых окончен" else "Отдых"), style = T.label())
            NumText(
                if (over) "+" + Ru.clock((-leftMs / 1000).toInt()) else Ru.clock(((leftMs + 999) / 1000).toInt()),
                style = T.number(22.sp).copy(color = if (over) F.c.warn else F.c.text),
            )
            if (next != null) Text("далее: $next", style = T.secondary(11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!over) SmallButton("−15", onMinus)
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

/**
 * A hold (plank) to the stopwatch: "3, 2, 1", then the seconds count up, with a signal at [target]; "Стоп" puts the
 * seconds held into the field.
 */
@Composable
private fun HoldTimer(target: Int, beep: Boolean, vibrate: Boolean, onDone: (Int) -> Unit) {
    val context = LocalContext.current
    var startAt by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(0L) }
    val s = startAt
    LaunchedEffect(s) {
        var signalled = false
        while (s != null) {
            now = System.currentTimeMillis()
            if (!signalled && now - s - HOLD_LEAD_MS >= target * 1000L) {
                signalled = true
                forma.app.run.Feedback.restOver(context, beep, vibrate)
            }
            delay(100)
        }
    }
    if (s == null) {
        GhostButton("Засечь время", { now = System.currentTimeMillis(); startAt = now }, Modifier.fillMaxWidth(), height = 48.dp)
        return
    }
    val ms = now - s
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ms < HOLD_LEAD_MS) {
            Text("Приготовься: ${(HOLD_LEAD_MS - ms + 999) / 1000}", style = T.title(20.sp), modifier = Modifier.weight(1f))
            GhostButton("Отмена", { startAt = null }, height = 44.dp)
        } else {
            val held = ((ms - HOLD_LEAD_MS) / 1000).toInt()
            NumText(
                "${Ru.clock(held)} из ${Ru.clock(target)}",
                style = T.number(22.sp).copy(color = if (held >= target) F.c.good else F.c.text), modifier = Modifier.weight(1f),
            )
            GhostButton("Стоп", { onDone(held); startAt = null }, height = 44.dp, color = F.c.strength)
        }
    }
}

/** Seconds to get into position before a hold starts counting. */
private const val HOLD_LEAD_MS = 3_000L

/** The effort of the whole session: none chosen until the person picks one (a default would be counted as theirs). */
@Composable
private fun FinishDialog(
    hasSets: Boolean, notStarted: List<String>, endNote: String?,
    onDismiss: () -> Unit, onCancelWorkout: () -> Unit, onFinish: (Int) -> Unit,
) {
    var rpe by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = F.c.surface,
        title = { Text(if (hasSets) "Как тяжело было в целом?" else "Ни одного подхода", style = T.title(20.sp)) },
        text = {
            if (hasSets) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Оцени всю тренировку от 1 до 10. По оценке считаются нагрузка и усталость.", style = T.secondary(14.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..5).forEach { v -> Chip("$v", rpe == v, { rpe = v }, Modifier.weight(1f), minWidth = 0.dp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (6..10).forEach { v -> Chip("$v", rpe == v, { rpe = v }, Modifier.weight(1f), minWidth = 0.dp) }
                    }
                    val chosen = rpe
                    Text(
                        if (chosen != null) Texts.RPE.first { it.first == chosen }.second else "Выбери оценку",
                        style = T.body(15.sp, FontWeight.Bold).copy(color = if (chosen != null) F.c.text else F.c.text3),
                    )
                    if (notStarted.isNotEmpty()) {
                        Text(
                            "Не начаты: ${notStarted.joinToString(", ")}. Их веса в следующий раз будут те же.",
                            style = T.secondary(13.sp).copy(color = F.c.warn),
                        )
                    }
                    endNote?.let { Text(it, style = T.secondary(13.sp)) }
                }
            } else {
                Text("Удалить начатую тренировку? Прогресс не изменится.", style = T.secondary(14.sp))
            }
        },
        confirmButton = {
            val chosen = rpe
            if (hasSets) {
                TextButton({ if (chosen != null) onFinish(chosen) }, enabled = chosen != null) {
                    Text("Сохранить", style = T.body(15.sp, FontWeight.Bold).copy(color = if (chosen != null) F.c.accent else F.c.text3))
                }
            } else TextButton(onCancelWorkout) { Text("Удалить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.bad)) }
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

/** A logged set put right; deleting it is a separate small action, taken back with "Отменить" like a set logged. */
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (load != LoadKind.TIMED) SignedWeightField(weight, { weight = it }, ex.assisted, help, { help = it })
                NumberField(reps, { reps = it }, if (load == LoadKind.TIMED) "Секунды" else "Повторы", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                if (load != LoadKind.TIMED) {
                    Text("Сколько ещё мог?", style = T.secondary(13.sp))
                    RirChips(rir) { rir = it }
                }
                Text(
                    "Удалить подход", style = T.body(14.sp).copy(color = F.c.bad),
                    modifier = Modifier.clickable(onClickLabel = "Удалить подход", onClick = onDelete).padding(vertical = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton({
                onSave(s.copy(weight = signed(weight, ex.assisted, help) ?: s.weight, reps = reps.toIntOrNull()?.coerceAtLeast(0) ?: s.reps, rir = rir))
            }) { Text("Сохранить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена", style = T.body(15.sp)) } },
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

/** Reps (or seconds of a hold) typed as a number: a long press on the value, like the weight. */
@Composable
private fun RepsDialog(value: Int, timed: Boolean, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = F.c.surface,
        title = { Text(if (timed) "Секунды" else "Повторы", style = T.title(20.sp)) },
        text = { NumberField(text, { text = it }, if (timed) "Сколько секунд" else "Сколько повторов", keyboard = androidx.compose.ui.text.input.KeyboardType.Number) },
        confirmButton = {
            TextButton({ text.trim().toIntOrNull()?.takeIf { it in 1..999 }?.let(onSet) ?: onDismiss() }) {
                Text("Готово", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена", style = T.body(15.sp)) } },
    )
}

/** A short note on the exercise, kept for every session: the seat, the grip, the machine's number. */
@Composable
private fun NoteDialog(value: String, name: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = F.c.surface,
        title = { Text("Заметка", style = T.title(20.sp)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput(text, { text = it }, "К «$name» — будет в каждой тренировке", placeholder = "сиденье 4, хват шире", maxLength = 80)
                if (value.isNotEmpty()) Text("Пустая заметка удаляется.", style = T.secondary(12.sp))
            }
        },
        confirmButton = { TextButton({ onSave(text) }) { Text("Сохранить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена", style = T.body(15.sp)) } },
    )
}
