package forma.app

import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.ui.screens.UpdateDialog
import forma.app.ui.screens.offerUpdate
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.app.update.Update
import forma.app.update.UpdateReceiver
import forma.app.update.Updater
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app updating itself (Denis: «чтобы любой мог обновить просто зайдя в приложение если есть обновление»): the latest
 * published build read from GitHub's answer, offered only when newer and never over a run or a session going on, and the
 * system installer's answers turned into what the person sees.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class UpdaterTest {
    @get:Rule val compose = createComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun release(tag: String, apk: String? = "forma.apk", draft: Boolean = false, url: String = "https://github.com/Ludosiellll/game/releases/download/$tag/forma.apk") = """
        {"tag_name":"$tag","draft":$draft,"prerelease":false,"html_url":"https://github.com/Ludosiellll/game/releases/tag/$tag",
         "body":"## Что нового\n- Обновления прямо в приложении.\n- **Цель в зале**: путь к весу.\n\n* Тренер видит тренировку.",
         "assets":[${if (apk == null) "" else """{"name":"$apk","size":4135261,"browser_download_url":"$url"}"""}]}
    """.trimIndent()

    @Before fun setUp() = Updater.reset()
    @After fun tearDown() = Updater.reset()

    @Test fun aPublishedBuildIsReadWithItsNumberWordsAndApk() {
        val u = assertNotNull(Updater.parse(release("v431700")))
        assertEquals(431700L, u.versionCode)
        assertEquals(listOf("Обновления прямо в приложении.", "Цель в зале: путь к весу.", "Тренер видит тренировку."), u.notes)
        assertTrue(u.apkUrl.endsWith("/v431700/forma.apk"))
        assertEquals(4135261L, u.size)
        // Not a build to offer: a draft, no APK, a tag without a number, an address that is not https, an odd answer.
        assertNull(Updater.parse(release("v431700", draft = true)))
        assertNull(Updater.parse(release("v431700", apk = null)))
        assertNull(Updater.parse(release("latest")))
        assertNull(Updater.parse(release("v431700", url = "http://example.com/forma.apk")))
        assertNull(Updater.parse("{oops"))
    }

    @Test fun onlyANewerBuildIsOffered() = runBlocking<Unit> {
        val mine = Updater.installed
        assertNull(Updater.newer(Update(mine, emptyList(), "https://x/a.apk", 1)))
        assertNull(Updater.newer(Update(mine - 5, emptyList(), "https://x/a.apk", 1)))
        // The same build published: nothing to say.
        Updater.check { release("v$mine") }
        assertIs<Updater.State.Idle>(Updater.state.value)
        // Asked once a run: a newer one published meanwhile waits for the next opening, or «Проверить обновления».
        Updater.check { release("v${mine + 10}") }
        assertIs<Updater.State.Idle>(Updater.state.value)
        Updater.later.value = true
        Updater.check(force = true) { release("v${mine + 10}") }
        val a = assertIs<Updater.State.Available>(Updater.state.value)
        assertEquals(mine + 10, a.update.versionCode)
        assertFalse(Updater.later.value, "asked for by hand: shown even after «Позже»")
        assertTrue(Updater.asked.value)
        // No network: nothing changes, nothing is said.
        Updater.reset()
        Updater.check { null }
        assertIs<Updater.State.Idle>(Updater.state.value)
    }

    @Test fun neverOverARunOrASessionGoingOn() {
        val u = Updater.State.Available(Update(Updater.installed + 1, emptyList(), "https://x/a.apk", 1))
        fun offer(ready: Boolean = true, news: Boolean = true, busy: Boolean = false, later: Boolean = false, running: Boolean = false, lifting: Boolean = false, asked: Boolean = false, state: Updater.State = u) =
            offerUpdate(ready, news, busy, later, running, lifting, asked, state)
        assertTrue(offer())
        assertFalse(offer(running = true), "a run going on: never")
        assertFalse(offer(running = true, asked = true), "a run going on: not even asked for")
        assertFalse(offer(lifting = true), "a gym session open: not by itself")
        assertTrue(offer(lifting = true, asked = true), "asked for by hand: its sets are saved")
        assertFalse(offer(news = false), "what is new in this one first")
        assertFalse(offer(busy = true), "the questionnaire or a run screen")
        assertFalse(offer(later = true), "«Позже»")
        assertFalse(offer(state = Updater.State.Idle))
        // One being installed stays on screen.
        assertTrue(offer(running = true, state = Updater.State.Installing(u.update)))
    }

    @Test fun withoutTheInstallPermissionThePersonIsSentToAllowIt() = runBlocking<Unit> {
        val u = Update(Updater.installed + 1, emptyList(), "https://x/a.apk", 1)
        shadowOf(ctx.packageManager).setCanRequestPackageInstalls(false)
        Updater.update(ctx, u)
        assertEquals(Updater.State.NeedsPermission(u), Updater.state.value)
    }

    @Test fun aBrokenDownloadIsNotInstalled() {
        val u = Update(Updater.installed + 1, emptyList(), "https://x/a.apk", 1)
        val f = File(ctx.cacheDir, "broken.apk").apply { writeText("not an apk") }
        assertEquals("Файл обновления повреждён. Попробуй ещё раз.", Updater.problem(ctx, f, u))
    }

    @Test fun theInstallersAnswersAreSaid() {
        val u = Update(Updater.installed + 1, emptyList(), "https://x/a.apk", 1)
        Updater.state.value = Updater.State.Installing(u)
        // The first update: the system's «Установить?» is shown over the app.
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        UpdateReceiver().onReceive(
            ctx,
            Intent(UpdateReceiver.ACTION_RESULT).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirm),
        )
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity?.action)
        // Cancelled there: said plainly, «Попробовать ещё раз» on screen.
        UpdateReceiver().onReceive(ctx, Intent(UpdateReceiver.ACTION_RESULT).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED))
        val f = assertIs<Updater.State.Failed>(Updater.state.value)
        assertTrue("отменена" in f.why, f.why)
    }

    private fun apkInCache(): File = File(ctx.cacheDir, "updates").apply { mkdirs() }.let { File(it, "forma-test.apk").apply { writeText("apk") } }

    /**
     * Denis's Redmi (HyperOS): «скачалось, а потом пишет установка отменена» — HyperOS refuses an app's own session
     * ("INSTALL_FAILED_ABORTED: Permission denied"). On Xiaomi, Redmi, POCO the checked file goes to the phone's own
     * installer (as an APK opened from a chat): no session at all.
     */
    @Test fun onXiaomiTheUpdateOpensInThePhonesInstaller() {
        assertTrue(Updater.systemInstallerOnly("Xiaomi", "Redmi"))
        assertTrue(Updater.systemInstallerOnly("Xiaomi", "POCO"))
        assertFalse(Updater.systemInstallerOnly("Google", "google"))
        val u = Update(Updater.installed + 1, emptyList(), "https://x/a.apk", 1)
        val sessions = mutableListOf<Boolean>()
        Updater.installer = { _, _, ask -> sessions += ask }
        org.robolectric.shadows.ShadowBuild.setManufacturer("Xiaomi")
        Updater.installChecked(ctx, u, apkInCache())
        assertEquals(Updater.State.Ready(u), Updater.state.value)
        assertTrue(sessions.isEmpty(), "no session on Xiaomi: $sessions")
        assertTrue(Updater.openInstaller(ctx))
        val open = assertNotNull(shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, open.action)
        assertEquals("application/vnd.android.package-archive", open.type)
        assertEquals("content", open.data?.scheme)
        assertTrue(open.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    /**
     * Elsewhere a silent session first; refused without the confirmation shown (the person chose nothing) — asked again
     * with «Установить?»; refused again — the phone's installer. The system's words stay on the screen.
     */
    @Test fun aRefusedSilentInstallIsAskedAgainThenGoesToThePhonesInstaller() {
        val u = Update(Updater.installed + 1, emptyList(), "https://x/a.apk", 1)
        val sessions = mutableListOf<Boolean>()
        Updater.installer = { _, _, ask -> sessions += ask }
        org.robolectric.shadows.ShadowBuild.setManufacturer("Google")
        org.robolectric.shadows.ShadowBuild.setBrand("google")
        Updater.installChecked(ctx, u, apkInCache())
        assertEquals(listOf(false), sessions)
        fun aborted() = UpdateReceiver().onReceive(
            ctx,
            Intent(UpdateReceiver.ACTION_RESULT).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "INSTALL_FAILED_ABORTED: Permission denied"),
        )
        aborted()
        assertEquals(listOf(false, true), sessions, "asked again with the confirmation")
        assertIs<Updater.State.Installing>(Updater.state.value)
        aborted()
        assertEquals(Updater.State.Ready(u), Updater.state.value)
        // A failure of another kind: said, with the system's words and the phone's installer offered.
        UpdateReceiver().onReceive(
            ctx,
            Intent(UpdateReceiver.ACTION_RESULT).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "INSTALL_FAILED_INTERNAL_ERROR"),
        )
        val f = assertIs<Updater.State.Failed>(Updater.state.value)
        assertTrue(f.system)
        assertEquals("INSTALL_FAILED_INTERNAL_ERROR", f.detail)
    }

    /** The phone's installer on the screen: said what to press there, with «Открыть установщик» if it did not show. */
    @Test fun theReadyWindowSaysToPressUpdateInThePhonesInstaller() {
        val u = Update(Updater.installed + 1, emptyList(), "https://x/a.apk", 1)
        compose.setContent { FormaTheme(Looks.byId("carbon")) { UpdateDialog(Updater.State.Ready(u)) {} } }
        compose.onNodeWithText("откроется установщик телефона", substring = true).assertExists()
        compose.onNodeWithText("Открыть установщик", ignoreCase = true).assertExists()
    }

    @Test fun theWindowSaysWhatIsNewAndLaterClosesIt() {
        val u = Update(Updater.installed + 1, listOf("Обновления прямо в приложении."), "https://x/a.apk", 1)
        var later = false
        compose.setContent { FormaTheme(Looks.byId("carbon")) { UpdateDialog(Updater.State.Available(u)) { later = true } } }
        compose.onNodeWithText("Вышла новая версия").assertExists()
        compose.onNodeWithText("Обновления прямо в приложении.").assertExists()
        compose.onNodeWithText("Обновить", ignoreCase = true).assertExists()
        compose.onNodeWithText("Позже", ignoreCase = true).performClick()
        assertTrue(later)
    }
}
