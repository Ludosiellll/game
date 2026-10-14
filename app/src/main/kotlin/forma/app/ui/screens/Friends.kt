package forma.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.TrainingRepository
import forma.app.sync.AddResult
import forma.app.sync.FeedItem
import forma.app.sync.Friend
import forma.app.sync.FriendMe
import forma.app.sync.FriendProgress
import forma.app.sync.FriendsApi
import forma.app.sync.NotSignedInException
import forma.app.sync.describeFriends
import forma.app.ui.LocalCloud
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Navigator
import forma.app.ui.Screen
import forma.app.ui.components.BarChart
import forma.app.ui.components.Banner
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.NumText
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.StatTile
import forma.app.ui.components.TopBar
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.core.model.Catalog
import forma.core.run.Vdot
import forma.core.text.Ru
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** Everything the friends screen shows. [fresh]: a new friends profile — what friends see is explained, once. */
data class FriendsUi(
    val signedIn: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val me: FriendMe? = null,
    val friends: List<Friend> = emptyList(),
    val feed: List<FeedItem> = emptyList(),
    val fresh: Boolean = false,
)

/**
 * The friends screen from the server. The profile's name goes up only when the server has none — a friends profile
 * created just now: a name changed for friends is never overwritten by opening the screen. What friends see is
 * explained to a new friends profile until [explained]: a profile may stay without a name (the app does not ask for
 * one), and a blank name alone would explain it on every visit.
 */
suspend fun loadFriends(api: FriendsApi, localName: String?, message: String? = null, explained: Boolean = false): FriendsUi {
    var me = api.me()
    val blank = me.name.isBlank()
    val name = localName?.trim().orEmpty()
    if (blank && name.isNotEmpty()) me = api.me(name)
    return FriendsUi(signedIn = true, me = me, friends = api.list(), feed = api.feed(limit = 30), message = message, fresh = blank && !explained)
}

@Composable
fun FriendsScreen() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val cloud = LocalCloud.current
    val cloudState = cloud?.state?.collectAsState()?.value
    val api = cloud?.friends
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var ui by remember { mutableStateOf(FriendsUi(signedIn = cloudState?.email != null, loading = true)) }
    var ask by remember { mutableStateOf<Pair<FeedItem, List<String>>?>(null) }

    suspend fun reload() {
        if (api == null || cloudState?.email == null) {
            ui = FriendsUi(signedIn = false)
            return
        }
        ui = ui.copy(loading = true, error = null)
        ui = runCatching {
            loadFriends(api, repo.store.profileNow()?.name, ui.message, explained = cloud?.friendsExplained == true).let { it.copy(fresh = it.fresh || ui.fresh) }
        }.getOrElse { FriendsUi(signedIn = true, error = describeFriends(it)) }
        // Explained once: the banner stays on this visit, not on the next ones.
        if (ui.fresh) cloud?.friendsExplained = true
    }
    LaunchedEffect(cloudState?.email) { reload() }

    /** A call that changes friends: on failure (offline) the error shows and the screen stays as it was. */
    fun change(call: suspend (FriendsApi) -> Unit) {
        val a = api ?: return
        scope.launch {
            runCatching { call(a) }.onSuccess { reload() }.onFailure { ui = ui.copy(error = describeFriends(it), message = null) }
        }
    }

    FriendsContent(
        ui,
        onSignIn = { nav.push(Screen.CloudSignIn(restore = false)) },
        onShare = { code ->
            val text = "Добавь меня в друзья в «Форме»: код $code"
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        },
        onRename = { name ->
            scope.launch {
                runCatching { api?.me(name) }.onSuccess { m -> ui = ui.copy(me = m ?: ui.me, message = "Имя сохранено", error = null) }
                    .onFailure { ui = ui.copy(error = describeFriends(it)) }
            }
        },
        onSharing = { activity, body ->
            scope.launch { runCatching { api?.setSharing(activity, body) }.onSuccess { m -> ui = ui.copy(me = m ?: ui.me, error = null) }.onFailure { ui = ui.copy(error = describeFriends(it)) } }
        },
        onAdd = { code ->
            scope.launch {
                val r = runCatching { api!!.add(code) }
                ui = ui.copy(message = r.fold({ addText(it) }, { describeFriends(it) }))
                reload()
            }
        },
        onAnswer = { f, accept -> change { it.answer(f.userId, accept) } },
        onRemove = { f -> change { it.remove(f.userId) } },
        onOpen = { f -> nav.push(Screen.Friend(f.userId, f.name)) },
        onCopy = { item -> scope.launch { copyAsked(api, repo, nav, item, { ask = item to it }) { m -> ui = ui.copy(message = m) } } },
        onBack = { nav.back() },
    )
    ask?.let { (item, names) ->
        LeftOutDialog(names, onCancel = { ask = null }) {
            ask = null
            scope.launch { ui = ui.copy(message = startCopy(repo, nav, item)) }
        }
    }
}

