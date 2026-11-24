package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.AppJson
import forma.app.data.BackupManager
import forma.app.data.BodyWeightEntity
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.LiftSummary
import forma.app.data.RunEntity
import forma.app.data.SetEntity
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.SlotReport
import forma.app.data.SlotStateEntity
import forma.app.data.Status
import forma.app.data.TrainingReport
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.app.ui.components.trendX
import forma.app.ui.screens.BodyScreen
import forma.app.ui.screens.ExerciseScreen
import forma.app.ui.screens.Hints
import forma.app.ui.screens.LiftDoneScreen
import forma.app.ui.screens.ProgressScreen
import forma.app.ui.screens.coachContext
import forma.app.ui.screens.liftRow
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.analytics.SetRow
import forma.core.model.Catalog
import forma.core.model.Exercise
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LoadKind
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.strength.OneRm
import forma.core.strength.Outcome
import forma.core.text.Ru
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Audit fixes of group I (progress screens): one number for «на 1 раз» on the exercise's page, «Прогресс», the coach and
 * the export; lifts with the body said as the weight added to it; weigh-ins drawn by date; a week of runs without a
 * distance not empty.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h4200dp-xhdpi")
class AuditFixProgressUiTest {
    @get:Rule val compose = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private var clock = 0L
    private fun at(d: LocalDate, hour: Int) { clock = d.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli() }

    private fun settle(rounds: Int = 6) {
        repeat(rounds) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun texts(substring: String): List<String> =
        compose.onAllNodesWithText(substring, substring = true).fetchSemanticsNodes()
            .map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "" }

