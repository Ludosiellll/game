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
import forma.app.ui.theme.titleText
import kotlinx.coroutines.launch

@Composable
fun MoreScreen() {
    val nav = LocalNav.current
    val cloud = LocalCloud.current?.state?.collectAsState()?.value
    val repo = forma.app.ui.LocalRepo.current
    val waiting by androidx.compose.runtime.remember { repo.db.sync().observeNotUploaded() }.collectAsState(initial = 0)
    ScrollScreen {
        Text(titleText("Ещё"), style = T.screenTitle(), modifier = Modifier.padding(top = 8.dp))
        FCard(gap = 0.dp) {
            ListRow("Спросить тренера", "ИИ ответит по твоим тренировкам: почему так, что значат цифры, что делать", icon = Ic.user, iconTint = F.c.accent) { nav.tab(Screen.Coach()) }
            Hairline()
            ListRow("Как пользоваться", "Что где в приложении и словарь слов", icon = Ic.info, iconTint = F.c.accent) { nav.push(Screen.Help) }
            Hairline()
            ListRow("Калькуляторы", "Вес на 1 раз, блины, разминка, темп, прогноз, пульс", icon = Ic.calc, iconTint = F.c.accent) { nav.push(Screen.Calculators) }
            Hairline()
            ListRow("История", "Все тренировки и пробежки", icon = Ic.list, iconTint = F.c.accent) { nav.push(Screen.History) }
            Hairline()
            ListRow("Упражнения", "Найти упражнение, замену или добавить своё", icon = Ic.dumbbell, iconTint = F.c.accent) { nav.push(Screen.Library()) }
            Hairline()
            ListRow("Люди и друзья", "Чужие тренировки, программы, сообщения", icon = Ic.user, iconTint = F.c.accent) { nav.push(Screen.Friends) }
            Hairline()
            ListRow("Вес тела", "Записать и посмотреть график", icon = Ic.scale, iconTint = F.c.accent) { nav.push(Screen.Body) }
        }
        FCard(gap = 0.dp) {
            ListRow(
                "Облако и резервная копия",
                when {
                    cloud == null || !cloud.configured -> "Резервная копия в файл"
                    cloud.email == null -> "Копия в облаке по почте и паролю или в файл"
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
            Hairline()
            // The version and «Проверить обновления»: a newer one opens the update window (also after «Позже»).
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val update by forma.app.update.Updater.state.collectAsState()
            if (forma.app.BuildConfig.SELF_UPDATE) ListRow(
                "Обновления",
                if (update is forma.app.update.Updater.State.Available) "Есть новая версия — нажми, чтобы обновить"
                else "Сборка ${forma.app.BuildConfig.VERSION_CODE} · нажми, чтобы проверить",
                icon = Ic.up, iconTint = F.c.text2,
            ) { scope.launch { forma.app.update.Updater.check(force = true) } }
            // From Google Play: the build, updates come from Play.
            else ListRow("Версия", "Сборка ${forma.app.BuildConfig.VERSION_CODE} · обновляется через Google Play", icon = Ic.up, iconTint = F.c.text2) {}
        }
        Text(
            if (cloud?.email != null) "Данные хранятся на телефоне, копия — в облаке."
            else "Данные есть только на этом телефоне. Чтобы не потерять историю при смене телефона, включи облако или сохраняй копию в файл. Всё это — в разделе «Облако и резервная копия».",
            style = T.secondary(13.sp),
        )
    }
}
