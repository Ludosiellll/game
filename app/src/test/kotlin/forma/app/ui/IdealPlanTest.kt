package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.AppJson
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.LiftSummary
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.SlotReport
import forma.app.data.Status
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.app.ui.screens.IdealPlanScreen
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
import forma.core.strength.HoldReason
import forma.core.strength.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Идеальный план» on Denis's plan: what it proposes, that looking changes nothing, that only what he ticks is taken. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class IdealPlanTest {
    @get:Rule val compose = createComposeRule()
    private val monday = LocalDate.of(2026, 10, 12)
    private var clock = monday.plusDays(2).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val denis = Profile(
        name = "Денис", aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
        runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6), runGoal = RunGoal(1500, 330), lapMeters = 230, doubleDays = true,
        gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
    )

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private lateinit var db: FormaDatabase

    /** Denis as he is: his own swap of the Bulgarian split squat for the leg extension, made before swaps had their own numbers. */
    private fun repo(name: String): TrainingRepository = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, name)
        store.clear()
        TrainingRepository(db, store, now = { clock }).also {
            it.completeOnboarding(denis)
            store.editSettings { s -> s.copy(swaps = s.swaps + ("bulgarian" to "leg_ext")) }
        }
    }

    @Test fun lookingChangesNothingAndOnlyTheTickedChangesAreTaken() = runBlocking<Unit> {
        val repo = repo("ideal-denis")
        val before = repo.store.settingsNow()
        val program = repo.store.programNow()
        val a = repo.planAdvice()!!
        assertEquals(before, repo.store.settingsNow())
        assertEquals(program, repo.store.programNow())
        val ids = a.changes.map { it.id }
        assertTrue("oneleg:bulgarian" in ids && "add:nordic" in ids && "add:seated_calf" in ids, ids.toString())
        val chest = a.changes.first { it.id.startsWith("vol:CHEST") }

        repo.applyPlanAdvice(listOf(a.changes.first { it.id == "oneleg:bulgarian" }.pick("split_squat"), a.changes.first { it.id == "add:nordic" }, chest))
        val s = repo.store.settingsNow()
        assertEquals("split_squat", s.swaps["bulgarian"])
        assertEquals(listOf("nordic_curl"), s.added["lower_a"])
        assertTrue("seated_calf" !in s.added["lower_a"].orEmpty(), "not ticked, not taken")
        val legs = repo.liftPlan("lower_a", monday.plusDays(5))
        val nordic = legs.items.first { it.exerciseId == "nordic_curl" }.slot
        assertEquals(Triple(2, 4, 8), Triple(nordic.sets, nordic.repMin, nordic.repMax))
        // The one-leg lift in the Bulgarian's place keeps the plan's numbers.
        assertEquals(3, legs.items.first { it.exerciseId == "split_squat" }.slot.sets)
        assertEquals(chest.setsTo, repo.liftPlan(chest.templateId, monday.plusDays(1)).items.first { it.exerciseId == chest.planId }.slot.sets)
        // Taken: no longer proposed; the rest still is.
        val again = repo.planAdvice()!!.changes.map { it.id }
        assertTrue("oneleg:bulgarian" !in again && "add:nordic" !in again && "add:seated_calf" in again, again.toString())
        // Bringing his own back is one choice away too: the plan's Bulgarian split squat.
        repo.applyPlanAdvice(emptyList())
        assertEquals(s, repo.store.settingsNow())
    }

    /** The bench without a new best for six weeks: another variation of it, first on the list. */
    @Test fun aLiftWithoutANewBestForWeeksIsSeenFromTheHistory() = runBlocking<Unit> {
        val repo = repo("ideal-stall")
        val program = repo.store.programNow()!!
        val t = program.templates.first { it.id == "upper_a" }
        for (week in 0..6) {
            val day = monday.minusWeeks(6L - week).plusDays(1)
            val at = day.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val e1rm = if (week == 0) 100.0 else 99.5
            val summary = LiftSummary(listOf(SlotReport("bench", "Жим штанги лёжа", Outcome.Hold(80.0, 6, HoldReason.NEAR_MISS), e1rm, false)), 0.0, 3, 60, 300.0)
            db.workouts().insert(
                WorkoutEntity(epochDay = day.toEpochDay(), kind = Kind.LIFT, templateId = t.id, title = t.title, startedAt = at, finishedAt = at,
                    status = Status.DONE, rpe = 8, durationSec = 3600, load = 300.0,
                    summaryJson = AppJson.encodeToString(LiftSummary.serializer(), summary)),
            )
        }
        val first = repo.planAdvice()!!.changes.first()
        assertEquals("stall:upper_a:bench", first.id)
        assertEquals("pause_bench", first.to)
    }

    @Test fun theScreenShowsWhatAndWhyAndTakesOnlyTheTickedChange() {
        val repo = repo("ideal-ui")
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.IdealPlan), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { IdealPlanScreen() } }
            }
        }
        compose.waitUntil(10_000) { settle(); compose.onAllNodes(hasText("Что можно улучшить", ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Разгибание ног → Болгарские сплит-приседания (как было в плане)").assertExists()
        compose.onNodeWithText("Бег — череда приземлений", substring = true).assertExists()
        // Nothing ticked: nothing to take.
        compose.onNodeWithText("Отметь, что взять", ignoreCase = true).performScrollTo().assertExists()
        compose.onNodeWithText("Разгибание ног → Болгарские сплит-приседания (как было в плане)").performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Сплит-приседания").performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Разгибание ног → Сплит-приседания").assertExists()
        compose.onNodeWithText("Принять выбранные (1)", ignoreCase = true).performScrollTo().performClick()
        settle()
        val s = runBlocking { repo.store.settingsNow() }
        assertEquals("split_squat", s.swaps["bulgarian"])
        assertTrue(s.added.isEmpty(), s.added.toString())
        compose.waitUntil(10_000) { settle(); compose.onAllNodes(hasText("Принято: 1", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Pictures of the screen for the eye (build/shots): the changes, a ticked one with its options, the week, the sets table. */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun shots() {
        val repo = repo("ideal-shots")
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.IdealPlan), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { IdealPlanScreen() } }
            }
        }
        compose.waitUntil(10_000) { settle(); compose.onAllNodes(hasText("Что можно улучшить", ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureRoboImage("build/shots/carbon-ideal-1.png")
        compose.onNodeWithText("Разгибание ног → Болгарские сплит-приседания (как было в плане)").performScrollTo().performClick()
        settle()
        compose.onRoot().captureRoboImage("build/shots/carbon-ideal-2.png")
        compose.onNodeWithText("Неделя в зале", ignoreCase = true).performScrollTo()
        settle()
        compose.onRoot().captureRoboImage("build/shots/carbon-ideal-3.png")
        compose.onNodeWithText("Подходы в неделю на мышцу", ignoreCase = true).performScrollTo()
        settle()
        compose.onRoot().captureRoboImage("build/shots/carbon-ideal-4.png")
        compose.onNodeWithText("Что уже хорошо", ignoreCase = true).performScrollTo()
        settle()
        compose.onRoot().captureRoboImage("build/shots/carbon-ideal-5.png")
    }
}
