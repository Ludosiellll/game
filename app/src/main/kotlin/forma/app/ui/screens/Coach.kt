package forma.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.CoachActState
import forma.app.data.CoachActions
import forma.app.data.CoachChatAct
import forma.app.data.CoachChatMsg
import forma.app.data.CoachLogEntry
import forma.app.data.CoachTalk
import forma.app.sync.CoachApi
import forma.app.sync.CoachException
import forma.app.sync.CoachTurn
import forma.app.ui.LocalCloud
import forma.app.ui.LocalCoachApi
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.LocalSettings
import forma.app.ui.Screen
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.RoundIcon
import forma.app.ui.components.SectionLabel
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.core.text.Ru
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Questions to start with: what newcomers ask, about their day and numbers, and what the coach can change. */
private val STARTERS = listOf(
    "Почему сегодня такая тренировка?",
    "Разбери мою последнюю тренировку",
    "Как идёт мой прогресс?",
    "Мне сегодня плохо, давай полегче",
    "Почему мне дают меньше веса, чем я могу?",
    "Прошлая пробежка была лёгкой, хочу бегать дольше",
)

/** Who answers: the stand-in of tests, or the cloud when the person is signed in; and whether they can ask now. */
@Composable
private fun coachApi(): Pair<CoachApi?, Boolean> {
    val stand = LocalCoachApi.current
    if (stand != null) return stand to true
    val cloud = LocalCloud.current
    val email = cloud?.state?.collectAsState()?.value?.email
    return cloud to (cloud != null && email != null)
}

/**
 * One of the two the coach's DeepSeek key is for ([forma.app.sync.TOKEN_PEOPLE], as «Токены ИИ» in «Ещё»): their questions
 * go to DeepSeek through the key's seller first, to Google only when it fails. False until the account is known, and with
 * no cloud.
 */
@Composable
private fun asksDeepSeek(): Boolean {
    val cloud = LocalCloud.current
    val email = cloud?.state?.collectAsState()?.value?.email
    val on by produceState(false, cloud, email) { value = cloud?.accountId() in forma.app.sync.TOKEN_PEOPLE }
    return on
}

private enum class Panel { MEMORY, CHANGES, ABOUT }

/**
 * «Тренер»: a chat with an AI coach (Gemini, or DeepSeek for the two on its key, through the project's server function)
 * that knows the training, the app's rules and what it was told about the person. It explains, reviews workouts and makes
 * changes within the app's limits — the day, ratings, running and weights at once (with «Вернуть»), the gym plan, the goal
 * and the stadium on «Принять».
 * A tab of its own; opened with [question] (from a workout's review) it puts it in the field.
 */
