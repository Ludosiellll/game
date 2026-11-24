package forma.app.ui

import android.os.Looper
import android.view.View
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.sync.MessageNotice
import forma.app.ui.screens.MoreScreen
import forma.app.ui.screens.RELEASES
import forma.app.ui.screens.ScrollScreen
import forma.app.ui.screens.UpdateDialog
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.app.update.Update
import forma.app.update.Updater
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app's shell on the screen, from the audit of 24 November (group G-data-shell): the update window, the root, insets. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class AuditFixDataShellUiTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val profile = Profile(goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 3, 5), bodyWeightKg = 70.0, lapMeters = 230)

    private fun newer() = Update(Updater.installed + 1, emptyList(), "https://x/a.apk", 1)

    @Before fun setUp() {
        forma.app.run.RunSession.clear()
        Updater.reset()
        Opens.pending.value = null
    }

    @After fun tearDown() {
        Updater.reset()
        Opens.pending.value = null
    }

    private fun settle() = repeat(4) {
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun repo(name: String): TrainingRepository {
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
    }

    /** SHELL-5: «Отмена» stops a download that held the whole app, and the offer is back with «Обновить» and «Позже». */
    @Test fun aDownloadCanBeStoppedFromItsWindow() {
        val u = newer()
        Updater.state.value = Updater.State.Downloading(u, 0.07f)
        compose.setContent { FormaTheme(Looks.byId("carbon")) { UpdateDialog(Updater.state.collectAsState().value) {} } }
        compose.onNodeWithText("Отмена", ignoreCase = true).performClick()
        compose.waitForIdle()
        assertEquals(Updater.State.Available(u), Updater.state.value)
        compose.onNodeWithText("Обновить", ignoreCase = true).assertExists()
        compose.onNodeWithText("Позже", ignoreCase = true).assertExists()
    }

    /** SHELL-3: an update downloaded and put off with «Позже» is said in the row of «Ещё», not only the version. */
    @Test fun theVersionRowSaysAnUpdateIsDownloaded() {
        val repo = repo("audit-shell3-${System.nanoTime()}")
        Updater.state.value = Updater.State.Ready(newer())
        Updater.later.value = true
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.More)) {
                FormaTheme(Looks.byId("carbon")) { MoreScreen() }
            }
        }
        compose.onNodeWithText("Обновление скачано, нажми, чтобы поставить").assertExists()
    }

    /** SHELL-7: right after the first questionnaire a message notification opens «Люди» and a newer version is offered. */
    @Test fun afterTheQuestionnaireAMessageNoticeAndTheUpdateWorkAtOnce() {
        // Checked for this run already: the screen asks GitHub nothing.
        runBlocking { Updater.check { null } }
        val repo = repo("audit-shell7-${System.nanoTime()}")
        compose.setContent { CompositionLocalProvider(LocalRepo provides repo) { AppRoot() } }
        // A first install: the questionnaire is on the screen (and the news of earlier versions counts as seen).
        compose.waitUntil(10_000) { settle(); runBlocking { repo.store.settingsNow().newsSeen } == RELEASES.last().id }
        // The questionnaire's last step.
        runBlocking { repo.completeOnboarding(profile) }
        // A tap on a message notification: «Люди» opens and takes the request.
        Opens.pending.value = MessageNotice.OPEN_MESSAGES
        compose.waitUntil(10_000) { settle(); Opens.pending.value == null }
        // A newer version published: its window shows.
        Updater.state.value = Updater.State.Available(newer())
        compose.waitUntil(10_000) { settle(); compose.onAllNodesWithText("Вышла новая версия").fetchSemanticsNodes().isNotEmpty() }
    }

    /** SHELL-6: edge to edge, a scrolling screen («Цель, дни и данные») ends above the keyboard, its last field in sight. */
    @Test fun theKeyboardDoesNotCoverTheEndOfAScrollingScreen() {
        var view: View? = null
        compose.setContent {
            val v = LocalView.current
            SideEffect { view = v }
            ScrollScreen {
                repeat(50) { Text("Строка $it") }
                Text("Пульс в покое", Modifier.testTag("last"))
            }
        }
        compose.waitForIdle()
        val ime = 900
        compose.runOnIdle {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build()
            ViewCompat.dispatchApplyWindowInsets(view!!, insets)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("last").performScrollTo()
        val height = compose.onRoot().fetchSemanticsNode().size.height
        val bottom = compose.onNodeWithTag("last").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(bottom <= height - ime + 1f, "the field ends at $bottom px, the keyboard starts at ${height - ime} px")
    }
}
