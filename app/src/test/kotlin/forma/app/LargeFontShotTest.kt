package forma.app

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
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
import forma.app.ui.screens.OnboardingScreen
import forma.app.ui.screens.PlanScreen
import forma.app.ui.screens.ProgressScreen
import forma.app.ui.screens.RunActiveScreen
import forma.app.ui.screens.RunSetupScreen
import forma.app.ui.screens.TodayScreen
import forma.app.ui.screens.WorkoutScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Experience
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.RunTracker
import forma.core.run.SegmentKind
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The main screens on a 360 dp phone (many Samsung Galaxy) at the normal font and with the system font made larger:
 * numbers must not be cut ("47,5" read as "47,"), labels must not break mid-word, the lap button must stay reachable.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi")
class LargeFontShotTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    @Test fun largeFont() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "shots-large")
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        val profile = Profile(
            name = "Денис", aim = forma.core.model.Aim(forma.core.model.LiftFocus.MUSCLE, forma.core.model.RunFocus.SPEED, forma.core.model.Emphasis.BALANCED),
            experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), bodyWeightKg = 80.0,
            birthYear = 1996, doubleDays = true, runGoal = forma.core.model.RunGoal(1500, 335), lapMeters = 230,
        )
        val (pace, lift) = runBlocking {
            store.clear()
            repo.completeOnboarding(profile)
            store.setRunState(store.runStateNow().copy(vdot = 43.5, runs = 4, goalStage = 2, qualityCount = 2, lastTestDay = repo.today().toEpochDay() - 10))
            val id = repo.startLift(store.programNow()!!.templates.first().id)
            val plan = repo.planOf(repo.db.workouts().get(id)!!)!!
            val first = plan.items.first()
            repeat(2) { repo.logSet(id, first, first.prescription.weight ?: 60.0, first.prescription.reps, 2) }
            repo.runFor(SessionKind.RUN_QUALITY, repo.today()) to id
        }
        val tracker = RunTracker(pace)
        tracker.start(0)
        val warm = 12 * 60 * 1000L
        tracker.press(warm)
        val work = pace.segments.first { it.kind == SegmentKind.WORK }.seconds
        val rest = pace.segments.first { it.kind == SegmentKind.RECOVERY }.seconds
        tracker.lap(warm + (work + 1) * 1000L)
        val rep2 = warm + (work + rest) * 1000L
        tracker.lap(rep2 + (work - 3) * 1000L)
        val now = rep2 + (work - 3 + rest + 20) * 1000L
        RunSession.tracker = tracker
        RunSession.gpsEnabled = false
        RunSession.publish(now)

        var scale by mutableStateOf(1.3f)
        var screen by mutableStateOf<@Composable () -> Unit>({})
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(d.density, scale),
                LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings(),
            ) {
                FormaTheme(Looks.byId("carbon")) {
                    Box(Modifier.fillMaxSize().background(F.c.bg)) { screen() }
                }
            }
        }
        val shots: List<Pair<String, @Composable () -> Unit>> = listOf(
            "today" to { TodayScreen() },
            "workout" to { WorkoutScreen(lift) },
            "plan" to { PlanScreen() },
            "runsetup" to { RunSetupScreen(pace, SessionKind.RUN_QUALITY) },
            "run" to { RunActiveScreen() },
            "progress" to { ProgressScreen(initialTab = 1) },
            "onboarding" to { OnboardingScreen() },
            "more" to { forma.app.ui.screens.MoreScreen() },
        )
        for (s in listOf(1.0f, 1.15f, 1.3f, 1.5f)) {
            scale = s
            for ((name, content) in shots) {
                screen = {}
                settle()
                screen = content
                settle()
                compose.onRoot().captureRoboImage("build/shots/large-${(s * 100).toInt()}-$name.png")
            }
        }
        RunSession.clear()
    }
}