    private fun descriptions(substring: String): List<String> =
        compose.onAllNodesWithContentDescription(substring, substring = true).fetchSemanticsNodes()
            .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() }

    /** The screens read the database a query at a time: waited for, not a fixed pause. */
    private fun waitForText(substring: String) = compose.waitUntil(30_000) { settle(1); texts(substring).isNotEmpty() }

    private fun waitForDescription(substring: String) = compose.waitUntil(30_000) { settle(1); descriptions(substring).isNotEmpty() }

    /** Whether [condition] comes true within a while: a screen's flows emit one after another (the stats before the sets). */
    private fun eventually(condition: () -> Boolean): Boolean = runCatching { compose.waitUntil(60_000) { settle(1); condition() } }.isSuccess

    private val denis = Profile(
        name = "Денис", goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE,
        trainingDays = setOf(2, 4, 6), bodyWeightKg = 80.0, birthYear = 2008,
    )

    /** Someone who runs without a race goal, so «Прогресс» shows the runs week by week. */
    private val runner = Profile(
        name = "Аня", goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.TRAINED,
        trainingDays = setOf(1, 3, 5), bodyWeightKg = 60.0, birthYear = 1998,
    )

    private fun repo(name: String, on: LocalDate, profile: Profile = denis): TrainingRepository = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, name)
        store.clear()
        at(on, 9)
        // Each read of the clock a second later: sets keep their order.
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now = { clock += 1000; clock })
        repo.completeOnboarding(profile)
        store.editSettings { it.copy(hintsSeen = it.hintsSeen + listOf(Hints.PROGRESS)) }
        repo
    }

    private fun show(repo: TrainingRepository, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { content() } }
            }
        }
        settle()
    }

    /**
     * The finding's bench: eight weekly sessions of the first template's first exercise with a weight, at the plan's weight,
     * its sets with the last one two past the target («8, 8, 10»), nothing marked. The repository's day ends on the last
     * one. Returns the exercise and each session's own estimate (its summary), oldest first.
     */
    private fun eightWeeks(repo: TrainingRepository): Pair<Exercise, List<Double>> = runBlocking {
        val start = repo.today()
        val t = repo.store.programNow()!!.templates.first()
        val first = repo.liftPlan(t.id, start).items.first { Catalog[it.exerciseId].load == LoadKind.EXTERNAL }
        val ex = Catalog[first.exerciseId]
        // A working weight already: regular sessions with their «+» set, not the first search for the weight.
        repo.db.state().upsertSlot(SlotStateEntity(first.slot.key, ex.id, 80.0, first.slot.repMin, 0, 0, 3, 0L, false, 0, null, null))
        val estimates = (0 until 8).map { k ->
            val day = start.plusWeeks(k.toLong())
            at(day, 18)
            val id = repo.startLift(t.id, day)
            val item = repo.planOf(repo.db.workouts().get(id)!!)!!.items.first { it.exerciseId == ex.id }
            val p = item.prescription
            repeat(p.sets) { i -> repo.logSet(id, item, p.weight ?: 80.0, if (i == p.sets - 1) p.reps + 2 else p.reps, null) }
            repo.finishLift(id, 7).reports.first { it.exerciseId == ex.id }.e1rm!!
        }
        ex to estimates
    }

    /**
     * progress-1rm-two-estimators: the exercise's page says one number for one rep — the headline the better of the last
     * two sessions by the engine's own estimate, and «1 повтор до отказа» in its table the same, down to the step. Before,
     * the headline read the sets a second way (≈ 143,5) and the table the smoothed working estimate (132,5).
     */
    @Test fun theExercisePageSaysOneNumberForOneRep() {
        val repo = repo("fix-i-exercise", LocalDate.of(2026, 9, 29))
        val (ex, estimates) = eightWeeks(repo)
        val now = maxOf(estimates[6], estimates[7])
        show(repo) { ExerciseScreen(ex.id) }
        waitForText("Вес на 1 раз")
        assertTrue(eventually { texts("≈").any { it == "≈ ${Texts.oneRm(ex, now)}" } }, "headline ≈ ${Texts.oneRm(ex, now)}: ${texts("≈")}")
        compose.onNodeWithText("Сколько раз смогу с весом", substring = true).performScrollTo().performClick()
        settle()
        val one = Texts.weight(ex, runBlocking { repo.store.settingsNow() }.onSteps(ex).floorW(OneRm.loadFor(now, 1)))
        assertTrue(eventually { texts("кг").any { it == one } }, "«1 повтор до отказа» should be $one: ${texts("кг")}")
    }

    /**
     * progress-1rm-two-estimators: «Прогресс», the coach and the export say the same «на 1 раз» as the exercise's page.
     * Before, one bench was 143,5 on its row, «расчётный максимум 134,4» for the coach and 134,5 in the export.
     */
    @Test fun progressTheCoachAndTheExportSayTheSameOneRep() {
        val repo = repo("fix-i-progress", LocalDate.of(2026, 9, 29))
        val (ex, estimates) = eightWeeks(repo)
        val shown = Ru.kg((maxOf(estimates[6], estimates[7]) * 2).roundToInt() / 2.0)
        show(repo) { ProgressScreen() }
        waitForText(ex.name)
        // A row is one merged node: its texts come joined by ", " («Приседания со штангой, 102,5 кг, +16,5 кг»).
        assertTrue(eventually { texts("кг").any { "$shown кг" in it.split(", ") } }, "the row of ${ex.name} should say $shown кг: ${texts("кг")}")
        val today = repo.today()
        val brief = runBlocking { coachContext(repo, today) }.lines().filter { "[${ex.id}]" in it && "расчётный максимум" in it }
        assertTrue(brief.isNotEmpty() && brief.all { "расчётный максимум $shown кг" in it }, brief.toString())
        val export = TrainingReport.build(runBlocking { BackupManager(repo).snapshot() }, today, "test")
        assertTrue(export.lines().any { it.startsWith("- ${ex.name}: $shown кг") }, export.lines().filter { it.startsWith("- ${ex.name}:") }.toString())
    }

    /**
     * gym-07: a record on pull-ups is said as the weight added to the body. Denis (80 kg), 10 pull-ups with two left: the
     * summary said «Подтягивания: около 112 кг на 1 раз», read as 112 kg on the belt.
     */
    @Test fun aPullUpRecordSaysTheWeightAddedToTheBody() {
        val repo = repo("fix-i-pullup-record", LocalDate.of(2026, 11, 24))
        val id = runBlocking {
            val summary = LiftSummary(listOf(SlotReport("pullup", "Подтягивания", Outcome.AddReps(0.0, 10, 11), 112.0, true)), 800.0, 3, 30, 210.0)
            repo.db.workouts().insert(
                WorkoutEntity(
                    epochDay = repo.today().toEpochDay(), kind = Kind.LIFT, templateId = null, title = "Верх · А", startedAt = clock,
                    finishedAt = clock, status = Status.DONE, rpe = 7, durationSec = 1800,
                    summaryJson = AppJson.encodeToString(LiftSummary.serializer(), summary),
                ),
            )
        }
        show(repo) { LiftDoneScreen(id) }
        waitForText("на 1 раз")
        assertEquals(listOf("Подтягивания: +32 кг на 1 раз"), texts("на 1 раз"))
        assertTrue(texts("112").isEmpty(), texts("112").toString())
    }

    /** gym-07: the pull-ups page says «на 1 раз» as the weight added to the body, as «Прогресс» does, not body and all. */
    @Test fun thePullUpPageSaysTheWeightAddedToTheBody() {
        val repo = repo("fix-i-pullup-page", LocalDate.of(2026, 11, 24))
        runBlocking {
            val id = repo.db.workouts().insert(
                WorkoutEntity(
                    epochDay = repo.today().toEpochDay(), kind = Kind.LIFT, templateId = null, title = "Верх · А", startedAt = clock,
                    finishedAt = clock, status = Status.DONE,
                ),
            )
            repeat(3) { i ->
                repo.db.sets().insert(
                    SetEntity(workoutId = id, exerciseId = "pullup", slotKey = "pullup", position = 0, weight = 0.0, reps = 10, rir = null, targetWeight = 0.0, targetReps = 10, doneAt = clock + i),
                )
            }
        }
        show(repo) { ExerciseScreen("pullup") }
        waitForText("Вес на 1 раз")
        // 10 with the body (80 kg) and two in reserve: ≈ 117,6 kg moved at one rep, 37,5 kg on top of the body.
        assertTrue(eventually { texts("≈").any { it == "≈ +37,5 кг" } }, texts("≈").toString())
    }

    private val day0 = LocalDate.of(2026, 10, 6).toEpochDay()
    private fun row(w: Long, d: Long, ex: String, kg: Double, reps: Int) = SetRow(w, day0 + d, ex, kg, reps, null, reps)

    /**
     * bodyweight-row-subtracts-whole-body: a row of a lift with the body says what is added to the share of the body it
     * moves. Less the whole body, leg raises (a third of it) always read «свой вес», dips 3–4 kg low, help «свой вес».
     */
    @Test fun aRowOfALiftWithTheBodySubtractsOnlyTheShareItMoves() {
        // Leg raises 12, 13, 14 with the body (75 kg): the row says a weight that grows with the reps.
        val raises = (0 until 3).map { i -> row(i.toLong(), i * 7L, "leg_raise", 0.0, 12 + i) }
        val (raise, _) = assertNotNull(liftRow(Catalog["leg_raise"], raises, { 75.0 }, day0 - 1, day0 + 14))
        assertTrue(raise.startsWith("+"), raise)
        // Pull-ups with 35 kg of help at 80 kg: help still.
        val (helped, _) = assertNotNull(liftRow(Catalog["pullup"], listOf(row(1, 0, "pullup", -35.0, 8)), { 80.0 }, day0 - 1, day0))
        assertEquals("помощь 19 кг", helped)
        // Dips, 10 with the body (75 kg, 95 % of it moved): 33,5 kg on top, not 30.
        val (dips, _) = assertNotNull(liftRow(Catalog["dip"], listOf(row(1, 0, "dip", 0.0, 10)), { 75.0 }, day0 - 1, day0))
        assertEquals("+33,5 кг", dips)
    }

    /**
     * body-weight-chart-by-index: weigh-ins on days 0, 2 and 40 sit across the chart by date (0, 5 and 100 %), not evenly
     * (0, 50 and 100 %: a rise over 39 days drawn as steep as the change over the 2 before it).
     */
    @Test fun weighInsSitOnTheChartByDate() {
        assertEquals(listOf(0f, 0.05f, 1f), listOf(0L, 2L, 40L).map { trendX(day0 + it, day0, day0 + 40) })
    }

    /** body-weight-chart-by-index: «Вес тела» draws its weigh-ins by date, from the first one to today, as «Прогресс» does. */
    @Test fun theBodyScreenDrawsWeighInsByDate() {
        val today = LocalDate.of(2026, 11, 20)
        val repo = repo("fix-i-body", today)
        runBlocking {
            listOf(LocalDate.of(2026, 10, 10) to 73.0, LocalDate.of(2026, 10, 12) to 73.2, today to 74.45)
                .forEach { (d, kg) -> repo.db.state().upsertBodyWeight(BodyWeightEntity(d.toEpochDay(), kg)) }
        }
        show(repo) { BodyScreen() }
        waitForDescription("Вес тела по датам")
    }

    /**
     * run-weeks-drop-distance-less-runs: a week with only a run entered by hand without a distance is not empty. 5 km with
     * GPS last week, 30 minutes typed in this week: the chart said «Километры по неделям, на этой неделе 0,0».
     */
    @Test fun aWeekOfARunWithoutADistanceIsNotEmpty() {
        val today = LocalDate.of(2026, 11, 25)
        val repo = repo("fix-i-run-weeks", today, runner)
        runBlocking {
            for ((day, meters) in listOf(today.minusWeeks(1) to 5000.0, today.minusDays(1) to null)) {
                val id = repo.db.workouts().insert(
                    WorkoutEntity(
                        epochDay = day.toEpochDay(), kind = Kind.RUN, templateId = "EASY", title = "Лёгкий бег", startedAt = clock,
                        finishedAt = clock, status = Status.DONE, rpe = 4, durationSec = 1800,
                    ),
                )
                repo.db.runs().insert(RunEntity(id, "EASY", "{}", 1.0, 1800, meters, meters?.let { 1800 / (it / 1000) }, null, null, null))
            }
        }
        show(repo) { ProgressScreen() }
        waitForDescription("по неделям")
        assertTrue(eventually { descriptions("по неделям").any { it == "Минуты по неделям, на этой неделе 30" } }, descriptions("по неделям").toString())
    }
}
