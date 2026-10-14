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
import forma.app.data.SetEntity
import forma.app.data.LiftPlan
import androidx.compose.ui.test.onAllNodesWithText
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
        // His own swap stays, and nothing hard and unusual is pushed on him — said why, not proposed.
        assertTrue(ids.none { it.startsWith("oneleg") || it == "add:nordic" } && "add:seated_calf" in ids, ids.toString())
        assertTrue(a.held.any { "это твой выбор, обратно не предлагаю" in it }, a.held.toString())
        assertTrue(a.held.any { it.startsWith("Нордические сгибания не предлагаю") }, a.held.toString())
        val chest = a.changes.first { it.id.startsWith("vol:CHEST") }

        repo.applyPlanAdvice(listOf(chest))
        val s = repo.store.settingsNow()
        assertEquals("leg_ext", s.swaps["bulgarian"], "his swap stays")
        assertTrue(s.added["lower_a"].orEmpty().isEmpty(), "not ticked, not taken")
        assertEquals(chest.setsTo, repo.liftPlan(chest.templateId, monday.plusDays(1)).items.first { it.exerciseId == chest.planId }.slot.sets)
        // Taken: no longer proposed; the rest still is.
        val again = repo.planAdvice()!!.changes.map { it.id }
        assertTrue(chest.id !in again && "add:seated_calf" in again, again.toString())
        repo.applyPlanAdvice(emptyList())
        assertEquals(s, repo.store.settingsNow())
    }

    /** The bench without a new best for five weeks (a median of three sessions, so one odd session decides nothing): another variation, first. */
    @Test fun aLiftWithoutANewBestForWeeksIsSeenFromTheHistory() = runBlocking<Unit> {
        val repo = repo("ideal-stall")
        val program = repo.store.programNow()!!
        val t = program.templates.first { it.id == "upper_a" }
        for (week in 0..7) {
            val day = monday.minusWeeks(7L - week).plusDays(1)
            val at = day.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val e1rm = if (week < 3) 100.0 else 99.5
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
        assertTrue("5 недель" in first.why, first.why)
    }

    /**
     * From his sessions: the leg extension (put in the Bulgarian's place with an added exercise's numbers, 4 sets) never
     * done in four sessions, the calf raise done one set of two. Proposed: the same muscle another way, the calf raise
     * set to what he does. «С выбранным» is what taking them does: the step-up in that place gets the plan's numbers.
     */
    @Test fun whatHeSkipsOrCutsShortIsSeenFromHisSessions() = runBlocking<Unit> {
        val repo = repo("ideal-habits")
        repo.store.editSettings { it.copy(swaps = it.swaps - "bulgarian") }
        repo.setSwap("bulgarian", "leg_ext")
        repo.setPlanSets("bulgarian", 4, 3)
        val days = listOf(13L, 17L, 24L, 31L).map { LocalDate.of(2026, 10, it.toInt()) }
        for (day in days) {
            val at = day.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val plan = repo.liftPlan("lower_a", day)
            val id = db.workouts().insert(
                WorkoutEntity(epochDay = day.toEpochDay(), kind = Kind.LIFT, templateId = "lower_a", title = plan.title, startedAt = at, finishedAt = at,
                    status = Status.DONE, rpe = 7, durationSec = 3600, load = 300.0, planJson = AppJson.encodeToString(LiftPlan.serializer(), plan)),
            )
            for (item in plan.items) {
                val n = when (item.exerciseId) {
                    "leg_ext" -> 0
                    "calf_raise" -> 1
                    else -> item.prescription.sets
                }
                repeat(n) { db.sets().insert(SetEntity(workoutId = id, exerciseId = item.exerciseId, slotKey = item.slot.key, position = item.position,
                    weight = 40.0, reps = 8, rir = 2, targetWeight = null, targetReps = null, doneAt = at + it)) }
            }
        }
        clock = LocalDate.of(2026, 11, 1).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val a = repo.planAdvice()!!
        val skip = a.changes.first { it.id == "skip:bulgarian" }
        assertTrue("в 4 из 4 последних тренировок" in skip.why, skip.why)
        assertTrue("leg_ext" !in skip.options && "bulgarian" !in skip.options && "step_up" in skip.options, skip.options.toString())
        val calves = a.changes.first { it.id == "real:calf_raise" }
        assertEquals(2 to 1, calves.setsFrom to calves.setsTo)

        val chosen = listOf(skip.pick("step_up"), calves)
        val preview = repo.planPreview(chosen)
        repo.applyPlanAdvice(chosen)
        assertEquals(preview, repo.planAdvice()!!.days)
        val step = preview.first { it.templateId == "lower_a" }.items.first { it.exerciseId == "step_up" }
        assertEquals(Triple(3, 8, 10), Triple(step.sets, step.repMin, step.repMax), "the plan's own numbers, his 4 sets of the extension do not carry over")
        assertEquals(1, repo.liftPlan("lower_a", LocalDate.of(2026, 11, 7)).items.first { it.exerciseId == "calf_raise" }.slot.sets)
    }

    /** «Не предлагать»: kept, not proposed again, and Nordic curls hidden count against hard unusual exercises. */
    @Test fun whatHeSaidNotToProposeStaysAway() = runBlocking<Unit> {
        val repo = repo("ideal-hide")
        repo.store.editSettings { it.copy(swaps = it.swaps - "bulgarian") }
        repo.hideAdvice("add:nordic")
        val a = repo.planAdvice()!!
        assertTrue(a.changes.none { it.id == "add:nordic" })
        assertEquals(1, a.hidden)
        // Nordic curls said not to: the Bulgarian split squat is not pushed either.
        assertTrue(a.changes.none { "bulgarian" in it.options || "nordic_curl" in it.options }, a.changes.map { it.id to it.options }.toString())
        repo.showHiddenAdvice()
        assertEquals(0, repo.planAdvice()!!.hidden)
    }

    @Test fun nothingNewForHisLegsBeforeTheRace() = runBlocking<Unit> {
        val repo = repo("ideal-race")
        repo.updateProfile(repo.store.profileNow()!!.copy(runGoal = RunGoal(1500, 330, monday.plusDays(16).toEpochDay())))
        val a = repo.planAdvice()!!
        assertTrue(a.changes.none { it.templateId == "lower_a" }, a.changes.map { it.id }.toString())
        assertTrue(a.held.any { "До забега" in it }, a.held.toString())
    }

    @Test fun theScreenShowsWhatAndWhyAndTakesOnlyTheTickedChange() {
        val repo = repo("ideal-ui")
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.IdealPlan), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { IdealPlanScreen() } }
            }
        }
        compose.waitUntil(10_000) { settle(); compose.onAllNodes(hasText("Что можно улучшить", ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("+ Подъём на носки сидя · 3 × 12–20").assertExists()
        compose.onNodeWithText("Камбаловидная мышца", substring = true).assertExists()
        compose.onNodeWithText("это твой выбор, обратно не предлагаю", substring = true).performScrollTo().assertExists()
        // Nothing ticked: nothing to take.
        compose.onNodeWithText("Отметь, что взять", ignoreCase = true).performScrollTo().assertExists()
        // «Не предлагать» on the soleus: gone, kept, can come back.
        compose.onAllNodesWithText("Не предлагать")[0].performScrollTo().performClick()
        compose.waitUntil(10_000) { settle(); compose.onAllNodes(hasText("Скрыто по твоей просьбе: 1", substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("add:seated_calf" in runBlocking { repo.store.settingsNow() }.adviceHidden)
        compose.onNodeWithText("+ Подъём на носки сидя · 3 × 12–20").assertDoesNotExist()
        compose.onNodeWithText("Жим гантелей на наклонной: 3 → 4 подхода").performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Принять выбранные (1)", ignoreCase = true).performScrollTo().performClick()
        settle()
        val s = runBlocking { repo.store.settingsNow() }
        assertEquals(4, s.sets["db_incline"])
        assertEquals("leg_ext", s.swaps["bulgarian"])
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
        compose.onNodeWithText("Жим гантелей на наклонной: 3 → 4 подхода").performScrollTo().performClick()
        settle()
        compose.onRoot().captureRoboImage("build/shots/carbon-ideal-2.png")
        compose.onNodeWithText("Что не предлагаю сейчас", ignoreCase = true).performScrollTo()
        settle()
        compose.onRoot().captureRoboImage("build/shots/carbon-ideal-held.png")
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
