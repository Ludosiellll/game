package forma.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.LibraryMode
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.components.Chip
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.TopBar
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.core.model.Catalog
import forma.core.model.Equipment
import forma.core.model.Exercise
import forma.core.model.ExerciseSearch
import forma.core.model.LoadKind
import forma.core.model.Muscle
import kotlinx.coroutines.launch

/** The exercise library: search, filters, and picking a replacement or an addition for a session or the plan. */
@Composable
fun LibraryScreen(mode: LibraryMode) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var muscle by rememberSaveable { mutableStateOf<Muscle?>(null) }
    var equipment by rememberSaveable { mutableStateOf<Equipment?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Exercise?>(null) }
    var adding by remember { mutableStateOf<Exercise?>(null) }
    // Recompose when own exercises change.
    val own by repo.store.customExercises.collectAsState(initial = emptyList())

    val current = when (mode) {
        is LibraryMode.SwapInWorkout -> Catalog[mode.exerciseId]
        is LibraryMode.SwapInPlan -> Catalog[mode.currentId]
        is LibraryMode.SwapAdded -> Catalog[mode.exerciseId]
        else -> null
    }
    val pool = remember(own, current) {
        Catalog.all.filter { current == null || (it.load == LoadKind.TIMED) == (current.load == LoadKind.TIMED) }
    }
    val results = remember(query, muscle, equipment, pool) { ExerciseSearch.search(query, pool, muscle, equipment) }
    val similar = remember(current, own) { current?.let { repo.alternativesFor(it.id).take(8) }.orEmpty() }
    val browsing = query.isBlank() && muscle == null && equipment == null

    fun pick(ex: Exercise) {
        when (mode) {
            LibraryMode.Browse -> nav.push(Screen.Exercise(ex.id))
            is LibraryMode.SwapInWorkout -> confirm = ex
            is LibraryMode.SwapInPlan -> scope.launch { repo.setSwap(mode.planExerciseId, ex.id); nav.back() }
            is LibraryMode.AddToWorkout -> adding = ex
            is LibraryMode.AddToPlan -> scope.launch { repo.addToPlan(mode.templateId, ex.id); nav.back() }
            is LibraryMode.SwapAdded -> scope.launch { repo.replaceAdded(mode.templateId, mode.exerciseId, ex.id); nav.back() }
        }
    }
    val program by repo.store.program.collectAsState(initial = null)
    fun templateTitle(id: String) = program?.templates?.firstOrNull { it.id == id }?.title ?: "этого дня"

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        item {
            val (title, subtitle) = when (mode) {
                LibraryMode.Browse -> "Упражнения" to Catalog.all.size.let { n -> "$n ${forma.core.text.Ru.plural(n, "упражнение", "упражнения", "упражнений")} и их вариации" }
                is LibraryMode.SwapInWorkout -> "Замена" to "Вместо «${current?.name}»"
                is LibraryMode.SwapInPlan -> "Замена в плане" to "Вместо «${Catalog[mode.planExerciseId].name}»"
                is LibraryMode.AddToWorkout -> "Добавить упражнение" to "В эту тренировку или в план"
                is LibraryMode.AddToPlan -> "Добавить в план" to "В каждую тренировку «${templateTitle(mode.templateId)}»"
                is LibraryMode.SwapAdded -> "Замена в плане" to "Вместо «${current?.name}»"
            }
            TopBar(title, subtitle, onBack = { nav.back() })
        }
        item {
            Column(Modifier.padding(top = 10.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextInput(query, { query = it }, "Поиск", placeholder = "присед, жим лёжа, бицепс, гантели…")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Все мышцы", muscle == null, { muscle = null })
                    Muscle.entries.forEach { m -> Chip(m.ru, muscle == m, { muscle = if (muscle == m) null else m }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Любое", equipment == null, { equipment = null })
                    Equipment.entries.forEach { e -> Chip(e.ru, equipment == e, { equipment = if (equipment == e) null else e }) }
                }
                if (mode is LibraryMode.SwapInPlan && mode.currentId != mode.planExerciseId) {
                    GhostButton("Вернуть «${Catalog[mode.planExerciseId].name}»", {
                        scope.launch { repo.setSwap(mode.planExerciseId, null); nav.back() }
                    }, Modifier.fillMaxWidth())
                }
            }
        }
        if (browsing && similar.isNotEmpty()) {
            item { SectionLabel("Похожие по движению", Modifier.padding(top = 8.dp, bottom = 4.dp)) }
            items(similar, key = { "similar-" + it.id }) { ExerciseRow(it) { pick(it) } }
            item { SectionLabel("Все", Modifier.padding(top = 14.dp, bottom = 4.dp)) }
        }
        if (query.isBlank()) {
            // Browsing: grouped by movement, so related exercises sit together.
            results.groupBy { it.pattern }.forEach { (pattern, list) ->
                item(key = "pattern-" + pattern.name) { SectionLabel(pattern.ru, Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                items(list, key = { it.id }) { ExerciseRow(it) { pick(it) } }
            }
        } else {
            items(results, key = { it.id }) { ExerciseRow(it) { pick(it) } }
        }
        if (results.isEmpty()) {
            item { Text("Ничего не нашлось. Можно добавить своё упражнение.", style = T.secondary(14.sp), modifier = Modifier.padding(vertical = 16.dp)) }
        }
        item {
            GhostButton("Своё упражнение", { creating = true }, Modifier.fillMaxWidth().padding(top = 12.dp))
            Spacer(Modifier.height(24.dp))
        }
    }

    val swap = confirm
    if (swap != null && mode is LibraryMode.SwapInWorkout) {
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = F.c.surface,
            title = { Text("Заменить на «${swap.name}»?", style = T.title(20.sp)) },
            text = {
                Text(
                    "Только сегодня — план не меняется. Насовсем — это упражнение будет вместо «${current?.name}» во всех " +
                        "следующих тренировках; вернуть можно в плане.",
                    style = T.secondary(14.sp),
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton({
                        confirm = null
                        scope.launch { repo.swapExercise(mode.workoutId, mode.position, swap.id, forever = true); nav.back() }
                    }) { Text("Насовсем", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
                    TextButton({
                        confirm = null
                        scope.launch { repo.swapExercise(mode.workoutId, mode.position, swap.id, forever = false); nav.back() }
                    }) { Text("Только сегодня", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
                }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Отмена", style = T.body(15.sp)) } },
        )
    }
    val add = adding
    if (add != null && mode is LibraryMode.AddToWorkout) {
        val title by produceState<String?>(null, mode.workoutId) { value = repo.db.workouts().get(mode.workoutId)?.title }
        AlertDialog(
            onDismissRequest = { adding = null },
            containerColor = F.c.surface,
            title = { Text("Добавить «${add.name}»?", style = T.title(20.sp)) },
            text = {
                Text(
                    "Только сегодня — план не меняется. В план насовсем — упражнение будет в каждой тренировке " +
                        "«${title ?: "этого дня"}» после упражнений плана; подходы, повторы и отдых меняются в плане, там же его можно убрать.",
                    style = T.secondary(14.sp),
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton({
                        adding = null
                        scope.launch { repo.addToWorkout(mode.workoutId, add.id, forever = true); nav.back() }
                    }) { Text("В план насовсем", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
                    TextButton({
                        adding = null
                        scope.launch { repo.addToWorkout(mode.workoutId, add.id, forever = false); nav.back() }
                    }) { Text("Только сегодня", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) }
                }
            },
            dismissButton = { TextButton({ adding = null }) { Text("Отмена", style = T.body(15.sp)) } },
        )
    }
    if (creating) {
        CustomExerciseDialog(initialName = query, onDismiss = { creating = false }) { ex ->
            creating = false
            scope.launch {
                val saved = repo.addCustom(ex)
                if (mode != LibraryMode.Browse) pick(saved)
            }
        }
    }
}

@Composable
private fun ExerciseRow(ex: Exercise, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Hairline()
        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ex.name + if (ex.custom) "  · своё" else "", style = T.body(16.sp, FontWeight.SemiBold))
            Text(
                (ex.primary.map { it.ru } + ex.equipment.ru).joinToString(" · ") +
                    when (ex.load) {
                        LoadKind.TIMED -> " · на время"
                        LoadKind.BODYWEIGHT_PLUS -> if (ex.equipment == Equipment.BODYWEIGHT) "" else " · свой вес"
                        LoadKind.EXTERNAL -> ""
                    },
                style = T.secondary(13.sp),
            )
        }
    }
}

/** A new own exercise: name, equipment, main muscle and how the load is counted; the rest comes from those. */
@Composable
private fun CustomExerciseDialog(initialName: String, onDismiss: () -> Unit, onSave: (Exercise) -> Unit) {
    var name by remember { mutableStateOf(initialName.trim().replaceFirstChar { it.uppercase() }) }
    var equipment by remember { mutableStateOf(Equipment.MACHINE) }
    var muscle by rememberSaveable { mutableStateOf<Muscle?>(null) }
    var load by remember { mutableStateOf(LoadKind.EXTERNAL) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = F.c.surface,
        title = { Text("Своё упражнение", style = T.title(20.sp)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextInput(name, { name = it }, "Название", maxLength = 60)
                Text("Чем", style = T.secondary(13.sp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Equipment.entries.forEach { e ->
                        Chip(e.ru, equipment == e, {
                            equipment = e
                            load = if (e == Equipment.BODYWEIGHT) LoadKind.BODYWEIGHT_PLUS else LoadKind.EXTERNAL
                        })
                    }
                }
                Text("Главная мышца", style = T.secondary(13.sp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Muscle.entries.forEach { m -> Chip(m.ru, muscle == m, { muscle = m }) }
                }
                Text("Что записывать", style = T.secondary(13.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        (if (equipment == Equipment.BODYWEIGHT) LoadKind.BODYWEIGHT_PLUS else LoadKind.EXTERNAL) to "Вес и повторы",
                        LoadKind.TIMED to "Время",
                    ).forEach { (k, label) -> Chip(label, load == k, { load = k }, Modifier.weight(1f)) }
                }
            }
        },
        confirmButton = {
            val m = muscle
            TextButton({
                if (m != null && name.isNotBlank()) {
                    onSave(Catalog.makeCustom("custom_" + java.util.UUID.randomUUID().toString().take(8), name, equipment, m, load))
                }
            }, enabled = muscle != null && name.isNotBlank()) {
                Text("Сохранить", style = T.body(15.sp, FontWeight.Bold).copy(color = if (muscle != null && name.isNotBlank()) F.c.accent else F.c.text3))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена", style = T.body(15.sp)) } },
    )
}
