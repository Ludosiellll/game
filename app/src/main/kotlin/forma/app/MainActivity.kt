package forma.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import forma.app.data.Status
import forma.app.ui.AppRoot
import forma.app.ui.LocalCloud
import forma.app.ui.LocalRepo
import forma.app.ui.RestTimer
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as FormaApp
        val repo = app.repository
        val cloud = app.cloud
        // Catch up with the cloud on every launch; the periodic job covers the days the app stays closed.
        cloud.schedulePeriodic()
        cloud.syncSoon()
        // The rest between sets saved before the app was closed comes back; one of a session finished or deleted since,
        // or one that ended long ago, goes with its notification.
        lifecycleScope.launch { RestTimer.restore(this@MainActivity) { id -> repo.db.workouts().get(id)?.status == Status.ACTIVE } }
        opened(intent)
        // The new look, once for everyone (Denis's choice, 30.10): another can be chosen in «Оформление и звук».
        lifecycleScope.launch { repo.updateSettings { if (it.designVersion < 1) it.copy(look = forma.app.ui.theme.Looks.minimal.id, designVersion = 1) else it } }
        setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalCloud provides cloud) { AppRoot() }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        opened(intent)
    }

    /** Opened from a notification about messages: the app goes to them. */
    private fun opened(intent: android.content.Intent?) {
        intent?.getStringExtra(forma.app.sync.MessageNotice.EXTRA_OPEN)?.let { forma.app.ui.Opens.pending.value = it }
    }

    /**
     * Over the lock screen, and waking the screen when brought up, only while a run is on: the runner sees the clock and
     * presses "lap" without unlocking. Off otherwise — the rest of the app stays behind the lock.
     */
    fun showOverLock(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            val flags = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
    }
}
