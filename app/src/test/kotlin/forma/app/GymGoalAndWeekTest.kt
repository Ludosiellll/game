package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.CoachActions
import forma.app.data.FormaDatabase
import forma.app.data.GymGoals
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
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
import forma.core.plan.SessionKind
import forma.core.strength.GymGoal
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
 * The coach's week ("убери бег", "бег во вторник и четверг") and the goal in the gym ("хочу жать 100"): made only with
 * «Принять», said with what they cost the goals, taken back with «Вернуть»; the plan trains the goal's lift for it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class GymGoalAndWeekTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    /** Monday 26 Oct 2026. */
    private val day = LocalDate.of(2026, 10, 26)
    private var clock = day.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Like Denis: the gym Tue/Thu/Sat, running Mon/Thu/Sat, 1500 m in 5:30 on a stadium. */
    private val denis = Profile(
        goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
        bodyWeightKg = 71.0, lapMeters = 230, runGoal = RunGoal(1500, 330), aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
        doubleDays = true, gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
    )

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "gym-goal-week")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock++ })
        repo.completeOnboarding(denis)
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

    private suspend fun runDays() = repo.store.programNow()!!.week.days.filter { d -> d.sessions.any { it.isRun } }.map { it.day }.toSet()

    // ---------- the week ----------

    @Test fun gymOnlyIsSaidToCostTheRaceAndComesBack() = runBlocking<Unit> {
        val r = ready(call(CoachActions.WEEK, "sides" to "gym"))
        assertEquals(CoachActions.Mode.CONFIRM, r.mode)
        assertTrue("без бега" in r.text && "Важно:" in r.text && "останется без бега" in r.text && "2 пробежки" in r.text, r.text)
        val e = CoachActions.make(repo, r)
        assertEquals(RunFocus.NONE, repo.store.profileNow()!!.aim.run)
        assertTrue(runDays().isEmpty())
        // The race goal and the stadium stay for when running comes back.
        assertEquals(RunGoal(1500, 330), repo.store.profileNow()!!.runGoal)
        assertTrue(CoachActions.undo(repo, e))
        assertEquals(denis.aim, repo.store.profileNow()!!.aim)
        assertEquals(setOf(1, 4, 6), runDays())
    }

    @Test fun runDaysMoveAndTheGymStays() = runBlocking<Unit> {
        val r = ready(call(CoachActions.WEEK, "run_days" to "вторник и четверг, суббота"))
        assertTrue("бег — вт, чт, сб" in r.text && "Важно" !in r.text, r.text)
        CoachActions.make(repo, r)
        assertEquals(setOf(2, 4, 6), repo.store.profileNow()!!.runDays)
        assertEquals(setOf(2, 4, 6), repo.store.profileNow()!!.gymDays)
        // One run a week for 1500 m is said to cost the goal.
        val one = ready(call(CoachActions.WEEK, "run_days" to "сб"))
        assertTrue("Одна пробежка" in one.text, one.text)
    }

    @Test fun daysAreReadAsPeopleWriteThem() {
        assertEquals(setOf(2, 4), CoachActions.weekDays("вторник и четверг"))
        assertEquals(setOf(2, 4, 6), CoachActions.weekDays("2,4,6"))
        assertEquals(setOf(3, 7), CoachActions.weekDays("Среда; воскресенье"))
        assertEquals(emptySet(), CoachActions.weekDays(""))
        assertNull(CoachActions.weekDays("завтра"))
    }

    // ---------- the goal in the gym ----------

    @Test fun aGoalOnTheBenchTrainsItHeavyAndOnASecondDay() = runBlocking<Unit> {
        assertTrue("bench" in GymGoals.candidates(repo))
        val r = ready(call(CoachActions.GYM_GOAL, "exercise" to "bench", "kg" to 100))
        assertEquals(CoachActions.Mode.CONFIRM, r.mode)
        assertTrue("тяжёлый день" in r.text && "второй день" in r.text, r.text)
        val before = repo.store.settingsNow()
        val e = CoachActions.make(repo, r)
        val goal = assertNotNull(repo.store.profileNow()!!.liftGoal)
        assertEquals("bench", goal.exerciseId)
        assertEquals(day.toEpochDay(), goal.setDay)
        val s = repo.store.settingsNow()
        val heavy = GymGoal.heavy(Experience.INTERMEDIATE)
        assertEquals(heavy.sets, s.sets["bench"])
        assertEquals(listOf(heavy.repMin, heavy.repMax), s.reps["bench"])
        assertEquals(heavy.restSec, s.rest["bench"])
        // Bench is on one day of the plan: it is added to the other upper day as its second, lighter day.
        val second = repo.store.programNow()!!.templates.first { t -> t.id != "upper_a" && "bench" in s.added[t.id].orEmpty() }
        val added = CoachActions.places(repo).first { it.templateId == second.id && it.exerciseId == "bench" }
        assertEquals(6 to 10, added.tuned.repMin to added.tuned.repMax)
        assertEquals(GymGoal.VOLUME.restSec, added.tuned.restSec)
        // The heavy day puts the goal's lift first among the main lifts.
        val plan = repo.liftPlan("upper_a", day)
        assertEquals("bench", plan.items.first().exerciseId)
        // «Вернуть»: the goal and the plan as they were.
        assertTrue(CoachActions.undo(repo, e))
        assertNull(repo.store.profileNow()!!.liftGoal)
        val back = repo.store.settingsNow()
        assertEquals(before.sets, back.sets)
        assertEquals(before.reps, back.reps)
        assertEquals(before.added, back.added)
        assertNull(back.goalPlan)
    }

    @Test fun whatThePersonChangedAfterTheGoalStaysWhenItGoes() = runBlocking<Unit> {
        repo.setLiftGoal(LiftGoal("bench", 100.0), adjust = true)
        // The person sets bench to 5 sets themselves afterwards.
        repo.updateSettings { it.copy(sets = it.sets + ("bench" to 5)) }
        repo.setLiftGoal(null, adjust = false)
        val s = repo.store.settingsNow()
        assertEquals(5, s.sets["bench"])
        // Its reps and rest were still the goal's: they go back.
        assertNull(s.reps["bench"])
        assertNull(s.goalPlan)
    }

    @Test fun takingOutOrCuttingTheGoalLiftIsSaid() = runBlocking<Unit> {
        repo.setLiftGoal(LiftGoal("bench", 100.0), adjust = false)
        val cut = ready(call(CoachActions.SETS, "exercise" to "bench", "sets" to 1))
        assertTrue("Важно:" in cut.text && "медленнее к цели" in cut.text, cut.text)
        val out = ready(call(CoachActions.REMOVE, "exercise" to "bench"))
        assertTrue("Важно:" in out.text && "цель в зале" in out.text, out.text)
        val off = ready(call(CoachActions.WEEK, "sides" to "run"))
        assertTrue("Цель в зале" in off.text, off.text)
    }

    @Test fun aGoalOffThePlanOrOnASingleJointLiftIsRefused() = runBlocking<Unit> {
        // The barbell deadlift is not in this plan: no goal on a lift like it instead.
        val none = assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call(CoachActions.GYM_GOAL, "exercise" to "deadlift", "kg" to 150), day))
        assertTrue("нет в плане" in none.why, none.why)
        val single = CoachActions.prepare(repo, call(CoachActions.GYM_GOAL, "exercise" to "lateral_raise", "kg" to 20), day)
        assertTrue(single is CoachActions.Refused, single.toString())
    }

    @Test fun theDaysBeforeTheDateHalveTheLiftsSets() = runBlocking<Unit> {
        val date = day.plusDays(5)
        repo.setLiftGoal(LiftGoal("bench", 100.0, date = date.toEpochDay()), adjust = true)
        val heavy = GymGoal.heavy(Experience.INTERMEDIATE).sets
        val taper = repo.liftPlan("upper_a", day).items.first { it.exerciseId == "bench" }
        assertEquals(GymGoal.taperSets(heavy), taper.prescription.sets)
        // After the date, as before.
        val after = repo.liftPlan("upper_a", date.plusDays(1)).items.first { it.exerciseId == "bench" }
        assertEquals(heavy, after.prescription.sets)
    }

    @Test fun theCoachReadsTheGoalsAndTheWeek() = runBlocking<Unit> {
        repo.setLiftGoal(LiftGoal("bench", 100.0), adjust = false)
        val ctx = coachContext(repo, day)
        assertTrue("## Цели человека" in ctx, ctx.take(2000))
        assertTrue("Неделя: зал — вт, чт, сб; бег — пн, чт, сб." in ctx, ctx.take(2000))
        assertTrue("1500 м за 5:30" in ctx)
        assertTrue("Цель — Жим штанги лёжа 100 кг" in ctx || "Цель — " in ctx, ctx.take(2500))
        assertTrue(repo.store.programNow()!!.week.days.any { d -> d.sessions.any { it.kind == SessionKind.LIFT } })
    }
}
