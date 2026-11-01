package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.components.FitTitle
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
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Denis, 1 November: «обрезаются названия!» — the exercise title in capitals, beside «Заменить» and «Убрать», broke
 * inside words: «ПРИСЕД / АНИЯ / СО / ШТАНГ / ОЙ». Titles now take the whole width and shrink until the longest word fits.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi")
class TitleFitUiTest {
    @get:Rule val compose = createComposeRule()

    @After fun stopRest() = RestTimer.stop()

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    /** Line ends that fall between two letters: a word cut in two. */
    private fun cutWords(layout: TextLayoutResult): List<String> {
        val text = layout.layoutInput.text.text
        return (0 until layout.lineCount - 1).mapNotNull { i ->
            val end = layout.getLineEnd(i)
            if (end in 1 until text.length && text[end - 1].isLetter() && text[end].isLetter()) text.substring(layout.getLineStart(i), end) else null
        }
    }

    private val big = TextStyle(fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black)

    @Test fun aLongWordShrinksInsteadOfBreaking() {
        val name = "КОНЦЕНТРИРОВАННЫЕ СГИБАНИЯ"
        compose.setContent {
            Column {
                // The old way, as a check that a cut is caught: plain text in a narrow column.
                Box(Modifier.width(200.dp)) { Text(name, style = big) }
                Box(Modifier.width(200.dp)) { FitTitle(name, style = big) }
            }
        }
        settle()
        val plain = layoutOf(compose.onAllNodesWithText(name, useUnmergedTree = true)[0])
        assertTrue(cutWords(plain).isNotEmpty(), "the check finds the old cut")
        val fit = layoutOf(compose.onAllNodesWithText(name, useUnmergedTree = true)[1])
        assertEquals(emptyList(), cutWords(fit), "no word cut in two")
        assertTrue(fit.layoutInput.style.fontSize.value < 34f, "the font shrank to fit")
    }

    @Test fun aShortTitleKeepsItsSize() {
        compose.setContent { Box(Modifier.width(300.dp)) { FitTitle("ЖИМ ЛЁЖА", style = big) } }
        settle()
        assertEquals(34f, layoutOf(compose.onNodeWithText("ЖИМ ЛЁЖА")).layoutInput.style.fontSize.value)
    }

    private fun repo(name: String): TrainingRepository {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
    }

    private fun workoutWith(exerciseId: String, shot: String) {
        val repo = repo("title-$exerciseId")
        val id = runBlocking {
            repo.completeOnboarding(
                Profile(
                    name = "Денис", goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE,
                    trainingDays = setOf(1, 3, 5), bodyWeightKg = 80.0, birthYear = 1995,
                ),
            )
            val id = repo.startLift(repo.store.programNow()!!.templates.first().id)
            assertTrue(repo.swapExercise(id, 0, exerciseId, forever = false), "swapped to $exerciseId")
            id
        }
        val name = forma.core.model.Catalog[exerciseId].name.uppercase()
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.minimalDark) { Box(Modifier.fillMaxSize().background(F.c.bg)) { WorkoutScreen(id) } }
            }
        }
        settle()
        // The first-time hint closed, as on Denis's phone: the header is in view.
        compose.onAllNodesWithText("Понятно", ignoreCase = true).fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let {
            compose.onAllNodesWithText("Понятно", ignoreCase = true)[0].performClick()
            settle()
        }
        assertEquals(emptyList(), cutWords(layoutOf(compose.onNodeWithText(name))), "$name: no word cut in two")
        compose.onNodeWithText("Заменить", ignoreCase = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/shots/title-$shot.png")
    }

    @Test fun squatTitleIsWhole() = workoutWith("squat", "squat")

    @Test fun longestNameIsWhole() = workoutWith("concentration_curl", "concentration")
}
