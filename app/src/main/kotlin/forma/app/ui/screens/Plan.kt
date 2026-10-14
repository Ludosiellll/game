package forma.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.LiftPlan
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.NumText
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.core.model.Experience
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.plan.Template
import forma.core.run.RunWorkout
import forma.core.text.Ru
import kotlinx.coroutines.launch

@Composable
fun PlanScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val program by repo.store.program.collectAsState(initial = null)
    val profile by repo.store.profile.collectAsState(initial = null)
    val p = program ?: return
    val today = forma.app.ui.rememberToday(repo)
    val weekNo = ProgramGenerator.programWeek(p, today)
    val lifting by repo.store.liftState.collectAsState(initial = forma.app.data.LiftState())
    ScrollScreen {
        Text(if (F.type.capsTitles) "ПЛАН" else "План", style = T.screenTitle(), modifier = Modifier.padding(top = 8.dp))
        val goal = aimTitle(p.aim) + (p.copiedFrom?.let { " · программа друга ($it)" } ?: "")
        Text("$goal · ${when (p.experience) { Experience.NOVICE -> "новичок"; Experience.INTERMEDIATE -> "средний уровень"; Experience.ADVANCED -> "опытный" }}", style = T.secondary(15.sp))

        // The cycle is the gym's (sets, reserve, light weeks): without the gym there is nothing to say.
        if (p.templates.isNotEmpty()) FCard(gap = 6.dp) {
            SectionLabel("Цикл")
            val cycle = cycleWords(p, today, lifting.reactiveDeloadStart)
            if (cycle != null) {
                Text("Неделя ${cycle.first} из ${p.blockWeeks}", style = T.title(22.sp))
                Text(cycle.second.replaceFirstChar { it.uppercase() }, style = T.secondary(14.sp))
            } else {
                Text("Неделя $weekNo", style = T.title(22.sp))
                Text("Новичкам плановая разгрузка не нужна: приложение даст лёгкую неделю само, если увидит, что веса откатываются.", style = T.secondary(14.sp))
            }
        }

        SectionLabel("Неделя")
        // A due trial is the next hard day only: the hard days after it show what comes after the trial.
        val trialOn by produceState<java.time.LocalDate?>(null, p, today) { value = repo.trialDay(today) }
        val weekMonday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val weekItems by remember(weekMonday) { repo.weekFlow(weekMonday) }.collectAsState(initial = emptyList())
        FCard(gap = 0.dp) {
            (1..7).forEach { day ->
                if (day > 1) Hairline()
                val sessions = p.week.on(day)
                val dayDate = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).plusDays(day - 1L)
                // A tap opens the day in full: what each session is, what it is for, why on this day.
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Подробнее о дне") { nav.push(Screen.Day(dayDate.toEpochDay())) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(DAY_SHORT[day - 1], style = T.body(15.sp, FontWeight.ExtraBold).copy(color = if (sessions.isEmpty()) F.c.text3 else F.c.text), modifier = Modifier.width(40.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (sessions.isEmpty()) Text("Отдых", style = T.body(15.sp).copy(color = F.c.text3))
                        sessions.forEach { s ->
                            // Done this week: said so; a run as it was run (the plan has moved on since), a free run in
                            // the place of a planned one as it is.
                            val done = weekItems.firstOrNull { it.date == dayDate }?.doneFor(s)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(if (s.kind == SessionKind.LIFT) Ic.dumbbell else Ic.run, null, tint = if (s.kind == SessionKind.LIFT) F.c.strength else F.c.endurance, modifier = Modifier.size(16.dp))
                                if (s.kind == SessionKind.LIFT) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(sessionTitle(s, p), style = T.body(15.sp, FontWeight.SemiBold))
                                        if (done != null) Text("Сделано", style = T.secondary(13.sp).copy(color = F.c.good))
                                    }
                                } else {
                                    // Each row on its own date this week: an easy run on a lifting day is shorter.
                                    val free = done != null && done.templateId == forma.core.run.RunType.FREE.name
                                    val w by produceState<RunWorkout?>(null, s.kind, dayDate, trialOn, done?.id) {
                                        value = when {
                                            free -> null
                                            done != null -> repo.runPlanOf(done.id) ?: repo.runFor(s.kind, dayDate, trial = dayDate == trialOn)
                                            else -> repo.runFor(s.kind, dayDate, trial = dayDate == trialOn)
                                        }
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(if (free) done!!.title else w?.title ?: sessionTitle(s, p), style = T.body(15.sp, FontWeight.SemiBold))
                                        // How: the lap time or pace to keep, so the week says what to do, not only what.
                                        if (done != null) Text(if (free) "Сделано вместо плановой" else "Сделано", style = T.secondary(13.sp).copy(color = F.c.good))
                                        else w?.let { Text(forma.app.ui.RunHow.short(it), style = T.secondary(13.sp)) }
                                    }
                                }
                            }
                        }
                    }
                    Icon(Ic.chevron, null, tint = F.c.text3, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                }
            }
        }
        Text("Нажми на день — там тренировка целиком: что, зачем и почему в этот день.", style = T.secondary(13.sp))

        GhostButton(if (p.templates.isNotEmpty() && p.aim.runs) "Поменять дни зала и бега" else "Поменять дни тренировок", { nav.push(Screen.Profile) }, Modifier.fillMaxWidth(), height = 48.dp)

        if (p.week.notes.isNotEmpty()) {
            SectionLabel("Почему так")
            p.week.notes.forEach { Text("• $it", style = T.secondary(14.sp)) }
        }

        SectionLabel("Силовые")
        p.templates.forEach { t -> TemplateCard(t) }

        PauseControl()

        GhostButton("Изменить цель, дни и данные", { nav.push(Screen.Profile) }, Modifier.fillMaxWidth(), height = 52.dp)
        profile?.let {
            fun days(s: Set<Int>) = s.sorted().joinToString(", ") { d -> DAY_SHORT[d - 1] }
            val gym = it.gymDays
            val run = it.runDays
            Text(
                if (gym != null && run != null) "Зал: ${days(gym).ifEmpty { "—" }} · бег: ${days(run).ifEmpty { "—" }} · до ${it.sessionMinutes} мин"
                else "Дни: ${days(it.trainingDays)} · до ${it.sessionMinutes} мин",
                style = T.secondary(13.sp),
            )
        }
    }
}

