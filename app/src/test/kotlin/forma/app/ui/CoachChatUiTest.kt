package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.CoachActions
import forma.app.data.Feel
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.SettingsStore
import forma.app.data.Status
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.app.sync.CoachApi
import forma.app.sync.CoachCall
import forma.app.sync.CoachReply
import forma.app.sync.CoachTurn
import forma.app.ui.screens.CoachReviewCard
import forma.app.ui.screens.CoachScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The coach's chat: a question, the answer with a change made and one to accept, «Вернуть», the panels, a review. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class CoachChatUiTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    /** Answers as told, and remembers the questions. */
    private class Fake(val answer: (String) -> CoachReply) : CoachApi {
        val asked = mutableListOf<String>()
        override suspend fun askCoach(turns: List<CoachTurn>, context: String, tools: JsonArray): CoachReply {
            asked += turns.last().text
            return answer(turns.last().text)
        }
    }

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) }),
    )

    private fun repo(name: String): TrainingRepository {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        runBlocking { repo.completeOnboarding(Profile(goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 3, 5), bodyWeightKg = 70.0)) }
        return repo
    }

    private fun show(repo: TrainingRepository, api: CoachApi?, look: String = "carbon", content: @androidx.compose.runtime.Composable () -> Unit = { CoachScreen() }) {
        compose.setContent {
            val settings by repo.store.settings.collectAsState(initial = forma.app.data.Settings())
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Coach()), LocalSettings provides settings, LocalCoachApi provides api) {
                FormaTheme(Looks.byId(look)) { Box(Modifier.fillMaxSize().background(F.c.bg)) { content() } }
            }
        }
        settle()
    }

    private fun waitFor(text: String, ms: Long = 10_000) = try {
        compose.waitUntil(ms) {
            settle()
            compose.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
    } catch (e: Throwable) {
        compose.onRoot().captureRoboImage("build/shots/debug-coach-${text.take(12)}.png")
        println("DEBUG failed=" + forma.app.data.CoachTalk.failed.value + " busy=" + forma.app.data.CoachTalk.busy.value)
        throw e
    }

    @Test fun aQuestionTheAnswerAndItsChanges() {
        val repo = repo("ui-coach-chat")
        val p = runBlocking { CoachActions.places(repo).first { !it.added } }
        val api = Fake {
            CoachReply(
                "Понял: сегодня полегче. И предлагаю на подход меньше в этом упражнении, пока восстанавливаешься.",
                listOf(
                    call(CoachActions.FEEL, "feel" to "low", "why" to "ты плохо спал, а сегодня тяжёлый день"),
                    call(CoachActions.SETS, "exercise" to p.exerciseId, "sets" to p.tuned.sets - 1, "why" to "меньше объёма на время восстановления"),
                ),
            )
        }
        show(repo, api)
        compose.onNodeWithText("Привет! Я тренер", substring = true).assertExists()
        compose.onRoot().captureRoboImage("build/shots/carbon-coach-hello.png")
        compose.onNodeWithText("Сегодня плохо себя чувствую — давай полегче").performScrollTo().performClick()
        waitFor("Предлагаю изменить")
        assertEquals(listOf("Сегодня плохо себя чувствую — давай полегче"), api.asked)
        // Made at once, with its way back; the plan's change waits.
        assertEquals(Feel.LOW, runBlocking { repo.store.settingsNow().feelOn(repo.today()) })
        compose.onNodeWithText("Почему: ты плохо спал", substring = true).assertExists()
        assertEquals(null, runBlocking { repo.store.settingsNow().sets[p.key] })
        compose.onRoot().captureRoboImage("build/shots/carbon-coach-chat.png")
        compose.onNodeWithText("Принять", ignoreCase = true).performScrollTo().performClick()
        compose.waitUntil(10_000) { settle(); compose.onAllNodesWithText("Сделано", ignoreCase = true).fetchSemanticsNodes().size == 2 }
        compose.waitUntil(10_000) { settle(); runBlocking { repo.store.settingsNow().sets[p.key] } == p.tuned.sets - 1 }
        // Taken back: the day as it was.
        compose.onAllNodesWithText("Вернуть как было", ignoreCase = true)[0].performScrollTo().performClick()
        compose.waitUntil(10_000) { settle(); runBlocking { repo.store.settingsNow().feelOn(repo.today()) } == Feel.UNSET }
        waitFor("Возвращено как было")
    }

    @Test fun typedInTheField() {
        val repo = repo("ui-coach-typed")
        show(repo, Fake { CoachReply("Лёгкий бег вырастет сам, если оценишь пробежку на 5 или легче.") }, look = "light")
        compose.onNode(hasSetTextAction() and hasContentDescription("Сообщение тренеру")).performTextInput("Почему мой бег не растёт?")
        settle()
        compose.onNodeWithContentDescription("Отправить").performClick()
        waitFor("Лёгкий бег вырастет сам")
        compose.onNodeWithText("Почему мой бег не растёт?").assertExists()
        compose.onRoot().captureRoboImage("build/shots/light-coach-chat.png")
    }

    @Test fun thePanels() {
        val repo = repo("ui-coach-panels")
        runBlocking { repo.updateSettings { it.copy(coachNotes = listOf("Гантели в зале через 2,5 кг", "Любит долгий бег")) } }
        show(repo, Fake { CoachReply("ок") })
        compose.onNodeWithText("Что знает обо мне").performClick()
        settle()
        compose.onNodeWithText("Гантели в зале через 2,5 кг").assertExists()
        compose.onAllNodesWithText("Забыть", ignoreCase = true)[0].performScrollTo().performClick()
        compose.waitUntil(10_000) { settle(); runBlocking { repo.store.settingsNow().coachNotes } == listOf("Любит долгий бег") }
        compose.onNodeWithText("Как это работает").performClick()
        settle()
        compose.onNodeWithText("Сам меняет", substring = true).assertExists()
        compose.onRoot().captureRoboImage("build/shots/carbon-coach-about.png")
    }

    /** No coach to ask (no cloud sign-in): the way in, not a field. */
    @Test fun withoutTheCloud() {
        val repo = repo("ui-coach-nocloud")
        show(repo, null)
        compose.onNodeWithText("Войти в облако", ignoreCase = true).assertExists()
        compose.onNodeWithText("Сообщение тренеру").assertDoesNotExist()
    }

    @Test fun aWorkoutReviewed() {
        val repo = repo("ui-coach-review")
        val id = runBlocking {
            repo.db.workouts().insert(
                WorkoutEntity(epochDay = LocalDate.of(2026, 10, 24).toEpochDay(), kind = Kind.LIFT, templateId = null, title = "Низ", startedAt = 0, finishedAt = 3_600_000, status = Status.DONE, rpe = 7, durationSec = 3600),
            )
        }
        val api = Fake { CoachReply("Хорошая тренировка: все подходы в плане. В следующий раз отдыхай между подходами приседа дольше — 3 минуты.") }
        show(repo, api) { androidx.compose.foundation.layout.Column { CoachReviewCard(id, "Низ", LocalDate.of(2026, 10, 24)) } }
        // Never talked to the coach: told first where the workouts go, asked with a tap — not by itself.
        waitFor("Разобрать тренировку", 40_000)
        compose.onNodeWithText("без имени и почты", substring = true).assertExists()
        assertTrue(api.asked.isEmpty())
        compose.onNodeWithText("Разобрать тренировку", ignoreCase = true).performClick()
        // The card's own coroutine resumes on the test's frames, slower than on a phone: more time.
        waitFor("отдыхай между подходами", 40_000)
        assertTrue(api.asked.single().startsWith("Разбери мою тренировку"))
        // Kept: not asked again.
        assertEquals("Хорошая тренировка: все подходы в плане. В следующий раз отдыхай между подходами приседа дольше — 3 минуты.", runBlocking { repo.store.coachReview(id) })
        compose.onRoot().captureRoboImage("build/shots/carbon-coach-review.png")
    }

    @Test fun aReviewComesByItselfAfterTalkingToTheCoach() {
        val repo = repo("ui-coach-review-used")
        val id = runBlocking {
            repo.store.editCoachChat { listOf(forma.app.data.CoachChatMsg(1, true, "привет")) }
            repo.db.workouts().insert(
                WorkoutEntity(epochDay = LocalDate.of(2026, 10, 24).toEpochDay(), kind = Kind.LIFT, templateId = null, title = "Верх", startedAt = 0, finishedAt = 3_600_000, status = Status.DONE, rpe = 7, durationSec = 3600),
            )
        }
        val api = Fake { CoachReply("Ровно по плану.") }
        show(repo, api) { androidx.compose.foundation.layout.Column { CoachReviewCard(id, "Верх", LocalDate.of(2026, 10, 24)) } }
        waitFor("Ровно по плану", 40_000)
        assertEquals(1, api.asked.size)
    }
}