@Composable
fun CoachScreen(question: String? = null, workoutId: Long? = null) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val nav = LocalNav.current
    val settings = LocalSettings.current
    val (api, signedIn) = coachApi()
    val chat by repo.store.coachChat.collectAsState(initial = emptyList())
    val log by repo.store.coachLog.collectAsState(initial = emptyList())
    val busy by CoachTalk.busy.collectAsState()
    val failed by CoachTalk.failed.collectAsState()
    // Opened from a workout: its question is put in the field, sent when the person sends it (nothing changes unasked).
    var typed by rememberSaveable { mutableStateOf(question.orEmpty()) }
    var panel by rememberSaveable { mutableStateOf<Panel?>(null) }
    fun send(q: String) {
        val c = api ?: return
        // The flow's own value: the screen's copy is a frame behind, and a question sent into a busy talk is dropped.
        if (q.isBlank() || CoachTalk.busy.value) return
        typed = ""
        // Opened from a workout: its details go with every question here.
        CoachTalk.send(repo, c, q.trim()) { coachContext(repo) + (workoutId?.let { id -> coachWorkoutFacts(repo, id)?.let { "\n\n$it" } } ?: "") }
    }
    // A question that got no answer goes back into the field, to send again.
    LaunchedEffect(failed) { failed?.second?.let { if (typed.isBlank()) typed = it } }
    val list = rememberLazyListState()
    // The newest at the bottom in sight: after a question, an answer, and when the screen opens.
    val count = (if (panel != null) 1 else 0) + (if (chat.isEmpty()) 1 else 0) + chat.size + (if (busy) 1 else 0) + (if (failed != null) 1 else 0)
    LaunchedEffect(chat.size, busy, failed) { if (count > 0) list.scrollToItem(count - 1) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().let { if (nav.canGoBack) it.navigationBarsPadding() else it }) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (nav.canGoBack) { RoundIcon(Ic.back, "Назад", { nav.back() }, size = 42.dp); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                Text("Тренер", style = T.title(22.sp))
                Text(if (busy) "печатает…" else "ИИ · знает твои тренировки", style = T.secondary(13.sp).copy(color = if (busy) F.c.accent else F.c.text2))
            }
            // A button of its own, in sight: it clears the talk. Not while an answer is on its way (it would land alone in
            // the new talk).
            if (chat.isNotEmpty() && !busy) {
                Spacer(Modifier.width(8.dp))
                GhostButton("Новый разговор", { scope.launch { CoachTalk.clear(repo) } })
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            fun toggle(p: Panel) {
                panel = if (panel == p) null else p
                // An opened panel is the first thing of the list: shown from its top.
                if (panel != null) scope.launch { list.scrollToItem(0) }
            }
            // No counts: «Изменения 37» read as 37 unread, and the three names fit the row.
            ToolChip("Что помнит", panel == Panel.MEMORY) { toggle(Panel.MEMORY) }
            ToolChip("Изменения", panel == Panel.CHANGES) { toggle(Panel.CHANGES) }
            ToolChip("Как работает", panel == Panel.ABOUT) { toggle(Panel.ABOUT) }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            // A short talk sits by the field, as in a messenger.
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
        ) {
            panel?.let { p -> item(key = "panel") { PanelCard(p, settings.coachNotes, log) } }
            if (chat.isEmpty()) item(key = "hello") { Welcome(enabled = signedIn && !busy, onAsk = ::send) }
            items(chat, key = { it.id }) { m -> Message(m, log) }
            if (busy) item(key = "typing") { Typing() }
            failed?.let { (why, _) -> item(key = "failed") { Text(why, style = T.body(14.sp).copy(color = F.c.warn), modifier = Modifier.padding(horizontal = 8.dp)) } }
        }
        // Not signed in: straight to the e-mail form (not the backup page), and back here once signed in.
        if (signedIn) InputBar(typed, { typed = it }, enabled = !busy, onSend = { send(typed) })
        else SignIn { nav.push(Screen.CloudSignIn(restore = false)) }
    }
}

/** A small pill over the talk: a panel to open. */
@Composable
private fun ToolChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.heightIn(min = 40.dp).clip(shape).background(if (selected) F.c.text else F.c.surface)
            .border(1.dp, if (selected) F.c.text else F.c.line, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = T.body(14.sp, FontWeight.SemiBold).copy(color = if (selected) F.c.bg else F.c.text), maxLines = 1)
    }
}

/** The first look: one line on what to ask, then questions to start with. */
@Composable
private fun Welcome(enabled: Boolean, onAsk: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CoachBubble {
            Text("Спроси про тренировку или попроси поменять план. Изменения можно вернуть.", style = T.body(15.sp))
        }
        SectionLabel("Можно спросить", Modifier.padding(start = 4.dp, top = 4.dp))
        STARTERS.forEach { q ->
            val shape = RoundedCornerShape(F.shape.radiusSmall + 6.dp)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).border(1.dp, F.c.line, shape)
                    .clickable(enabled = enabled, role = Role.Button) { onAsk(q) }.padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text(q, style = T.body(15.sp).copy(color = if (enabled) F.c.text else F.c.text3)) }
        }
    }
}

/** A message: the person's on the right in the accent's tint; the coach's on the left, with its changes under it. */
@Composable
private fun Message(m: CoachChatMsg, log: List<CoachLogEntry>) {
    if (m.mine) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(F.c.accent).padding(horizontal = 14.dp, vertical = 10.dp),
            ) { SelectionContainer { Text(m.text, style = T.body(15.sp).copy(color = F.c.onAccent)) } }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (m.text.isNotBlank()) CoachBubble { SelectionContainer { Text(forma.app.ui.components.markdownText(m.text), style = T.body(15.sp)) } }
        m.acts.forEachIndexed { i, a -> ActCard(m.id, i, a, log) }
    }
}

