package forma.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.components.GhostButton
import forma.app.ui.components.StatTile
import forma.app.ui.components.FCard
import forma.app.ui.components.Hairline
import forma.app.ui.components.LineChart
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.Series
import forma.app.ui.components.TopBar
import forma.app.ui.components.NumText
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.core.body.Body
import forma.core.text.Ru
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun BodyScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val list by remember { repo.db.state().observeBodyWeight() }.collectAsState(initial = emptyList())
    // Empty until today's weight is typed: the last one is only the grey example, so one tap never saves an old weight
    // for today.
    var value by remember { mutableStateOf("") }
    ScrollScreen {
        TopBar("Вес тела", "Взвешивайся утром натощак, раз в неделю", onBack = { nav.back() })
        var saved by remember { mutableStateOf(false) }
        NumberField(value, { value = it; saved = false }, "Сегодня", "кг", placeholder = list.lastOrNull()?.kg?.let { Ru.kg(it) } ?: "70")
        val kg = parseNum(value)?.takeIf { it in 30.0..250.0 }
        // Not believable: said in red, and the button waits. Not while the first digits are typed («7» of 74).
        if (kg == null && (value.length >= 3 || (parseNum(value) ?: 0.0) > 250)) BadHint("Вес — от 30 до 250\u00A0кг, например 72,5.")
        if (saved) Text("Записано.", style = T.secondary(13.sp).copy(color = F.c.good))
        PrimaryButton("Записать", {
            if (kg != null) scope.launch { repo.logBodyWeight(kg); saved = true }
        }, Modifier.fillMaxWidth(), enabled = kg != null)
        if (list.size >= 2) {
            FCard {
                // The chart spreads any change over its height: the first and the last weight say how big it is.
                NumText("${Ru.kg(list.first().kg)} → ${Ru.kg(list.last().kg)}\u00A0кг", style = T.number(15.sp))
                LineChart(listOf(Series(list.map { it.kg }, F.c.text, fill = true)), description = "Вес тела", xLabels = listOf(shortDate(LocalDate.ofEpochDay(list.first().epochDay)), shortDate(LocalDate.ofEpochDay(list.last().epochDay))))
            }
        }
        Estimates(list.lastOrNull()?.kg)
        if (list.isNotEmpty()) {
            SectionLabel("Записи")
            FCard(gap = 0.dp) {
                list.reversed().take(20).forEachIndexed { i, b ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(shortDate(LocalDate.ofEpochDay(b.epochDay)), style = T.body(15.sp), modifier = Modifier.weight(1f))
                        NumText("${Ru.kg(b.kg)} кг", style = T.number(15.sp))
                    }
                }
            }
        }
    }
}

/** BMI, resting and daily energy, protein: from the latest body weight, height, age and the last month of training. */
@Composable
private fun Estimates(latestKg: Double?) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val profile by repo.store.profile.collectAsState(initial = null)
    val p = profile ?: return
    val kg = latestKg ?: p.bodyWeightKg
    val training by produceState(0.0, kg) { value = repo.trainingKcalPerWeek(kg) }
    var askHeight by remember { mutableStateOf(false) }
    SectionLabel("Расчёты")
    FCard(gap = 10.dp) {
        val h = p.heightCm
        if (h == null) {
            Text("Нужен рост, чтобы посчитать ИМТ и калории.", style = T.secondary(14.sp))
            GhostButton("Указать рост", { askHeight = true }, Modifier.fillMaxWidth())
        } else {
            val bmi = Body.bmi(kg, h)
            val bmr = Body.bmr(kg, h, p.age(LocalDate.now().year), p.sex)
            val daily = Body.dailyEnergy(bmr, training)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("ИМТ", Ru.one(bmi), Body.bmiClass(bmi), F.c.text, Modifier.weight(1f))
                StatTile("В покое", "≈${(bmr / 10).roundToInt() * 10}", "ккал в день", F.c.text, Modifier.weight(1f))
                StatTile("Расход", "≈${(daily / 50).roundToInt() * 50}", "ккал в день", F.c.text, Modifier.weight(1f))
            }
            // No «≈0 ккал» for a month without training; the BMI caveat only where it can mislead.
            val perWeek = (training / 50).roundToInt() * 50
            val notes = listOfNotNull(
                "На тренировки за последний месяц уходило ≈$perWeek\u00A0ккал в неделю.".takeIf { perWeek > 0 },
                "ИМТ не отличает мышцы от жира.".takeIf { bmi >= 25 },
            )
            if (notes.isNotEmpty()) Text(notes.joinToString(" "), style = T.secondary(12.sp))
        }
        val protein = Body.protein(kg, p.aim.lift)
        Text("Белок: ${protein.start.roundToInt()}–${protein.endInclusive.roundToInt()} г в день", style = T.body(15.sp, FontWeight.Bold))
    }
    if (askHeight) HeightDialog(onDismiss = { askHeight = false }) { cm ->
        askHeight = false
        // The height alone: the plan stays as it is (the profile screen would build it again).
        scope.launch {
            repo.store.profileNow()?.let { now ->
                repo.store.setProfile(now.copy(heightCm = cm))
                repo.changed()
            }
        }
    }
}

/** The height typed right here, 120 to 230 cm as in the questionnaire. */
@Composable
private fun HeightDialog(onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var typed by remember { mutableStateOf("") }
    val cm = typed.trim().toIntOrNull()?.takeIf { it in 120..230 }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = F.c.surface,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(typed, { typed = it.filter(Char::isDigit) }, "Рост", "см", keyboard = KeyboardType.Number, placeholder = "178")
                if (cm == null && typed.length >= 3) BadHint("Рост — от 120 до 230\u00A0см.")
            }
        },
        confirmButton = {
            TextButton({ cm?.let(onSave) }, enabled = cm != null) {
                Text("Сохранить", style = T.body(15.sp, FontWeight.Bold).copy(color = if (cm != null) F.c.accent else F.c.text3))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена", style = T.body(15.sp)) } },
    )
}