@Composable
private fun TemplateCard(t: Template) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val today = forma.app.ui.rememberToday(repo)
    val settings by repo.store.settings.collectAsState(initial = forma.app.data.Settings())
    val plan by produceState<LiftPlan?>(null, t.id, settings) { value = runCatching { repo.liftPlan(t.id, today) }.getOrNull() }
    // A plan exercise by its position: the plan's own first, then the ones added for good.
    var editing by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(-1) }
    val added = repo.addedTo(t.id, settings)
    FCard(gap = 0.dp) {
        Text(t.title, style = T.title(20.sp))
        Text("Нажми на упражнение: подходы, повторы, отдых, замена или убрать.", style = T.secondary(12.sp), modifier = Modifier.padding(bottom = 8.dp))
        t.slots.forEachIndexed { i, s ->
            Hairline()
            val item = plan?.items?.firstOrNull { it.position == i && !it.addedForGood }
            val removed = s.exerciseId in settings.removed && plan != null && item == null
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (removed) scope.launch { repo.setRemoved(s.exerciseId, false) } else editing = i
                }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val name = item?.name ?: forma.core.model.Catalog.find(settings.swaps[s.exerciseId] ?: s.exerciseId)?.name ?: s.exerciseId
                    Text(name, style = T.body(15.sp, FontWeight.SemiBold).copy(color = if (removed) F.c.text3 else F.c.text))
                    val now = item?.slot ?: s
                    // A single-joint exercise in the place of a heavy lift is done as one: said with the numbers.
                    val demoted = s.exerciseId in settings.demoted && settings.swaps[s.exerciseId]?.let { forma.app.data.TrainingRepository.demotes(s, it) } == true
                    // Only what the user changed, next to what the plan had.
                    val was = if (demoted) emptyList() else listOfNotNull(
                        if (now.sets != s.sets) "${s.sets} ${Ru.plural(s.sets, "подход", "подхода", "подходов")}" else null,
                        if (now.repMin != s.repMin || now.repMax != s.repMax) "${s.repMin}–${s.repMax}" else null,
                        if (now.restSec != s.restSec) "отдых ${Ru.clock(s.restSec)}" else null,
                    )
                    Text(
                        if (removed) "Убрано из плана · нажми, чтобы вернуть"
                        else specLine(now, timed(item?.exerciseId ?: s.exerciseId)) + when {
                            demoted -> " · вместо «${forma.core.model.Catalog.find(s.exerciseId)?.name ?: s.exerciseId}»: ${swapNote(s, now)}"
                            was.isNotEmpty() -> " · в плане было: ${was.joinToString(", ")}"
                            else -> ""
                        },
                        style = T.secondary(12.sp),
                    )
                }
                if (item != null) NumText(prescriptionShort(item.prescription.sets, item.prescription.reps, item.prescription.weight, item.exerciseId), style = T.number(14.sp, FontWeight.Bold))
            }
        }
        added.forEachIndexed { j, id ->
            Hairline()
            val pos = t.slots.size + j
            val item = plan?.items?.firstOrNull { it.position == pos && it.addedForGood }
            Row(Modifier.fillMaxWidth().clickable { editing = pos }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item?.name ?: forma.core.model.Catalog.find(id)?.name ?: id, style = T.body(15.sp, FontWeight.SemiBold))
                    Text("Добавлено тобой · " + specLine(item?.slot ?: repo.addedPlanned(id), timed(id)), style = T.secondary(12.sp))
                }
                if (item != null) NumText(prescriptionShort(item.prescription.sets, item.prescription.reps, item.prescription.weight, item.exerciseId), style = T.number(14.sp, FontWeight.Bold))
            }
        }
        Hairline()
        GhostButton(
            "Добавить упражнение", { nav.push(Screen.Library(forma.app.ui.LibraryMode.AddToPlan(t.id))) },
            Modifier.fillMaxWidth().padding(top = 10.dp), height = 48.dp,
        )
    }
    val e = editing
    val own = t.slots.getOrNull(e)
    val addedId = if (e >= t.slots.size) added.getOrNull(e - t.slots.size) else null
    // What the plan itself sets for this exercise (the user's changes are kept apart, so "как в плане" is always known);
    // with a single-joint exercise in the place of a heavy lift, what the plan sets for that one.
    val planned = own?.let { forma.app.data.TrainingRepository.plannedFor(it, settings) } ?: addedId?.let { repo.addedPlanned(it) }
    if (planned != null) {
        val planId = own?.exerciseId ?: planned.exerciseId
        // Where its sets, reps and rest are kept: under the plan's exercise, or for one added to this day, under the day.
        val key = if (own != null) planId else forma.app.data.TrainingRepository.addedKey(t.id, planId)
        val item = plan?.items?.firstOrNull { it.position == e && it.addedForGood == (own == null) }
        val kept = t.slots.count { it.exerciseId !in settings.removed } + added.size
        // The weight step belongs to the exercise done (after a swap, the one in its place), not to the plan's slot.
        val exId = item?.exerciseId ?: (if (own != null) settings.swaps[planId] else null) ?: planId
        val ex = forma.core.model.Catalog.find(exId)
        val loaded = ex != null && (ex.load == forma.core.model.LoadKind.EXTERNAL || ex.load == forma.core.model.LoadKind.BODYWEIGHT_PLUS)
        PlanExerciseDialog(
            name = item?.name ?: ex?.name ?: planId,
            // From the settings, not the plan being loaded: opened in that moment, an unchanged save kept the override.
            sets = settings.sets[key]?.coerceIn(1, forma.app.data.TrainingRepository.MAX_SETS) ?: planned.sets,
            planned = planned.sets,
            reps = settings.reps[key]?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: (planned.repMin to planned.repMax),
            plannedReps = planned.repMin to planned.repMax,
            timed = ex?.load == forma.core.model.LoadKind.TIMED,
            rest = settings.rest[key] ?: planned.restSec,
            plannedRest = planned.restSec,
            canRemove = kept > 1,
            step = if (loaded) settings.steps[exId] ?: ex!!.step else null,
            defaultStep = ex?.step ?: 2.5,
            onSave = { n, reps, rest, st ->
                scope.launch {
                    repo.tunePlan(key, planned, n, reps, rest)
                    if (st != null && ex != null) repo.setStep(exId, st, ex.step)
                }
                editing = -1
            },
            onSwap = {
                editing = -1
                nav.push(
                    Screen.Library(
                        if (own != null) forma.app.ui.LibraryMode.SwapInPlan(planId, item?.exerciseId ?: planId)
                        else forma.app.ui.LibraryMode.SwapAdded(t.id, planId),
                    ),
                )
            },
            onRemove = {
                scope.launch { if (own != null) repo.setRemoved(planId, true) else repo.removeAdded(t.id, planId) }
                editing = -1
            },
            removeNote = if (own != null) "Вернуть можно здесь же: убранное останется в списке серым." else "Добавить снова можно кнопкой «Добавить упражнение».",
            swapNote = if (own != null && planned != own) {
                "Вместо «${forma.core.model.Catalog.find(own.exerciseId)?.name ?: own.exerciseId}» — упражнение на одну мышцу, " +
                    "ему нужны другие повторы: ${swapNote(own, planned)}."
            } else null,
            onHistory = {
                editing = -1
                nav.push(Screen.Exercise(exId))
            },
            onDismiss = { editing = -1 },
        )
    }
}