private fun addText(r: AddResult) = when (r) {
    AddResult.REQUESTED -> "Запрос отправлен. Когда друг примет его, вы увидите тренировки друг друга."
    AddResult.ACCEPTED -> "Готово — вы друзья."
    AddResult.ALREADY -> "Вы уже друзья."
    AddResult.NOT_FOUND -> "Такого кода нет. Проверь буквы и цифры."
    AddResult.SELF -> "Это твой собственный код."
}

/**
 * "Сделать так же": the friend's own exercises of that session come first (only those), then the session starts — or,
 * when some exercises cannot be done here, [ask] gets their names to ask first. [say]: a line for the screen.
 */
private suspend fun copyAsked(api: FriendsApi?, repo: TrainingRepository, nav: Navigator, item: FeedItem, ask: (List<String>) -> Unit, say: (String?) -> Unit) {
    val plan = item.liftPlan() ?: return say("Эту тренировку нельзя повторить: в ней нет плана.")
    if (repo.db.workouts().active() != null) return say("Сначала закончи начатую тренировку.")
    val used = plan.items.map { it.exerciseId }.toSet()
    val own = runCatching { api?.progress(item.userId)?.customExercises() }.getOrNull().orEmpty()
    repo.importExercises(own.filter { it.id in used })
    val left = repo.copiedLeftOut(plan)
    when {
        left.size >= plan.items.size -> say("Упражнений из этой тренировки нет на телефоне — повторить её не получится.")
        left.isNotEmpty() -> ask(left)
        else -> say(startCopy(repo, nav, item))
    }
}

/** Starts today's session as the friend did it; returns a line for the screen when it cannot. */
private suspend fun startCopy(repo: TrainingRepository, nav: Navigator, item: FeedItem): String? {
    val plan = item.liftPlan() ?: return "Эту тренировку нельзя повторить: в ней нет плана."
    val id = repo.startCopiedLift(plan, item.name)
        ?: return if (repo.db.workouts().active() != null) "Сначала закончи начатую тренировку."
        else "Упражнений из этой тренировки нет на телефоне — повторить её не получится."
    nav.push(Screen.Workout(id))
    return null
}

/** Before a copy without some of the friend's exercises: which ones, and why. */
@Composable
private fun LeftOutDialog(names: List<String>, onCancel: () -> Unit, onStart: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = F.c.surface,
        title = { Text("Повторить без части упражнений?", style = T.title(20.sp)) },
        text = {
            Text(
                "Не войдут: ${names.joinToString(", ")}. Их нет на этом телефоне (своё упражнение друга не скачалось — нужен интернет) " +
                    "или в тренировке больше 12 упражнений.",
                style = T.secondary(14.sp),
            )
        },
        confirmButton = { TextButton(onStart) { Text("Начать без них", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) } },
        dismissButton = { TextButton(onCancel) { Text("Отмена", style = T.body(15.sp)) } },
    )
}

