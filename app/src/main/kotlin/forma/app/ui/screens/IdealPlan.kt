package forma.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.Settings
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.components.Banner
import forma.app.ui.components.Chip
import forma.app.ui.components.FCard
import forma.app.ui.components.Hairline
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.Segmented
import forma.app.ui.components.TopBar
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.core.model.Catalog
import forma.core.model.LoadKind
import forma.core.plan.PlanAdvisor
import forma.core.text.Ru
import kotlinx.coroutines.launch

/**
 * «Идеальный план»: the gym plan as it is and as it could be, each change with its reasons and options. Nothing is
 * ticked at first: the plan changes only by what the person picks, through the same edits as the plan screen.
 */
@Composable
fun IdealPlanScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val settings by repo.store.settings.collectAsState(initial = Settings())
    val program by repo.store.program.collectAsState(initial = null)
    // Loaded: Result of the advice (null inside — no plan to weigh); not loaded yet: null.
    val loaded by produceState<Result<PlanAdvisor.Advice?>?>(null, settings, program) {
        // A new key cancels the old reading: that is not a failure to show.
        value = try {
            Result.success(repo.planAdvice())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    // Ticked change ids, and "id=option" for an option picked other than the first (saved across rotation).
    var ticked by rememberSaveable { mutableStateOf(listOf<String>()) }
    var picks by rememberSaveable { mutableStateOf(listOf<String>()) }
    var taken by rememberSaveable { mutableIntStateOf(0) }
    var view by rememberSaveable { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var all by rememberSaveable { mutableStateOf(false) }
    ScrollScreen {
        TopBar("Идеальный план", "Какой он сейчас и каким может быть", onBack = { nav.back() })
        val result = loaded
        val a = result?.getOrNull()
        if (a == null) {
            Text(
                when {
                    result == null -> "Разбираю план…"
                    result.isFailure -> "Не получилось разобрать план. Открой экран ещё раз."
                    else -> "Плана зала пока нет — разбирать нечего."
                },
                style = T.secondary(14.sp),
            )
            return@ScrollScreen
        }
        fun picked(c: PlanAdvisor.Change): PlanAdvisor.Change =
            picks.firstOrNull { it.startsWith("${c.id}=") }?.substringAfter('=')?.let { c.pick(it) } ?: c
        val changes = a.changes.map(::picked)
        // Two ticked changes putting the same exercise into one day: the later one waits for another option.
        val clash = mutableMapOf<String, String>()
        val chosen = mutableListOf<PlanAdvisor.Change>()
        for (c in changes.filter { it.id in ticked }) {
            val other = chosen.firstOrNull { o -> o.templateId == c.templateId && o.to == c.to }
            if (other != null) clash[c.id] = changeTitle(other) else chosen += c
        }
        // The week as taking them makes it: the same edits as «Принять», read the same way as the plan.
        val chosenKey = chosen.map { "${it.id}=${it.to}" }
        val preview by produceState<List<PlanAdvisor.Day>?>(null, chosenKey, settings) {
            value = null
            value = runCatching { repo.planPreview(chosen) }.getOrNull()
        }
        val after = preview ?: PlanAdvisor.apply(a.days, chosen)

        FCard(gap = 8.dp) {
            SectionLabel("Разбор")
            Text(a.summary, style = T.body(16.sp, FontWeight.SemiBold))
            Text(
                "Смотрю на всё: цель и опыт, бег, подходы на каждую мышцу за неделю, жимы и тяги, рост весов, " +
                    "длину тренировки и как ты восстанавливаешься. Что ты сам заменил, убрал или пропускаешь, обратно не предлагаю. " +
                    "Тяжёлые непривычные упражнения — только если видно, что они тебе нравятся. " +
                    "Сам план не меняется: отметь, что берёшь.",
                style = T.secondary(13.sp),
            )
        }
        a.warning?.let { Banner(it, F.c.warn) }
        if (taken > 0) Banner(
            "Принято: $taken. Всё видно в «Плане», там же любое изменение можно вернуть.", F.c.good, icon = Ic.check,
        )

        // The most important first; a long list folds after a few (a ticked one stays in view).
        val shown = if (all || changes.size <= SHOWN) changes.size else maxOf(SHOWN, changes.indexOfLast { it.id in ticked } + 1)
        if (changes.isNotEmpty()) {
            SectionLabel("Что можно улучшить")
            changes.take(shown).forEachIndexed { k, c ->
                ChangeCard(
                    c, a.changes[k].options, checked = c.id in ticked, clash = clash[c.id],
                    onCheck = { ticked = if (c.id in ticked) ticked - c.id else ticked + c.id },
                    onPick = { id -> picks = picks.filterNot { it.startsWith("${c.id}=") } + "${c.id}=$id" },
                    onHide = {
                        ticked = ticked - c.id
                        scope.launch { repo.hideAdvice(c.id) }
                    },
                )
            }
        }

        if (changes.size > shown) {
            val more = changes.size - shown
            forma.app.ui.components.GhostButton(
                "Показать ещё $more", { all = true }, Modifier.fillMaxWidth(), height = 48.dp,
            )
        }
        if (a.hidden > 0) forma.app.ui.components.GhostButton(
            "Скрыто по твоей просьбе: ${a.hidden} — показать снова", { scope.launch { repo.showHiddenAdvice() } }, Modifier.fillMaxWidth(), height = 48.dp,
        )

        SectionLabel("Неделя в зале")
        if (changes.isNotEmpty()) Segmented(listOf("Сейчас", "С выбранным"), view, { view = it })
        val days = if (view == 1) after else a.days
        days.forEach { d -> DayCard(d, a.days.first { it.templateId == d.templateId }, compare = view == 1) }
        if (view == 1 && chosen.isEmpty() && changes.isNotEmpty()) Text("Отметь изменения выше — здесь появится, каким станет план.", style = T.secondary(13.sp))

        SectionLabel("Подходы в неделю на мышцу")
        FCard(gap = 0.dp) {
            PlanAdvisor.table(a, after, Catalog::find).forEachIndexed { i, v ->
                if (i > 0) Hairline()
                VolumeRow(v, showAfter = chosen.isNotEmpty())
            }
        }
        Text(
            "Подход идёт в счёт целиком для главной мышцы упражнения и наполовину — для помогающих. " +
                "Норма зависит от цели и опыта. Если ты бегаешь, ногам нужно меньше: бег тоже их нагружает.",
            style = T.secondary(13.sp),
        )

        if (a.held.isNotEmpty()) {
            SectionLabel("Что не предлагаю сейчас")
            FCard(gap = 8.dp) { a.held.forEach { Text("• $it", style = T.body(15.sp)) } }
        }

        if (a.fine.isNotEmpty()) {
            SectionLabel("Что уже хорошо")
            FCard(gap = 8.dp) { a.fine.forEach { Text("• $it", style = T.body(15.sp)) } }
        }

        if (changes.isNotEmpty()) PrimaryButton(
            if (chosen.isEmpty()) "Отметь, что взять" else "Принять выбранные (${chosen.size})",
            {
                val take = chosen
                busy = true
                scope.launch {
                    try {
                        repo.applyPlanAdvice(take)
                        taken = take.size
                        ticked = emptyList()
                        picks = emptyList()
                        view = 1
                    } finally {
                        busy = false
                    }
                }
            },
            Modifier.fillMaxWidth(), enabled = chosen.isNotEmpty() && !busy,
        )
    }
}

/** Changes shown before "Показать ещё". */
private const val SHOWN = 6

private fun name(id: String?) = id?.let { Catalog.find(it)?.name ?: it } ?: ""

/** The change in one line: "Разгибание ног → Болгарские сплит-приседания", "+ Нордические сгибания · 2 × 4–8". */
internal fun changeTitle(c: PlanAdvisor.Change): String = when (c.kind) {
    PlanAdvisor.Kind.SWAP -> "${name(c.from)} → ${name(c.to)}" + if (c.to == c.planId && !c.added) " (как было в плане)" else ""
    PlanAdvisor.Kind.ADD -> "+ ${name(c.to)}" + (c.setsTo?.let { s -> " · $s × " + (c.repsTo?.let { "${it.first}–${it.second}" } ?: "10–15") } ?: "")
    PlanAdvisor.Kind.SETS -> "${name(c.from)}: ${c.setsFrom} → ${Ru.sets(c.setsTo ?: 0)}"
}

@Composable
private fun ChangeCard(
    c: PlanAdvisor.Change, options: List<String>, checked: Boolean, clash: String?,
    onCheck: () -> Unit, onPick: (String) -> Unit, onHide: () -> Unit,
) {
    FCard(outline = if (checked) F.c.accent else null, gap = 10.dp) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { onCheck() }),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top,
        ) {
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier.padding(top = 2.dp).size(24.dp).clip(shape).background(if (checked) F.c.accent else F.c.surface)
                    .border(1.5.dp, if (checked) F.c.accent else F.c.text3, shape),
                contentAlignment = Alignment.Center,
            ) { if (checked) Icon(Ic.check, null, tint = F.c.onAccent, modifier = Modifier.size(18.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.dayTitle, style = T.secondary(12.sp))
                Text(changeTitle(c), style = T.body(16.sp, FontWeight.Bold))
            }
        }
        // The options in their own order (the most fitting first), whichever is picked.
        if (options.size > 1) {
            Text(if (c.kind == PlanAdvisor.Kind.ADD) "Что добавить:" else "Чем заменить:", style = T.secondary(13.sp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { id -> Chip(name(id), selected = id == c.to, onClick = { onPick(id) }, minWidth = 0.dp) }
            }
        }
        if (clash != null) Text("«${name(c.to)}» в этот день уже стоит в изменении «$clash». Выбери другой вариант.", style = T.secondary(13.sp).copy(color = F.c.warn))
        Text(c.why, style = T.secondary(14.sp))
        // Not wanted: never again (until shown again at the bottom) — and the advisor learns from it.
        Text(
            "Не предлагать",
            style = T.body(14.sp, FontWeight.SemiBold).copy(color = F.c.text2),
            modifier = Modifier.align(Alignment.End).clickable(role = Role.Button, onClick = onHide).padding(vertical = 10.dp, horizontal = 4.dp),
        )
    }
}