@Composable
private fun CoachBubble(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)).background(F.c.surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

/** «печатает…» while the answer comes. */
@Composable
private fun Typing() {
    CoachBubble { Text("Тренер думает…", style = T.secondary(15.sp)) }
}

/** A change under the coach's answer: made (with «Вернуть»), waiting for «Принять», or not made and why. */
@Composable
private fun ActCard(msgId: Long, index: Int, act: CoachChatAct, log: List<CoachLogEntry>) {
    val repo = LocalRepo.current
    var note by remember(msgId, index) { mutableStateOf<String?>(null) }
    // A tap is being made: the buttons wait (the talk makes one change at a time anyway, and once).
    var working by remember(msgId, index) { mutableStateOf(false) }
    fun run(block: suspend () -> Unit) {
        if (working) return
        working = true
        // On the talk's own scope: a change begun is made to the end even if the screen is left.
        CoachTalk.launch {
            try { block() } finally { working = false }
        }
    }
    // Taken back elsewhere («Изменения»): shown so here too.
    val state = if (act.state == CoachActState.DONE && log.firstOrNull { it.id == act.logId }?.undone == true) CoachActState.UNDONE else act.state
    val pending = state == CoachActState.PENDING
    val done = state == CoachActState.DONE
    FCard(
        Modifier.padding(start = 12.dp), gap = 8.dp, padding = PaddingValues(14.dp),
        outline = when { pending -> F.c.accent; done -> F.c.good.copy(alpha = 0.6f); else -> null },
    ) {
        val open = act.call.name == forma.app.data.CoachActions.OPEN
        SectionLabel(
            when (state) {
                CoachActState.PENDING -> "Предлагаю изменить"
                CoachActState.DONE -> if (open) "Где это в приложении" else "Сделано"
                CoachActState.UNDONE -> "Возвращено как было"
                CoachActState.DECLINED -> "Не стал менять"
                CoachActState.REFUSED -> "Не получилось"
            },
            color = when (state) { CoachActState.DONE -> F.c.good; CoachActState.PENDING -> F.c.accent; else -> F.c.text3 },
        )
        Text(act.text, style = T.body(15.sp, if (pending || done) FontWeight.SemiBold else FontWeight.Medium).copy(color = if (pending || done) F.c.text else F.c.text2))
        act.why?.let { Text("Почему: $it", style = T.secondary(14.sp)) }
        when (state) {
            CoachActState.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Принять", {
                    run {
                        runCatching { CoachTalk.accept(repo, msgId, index) }
                            .onSuccess { if (it?.state == CoachActState.PENDING) note = "С тех пор данные изменились — проверь и нажми «Принять» ещё раз." }
                            .onFailure { note = "Не получилось — попробуй ещё раз." }
                    }
                }, Modifier.weight(1f), enabled = !working)
                // As tall as «Принять» beside it.
                GhostButton("Не надо", { run { CoachTalk.decline(repo, msgId, index) } }, Modifier.weight(1f), height = 54.dp)
            }
            CoachActState.DONE -> if (open) {
                val nav = forma.app.ui.LocalNav.current
                PrimaryButton("Открыть", { openScreen(nav, (act.call.args["screen"] as? kotlinx.serialization.json.JsonPrimitive)?.content) }, Modifier.fillMaxWidth())
            } else if (log.firstOrNull { it.id == act.logId }?.undo != null) GhostButton("Вернуть как было", {
                run { if (runCatching { CoachTalk.undo(repo, msgId, index) }.getOrDefault(false) != true) note = "Уже не вернуть: после этого всё изменилось." }
            }, Modifier.fillMaxWidth())
            else -> Unit
        }
        note?.let { Text(it, style = T.secondary(13.sp)) }
    }
}

