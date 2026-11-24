package forma.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.sync.MessageNotice
import forma.app.ui.Opens
import forma.app.update.Updater
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The app opened by a tap on a message notification (the audit's social group): the notification asks for the messages
 * once, on that start, and not again each time the activity is made anew.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AuditFixSocialTest {
    private val app get() = ApplicationProvider.getApplicationContext<FormaApp>()

    @Before fun setUp() {
        // The questionnaire still ahead: no screen takes the ask, so what the activity asked for stays to be read.
        runBlocking { app.repository.store.clear() }
        // No look for a newer build over the network while the activity is up.
        Updater.reset()
        runBlocking { Updater.check(fetch = { null }) }
        Opens.pending.value = null
    }

    @After fun tearDown() {
        Opens.pending.value = null
        Updater.reset()
    }

    private fun tap() = Intent(app, MainActivity::class.java).putExtra(MessageNotice.EXTRA_OPEN, MessageNotice.OPEN_MESSAGES)

    /** SHELL-4 and notification-open-repeats: a turn of the phone (a theme switch, a restore) does not ask again. */
    @Test fun aNotificationAsksForTheMessagesOnceNotOnEveryRecreation() {
        ActivityScenario.launch<MainActivity>(tap()).use { scenario ->
            assertEquals(MessageNotice.OPEN_MESSAGES, Opens.pending.value)
            // «Люди» took the ask, the person went back to «Сегодня»; then the activity is made again.
            Opens.pending.value = null
            scenario.recreate()
            assertNull(Opens.pending.value)
        }
    }

    /** notification-open-repeats: started again from the recents with the notification's old intent, no jump to «Люди». */
    @Test fun aStartFromTheRecentsDoesNotAskForTheMessages() {
        ActivityScenario.launch<MainActivity>(tap().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)).use {
            assertNull(Opens.pending.value)
        }
    }
}