/** A day of the plan; with [compare], what changes against [before] is marked. */
@Composable
private fun DayCard(d: PlanAdvisor.Day, before: PlanAdvisor.Day, compare: Boolean) {
    FCard(gap = 0.dp) {
        Text(d.title + if (d.times > 1) " · ${Ru.times(d.times)} в неделю" else "", style = T.title(18.sp), modifier = Modifier.padding(bottom = 6.dp))
        d.items.forEach { i ->
            Hairline()
            val was = before.items.firstOrNull { it.planId == i.planId && it.added == i.added }
            val note = when {
                !compare -> null
                was == null -> "новое"
                was.exerciseId != i.exerciseId -> "вместо «${name(was.exerciseId)}»"
                was.sets != i.sets || was.repMin != i.repMin || was.repMax != i.repMax -> "было ${was.sets} × ${was.repMin}–${was.repMax}"
                else -> null
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (note == "новое") "+ " else "") + name(i.exerciseId),
                        style = T.body(15.sp, FontWeight.SemiBold).copy(color = if (note != null) F.c.accent else F.c.text),
                    )
                    if (note != null) Text(note, style = T.secondary(12.sp).copy(color = F.c.accent))
                }
                val timed = Catalog.find(i.exerciseId)?.load == LoadKind.TIMED
                Text("${i.sets} × ${i.repMin}–${i.repMax}${if (timed) " с" else ""}", style = T.secondary(14.sp))
            }
        }
    }
}

@Composable
private fun VolumeRow(v: PlanAdvisor.Volume, showAfter: Boolean) {
    val value = if (showAfter) v.after else v.now
    val color = when {
        v.low > 0 && value < v.low - 1 -> F.c.warn
        value > v.high + 2 -> F.c.warn
        else -> F.c.good
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(v.muscle.ru, style = T.body(15.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (showAfter && v.after != v.now) "${num(v.now)} → ${num(v.after)}" else num(value),
                style = T.body(15.sp, FontWeight.Bold).copy(color = color),
            )
            Text("норма ${v.low}–${v.high}", style = T.secondary(12.sp))
        }
    }
}

private fun num(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else Ru.one(x)