@Composable
fun FriendsContent(
    ui: FriendsUi,
    onSignIn: () -> Unit,
    onShare: (String) -> Unit,
    onRename: (String) -> Unit,
    onSharing: (activity: Boolean, body: Boolean) -> Unit,
    onAdd: (String) -> Unit,
    onAnswer: (Friend, Boolean) -> Unit,
    onRemove: (Friend) -> Unit,
    onOpen: (Friend) -> Unit,
    onCopy: (FeedItem) -> Unit,
    onBack: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    ScrollScreen {
        TopBar("Друзья", "Прогресс и тренировки друзей", onBack = onBack)
        if (!ui.signedIn) {
            FCard(gap = 10.dp) {
                Text("Друзья работают через облако", style = T.title(20.sp))
                Text("Войди или создай аккаунт: тогда друзья смогут добавить тебя по коду, а ты — видеть их тренировки и прогресс.", style = T.secondary(14.sp))
                PrimaryButton("Войти в облако", onSignIn, Modifier.fillMaxWidth())
            }
            return@ScrollScreen
        }
        ui.error?.let { Banner(it, F.c.warn) }
        ui.message?.let { Text(it, style = T.body(14.sp, FontWeight.SemiBold).copy(color = F.c.accent)) }

        val me = ui.me
        if (me != null && ui.fresh && me.shareActivity) {
            Banner("Друзья увидят твои тренировки, веса, пробежки и программу. Не хочешь — выключи «Тренировки и прогресс» ниже.", F.c.accent)
        }
        if (me != null) {
            FCard(gap = 10.dp) {
                SectionLabel("Твой код")
                // Letters and digits alike: the whole code at one size (number text would shrink the letters).
                Text(me.code.chunked(4).joinToString(" "), style = T.number(34.sp))
                Text("Друг вводит этот код у себя — и вы видите тренировки и прогресс друг друга.", style = T.secondary(13.sp))
                PrimaryButton("Поделиться кодом", { onShare(me.code) }, Modifier.fillMaxWidth())
                var name by remember(me.name) { mutableStateOf(me.name) }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput(name, { name = it }, "Имя для друзей", Modifier.weight(1f), maxLength = 40, placeholder = "Как тебя называть")
                    if (name.trim() != me.name) GhostButton("Сохранить", { onRename(name.trim()) }, height = 54.dp)
                }
            }
            // What friends see, in sight right away: sharing is on from the start.
            FCard(gap = 6.dp) {
                SectionLabel("Что видят друзья")
                Text(
                    if (me.shareActivity) "Сейчас друзья видят все твои тренировки и пробежки, веса, расчётные максимумы и программу — и могут их повторить."
                    else "Сейчас друзья не видят твоих тренировок: только имя.",
                    style = T.secondary(13.sp),
                )
                Toggle("Тренировки и прогресс", "Что делал, веса, пробежки, программа", me.shareActivity) { onSharing(it, me.shareBody) }
                Toggle("Вес тела", "График веса за полгода", me.shareBody) { onSharing(me.shareActivity, it) }
                Text("Маршруты пробежек, возраст, пульс и заметки друзья не видят никогда.", style = T.secondary(12.sp))
            }
        }

        FCard(gap = 10.dp) {
            SectionLabel("Добавить друга")
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput(code, { code = it.uppercase() }, "Код друга", Modifier.weight(1f), maxLength = 12, placeholder = "ABCD 2345")
                GhostButton("Добавить", { onAdd(code); code = "" }, height = 54.dp)
            }
        }

        val incoming = ui.friends.filter { it.incoming }
        if (incoming.isNotEmpty()) {
            SectionLabel("Хотят дружить")
            FCard(gap = 0.dp) {
                incoming.forEachIndexed { i, f ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name, style = T.body(16.sp, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                        TextButton({ onAnswer(f, false) }) { Text("Отклонить", style = T.body(14.sp)) }
                        TextButton({ onAnswer(f, true) }) { Text("Принять", style = T.body(14.sp, FontWeight.Bold).copy(color = F.c.accent)) }
                    }
                }
            }
        }

        val friends = ui.friends.filter { it.accepted }
        val outgoing = ui.friends.filter { it.state == "outgoing" }
        if (friends.isNotEmpty() || outgoing.isNotEmpty()) {
            SectionLabel("Друзья")
            FCard(gap = 0.dp) {
                friends.forEachIndexed { i, f ->
                    if (i > 0) Hairline()
                    forma.app.ui.components.ListRow(
                        f.name, f.lastDay?.let { "Последняя тренировка: ${relativeDay(LocalDate.parse(it.take(10)))}" } ?: "Пока без тренировок",
                        icon = Ic.user, iconTint = F.c.accent,
                    ) { onOpen(f) }
                }
                outgoing.forEachIndexed { i, f ->
                    if (i > 0 || friends.isNotEmpty()) Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = T.body(16.sp, FontWeight.SemiBold))
                            Text("Ждём ответа", style = T.secondary(13.sp))
                        }
                        TextButton({ onRemove(f) }) { Text("Отменить", style = T.body(14.sp)) }
                    }
                }
            }
        }

        if (ui.feed.isNotEmpty()) {
            SectionLabel("Что делают друзья")
            ui.feed.forEach { FeedCard(it, showName = true, onCopy = { onCopy(it) }) }
        } else if (!ui.loading && friends.isNotEmpty()) {
            Text("У друзей пока нет тренировок в облаке.", style = T.secondary(14.sp))
        }
    }
}

/** "сегодня", "вчера", "3 дня назад", "12 октября". */
fun relativeDay(d: LocalDate, today: LocalDate = LocalDate.now()): String {
    val days = today.toEpochDay() - d.toEpochDay()
    return when {
        days <= 0L -> "сегодня"
        days == 1L -> "вчера"
        days < 7 -> "${Ru.days(days.toInt())} назад"
        else -> shortDate(d)
    }
}