private fun timed(exerciseId: String) = forma.core.model.Catalog.find(exerciseId)?.load == forma.core.model.LoadKind.TIMED

/** "3 × 6–10 · запас 2 · отдых ~2:00" for a plan row (the rest is worked out after each set around it). */
private fun specLine(s: forma.core.strength.SlotSpec, timed: Boolean): String =
    "${s.sets} × ${s.repMin}–${s.repMax}${if (timed) " с" else ""} · запас ${s.rir} · отдых ~${Ru.clock(s.restSec)}"

/** Rep ranges to pick from: strength, strength and size, size, size and endurance. */
private val REP_RANGES = listOf(3 to 5, 5 to 8, 6 to 10, 8 to 12, 10 to 15, 12 to 20)
private val HOLD_RANGES = listOf(20 to 40, 30 to 60, 45 to 90)
private val REST_OPTIONS = listOf(60, 90, 120, 180, 240, forma.app.data.TrainingRepository.MAX_REST)

/** "было 3×5–8, стало 3×10–15": what a swap changed in the numbers. */
internal fun swapNote(was: forma.core.strength.SlotSpec, now: forma.core.strength.SlotSpec): String =
    "было ${was.sets}×${was.repMin}–${was.repMax}, стало ${now.sets}×${now.repMin}–${now.repMax}"

