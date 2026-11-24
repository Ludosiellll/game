package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.ProgramWizardScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.Split
import forma.core.plan.ProgramGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Audit fixes, group J, on the screen: «Собрать программу заново» saves the program its last step showed. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h1700dp-mdpi")
class AuditFixRebuildUiTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() = repeat(5) {
        Thread.sleep(80)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    /** SHELL-1: the wizard on a friend's program, a split picked: the friend's days in that split, not the own program put aside. */
    @Test fun theWizardOnAFriendsProgramSavesWhatItShowed() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "ui-rebuild-copy")
        runBlocking { store.clear() }
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        val gym = Aim(LiftFocus.MUSCLE, RunFocus.NONE, Emphasis.GYM)
        runBlocking {
            // His own program: the gym on four days, upper and lower twice.
            repo.completeOnboarding(Profile(aim = gym, experience = Experience.INTERMEDIATE, trainingDays = setOf(1, 2, 4, 5), bodyWeightKg = 80.0))
            // The friend's: three days.
            val friend = ProgramGenerator.generate(Profile(aim = gym, experience = Experience.INTERMEDIATE, trainingDays = setOf(2, 4, 6)), LocalDate.now())
            assertTrue(repo.copyProgram(friend, "Денис"))
        }
        val nav = Navigator(Screen.Plan).also { it.push(Screen.ProgramWizard) }
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav, LocalSettings provides Settings(look = Looks.minimalDark.id)) {
                FormaTheme(Looks.minimalDark) { Box(Modifier.fillMaxSize().background(F.c.bg)) { ProgramWizardScreen() } }
            }
        }
        settle()
        val next = hasText("Дальше", ignoreCase = true) and hasClickAction()
        compose.onNode(hasText("Собрать программу", ignoreCase = true) and hasClickAction()).performClick()
        settle()
        // What he does, what the gym is for, his level, the days: as they are (the friend's days).
        repeat(4) {
            compose.onNode(next).performClick()
            settle()
        }
        compose.onNodeWithText("Тяни / толкай / ноги").performClick()
        settle()
        compose.onNode(next).performClick()
        settle()
        // The accents as they are; then the answers gathered and the program shown.
        compose.onNode(next).performClick()
        val save = hasText("Сохранить программу", ignoreCase = true) and hasClickAction()
        compose.waitUntil(10_000) { settle(); compose.onAllNodes(save).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(save).performClick()
        // The profile is saved off the main thread.
        compose.waitUntil(10_000) { settle(); runBlocking { store.programNow()?.copiedFrom } == null }
        val profile = runBlocking { store.profileNow()!! }
        val program = runBlocking { store.programNow()!! }
        assertEquals(Split.PPL, profile.split)
        assertEquals(setOf(2, 4, 6), profile.trainingDays, "the friend's days, as the wizard showed them")
        assertEquals(listOf("push_a", "pull_a", "legs_a"), program.templates.map { it.id })
    }
}
