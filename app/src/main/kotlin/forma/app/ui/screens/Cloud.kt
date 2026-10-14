package forma.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import forma.app.data.BackupManager
import forma.app.ui.components.SectionLabel
import forma.core.text.Ru
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

/** Cloud status: sign in, sync now, sign out, delete the account; the backup file next to them. */
@Composable
fun CloudScreen() {
    val cloud = LocalCloud.current
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val state = cloud?.state?.collectAsState()?.value ?: CloudState(configured = false, ready = true)
    var message by remember { mutableStateOf<String?>(null) }
    val waiting by remember { repo.db.sync().observeNotUploaded() }.collectAsState(initial = 0)
    // Workouts that would be lost by "Заменить данными этого аккаунта": the count waits for a decision.
    var losing by remember { mutableStateOf<Int?>(null) }
    fun replace() {
        scope.launch {
            message = null
            val r = cloud?.replaceWithThisAccount() ?: return@launch
            if (r.isSuccess) {
                // The phone now holds this account's data: start from it (or from the profile if it has none).
                nav.replaceAll(if (repo.isOnboarded()) Screen.Today else Screen.Onboarding)
            } else {
                message = r.exceptionOrNull()?.let { Cloud.describe(it) }
            }
        }
    }
    CloudContent(
        state, message,
        onReplace = {
            scope.launch {
                // Never silently: workouts the previous account's cloud has not got would be gone for good.
                val n = repo.db.sync().notUploaded()
                if (n > 0) losing = n else replace()
            }
        },
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
        waiting = waiting,
        onDeleteAccount = {
            scope.launch {
                message = null
                message = runCatching { cloud?.deleteAccount() }.fold(
                    { "Аккаунт и все данные в облаке удалены. На телефоне данные остались." },
                    { Cloud.describeDelete(it) },
                )
            }
        },
        data = { DataSection() },
    )
    losing?.let { n ->
        AlertDialog(
            onDismissRequest = { losing = null },
            containerColor = F.c.surface,
            title = { Text("${workoutsWord(n).replaceFirstChar { it.uppercase() }} не в облаке", style = T.title(20.sp)) },
            text = {
                Text(
                    "На телефоне ${workoutsWord(n)}, которых нет в облаке " + (state.previousEmail ?: "прежнего аккаунта") + ": " +
                        "при замене они пропадут навсегда. Чтобы сохранить их, сначала войди в прежний аккаунт — они загрузятся сами, " +
                        "или сохрани копию в файл ниже.",
                    style = T.secondary(14.sp),
                )
            },
            confirmButton = { TextButton({ losing = null; replace() }) { Text("Заменить, удалить их", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.bad)) } },
            dismissButton = { TextButton({ losing = null }) { Text("Отмена", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) } },
        )
    }
}

/** "1 тренировка", "3 тренировки", "5 тренировок". */
fun workoutsWord(n: Int): String = "$n " + Ru.plural(n, "тренировка", "тренировки", "тренировок")

/** "1 тренировка ждёт сети — друзья её пока не видят"; null when everything is up. */
fun waitingText(n: Int): String? = when {
    n <= 0 -> null
    n == 1 -> "1 тренировка ждёт сети — друзья её пока не видят"
    else -> "${workoutsWord(n)} ждут сети — друзья их пока не видят"
}

