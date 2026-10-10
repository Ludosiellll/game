package forma.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.AppJson
import forma.app.data.LiftSummary
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.Texts
import forma.app.ui.components.FCard
import forma.app.ui.components.Hairline
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.StatTile
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.core.model.Catalog
import forma.core.text.Ru
import kotlin.math.roundToInt

@Composable
fun LiftDoneScreen(workoutId: Long) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val w by remember(workoutId) { repo.db.workouts().observe(workoutId) }.collectAsState(initial = null)
    val workout = w ?: return
    val summary = remember(workout.summaryJson) {
        workout.summaryJson?.let { runCatching { AppJson.decodeFromString(LiftSummary.serializer(), it) }.getOrNull() }
    } ?: return
    ScrollScreen(bottomInset = true) {
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Тренировка сохранена", style = T.screenTitle())
            Text(workout.title, style = T.secondary(15.sp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Время", "${summary.minutes}", "минут", F.c.text, Modifier.weight(1f))
            StatTile("Подходы", "${summary.sets}", "рабочих", F.c.text, Modifier.weight(1f))
            StatTile("Тоннаж", Ru.one(summary.volumeKg / 1000), "тонны", F.c.strength, Modifier.weight(1f))
        }
        val records = summary.reports.filter { it.record }
        if (records.isNotEmpty()) {
            FCard(outline = F.c.strength) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Ic.trophy, null, tint = F.c.strength, modifier = Modifier.size(22.dp))
                    Text("Новый рекорд силы", style = T.title(18.sp))
                }
                records.forEach { r ->
                    Text("${r.name}: расчётный максимум ${Ru.kg(((r.e1rm ?: 0.0) * 2).roundToInt() / 2.0)} кг", style = T.body(15.sp))
                }
            }
        }
        SectionLabel("Что изменится в следующий раз")
        FCard(gap = 0.dp) {
            summary.reports.forEachIndexed { i, r ->
                if (i > 0) Hairline()
                val tone = Texts.tone(r.outcome)
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            when (tone) { 1 -> Ic.up; -1 -> Ic.down; else -> Ic.right }, null,
                            tint = when (tone) { 1 -> F.c.good; -1 -> F.c.warn; else -> F.c.text2 }, modifier = Modifier.size(18.dp),
                        )
                        Text(r.name, style = T.body(16.sp, FontWeight.Bold))
                    }
                    Text(Texts.outcome(r.outcome, Catalog.find(r.exerciseId)), style = T.secondary(14.sp))
                }
            }
        }
        Text("Нагрузка тренировки: ${summary.load.roundToInt()} (оценка × минуты). Она идёт в график формы и усталости.", style = T.secondary(13.sp))
        PrimaryButton("Готово", { nav.tab(Screen.Today) }, Modifier.fillMaxWidth())
    }
}
