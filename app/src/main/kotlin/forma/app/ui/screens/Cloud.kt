package forma.app.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.sync.Cloud
import forma.app.sync.CloudState
import forma.app.sync.SyncResult
import forma.app.ui.LocalCloud
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.components.Banner
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.ListRow
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.TopBar
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Cloud status: sign in, sync now, sign out. */
@Composable
fun CloudScreen() {
    val cloud = LocalCloud.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val state = cloud?.state?.collectAsState()?.value ?: CloudState(configured = false, ready = true)
    var message by remember { mutableStateOf<String?>(null) }
    CloudContent(
        state, message,
        onSignIn = { nav.push(Screen.CloudSignIn(restore = false)) },
        onSync = {
            scope.launch {
                message = null
                message = cloud?.syncNow()?.fold({ syncSummary(it) }, { null })
            }
        },
        onSignOut = {
            scope.launch {
                cloud?.signOut()
                message = "Ты вышел из облака. Данные на телефоне остались."
            }
        },
        onBack = { nav.back() },
    )
}

@Composable
fun CloudContent(
    state: CloudState,
    message: String?,
    onSignIn: () -> Unit,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    ScrollScreen {
        TopBar("Облако", onBack = onBack)
        when {
            !state.configured -> FCard {
                Text("В этой сборке облако не настроено.", style = T.body(15.sp))
            }
            !state.ready -> Text("Проверяю вход…", style = T.secondary(14.sp))
            state.email == null -> {
                FCard {
                    Text("Копия тренировок в Supabase", style = T.title(22.sp))
                    Text(
                        "Телефон остаётся главным: без интернета всё работает как раньше, а копия догоняет, когда появляется сеть. " +
                            "Сменишь телефон — войдёшь, и вся история вернётся.",
                        style = T.secondary(14.sp),
                    )
                }
                PrimaryButton("Подключить облако", onSignIn, Modifier.fillMaxWidth(), icon = Ic.cloud)
                Text("Нужны почта и пароль. Подтверждать почту не придётся.", style = T.secondary(13.sp))
            }
            else -> {
                FCard(gap = 0.dp, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    ListRow("Аккаунт", state.email, icon = Ic.user, iconTint = F.c.accent)
                    Hairline()
                    ListRow("Последняя синхронизация", lastSyncText(state.lastSyncAt), icon = Ic.cloud, iconTint = F.c.accent)
                }
                state.lastError?.let { Banner(it, F.c.warn) }
                PrimaryButton(
                    if (state.busy) "Синхронизирую…" else "Синхронизировать", onSync, Modifier.fillMaxWidth(),
                    icon = Ic.cloud, enabled = !state.busy,
                )
                Text("Синхронизация идёт сама: после каждой тренировки и дважды в день в фоне.", style = T.secondary(13.sp))
                GhostButton("Выйти", onSignOut, Modifier.fillMaxWidth(), height = 50.dp)
                Text("Выход не удаляет данные ни с телефона, ни из облака.", style = T.secondary(12.sp))
            }
        }
        message?.let { Banner(it, F.c.accent) }
    }
}

enum class SignInMode { SIGN_IN, SIGN_UP }

/** Email and password. With [restore] (first launch) a found copy replaces the empty start. */
@Composable
fun CloudSignInScreen(restore: Boolean) {
    val cloud = LocalCloud.current
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(if (restore) SignInMode.SIGN_IN else SignInMode.SIGN_UP) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val c = cloud ?: return
        scope.launch {
            busy = true
            error = null
            val signedIn = runCatching {
                if (mode == SignInMode.SIGN_UP) c.signUp(email, password) else { c.signIn(email, password); true }
            }.onFailure { error = Cloud.describeSignIn(it) }.getOrNull()
            when (signedIn) {
                null -> Unit
                false -> result = "Аккаунт создан. Supabase отправил письмо со ссылкой: подтверди почту, вернись сюда и войди."
                true -> {
                    progress = if (restore) "Скачиваю данные из облака…" else "Синхронизирую…"
                    val sync = c.syncNow()
                    progress = null
                    when {
                        restore && repo.isOnboarded() -> { nav.replaceAll(Screen.Today); return@launch }
                        !restore && sync.isSuccess -> { nav.back(); return@launch }
                        else -> result = sync.fold(
                            { if (restore) "Вход выполнен, но в облаке пока нет данных. Заполни профиль — копия начнёт сохраняться сама." else syncSummary(it) },
                            { "Вход выполнен, но синхронизация не удалась: ${Cloud.describe(it)}" },
                        )
                    }
                }
            }
            busy = false
        }
    }

    CloudSignInContent(
        restore = restore, configured = cloud?.configured == true, mode = mode, email = email, password = password,
        busy = busy, error = error, progress = progress, result = result,
        onEmail = { email = it.trim(); error = null }, onPassword = { password = it; error = null },
        onMode = { mode = it; error = null }, onSubmit = ::submit,
        onBack = { nav.back() },
    )
}