@Composable
fun CloudContent(
    state: CloudState,
    message: String?,
    onReplace: () -> Unit,
    onSignIn: () -> Unit,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    /** Finished workouts not uploaded yet. */
    waiting: Int = 0,
    onDeleteAccount: () -> Unit = {},
    /** The data section (the backup file) at the bottom, whatever the state of the cloud. */
    data: @Composable () -> Unit = {},
) {
    var deleteStep by remember { mutableStateOf(0) }
    ScrollScreen {
        TopBar("Облако и данные", onBack = onBack)
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
                Text("Нужны почта и пароль не короче 8 символов.", style = T.secondary(13.sp))
            }
            else -> {
                FCard(gap = 0.dp, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    ListRow("Аккаунт", state.email, icon = Ic.user, iconTint = F.c.accent)
                    Hairline()
                    ListRow("Последняя синхронизация", lastSyncText(state.lastSyncAt), icon = Ic.cloud, iconTint = F.c.accent)
                }
                if (state.otherAccount) {
                    FCard {
                        Text("На телефоне данные другого аккаунта", style = T.title(20.sp))
                        Text(
                            "Тренировки на этом телефоне синхронизировались с " + (state.previousEmail ?: "другим аккаунтом") + ". " +
                                "Чтобы чужая история не попала в этот аккаунт, синхронизация остановлена. " +
                                "Можно убрать их с телефона и загрузить данные этого аккаунта — в облаке прежнего аккаунта останется то, что успело туда загрузиться.",
                            style = T.secondary(14.sp),
                        )
                    }
                    PrimaryButton(if (state.busy) "Загружаю…" else "Заменить данными этого аккаунта", onReplace, Modifier.fillMaxWidth(), enabled = !state.busy)
                    GhostButton("Выйти, оставить как есть", onSignOut, Modifier.fillMaxWidth(), height = 50.dp)
                } else {
                    state.lastError?.let { Banner(it, F.c.warn) }
                    waitingText(waiting)?.let { Banner(it, F.c.warn, icon = Ic.cloud) }
                    PrimaryButton(
                        if (state.busy) "Синхронизирую…" else "Синхронизировать", onSync, Modifier.fillMaxWidth(),
                        icon = Ic.cloud, enabled = !state.busy && !state.deleting,
                    )
                    Text("Синхронизация идёт сама: после каждой тренировки и дважды в день в фоне.", style = T.secondary(13.sp))
                    GhostButton("Выйти", onSignOut, Modifier.fillMaxWidth(), height = 50.dp)
                    Text("Выход не удаляет данные ни с телефона, ни из облака.", style = T.secondary(12.sp))
                }
            }
        }
        message?.let { Banner(it, F.c.accent) }
        data()
        if (state.configured && state.ready && state.email != null && !state.otherAccount) {
            // Pressed once while it runs: a second deletion or a sync would only get in its way.
            GhostButton(
                if (state.deleting) "Удаляю аккаунт…" else "Удалить аккаунт и данные в облаке",
                { if (!state.deleting && !state.busy) deleteStep = 1 }, Modifier.fillMaxWidth(),
                color = if (state.deleting || state.busy) F.c.text3 else F.c.bad, height = 50.dp,
            )
            Text("Удалятся аккаунт, все тренировки и прогресс в облаке и друзья. На телефоне данные останутся.", style = T.secondary(12.sp))
        }
    }
    // Twice asked: the first time what goes, the second time that it cannot be undone.
    if (deleteStep == 1) {
        AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            containerColor = F.c.surface,
            title = { Text("Удалить аккаунт?", style = T.title(20.sp)) },
            text = {
                Text(
                    "Аккаунт ${state.email ?: ""} и всё, что в облаке, — тренировки, прогресс, план, друзья — удалятся. " +
                        "На этом телефоне данные останутся.",
                    style = T.secondary(14.sp),
                )
            },
            confirmButton = { TextButton({ deleteStep = 2 }) { Text("Дальше", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.bad)) } },
            dismissButton = { TextButton({ deleteStep = 0 }) { Text("Отмена", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) } },
        )
    }
    if (deleteStep == 2) {
        AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            containerColor = F.c.surface,
            title = { Text("Точно удалить навсегда?", style = T.title(20.sp)) },
            text = { Text("Это нельзя отменить: восстановить аккаунт и данные в облаке будет нельзя.", style = T.secondary(14.sp)) },
            confirmButton = { TextButton({ deleteStep = 0; onDeleteAccount() }) { Text("Удалить навсегда", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.bad)) } },
            dismissButton = { TextButton({ deleteStep = 0 }) { Text("Отмена", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) } },
        )
    }
}

/**
 * The backup file: save everything to one file, or restore from one — asked first (it replaces everything on the
 * phone), never during a workout or a run, and once more for a file of another cloud account.
 */
