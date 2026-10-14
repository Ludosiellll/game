package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.OnboardingScreen
import forma.app.ui.screens.ProfileDraft
import forma.app.ui.screens.lapTenths
import forma.app.ui.screens.parseClock
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
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The questionnaire and the profile after the review of 2026-10-14: each step checks only what it shows and what will
 * be saved, says why "Дальше" is grey, and a week laid out by hand needs a day for each side of the aim.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class OnboardingFixesTest {
    @get:Rule val compose = createComposeRule()

    /** Denis: runs Mon/Thu/Sat, lifts Tue/Thu/Sat, 1500 m in 5:30 on a 230 m track. */
    private val denis = Profile(
        name = "Денис", birthYear = 2008, bodyWeightKg = 72.0, experience = Experience.INTERMEDIATE, runLevel = RunLevel.TRAINED,
        aim = Aim(LiftFocus.MUSCLE, RunFocus.SPEED, Emphasis.BALANCED), runGoal = RunGoal(1500, 330), lapMeters = 230,
        gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6), trainingDays = setOf(1, 2, 4, 6),
    )

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    @Test fun aClockIsMinutesAndSecondsBelowSixty() {
        assertEquals(330.0, parseClock("5:30"))
        assertEquals(3900.0, parseClock("1:05:00"))
        assertNull(parseClock("5:75"))
        assertNull(parseClock("1:65:00"))
        assertNull(parseClock("-1:30"))
        assertNull(parseClock("5.30"))
    }

    /** A race typed, then the running level set to run/walk: the hidden race is neither checked nor saved. */
    @Test fun aHiddenRaceIsNeitherCheckedNorSaved() {
        val d = ProfileDraft(denis)
        d.raceKm = 1.5
        d.raceTime = "12.10"
        assertTrue(d.raceTimeBad)
        assertTrue(!d.fieldsOk)
        d.runLevel = RunLevel.NONE
        assertTrue(!d.raceTimeBad)
        assertTrue(d.fieldsOk, d.problem.toString())
        assertNull(d.toProfile(denis).raceMeters)
        // Running taken out of the aim: the stadium with a lap typed wrong is hidden too, and not saved.
        val e = ProfileDraft(denis)
        e.lapMeters = ""
        assertTrue(e.lapBad)
        e.run = RunFocus.NONE
        assertTrue(!e.lapBad && e.fieldsOk, e.problem.toString())
        assertNull(e.toProfile(denis).lapMeters)
    }

    /** 800 m, 1 km and 1,5 km are races too (Denis's own distance). */
    @Test fun middleDistanceRacesCount() {
        assertEquals(listOf("800 м", "1 км", "1,5 км", "3 км", "5 км", "10 км", "21,1 км"), ProfileDraft.RACES.map { it.second })
        val d = ProfileDraft(denis)
        d.raceKm = 0.8
        d.raceTime = "2:05"
        assertEquals(800.0 to 125.0, d.race)
        assertEquals(800.0, d.toProfile(denis).raceMeters)
    }

    /** A week laid out by hand with running on and no running day: nothing is saved, and it says what to do. */
    @Test fun anOwnWeekNeedsADayForEachSide() {
        val d = ProfileDraft(denis)
        assertTrue(d.fieldsOk, d.problem.toString())
        d.runDays = emptySet()
        assertEquals("Отметь хотя бы один день бега или выбери «Без бега».", d.weekProblem)
        assertTrue(!d.fieldsOk)
        d.run = RunFocus.NONE
        assertNull(d.weekProblem)
        val g = ProfileDraft(denis)
        g.gymDays = emptySet()
        assertEquals("Отметь хотя бы один день зала или выбери «Без зала».", g.weekProblem)
    }

    /** Birth year and weight are examples in empty fields, not 1995 and 75 saved silently; nonsense is called out. */
    @Test fun birthYearAndWeightAreTypedNotPrefilled() {
        val d = ProfileDraft(Profile(), blankBody = true)
        assertEquals("", d.birthYear)
        assertEquals("", d.weight)
        assertEquals("Впиши год рождения.", d.aboutProblem)
        d.birthYear = "1880"
        assertTrue(d.birthYearBad)
        d.birthYear = "2008"
        assertEquals("Впиши вес: от него стартовые веса в зале.", d.aboutProblem)
        d.weight = "7"
        assertTrue(d.weightBad)
        d.weight = "72,5"
        d.restingHr = "600"
        assertTrue(d.restingHrBad)
        d.restingHr = ""
        assertNull(d.aboutProblem)
        val p = d.toProfile(Profile())
        assertEquals(2008, p.birthYear)
        assertEquals(72.5, p.bodyWeightKg)
    }

    @Test fun lapTimesKeepTheirTenths() {
        assertEquals("50,6 с", lapTenths(50.6))
        assertEquals("1:28,4", lapTenths(88.4))
    }

    /**
     * The dead end of the review: a bad race time on the second step, back to the first — "Дальше" there is grey
     * without a word. Now each step checks its own fields, and a grey button says why.
     */
    @Test fun backNeverLeadsToAGreyButtonWithoutAReason() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "ui-onboarding-fixes")
        runBlocking { store.clear() }
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store)
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Onboarding), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { OnboardingScreen() } }
            }
        }
        settle()
        val next = hasText("Дальше") and hasClickAction()
        compose.onNode(next).performScrollTo().performClick()
        settle()
        // Step 2: a recent 1500 m with the dot instead of the colon.
        compose.onNodeWithText("1,5 км").performScrollTo().performClick()
        settle()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("12.10")
        settle()
        compose.onNode(next).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Время забега не похоже на время", substring = true).performScrollTo().assertIsDisplayed()
        // Back: the first step has nothing wrong of its own.
        compose.onNode(hasText("Назад", ignoreCase = true) and hasClickAction()).performScrollTo().performClick()
        settle()
        compose.onNode(next).performScrollTo().assertIsEnabled()
        // Forward again and the time fixed: on to the days.
        compose.onNode(next).performClick()
        settle()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("5:41")
        settle()
        compose.onNode(next).performScrollTo().assertIsEnabled().performClick()
        settle()
        compose.onNode(next).performScrollTo().assertIsEnabled().performClick()
        settle()
        // About oneself: empty birth year and weight, said why the button waits.
        compose.onNode(next).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Впиши год рождения.").performScrollTo().assertIsDisplayed()
        // The fields in order: birth year, weight, height, resting pulse — empty, with examples shown.
        compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement("2008")
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextReplacement("72")
        settle()
        compose.onNode(next).performScrollTo().assertIsEnabled().performClick()
        settle()
        // The summary: one's own gym and running days can be set right here.
        compose.onNodeWithText("Поменять дни зала и бега", ignoreCase = true).performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Отметь, в какие дни зал и в какие бег", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Начать") and hasClickAction()).performScrollTo().performClick()
        settle()
        val p = assertNotNull(runBlocking { store.profileNow() })
        assertEquals(2008, p.birthYear)
        assertEquals(72.0, p.bodyWeightKg)
        assertEquals(1500.0, p.raceMeters)
        assertEquals(341.0, p.raceSeconds)
    }
}
