package forma.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.LocalCloud
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
    val cloud = LocalCloud.current?.state?.collectAsState()?.value
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
            ListRow(
                "Облако",
                when {
                    cloud == null || !cloud.configured -> "Не настроено в этой сборке"
                    cloud.email == null -> "Копия в Supabase, вход по почте и паролю"
                    cloud.lastError != null -> "Есть ошибка синхронизации"
                    else -> "Синхронизация: ${lastSyncText(cloud.lastSyncAt)}"
                },
                icon = Ic.cloud, iconTint = F.c.accent,
            ) { nav.push(Screen.Cloud) }
            Hairline()
            ListRow("Цель, дни и данные", "Поменяешь — план пересчитается", icon = Ic.user, iconTint = F.c.text2) { nav.push(Screen.Profile) }
            Hairline()
            ListRow("Оформление и звук", "Стиль, голос, вибрация, резервная копия", icon = Ic.gear, iconTint = F.c.text2) { nav.push(Screen.Settings) }
        }
        Text(
            if (cloud?.email != null) "Данные хранятся на телефоне, копия — в облаке."
            else "Данные хранятся только на телефоне. Включи облако или делай резервную копию в настройках, чтобы не потерять историю при смене телефона.",
            style = T.secondary(13.sp),
        )
    }
}
