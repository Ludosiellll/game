package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.AppJson
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.sync.AddResult
import forma.app.sync.Conversation
import forma.app.sync.FeedItem
import forma.app.sync.Friend
import forma.app.sync.FriendMe
import forma.app.sync.FriendProgress
import forma.app.sync.FriendsApi
import forma.app.sync.Message
import forma.app.sync.MessageNotice
import forma.app.sync.Person
import forma.app.ui.screens.ChatContent
import forma.app.ui.screens.FriendContent
import forma.app.ui.screens.FriendsContent
import forma.app.ui.screens.FriendsScreen
import forma.app.ui.screens.FriendsUi
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** People, friends and messages after the audit's social group: what its findings showed, fixed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class AuditFixSocialUiTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() = repeat(4) {
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    // The notification's ask is the app's one place for it: none left over from another test, none left for the next.
    @Before fun setUp() { Opens.pending.value = null }
    @After fun tearDown() { Opens.pending.value = null }

    private fun repo(name: String): TrainingRepository {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
    }

    /** The server for Денис: no friends yet, one conversation with Егор; the conversations asked for are counted. */
    private open class Server : FriendsApi {
        var me = FriendMe("Денис", "K7QM4XZ2", shareActivity = true, shareBody = false, listed = false)
        var conversationCalls = 0
        override suspend fun me(name: String?) = me
        override suspend fun setSharing(activity: Boolean, body: Boolean): FriendMe {
            me = me.copy(shareActivity = activity, shareBody = body)
            return me
        }
        override suspend fun add(code: String) = AddResult.NOT_FOUND
        override suspend fun answer(userId: String, accept: Boolean) {}
        override suspend fun remove(userId: String) {}
        override suspend fun list() = emptyList<Friend>()
        override suspend fun feed(before: String?, limit: Int, userId: String?) = emptyList<FeedItem>()
        override suspend fun progress(userId: String) = FriendProgress("Егор", true)
        override suspend fun people() = emptyList<Person>()
        override suspend fun conversations(): List<Conversation> {
            conversationCalls++
            return listOf(Conversation("u1", "Егор", "Ты где?", unread = 1))
        }
    }

    /** Егор's side of a chat: the messages as the server pages them, newest first; read once marked so. */
    private class ChatServer(messages: List<Message>) : Server() {
        val all = messages.toMutableList()
        /** The `before` of each request for messages (null — the newest). */
        val asked = mutableListOf<Long?>()
        var marked = 0
        private var readUpTo = 0L
        override suspend fun messages(userId: String, before: Long?, limit: Int): List<Message> {
            asked += before
            return all.filter { before == null || it.id < before }.sortedByDescending { it.id }.take(limit)
                .map { if (!it.fromMe && it.id <= readUpTo && it.readAt == null) it.copy(readAt = "2026-11-24T10:01:00Z") else it }
        }
        override suspend fun markRead(userId: String): Int {
            marked++
            readUpTo = all.maxOf { it.id }
            return 1
        }
    }

    /** A screen's lifecycle moved by the test: in front, or stopped (the phone locked, another app on top). */
    private class Front : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun showFriends(repo: TrainingRepository, api: FriendsApi) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Friends), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) {
                    Box(Modifier.fillMaxSize().background(F.c.bg)) { FriendsScreen(api = api, account = "denis@example.com") }
                }
            }
        }
        settle()
    }

    private fun segment(label: String) = hasText(label, substring = true, ignoreCase = true) and isSelectable()

    /**
     * people-sharing-off-ignored: shown to all («Показывать меня всем», on for everybody since 4 November), the friends'
     * switch off does not say that friends see only the name, and a new profile is not told that this switch hides it.
     */
    @Test fun sharingOffWhileShownToAllSaysEveryoneStillSeesTheTraining() {
        var ui by mutableStateOf(FriendsUi(me = FriendMe("Денис", "K7QM4XZ2", shareActivity = false, listed = true)))
        compose.setContent {
            FormaTheme(Looks.byId("carbon")) { FriendsContent(ui, {}, {}, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}, {}, {}, section = 1) }
        }
        settle()
        compose.onNodeWithText("Тренировки всё равно видны всем, пока включено «Показывать меня всем».").assertExists()
        assertTrue(compose.onAllNodesWithText("только имя", substring = true).fetchSemanticsNodes().isEmpty())
        // Not shown to all: then friends do see only the name.
        ui = FriendsUi(me = FriendMe("Денис", "K7QM4XZ2", shareActivity = false, listed = false))
        settle()
        compose.onNodeWithText("Сейчас друзья не видят твоих тренировок, только имя.").assertExists()
        // A new profile shown to all: no advice that the friends' switch hides the training.
        ui = FriendsUi(me = FriendMe("", "K7QM4XZ2", shareActivity = true, listed = true), fresh = true)
        settle()
        assertTrue(compose.onAllNodesWithText("выключи «Тренировки и прогресс»", substring = true).fetchSemanticsNodes().isEmpty())
    }

    /** sharing-toggles-race: two switches tapped before the server answers both count, in the order tapped. */
    @Test fun twoSwitchesTappedQuicklyBothCount() {
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        val server = object : Server() {
            val calls = mutableListOf<Pair<Boolean, Boolean>>()
            override suspend fun setSharing(activity: Boolean, body: Boolean): FriendMe {
                calls += activity to body
                CompletableDeferred<Unit>().also { gates += it }.await()
                return super.setSharing(activity, body)
            }
        }
        showFriends(repo("ui-audit-social-sharing"), server)
        compose.onNode(segment("Друзья")).performClick()
        settle()
        // «Тренировки и прогресс» off, then «Вес тела» on, before the server answers the first.
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        settle()
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        settle()
        gates.first().complete(Unit)
        settle()
        gates.last().complete(Unit)
        settle()
        assertEquals(listOf(false to false, false to true), server.calls)
        assertEquals(false to true, server.me.shareActivity to server.me.shareBody)
        compose.onAllNodes(isToggleable())[0].assertIsOff()
        compose.onAllNodes(isToggleable())[1].assertIsOn()
    }

    /**
     * notification-tap-on-people: a message notification tapped while «Люди» is open goes to the messages, loaded anew,
     * and so does the next one: each ask is taken, so none is left to swallow the next tap.
     */
    @Test fun aNotificationTappedOnPeopleOpensTheMessagesEachTime() {
        val server = Server()
        showFriends(repo("ui-audit-social-open"), server)
        compose.onNode(segment("Люди")).assertIsSelected()
        assertEquals(1, server.conversationCalls)
        Opens.pending.value = MessageNotice.OPEN_MESSAGES
        settle()
        compose.onNode(segment("Сообщения")).assertIsSelected()
        compose.onNodeWithText("Ты где?").assertExists()
        assertEquals(2, server.conversationCalls)
        assertNull(Opens.pending.value)
        // Back to «Люди» by hand; the next notification is tapped.
        compose.onNode(segment("Люди")).performClick()
        settle()
        Opens.pending.value = MessageNotice.OPEN_MESSAGES
        settle()
        compose.onNode(segment("Сообщения")).assertIsSelected()
        assertEquals(3, server.conversationCalls)
        assertNull(Opens.pending.value)
    }

    /** chat-reads-in-background: a chat left behind the lock screen or another app neither asks for messages nor reads them. */
    @Test fun aChatOutOfSightNeitherPollsNorMarksRead() {
        val front = Front()
        val server = ChatServer(listOf(Message(1, fromMe = false, body = "Привет", sentAt = "2026-11-24T10:00:00Z")))
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides front) {
                FormaTheme(Looks.byId("carbon")) { ChatContent("u1", "Егор", server, onBack = {}, pollMs = 60_000) }
            }
        }
        settle()
        assertEquals(1, server.asked.size)
        assertEquals(1, server.marked)
        // The phone locked with the chat open; Егор answers meanwhile. Two polls' time goes by.
        compose.runOnIdle { front.registry.currentState = Lifecycle.State.CREATED }
        settle()
        server.all += Message(2, fromMe = false, body = "Ты где?", sentAt = "2026-11-24T10:05:00Z")
        compose.mainClock.advanceTimeBy(130_000)
        settle()
        assertEquals(1, server.asked.size, "no polls while the chat is out of sight")
        assertEquals(1, server.marked, "nothing marked read behind the lock screen")
        // In front again: the answer comes at once, and is read then.
        compose.runOnIdle { front.registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeBy(16)
        settle()
        assertEquals(2, server.asked.size)
        assertEquals(2, server.marked)
        compose.onNodeWithText("Ты где?").assertExists()
    }

    /** chat-only-last-50: the messages before the newest 50 page in at the top, and the next poll keeps them. */
    @Test fun olderMessagesLoadAtTheTopAndStay() {
        val server = ChatServer(
            (1L..120L).map { Message(it, fromMe = it % 2 == 0L, body = "Сообщение $it", sentAt = "2026-11-20T10:00:00Z", readAt = "2026-11-20T10:01:00Z") },
        )
        compose.setContent { FormaTheme(Looks.byId("carbon")) { ChatContent("u1", "Егор", server, onBack = {}, pollMs = 60_000) } }
        settle()
        compose.onNodeWithText("Сообщение 120").assertExists()
        assertEquals(listOf<Long?>(null), server.asked)
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(0)
        settle()
        assertEquals(listOf(null, 71L), server.asked)
        list.performScrollToIndex(0)
        settle()
        assertEquals(listOf(null, 71L, 21L), server.asked)
        list.performScrollToIndex(0)
        settle()
        compose.onNodeWithText("Сообщение 1").assertExists()
        // The next poll brings the newest 50 again; the older ones stay in the chat.
        compose.mainClock.advanceTimeBy(60_000)
        settle()
        assertEquals(listOf(null, 71L, 21L, null), server.asked)
        list.performScrollToNode(hasText("Сообщение 1"))
        compose.onNodeWithText("Сообщение 1").assertExists()
    }

    /**
     * friend-not-sharing-page: a friend who shares nothing and is not listed comes from the server with the name and
     * «Написать» allowed (it was an error); the page says they share nothing and offers «Написать».
     */
    @Test fun aFriendWhoSharesNothingCanBeWrittenTo() {
        val p = AppJson.decodeFromString(
            FriendProgress.serializer(), """{"name":"Егор","shared":false,"listed":false,"friend":true,"can_write":true}""",
        )
        assertTrue(p.canWrite && p.friend && !p.shared)
        var wrote = false
        compose.setContent {
            FormaTheme(Looks.byId("carbon")) { FriendContent("Егор", p, emptyList(), null, null, { _, _, _ -> }, {}, {}, {}, onWrite = { wrote = true }) }
        }
        settle()
        compose.onNodeWithText("Егор пока не делится тренировками.").assertExists()
        compose.onNodeWithText("Написать", ignoreCase = true).performClick()
        assertTrue(wrote)
    }
}
