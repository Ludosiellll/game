package forma.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import forma.app.ui.LocalCloud
import forma.app.ui.LocalNav
import forma.app.ui.Screen
import forma.app.ui.components.FitTitle
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
        FitTitle(titleText("Ещё"), style = T.screenTitle(), modifier = Modifier.padding(top = 8.dp))
        FCard(gap = 0.dp) {
            // The titles say where each row goes: no grey line repeating them.
            ListRow("Как пользоваться", icon = Ic.info, iconTint = F.c.accent) { nav.push(Screen.Help) }
            Hairline()
            ListRow("Калькуляторы", icon = Ic.calc, iconTint = F.c.accent) { nav.push(Screen.Calculators) }
            Hairline()
            ListRow("История", icon = Ic.list, iconTint = F.c.accent) { nav.push(Screen.History) }
            Hairline()
            ListRow("Упражнения", icon = Ic.dumbbell, iconTint = F.c.accent) { nav.push(Screen.Library()) }
            Hairline()
            ListRow("Люди и друзья", icon = Ic.user, iconTint = F.c.accent) { nav.push(Screen.Friends) }
            Hairline()
            ListRow("Вес тела", icon = Ic.scale, iconTint = F.c.accent) { nav.push(Screen.Body) }
        }
        FCard(gap = 0.dp) {
            // One name for the place, here, in «Оформление и звук» and on the screen itself; only the live state under it.
            ListRow(
                "Облако и копия",
                when {
                    cloud == null || !cloud.configured || cloud.email == null -> "Сейчас всё только на этом телефоне"
                    cloud.otherAccount -> "На телефоне данные другого аккаунта"
                    cloud.lastError != null -> listOfNotNull("Есть ошибка синхронизации", waitingText(waiting)).joinToString(" · ")
                    else -> waitingText(waiting) ?: "Синхронизация: ${lastSyncText(cloud.lastSyncAt)}"
                },
                icon = Ic.cloud, iconTint = F.c.accent,
            ) { nav.push(Screen.Cloud) }
            Hairline()
            ListRow("Цель, дни и данные", icon = Ic.user, iconTint = F.c.text2) { nav.push(Screen.Profile) }
            Hairline()
            // What the title does not say lives there too.
            ListRow("Оформление и звук", "GPS, автопауза, выгрузка для разбора", icon = Ic.gear, iconTint = F.c.text2) { nav.push(Screen.Settings) }
            Hairline()
            // «Токены ИИ» only for the two on the DeepSeek key (Denis and Egor); the server checks it again.
            val cloudApi = LocalCloud.current
            val showTokens by androidx.compose.runtime.produceState(false, cloud?.email) {
                value = cloudApi?.accountId() in forma.app.sync.TOKEN_PEOPLE
            }
            if (showTokens) {
                ListRow("Токены ИИ", icon = Ic.chat, iconTint = F.c.text2) { nav.push(Screen.Tokens) }
                Hairline()
            }
            // The version and «Проверить обновления»: a newer one opens the update window (also after «Позже»).
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val update by forma.app.update.Updater.state.collectAsState()
            // What the check by hand found, said in the row: «Проверяю…», then the latest or no connection.
            var checking by androidx.compose.runtime.remember { mutableStateOf(false) }
            var checked by androidx.compose.runtime.remember { mutableStateOf<forma.app.update.Updater.Checked?>(null) }
            val build = forma.app.BuildConfig.VERSION_CODE
            if (forma.app.BuildConfig.SELF_UPDATE) ListRow(
                "Проверить обновления",
                when {
                    // Put off with «Позже» after it came (Denis's Redmi: downloaded for the phone's installer): said here, a
                    // tap brings its window back.
                    update is forma.app.update.Updater.State.Ready -> "Обновление скачано, нажми, чтобы поставить"
                    update is forma.app.update.Updater.State.Available || update is forma.app.update.Updater.State.Failed ||
                        update is forma.app.update.Updater.State.NeedsPermission -> "Есть новая версия, нажми, чтобы обновить"
                    checking -> "Проверяю…"
                    checked == forma.app.update.Updater.Checked.LATEST -> "Это последняя версия"
                    checked == forma.app.update.Updater.Checked.FAILED -> "Не получилось проверить: нет связи. Нажми ещё раз"
                    else -> "Версия $build"
                },
                icon = Ic.up, iconTint = F.c.text2,
            ) {
                if (!checking) scope.launch {
                    checking = true
                    checked = forma.app.update.Updater.check(force = true)
                    checking = false
                }
            }
            // From Google Play: the build, updates come from Play. Nothing to tap, so no chevron.
            else ListRow("Версия", "Сборка ${forma.app.BuildConfig.VERSION_CODE} · обновляется через Google Play", icon = Ic.up, iconTint = F.c.text2)
        }
    }
}
