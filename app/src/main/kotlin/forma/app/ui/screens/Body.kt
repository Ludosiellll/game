package forma.app.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
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
import forma.core.text.Ru
import kotlinx.coroutines.launch
import java.time.LocalDate

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