@Composable
fun DataSection() {
    val repo = LocalRepo.current
    val cloud = LocalCloud.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val backup = remember { BackupManager(repo) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<forma.app.data.Backup?>(null) }
    var otherAccount by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            message = runCatching { backup.export(context, uri, cloud?.accountId()); "Копия сохранена." }.getOrElse { "Не получилось сохранить: ${it.message}" }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { backup.read(context, uri) }.onSuccess { pending = it }.onFailure { message = "Файл не подошёл: ${it.message}" }
        }
    }
    fun restore(b: forma.app.data.Backup) {
        scope.launch {
            message = runCatching { "Восстановлено тренировок: ${backup.restore(b, cloud?.accountId())}." }.getOrElse { "Не восстановлено: ${it.message}" }
        }
    }
    SectionLabel("Резервная копия")
    FCard {
        Text("Все тренировки, прогресс и настройки — в одном файле. Сохрани в облако или отправь себе.", style = T.secondary(14.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Сохранить", { exporter.launch("forma-${LocalDate.now()}.json") }, Modifier.weight(1f))
            GhostButton("Восстановить", {
                scope.launch {
                    if (backup.sessionGoing()) message = "Сначала закончи начатую тренировку или пробежку: восстановление заменит всё на телефоне."
                    else importer.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            }, Modifier.weight(1f))
        }
        Text("Восстановление заменяет данные на телефоне. Если включено облако, копия из облака потом добавится к ним.", style = T.secondary(12.sp))
    }
    message?.let { Banner(it, F.c.accent) }
    val b = pending
    if (b != null && !otherAccount) {
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = F.c.surface,
            title = { Text("Восстановить из файла?", style = T.title(20.sp)) },
            text = {
                Text(
                    "Это заменит всё на телефоне — тренировки, прогресс, план и настройки — данными файла: " +
                        "${workoutsWord(b.workouts.count { it.status == forma.app.data.Status.DONE })}, копия от ${shortDate(java.time.Instant.ofEpochMilli(b.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate())}.",
                    style = T.secondary(14.sp),
                )
            },
            confirmButton = {
                TextButton({
                    scope.launch {
                        val mine = backup.account(cloud?.accountId())
                        if (b.account != null && mine != null && b.account != mine) otherAccount = true
                        else { pending = null; restore(b) }
                    }
                }) { Text("Заменить всё", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.bad)) }
            },
            dismissButton = { TextButton({ pending = null }) { Text("Отмена", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) } },
        )
    }
    if (b != null && otherAccount) {
        AlertDialog(
            onDismissRequest = { pending = null; otherAccount = false },
            containerColor = F.c.surface,
            title = { Text("Файл из другого аккаунта", style = T.title(20.sp)) },
            text = {
                // Signed out (or into an account the phone's data is not of), the data still belongs to the account the
                // phone synced with: the file's workouts go there.
                val email = cloud?.state?.value?.takeIf { !it.otherAccount }?.email
                Text(
                    (if (email != null) "Эта копия — из другого облачного аккаунта, не из $email. После восстановления тренировки из файла загрузятся в облако $email. "
                    else "Эта копия — из другого облачного аккаунта, не из того, с которым телефон синхронизировался раньше. " +
                        "После восстановления тренировки из файла загрузятся в облако того, прежнего аккаунта, когда ты снова в него войдёшь. ") +
                        "Восстанавливай, только если это твои тренировки.",
                    style = T.secondary(14.sp),
                )
            },
            confirmButton = {
                TextButton({ pending = null; otherAccount = false; restore(b) }) {
                    Text("Всё равно восстановить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.bad))
                }
            },
            dismissButton = { TextButton({ pending = null; otherAccount = false }) { Text("Отмена", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) } },
        )
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
                        // The phone holds another account's data: the cloud screen explains and offers the choice.
                        sync.exceptionOrNull() is forma.app.sync.AccountMismatchException -> { nav.replaceTop(Screen.Cloud); return@launch }
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
