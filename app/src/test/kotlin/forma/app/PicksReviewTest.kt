package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.CoachActions
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.sync.CoachCall
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Gear
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Pattern
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.plan.PlanAdvisor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A review of «Мои упражнения» through the repository (each test failed on 1563b2a and is a finding fixed): hand-made replacements, the kit changing, the coach card, a place out and back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PicksReviewTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private val day = LocalDate.of(2026, 10, 26)
    private var clock = day.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val lifter = Profile(
        goal = Goal.MUSCLE, experience = Experience.INTERMEDIATE, runLevel = RunLevel.NONE, trainingDays = setOf(1, 2, 4, 5), bodyWeightKg = 75.0,
        sessionMinutes = 90, aim = Aim(LiftFocus.MUSCLE, RunFocus.NONE, Emphasis.GYM),
    )

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "picks-probe")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock++ })
        repo.completeOnboarding(lifter)
    }

    @After fun tearDown() = db.close()

    private suspend fun done(planId: String): String = repo.store.programNow()!!.templates.first { t -> t.slots.any { it.exerciseId == planId } }
        .let { t -> repo.liftPlan(t.id, day).items.first { it.slot.exerciseId == planId || it.planId == planId }.exerciseId }

    private suspend fun session(templateId: String) = repo.liftPlan(templateId, day).items.map { it.exerciseId }

    private suspend fun allItems() = repo.store.programNow()!!.templates.flatMap { t -> repo.liftPlan(t.id, day).items.map { t.title to it } }

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to JsonPrimitive(v.toString()) } + ("why" to JsonPrimitive("проверка"))),
    )

    /** A like, then a replacement made by hand on the same place, then the plan laid out again: the hand-made one is silently undone. */
    @Test fun aHandMadeReplacementIsUndoneWhenThePlanIsLaidOutAgain() = runBlocking<Unit> {
        repo.setPicks(mapOf(Pattern.SQUAT to listOf("hack_squat")), emptySet())
        assertEquals("hack_squat", done("squat"))
        repo.setSwap("squat", "box_squat")
        assertEquals("box_squat", done("squat"))
        assertTrue("squat" !in repo.store.settingsNow().pickSwaps)
        repo.updateProfile(repo.store.profileNow()!!.copy(trainingDays = setOf(1, 3, 5)))
        assertEquals(
            "box_squat", done("squat"),
            "the person put box_squat in the squat place by hand after liking hack_squat; a change of training days (reapplyPicks) put hack_squat back without a word",
        )
    }

    /** The same, without a relayout: adding a «не хочу» elsewhere re-resolves every place, and the hand-made one goes. */
    @Test fun aHandMadeReplacementIsUndoneByAnUnrelatedPick() = runBlocking<Unit> {
        repo.setPicks(mapOf(Pattern.SQUAT to listOf("hack_squat")), emptySet())
        repo.setSwap("squat", "box_squat")
        val r = repo.setPicks(mapOf(Pattern.SQUAT to listOf("hack_squat")), setOf("face_pull"))
        assertEquals("box_squat", done("squat"), "marking face_pull «не хочу» replaced the hand-made box_squat with hack_squat again: ${r.swaps}")
    }

    /** Denis took the calves out at the gym; at home with dumbbells the plan has db_calf — the calves are back. */
    @Test fun removedCalvesComeBackAtHome() = runBlocking<Unit> {
        repo.setRemoved("calf_raise", true)
        assertTrue(allItems().none { Catalog[it.second.exerciseId].pattern == Pattern.CALF })
        repo.updateProfile(repo.store.profileNow()!!.copy(gear = Gear.DUMBBELLS))
        val calves = allItems().filter { Catalog[it.second.exerciseId].pattern == Pattern.CALF }
        assertTrue(calves.isEmpty(), "calves taken out for good are back at home (GearFit gives the place a new plan id): ${calves.map { it.first to it.second.exerciseId }}; removed=${repo.store.settingsNow().removed}")
    }

    /** Own sets on the squat place are lost at home for the same reason: the place is now «goblet_squat». */
    @Test fun ownSetsOfAPlaceAreLostAtHome() = runBlocking<Unit> {
        repo.setPlanSets("squat", 5, 3)
        assertEquals(5, repo.liftPlan("lower_a", day).items.first { it.planId == "squat" }.prescription.sets)
        repo.updateProfile(repo.store.profileNow()!!.copy(gear = Gear.DUMBBELLS))
        val squatPlace = repo.liftPlan("lower_a", day).items.first { Catalog[it.exerciseId].pattern == Pattern.SQUAT }
        assertEquals(5, squatPlace.prescription.sets, "5 sets set on the squat place are gone at home; the place is now ${squatPlace.exerciseId} with the plan's own sets")
    }

    /** One like for the squat movement: Низ · А (squat + leg_press) gets the same exercise twice. */
    @Test fun aLikeIsPutTwiceInOneSession() = runBlocking<Unit> {
        repo.setPicks(mapOf(Pattern.SQUAT to listOf("hack_squat")), emptySet())
        val ids = session("lower_a")
        assertEquals(ids.distinct().size, ids.size, "Низ · А has the same exercise twice: $ids")
    }

    /** The coach's card says the same: «Приседания со штангой» → «Гакк-присед» and «Жим ногами» → «Гакк-присед» in one session. */
    @Test fun theCoachCardProposesADoubleInOneSession() = runBlocking<Unit> {
        val r = CoachActions.prepare(repo, call(CoachActions.PICKS, "like" to "Гакк-присед"), day)
            .let { assertIs<CoachActions.Ready>(it, (it as? CoachActions.Refused)?.why) }
        assertTrue("«Жим ногами» → «Гакк-присед»" !in r.text, "the card proposes hack_squat for both squat places of Низ · А: ${r.text}")
    }

    /** A «Идеальный план» change accepted on a place the picks had made is undone when the picks are reset. */
    @Test fun anAcceptedAdviceSwapIsUndoneByResettingPicks() = runBlocking<Unit> {
        repo.setPicks(mapOf(Pattern.HORIZONTAL_PUSH to listOf("chest_press")), emptySet())
        assertEquals("chest_press", done("bench"))
        // The advisor proposes smith_bench in that place; the person accepts (the same edit takeAdvice makes).
        val program = repo.store.programNow()!!
        val change = PlanAdvisor.Change(
            id = "probe", kind = PlanAdvisor.Kind.SWAP, templateId = "upper_a", dayTitle = "Верх · А", planId = "bench", from = "chest_press",
            options = listOf("smith_bench"), setsFrom = null, setsTo = null, repsTo = null, restSec = null, why = "проверка", priority = 1,
        )
        repo.updateSettings { TrainingRepository.advised(it, program, listOf(change)) }
        assertEquals("smith_bench", done("bench"))
        repo.setPicks(emptyMap(), emptySet())
        assertEquals("smith_bench", done("bench"), "«Сбросить всё» undid the advisor's change the person accepted after the pick (pickSwaps still flagged bench)")
    }

    /** A place taken out while a pick stood in it, the picks then cleared, the place brought back: the pick is still there. */
    @Test fun aClearedPickLingersInAPlaceThatWasOutMeanwhile() = runBlocking<Unit> {
        repo.setPicks(mapOf(Pattern.HORIZONTAL_PUSH to listOf("chest_press")), emptySet())
        repo.setRemoved("bench", true)
        repo.setPicks(emptyMap(), emptySet())
        repo.setRemoved("bench", false)
        assertEquals("bench", done("bench"), "likes are empty, yet the bench place still holds the old pick: swaps=${repo.store.settingsNow().swaps} pickSwaps=${repo.store.settingsNow().pickSwaps}")
    }
}
