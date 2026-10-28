package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.SlotStateEntity
import forma.app.data.TrainingRepository
import forma.app.ui.screens.WorkoutScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** Help on pull-ups is a band or the gravitron: the gym screen says what to do without one, and only when there is help. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi")
class AssistedHelpUiTest {
    @get:Rule val compose = createComposeRule()

    @After fun stopRest() = RestTimer.stop()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun texts(substring: String): List<String> =
        compose.onAllNodesWithText(substring, substring = true).fetchSemanticsNodes()
            .map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "" }

    /** The first exercise of the day made pull-ups, with [weight] kg stored for them. */
    private fun pullUpsWith(name: String, weight: Double): Pair<TrainingRepository, Long> = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, name)
        store.clear()
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        repo.completeOnboarding(
            Profile(name = "Егор", goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 3, 5), bodyWeightKg = 55.0, birthYear = 2007),
        )
        val t = repo.store.programNow()!!.templates.first()
        val first = t.slots.first()
        repo.updateSettings { it.copy(swaps = mapOf(first.exerciseId to "pullup")) }
        val item = repo.liftPlan(t.id, java.time.LocalDate.now()).items.first()
        repo.db.state().upsertSlot(SlotStateEntity(item.slot.key, "pullup", weight, item.slot.repMin, 0, 0, 3, System.currentTimeMillis(), false, 0, null, null))
        repo to repo.startLift(t.id)
    }

    private fun show(repo: TrainingRepository, id: Long) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { WorkoutScreen(id) } }
            }
        }
        settle()
    }

    @Test fun helpSaysWhatToDoWithoutABand() {
        val (repo, id) = pullUpsWith("help-band", -10.0)
        show(repo, id)
        assertTrue(texts("помощь 10 кг").isNotEmpty(), "the plan gives help: ${texts("помощь")}")
        assertTrue(texts("гравитрон или резинка").isNotEmpty(), "what help is and what to do without it")
        assertTrue(texts("тяга верхнего блока").isNotEmpty())
    }

    @Test fun noWordOfHelpWithTheBodyAlone() {
        val (repo, id) = pullUpsWith("help-none", 0.0)
        show(repo, id)
        assertTrue(texts("свой вес").isNotEmpty())
        assertTrue(texts("гравитрон").isEmpty())
    }
}
