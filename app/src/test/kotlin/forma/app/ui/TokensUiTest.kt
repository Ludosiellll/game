package forma.app.ui

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.sync.CoachException
import forma.app.sync.TokenLog
import forma.app.sync.TokenPerson
import forma.app.sync.TokenUsage
import forma.app.ui.screens.TokensScreen
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** «Токены ИИ» (Denis, 5 November): what is left on the coach's key, who spent how much, the last requests. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class TokensUiTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() = repeat(4) {
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    // 2,000 units of 10 million spent at 0.1 a token: 20 thousand tokens spent, 99.98 million left.
    private val usage = TokenUsage(
        balance = 9_998_000.0, limit = 10_000_000.0, rate = 0.1,
        people = listOf(
            TokenPerson("Ludosiel", me = true, requests = 12, units = 1500.0, weekRequests = 12, weekUnits = 1500.0),
            TokenPerson("Егор", requests = 3, units = 500.0, weekRequests = 1, weekUnits = 120.0),
        ),
        log = listOf(
            TokenLog(at = "2026-11-05T14:34:00Z", model = "deepseek-v4-flash", input = 9200, output = 310, units = 40.0, ms = 2400, status = "success"),
            TokenLog(at = "2026-11-05T14:30:00Z", model = "deepseek-v4-flash", status = "error", error = "rate limit"),
        ),
    )

    @Test fun itShowsWhatIsLeftWhoSpentItAndTheLastRequests() {
        var loads = 0
        compose.setContent {
            CompositionLocalProvider(LocalNav provides Navigator(Screen.Tokens)) {
                FormaTheme(Looks.byId("carbon")) { TokensScreen(load = { loads++; usage }) }
            }
        }
        settle()
        compose.onNodeWithText("≈ 99,98 млн токенов").assertExists()
        compose.onNodeWithText("Из 100,00 млн. Потрачено 20,0 тыс. (меньше 0,1 %).").assertExists()
        compose.onNodeWithText("Ты").assertExists()
        compose.onNodeWithText("Егор").assertExists()
        compose.onNodeWithText("12 вопросов · 15,0 тыс. токенов · за 7 дней 12 · 15,0 тыс.").assertExists()
        compose.onNodeWithText("вопрос и данные 9200 · ответ 310 · списано 400 токенов").assertExists()
        compose.onNodeWithText("ошибка: rate limit").assertExists()
        compose.onRoot().captureRoboImage("build/shots/carbon-tokens.png")
        assertEquals(1, loads)
        compose.onNodeWithText("Обновить", ignoreCase = true).performClick()
        settle()
        assertEquals(2, loads)
    }

    @Test fun signedOutItSaysWhereToSignIn() {
        compose.setContent {
            CompositionLocalProvider(LocalNav provides Navigator(Screen.Tokens)) {
                FormaTheme(Looks.byId("carbon")) { TokensScreen(load = { throw CoachException(CoachException.Reason.NOT_SIGNED_IN) }) }
            }
        }
        settle()
        compose.onNodeWithText(CoachException.Reason.NOT_SIGNED_IN.text).assertExists()
    }
}
