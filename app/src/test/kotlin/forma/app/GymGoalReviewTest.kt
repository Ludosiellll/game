package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.CoachActions
import forma.app.data.FormaDatabase
import forma.app.data.GymGoals
import forma.app.data.LiftItem
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.sync.CoachCall
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.LiftGoal
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.ProgramGenerator
import forma.core.strength.GymGoal
import forma.core.strength.Phase
import forma.core.strength.HoldReason
import forma.core.strength.Outcome
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «Цель в зале» on Denis's real-like plan: a review's probes on 1cbf326, each a finding fixed.
 * Denis: POWERBUILDING + 1500 m, ADVANCED, gym Tue/Thu/Sat, runs Mon/Thu/Sat (shared days), the bench and the incline
 * dumbbell press swapped for the machine chest press, the dumbbell shoulder press for the dumbbell bench, the OHP for
 * the machine shoulder press. The program starts on [monday]: block weeks 1–3 are +0..+20, the lighter week 4 is +21..+27.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class GymGoalReviewTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    /** Monday 26 Oct 2026. */
    private val monday = LocalDate.of(2026, 10, 26)
    private var clock = 0L

    private fun at(d: LocalDate, hour: Int = 9) { clock = d.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    private fun day(n: Long) = monday.plusDays(n)

    private val denis = Profile(
        name = "Денис", goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
        bodyWeightKg = 71.0, lapMeters = 230, runGoal = RunGoal(1500, 330), aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
        doubleDays = true, gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
    )

    private fun make(profile: Profile, name: String) = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, name)
        store.clear()
        at(monday)
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(profile)
    }

    @Before fun setUp() = runBlocking<Unit> {
        make(denis, "gym-goal-review")
        repo.updateSettings {
            it.copy(
                swaps = mapOf("bench" to "chest_press", "db_incline" to "chest_press", "db_shoulder" to "db_bench", "ohp" to "machine_shoulder_press"),
                removed = setOf("plank"),
            )
        }
        assertEquals(monday.toEpochDay(), repo.store.programNow()!!.startEpochDay)
    }

    @After fun tearDown() = db.close()

    /** One session of [templateId] on [date]: the chest press done as [sets] (weight, reps, rir); the rest of the day skipped. */
    private suspend fun session(templateId: String, date: LocalDate, vararg sets: Triple<Double, Int, Int?>): Pair<LiftItem, Outcome?> {
        at(date, 18)
        val id = repo.startLift(templateId, date)
        val item = repo.planOf(db.workouts().get(id)!!)!!.items.first { it.exerciseId == "chest_press" }
        for ((w, r, rir) in sets) repo.logSet(id, item, w, r, rir)
        val summary = repo.finishLift(id, 7)
        return item to summary.reports.firstOrNull { it.exerciseId == "chest_press" }?.outcome
    }

    private suspend fun press(date: LocalDate, w: Double, reps: Int, rir: Int, sets: Int = 3) =
        session("upper_a", date, *Array(sets) { Triple(w, reps, rir as Int?) })

    /** The chest press in its plan's range (5–8) on two Tuesdays, the estimate climbing. */
    private suspend fun twoSessions() {
        press(day(1), 100.0, 8, 2)
        press(day(8), 105.0, 8, 2)
    }

    /** The goal set with the plan on [on]; then the heavy slot (3–5) done twice so it has a state of its own. */
    private suspend fun goalAndTwoHeavySessions(goal: LiftGoal, on: LocalDate): Pair<Double, Int> {
        at(on)
        repo.setLiftGoal(goal, adjust = true)
        val first = repo.liftPlan("upper_a", on.plusDays(1)).items.first { it.exerciseId == "chest_press" }
        val w = first.prescription.weight!!
        // As asked: the slot calibrates on the first session, then climbs the reps.
        session("upper_a", on.plusDays(1), *Array(first.prescription.sets) { Triple(w, first.prescription.reps, 2 as Int?) })
        val second = repo.liftPlan("upper_a", on.plusDays(3)).items.first { it.exerciseId == "chest_press" }
        val w2 = second.prescription.weight!!
        val r2 = second.prescription.reps
        val sets = Array(second.prescription.sets) { i -> if (i == second.prescription.sets - 1) Triple(w2, r2 + 1, 1 as Int?) else Triple(w2, r2, 2 as Int?) }
        session("upper_a", on.plusDays(3), *sets)
        val third = repo.liftPlan("upper_a", on.plusDays(5)).items.first { it.exerciseId == "chest_press" }
        return third.prescription.weight!! to third.prescription.reps
    }

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to when (v) { is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) } } + ("why" to JsonPrimitive("проверка"))),
    )

    private fun setback(o: Outcome?) = o is Outcome.Reduce || o is Outcome.Reset || o is Outcome.LowerReps ||
        (o is Outcome.Hold && (o.reason == HoldReason.NEAR_MISS || o.reason == HoldReason.FIRST_MISS))

    // ---------- what works for Denis (sanity, expected to pass) ----------

    @Test fun sanityTheMachinePressGetsAHeavyAndAVolumeDay() = runBlocking<Unit> {
        assertEquals(listOf("upper_a", "lower_a", "upper_b"), repo.store.programNow()!!.templates.map { it.id })
        assertTrue("chest_press" in GymGoals.candidates(repo))
        repo.setLiftGoal(LiftGoal("chest_press", 150.0), adjust = true)
        val s = repo.store.settingsNow()
        assertEquals(4, s.sets["bench"]); assertEquals(listOf(3, 5), s.reps["bench"]); assertEquals(180, s.rest["bench"])
        assertEquals(listOf(6, 10), s.reps["db_incline"]); assertEquals(150, s.rest["db_incline"])
        assertNull(s.goalPlan!!.addedTo)
        val a = repo.liftPlan("upper_a", day(1)).items.first()
        assertEquals("chest_press" to 4, a.exerciseId to a.prescription.sets)
        assertEquals("chest_press@3-5", a.slot.key)
        val b = repo.liftPlan("upper_b", day(5)).items.first { it.exerciseId == "chest_press" }
        assertEquals("chest_press@6-10", b.slot.key)
        // The profile saved from its screen keeps the goal and the plan's keys.
        repo.updateProfile(repo.store.profileNow()!!.copy(sessionMinutes = 60))
        assertNotNull(repo.store.profileNow()!!.liftGoal)
        assertEquals(4, repo.store.settingsNow().sets["bench"])
    }

    // ---------- findings ----------

    /**
     * Finding A: the same plan exercise on two days (the bench in «Всё тело · А» and in «Верх» of a runner's 3-day plan
     * without shared days; the incline press in «Верх · Б» and «Всё тело · В» of a 5-day plan) has ONE key for its numbers,
     * so the volume scheme written second overwrites the heavy one: no heavy day at all, both days 3 × 6–10 — while the
     * card promised «4 × 3–5» on the first.
     */
    @Test fun theSameLiftOnTwoDaysLosesItsHeavyDay() = runBlocking<Unit> {
        db.close()
        // Gym Mon/Wed/Fri, runs Tue/Thu/Sat: three gym days, serious running, no shared day.
        make(denis.copy(doubleDays = false, trainingDays = setOf(1, 2, 3, 4, 5, 6), gymDays = setOf(1, 3, 5), runDays = setOf(2, 4, 6)), "gym-goal-review-dup")
        val program = repo.store.programNow()!!
        assertEquals(listOf("full_a", "upper_a", "full_b"), program.templates.map { it.id })
        assertEquals(2, program.templates.count { t -> t.slots.any { it.exerciseId == "bench" } })
        val proposal = assertNotNull(GymGoals.propose(CoachActions.places(repo), program, Experience.ADVANCED, "bench"))
        assertTrue(proposal.lines[0].contains("4 × 3–5"), proposal.lines.toString())
        repo.setLiftGoal(LiftGoal("bench", 120.0), adjust = true)
        val s = repo.store.settingsNow()
        val heavy = GymGoal.heavy(Experience.ADVANCED)
        val a = repo.liftPlan("full_a", monday).items.first { it.exerciseId == "bench" }
        assertEquals(listOf(heavy.repMin, heavy.repMax), s.reps["bench"], "reps under the one key: ${s.reps["bench"]}, sets ${s.sets["bench"]}, rest ${s.rest["bench"]}; card said: ${proposal.lines}")
        assertEquals(heavy.sets, a.prescription.sets)
        assertEquals(heavy.repMin to heavy.repMax, a.slot.repMin to a.slot.repMax)
    }

    /**
     * Finding B: the start of the way ([LiftGoal.fromE1rm]) is stamped from the SMOOTHED stat (moves ≤ 5 % a session),
     * while the way itself ([GymGoal.path]) measures the max from the sessions' own bests. The moment the goal is set the
     * bar is already part full; the test-day ladder is built on the smoothed number too, below the max the words quote.
     */
    @Test fun theStartOfTheWayIsTheMaxTheWayMeasures() = runBlocking<Unit> {
        twoSessions()
        press(day(15), 110.0, 8, 2)
        at(day(16))
        repo.setLiftGoal(LiftGoal("chest_press", 160.0), adjust = false)
        val goal = assertNotNull(repo.store.profileNow()!!.liftGoal)
        val path = assertNotNull(GymGoals.path(repo))
        val stat = db.state().stat("chest_press")!!
        // The smoothed estimate lags the sessions' own bests: the start is the max the way measures, not that one.
        assertTrue(path.current!! > stat.e1rm + 3, "current ${path.current} vs smoothed ${stat.e1rm}")
        assertEquals(path.current!!, goal.fromE1rm!!, 1e-9)
        assertTrue(path.share!! < 0.05, "just set, but share = ${path.share} (from ${goal.fromE1rm}, current ${path.current}, words: ${GymGoals.words(repo)})")
    }

    /**
     * Finding C: «подходов станет вдвое меньше при том же весе» — but the taper only halves the sets; the "+" set stays and
     * the progression goes on evaluating, so a good taper session jumps the weight up for the next one.
     */
    @Test fun theTaperKeepsTheWeight() = runBlocking<Unit> {
        twoSessions()
        // Goal on Wed of week 2, the date on Mon of week 4: the taper is +17..+25, the heavy slot settled on +10 and +12.
        val (w, reps) = goalAndTwoHeavySessions(LiftGoal("chest_press", 160.0, date = day(26).toEpochDay()), day(9))
        val g = repo.store.profileNow()!!.liftGoal
        assertTrue(GymGoal.tapering(g, day(17).toEpochDay()) && GymGoal.tapering(g, day(19).toEpochDay()))
        val first = repo.liftPlan("upper_a", day(17)).items.first { it.exerciseId == "chest_press" }
        assertEquals(2, first.prescription.sets)
        assertEquals(w, first.prescription.weight!!, 1e-9)
        // Both sets as asked; the "+" set with reps to spare (it is a "+" set, so he pushes it).
        val (_, outcome) = session("upper_a", day(17), Triple(w, reps, null), Triple(w, reps + 4, null))
        val next = repo.liftPlan("upper_a", day(19)).items.first { it.exerciseId == "chest_press" }
        assertEquals(w, next.prescription.weight!!, 1e-9, "the taper's weight went $w → ${next.prescription.weight} after $outcome; reps $reps → ${next.prescription.reps}")
        assertEquals(reps, next.prescription.reps, "reps climbed in the taper after $outcome")
    }

    /**
     * Finding D: on the goal's day the card says "the test first", but the day's plan still holds the lift's full heavy
     * session, and the only place to log the attempts is that lift — where the engine reads 1-rep attempts as missed
     * sets and steps the slot back after a successful test.
     */
    @Test fun theTestDayDoesNotStepTheSlotBack() = runBlocking<Unit> {
        twoSessions()
        // Goal on Wed of week 2, the test on Sat of week 3.
        val (w, reps) = goalAndTwoHeavySessions(LiftGoal("chest_press", 150.0, date = day(19).toEpochDay()), day(9))
        at(day(19))
        val plan = repo.liftPlan("upper_a", day(19))
        val item = plan.items.first { it.exerciseId == "chest_press" }
        // The day's lift is the test: first, the attempts of the card, not the heavy sets.
        assertEquals("chest_press", plan.items.first().exerciseId)
        assertEquals(Phase.TEST, item.prescription.phase)
        val attempts = item.prescription.attempts!!
        assertEquals(attempts, GymGoals.testDay(repo, repo.store.profileNow()!!.liftGoal!!)!!.attempts)
        assertEquals(attempts.first(), item.prescription.weight!!, 1e-9)
        assertTrue(attempts.all { it > w }, "attempts $attempts vs planned $w")
        // The attempts logged as the lift's sets; both made.
        val (_, outcome) = session("upper_a", day(19), *attempts.map { Triple(it, 1, null as Int?) }.toTypedArray())
        val next = repo.liftPlan("upper_a", day(20)).items.first { it.exerciseId == "chest_press" }
        assertTrue(!setback(outcome), "after a made test ($attempts; planned $w × $reps × ${item.prescription.sets}): $outcome; next $w → ${next.prescription.weight} × $reps → ${next.prescription.reps}")
        assertTrue(item.prescription.sets < GymGoal.heavy(Experience.ADVANCED).sets, "the test day still asks the full heavy session: ${item.prescription.sets} × ${item.prescription.reps} at $w")
    }

    /**
     * Finding E: a goal date falling 9 days or less after the block's planned lighter week begins means the taper IS that
     * lighter week: the taper halves 4 → 2 sets, the deload halves again and takes 10 % off — one set at 90 %, 2 more in
     * reserve, for a week out of every four.
     */
    @Test fun theTaperInTheBlocksLastWeekIsNotADeload() = runBlocking<Unit> {
        twoSessions()
        val today = day(22) // Tuesday of week 4
        assertTrue(ProgramGenerator.isDeloadWeek(repo.store.programNow()!!, today))
        at(today)
        repo.setLiftGoal(LiftGoal("chest_press", 160.0), adjust = true)
        val plain = repo.liftPlan("upper_a", today).items.first { it.exerciseId == "chest_press" }
        assertEquals(2, plain.prescription.sets, "a deload alone: half the heavy sets")
        // The same goal with a date: the taper over the same days.
        repo.setLiftGoal(LiftGoal("chest_press", 160.0, date = today.plusDays(9).toEpochDay()), adjust = false)
        val taper = repo.liftPlan("upper_a", today).items.first { it.exerciseId == "chest_press" }
        assertEquals(2, taper.prescription.sets, "taper + deload: ${taper.prescription.sets} set(s) at ${taper.prescription.weight} with ${taper.prescription.rir} in reserve (deload alone: ${plain.prescription.sets} at ${plain.prescription.weight})")
    }

    /**
     * Finding F: «Вернуть» after the coach changed a goal (same lift, plan adjusted) when the old goal had NO plan: the
     * old goal comes back, but the plan stays adjusted — setLiftGoal keeps the goal plan because the lift is the same.
     */
    @Test fun undoOfAChangedGoalTakesThePlanBack() = runBlocking<Unit> {
        repo.setLiftGoal(LiftGoal("chest_press", 150.0), adjust = false)
        assertNull(repo.store.settingsNow().goalPlan)
        val checked = CoachActions.prepare(repo, call(CoachActions.GYM_GOAL, "exercise" to "chest_press", "kg" to 160), monday)
        val r = assertIs<CoachActions.Ready>(checked, checked.toString())
        val e = CoachActions.make(repo, r)
        assertEquals(4, repo.store.settingsNow().sets["bench"])
        assertTrue(CoachActions.undo(repo, e))
        assertEquals(150.0, repo.store.profileNow()!!.liftGoal!!.kg, 1e-9)
        val s = repo.store.settingsNow()
        assertNull(s.sets["bench"], "the plan stays adjusted after undo: sets ${s.sets["bench"]}, reps ${s.reps["bench"]}, goalPlan ${s.goalPlan != null}")
        assertNull(s.goalPlan)
    }
}
