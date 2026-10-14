package forma.app.data

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.LocalSettings
import forma.app.ui.Navigator
import forma.app.ui.Screen
import forma.app.ui.screens.TodayScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.RunResult
import forma.core.run.RunType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * Denis's Monday: the gym Tue/Thu/Sat, running on Monday too, a trial due. A free run on Monday takes Monday's run; the
 * trial goes to the next hard day, Thursday, and only there — Tuesday is the gym and an easy run.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi")
class TrialDayTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private val monday = LocalDate.of(2026, 10, 12)
    private var clock = monday.atTime(19, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "trial-day")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        val denis = Profile(
            name = "Денис", aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
            runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6), bodyWeightKg = 80.0, birthYear = 2008, doubleDays = true,
            runGoal = RunGoal(1500, 330), lapMeters = 230, gymDays = setOf(2, 4, 6), runDays = setOf(1, 2, 4, 6),
        )
        repo.completeOnboarding(denis)
        // Two runs behind him: the first trial is due.
        repo.store.setRunState(repo.store.runStateNow().copy(runs = 2, walkStage = -1))
    }

    @After fun tearDown() = db.close()

    @Test fun aFreeRunOnMondayMovesTheTrialToThursday() = runBlocking {
        val week = repo.store.programNow()!!.week
        assertEquals(listOf(SessionKind.RUN_QUALITY), week.on(1).map { it.kind })
        // Monday is two days after Saturday's legs: the trial goes to Thursday (fresh legs), and Monday is race pace.
        val thursday = monday.plusDays(3)
        assertEquals(thursday, repo.trialDay(monday))
        assertEquals(RunType.GOAL_PACE, repo.runFor(SessionKind.RUN_QUALITY, monday).type)
        // A free run on Monday changes nothing about it.
        repo.saveRun(repo.freeRun(230), RunResult(1.0, 7, 1500, distanceM = 4600.0), clock - 1_600_000, emptyList(), monday)
        assertEquals(thursday, repo.trialDay(monday))
        // The week shows the trial once: Thursday's; a hard day after it shows what comes after the trial.
        assertEquals(RunType.TEST, repo.runFor(SessionKind.RUN_QUALITY, thursday, trial = thursday == repo.trialDay(monday)).type)
        assertEquals(RunType.GOAL_PACE, repo.runFor(SessionKind.RUN_QUALITY, monday.plusDays(7), trial = false).type)
        // Tuesday, tomorrow: the gym and an easy run.
        assertEquals(setOf(SessionKind.LIFT, SessionKind.RUN_EASY), week.on(2).map { it.kind }.toSet())
    }

    /** Today after the free run: "Сделано: Свободный бег" and where the trial went — not "Контрольный забег — сделано". */
    @Test fun todaySaysWhatWasDone() {
        runBlocking { repo.saveRun(repo.freeRun(230), RunResult(1.0, 7, 1500, distanceM = 4600.0), clock - 1_600_000, emptyList(), monday) }
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { TodayScreen() } }
            }
        }
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
        compose.onNodeWithText("Сделано: Свободный бег").performScrollTo()
        compose.onNodeWithText("в следующий тяжёлый день", substring = true).performScrollTo()
        compose.onRoot().captureRoboImage("build/shots/carbon-today-trialmoved.png")
    }
}
