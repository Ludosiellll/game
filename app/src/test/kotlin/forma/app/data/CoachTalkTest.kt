package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.sync.CoachApi
import forma.app.sync.CoachCall
import forma.app.sync.CoachException
import forma.app.sync.CoachReply
import forma.app.sync.CoachTurn
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The talk with the coach: kept, the changes made or waiting, accepted, declined, taken back; no answer — no trace. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CoachTalkTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository

    private class Fake(var reply: CoachReply? = null, var error: Exception? = null) : CoachApi {
        val asked = mutableListOf<Pair<List<CoachTurn>, JsonArray>>()
        override suspend fun askCoach(turns: List<CoachTurn>, context: String, tools: JsonArray): CoachReply {
            asked += turns to tools
            error?.let { throw it }
            return reply!!
        }
    }

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) }),
    )

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "coach-talk")
        store.clear()
        repo = TrainingRepository(db, store)
        repo.completeOnboarding(Profile(goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 3, 5), bodyWeightKg = 70.0))
    }

    @After fun tearDown() = db.close()

    @Test fun anAnswerWithChanges() = runBlocking<Unit> {
        val p = CoachActions.places(repo).first { !it.added }
        val api = Fake(
            CoachReply(
                "Понял. Запомнил и предлагаю на подход больше.",
                listOf(
                    call(CoachActions.REMEMBER, "text" to "Любит долгий бег", "why" to "сказал сам"),
                    call(CoachActions.SETS, "exercise" to p.exerciseId, "sets" to p.tuned.sets + 1, "why" to "растёт объём"),
                    call("delete_everything"),
                ),
            ),
        )
        val msg = CoachTalk.ask(repo, api, "  мне нравится бегать долго  ") { "сводка" }
        // The functions offered went with the question.
        assertEquals(CoachActions.declarations, api.asked.single().second)
        val chat = repo.store.coachChatNow()
        assertEquals(listOf(true, false), chat.map { it.mine })
        assertEquals("мне нравится бегать долго", chat.first().text)
        val (remember, sets, nonsense) = msg.acts
        assertEquals(CoachActState.DONE, remember.state)
        assertNotNull(remember.logId)
        assertEquals("сказал сам", remember.why)
        assertEquals(listOf("Любит долгий бег"), repo.store.settingsNow().coachNotes)
        assertEquals(CoachActState.PENDING, sets.state)
        assertEquals(null, repo.store.settingsNow().sets[p.key], "the plan waits for «Принять»")
        assertEquals(CoachActState.REFUSED, nonsense.state)
        // What the model is told next time: its text and what the app did.
        val said = CoachTalk.history(chat).last().text
        assertTrue("сделано: Запомнил" in said && "ждёт «Принять»" in said && "не сделано" in said, said)

        val accepted = CoachTalk.accept(repo, msg.id, 1)!!
        assertEquals(CoachActState.DONE, accepted.state)
        assertEquals(p.tuned.sets + 1, repo.store.settingsNow().sets[p.key])
        assertTrue(CoachTalk.undo(repo, msg.id, 1))
        assertEquals(null, repo.store.settingsNow().sets[p.key])
        assertEquals(CoachActState.UNDONE, repo.store.coachChatNow().last().acts[1].state)
        assertTrue(CoachTalk.undo(repo, msg.id, 0))
        assertTrue(repo.store.settingsNow().coachNotes.isEmpty())
    }

    @Test fun declinedStaysAsItWas() = runBlocking<Unit> {
        val p = CoachActions.places(repo).first { !it.added }
        val msg = CoachTalk.ask(repo, Fake(CoachReply("", listOf(call(CoachActions.SETS, "exercise" to p.exerciseId, "sets" to 1)))), "меньше подходов") { "" }
        CoachTalk.decline(repo, msg.id, 0)
        assertEquals(CoachActState.DECLINED, repo.store.coachChatNow().last().acts[0].state)
        assertEquals(null, CoachTalk.accept(repo, msg.id, 0), "declined: not accepted later")
        assertEquals(null, repo.store.settingsNow().sets[p.key])
    }

    @Test fun noAnswerLeavesNoTrace() = runBlocking<Unit> {
        val api = Fake(error = CoachException(CoachException.Reason.BUSY))
        val e = assertFailsWith<CoachException> { CoachTalk.ask(repo, api, "вопрос") { "" } }
        assertEquals(CoachException.Reason.BUSY, e.reason)
        assertTrue(repo.store.coachChatNow().isEmpty())
    }

    @Test fun theHistoryIsTheLastMessages() = runBlocking<Unit> {
        val api = Fake(CoachReply("ответ"))
        repeat(12) { CoachTalk.ask(repo, api, "вопрос $it") { "" } }
        val turns = api.asked.last().first
        assertEquals(CoachTalk.TURNS, turns.size)
        assertEquals(CoachTurn.USER, turns.last().role)
        assertEquals("вопрос 11", turns.last().text)
        CoachTalk.clear(repo)
        assertTrue(repo.store.coachChatNow().isEmpty())
    }
}
