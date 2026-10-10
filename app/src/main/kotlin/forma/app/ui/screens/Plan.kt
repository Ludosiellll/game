package forma.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
    val weekNo = ProgramGenerator.weekIndex(p, today)
    ScrollScreen {
        Text(if (F.type.capsTitles) "ПЛАН" else "План", style = T.screenTitle(), modifier = Modifier.padding(top = 8.dp))
        val goal = aimTitle(p.aim) + (p.copiedFrom?.let { " · программа от $it" } ?: "")
        Text("$goal · ${when (p.experience) { Experience.NOVICE -> "новичок"; Experience.INTERMEDIATE -> "средний уровень"; Experience.ADVANCED -> "опытный" }}", style = T.secondary(15.sp))

        FCard(gap = 6.dp) {
            SectionLabel("Цикл")
            if (p.blockWeeks > 0) {
                val pos = (weekNo - 1) % p.blockWeeks + 1
                Text("Неделя $pos из ${p.blockWeeks}", style = T.title(22.sp))
                val off = ProgramGenerator.rirOffset(p, today)
                Text(
                    when {
                        pos == p.blockWeeks -> "Разгрузка: вдвое меньше подходов, вес −10 %. Дальше новый цикл с тех же весов."
                        off > 0 -> "Вход в цикл: в каждом подходе оставляй на 1 повтор больше запаса."
                        off < 0 -> "Пик цикла: запаса на 1 повтор меньше. Следующая неделя — разгрузка."
                        else -> "Рабочая неделя: запас как в плане."
                    }, style = T.secondary(14.sp),
                )
            } else {
                Text("Неделя $weekNo", style = T.title(22.sp))
                Text("Новичкам плановая разгрузка не нужна: приложение даст лёгкую неделю само, если увидит, что веса откатываются.", style = T.secondary(14.sp))
            }
        }

        SectionLabel("Неделя")
        FCard(gap = 0.dp) {
            (1..7).forEach { day ->
                if (day > 1) Hairline()
                val sessions = p.week.on(day)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
                    Text(DAY_SHORT[day - 1], style = T.body(15.sp, FontWeight.ExtraBold).copy(color = if (sessions.isEmpty()) F.c.text3 else F.c.text), modifier = Modifier.width(40.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (sessions.isEmpty()) Text("Отдых", style = T.body(15.sp).copy(color = F.c.text3))
                        sessions.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(if (s.kind == SessionKind.LIFT) Ic.dumbbell else Ic.run, null, tint = if (s.kind == SessionKind.LIFT) F.c.strength else F.c.endurance, modifier = Modifier.size(16.dp))
                                if (s.kind == SessionKind.LIFT) {
                                    Text(sessionTitle(s, p), style = T.body(15.sp, FontWeight.SemiBold))
                                } else {
                                    // Each row on its own date this week: an easy run on a lifting day is shorter.
                                    val date = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).plusDays(day - 1L)
                                    val w by produceState<RunWorkout?>(null, s.kind, date) { value = repo.runFor(s.kind, date) }
                                    Text(w?.title ?: sessionTitle(s, p), style = T.body(15.sp, FontWeight.SemiBold))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (p.week.notes.isNotEmpty()) {
            SectionLabel("Почему так")
            p.week.notes.forEach { Text("• $it", style = T.secondary(14.sp)) }
        }

        SectionLabel("Силовые")
        p.templates.forEach { t -> TemplateCard(t) }

        PauseControl()

        GhostButton("Изменить цель, дни и данные", { nav.push(Screen.Profile) }, Modifier.fillMaxWidth(), height = 52.dp)
        profile?.let { Text("Дни: ${it.trainingDays.sorted().joinToString(", ") { d -> DAY_SHORT[d - 1] }} · до ${it.sessionMinutes} мин", style = T.secondary(13.sp)) }
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
    var editing by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(-1) }
    FCard(gap = 0.dp) {
        Text(t.title, style = T.title(20.sp))
        Text("Нажми на упражнение: подходы, замена или убрать.", style = T.secondary(12.sp), modifier = Modifier.padding(bottom = 8.dp))
        t.slots.forEachIndexed { i, s ->
            Hairline()
            val item = plan?.items?.firstOrNull { it.position == i }
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
                    val sets = item?.slot?.sets ?: s.sets
                    Text(
                        if (removed) "Убрано из плана · нажми, чтобы вернуть"
                        else "$sets × ${s.repMin}–${s.repMax}${if (s.exerciseId == "plank") " с" else ""} · запас ${s.rir} · отдых ${Ru.clock(s.restSec)}" +
                            if (sets != s.sets) " · в плане было ${s.sets}" else "",
                        style = T.secondary(12.sp),
                    )
                }
                if (item != null) NumText(prescriptionShort(item.prescription.sets, item.prescription.reps, item.prescription.weight, item.exerciseId), style = T.number(14.sp, FontWeight.Bold))
            }
        }
    }
    val e = editing
    val slot = t.slots.getOrNull(e)
    if (slot != null) {
        val item = plan?.items?.firstOrNull { it.position == e }
        val kept = t.slots.count { it.exerciseId !in settings.removed }
        // The weight step belongs to the exercise done (after a swap, the one in its place), not to the plan's slot.
        val exId = item?.exerciseId ?: settings.swaps[slot.exerciseId] ?: slot.exerciseId
        val ex = forma.core.model.Catalog.find(exId)
        val loaded = ex != null && (ex.load == forma.core.model.LoadKind.EXTERNAL || ex.load == forma.core.model.LoadKind.BODYWEIGHT_PLUS)
        PlanExerciseDialog(
            name = item?.name ?: slot.exerciseId,
            // From the settings, not the plan being loaded: opened in that moment, an unchanged save kept the override.
            sets = settings.sets[slot.exerciseId]?.coerceIn(1, forma.app.data.TrainingRepository.MAX_SETS) ?: slot.sets,
            planned = slot.sets,
            canRemove = kept > 1,
            step = if (loaded) settings.steps[exId] ?: ex!!.step else null,
            defaultStep = ex?.step ?: 2.5,
            onSave = { n, st ->
                scope.launch {
                    repo.setPlanSets(slot.exerciseId, n, slot.sets)
                    if (st != null && ex != null) repo.setStep(exId, st, ex.step)
                }
                editing = -1
            },
            onSwap = {
                editing = -1
                nav.push(Screen.Library(forma.app.ui.LibraryMode.SwapInPlan(slot.exerciseId, item?.exerciseId ?: slot.exerciseId)))
            },
            onRemove = { scope.launch { repo.setRemoved(slot.exerciseId, true) }; editing = -1 },
            onDismiss = { editing = -1 },
        )
    }
}

/** One exercise of the plan: how many sets from now on, another exercise in its place, or out of the plan. */
@Composable
private fun PlanExerciseDialog(
    name: String, sets: Int, planned: Int, canRemove: Boolean, step: Double?, defaultStep: Double,
    onSave: (Int, Double?) -> Unit, onSwap: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit,
) {
    var n by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(sets) }
    var st by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(step) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = F.c.surface,
        title = { Text(name, style = T.title(20.sp)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Подходов в каждой тренировке", style = T.secondary(13.sp))
                forma.app.ui.components.Stepper(
                    "$n", if (n == planned) "как в плане" else "в плане $planned",
                    { n = (n - 1).coerceAtLeast(1) }, { n = (n + 1).coerceAtMost(forma.app.data.TrainingRepository.MAX_SETS) },
                    Modifier.fillMaxWidth(), "Меньше подходов", "Больше подходов",
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
                GhostButton("Заменить упражнение", onSwap, Modifier.fillMaxWidth())
                if (canRemove) {
                    GhostButton("Убрать из плана", onRemove, Modifier.fillMaxWidth(), color = F.c.bad)
                    Text("Вернуть можно здесь же: убранное останется в списке серым.", style = T.secondary(12.sp))
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton({ onSave(n, st) }) { Text("Сохранить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
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
