package forma.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.AppJson
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.LiftItem
import forma.app.data.LiftPlan
import forma.app.data.SetEntity
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.SlotStateEntity
import forma.app.data.Status
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.app.ui.screens.WorkoutScreen
import forma.app.ui.screens.exerciseOf
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.strength.DoneSet
import forma.core.strength.Prescription
import forma.core.strength.Progression
import forma.core.text.Ru
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The next set right after a set (Denis, 25 November: «надо сделать 85 на 7, а ты сделал 85 на 7 и мог еще 5, ты ставишь
 * 5+, пишет "легко, но вес тот же", а если выйти назад на главный экран с текущей тренировки и вернуться назад, то тогда
 * уже вес повышается как и должен»): the hint right after the set read the reserve through the person's correction
 * (−2 for him since 22.11), the screen opened again read it as rated. Within a session the next set goes by the reserve
 * as rated, at once and after coming back. And a set left unrated is asked about with one tap («можно забыть нажать»).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class SetHintAtOnceUiTest {
    @get:Rule val compose = createComposeRule()

    @After fun tidy() = RestTimer.stop()

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

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

    private val profile = Profile(
        name = "Денис", goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE,
        trainingDays = setOf(2, 4, 6), bodyWeightKg = 73.0, birthYear = 2008,
    )

    private fun gymRepo(name: String): TrainingRepository {
        val store = SettingsStore(ctx, name)
        runBlocking { store.clear() }
        return TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store).also { runBlocking { it.completeOnboarding(profile) } }
    }

    /** Five finished sessions that read as a reserve rated 2 too high: «ещё 4» after 8, and two sets later only 7 come. */
    private fun overRated(repo: TrainingRepository, item: LiftItem) = runBlocking {
        val shown = item.copy(position = 90, prescription = Prescription(80.0, 8, 3, 2, calibrate = false))
        repeat(5) { k ->
            val t = LocalDate.now().minusDays(30L - k * 2).atTime(18, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val id = repo.db.workouts().insert(
                WorkoutEntity(
                    epochDay = LocalDate.now().minusDays(30L - k * 2).toEpochDay(), kind = Kind.LIFT, templateId = "t", title = "Т", startedAt = t,
                    finishedAt = t + 3_600_000L, status = Status.DONE, rpe = 7, durationSec = 3600, load = 420.0,
                    planJson = AppJson.encodeToString(LiftPlan.serializer(), LiftPlan("t", "Т", listOf(shown), deload = false)),
                ),
            )
            listOf(Triple(80.0, 8, 4), Triple(80.0, 8, 2), Triple(80.0, 7, null)).forEachIndexed { i, (w, r, rir) ->
                repo.db.sets().insert(
                    SetEntity(workoutId = id, exerciseId = shown.exerciseId, slotKey = shown.slot.key, position = shown.position, weight = w, reps = r,
                        rir = rir, targetWeight = 80.0, targetReps = 8, doneAt = t + i * 180_000L),
                )
            }
        }
        assertEquals(2, repo.rirBias(), "the history reads as a reserve rated 2 too high")
    }

    /** A session whose first exercise has a working weight (no first-time search), after the over-rated history. */
    private fun start(repo: TrainingRepository, weight: Double = 60.0): Long = runBlocking {
        val t = repo.store.programNow()!!.templates.first()
        val first = repo.liftPlan(t.id, LocalDate.now()).items.first()
        overRated(repo, first)
        repo.db.state().upsertSlot(SlotStateEntity(first.slot.key, first.exerciseId, weight, first.slot.repMin, 0, 0, 3, 0L, false, 0, null, null))
        repo.startLift(t.id)
    }

    private fun first(repo: TrainingRepository, id: Long) = runBlocking { repo.planOf(repo.db.workouts().get(id)!!)!!.items.first() }
    private fun setsOf(repo: TrainingRepository, id: Long) = runBlocking { repo.db.sets().forWorkout(id) }

    /** The next set as rated: what the fields must show after [done]. */
    private fun asRated(repo: TrainingRepository, item: LiftItem, done: List<DoneSet>) = runBlocking {
        val ex = exerciseOf(item.exerciseId, repo.store.settingsNow(), item)
        assertNotNull(Progression.nextSet(ex, item.prescription, done, profile.bodyWeightKg))
    }

    @Test fun theWeightGoesUpRightAfterASetRatedFivePlusAndStaysSoOnComingBack() {
        val repo = gymRepo("set-hint-at-once")
        val id = start(repo)
        val item = first(repo, id)
        val p = item.prescription
        val next = asRated(repo, item, listOf(DoneSet(p.weight!!, p.reps, 5)))
        assertTrue(next.change > 0, "five and more in reserve at the planned weight is a reason to add: $next")
        var shown by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { if (shown) WorkoutScreen(id) } }
            }
        }
        settle()
        compose.onNode(hasText("5+") and isSelectable()).performClick()
        compose.onNodeWithText("Подход сделан").performClick()
        settle()
        assertEquals(5, setsOf(repo, id).single().rir)
        // At once: the heavier weight in the field and the words for it.
        assertTrue(compose.onAllNodesWithText(Ru.kg(next.weight)).fetchSemanticsNodes().isNotEmpty(), "the field shows ${Ru.kg(next.weight)} at once: ${texts("кг")}")
        assertTrue(texts("Было легко, прибавляем").isNotEmpty(), "the hint says the weight goes up: ${texts("вес")}")
        assertTrue(texts("вес пока тот же").isEmpty() && texts("вес тот же").isEmpty(), "not «вес тот же»")
        // Out to another screen and back: the same weight (it used to change only now).
        shown = false
        settle()
        shown = true
        settle()
        assertTrue(compose.onAllNodesWithText(Ru.kg(next.weight)).fetchSemanticsNodes().isNotEmpty(), "the same ${Ru.kg(next.weight)} after coming back")
    }

    @Test fun aSetLeftUnratedIsAskedAboutWithOneTap() {
        val repo = gymRepo("set-hint-unrated")
        val id = start(repo)
        val item = first(repo, id)
        val p = item.prescription
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { WorkoutScreen(id) } }
            }
        }
        settle()
        assertTrue(texts("без отметки").isEmpty(), "nothing to ask before a set")
        compose.onNodeWithText("Подход сделан").performClick()
        settle()
        assertNull(setsOf(repo, id).single().rir, "written as it was, with no reserve marked")
        assertTrue(texts("Прошлый подход без отметки").isNotEmpty(), "the set just done is asked about")
        // One tap on the question's own row rates that set, and the next one follows it.
        compose.onNode(hasText("5+") and isSelectable() and hasAnyAncestor(hasTestTag("rate-last"))).performClick()
        settle()
        assertEquals(5, setsOf(repo, id).single().rir)
        assertTrue(texts("Прошлый подход без отметки").isEmpty(), "asked once")
        val next = asRated(repo, item, listOf(DoneSet(p.weight!!, p.reps, 5)))
        assertTrue(compose.onAllNodesWithText(Ru.kg(next.weight)).fetchSemanticsNodes().isNotEmpty(), "the next set follows the rating: ${Ru.kg(next.weight)}")
    }
}