/** A friend's workout: what it was, the best set of each exercise or the run, and "do the same". */
@Composable
fun FeedCard(item: FeedItem, showName: Boolean, onCopy: () -> Unit) {
    FCard(gap = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                if (item.isLift) Ic.dumbbell else Ic.run, null,
                tint = if (item.isLift) F.c.strength else F.c.endurance, modifier = Modifier.width(18.dp),
            )
            Text(
                "  " + listOfNotNull(item.name.takeIf { showName }, relativeDay(item.date)).joinToString(" · "),
                style = T.secondary(13.sp), modifier = Modifier.weight(1f),
            )
            item.durationSec?.let { NumText("${it / 60} мин", style = T.number(13.sp, FontWeight.Medium).copy(color = F.c.text2)) }
        }
        Text(item.title, style = T.title(20.sp))
        if (item.isLift) {
            val names = item.liftPlan()?.items?.associate { it.exerciseId to it.name }.orEmpty()
            item.topSets().take(4).forEach { s ->
                Row {
                    Text(names[s.exerciseId] ?: Catalog.find(s.exerciseId)?.name ?: s.exerciseId, style = T.body(14.sp), modifier = Modifier.weight(1f), maxLines = 1)
                    NumText(if (s.weight != 0.0) "${Ru.kg(s.weight)} × ${s.reps}" else "× ${s.reps}", style = T.number(14.sp, FontWeight.Bold))
                }
            }
            val more = item.topSets().size - 4
            if (more > 0) Text("+ ещё ${Ru.plural(more, "упражнение", "упражнения", "упражнений").let { "$more $it" }}", style = T.secondary(13.sp))
            val records = item.liftSummary()?.reports?.filter { it.record }.orEmpty()
            if (records.isNotEmpty()) Text("Рекорд: ${records.joinToString(", ") { it.name }}", style = T.body(13.sp, FontWeight.Bold).copy(color = F.c.good))
            if (item.liftPlan() != null) GhostButton("Сделать так же", onCopy, Modifier.fillMaxWidth())
        } else {
            val r = item.run
            if (r != null) {
                val parts = listOfNotNull(
                    r.distanceM?.takeIf { it > 50 }?.let { "${Ru.one(it / 1000)} км" },
                    r.movingSec?.let { Ru.clock(it) },
                    r.avgPace?.takeIf { it > 0 }?.let { "${Ru.pace(it)} /км" },
                )
                if (parts.isNotEmpty()) NumText(parts.joinToString(" · "), style = T.number(16.sp, FontWeight.Bold))
            }
        }
    }
}

@Composable
fun FriendScreen(userId: String, name: String) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val api = LocalCloud.current?.friends
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<FriendProgress?>(null) }
    var items by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf<Pair<FeedItem, List<String>>?>(null) }
    LaunchedEffect(userId) {
        if (api == null) return@LaunchedEffect
        runCatching { progress = api.progress(userId); items = api.feed(limit = 20, userId = userId) }.onFailure { error = describeFriends(it) }
    }
    FriendContent(
        name, progress, items, error, message,
        onCopyProgram = { p ->
            scope.launch {
                val program = p.program()
                when {
                    program == null -> message = "Программа друга не читается: пусть друг обновит приложение."
                    !repo.copyProgram(program, p.name, p.customExercises()) ->
                        message = "Эту программу не получилось взять: в ней нет упражнений, которые знает этот телефон."
                    else -> nav.tab(Screen.Plan)
                }
            }
        },
        onCopy = { item -> scope.launch { copyAsked(api, repo, nav, item, { ask = item to it }) { m -> message = m } } },
        // Offline the friend stays: the error shows and the screen stays open.
        onRemove = {
            scope.launch {
                runCatching { (api ?: throw NotSignedInException()).remove(userId) }
                    .onSuccess { nav.back() }.onFailure { error = describeFriends(it) }
            }
        },
        onBack = { nav.back() },
    )
    ask?.let { (item, names) ->
        LeftOutDialog(names, onCancel = { ask = null }) {
            ask = null
            scope.launch { message = startCopy(repo, nav, item) }
        }
    }
}

