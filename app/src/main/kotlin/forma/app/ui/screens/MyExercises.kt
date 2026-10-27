package forma.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.TrainingRepository
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.components.Chip
import forma.app.ui.components.ChipKind
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.TopBar
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.core.model.Catalog
import forma.core.model.Cues
import forma.core.model.Exercise
import forma.core.model.Pattern
import forma.core.plan.Picks
import kotlinx.coroutines.launch

/**
 * «Мои упражнения» (Denis: «чтобы он выбирал упражнения которые ему нравятся… чтобы исходя из всех выбранных составлялся
 * план индивидуальный»): per movement of the plan, the exercises one likes and the ones one does not want. Saved, the
 * likes stand in the places of their movement and the unwanted ones get the closest fine one ([Picks]); what a rule kept
 * is said. The base of the plan stays: a squat place gets a squat, a main place a compound exercise.
 */
@Composable
fun MyExercisesScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val settings by repo.store.settings.collectAsState(initial = forma.app.data.Settings())
    val loaded by produceState<Pair<List<TrainingRepository.PickGroup>, Picks.Rules>?>(null, settings) { value = repo.pickGroups() }
    // The draft: liked ids per movement (in the order picked) and the unwanted ones, from what is saved.
    var likes by rememberSaveable { mutableStateOf<Map<String, List<String>>?>(null) }
    var dislikes by rememberSaveable { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) {
        val s = repo.store.settingsNow()
        if (likes == null) likes = s.likes
        if (dislikes == null) dislikes = s.dislikes.toList()
    }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<Picks.Result?>(null) }
    val draftLikes = likes.orEmpty()
    val draftDislikes = dislikes.orEmpty().toSet()
    fun likesOf(): Map<Pattern, List<String>> = draftLikes.mapNotNull { (k, v) -> runCatching { Pattern.valueOf(k) }.getOrNull()?.let { it to v } }.toMap()
    val preview by produceState<Picks.Result?>(null, draftLikes, draftDislikes, settings) {
        value = runCatching { repo.previewPicks(likesOf(), draftDislikes) }.getOrNull()
    }

    ScrollScreen {
        TopBar("Мои упражнения", "Что нравится и что не хочешь делать", onBack = { nav.back() })
        Text(
            "Отметь в каждом движении, какие упражнения тебе нравятся и какие не хочешь делать. Понравившиеся встанут на места " +
                "своего движения, а «не хочу» заменю похожими. Основа плана останется: на месте приседа будет присед, " +
                "главными — многосуставные упражнения.",
            style = T.secondary(14.sp),
        )
        GhostButton("Спросить тренера, что выбрать", { nav.push(Screen.Coach("Помоги выбрать упражнения под меня в «Мои упражнения»: что мне подойдёт и почему?")) }, Modifier.fillMaxWidth())
        val data = loaded
        if (data == null || likes == null) {
            Text("Собираю план…", style = T.secondary(14.sp))
            return@ScrollScreen
        }
        val (groups, rules) = data
        if (groups.isEmpty()) {
            Text("В плане нет зала — выбирать не из чего.", style = T.secondary(14.sp))
            return@ScrollScreen
        }
        groups.forEach { g ->
            val key = g.pattern.name
            val liked = draftLikes[key].orEmpty()
            val options = remember(g.pattern, rules) { optionsFor(g, rules) }
            val unwanted = options.map { it.id }.filter { it in draftDislikes }
            FCard(gap = 8.dp) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = if (open == key) "Свернуть" else "Выбрать") { open = if (open == key) null else key },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        SectionLabel(g.pattern.ru)
                        Text("Сейчас: " + g.now.map { Catalog.find(it.second)?.name ?: it.second }.distinct().joinToString(", "), style = T.body(15.sp, FontWeight.SemiBold))
                        Text(
                            listOfNotNull(
                                liked.takeIf { it.isNotEmpty() }?.let { l -> "Нравится: " + l.joinToString(", ") { Catalog.find(it)?.name ?: it } },
                                unwanted.takeIf { it.isNotEmpty() }?.let { l -> "Не хочу: " + l.joinToString(", ") { Catalog.find(it)?.name ?: it } },
                            ).joinToString(" · ").ifEmpty { "Выбор за приложением" },
                            style = T.secondary(13.sp),
                        )
                    }
                    Icon(Ic.chevron, null, tint = F.c.text3, modifier = Modifier.size(18.dp))
                }
                if (open == key) {
                    options.forEach { ex ->
                        Hairline()
                        val why = Picks.unavailable(ex, rules)
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(ex.name, style = T.body(15.sp, FontWeight.SemiBold).let { if (why != null) it.copy(color = F.c.text3) else it })
                            Cues.of(ex.id)?.let { Text(it, style = T.secondary(12.sp), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            if (why != null) Text("Пока нельзя: $why", style = T.secondary(12.sp))
                            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val isLiked = ex.id in liked
                                val isUnwanted = ex.id in draftDislikes
                                Chip(if (isLiked) "Нравится ✓" else "Нравится", isLiked, {
                                    likes = draftLikes + (key to if (isLiked) liked - ex.id else liked + ex.id)
                                    if (!isLiked) dislikes = (draftDislikes - ex.id).toList()
                                }, Modifier.semantics { contentDescription = "Нравится: ${ex.name}" }, kind = ChipKind.TOGGLE)
                                Chip(if (isUnwanted) "Не хочу ✓" else "Не хочу", isUnwanted, {
                                    dislikes = (if (isUnwanted) draftDislikes - ex.id else draftDislikes + ex.id).toList()
                                    if (!isUnwanted) likes = draftLikes + (key to liked - ex.id)
                                }, Modifier.semantics { contentDescription = "Не хочу: ${ex.name}" }, kind = ChipKind.TOGGLE)
                            }
                        }
                    }
                }
            }
        }

        // What saving does, before it is saved: each place that changes, and what a rule kept.
        val p = preview
        val changes = p?.swaps.orEmpty().filter { (planId, to) -> (settings.swaps[planId] ?: planId) != to }
        if (p != null && (changes.isNotEmpty() || p.notes.isNotEmpty())) {
            FCard(gap = 6.dp) {
                SectionLabel("Что поменяется")
                changes.forEach { (planId, to) ->
                    Text("«${Catalog[settings.swaps[planId] ?: planId].name}» → «${Catalog[to].name}»", style = T.body(15.sp))
                }
                p.notes.forEach { Text("• $it", style = T.secondary(13.sp)) }
            }
        }
        saved?.let { r ->
            Text(
                if (r.swaps.isEmpty()) "Сохранено. План уже такой." else "Сохранено. План поменялся — вернуть можно здесь же, сняв отметки.",
                style = T.body(14.sp).copy(color = F.c.good),
            )
        }
        PrimaryButton("Сохранить", {
            scope.launch { saved = repo.setPicks(likesOf(), draftDislikes) }
        }, Modifier.fillMaxWidth())
        if (settings.likes.isNotEmpty() || settings.dislikes.isNotEmpty()) {
            GhostButton("Сбросить всё — план как был", {
                likes = emptyMap()
                dislikes = emptyList()
                scope.launch { saved = repo.setPicks(emptyMap(), emptySet()) }
            }, Modifier.fillMaxWidth())
        }
    }
}

/** The exercises of [g]'s movement to choose from: the ones in the plan now first, then the rest, the unavailable last. */
private fun optionsFor(g: TrainingRepository.PickGroup, rules: Picks.Rules): List<Exercise> {
    val now = g.now.map { it.second }.toSet()
    return Catalog.all.filter { it.pattern == g.pattern && !it.archived }
        .sortedWith(compareBy<Exercise>({ it.id !in now }, { Picks.unavailable(it, rules) != null }))
}
