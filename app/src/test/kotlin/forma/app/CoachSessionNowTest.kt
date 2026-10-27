package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.CoachActions
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.SettingsStore
import forma.app.data.Status
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.app.sync.CoachCall
import forma.app.ui.screens.coachContext
import forma.app.ui.screens.exerciseOf
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.LiftGoal
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.strength.DoneSet
import forma.core.strength.Progression
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The coach and the session going on now (from Denis's talk on 27.10: «я начал или нет, какой сейчас подход» got «в чате
 * не видно»; «сделал 9 раз» changed the rating of a workout four days before): the coach sees the session as its screen
 * has it, changes it for today only, and a rating said mid-session is not made on a past workout at once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CoachSessionNowTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    /** Tuesday 27 Oct 2026: «Верх · А» by the plan. */
    private val day = LocalDate.of(2026, 10, 27)
    private var clock = day.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val denis = Profile(
        goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
        bodyWeightKg = 71.0, lapMeters = 230, runGoal = RunGoal(1500, 330), aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
        doubleDays = true, gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
    )

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "coach-session-now")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock += 1000; clock })
        repo.completeOnboarding(denis)
    }

    @After fun tearDown() = db.close()

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to when (v) { is Number -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) } } + ("why" to JsonPrimitive("проверка"))),
    )

    private suspend fun ready(c: CoachCall): CoachActions.Ready {
        val checked = CoachActions.prepare(repo, c, day)
        if (checked is CoachActions.Refused) kotlin.test.fail("$c refused: ${checked.why}")
        return assertIs<CoachActions.Ready>(checked)
    }

    private suspend fun plan(id: Long) = repo.planOf(db.workouts().get(id)!!)!!

    @Test fun theBriefSaysWhatIsGoingOnAndWhichSetIsNext() = runBlocking<Unit> {
        assertTrue("Сейчас тренировка не идёт" in coachContext(repo, day))
        val template = repo.store.programNow()!!.week.on(day.dayOfWeek.value).first { it.templateId != null }.templateId!!
        val id = repo.startLift(template, day)
        val first = plan(id).items.first()
        repo.logSet(id, first, 60.0, 9, 3)
        val ctx = coachContext(repo, day)
        assertTrue("НАЧАТА, идёт сейчас" in ctx, ctx.take(1500))
        assertTrue("## Идёт сейчас" in ctx, ctx.take(3000))
        // The exercise now, its set done and the next one the app gives.
        val line = ctx.lines().first { "[${first.exerciseId}]" in it && "сделано 1 из" in it }
        assertTrue("60 кг × 9 (запас 3)" in line && "← СЕЙЧАС" in line, line)
        assertTrue(ctx.lines().any { it.trim().startsWith("Подход 2: приложение даёт") }, ctx.take(3000))
    }

    @Test fun anExerciseIsSwappedRemovedAndAddedForTodayOnlyAndComesBack() = runBlocking<Unit> {
        val id = repo.startLift("upper_a", day)
        val before = repo.store.settingsNow()
        val items = plan(id).items
        val target = items.drop(1).first { it.exerciseId != "db_bench" && Catalog[it.exerciseId].compound }
        // Swapped for today: made at once, the plan's own exercises as they were.
        val swap = ready(call(CoachActions.NOW_SWAP, "exercise" to target.exerciseId, "new_exercise" to "Жим гантелей лёжа"))
        assertEquals(CoachActions.Mode.AUTO, swap.mode)
        val e1 = CoachActions.make(repo, swap)
        assertEquals("db_bench", plan(id).items.first { it.position == target.position }.exerciseId)
        assertEquals(before.swaps, repo.store.settingsNow().swaps)
        assertTrue(CoachActions.undo(repo, e1))
        assertEquals(target, plan(id).items.first { it.position == target.position })
        // Taken out for today, then put back at its place.
        val e2 = CoachActions.make(repo, ready(call(CoachActions.NOW_REMOVE, "exercise" to target.exerciseId)))
        assertTrue(plan(id).items.none { it.position == target.position })
        assertEquals(before.removed, repo.store.settingsNow().removed)
        assertTrue(CoachActions.undo(repo, e2))
        assertEquals(items, plan(id).items)
        // Added at the end for today, and taken back.
        val e3 = CoachActions.make(repo, ready(call(CoachActions.NOW_ADD, "exercise" to "Подъём на носки стоя")))
        assertEquals("calf_raise", plan(id).items.last().exerciseId)
        assertEquals(before.added, repo.store.settingsNow().added)
        assertTrue(CoachActions.undo(repo, e3))
        assertEquals(items, plan(id).items)
    }

    @Test fun anExerciseWithSetsIsNotSwappedAndItsSetsNeverGoBelowTheDone() = runBlocking<Unit> {
        val id = repo.startLift("upper_a", day)
        val first = plan(id).items.first()
        repo.logSet(id, first, 60.0, 5, 2)
        repo.logSet(id, first, 60.0, 5, 2)
        val no = CoachActions.prepare(repo, call(CoachActions.NOW_SWAP, "exercise" to first.exerciseId, "new_exercise" to "Жим гантелей лёжа"), day)
        assertIs<CoachActions.Refused>(no)
        assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call(CoachActions.NOW_SETS, "exercise" to first.exerciseId, "sets" to 1), day))
        val e = CoachActions.make(repo, ready(call(CoachActions.NOW_SETS, "exercise" to first.exerciseId, "sets" to 2)))
        assertEquals(2, plan(id).items.first().prescription.sets)
        assertTrue(CoachActions.undo(repo, e))
        assertEquals(first.prescription.sets, plan(id).items.first().prescription.sets)
        // Nothing going on: the session levers say so.
        repo.finishLift(id, 6)
        assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call(CoachActions.NOW_ADD, "exercise" to "Подъём на носки стоя"), day))
    }

    @Test fun theGoalLiftTakenOutForTodayWaitsForThePerson() = runBlocking<Unit> {
        val id = repo.startLift("upper_a", day)
        val lift = plan(id).items.first()
        repo.setLiftGoal(LiftGoal(lift.exerciseId, 100.0), adjust = false)
        val r = ready(call(CoachActions.NOW_REMOVE, "exercise" to lift.exerciseId))
        assertEquals(CoachActions.Mode.CONFIRM, r.mode)
        assertTrue("Важно:" in r.text, r.text)
    }

    @Test fun aRatingSaidMidSessionOrOfDaysAgoIsNotMadeAtOnce() = runBlocking<Unit> {
        // «Низ» four days ago, rated 5.
        db.workouts().insert(
            WorkoutEntity(epochDay = day.toEpochDay() - 4, kind = Kind.LIFT, templateId = "lower_a", title = "Низ", startedAt = clock - 4 * 86_400_000L, finishedAt = clock - 4 * 86_400_000L + 3_600_000, status = Status.DONE, rpe = 5, durationSec = 3600),
        )
        val old = ready(call(CoachActions.RATE, "which" to "last_gym", "rpe" to 7))
        assertEquals(CoachActions.Mode.CONFIRM, old.mode, old.text)
        assertTrue("не сегодня и не вчера" in old.text, old.text)
        repo.startLift("upper_a", day)
        val mid = ready(call(CoachActions.RATE, "which" to "last_gym", "rpe" to 7))
        assertEquals(CoachActions.Mode.CONFIRM, mid.mode)
        assertTrue("не идущая сейчас" in mid.text, mid.text)
    }

    @Test fun aStepSetMidSessionWorksInItAtOnce() = runBlocking<Unit> {
        val id = repo.startLift("upper_a", day)
        val pull = plan(id).items.first { it.exerciseId == "lat_pulldown" }
        val e = CoachActions.make(repo, ready(call(CoachActions.STEP, "exercise" to "lat_pulldown", "step_kg" to 4)))
        assertTrue(e.text.isNotEmpty())
        val now = plan(id).items.first { it.position == pull.position }
        assertEquals(4.0, now.step, 1e-9)
        now.prescription.weight?.let { w -> assertEquals(0.0, w % 4.0, 1e-9, "weight $w on the 4 kg step") }
        // The screen's hints after a set go by the person's step, not the catalog's (a 4 kg stack never gets 5).
        val ex = exerciseOf("lat_pulldown", repo.store.settingsNow().steps)
        assertEquals(4.0, ex.step, 1e-9)
        val p = now.prescription.copy(weight = 48.0, reps = 8, sets = 3, rir = 2, calibrate = false)
        val next = Progression.nextSet(ex, p, listOf(DoneSet(48.0, 14, 4)), 71.0)!!
        assertEquals(0.0, next.weight % 4.0, 1e-9, "next ${next.weight}")
        assertFalse(next.weight == 48.0, "a set this easy moves the weight: ${next.weight}")
    }
}
