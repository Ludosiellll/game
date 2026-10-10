package forma.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import forma.app.ui.AppRoot
import forma.app.ui.LocalRepo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = (application as FormaApp).repository
        setContent {
            CompositionLocalProvider(LocalRepo provides repo) { AppRoot() }
        }
    }
}
