package forma.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.LocalNav
import forma.app.ui.Screen
import forma.app.ui.components.FCard
import forma.app.ui.components.Hairline
import forma.app.ui.components.ListRow
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T

@Composable
fun MoreScreen() {
    val nav = LocalNav.current
    ScrollScreen {
        Text(if (F.type.capsTitles) "ЕЩЁ" else "Ещё", style = T.screenTitle(), modifier = Modifier.padding(top = 8.dp))
        FCard(gap = 0.dp) {
            ListRow("Калькуляторы", "1ПМ, блины, разминка, темп, прогноз, пульс", icon = Ic.calc, iconTint = F.c.accent) { nav.push(Screen.Calculators) }
            Hairline()
            ListRow("История", "Все тренировки и пробежки", icon = Ic.list, iconTint = F.c.accent) { nav.push(Screen.History) }
            Hairline()
            ListRow("Вес тела", "Записать и посмотреть график", icon = Ic.scale, iconTint = F.c.accent) { nav.push(Screen.Body) }
        }
        FCard(gap = 0.dp) {
            ListRow("Цель, дни и данные", "Поменяешь — план пересчитается", icon = Ic.user, iconTint = F.c.text2) { nav.push(Screen.Profile) }
            Hairline()
            ListRow("Оформление и звук", "Стиль, голос, вибрация, резервная копия", icon = Ic.gear, iconTint = F.c.text2) { nav.push(Screen.Settings) }
        }
        Text("Данные хранятся только на телефоне. Делай резервную копию в настройках, чтобы не потерять историю при смене телефона.", style = T.secondary(13.sp))
    }
}
