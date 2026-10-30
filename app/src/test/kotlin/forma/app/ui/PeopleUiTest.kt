package forma.app.ui

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.AppJson
import forma.app.data.CopyPart
import forma.app.data.Ease
import forma.app.sync.Conversation
import forma.app.sync.FriendMe
import forma.app.sync.FriendProgress
import forma.app.sync.FriendsApi
import forma.app.sync.Message
import forma.app.sync.Person
import forma.app.sync.PersonRun
import forma.app.sync.PersonSet
import forma.app.ui.screens.ChatContent
import forma.app.ui.screens.FriendContent
import forma.app.ui.screens.FriendsContent
import forma.app.ui.screens.FriendsUi
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.plan.DayPlan
import forma.core.plan.PlannedSession
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.plan.WeekPlan
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Denis (30.10): the people of the app open to all, their page with their statistics and charts, copying their plan
 * (all, their gym with one's own running, or one's own gym with theirs) with the week's load said, and messages.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class PeopleUiTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() = repeat(4) {
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private val today = LocalDate.now()

    @Test fun theListOfPeopleAndTheSwitchToBeInIt() {
        var listed: Boolean? = null
        var opened: Person? = null
        val ui = FriendsUi(
            me = FriendMe("Денис", "K7QM4XZ2", listed = false),
            people = listOf(
                Person("u1", "Егор", friend = true, lastDay = today.minusDays(1).toString(), month = 9,
                    aim = AppJson.encodeToJsonElement(Aim.serializer(), Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.BALANCED))),
                Person("u2", "Маша", lastDay = today.minusDays(10).toString(), month = 4),
            ),
        )
        compose.setContent {
            FormaTheme(Looks.byId("carbon")) {
                FriendsContent(ui, {}, {}, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}, {}, {}, section = 0, onListed = { listed = it }, onPerson = { opened = it })
            }
        }
        settle()
        compose.onNodeWithText("Показывать меня всем").assertExists()
        compose.onNodeWithText("Сейчас тебя видят только друзья").assertExists()
        compose.onNodeWithText("Егор · друг").assertExists()
        compose.onNodeWithText("Мышцы + выносливость", substring = true).assertExists()
        compose.onNodeWithText("9 за месяц", substring = true).assertExists()
        compose.onRoot().captureRoboImage("build/shots/carbon-people.png")
        compose.onNode(isToggleable() and hasAnySibling(hasText("Показывать меня всем"))).performClick()
        settle()
        assertEquals(true, listed)
        compose.onNodeWithText("Маша").performClick()
        assertEquals("u2", opened?.userId)
    }

    @Test fun conversationsWithUnreadCounts() {
        var opened: Conversation? = null
        var section = 2
        val ui = FriendsUi(
            me = FriendMe("Денис", "K7QM4XZ2"),
            conversations = listOf(
                Conversation("u1", "Егор", "Как прошёл контрольный?", "2026-10-30T15:05:00Z", unread = 2),
                Conversation("u2", "Маша", "До завтра", "2026-10-29T08:00:00Z", fromMe = true),
            ),
        )
        compose.setContent {
            FormaTheme(Looks.byId("carbon")) {
                FriendsContent(ui, {}, {}, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}, {}, {}, section = section, onSection = { section = it }, onChat = { opened = it })
            }
        }
        settle()
        compose.onNodeWithText("Сообщения · 2", ignoreCase = true).assertExists()
        compose.onNodeWithText("Как прошёл контрольный?").assertExists()
        compose.onNodeWithText("2 новых").assertExists()
        compose.onNodeWithText("Ты: До завтра").assertExists()
        compose.onNodeWithText("Егор").performClick()
        assertEquals("u1", opened?.userId)
    }

    /** A listed person (not a friend): write to them; their strength, running and goals; no «Удалить из друзей». */
    @Test fun aPersonsPageShowsTheirProgress() {
        var wrote = false
        val program = ProgramGenerator.generate(Profile(aim = Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.INTERMEDIATE, trainingDays = setOf(1, 3, 5, 6)), today)
        val p = FriendProgress(
            "Маша", shared = true, listed = true, friend = false, canWrite = true, vdot = 46.0,
            sets = (0 until 6).flatMap { w -> listOf(PersonSet(today.minusWeeks(5L - w).toString(), "bench", 50.0 + w * 2.5, 6), PersonSet(today.minusWeeks(5L - w).toString(), "squat", 70.0 + w * 2.5, 5)) },
            runs = (0 until 8).map { i -> PersonRun(today.minusDays(i * 4L).toString(), "EASY", 6000.0 + i * 100, 2100, 350.0, null, 44.0 + i * 0.2) },
            runGoal = AppJson.encodeToJsonElement(RunGoal.serializer(), RunGoal(5000, 1380)),
            program = AppJson.encodeToJsonElement(forma.core.plan.Program.serializer(), program),
        )
        compose.setContent { FormaTheme(Looks.byId("carbon")) { FriendContent("Маша", p, emptyList(), null, null, { _, _, _ -> }, {}, {}, {}, onWrite = { wrote = true }) } }
        settle()
        compose.onNodeWithText("Открытый профиль").assertExists()
        compose.onNodeWithText("Сила за год", ignoreCase = true).performScrollTo().assertExists()
        compose.onNodeWithText("Жим штанги лёжа").assertExists()
        compose.onNodeWithText("км за 12 недель", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("Бег: 5000 м за 23:00", substring = true).performScrollTo().assertExists()
        assertTrue(compose.onAllNodesWithText("Удалить из друзей").fetchSemanticsNodes().isEmpty())
        compose.onRoot().captureRoboImage("build/shots/carbon-person.png")
        compose.onNodeWithText("Написать", ignoreCase = true).performScrollTo().performClick()
        assertTrue(wrote)
    }

    /** «Зал»: the week it makes is said with its load, made lighter when asked, and copied as chosen. */
    @Test fun aMixedCopySaysTheLoadAndCanBeEased() {
        var copied: Pair<CopyPart, Set<Ease>>? = null
        val program = ProgramGenerator.generate(Profile(aim = Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.INTERMEDIATE, trainingDays = setOf(1, 3, 5, 6)), today)
        val p = FriendProgress("Маша", shared = true, listed = true, friend = false, program = AppJson.encodeToJsonElement(forma.core.plan.Program.serializer(), program))
        val lower = program.templates.first { it.legs }
        // Seven days of work, legs before the hard runs: too much.
        val heavy = program.copy(week = WeekPlan((1..7).map { d ->
            DayPlan(d, if (d % 2 == 1) listOf(PlannedSession(SessionKind.LIFT, lower.id)) else listOf(PlannedSession(SessionKind.RUN_QUALITY)))
        }, emptyList()))
        compose.setContent {
            FormaTheme(Looks.byId("carbon")) {
                FriendContent(
                    "Маша", p, emptyList(), null, null, { _, part, ease -> copied = part to ease }, {}, {}, {},
                    preview = { _, _, ease -> if (Ease.RUN in ease) program else heavy },
                )
            }
        }
        settle()
        compose.onNodeWithText("Скопировать программу", ignoreCase = true).performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Зал", ignoreCase = true).performClick()
        settle()
        compose.onNodeWithText("Твой бег останется", substring = true).assertExists()
        compose.onNodeWithText("Нагрузка большая:").assertExists()
        compose.onNodeWithText("Нет ни одного дня отдыха", substring = true).assertExists()
        // The dialog's first switch: «Одну тяжёлую пробежку — лёгкой».
        compose.onNodeWithText("Одну тяжёлую пробежку сделать лёгкой").assertExists()
        compose.onAllNodes(isToggleable())[0].performClick()
        settle()
        compose.onNodeWithText("Скопировать").performClick()
        assertEquals(CopyPart.GYM to setOf(Ease.RUN), copied)
    }

    /** A chat: the messages, newest at the bottom; one sent shows; read marks go to the server. */
    @Test fun aChatSendsAndMarksRead() {
        val api = object : FriendsApi {
            val sent = mutableListOf<String>()
            var marked = 0
            override suspend fun me(name: String?) = FriendMe()
            override suspend fun setSharing(activity: Boolean, body: Boolean) = FriendMe()
            override suspend fun add(code: String) = forma.app.sync.AddResult.NOT_FOUND
            override suspend fun answer(userId: String, accept: Boolean) {}
            override suspend fun remove(userId: String) {}
            override suspend fun list() = emptyList<forma.app.sync.Friend>()
            override suspend fun feed(before: String?, limit: Int, userId: String?) = emptyList<forma.app.sync.FeedItem>()
            override suspend fun progress(userId: String) = FriendProgress("Егор", true)
            override suspend fun conversations() = listOf(Conversation("u1", "Егор", "Привет", unread = 1))
            override suspend fun messages(userId: String, before: Long?, limit: Int) =
                listOf(Message(1, fromMe = false, body = "Привет! Как контрольный?", sentAt = "2026-10-30T15:05:00Z")) +
                    sent.mapIndexed { i, b -> Message(10L + i, fromMe = true, body = b, sentAt = "2026-10-30T15:06:00Z") }.reversed()
            override suspend fun markRead(userId: String): Int { marked++; return 1 }
            override suspend fun send(userId: String, body: String): Message { sent += body; return Message(10L + sent.size - 1, true, body, "2026-10-30T15:06:00Z") }
        }
        compose.setContent { FormaTheme(Looks.byId("carbon")) { ChatContent("u1", "Егор", api, onBack = {}, pollMs = 60_000) } }
        settle()
        compose.onNodeWithText("Привет! Как контрольный?").assertExists()
        assertTrue(api.marked >= 1)
        compose.onNodeWithContentDescription("Сообщение").performTextInput("Не засчитали, побегу ещё")
        settle()
        compose.onNodeWithContentDescription("Отправить").performClick()
        settle()
        compose.onNodeWithText("Не засчитали, побегу ещё").assertExists()
        assertEquals(listOf("Не засчитали, побегу ещё"), api.sent)
        compose.onRoot().captureRoboImage("build/shots/carbon-chat.png")
    }

    /** Server answers decode as the people migration writes them. */
    @Test fun serverAnswersDecode() {
        val people = AppJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Person.serializer()),
            """[{"user_id":"u","name":"Открытый","friend":false,"last_day":"2026-10-29","month":2,"aim":{"lift":"MUSCLE","run":"ENDURANCE","emphasis":"BALANCED"}}]""",
        )
        assertEquals(LiftFocus.MUSCLE, people.single().aim()?.lift)
        val unread = AppJson.decodeFromString(forma.app.sync.Unread.serializer(), """{"count":3,"from":[{"user_id":"u","name":"Егор","count":2,"last_id":41,"last":"Ну что?"},{"user_id":"v","name":"Маша","count":1,"last_id":39,"last":"Привет"}]}""")
        assertEquals(41, unread.lastId)
        assertEquals("3 новых сообщения" to "Егор, Маша", forma.app.sync.MessageNotice.text(unread))
        val one = AppJson.decodeFromString(forma.app.sync.Unread.serializer(), """{"count":1,"from":[{"user_id":"u","name":"Егор","count":1,"last_id":7,"last":"Ну что?"}]}""")
        assertEquals("Егор" to "Ну что?", forma.app.sync.MessageNotice.text(one))
        assertEquals(null, forma.app.sync.MessageNotice.text(forma.app.sync.Unread()))
        val p = AppJson.decodeFromString(FriendProgress.serializer(), buildJsonObject {
            put("name", JsonPrimitive("Маша")); put("shared", JsonPrimitive(true)); put("listed", JsonPrimitive(true)); put("friend", JsonPrimitive(false)); put("can_write", JsonPrimitive(true))
            put("run_goal", AppJson.parseToJsonElement("""{"meters":1500,"seconds":330}"""))
            put("sets", AppJson.parseToJsonElement("""[{"day":"2026-10-28","exercise_id":"bench","weight":80,"reps":6}]"""))
            put("runs", AppJson.parseToJsonElement("""[{"day":"2026-10-29","type":"EASY","distance_m":7200,"moving_sec":2390,"avg_pace":331.9,"work_pace":null,"vdot":44.5}]"""))
        }.toString())
        assertTrue(p.canWrite && !p.friend)
        assertEquals(1500, p.runGoal()?.meters)
        assertEquals(LocalDate.of(2026, 10, 28), p.sets.single().date)
    }
}
