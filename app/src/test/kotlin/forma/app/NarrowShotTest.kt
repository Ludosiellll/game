package forma.app

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.run.RunSession
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.LocalSettings
import forma.app.ui.Navigator
import forma.app.ui.Screen
import forma.app.ui.components.StatTile
import forma.app.ui.screens.ProfileScreen
import forma.app.ui.screens.RunActiveScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.RunWorkout
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The narrowest common phone (360 dp): day chips, long times and stat tiles must fit without being cut. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi")
class NarrowShotTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    @Test fun narrowPhone() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "narrow-test")
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        runBlocking {
            store.clear()
            repo.completeOnboarding(
                Profile(goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.TRAINED, trainingDays = setOf(1, 3, 5, 7), bodyWeightKg = 80.0),
            )
        }
        // A free run past an hour: the biggest number the run screen shows.
        val tracker = RunTracker(RunWorkout(RunType.FREE, "Свободный бег", emptyList()))
        tracker.start(0)
        RunSession.tracker = tracker
        RunSession.gpsEnabled = false
        RunSession.publish(3_909_000)

        var look by mutableStateOf("carbon")
        var screen by mutableStateOf<@Composable () -> Unit>({})
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings(look = look)) {
                FormaTheme(Looks.byId(look)) {
                    Box(Modifier.fillMaxSize().background(F.c.bg)) { screen() }
                }
            }
        }
        val shots: List<Pair<String, @Composable () -> Unit>> = listOf(
            "profile" to { ProfileScreen() },
            "run" to { RunActiveScreen() },
            "tiles" to {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("5 км", "24:12", null, F.c.text, Modifier.weight(1f))
                        StatTile("10 км", "50:21", null, F.c.text, Modifier.weight(1f))
                        StatTile("Полумарафон", "1:51:30", null, F.c.text, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Время", "1:05:09", "100% плана", F.c.text, Modifier.weight(1f))
                        StatTile("Дистанция", "12,48 км", "", F.c.endurance, Modifier.weight(1f))
                        StatTile("Темп", "5:13", "средний", F.c.text, Modifier.weight(1f))
                    }
                }
            },
        )
        for (l in listOf("carbon", "poster")) {
            look = l
            for ((name, content) in shots) {
                screen = content
                settle()
                compose.onRoot().captureRoboImage("build/shots/narrow-$l-$name.png")
            }
        }
        RunSession.clear()
    }
}
