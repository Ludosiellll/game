package forma.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.sync.FriendsApi
import forma.app.sync.Message
import forma.app.sync.MessageNotice
import forma.app.sync.describeFriends
import forma.app.ui.LocalCloud
import forma.app.ui.LocalNav
import forma.app.ui.components.TopBar
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** How often an open chat asks for new messages. */
private const val CHAT_POLL_MS = 5_000L

/** «18:05» today, «вчера 18:05», «12 октября 18:05» before. */
fun messageTime(sentAt: String, today: LocalDate = LocalDate.now()): String {
    val t = runCatching { OffsetDateTime.parse(sentAt).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull() ?: return ""
    val clock = "%02d:%02d".format(t.hour, t.minute)
    return if (t.toLocalDate() == today) clock else "${relativeDay(t.toLocalDate(), today)} $clock"
}

/** A conversation with one person: the messages, newest at the bottom, refreshed while open; write, block. */
@Composable
fun ChatScreen(userId: String, name: String) {
    val nav = LocalNav.current
    ChatContent(userId, name, LocalCloud.current?.friends, onBack = { nav.back() })
}

/** The chat itself, on [api] (null — no cloud): what [ChatScreen] shows. */
@Composable
fun ChatContent(userId: String, name: String, api: FriendsApi?, onBack: () -> Unit, pollMs: Long = CHAT_POLL_MS) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var typed by rememberSaveable(userId) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var canWrite by remember { mutableStateOf(true) }
    var blocked by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var askBlock by remember { mutableStateOf(false) }

    suspend fun refresh(a: FriendsApi) {
        runCatching { a.messages(userId).reversed() }
            .onSuccess { fresh -> if (fresh != messages) messages = fresh; error = null }
            .onFailure { error = describeFriends(it) }
        // Read here: off the server's count and the phone's notification.
        if (messages.any { !it.fromMe && it.readAt == null }) {
            runCatching { a.markRead(userId) }
            MessageNotice.clear(context)
        }
    }
    LaunchedEffect(userId, api) {
        val a = api ?: return@LaunchedEffect
        runCatching { a.conversations().firstOrNull { it.userId == userId } }.getOrNull()?.let { canWrite = it.canWrite; blocked = it.blocked }
        while (true) {
            refresh(a)
            delay(pollMs)
        }
    }
    fun send() {
        val a = api ?: return
        val text = typed.trim()
        if (text.isEmpty() || sending) return
        sending = true
        scope.launch {
            runCatching { a.send(userId, text) }
                .onSuccess { m -> messages = messages + m; typed = ""; error = null }
                .onFailure { error = describeFriends(it) }
            sending = false
        }
    }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.scrollToItem(messages.size - 1) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
        Box(Modifier.padding(horizontal = 20.dp)) {
            TopBar(name, if (blocked) "Заблокирован" else "Сообщения", onBack = onBack) {
                TextButton({ askBlock = true }) {
                    Text(if (blocked) "Разблокировать" else "Заблокировать", style = T.body(13.sp).copy(color = F.c.text2))
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            if (messages.isEmpty() && error == null) {
                item(key = "empty") { Text("Сообщений пока нет. Напиши первым.", style = T.secondary(14.sp), modifier = Modifier.padding(8.dp)) }
            }
            items(messages, key = { it.id }) { m -> ChatBubble(m) }
            error?.let { e -> item(key = "error") { Text(e, style = T.body(14.sp).copy(color = F.c.warn), modifier = Modifier.padding(horizontal = 8.dp)) } }
        }
        when {
            blocked -> Text("Переписка заблокирована. Разблокировать можно сверху.", style = T.secondary(13.sp), modifier = Modifier.padding(16.dp))
            !canWrite -> Text("Сейчас написать нельзя: профиль закрыт или переписка заблокирована.", style = T.secondary(13.sp), modifier = Modifier.padding(16.dp))
            else -> MessageInput(typed, { typed = it }, enabled = !sending && api != null, onSend = ::send)
        }
    }
    if (askBlock) {
        AlertDialog(
            onDismissRequest = { askBlock = false },
            containerColor = F.c.surface,
            title = { Text(if (blocked) "Разблокировать $name?" else "Заблокировать $name?", style = T.title(20.sp)) },
            text = {
                Text(
                    if (blocked) "Снова сможете писать друг другу."
                    else "Вы не сможете писать друг другу, и $name не увидит тебя в списке.",
                    style = T.secondary(14.sp),
                )
            },
            confirmButton = {
                TextButton({
                    askBlock = false
                    val a = api ?: return@TextButton
                    val to = !blocked
                    scope.launch {
                        runCatching { a.block(userId, to) }
                            .onSuccess { blocked = to; canWrite = !to }
                            .onFailure { error = describeFriends(it) }
                    }
                }) { Text(if (blocked) "Разблокировать" else "Заблокировать", style = T.body(15.sp, FontWeight.Bold).copy(color = if (blocked) F.c.accent else F.c.bad)) }
            },
            dismissButton = { TextButton({ askBlock = false }) { Text("Отмена", style = T.body(15.sp)) } },
        )
    }
}

@Composable
private fun ChatBubble(m: Message) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .clip(if (m.fromMe) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                .background(if (m.fromMe) F.c.accent else F.c.surface)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = if (m.fromMe) Alignment.End else Alignment.Start,
        ) {
            SelectionContainer { Text(m.body, style = T.body(15.sp).copy(color = if (m.fromMe) F.c.onAccent else F.c.text)) }
            Text(
                messageTime(m.sentAt) + if (m.fromMe && m.readAt != null) " · прочитано" else "",
                style = T.secondary(11.sp).copy(color = if (m.fromMe) F.c.onAccent.copy(alpha = 0.75f) else F.c.text3),
            )
        }
    }
}

@Composable
private fun MessageInput(value: String, onChange: (String) -> Unit, enabled: Boolean, onSend: () -> Unit) {
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
            if (value.isEmpty()) Text("Сообщение…", style = T.body(16.sp).copy(color = F.c.text3))
            BasicTextField(
                value, { if (it.length <= 1000) onChange(it) },
                Modifier.fillMaxWidth().semantics { contentDescription = "Сообщение" },
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
