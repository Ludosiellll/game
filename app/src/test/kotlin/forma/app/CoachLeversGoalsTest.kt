package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.CoachActions
import forma.app.data.FormaDatabase
import forma.app.data.GymGoals
import forma.app.data.Kind
import forma.app.data.SetEntity
import forma.app.data.SettingsStore
import forma.app.data.Status
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.app.sync.CoachCall
import forma.app.ui.screens.coachContext
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
import forma.core.strength.GymGoal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
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

/** The coach's levers against the goals (a review's probes on 1cbf326, each a finding fixed): every change checked against them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CoachLeversGoalsTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    /** Monday 26 Oct 2026. */
    private val day = LocalDate.of(2026, 10, 26)
    private var clock = day.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Like Denis: the gym Tue/Thu/Sat, running Mon/Thu/Sat, 1500 m in 5:30 on a stadium, gym and running equally. */
    private val denis = Profile(
        goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
        bodyWeightKg = 71.0, lapMeters = 230, runGoal = RunGoal(1500, 330), aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
        doubleDays = true, gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
    )

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "lever-probe")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock++ })
        repo.completeOnboarding(denis)
        store.setRunState(store.runStateNow().copy(walkStage = -1, runs = 6, easyMinutes = 30, lastRunDay = day.toEpochDay() - 2))
    }

    @After fun tearDown() = db.close()

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to when (v) { is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) } } + ("why" to JsonPrimitive("проверка"))),
    )

    private suspend fun ready(c: CoachCall): CoachActions.Ready {
        val checked = CoachActions.prepare(repo, c, day)
        if (checked is CoachActions.Refused) kotlin.test.fail("$c refused: ${checked.why}")
        return assertIs<CoachActions.Ready>(checked, c.toString())
    }

    private suspend fun refused(c: CoachCall) = assertIs<CoachActions.Refused>(CoachActions.prepare(repo, c, day), c.toString())

    // ---------- 1. A pause shorter than a week that covers the race day (or the gym goal's test day) is made at once, no word ----------

    @Test fun aPauseOverTheRaceDayIsSaidAndWaitsForThePerson() = runBlocking<Unit> {
        // The race is on Thursday; "уезжаю на 5 дней" on Monday.
        repo.store.setProfile(repo.store.profileNow()!!.copy(runGoal = RunGoal(1500, 330, day.plusDays(3).toEpochDay())))
        val r = ready(call(CoachActions.PAUSE, "days" to 5))
        assertTrue("забег" in r.text.lowercase() && "Важно" in r.text, "a pause over the race day says nothing of it: ${r.text}")
        assertEquals(CoachActions.Mode.CONFIRM, r.mode, "a change that costs the goal must wait for «Принять»")
    }

    @Test fun aPauseOverTheGymTestDayIsSaidAndWaitsForThePerson() = runBlocking<Unit> {
        repo.setLiftGoal(LiftGoal("bench", 100.0, date = day.plusDays(4).toEpochDay()), adjust = true)
        val r = ready(call(CoachActions.PAUSE, "days" to 6))
        assertTrue("Важно" in r.text, "a pause over the goal's test day says nothing of it: ${r.text}")
        assertEquals(CoachActions.Mode.CONFIRM, r.mode)
    }

    // ---------- 2. A run goal set while the week has no running at all is not said to be without running ----------

    @Test fun aRunGoalOnAWeekWithoutRunningIsSaidToBeWithoutRunning() = runBlocking<Unit> {
        CoachActions.make(repo, ready(call(CoachActions.WEEK, "sides" to "gym")))
        assertEquals(RunFocus.NONE, repo.store.profileNow()!!.aim.run)
        val r = ready(call(CoachActions.GOAL, "meters" to 1500, "time" to "5:20", "date" to day.plusWeeks(6).toString()))
        assertTrue("Важно" in r.text && "бега" in r.text, "a goal with no running in the week, and the card says the plan will be recounted: ${r.text}")
    }

    // ---------- 3. The goal lift swapped on its heavy day: no word, and «во всех тренировках» is not true ----------

    @Test fun swappingTheGoalLiftOnItsHeavyDaySaysWhatItCosts() = runBlocking<Unit> {
        repo.setLiftGoal(LiftGoal("bench", 100.0), adjust = true)
        val heavy = GymGoal.heavy(Experience.INTERMEDIATE)
        assertEquals(heavy.sets, repo.store.settingsNow().sets["bench"])
        val second = repo.store.programNow()!!.templates.first { "bench" in repo.store.settingsNow().added[it.id].orEmpty() }
        val r = ready(call(CoachActions.SWAP, "exercise" to "bench", "new_exercise" to "db_bench"))
        assertTrue("Важно" in r.text, "the goal's lift swapped away and the card did not say so: ${r.text}")
        val e = CoachActions.make(repo, r)
        val s = repo.store.settingsNow()
        assertEquals("db_bench", s.swaps["bench"])
        // «Во всех тренировках» is true: the lighter day added for the goal is swapped too, no half of the bench is left.
        assertTrue("db_bench" in s.added[second.id].orEmpty() && "bench" !in s.added[second.id].orEmpty(), s.added.toString())
        assertTrue(CoachActions.places(repo).none { it.exerciseId == "bench" })
        // «Вернуть»: both places as they were.
        assertTrue(CoachActions.undo(repo, e))
        val back = repo.store.settingsNow()
        assertNull(back.swaps["bench"])
        assertTrue("bench" in back.added[second.id].orEmpty())
        val light = CoachActions.places(repo).first { it.templateId == second.id && it.exerciseId == "bench" }
        assertEquals(GymGoal.VOLUME.repMin to GymGoal.VOLUME.repMax, light.tuned.repMin to light.tuned.repMax)
    }

    // ---------- 4. The goal lift taken out of its heavy day only: no word ----------

    @Test fun removingTheGoalLiftFromItsHeavyDaySaysWhatItCosts() = runBlocking<Unit> {
        repo.setLiftGoal(LiftGoal("bench", 100.0), adjust = true)
        val r = ready(call(CoachActions.REMOVE, "exercise" to "bench", "session" to "upper_a"))
        assertTrue("Важно" in r.text, "the goal's heavy day taken out and the card did not say so: ${r.text}")
    }

    // ---------- 5. A goal on another lift with plan=false: the card says «План не меняю», the old goal's plan is reverted ----------

    @Test fun aGoalOnAnotherLiftWithPlanFalseIsHonestAboutThePlan() = runBlocking<Unit> {
        repo.setLiftGoal(LiftGoal("bench", 100.0), adjust = true)
        val heavy = GymGoal.heavy(Experience.INTERMEDIATE)
        assertEquals(heavy.sets, repo.store.settingsNow().sets["bench"])
        assertTrue("squat" in CoachActions.places(repo).map { it.exerciseId })
        val r = ready(call(CoachActions.GYM_GOAL, "exercise" to "squat", "kg" to 150, "plan" to false))
        CoachActions.make(repo, r)
        val s = repo.store.settingsNow()
        assertEquals("squat", repo.store.profileNow()!!.liftGoal!!.exerciseId)
        val benchKept = s.sets["bench"] == heavy.sets && s.reps["bench"] == listOf(heavy.repMin, heavy.repMax)
        assertTrue(benchKept || "План не меняю" !in r.text, "card: «${r.text}» — but bench sets/reps now ${s.sets["bench"]} / ${s.reps["bench"]}")
    }

    // ---------- 6. «Только зал», then «верни бег»: the week comes back with the gym first, not equally as it was ----------

    @Test fun runningTakenBackAfterGymOnlyKeepsThePriorityOfTheWeek() = runBlocking<Unit> {
        val before = repo.store.programNow()!!
        CoachActions.make(repo, ready(call(CoachActions.WEEK, "sides" to "gym")))
        val back = ready(call(CoachActions.WEEK, "sides" to "both", "run_days" to "пн, чт, сб"))
        CoachActions.make(repo, back)
        val p = repo.store.profileNow()!!
        assertEquals(setOf(1, 4, 6), p.runDays)
        assertEquals(setOf(2, 4, 6), p.gymDays)
        val after = repo.store.programNow()!!
        fun slots(pr: forma.core.plan.Program) = pr.templates.map { t -> t.id to t.slots.map { "${it.exerciseId}×${it.sets}" } }
        assertEquals(Emphasis.BALANCED, p.aim.emphasis,
            "the week came back as «упор на зал»: caps ${before.runCaps} → ${after.runCaps}; templates ${slots(before)} → ${slots(after)}; card: ${back.text}")
    }

    @Test fun theGymTakenBackAfterRunOnlyKeepsItsFocus() = runBlocking<Unit> {
        repo.updateProfile(repo.store.profileNow()!!.copy(aim = Aim(LiftFocus.STRENGTH, RunFocus.ENDURANCE, Emphasis.BALANCED)))
        CoachActions.make(repo, ready(call(CoachActions.WEEK, "sides" to "run")))
        assertEquals(LiftFocus.NONE, repo.store.profileNow()!!.aim.lift)
        val back = ready(call(CoachActions.WEEK, "sides" to "both", "gym_days" to "вт, чт, сб"))
        CoachActions.make(repo, back)
        assertEquals(LiftFocus.STRENGTH, repo.store.profileNow()!!.aim.lift, "the gym came back for another purpose; card: ${back.text}")
    }

    // ---------- 8. A gym goal asked on a week without the gym: refused with a hint that points the wrong way ----------

    /** «Не хочу тренировать икры» with a 1500 m goal: the last calf work out of the plan is said to cost the race. */
    @Test fun theRunnersLastCalfWorkTakenOutIsSaid() = runBlocking<Unit> {
        val calves = CoachActions.places(repo).filter { forma.core.model.Catalog[it.exerciseId].pattern == forma.core.model.Pattern.CALF }
        assertTrue(calves.size >= 2, "a runner's plan holds standing and seated calf raises: ${calves.map { it.exerciseId }}")
        // One of two out: nothing to say; the last one: «Важно» with what keeps the goal.
        val first = ready(call(CoachActions.REMOVE, "exercise" to calves[0].exerciseId))
        assertTrue("Важно" !in first.text, first.text)
        CoachActions.make(repo, first)
        val last = ready(call(CoachActions.REMOVE, "exercise" to calves[1].exerciseId))
        assertTrue("Важно:" in last.text && "ахилл" in last.text && "1500 м" in last.text, last.text)
    }

    @Test fun aGymGoalOnAWeekWithoutTheGymPointsToTheWeek() = runBlocking<Unit> {
        CoachActions.make(repo, ready(call(CoachActions.WEEK, "sides" to "run")))
        val no = refused(call(CoachActions.GYM_GOAL, "exercise" to "bench", "kg" to 100))
        assertTrue("зал" in no.why.lowercase() && "set_week" in no.why, "the refusal should say the week has no gym: «${no.why}»")
    }

    // ---------- 7. A goal already within reach: the higher weight proposed is a one-rep max, the goal was for 5 reps ----------

    @Test fun aDoneGoalProposesAHigherWeightInTheGoalsReps() = runBlocking<Unit> {
        val id = db.workouts().insert(
            WorkoutEntity(epochDay = day.toEpochDay() - 2, kind = Kind.LIFT, templateId = "upper_a", title = "Верх · А", startedAt = clock, finishedAt = clock, status = Status.DONE, rpe = 7, durationSec = 3600),
        )
        db.sets().insert(SetEntity(workoutId = id, exerciseId = "bench", slotKey = "bench", position = 0, weight = 110.0, reps = 5, rir = 0, targetWeight = 110.0, targetReps = 5, doneAt = clock))
        val goal = LiftGoal("bench", 100.0, 5)
        val current = assertNotNull(GymGoals.path(repo, goal)?.current)
        val no = refused(call(CoachActions.GYM_GOAL, "exercise" to "bench", "kg" to 100, "reps" to 5))
        assertTrue("например" in no.why, no.why)
        val suggested = Regex("например ([\\d,]+) кг").find(no.why)!!.groupValues[1].replace(',', '.').toDouble()
        // A weight for 5 reps cannot be above the one-rep max.
        assertTrue(suggested < current, "goal 100 × 5, max ≈ ${current.toInt()}: «${no.why}»")
    }

    // ---------- Sanity: the declarations still fit the edge function's limits ----------

    @Test fun theDeclarationsStillFit() {
        val names = CoachActions.declarations.map { ((it as JsonObject)["name"] as JsonPrimitive).content }
        assertTrue(names.size <= 40)
        for (d in CoachActions.declarations) {
            val o = d as JsonObject
            assertTrue(((o["description"] as JsonPrimitive).content).length <= 800, o["name"].toString())
            assertTrue((o["parameters"] as JsonObject).toString().length <= 4000, o["name"].toString())
            assertTrue((((o["parameters"] as JsonObject)["required"]) as JsonArray).any { (it as JsonPrimitive).content == "why" })
        }
    }

    @Test fun theBriefSaysTheWeekAsTheLeverSeesIt() = runBlocking<Unit> {
        val ctx = coachContext(repo, day)
        assertTrue("Неделя: зал — вт, чт, сб; бег — пн, чт, сб." in ctx, ctx.take(1500))
    }
}