/** What opens under the chips: what the coach knows, its changes, how it works. */
@Composable
private fun PanelCard(p: Panel, notes: List<String>, log: List<CoachLogEntry>) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    FCard(gap = 10.dp) {
        when (p) {
            Panel.MEMORY -> {
                SectionLabel("Что тренер помнит")
                if (notes.isEmpty()) Text("Пока ничего. Расскажи тренеру, что тебе нравится, что нет, какие гантели в зале — он запомнит.", style = T.secondary(14.sp))
                notes.forEach { n ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(n, style = T.body(14.sp), modifier = Modifier.weight(1f))
                        GhostButton("Забыть", { CoachTalk.launch { repo.updateSettings { it.copy(coachNotes = it.coachNotes - n) } } })
                    }
                }
            }
            Panel.CHANGES -> {
                SectionLabel("Что поменял тренер")
                if (log.isEmpty()) Text("Пока ничего не менял.", style = T.secondary(14.sp))
                var note by remember { mutableStateOf<String?>(null) }
                var all by remember { mutableStateOf(false) }
                val zone = java.time.ZoneId.systemDefault()
                log.reversed().let { if (all) it else it.take(8) }.forEach { e ->
                    val day = java.time.Instant.ofEpochMilli(e.at).atZone(zone).toLocalDate()
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${Ru.date(day)} · ${e.text}", style = T.body(14.sp).copy(color = if (e.undone) F.c.text3 else F.c.text))
                        e.why?.let { Text("Почему: $it", style = T.secondary(13.sp)) }
                        if (e.undone) Text("Возвращено как было", style = T.secondary(13.sp))
                        else if (e.undo != null) GhostButton("Вернуть как было", {
                            CoachTalk.launch { if (runCatching { CoachTalk.undoEntry(repo, e) }.getOrDefault(false) != true) note = "Уже не вернуть: после этого всё изменилось." }
                        })
                    }
                }
                if (log.size > 8 && !all) GhostButton("Показать все (${log.size})", { all = true })
                note?.let { Text(it, style = T.secondary(13.sp)) }
            }
            Panel.ABOUT -> {
                SectionLabel("Как это работает")
                Text(
                    "Отвечает ИИ. Он видит твой план, тренировки, бег и самочувствие. Пиши своими словами, он сам подберёт, что поменять.",
                    style = T.body(14.sp),
                )
                Text(
                    "Сам меняет: самочувствие на сегодня, длину лёгкого бега, шаг веса под твой зал, звук и оформление. " +
                        "Их можно вернуть в «Изменения», пока тренировка не сдвинула то же самое.",
                    style = T.body(14.sp),
                )
                Text(
                    "Рабочий вес, упражнения, подходы, отдых, цель, стадион и паузу на неделю и больше меняет только после твоего «Принять». " +
                        "Правила плана он не обходит. Чего не может, скажет прямо, покажет, где это сделать, и запишет пожелание.",
                    style = T.body(14.sp),
                )
                // Where the questions go: DeepSeek first for the two on its key (Google when it fails), Google for the rest.
                val where = if (asksDeepSeek()) "в DeepSeek через продавца ключа, а если он не ответит, в Google" else "в Google"
                Text(
                    "При боли или болезни иди к врачу. Вопросы и сводка тренировок (без имени и почты) уходят $where. " +
                        "На бесплатном тарифе Google может использовать переписку для улучшения моделей.",
                    style = T.secondary(13.sp),
                )
            }
        }
    }
}

/** The field and the send button, on the keyboard when typing. */
@Composable
private fun InputBar(value: String, onChange: (String) -> Unit, enabled: Boolean, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(F.c.bg).padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val shape = RoundedCornerShape(22.dp)
        Box(
            Modifier.weight(1f).heightIn(min = 48.dp).clip(shape).background(F.c.surface).border(1.dp, F.c.line, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text("Напиши тренеру…", style = T.body(16.sp).copy(color = F.c.text3))
            BasicTextField(
                value, { if (it.length <= 1000) onChange(it) },
                Modifier.fillMaxWidth().semantics { contentDescription = "Сообщение тренеру" },
                textStyle = T.body(16.sp), cursorBrush = SolidColor(F.c.accent), maxLines = 6,
            )
        }
        val can = enabled && value.isNotBlank()
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (can) F.c.accent else F.c.surface2)
                .clickable(enabled = can, role = Role.Button, onClickLabel = "Отправить", onClick = onSend),
            contentAlignment = Alignment.Center,
        ) { Icon(Ic.send, "Отправить", tint = if (can) F.c.onAccent else F.c.text3, modifier = Modifier.size(24.dp)) }
    }
}

/** Not signed in: the coach works through the cloud. */
@Composable
private fun SignIn(onSignIn: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Тренер работает через облако: нужен вход по почте.", style = T.body(15.sp))
        PrimaryButton("Войти в облако", onSignIn, Modifier.fillMaxWidth())
    }
}