@Composable
fun FriendContent(
    name: String,
    progress: FriendProgress?,
    items: List<FeedItem>,
    error: String?,
    message: String?,
    onCopyProgram: (FriendProgress) -> Unit,
    onCopy: (FeedItem) -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmProgram by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    ScrollScreen {
        TopBar(progress?.name ?: name, "Друг", onBack = onBack)
        error?.let { Banner(it, F.c.warn) }
        message?.let { Text(it, style = T.body(14.sp, FontWeight.SemiBold).copy(color = F.c.accent)) }
        val p = progress
        if (p != null && !p.shared) Text("${p.name} пока не делится тренировками.", style = T.secondary(15.sp))
        if (p != null && p.shared) {
            // Sessions per week over the last eight weeks.
            val monday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            val byWeek = p.weeks.associateBy { LocalDate.parse(it.week.take(10)) }
            val weeks = (7 downTo 0).map { monday.minusWeeks(it.toLong()) }
            val counts = weeks.map { w -> byWeek[w]?.let { it.lifts + it.runs } ?: 0 }
            FCard(gap = 6.dp) {
                SectionLabel("Тренировок в неделю")
                Text("${counts.last()} на этой неделе · в среднем ${Ru.one(counts.dropLast(1).average())}", style = T.title(18.sp))
                BarChart(counts.map { it.toDouble() }, F.c.accent.copy(alpha = 0.5f), highlight = F.c.accent, description = "Тренировки по неделям")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val main = listOf("squat" to "Присед", "bench" to "Жим лёжа", "deadlift" to "Становая", "ohp" to "Жим стоя")
                main.mapNotNull { (id, label) -> p.stats.firstOrNull { it.exerciseId == id }?.let { label to it } }.take(2).forEach { (label, s) ->
                    StatTile(label, "${Ru.kg((s.e1rm * 2).roundToInt() / 2.0)}", "кг на 1 раз", F.c.strength, Modifier.weight(1f))
                }
                p.vdot?.let { v ->
                    StatTile("5 км", Ru.clock(Vdot.predictSeconds(v, 5000.0).roundToInt()), "прогноз", F.c.endurance, Modifier.weight(1f))
                }
            }
            val body = p.body
            if (!body.isNullOrEmpty()) {
                FCard(gap = 4.dp) {
                    SectionLabel("Вес тела")
                    NumText("${Ru.kg(body.last().kg)} кг", style = T.title(22.sp))
                    if (body.size >= 2) forma.app.ui.components.LineChart(
                        listOf(forma.app.ui.components.Series(body.map { it.kg }, F.c.text, fill = true)), description = "Вес тела друга",
                    )
                }
            }
            val program = p.program()
            if (program != null) {
                FCard(gap = 8.dp) {
                    SectionLabel("Программа")
                    Text(aimTitle(program.aim), style = T.title(18.sp))
                    Text(
                        (1..7).filter { program.week.on(it).isNotEmpty() }.joinToString(" · ") { d ->
                            DAY_SHORT[d - 1] + " " + program.week.on(d).joinToString("+") { sessionTitle(it, program) }
                        },
                        style = T.secondary(13.sp),
                    )
                    GhostButton("Скопировать программу", { confirmProgram = true }, Modifier.fillMaxWidth())
                }
            }
        }
        if (items.isNotEmpty()) {
            SectionLabel("Последние тренировки")
            items.forEach { FeedCard(it, showName = false, onCopy = { onCopy(it) }) }
        }
        GhostButton("Удалить из друзей", { confirmRemove = true }, Modifier.fillMaxWidth())
    }
    if (confirmProgram && progress != null) {
        AlertDialog(
            onDismissRequest = { confirmProgram = false },
            containerColor = F.c.surface,
            title = { Text("Тренироваться по программе ${progress.name}?", style = T.title(20.sp)) },
            text = {
                Text(
                    "Твоя программа заменится: те же дни, тренировки и упражнения. Веса — твои: прогресс по каждому упражнению " +
                        "сохранится, новые подберутся на первой тренировке. Твоя программа с заменами упражнений, подходами и отдыхом " +
                        "сохранится: вернуть её — кнопка «Вернуть мою программу» в разделе «Цель, дни и данные».",
                    style = T.secondary(14.sp),
                )
            },
            confirmButton = { TextButton({ confirmProgram = false; onCopyProgram(progress) }) { Text("Скопировать", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.accent)) } },
            dismissButton = { TextButton({ confirmProgram = false }) { Text("Отмена", style = T.body(15.sp)) } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = F.c.surface,
            title = { Text("Удалить из друзей?", style = T.title(20.sp)) },
            text = { Text("Вы перестанете видеть тренировки друг друга. Добавиться снова можно по коду.", style = T.secondary(14.sp)) },
            confirmButton = { TextButton({ confirmRemove = false; onRemove() }) { Text("Удалить", style = T.body(15.sp, FontWeight.Bold).copy(color = F.c.bad)) } },
            dismissButton = { TextButton({ confirmRemove = false }) { Text("Отмена", style = T.body(15.sp)) } },
        )
    }
}
