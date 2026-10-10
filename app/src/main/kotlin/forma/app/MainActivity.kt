package forma.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import forma.app.ui.AppRoot
import forma.app.ui.LocalCloud
import forma.app.ui.LocalRepo

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
        setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalCloud provides cloud) { AppRoot() }
        }
    }
}
