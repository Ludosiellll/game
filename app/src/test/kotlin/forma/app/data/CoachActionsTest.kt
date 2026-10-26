package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.sync.CoachCall
import forma.core.model.Catalog
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LoadKind
import forma.core.model.Profile
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.RunType
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the coach (the AI) may change: only through the app's own edits, within limits, nonsense refused, the gym plan,
 * the goal and the stadium waiting for «Принять», big weight moves and a busy day too, every change with its way back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CoachActionsTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private val day = LocalDate.of(2026, 10, 26)
    private var clock = day.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "coach-actions")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock++ })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 70.0, lapMeters = 230, runGoal = RunGoal(1500, 330),
            ),
        )
        store.setRunState(store.runStateNow().copy(walkStage = -1, runs = 4, easyMinutes = 30, lastRunDay = day.toEpochDay() - 2))
    }

    @After fun tearDown() = db.close()

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) } + ("why" to JsonPrimitive("проверка"))),
    )

    private suspend fun ready(c: CoachCall) = assertIs<CoachActions.Ready>(CoachActions.prepare(repo, c), c.toString())
    private suspend fun refused(c: CoachCall) = assertIs<CoachActions.Refused>(CoachActions.prepare(repo, c), c.toString())

    /** A plan exercise with a weight (not a hold, not body weight alone), its slot set to [kg]. */
    private suspend fun weighted(kg: Double): CoachActions.Place {
        val p = CoachActions.places(repo).first { !it.added && Catalog[it.exerciseId].load == LoadKind.EXTERNAL }
        db.state().upsertSlot(SlotStateEntity(p.tuned.key, p.tuned.exerciseId, kg, p.tuned.repMin, 0, 0, 3, clock))
        return p
    }

    @Test fun theDeclarationsFitWhatGeminiTakes() {
        val names = CoachActions.declarations.map { ((it as JsonObject)["name"] as JsonPrimitive).content }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.size <= 40)
        for (d in CoachActions.declarations) {
            val o = d as JsonObject
            assertTrue(((o["name"] as JsonPrimitive).content).matches(Regex("^[a-z_]{1,40}$")))
            assertTrue(((o["description"] as JsonPrimitive).content).length <= 800)
            val params = o["parameters"] as JsonObject
            assertTrue(params.toString().length <= 4000)
            // Every change says why: the card shows it.
            assertTrue((params["required"] as JsonArray).any { (it as JsonPrimitive).content == "why" }, o.toString())
        }
    }

    @Test fun nonsenseIsRefused() = runBlocking<Unit> {
        val ex = weighted(60.0).exerciseId
        listOf(
            call("drop_table"),
            call(CoachActions.FEEL, "feel" to "great"),
            call(CoachActions.SICK, "pause_days" to 40),
            call(CoachActions.PAUSE, "days" to 0), call(CoachActions.PAUSE, "days" to 90), call(CoachActions.PAUSE, "days" to "много"),
            call(CoachActions.RESUME),
            call(CoachActions.RATE, "rpe" to 5),
            call(CoachActions.EASY, "minutes" to 300), call(CoachActions.EASY, "minutes" to 5), call(CoachActions.EASY, "minutes" to 30),
            call(CoachActions.STRIDES, "count" to 20, "weeks" to 2), call(CoachActions.LONG, "weeks" to 30),
            call(CoachActions.STEP, "exercise" to ex, "step_kg" to 0), call(CoachActions.STEP, "exercise" to "летающий жим", "step_kg" to 2),
            call(CoachActions.WEIGHT, "exercise" to ex, "kg" to 200), call(CoachActions.WEIGHT, "exercise" to ex, "kg" to 20),
            call(CoachActions.WEIGHT, "exercise" to "нет такого", "kg" to 50),
            call(CoachActions.SETS, "exercise" to ex, "sets" to 20), call(CoachActions.REPS, "exercise" to ex, "min" to 10, "max" to 5),
            call(CoachActions.REST, "exercise" to ex, "seconds" to 5), call(CoachActions.REMOVE, "exercise" to "нет такого"),
            call(CoachActions.SWAP, "exercise" to ex, "new_exercise" to "абракадабра"),
            call(CoachActions.ADD, "session" to "нет такой", "exercise" to ex),
            call(CoachActions.GOAL, "meters" to 1500, "time" to "1:00"), call(CoachActions.GOAL, "meters" to 1500, "time" to "пять"),
            call(CoachActions.GOAL, "meters" to 1500, "time" to "5:20", "date" to "2020-01-01"),
            call(CoachActions.LAP, "meters" to 5000), call(CoachActions.LAP, "meters" to 230),
            call(CoachActions.FORGET, "text" to "чего не было"),
        ).forEach { refused(it) }
    }

    @Test fun aLighterDayAndBack() = runBlocking<Unit> {
        val r = ready(call(CoachActions.FEEL, "feel" to "low"))
        assertEquals(CoachActions.Mode.AUTO, r.mode)
        val e = CoachActions.make(repo, r)
        assertEquals(Feel.LOW, repo.store.settingsNow().feelOn(day))
        assertEquals("проверка", e.why)
        assertTrue(CoachActions.undo(repo, e))
        assertEquals(Feel.UNSET, repo.store.settingsNow().feelOn(day))
        // Taken back once: not again.
        assertFalse(CoachActions.undo(repo, repo.store.coachLogNow().last()))
    }

    @Test fun sickWithAPauseAndBack() = runBlocking<Unit> {
        val e = CoachActions.make(repo, ready(call(CoachActions.SICK, "pause_days" to 3)))
        val s = repo.store.settingsNow()
        assertEquals(Feel.SICK, s.feelOn(day))
        assertTrue(repo.paused(s, day.plusDays(2)))
        assertTrue(CoachActions.undo(repo, e))
        val after = repo.store.settingsNow()
        assertFalse(repo.paused(after, day.plusDays(1)))
        assertNull(repo.sickEnd(after, day.plusDays(1)))
    }

    @Test fun theEasyRunGrowsWithinTheLimitsAndComesBack() = runBlocking<Unit> {
        val e = CoachActions.make(repo, ready(call(CoachActions.EASY, "minutes" to 35)))
        assertEquals(35, repo.store.runStateNow().easyMinutes)
        // Up to +15 % (at least 5 minutes) at a time: 60 is too much.
        refused(call(CoachActions.EASY, "minutes" to 60))
        assertTrue(CoachActions.undo(repo, e))
        assertEquals(30, repo.store.runStateNow().easyMinutes)
        // Moved on by a run since: the coach's change is not taken back over it.
        val e2 = CoachActions.make(repo, ready(call(CoachActions.EASY, "minutes" to 34)))
        repo.store.setRunState(repo.store.runStateNow().copy(easyMinutes = 37))
        assertFalse(CoachActions.undo(repo, e2))
        assertEquals(37, repo.store.runStateNow().easyMinutes)
    }

    @Test fun weightsMoveALittleByThemselvesMoreOnAccept() = runBlocking<Unit> {
        val p = weighted(60.0)
        val small = ready(call(CoachActions.WEIGHT, "exercise" to p.exerciseId, "kg" to 62.5))
        assertEquals(CoachActions.Mode.AUTO, small.mode)
        val e = CoachActions.make(repo, small)
        assertEquals(62.5, db.state().slot(p.tuned.key)!!.weight)
        // The same exercise again this week, even a little: the person says.
        assertEquals(CoachActions.Mode.CONFIRM, ready(call(CoachActions.WEIGHT, "exercise" to p.exerciseId, "kg" to 65)).mode)
        // More than 10 %: the person says; more than 25 %: never.
        refused(call(CoachActions.WEIGHT, "exercise" to p.exerciseId, "kg" to 80))
        assertTrue(CoachActions.undo(repo, e))
        assertEquals(60.0, db.state().slot(p.tuned.key)!!.weight)
        assertEquals(CoachActions.Mode.CONFIRM, ready(call(CoachActions.WEIGHT, "exercise" to p.exerciseId, "kg" to 70)).mode)
        // Lifted since (the weight moved on): the coach's change stays the person's progress.
        val e2 = CoachActions.make(repo, ready(call(CoachActions.WEIGHT, "exercise" to p.exerciseId, "kg" to 62.5)))
        db.state().upsertSlot(db.state().slot(p.tuned.key)!!.copy(weight = 65.0))
        assertFalse(CoachActions.undo(repo, e2))
        assertEquals(65.0, db.state().slot(p.tuned.key)!!.weight)
    }

    @Test fun theWeightStepOfTheGym() = runBlocking<Unit> {
        val p = weighted(60.0)
        val default = Catalog[p.exerciseId].step
        val step = if (default == 2.0) 2.5 else 2.0
        val e = CoachActions.make(repo, ready(call(CoachActions.STEP, "exercise" to p.exerciseId, "step_kg" to step)))
        assertEquals(step, repo.store.settingsNow().steps[p.exerciseId])
        assertTrue(CoachActions.undo(repo, e))
        assertNull(repo.store.settingsNow().steps[p.exerciseId])
        // A step a third of the weight is likely a slip: the person says.
        assertEquals(CoachActions.Mode.CONFIRM, ready(call(CoachActions.STEP, "exercise" to p.exerciseId, "step_kg" to 20)).mode)
    }

    @Test fun theGymPlanChangesOnlyOnAccept() = runBlocking<Unit> {
        val p = CoachActions.places(repo).first { !it.added }
        val sets = ready(call(CoachActions.SETS, "exercise" to p.exerciseId, "sets" to p.tuned.sets + 1))
        assertEquals(CoachActions.Mode.CONFIRM, sets.mode)
        assertNull(repo.store.settingsNow().sets[p.key], "nothing before «Принять»")
        val e = CoachActions.make(repo, sets)
        assertEquals(p.tuned.sets + 1, repo.store.settingsNow().sets[p.key])
        assertTrue(CoachActions.undo(repo, e))
        assertNull(repo.store.settingsNow().sets[p.key])

        val to = repo.alternativesFor(p.exerciseId).first { it.id != p.exerciseId }
        val swap = ready(call(CoachActions.SWAP, "exercise" to p.exerciseId, "new_exercise" to to.id))
        assertEquals(CoachActions.Mode.CONFIRM, swap.mode)
        val e2 = CoachActions.make(repo, swap)
        assertEquals(to.id, repo.store.settingsNow().swaps[p.planId])
        assertTrue(CoachActions.undo(repo, e2))
        assertNull(repo.store.settingsNow().swaps[p.planId])

        val remove = ready(call(CoachActions.REMOVE, "exercise" to p.exerciseId))
        assertEquals(CoachActions.Mode.CONFIRM, remove.mode)
        val e3 = CoachActions.make(repo, remove)
        assertTrue(p.planId in repo.store.settingsNow().removed)
        assertTrue(CoachActions.undo(repo, e3))
        assertFalse(p.planId in repo.store.settingsNow().removed)
    }

    @Test fun theGoalAndTheStadiumOnAccept() = runBlocking<Unit> {
        val goal = ready(call(CoachActions.GOAL, "meters" to 1500, "time" to "5:20"))
        assertEquals(CoachActions.Mode.CONFIRM, goal.mode)
        val e = CoachActions.make(repo, goal)
        assertEquals(320, repo.store.profileNow()!!.runGoal!!.seconds)
        assertTrue(CoachActions.undo(repo, e))
        assertEquals(330, repo.store.profileNow()!!.runGoal!!.seconds)

        val lap = ready(call(CoachActions.LAP, "meters" to 400))
        assertEquals(CoachActions.Mode.CONFIRM, lap.mode)
        val e2 = CoachActions.make(repo, lap)
        assertEquals(400, repo.store.profileNow()!!.lapMeters)
        assertTrue(CoachActions.undo(repo, e2))
        assertEquals(230, repo.store.profileNow()!!.lapMeters)
        CoachActions.make(repo, ready(call(CoachActions.LAP, "meters" to 0)))
        assertNull(repo.store.profileNow()!!.lapMeters)
    }

    @Test fun aBusyDayWaitsForThePerson() = runBlocking<Unit> {
        repeat(CoachActions.AUTO_A_DAY) { i ->
            val r = ready(call(CoachActions.REMEMBER, "text" to "заметка $i"))
            assertEquals(CoachActions.Mode.AUTO, r.mode)
            CoachActions.make(repo, r)
        }
        assertEquals(CoachActions.Mode.CONFIRM, ready(call(CoachActions.REMEMBER, "text" to "ещё одна")).mode)
        assertEquals(CoachActions.AUTO_A_DAY, repo.store.settingsNow().coachNotes.size)
    }

    @Test fun rememberedAndForgotten() = runBlocking<Unit> {
        CoachActions.make(repo, ready(call(CoachActions.REMEMBER, "text" to "Гантели в зале через 2,5 кг")))
        refused(call(CoachActions.REMEMBER, "text" to "гантели в зале через 2,5 кг"))
        val e = CoachActions.make(repo, ready(call(CoachActions.FORGET, "text" to "гантели")))
        assertTrue(repo.store.settingsNow().coachNotes.isEmpty())
        assertTrue(CoachActions.undo(repo, e))
        assertEquals(listOf("Гантели в зале через 2,5 кг"), repo.store.settingsNow().coachNotes)
    }

    @Test fun strideCountAndALongRunGoIntoThePlan() = runBlocking<Unit> {
        val program = repo.store.programNow()!!
        val monday = day.minusDays(day.dayOfWeek.value - 1L).plusWeeks(1)
        val week = (0L..6L).map { monday.plusDays(it) }
        val easy = week.filter { d -> program.week.on(d.dayOfWeek.value).any { it.kind == SessionKind.RUN_EASY } }
        assertTrue(easy.isNotEmpty())
        // Strides where the plan has them: the coach's count.
        val withStrides = easy.firstOrNull { repo.runFor(SessionKind.RUN_EASY, it).title.contains("ускорени") }
        if (withStrides != null) {
            CoachActions.make(repo, ready(call(CoachActions.STRIDES, "count" to 6, "weeks" to 3)))
            assertTrue(repo.runFor(SessionKind.RUN_EASY, withStrides).title.contains("6 ускорений"), repo.runFor(SessionKind.RUN_EASY, withStrides).title)
        }
        // A long easy run: one easy day of the week becomes it, within the long run's own limits.
        CoachActions.make(repo, ready(call(CoachActions.LONG, "weeks" to 3)))
        val runs = easy.map { repo.runFor(SessionKind.RUN_EASY, it) }
        assertTrue(runs.any { it.type == RunType.LONG }, runs.map { it.title }.toString())
        assertNotNull(repo.store.settingsNow().coachLong)
    }
}
