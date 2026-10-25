package forma.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    val repo = forma.app.ui.LocalRepo.current
    val waiting by androidx.compose.runtime.remember { repo.db.sync().observeNotUploaded() }.collectAsState(initial = 0)
    ScrollScreen {
        Text(if (F.type.capsTitles) "ЕЩЁ" else "Ещё", style = T.screenTitle(), modifier = Modifier.padding(top = 8.dp))
        FCard(gap = 0.dp) {
            ListRow("Спросить тренера", "ИИ ответит по твоим тренировкам: почему так, что значат цифры, что делать", icon = Ic.user, iconTint = F.c.accent) { nav.push(Screen.Coach) }
            Hairline()
            ListRow("Как пользоваться", "Знакомство с приложением, что где и словарь слов", icon = Ic.info, iconTint = F.c.accent) { nav.push(Screen.Help) }
            Hairline()
            ListRow("Калькуляторы", "1ПМ, блины, разминка, темп, прогноз, пульс", icon = Ic.calc, iconTint = F.c.accent) { nav.push(Screen.Calculators) }
            Hairline()
            ListRow("История", "Все тренировки и пробежки", icon = Ic.list, iconTint = F.c.accent) { nav.push(Screen.History) }
            Hairline()
            ListRow("Упражнения", "Поиск, похожие, свои упражнения", icon = Ic.dumbbell, iconTint = F.c.accent) { nav.push(Screen.Library()) }
            Hairline()
            ListRow("Друзья", "Прогресс друзей, их тренировки и программы", icon = Ic.user, iconTint = F.c.accent) { nav.push(Screen.Friends) }
            Hairline()
            ListRow("Вес тела", "Записать и посмотреть график", icon = Ic.scale, iconTint = F.c.accent) { nav.push(Screen.Body) }
        }
        FCard(gap = 0.dp) {
            ListRow(
                "Облако и резервная копия",
                when {
                    cloud == null || !cloud.configured -> "Резервная копия в файл"
                    cloud.email == null -> "Копия в Supabase, вход по почте и паролю; копия в файл"
                    cloud.otherAccount -> "На телефоне данные другого аккаунта"
                    cloud.lastError != null -> listOfNotNull("Есть ошибка синхронизации", waitingText(waiting)).joinToString(" · ")
                    else -> waitingText(waiting) ?: "Синхронизация: ${lastSyncText(cloud.lastSyncAt)}"
                },
                icon = Ic.cloud, iconTint = F.c.accent,
            ) { nav.push(Screen.Cloud) }
            Hairline()
            ListRow("Цель, дни и данные", "Поменяешь — план пересчитается", icon = Ic.user, iconTint = F.c.text2) { nav.push(Screen.Profile) }
            Hairline()
            ListRow("Оформление и звук", "Стиль, голос, вибрация, выгрузка для разбора", icon = Ic.gear, iconTint = F.c.text2) { nav.push(Screen.Settings) }
        }
        Text(
            if (cloud?.email != null) "Данные хранятся на телефоне, копия — в облаке."
            else "Данные хранятся только на телефоне. Включи облако или сохраняй резервную копию в файл («Облако и резервная копия»), чтобы не потерять историю при смене телефона.",
            style = T.secondary(13.sp),
        )
    }
}
