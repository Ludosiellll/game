package forma.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
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
    var value by remember(list.isEmpty()) { mutableStateOf(list.lastOrNull()?.kg?.let { Ru.kg(it) } ?: "") }
    ScrollScreen {
        TopBar("Вес тела", "Лучше утром, натощак, раз в неделю", onBack = { nav.back() })
        NumberField(value, { value = it }, "Сегодня", "кг")
        PrimaryButton("Записать", {
            parseNum(value)?.takeIf { it in 30.0..250.0 }?.let { kg -> scope.launch { repo.logBodyWeight(kg) } }
        }, Modifier.fillMaxWidth())
        Estimates(list.lastOrNull()?.kg)
        if (list.size >= 2) {
            FCard {
                LineChart(listOf(Series(list.map { it.kg }, F.c.text, fill = true)), description = "Вес тела", xLabels = listOf(shortDate(LocalDate.ofEpochDay(list.first().epochDay)), shortDate(LocalDate.ofEpochDay(list.last().epochDay))))
            }
        }
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
    val nav = LocalNav.current
    val profile by repo.store.profile.collectAsState(initial = null)
    val p = profile ?: return
    val kg = latestKg ?: p.bodyWeightKg
    val training by produceState(0.0, kg) { value = repo.trainingKcalPerWeek(kg) }
    SectionLabel("Расчёты")
    FCard(gap = 10.dp) {
        val h = p.heightCm
        if (h == null) {
            Text("Укажи рост — посчитаю ИМТ, обмен покоя и расход энергии за день.", style = T.secondary(14.sp))
            GhostButton("Указать рост", { nav.push(Screen.Profile) }, Modifier.fillMaxWidth())
        } else {
            val bmi = Body.bmi(kg, h)
            val bmr = Body.bmr(kg, h, p.age(LocalDate.now().year), p.sex)
            val daily = Body.dailyEnergy(bmr, training)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("ИМТ", Ru.one(bmi), Body.bmiClass(bmi), F.c.text, Modifier.weight(1f))
                StatTile("Покой", "≈${(bmr / 10).roundToInt() * 10}", "ккал в день", F.c.text, Modifier.weight(1f))
                StatTile("Расход", "≈${(daily / 50).roundToInt() * 50}", "ккал в день", F.c.load, Modifier.weight(1f))
            }
            Text(
                "Оценки, не измерения. ИМТ не отличает мышцы от жира. Расход = обмен покоя × 1,3 плюс тренировки: " +
                    "≈${(training / 50).roundToInt() * 50} ккал в неделю за последний месяц. Чтобы худеть, обычно ешь на 300–500 ккал меньше расхода.",
                style = T.secondary(12.sp),
            )
        }
        val protein = Body.protein(kg, p.aim.lift)
        Text("Белок: ${protein.start.roundToInt()}–${protein.endInclusive.roundToInt()} г в день", style = T.body(15.sp, FontWeight.Bold))
        Text(
            if (protein.start >= kg * 1.6) "Для роста мышц и силы — 1,6–2,2 г на кг веса."
            else "Для восстановления и здоровья — 1,2–1,6 г на кг веса.",
            style = T.secondary(12.sp),
        )
    }
}
