package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.ExerciseStatEntity
import forma.app.data.FormaDatabase
import forma.app.data.GymGoals
import forma.app.data.LiftItem
import forma.app.data.SettingsStore
import forma.app.data.SlotStateEntity
import forma.app.data.TrainingRepository
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Equipment
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.LiftGoal
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.plan.ProgramGenerator
import forma.core.plan.Template
import forma.core.strength.Phase
import forma.core.strength.SlotRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The gym findings of the November audit (group A) that live in the repository: warm-ups, steps, the test day, limits. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AuditFixGymEngineRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var store: SettingsStore
    private lateinit var repo: TrainingRepository
    private val start = LocalDate.of(2026, 11, 2)
    private var clock = millis(start)

    private fun millis(d: LocalDate) = d.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        store = SettingsStore(ctx, "audit-fix-gym-engine")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 71.0, aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
    }

    @After fun tearDown() = db.close()

    /** A day of the plan that is no lighter week, nor is the day [after] it. */
    private suspend fun usualDay(after: Long = 0): LocalDate {
        val program = store.programNow()!!
        return generateSequence(start) { it.plusDays(1) }.first { d ->
            !ProgramGenerator.isDeloadWeek(program, d) && !ProgramGenerator.isDeloadWeek(program, d.plusDays(after))
        }
    }

    /**
     * [newId] in the place of the first slot of [role] (a compound one) in a template that has one, done first that day
     * (the person's own order): its muscles fresh. Returns the template and the place.
     */
    private suspend fun firstOfTheDay(role: SlotRole, newId: String): Pair<Template, Int> {
        val t = store.programNow()!!.templates.first { tpl -> tpl.slots.any { it.role == role && Catalog[it.exerciseId].compound } }
        val position = t.slots.indexOfFirst { it.role == role && Catalog[it.exerciseId].compound }
        val planId = t.slots[position].exerciseId
        if (planId != newId) repo.setSwap(planId, newId)
        repo.updateSettings { it.copy(order = it.order + (t.id to listOf(planId))) }
        return t to position
    }

    private suspend fun item(t: Template, position: Int, day: LocalDate): LiftItem =
        repo.liftPlan(t.id, day).items.first { it.position == position && !it.addedForGood }

    private suspend fun state(item: LiftItem, weight: Double, at: Long = clock) =
        db.state().upsertSlot(SlotStateEntity(item.slot.key, item.exerciseId, weight, item.slot.repMin, 0, 0, 8, at, false, 0, null, null))

    private fun onSteps(w: Double, step: Double, from: Double = 0.0) = abs((w - from) / step - Math.round((w - from) / step)) < 1e-9

    /** gym-weighted-pullups-no-warmup */
    @Test fun weightedPullUpsAsTheMainLiftAreWarmedUpFromTheBody() = runBlocking<Unit> {
        val day = usualDay()
        clock = millis(day)
        val (t, position) = firstOfTheDay(SlotRole.MAIN, "pullup")
        val before = item(t, position, day)
        assertEquals("pullup", before.exerciseId)
        assertEquals(SlotRole.MAIN, before.slot.role)
        state(before, 25.0)
        val pull = item(t, position, day)
        val work = assertNotNull(pull.prescription.weight)
        assertTrue(work > 0, "${pull.prescription}")
        // Before, no warm-up rows and no word before the heaviest pull of the day.
        assertTrue(pull.warmups.isNotEmpty(), "no warm-up before pull-ups +$work: ${pull.noWarmUp}")
        assertEquals(0.0, pull.warmups.first().weight, "the body alone first: ${pull.warmups}")
        assertTrue(pull.warmups.all { it.weight < work }, "${pull.warmups} before +$work")
        assertTrue(pull.warmups.zipWithNext().all { (a, b) -> a.weight < b.weight }, "${pull.warmups}")
        // With the body alone, nothing lighter without help: no ramp, and a word why.
        state(before, 0.0)
        val own = item(t, position, day)
        assertEquals(0.0, own.prescription.weight)
        assertTrue(own.warmups.isEmpty() && !own.noWarmUp.isNullOrBlank(), "${own.warmups} / ${own.noWarmUp}")
    }

    /** gym-single-warmup-catalog-step */
    @Test fun theOneWarmUpSetIsOnThePersonsOwnStep() = runBlocking<Unit> {
        val day = usualDay()
        clock = millis(day)
        val (t, position) = firstOfTheDay(SlotRole.SECONDARY, "db_shoulder")
        repo.setStep("db_shoulder", 2.5, Catalog["db_shoulder"].step)
        val before = item(t, position, day)
        assertEquals("db_shoulder", before.exerciseId)
        assertEquals(SlotRole.SECONDARY, before.slot.role)
        state(before, 22.5)
        val press = item(t, position, day)
        assertEquals(2.5, press.step)
        // 22.5 on a rack of 2.5 kg steps: 12.5, not the catalog's 14 (no such dumbbell there).
        assertTrue(press.warmups.isNotEmpty(), "${press.prescription.weight}: ${press.noWarmUp}")
        press.warmups.forEach { assertTrue(onSteps(it.weight, 2.5), "warm-up ${it.weight} off the 2.5 kg steps: ${press.warmups}") }
    }

    /** gym-restep-start-hint */
    @Test fun aStepChangedDuringTheSessionMovesTheStartWeightAndTheRamp() = runBlocking<Unit> {
        val day = usualDay()
        clock = millis(day)
        val t = store.programNow()!!.templates.first { tpl -> tpl.slots.any { it.role == SlotRole.MAIN && Catalog[it.exerciseId].equipment == Equipment.BARBELL } }
        val id = repo.startLift(t.id, day)
        // The first time on the lateral raise machine: the search starts from a guess.
        assertTrue(repo.addToWorkout(id, "machine_lateral"))
        suspend fun plan() = repo.planOf(db.workouts().get(id)!!)!!
        val raise = plan().items.first { it.exerciseId == "machine_lateral" }
        assertTrue(raise.prescription.calibrate)
        val stack = (0..40).map { 9.0 + 5 * it }
        assertTrue(raise.prescription.startHint!! !in stack, "the guess ${raise.prescription.startHint} is on the stack already")
        // Its stack goes 9, 14, 19 …, said during the session: the field's start weight is on it at once.
        repo.setStep("machine_lateral", 5.0, Catalog["machine_lateral"].step, 9.0)
        val hint = assertNotNull(plan().items.first { it.exerciseId == "machine_lateral" }.prescription.startHint)
        assertTrue(stack.any { abs(it - hint) < 1e-9 }, "start weight $hint is not on the stack")
        // A main lift not started yet: the ramp to it on the new step too.
        val main = plan().items.first { it.slot.role == SlotRole.MAIN && Catalog[it.exerciseId].equipment == Equipment.BARBELL && it.warmups.isNotEmpty() }
        repo.setStep(main.exerciseId, 5.0, Catalog[main.exerciseId].step)
        val now = plan().items.first { it.position == main.position }
        val work = now.prescription.weight ?: now.prescription.startHint!!
        assertTrue(onSteps(work, 5.0), "work $work")
        assertTrue(now.warmups.isNotEmpty())
        now.warmups.forEach { assertTrue(onSteps(it.weight, 5.0), "warm-up ${it.weight} off the 5 kg step: ${now.warmups}") }
    }

    /** gym-test-day-off-stack */
    @Test fun theTestDayIsOnTheMachinesStack() = runBlocking<Unit> {
        db.state().upsertStat(ExerciseStatEntity("chest_press", 100.0, 100.0, clock))
        repo.setStep("chest_press", 5.0, Catalog["chest_press"].step, 9.0)
        val test = assertNotNull(GymGoals.testDay(repo, LiftGoal("chest_press", 104.0)))
        val stack = (0..60).map { 9.0 + 5 * it }
        (test.warmups.map { it.first } + test.attempts).forEach { w -> assertTrue(stack.any { abs(it - w) < 1e-9 }, "$w off the stack: $test") }
    }

    /** gym-layoff-lost-after-lighter-session */
    @Test fun backAfterABreakTheLighterFirstSessionKeepsTheBreaksCut() = runBlocking<Unit> {
        val day = usualDay(after = 3)
        val t = store.programNow()!!.templates.first { tpl -> tpl.slots.any { it.role == SlotRole.MAIN && Catalog[it.exerciseId].equipment == Equipment.BARBELL } }
        val position = t.slots.indexOfFirst { it.role == SlotRole.MAIN && Catalog[it.exerciseId].equipment == Equipment.BARBELL }
        // 100 kg five weeks ago, then nothing: ill.
        state(item(t, position, day), 100.0, at = millis(day.minusDays(35)))
        clock = millis(day)
        // The first day back is a «Так себе» one: lighter still.
        val id = repo.startLift(t.id, day, forceDeload = true)
        val plan = repo.planOf(db.workouts().get(id)!!)!!
        val lift = plan.items.first { it.position == position }
        assertEquals(Phase.DELOAD, lift.prescription.phase)
        assertEquals(80.0, lift.prescription.weight)
        repeat(lift.prescription.sets) { repo.logSet(id, lift, lift.prescription.weight!!, lift.prescription.reps, null) }
        repo.finishLift(id, 6)
        // Three days later: the weight the break made lighter, not the 100 from before it.
        clock = millis(day.plusDays(3))
        val next = item(t, position, day.plusDays(3))
        assertEquals(Phase.NORMAL, next.prescription.phase)
        assertTrue(next.prescription.weight!! <= 90.0, "${next.prescription}")
    }

    /** apps-2 */
    @Test fun theMachinesHeaviestWeightCapsThePlan() = runBlocking<Unit> {
        val day = usualDay()
        clock = millis(day)
        val (t, position) = firstOfTheDay(SlotRole.SECONDARY, "lat_pulldown")
        repo.setStep("lat_pulldown", 4.0, Catalog["lat_pulldown"].step)
        state(item(t, position, day), 96.0)
        assertTrue(item(t, position, day).prescription.weight!! > 92.0)
        // A session under way follows at once.
        val id = repo.startLift(t.id, day)
        repo.setHeaviest("lat_pulldown", 92.0)
        assertEquals(92.0, store.settingsNow().heaviest["lat_pulldown"])
        val today = repo.planOf(db.workouts().get(id)!!)!!.items.first { it.position == position }
        assertTrue(today.prescription.weight!! <= 92.0, "${today.prescription}")
        repo.cancelWorkout(id)
        // And every plan from now on.
        assertTrue(item(t, position, day).prescription.weight!! <= 92.0)
        assertEquals(92.0, store.settingsNow().onSteps(Catalog["lat_pulldown"]).heaviest)
        // Taken back: no limit.
        repo.setHeaviest("lat_pulldown", null)
        assertNull(store.settingsNow().heaviest["lat_pulldown"])
        assertTrue(item(t, position, day).prescription.weight!! > 92.0)
    }
}
