package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.AppJson
import forma.app.data.FormaDatabase
import forma.app.data.RunSummary
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.RunManualScreen
import forma.app.ui.screens.RunSetupScreen
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
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunType
import forma.core.run.RunWorkout
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A run with a watch and no phone, entered by hand: from the screen before the start to the plan's next step. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class RunManualUiTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun repo(name: String): TrainingRepository {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store).also {
            runBlocking {
                it.completeOnboarding(
                    Profile(
                        name = "Денис", aim = Aim(LiftFocus.MUSCLE, RunFocus.SPEED, Emphasis.BALANCED), experience = Experience.INTERMEDIATE,
                        runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), bodyWeightKg = 80.0, birthYear = 2008, doubleDays = true,
                        runGoal = RunGoal(1500, 335), lapMeters = 230,
                    ),
                )
            }
        }
    }

    private fun show(repo: TrainingRepository, nav: Navigator, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav, LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { content() } }
            }
        }
        settle()
    }

    private fun type(label: String, text: String) {
        val field = compose.onAllNodesWithText(label, substring = true)[0]
        field.performScrollTo()
        // The field follows its label: the n-th editable after it.
        val edits = compose.onAllNodes(hasSetTextAction())
        val all = edits.fetchSemanticsNodes()
        val labelTop = field.fetchSemanticsNode().boundsInRoot.top
        val i = all.indexOfFirst { it.boundsInRoot.top > labelTop }
        edits[i].performTextReplacement(text)
        settle()
    }

    private fun runs(repo: TrainingRepository) = runBlocking { repo.db.workouts().allDone().filter { it.kind == "RUN" } }

    @Test fun theSetupScreenLeadsToTheManualEntry() {
        val repo = repo("ui-manual-setup")
        val easy = runBlocking { repo.runFor(SessionKind.RUN_EASY, repo.today()) }
        val nav = Navigator(Screen.Today)
        show(repo, nav) { RunSetupScreen(easy, SessionKind.RUN_EASY) }
        compose.onNodeWithText("Бегал без телефона", substring = true, ignoreCase = true).performScrollTo().performClick()
        settle()
        assertEquals(Screen.RunManual(easy), nav.current)
    }

    @Test fun goalPaceRepsWithAWatchMoveTheLadder() {
        val repo = repo("ui-manual-goal")
        val w = runBlocking {
            repo.store.setRunState(repo.store.runStateNow().copy(vdot = 46.0, runs = 6, goalStage = 2, lastTestDay = repo.today().toEpochDay() - 10))
            repo.runFor(SessionKind.RUN_QUALITY, repo.today())
        }
        assertEquals(RunType.GOAL_PACE, w.type)
        show(repo, Navigator(Screen.Today)) { RunManualScreen(w) }
        type("Средний круг", "52")
        compose.onNodeWithText("7").performScrollTo().performClick()
        settle()
        compose.onRoot().captureRoboImage("build/shots/carbon-runmanual.png")
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()
        settle()
        assertTrue(compose.onAllNodesWithText("Сохранено", ignoreCase = true).fetchSemanticsNodes().isNotEmpty())
        val saved = runs(repo).single()
        val summary = AppJson.decodeFromString(RunSummary.serializer(), saved.summaryJson!!)
        assertTrue(summary.manual)
        assertTrue(summary.outcomes.any { it is RunOutcome.StageUp }, summary.outcomes.toString())
        assertEquals(3, runBlocking { repo.store.runStateNow().goalStage })
        compose.onRoot().captureRoboImage("build/shots/carbon-runmanual-saved.png")
    }

    @Test fun anEasyRunYesterdayNeedsItsTime() {
        val repo = repo("ui-manual-easy")
        val easy = runBlocking { repo.runFor(SessionKind.RUN_EASY, repo.today()) }
        show(repo, Navigator(Screen.Today)) { RunManualScreen(easy) }
        compose.onNodeWithText("Вчера").performClick()
        compose.onNodeWithText("4").performScrollTo().performClick()
        settle()
        // No time yet: nothing is saved.
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()
        settle()
        assertTrue(runs(repo).isEmpty())
        type("Время пробежки", "32:10")
        type("Дистанция", "5,4")
        assertTrue(compose.onAllNodesWithText("Средний темп 5:57", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()
        settle()
        val saved = runs(repo).single()
        assertEquals(repo.today().minusDays(1).toEpochDay(), saved.epochDay)
        assertEquals(32 * 60 + 10, saved.durationSec)
        val run = runBlocking { repo.db.runs().get(saved.id)!! }
        assertEquals(5400.0, run.distanceM!!, 1e-6)
    }

    @Test fun aTrialOnTheWatch() {
        val repo = repo("ui-manual-trial")
        val w: RunWorkout = runBlocking { RunPlan.goalTest(repo.store.runStateNow().copy(vdot = 46.0), RunGoal(1500, 335), 230) }
        show(repo, Navigator(Screen.Today)) { RunManualScreen(w) }
        type("Время забега", "5:38")
        compose.onNodeWithText("9").performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Сохранить").performScrollTo().performClick()
        settle()
        val summary = AppJson.decodeFromString(RunSummary.serializer(), runs(repo).single().summaryJson!!)
        assertTrue(summary.outcomes.contains(RunOutcome.TestTime(1495, 338)), summary.outcomes.toString())
    }
}