/**
 * The coach's word on a finished workout: what went well, what not, what to do next time — kept on the phone once asked.
 * Asked by itself for a person who has talked to the coach before; anyone else is told first which AI gets the workouts
 * (Google's, or DeepSeek first for the two on its key), and asks with a tap. Nothing shown when the coach is not set up.
 */
@Composable
fun CoachReviewCard(workoutId: Long, title: String, day: LocalDate) {
    val repo = LocalRepo.current
    val (api, signedIn) = coachApi()
    if (api == null || !signedIn) return
    var text by remember(workoutId) { mutableStateOf<String?>(null) }
    var error by remember(workoutId) { mutableStateOf<String?>(null) }
    var busy by remember(workoutId) { mutableStateOf(false) }
    var tries by remember(workoutId) { mutableIntStateOf(0) }
    // Talked to the coach before (a talk, a change, a review): reviews come by themselves. Null until known.
    var used by remember(workoutId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(workoutId, tries) {
        repo.store.coachReview(workoutId)?.let { text = it; return@LaunchedEffect }
        val known = used ?: (repo.store.coachChatNow().isNotEmpty() || repo.store.coachLogNow().isNotEmpty() || repo.store.hasCoachReviews())
        used = known
        if (!known && tries == 0) return@LaunchedEffect
        busy = true
        error = null
        try {
            val facts = coachWorkoutFacts(repo, workoutId) ?: return@LaunchedEffect
            // The recent workouts are enough to compare with: not the whole history.
            text = CoachTalk.review(repo, api, workoutId, reviewQuestion(title, day)) { coachContext(repo, reportChars = 8_000) + "\n\n" + facts }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CoachException) {
            error = e.reason.text
        } catch (e: Exception) {
            error = CoachException.Reason.FAILED.text
        } finally {
            busy = false
        }
    }
    val nav = LocalNav.current
    // The two on the DeepSeek key are asked by DeepSeek first, Gemini only when it fails.
    val who = if (asksDeepSeek()) "DeepSeek, а если он не ответит, Gemini от Google" else "Gemini от Google"
    FCard(gap = 10.dp) {
        SectionLabel("Разбор тренера")
        when {
            text != null -> SelectionContainer { Text(forma.app.ui.components.markdownText(text!!), style = T.body(15.sp)) }
            busy -> Text("Тренер смотрит тренировку…", style = T.secondary(14.sp))
            used == false && tries == 0 -> {
                Text("ИИ-тренер ($who) разберёт тренировку: что получилось, что нет и что сделать в следующий раз. " +
                    "Для этого он получит сводку твоих тренировок — без имени и почты.", style = T.secondary(14.sp))
                GhostButton("Разобрать тренировку", { tries++ }, Modifier.fillMaxWidth())
            }
            error != null -> {
                Text(error!!, style = T.secondary(14.sp))
                GhostButton("Ещё раз", { tries++ })
            }
        }
        if (text != null) GhostButton("Обсудить с тренером", { nav.push(Screen.Coach("Давай обсудим мою тренировку ${Ru.date(day)} «$title».", workoutId)) }, Modifier.fillMaxWidth())
    }
}

/** The screen of the coach's «Открыть» button ([forma.app.data.CoachActions.SCREENS]). */
internal fun openScreen(nav: forma.app.ui.Navigator, key: String?) {
    when (key?.lowercase()) {
        "today" -> nav.tab(Screen.Today)
        "plan" -> nav.tab(Screen.Plan)
        "progress" -> nav.tab(Screen.Progress)
        "history" -> nav.push(Screen.History)
        "exercises" -> nav.push(Screen.Library())
        "my_exercises" -> nav.push(Screen.MyExercises)
        "ideal_plan" -> nav.push(Screen.IdealPlan)
        "friends" -> nav.push(Screen.Friends)
        "body" -> nav.push(Screen.Body)
        "cloud" -> nav.push(Screen.Cloud)
        "profile" -> nav.push(Screen.Profile)
        "gym_goal" -> nav.push(Screen.GymGoal)
        "settings" -> nav.push(Screen.Settings)
        "calculators" -> nav.push(Screen.Calculators)
        "help" -> nav.push(Screen.Help)
        else -> Unit
    }
}
