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
import forma.app.ui.screens.coachContext
import forma.app.ui.screens.inProgress
import forma.core.analytics.Stats
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
import forma.core.strength.Phase
import kotlinx.coroutines.flow.first
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
 * Review probes on cac12ca (the session going on in the coach's brief and its levers, the goal's taper and test, the
 * weight step in the session), on Denis's real-like plan: POWERBUILDING + 1500 m, ADVANCED, gym Tue/Thu/Sat, the bench
 * and the incline press swapped for the machine chest press. The program starts on [monday].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SessionNowReviewTest {
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

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "now-probe")
        store.clear()
        at(monday)
        // Each read of the clock is a second later: sets keep their order, two log entries never share an id.
        repo = TrainingRepository(db, store, now = { clock += 1000; clock })
        repo.completeOnboarding(denis)
        repo.updateSettings {
            it.copy(
                swaps = mapOf("bench" to "chest_press", "db_incline" to "chest_press", "db_shoulder" to "db_bench", "ohp" to "machine_shoulder_press"),
                removed = setOf("plank"),
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to when (v) { is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) } } + ("why" to JsonPrimitive("проверка"))),
    )

    private suspend fun ready(c: CoachCall, today: LocalDate): CoachActions.Ready {
        val checked = CoachActions.prepare(repo, c, today)
        if (checked is CoachActions.Refused) kotlin.test.fail("$c refused: ${checked.why}")
        return assertIs<CoachActions.Ready>(checked)
    }

    private suspend fun plan(id: Long) = repo.planOf(db.workouts().get(id)!!)!!

    /** One session of [templateId] on [date]: the chest press done as [sets] (weight, reps, rir); the rest of the day skipped. */
    private suspend fun session(templateId: String, date: LocalDate, vararg sets: Triple<Double, Int, Int?>) {
        at(date, 18)
        val id = repo.startLift(templateId, date)
        val item = plan(id).items.first { it.exerciseId == "chest_press" }
        for ((w, r, rir) in sets) repo.logSet(id, item, w, r, rir)
        repo.finishLift(id, 7)
    }

    private suspend fun press(date: LocalDate, w: Double, reps: Int, rir: Int, sets: Int = 3) =
        session("upper_a", date, *Array(sets) { Triple(w, reps, rir as Int?) })

    /**
     * The chest press on two Tuesdays, then the goal with the plan (its date far off) and two heavy sessions evaluated as
     * usual (the heavy slot gets its own weight); then the date moved to day(19): the taper is day(10)..day(18), the test
     * day is day(19). Returns the heavy slot's weight and reps as the taper has them.
     */
    private suspend fun onTheWayToTheTest(): Pair<Double, Int> {
        press(day(1), 100.0, 8, 2)
        press(day(8), 105.0, 8, 2)
        val on = day(9)
        at(on)
        repo.setLiftGoal(LiftGoal("chest_press", 150.0, date = day(40).toEpochDay()), adjust = true)
        val first = repo.liftPlan("upper_a", on.plusDays(1)).items.first { it.exerciseId == "chest_press" }
        val w = first.prescription.weight!!
        session("upper_a", on.plusDays(1), *Array(first.prescription.sets) { Triple(w, first.prescription.reps, 2 as Int?) })
        val second = repo.liftPlan("upper_a", on.plusDays(3)).items.first { it.exerciseId == "chest_press" }
        val w2 = second.prescription.weight!!
        val r2 = second.prescription.reps
        val sets = Array(second.prescription.sets) { i -> if (i == second.prescription.sets - 1) Triple(w2, r2 + 1, 1 as Int?) else Triple(w2, r2, 2 as Int?) }
        session("upper_a", on.plusDays(3), *sets)
        assertTrue(db.state().slot("chest_press@3-5")?.weight != null, "the heavy slot has a weight of its own")
        // The date brought nearer, the plan kept: the test is on day(19).
        at(on.plusDays(4))
        repo.setLiftGoal(LiftGoal("chest_press", 150.0, date = day(19).toEpochDay()), adjust = false)
        val third = repo.liftPlan("upper_a", on.plusDays(5)).items.first { it.exerciseId == "chest_press" }
        assertEquals(Phase.TAPER, third.prescription.phase)
        return third.prescription.weight!! to third.prescription.reps
    }

    /** An exercise like [item]'s that is not in the session (what «тренажёр занят» gets). */
    private fun alternativeFor(items: List<LiftItem>, item: LiftItem) =
        repo.alternativesFor(item.exerciseId).first { alt -> items.none { it.exerciseId == alt.id } }

    // ---------- B. the test day ----------

    /**
     * Probe B1: the opener made on the test day, the reserve not marked (nobody taps «мог ещё» on a max attempt, and the
     * card says the second attempt decides). The engine takes the single at the test's rir 0; the way to the goal
     * ([GymGoals.path] over [Stats.e1rmSeries]) takes the same set with the regular-set default of two in reserve, so the
     * test day's point on the way is ≈7 % above the weight lifted — a goal just above the max reads as taken.
     */
    @Test fun aTestSingleWithoutAReserveMarkInflatesTheWayToTheGoal() = runBlocking<Unit> {
        onTheWayToTheTest()
        at(day(19), 18)
        val before = GymGoals.path(repo)!!.current!!
        val id = repo.startLift("upper_a", day(19))
        val test = plan(id).items.first()
        assertEquals(Phase.TEST, test.prescription.phase)
        val opener = test.prescription.attempts!!.first()
        repo.logSet(id, test, opener, 1, null)
        repo.finishLift(id, 8)
        val series = Stats.e1rmSeries(repo.setRows().first(), Catalog["chest_press"], repo.bodyWeightLookup().first())
        val point = series.last()
        val path = GymGoals.path(repo)!!
        val stat = db.state().stat("chest_press")!!
        assertEquals(day(19).toEpochDay(), point.epochDay)
        assertTrue(
            point.value <= opener * 1.01 + 1e-9,
            "the test day: one single at ${opener} kg made; on the way it is ${"%.1f".format(point.value)} kg (max before the test ≈ ${"%.1f".format(before)}, " +
                "the engine's stat after it: e1rm ${"%.1f".format(stat.e1rm)}, best ${"%.1f".format(stat.best)}); the way now says ≈ ${"%.1f".format(path.current!!)}, verdict ${path.verdict}",
        )
    }

    /**
     * Probe B2: «Так себе» on the goal's day (or the first session after being sick — the same [TrainingRepository.liftPlan]
     * path): the session is a lighter one — the screen's banner says «Разгрузка… Не доводи до отказа», the brief says
     * «облегчённая» — and its first exercise is the max test with the full attempts. One of the two is wrong.
     */
    @Test fun aTestDayOnATakSebeDayIsAMaxAttemptUnderTheLighterWeekBanner() = runBlocking<Unit> {
        onTheWayToTheTest()
        at(day(19), 18)
        val plan = repo.liftPlan("upper_a", day(19), forceDeload = true)
        val first = plan.items.first()
        assertFalse(
            plan.deload && first.prescription.phase == Phase.TEST,
            "«Так себе» on the goal's day: a lighter session (deload=${plan.deload}, banner «не доводи до отказа») whose first exercise is the max test " +
                "${first.prescription.attempts} — the test is neither moved nor the banner dropped",
        )
    }

    /**
     * Probe B3: the brief's plan section on the test day: the goal lift's line says «следующий вес <opener>» and then
     * «рабочий вес W кг (на этой тренировке легче)» — the test's weight is heavier than the working weight, not lighter.
     */
    @Test fun theBriefCallsTheTestWeightLighterThanTheWorkingWeight() = runBlocking<Unit> {
        val (w, _) = onTheWayToTheTest()
        at(day(19), 18)
        val opener = repo.liftPlan("upper_a", day(19)).items.first().prescription.weight!!
        assertTrue(opener > w, "the opener $opener is above the working weight $w")
        val ctx = coachContext(repo, day(19))
        val lines = ctx.lines().filter { "[chest_press]" in it }
        val line = lines.firstOrNull { "следующий вес" in it } ?: kotlin.test.fail("no plan line for the press: $lines")
        assertFalse("легче" in line, "the test weight ($opener) is heavier than the working weight ($w), the brief says: $line")
    }

    // ---------- C. the step in the session ----------

    /**
     * Probe C1: the step set during the test-day session (the plan screen, or set_weight_step): [TrainingRepository.setStep]
     * re-steps the test item's weight down to the new step, but not its ladder of attempts (nor the warm-ups): the field
     * starts below the first attempt the hint names, and the second attempt stays off the step.
     */
    @Test fun aStepSetOnTheTestDayPutsTheFirstAttemptOffTheLadder() = runBlocking<Unit> {
        onTheWayToTheTest()
        at(day(19), 18)
        val id = repo.startLift("upper_a", day(19))
        val before = plan(id).items.first()
        assertEquals(Phase.TEST, before.prescription.phase)
        val opener = before.prescription.weight!!
        // A step the opener is not on (4 kg on Denis's machines; 3 or 6 when the ladder happens to be on fours).
        val step = listOf(4.0, 3.0, 6.0).first { opener % it > 1e-9 }
        repo.setStep("chest_press", step, Catalog["chest_press"].step)
        val after = plan(id).items.first()
        val attempts = after.prescription.attempts!!
        assertEquals(
            attempts.first(), after.prescription.weight!!, 1e-9,
            "step $step set mid-session: the field starts at ${after.prescription.weight}, the hint's attempts are $attempts (were ${before.prescription.attempts})",
        )
    }

    // ---------- A. the session going on and its levers ----------

    /**
     * Probe A1: the machine of the first exercise is busy, so Denis does the second one first and finishes it. The screen
     * then goes on to the THIRD exercise ([nextOpen]: the next one after the one just done, in the day's order); the brief
     * puts «← СЕЙЧАС» on the first. Asked «какой сейчас подход?», the coach answers about an exercise the screen is not on.
     */
    @Test fun theBriefsNowMarkDoesNotFollowTheScreenAfterAnExerciseDoneOutOfOrder() = runBlocking<Unit> {
        at(day(1), 18)
        val id = repo.startLift("upper_a", day(1))
        val items = plan(id).items
        assertTrue(items.size >= 3, items.map { it.name }.toString())
        val second = items[1]
        repeat(second.prescription.sets) { repo.logSet(id, second, 40.0, second.prescription.reps, 2) }
        val brief = inProgress(repo)
        val line = brief.lines().first { "← СЕЙЧАС" in it }
        assertTrue(
            "[${items[2].exerciseId}]" in line,
            "after «${second.name}» the screen goes on to «${items[2].name}»; the brief marks: ${line.trim()}",
        )
    }

    /**
     * Probe A2: a swap for today checked while the exercise had no sets, made a moment later when it has one (the lever's
     * make ignores what [TrainingRepository.swapExercise] returns): nothing changes in the session, yet the coach's log
     * says «Сегодня вместо … — …» with a «Вернуть» that then cannot work.
     */
    @Test fun aSwapForTodayAfterASetIsReportedAsMade() = runBlocking<Unit> {
        at(day(1), 18)
        val id = repo.startLift("upper_a", day(1))
        val items = plan(id).items
        val target = items[1]
        val to = alternativeFor(items, target)
        val r = ready(call(CoachActions.NOW_SWAP, "exercise" to target.exerciseId, "new_exercise" to to.name), day(1))
        assertEquals(CoachActions.Mode.AUTO, r.mode)
        repo.logSet(id, target, 40.0, 8, 2)
        // Refused by the session: it fails (the talk says «Не получилось: …») and nothing is logged as made.
        kotlin.test.assertFails { CoachActions.make(repo, r) }
        // The session is right: the exercise with a set stays.
        assertEquals(target.exerciseId, plan(id).items.first { it.position == target.position }.exerciseId)
        assertTrue(repo.store.coachLogNow().none { "вместо" in it.text }, repo.store.coachLogNow().toString())
    }

    /**
     * Probe A3: after a swap for today the brief's «По плану сегодня» line («НАЧАТА, идёт сейчас: …») still lists the
     * plan's exercise, gone from the session, with its numbers; the section below lists the one in its place. Two
     * different lists of today's exercises, both said to be the session going on.
     */
    @Test fun theTodayLineStillListsTheExerciseSwappedOutOfTheSession() = runBlocking<Unit> {
        at(day(1), 18)
        // The session the plan has for this Tuesday (the today line marks only that one).
        val template = repo.store.programNow()!!.week.on(day(1).dayOfWeek.value).first { it.templateId != null }.templateId!!
        val id = repo.startLift(template, day(1))
        val items = plan(id).items
        val target = items[1]
        val to = alternativeFor(items, target)
        CoachActions.make(repo, ready(call(CoachActions.NOW_SWAP, "exercise" to target.exerciseId, "new_exercise" to to.name), day(1)))
        assertEquals(to.id, plan(id).items.first { it.position == target.position }.exerciseId)
        val ctx = coachContext(repo, day(1))
        val today = ctx.lines().firstOrNull { it.startsWith("- Силовая") && "НАЧАТА" in it }
            ?: kotlin.test.fail("no «НАЧАТА» line for today: " + ctx.lines().filter { "Силовая" in it || "Идёт сейчас" in it })
        assertFalse(
            target.name in today && to.name !in today,
            "the today line names «${target.name}» (swapped out for «${to.name}») as the session going on: $today",
        )
    }
}