/** One exercise of the plan from now on: sets, rep range, rest, the weight step, another exercise in its place, or out. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PlanExerciseDialog(
    name: String, sets: Int, planned: Int, reps: Pair<Int, Int>, plannedReps: Pair<Int, Int>, timed: Boolean,
    rest: Int, plannedRest: Int, canRemove: Boolean, step: Double?, defaultStep: Double,
    onSave: (Int, Pair<Int, Int>, Int, Double?) -> Unit, onSwap: () -> Unit, onRemove: () -> Unit, removeNote: String,
    swapNote: String?, onHistory: () -> Unit, onDismiss: () -> Unit,
) {
    var n by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(sets) }
    var lo by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(reps.first) }
    var hi by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(reps.second) }
    var rs by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(rest) }
    var st by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(step) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = F.c.surface,
        title = { Text(name, style = T.title(20.sp)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                swapNote?.let { Text(it, style = T.secondary(13.sp).copy(color = F.c.text)) }
                Text("Подходов в каждой тренировке", style = T.secondary(13.sp))
                forma.app.ui.components.Stepper(
                    "$n", if (n == planned) "как в плане" else "в плане $planned",
                    { n = (n - 1).coerceAtLeast(1) }, { n = (n + 1).coerceAtMost(forma.app.data.TrainingRepository.MAX_SETS) },
                    Modifier.fillMaxWidth(), "Меньше подходов", "Больше подходов",
                )
                Text(if (timed) "Секунд в подходе" else "Повторов в подходе", style = T.secondary(13.sp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ((if (timed) HOLD_RANGES else REP_RANGES) + plannedReps).distinct().sortedWith(compareBy({ it.first }, { it.second })).forEach { r ->
                        forma.app.ui.components.Chip("${r.first}–${r.second}", lo == r.first && hi == r.second, { lo = r.first; hi = r.second }, minWidth = 64.dp)
                    }
                }
                if (lo != plannedReps.first || hi != plannedReps.second) {
                    Text(
                        "В плане ${plannedReps.first}–${plannedReps.second}." +
                            if (timed) "" else " Вес под новый диапазон посчитаю по прошлым подходам, а если их не было — подберём в первой тренировке.",
                        style = T.secondary(12.sp),
                    )
                }
                Text("Отдых между подходами", style = T.secondary(13.sp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (REST_OPTIONS + plannedRest).distinct().sorted().forEach { v ->
                        forma.app.ui.components.Chip(Ru.clock(v), rs == v, { rs = v }, minWidth = 64.dp)
                    }
                }
                Text(
                    if (rs != plannedRest) "В плане ${Ru.clock(plannedRest)}. Свой отдых приложение не меняет — он будет одинаковым после любого подхода."
                    else "Это середина: после каждого подхода отдых считается заново — дольше после тяжёлого подхода и на большом весе, " +
                        "короче, когда был запас, и с поправкой на то, как ты восстанавливаешься в этом упражнении. Выберешь своё — будет всегда таким.",
                    style = T.secondary(12.sp),
                )
                val chosen = st
                if (chosen != null) {
                    // Weights come in the steps a gym really has: with 2.5 kg plates the barbell goes in 5 kg steps.
                    Text("Шаг веса — как позволяют блины и гантели в твоём зале", style = T.secondary(13.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (listOf(1.0, 2.0, 2.5, 5.0) + defaultStep).distinct().sorted().forEach { v ->
                            forma.app.ui.components.Chip(Ru.kg(v), chosen == v, { st = v }, Modifier.weight(1f), minWidth = 0.dp)
                        }
                    }
                }
                GhostButton("История и прогресс", onHistory, Modifier.fillMaxWidth())
                GhostButton("Заменить упражнение", onSwap, Modifier.fillMaxWidth())
                if (canRemove) {
                    GhostButton("Убрать из плана", onRemove, Modifier.fillMaxWidth(), color = F.c.bad)
                    Text(removeNote, style = T.secondary(12.sp))
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton({ onSave(n, lo to hi, rs, st) }) { Text("Сохранить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onDismiss) { Text("Отмена", style = T.body(15.sp)) } },
    )
}

/** Vacation or illness: the plan stops expecting sessions and brings the person back gently. */
@Composable
private fun PauseControl() {
    val repo = LocalRepo.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val settings by repo.store.settings.collectAsState(initial = forma.app.data.Settings())
    val today = forma.app.ui.rememberToday(repo)
    SectionLabel("Пауза")
    FCard(gap = 10.dp) {
        if (repo.paused(settings, today)) {
            Text("Пауза до ${shortDate(java.time.LocalDate.ofEpochDay(settings.pauseUntil!!))}", style = T.title(20.sp))
            GhostButton("Вернуться сегодня", { scope.launch { repo.resume(today) } }, Modifier.fillMaxWidth())
        } else {
            Text(
                "Отпуск, командировка или болезнь? Поставь паузу: пропуски не будут висеть, после возвращения первые тренировки " +
                    "будут легче, а после паузы в неделю и больше цикл начнётся заново.",
                style = T.secondary(14.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3).forEach { w ->
                    forma.app.ui.components.Chip(Ru.weeks(w), false, {
                        scope.launch { repo.pause(today, today.plusDays(w * 7L - 1)) }
                    }, Modifier.weight(1f), kind = forma.app.ui.components.ChipKind.ACTION)
                }
            }
        }
    }
}
