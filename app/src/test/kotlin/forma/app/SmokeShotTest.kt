package forma.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h873dp-xxhdpi")
class SmokeShotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun smoke() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF101010))) {
                    Text("Привет, Денис — 102,5 кг × 5", color = Color.White)
                }
            }
        }
        compose.onRoot().captureRoboImage("build/shots/smoke.png")
    }
}