@Composable
fun CloudSignInContent(
    restore: Boolean,
    configured: Boolean,
    mode: SignInMode,
    email: String,
    password: String,
    busy: Boolean,
    error: String?,
    progress: String?,
    result: String?,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onMode: (SignInMode) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val signUp = mode == SignInMode.SIGN_UP
    ScrollScreen(bottomInset = true) {
        TopBar(if (restore) "Восстановить из облака" else if (signUp) "Аккаунт в облаке" else "Вход в облако", onBack = onBack)
        if (!configured) {
            FCard { Text("В этой сборке облако не настроено.", style = T.body(15.sp)) }
            return@ScrollScreen
        }
        if (result != null) {
            FCard { Text(result, style = T.body(15.sp)) }
            PrimaryButton(if (restore) "К настройке профиля" else "Готово", onBack, Modifier.fillMaxWidth())
            return@ScrollScreen
        }
        Text(
            when {
                restore -> "Войди с той же почтой и паролем, что на старом телефоне: тренировки, план и прогресс вернутся."
                signUp -> "Одна учётная запись на все твои телефоны. Пароль — минимум 8 символов."
                else -> "Войди, и тренировки начнут копироваться в облако."
            },
            style = T.secondary(14.sp),
        )
        TextInput(email, onEmail, "Почта", keyboard = KeyboardType.Email, placeholder = "name@example.com", enabled = !busy)
        TextInput(password, onPassword, "Пароль", placeholder = if (signUp) "минимум 8 символов" else "", enabled = !busy, secret = true, maxLength = 72)
        PrimaryButton(
            progress ?: when {
                busy -> if (signUp) "Создаю…" else "Вхожу…"
                signUp -> "Создать аккаунт"
                else -> "Войти"
            },
            onSubmit, Modifier.fillMaxWidth(),
            enabled = !busy && EMAIL.matches(email) && password.length >= (if (signUp) 8 else 1),
        )
        GhostButton(
            if (signUp) "Уже есть аккаунт? Войти" else "Нет аккаунта? Создать",
            { if (!busy) onMode(if (signUp) SignInMode.SIGN_IN else SignInMode.SIGN_UP) }, Modifier.fillMaxWidth(),
        )
        error?.let { Banner(it, F.c.bad) }
        Text(
            "Данные хранятся в твоём проекте Supabase. Каждая строка доступна только твоему аккаунту.",
            style = T.secondary(12.sp), modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")

fun syncSummary(r: SyncResult): String = when {
    r.uploaded == 0 && r.downloaded == 0 && r.removed == 0 -> "Всё уже совпадает с облаком."
    else -> buildList {
        if (r.uploaded > 0) add("отправлено записей: ${r.uploaded}")
        if (r.downloaded > 0) add("получено: ${r.downloaded}")
        if (r.removed > 0) add("удалено: ${r.removed}")
    }.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
}

fun lastSyncText(ms: Long?, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
    if (ms == null) return "ещё не было"
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val time = "%d:%02d".format(t.hour, t.minute)
    return when (val d = t.toLocalDate()) {
        today -> "сегодня в $time"
        today.minusDays(1) -> "вчера в $time"
        else -> "${shortDate(d)} в $time"
    }
}
