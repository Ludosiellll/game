package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.run.RunSession
import forma.app.ui.screens.PlanScreen
import forma.app.ui.screens.ProfileScreen
import forma.app.ui.screens.RunDoneScreen
import forma.app.ui.screens.WorkoutScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.RunPlan
import forma.core.run.RunTracker
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Screens after the review of 2026-10-10: what the testers found on them, fixed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ReviewFixesUiTest {
    @get:Rule val compose = createComposeRule()
    private var clock = System.currentTimeMillis()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun texts(substring: String): List<String> =
        compose.onAllNodesWithText(substring, substring = true).fetchSemanticsNodes()
            .map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: n.config.getOrNull(SemanticsProperties.EditableText)?.text ?: "" }

    private fun repo(name: String): TrainingRepository {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now = { clock })
    }

    private val profile = Profile(
        name = "Денис", goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE,
        trainingDays = setOf(1, 3, 5), bodyWeightKg = 80.0, birthYear = 1995,
    )

    private fun show(repo: TrainingRepository, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { content() } }
            }
        }
        settle()
    }

    @Test fun aSwappedExerciseGetsItsOwnWeightNotTheOldOnes() {
        val repo = repo("ui-fix-swap")
        val id = runBlocking {
            repo.completeOnboarding(profile)
            repo.startLift(repo.store.programNow()!!.templates.first().id)
        }
        var shown by mutableStateOf(true)
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) {
                    Box(Modifier.fillMaxSize().background(F.c.bg)) { if (shown) holder.SaveableStateProvider("workout") { WorkoutScreen(id) } }
                }
            }
        }
        settle()
        val first = runBlocking { repo.planOf(repo.db.workouts().get(id)!!)!!.items.first() }
        // The library on top, a swap to an exercise with a very different start, back to the session.
        shown = false
        settle()
        val target = repo.alternativesFor(first.exerciseId).maxBy { a ->
            kotlin.math.abs((forma.core.strength.Progression.startHint(a, 80.0, false, 1.0) ?: 0.0) - (first.prescription.startHint ?: 0.0))
        }.id
        runBlocking { repo.swapExercise(id, first.position, target, forever = false) }
        shown = true
        settle()
        val now = runBlocking { repo.planOf(repo.db.workouts().get(id)!!)!!.items.first { it.position == first.position } }
        val expected = "Начни с ${Texts.weight(Catalog[target], now.prescription.weight ?: now.prescription.startHint ?: 0.0)}"
        assertTrue(texts("Начни с").any { expected in it }, texts("Начни с").toString())
    }

    @Test fun profileEditsSurviveRecreation() {
        val repo = repo("ui-fix-profile")
        runBlocking { repo.completeOnboarding(profile) }
        val tester = StateRestorationTester(compose)
        tester.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { ProfileScreen() } }
            }
        }
        settle()
        compose.onNode(hasSetTextAction() and hasText("1995")).performScrollTo().performTextReplacement("2008")
        settle()
        tester.emulateSavedInstanceStateRestore()
        settle()
        assertEquals(1, compose.onAllNodes(hasSetTextAction() and hasText("2008")).fetchSemanticsNodes().size)
    }

    @Test fun aTrialAsksForNoTwelveMinutesAndTheEffortIsNotPicked() {
        val repo = repo("ui-fix-trial")
        val goal = RunGoal(1500, 335)
        val trial = runBlocking {
            repo.completeOnboarding(profile.copy(runGoal = goal, lapMeters = 230, aim = Aim(LiftFocus.MUSCLE, RunFocus.SPEED)))
            RunPlan.goalTest(repo.store.runStateNow(), goal, 230)
        }
        val tt = RunTracker(trial)
        tt.start(0)
        tt.press(12 * 60 * 1000L)
        var at = 12 * 60 * 1000L + 27_000L
        tt.lap(at)
        listOf(53, 54, 54, 55, 56, 52).forEach { at += it * 1000L; tt.lap(at) }
        tt.finish(at + 5 * 60_000)
        RunSession.tracker = tt
        RunSession.gpsEnabled = false
        RunSession.publish(at + 5 * 60_000)
        show(repo) { RunDoneScreen(0) }
        val twelve = texts("за 12 минут")
        // No effort chosen for the runner: saving waits for it.
        val waits = texts("Сначала оценка")
        RunSession.clear()
        assertTrue(twelve.isEmpty(), twelve.toString())
        assertTrue(waits.isNotEmpty())
    }

    @Test fun theSavedRunSummarySurvivesRecreation() {
        val repo = repo("ui-fix-rundone")
        val w = runBlocking {
            repo.completeOnboarding(profile)
            repo.runFor(SessionKind.RUN_EASY)
        }
        val t = RunTracker(w)
        t.start(0)
        t.finish(w.totalSeconds * 1000L)
        RunSession.tracker = t
        RunSession.gpsEnabled = false
        RunSession.publish(w.totalSeconds * 1000L)
        val tester = StateRestorationTester(compose)
        tester.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { RunDoneScreen(0) } }
            }
        }
        settle()
        compose.onAllNodesWithText("4")[0].performScrollTo().performClick()
        settle()
        compose.onAllNodesWithText("Сохранить")[0].performScrollTo().performClick()
        settle()
        tester.emulateSavedInstanceStateRestore()
        settle()
        val gone = texts("уже сохранена или удалена")
        RunSession.clear()
        assertTrue(gone.isEmpty(), gone.toString())
        assertTrue(texts("Сохранено").isNotEmpty() || texts("СОХРАНЕНО").isNotEmpty())
    }

    @Test fun thePlanDialogOpenedEarlyKeepsTheSets() {
        val repo = repo("ui-fix-plan-dialog")
        val t = runBlocking {
            repo.completeOnboarding(profile.copy(experience = Experience.INTERMEDIATE))
            val t = repo.store.programNow()!!.templates.first()
            repo.setPlanSets(t.slots.first().exerciseId, t.slots.first().sets + 1, t.slots.first().sets)
            t
        }
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { PlanScreen() } }
            }
        }
        val name = Catalog[t.slots.first().exerciseId].name
        compose.onAllNodesWithText(name)[0].performScrollTo().performClick()
        settle()
        compose.onAllNodesWithText("Сохранить")[0].performClick()
        settle()
        assertEquals(t.slots.first().sets + 1, runBlocking { repo.store.settingsNow().sets[t.slots.first().exerciseId] })
    }
}
