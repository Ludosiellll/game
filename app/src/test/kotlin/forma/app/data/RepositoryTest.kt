package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Catalog
import forma.core.model.Equipment
import forma.core.model.Experience
import forma.core.model.LoadKind
import forma.core.model.Muscle
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.RunResult
import forma.core.run.RunType
import forma.core.strength.Outcome
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import forma.core.plan.PlannedSession
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RepositoryTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private var clock = 1_760_000_000_000L

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx)
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
    }

    @After fun tearDown() = db.close()

    private val profile = Profile(
        goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE,
        trainingDays = setOf(1, 3, 5, 6), bodyWeightKg = 80.0,
    )

    @Test fun liftSessionAdaptsNextPrescription() = runBlocking {
        repo.completeOnboarding(profile)
        val program = repo.store.programNow()!!
        val tpl = program.templates.first().id
        // Weeks 1–3 of the program: no deload week in between.
        val day = LocalDate.ofEpochDay(program.startEpochDay)
        assertTrue(program.blockWeeks == 0 || program.blockWeeks > 3)

        // First session: calibration from what was lifted.
        val id = repo.startLift(tpl, day)
        val plan = repo.planOf(db.workouts().get(id)!!)!!
        val squat = plan.items.first { it.exerciseId == "squat" }
        assertTrue(squat.prescription.calibrate)
        repeat(3) { repo.logSet(id, squat, 60.0, 8, null) }
        clock += 50 * 60_000
        val summary = repo.finishLift(id, 7)
        val rep = summary.reports.first { it.exerciseId == "squat" }
        assertIs<Outcome.Calibrated>(rep.outcome)
        assertEquals(50 * 7.0, summary.load)

        // Second session uses the calibrated weight. A novice who does every rep and one more on the "+" set adds weight.
        val id2 = repo.startLift(tpl, day.plusDays(7))
        val sq2 = repo.planOf(db.workouts().get(id2)!!)!!.items.first { it.exerciseId == "squat" }
        val p2 = sq2.prescription
        val w = assertNotNull(p2.weight)
        assertTrue(p2.top)
        repeat(p2.sets - 1) { repo.logSet(id2, sq2, w, p2.reps, null) }
        repo.logSet(id2, sq2, w, p2.reps + 1, null)
        val rep2 = repo.finishLift(id2, 7).reports.first { it.exerciseId == "squat" }
        assertIs<Outcome.AddWeight>(rep2.outcome)
        val sq3 = repo.liftPlan(tpl, day.plusDays(14)).items.first { it.exerciseId == "squat" }
        assertEquals(w + 2.5, sq3.prescription.weight)

        // Third session: exactly the target with nothing to spare consolidates instead of adding.
        val id3 = repo.startLift(tpl, day.plusDays(14))
        val sq3s = repo.planOf(db.workouts().get(id3)!!)!!.items.first { it.exerciseId == "squat" }
        repeat(sq3s.prescription.sets) { repo.logSet(id3, sq3s, sq3s.prescription.weight!!, sq3s.prescription.reps, null) }
        val rep3 = repo.finishLift(id3, 7).reports.first { it.exerciseId == "squat" }
        assertEquals(Outcome.Hold(w + 2.5, sq3s.prescription.reps, forma.core.strength.HoldReason.CONSOLIDATE), rep3.outcome)

        val week = repo.weekFlow(day.plusDays(7).with(java.time.DayOfWeek.MONDAY)).first()
        assertTrue(week.any { d -> d.done.isNotEmpty() })
    }

    @Test fun runsComeBackGentlyAfterABreak() = runBlocking {
        repo.completeOnboarding(profile.copy(runLevel = RunLevel.TRAINED, raceMeters = 5000.0, raceSeconds = 1500.0))
        val day = LocalDate.of(2026, 1, 5)
        val easy = repo.runFor(SessionKind.RUN_EASY, day)
        repo.saveRun(easy, RunResult(1.0, 4, easy.totalSeconds), clock, emptyList(), day)
        val before = repo.store.runStateNow()

        // Six weeks later: shorter, slower, and the hard day is an easy run for now.
        val back = day.plusWeeks(6)
        val easyBack = repo.runFor(SessionKind.RUN_EASY, back)
        assertTrue(easyBack.note.startsWith("Перерыв 6 недель"), easyBack.note)
        assertTrue(easyBack.totalSeconds <= before.easyMinutes * 60 * 0.6 + 150, "${easyBack.totalSeconds}")
        val hard = repo.runFor(SessionKind.RUN_QUALITY, back)
        assertEquals(RunType.EASY, hard.type)
        val long = repo.runFor(SessionKind.RUN_LONG, back)
        assertTrue(long.totalSeconds < before.longMinutes * 60, "${long.totalSeconds}")

        // The break is taken once: the next day plans from the run done on the way back.
        repo.saveRun(easyBack, RunResult(1.0, 4, easyBack.totalSeconds), clock + 42L * 86_400_000, emptyList(), back)
        val next = repo.runFor(SessionKind.RUN_EASY, back.plusDays(2))
        assertTrue(!next.note.startsWith("Перерыв"), next.note)
        assertTrue(repo.store.runStateNow().vdot!! < before.vdot!!)
    }

    @Test fun missedSessionsPausesAndBadDays() = runBlocking {
        repo.completeOnboarding(profile)
        val program = repo.store.programNow()!!
        val plan = (1..7).joinToString("  ") { d -> "$d:" + program.week.on(d).joinToString("+") { it.templateId ?: it.kind.name } }
        // A new user (plan made on Thursday) is not told about Monday and Wednesday.
        assertNull(repo.catchUp(LocalDate.of(2025, 10, 9)), plan)

        // Next week: Monday skipped, Wednesday's intervals done. On free Thursday the missed easy run fits; the
        // missed full-body session does not (Friday is a leg day).
        val monday = LocalDate.of(2025, 10, 13)
        val wed = monday.plusDays(2)
        val q = repo.runFor(SessionKind.RUN_QUALITY, wed)
        repo.saveRun(q, RunResult(1.0, 7, q.totalSeconds, distanceM = 6000.0), clock, emptyList(), wed)
        val s = repo.catchUp(monday.plusDays(3))
        assertEquals(PlannedSession(SessionKind.RUN_EASY), s?.session, plan)
        assertEquals(1, s?.plannedOn)
        assertTrue(repo.trainingKcalPerWeek(80.0, monday.plusDays(3)) >= 6.0 * 80 / 4 - 1)

        // A bad day: the long run becomes an easy one that moves no ladder.
        val light = repo.runFor(SessionKind.RUN_LONG, monday.plusDays(5), light = true)
        assertTrue(light.light)
        assertEquals(RunType.EASY, light.type)

        // Two weeks of vacation from Friday: nothing is missed meanwhile, the block restarts in the week of return.
        repo.pause(monday.plusDays(4), monday.plusDays(17))
        assertTrue(repo.paused(repo.store.settingsNow(), monday.plusDays(5)))
        assertNull(repo.catchUp(monday.plusDays(6)))
        assertEquals(monday.plusDays(14).toEpochDay(), repo.store.programNow()!!.startEpochDay)
        // Back early on Wednesday: the pause ends the day before, the block starts this week.
        repo.resume(monday.plusDays(9))
        assertEquals(monday.plusDays(8).toEpochDay(), repo.store.settingsNow().pauseUntil)
        assertEquals(monday.plusDays(7).toEpochDay(), repo.store.programNow()!!.startEpochDay)
        assertTrue(!repo.paused(repo.store.settingsNow(), monday.plusDays(9)))
    }

    @Test fun exercisesCanBeSwappedAddedAndMadeUp() = runBlocking {
        repo.completeOnboarding(profile)
        val program = repo.store.programNow()!!
        val tpl = program.templates.first()
        val day = LocalDate.ofEpochDay(program.startEpochDay)
        val id = repo.startLift(tpl.id, day)
        fun plan() = runBlocking { repo.planOf(db.workouts().get(id)!!)!! }
        val first = plan().items[0]
        val second = plan().items[1]

        // Today only: the plan stays as it was.
        assertTrue(repo.swapExercise(id, first.position, "leg_press", forever = false))
        assertEquals("leg_press", plan().items[0].exerciseId)
        assertEquals(first.exerciseId, repo.liftPlan(tpl.id, day.plusDays(7)).items[0].exerciseId)
        // Not once its first set is logged.
        repo.logSet(id, plan().items[0], 100.0, 10, null)
        assertTrue(!repo.swapExercise(id, first.position, "hack_squat", forever = false))

        // For good: every next session, even after the plan is rebuilt; and back on request.
        assertTrue(repo.swapExercise(id, second.position, "db_bench", forever = true))
        assertEquals("db_bench", repo.liftPlan(tpl.id, day.plusDays(7)).items[1].exerciseId)
        repo.updateProfile(repo.store.profileNow()!!.copy(sessionMinutes = 90))
        assertEquals("db_bench", repo.liftPlan(tpl.id, day.plusDays(7)).items[1].exerciseId)
        repo.setSwap(second.exerciseId, null)
        assertEquals(second.exerciseId, repo.liftPlan(tpl.id, day.plusDays(7)).items[1].exerciseId)

        // An own exercise added for today is found, planned and evaluated like any other.
        val mine = repo.addCustom(Catalog.makeCustom("custom_test", "Жим лэндмайн", Equipment.BARBELL, Muscle.SHOULDERS, LoadKind.EXTERNAL))
        assertEquals("custom_test", forma.core.model.ExerciseSearch.search("лэндмайн").first().id)
        assertTrue(repo.addToWorkout(id, mine.id))
        val added = plan().items.last()
        assertEquals("custom_test", added.exerciseId)
        assertTrue(added.prescription.calibrate)
        repo.logSet(id, added, 30.0, 10, 2)
        assertTrue(repo.finishLift(id, 7).reports.any { it.exerciseId == "custom_test" })
        // It travels with the cloud documents; removed, it leaves the lists but its history keeps the name.
        assertTrue(repo.store.syncDocs().containsKey("custom_exercises"))
        repo.archiveCustom("custom_test")
        assertTrue(Catalog.all.none { it.id == "custom_test" })
        assertEquals("Жим лэндмайн", Catalog["custom_test"].name)
    }

    @Test fun runIsSavedAndMovesTheLadder() = runBlocking {
        repo.completeOnboarding(profile.copy(runLevel = RunLevel.NONE))
        val w = repo.runFor(SessionKind.RUN_EASY)
        assertEquals(RunType.RUN_WALK, w.type)
        val (id, summary) = repo.saveRun(w, RunResult(1.0, 6, w.totalSeconds, distanceM = 3200.0), clock, emptyList())
        assertTrue(id > 0)
        assertTrue(summary.outcomes.isNotEmpty())
        assertEquals(1, repo.store.runStateNow().walkStage)
        val saved = db.runs().get(id)!!
        assertTrue(saved.avgPace!! > 0)
        // Saving the same run again (a double tap, a retry) changes nothing.
        val again = repo.saveRun(w, RunResult(1.0, 6, w.totalSeconds, distanceM = 3200.0), clock, emptyList())
        assertEquals(id, again.first)
        assertEquals(1, db.workouts().allDone().count { it.kind == Kind.RUN })
        assertEquals(1, repo.store.runStateNow().walkStage)
    }

    @Test fun aRaceGoalOnAStadiumIsPlannedInLapsAndTested() = runBlocking {
        // Tue, Thu, Sat with a run after the gym each day; 1500 m in 5:35 on a 230 m lap.
        repo.completeOnboarding(
            profile.copy(
                aim = forma.core.model.Aim(forma.core.model.LiftFocus.MUSCLE, forma.core.model.RunFocus.SPEED, forma.core.model.Emphasis.BALANCED),
                experience = Experience.INTERMEDIATE, trainingDays = setOf(2, 4, 6), doubleDays = true,
                runGoal = forma.core.model.RunGoal(1500, 335), lapMeters = 230,
            ),
        )
        val program = repo.store.programNow()!!
        val tue = LocalDate.ofEpochDay(program.startEpochDay).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.TUESDAY))
        // Tuesday: race pace, a lap at a time, 51–52 s.
        val pace = repo.runFor(SessionKind.RUN_QUALITY, tue)
        assertEquals(RunType.GOAL_PACE, pace.type)
        assertEquals(230, pace.lapMeters)
        assertEquals(1.0, pace.segments.first { it.kind == forma.core.run.SegmentKind.WORK }.laps)
        val laps = List(pace.reps) { forma.core.run.Lap(51.0 + it % 2, 50.7, 52.1, 1, 1, it + 1) }
        val (id, summary) = repo.saveRun(pace, RunResult(1.0, 7, 35 * 60, workPace = 225.0, laps = laps, reps = pace.reps), clock, emptyList(), tue)
        assertEquals(1, repo.store.runStateNow().goalStage)
        // The laps are kept with the run.
        val stored = AppJson.decodeFromString(RunSummary.serializer(), db.workouts().get(id)!!.summaryJson!!)
        assertEquals(laps, stored.laps)
        assertEquals(laps, summary.laps)
        // Thursday: an easy run after the legs, not cut to 30 minutes (the plan was built for shared days; the longest run
        // of 35 minutes allows 38), in laps.
        val thu = tue.plusDays(2)
        repo.store.setRunState(repo.store.runStateNow().copy(easyMinutes = 36))
        val easy = repo.runFor(SessionKind.RUN_EASY, thu)
        assertEquals(RunType.EASY, easy.type)
        assertEquals(230, easy.lapMeters)
        assertEquals(36 * 60, easy.workSeconds)
        clock += 1000
        repo.saveRun(easy, RunResult(1.0, 4, easy.totalSeconds + 6 * 60), clock, emptyList(), thu)
        // Two runs done and no fitness known yet: the 1500 m time trial, 6.5 laps — not on Saturday, two days after
        // Thursday's legs, but on the next Tuesday with fresh legs.
        val sat = tue.plusDays(7)
        assertEquals(sat, repo.trialDay(tue.plusDays(4)))
        val trial = repo.runFor(SessionKind.RUN_QUALITY, sat)
        assertEquals(RunType.TEST, trial.type)
        assertEquals(1495, trial.testMeters)
        clock += 1000
        val (_, result) = repo.saveRun(trial, RunResult(1.0, 9, 25 * 60, testSeconds = 370), clock, emptyList(), sat)
        assertTrue(result.outcomes.any { it is forma.core.run.RunOutcome.TestTime })
        val st = repo.store.runStateNow()
        assertNotNull(st.vdot)
        assertEquals(sat.toEpochDay(), st.lastTestDay)
        // The next hard day: tempo (twice for every intervals), in whole laps at the new paces.
        val next = repo.runFor(SessionKind.RUN_QUALITY, tue.plusDays(9))
        assertEquals(RunType.TEMPO, next.type)
        assertTrue(next.segments.filter { it.kind == forma.core.run.SegmentKind.WORK }.all { it.laps != null && it.lapSec != null })
    }

    @Test fun setsAndExercisesCanBeChangedInThePlan() = runBlocking {
        repo.completeOnboarding(profile)
        val program = repo.store.programNow()!!
        val t = program.templates.first { it.slots.size >= 3 }
        val day = LocalDate.ofEpochDay(program.startEpochDay)
        val first = t.slots[0]
        val second = t.slots[1]
        // Five sets of the first exercise from now on.
        repo.setPlanSets(first.exerciseId, 5, first.sets)
        assertEquals(5, repo.liftPlan(t.id, day).items.first { it.position == 0 }.prescription.sets)
        // The second one out of the plan: the others keep their places.
        repo.setRemoved(second.exerciseId, true)
        val plan = repo.liftPlan(t.id, day)
        assertTrue(plan.items.none { it.position == 1 })
        assertEquals(t.slots.size - 1, plan.items.size)
        assertEquals(t.slots[2].exerciseId, plan.items.first { it.position == 2 }.slot.exerciseId)
        // Back as in the plan.
        repo.setPlanSets(first.exerciseId, first.sets, first.sets)
        repo.setRemoved(second.exerciseId, false)
        val back = repo.liftPlan(t.id, day)
        assertEquals(first.sets, back.items.first { it.position == 0 }.prescription.sets)
        assertEquals(t.slots.size, back.items.size)
        assertTrue(repo.store.settingsNow().sets.isEmpty())
        // Every exercise out: the session still has them all, never an empty one.
        t.slots.forEach { repo.setRemoved(it.exerciseId, true) }
        assertEquals(t.slots.size, repo.liftPlan(t.id, day).items.size)
    }

    @Test fun anExerciseCanBeTakenOutOfASession() = runBlocking {
        repo.completeOnboarding(profile)
        val program = repo.store.programNow()!!
        val t = program.templates.first { it.slots.size >= 3 }
        val day = LocalDate.ofEpochDay(program.startEpochDay)
        val id = repo.startLift(t.id, day)
        val plan = repo.planOf(db.workouts().get(id)!!)!!
        // Only today: the plan keeps it.
        assertTrue(repo.removeFromWorkout(id, plan.items.last().position, forever = false))
        assertEquals(plan.items.size - 1, repo.planOf(db.workouts().get(id)!!)!!.items.size)
        assertEquals(t.slots.size, repo.liftPlan(t.id, day).items.size)
        // For good: out of the plan too.
        val pos = plan.items[1].position
        assertTrue(repo.removeFromWorkout(id, pos, forever = true))
        assertTrue(t.slots[pos].exerciseId in repo.store.settingsNow().removed)
        assertTrue(repo.liftPlan(t.id, day).items.none { it.position == pos })
        // Not after its first set.
        val firstItem = plan.items.first()
        repo.logSet(id, firstItem, firstItem.prescription.weight ?: 40.0, firstItem.prescription.reps, 2)
        assertTrue(!repo.removeFromWorkout(id, firstItem.position, forever = false))
        // Never the last one left.
        val left = repo.planOf(db.workouts().get(id)!!)!!.items.filter { it.position != firstItem.position }
        left.forEach { repo.removeFromWorkout(id, it.position, forever = false) }
        assertEquals(1, repo.planOf(db.workouts().get(id)!!)!!.items.size)
    }

    @Test fun aSessionIsStartedAndFinishedOnce() = runBlocking {
        repo.completeOnboarding(profile)
        val program = repo.store.programNow()!!
        val day = LocalDate.ofEpochDay(program.startEpochDay)
        // Two taps at once: one session.
        val ids = kotlinx.coroutines.coroutineScope {
            listOf(async { repo.startLift(program.templates[0].id, day) }, async { repo.startLift(program.templates[1].id, day) }).map { it.await() }
        }
        assertEquals(1, ids.toSet().size)
        val id = ids.first()
        val plan = repo.planOf(db.workouts().get(id)!!)!!
        val item = plan.items.first()
        repeat(item.prescription.sets) { repo.logSet(id, item, item.prescription.weight ?: 40.0, item.prescription.reps, 2) }
        val first = repo.finishLift(id, 7)
        val slot = db.state().slot(item.slot.key)
        // Finishing again (a double tap, a retry after the screen closed) evaluates nothing twice.
        val second = repo.finishLift(id, 7)
        assertEquals(first, second)
        assertEquals(slot, db.state().slot(item.slot.key))
    }

    @Test fun aBrokenProgramFromAFriendIsRefused() = runBlocking {
        repo.completeOnboarding(profile)
        val mine = repo.store.programNow()!!
        val other = forma.core.plan.ProgramGenerator.generate(profile.copy(goal = Goal.MUSCLE), LocalDate.of(2026, 10, 5))
        // The week names a session that does not exist: starting it would crash every time.
        val broken = other.copy(templates = other.templates.drop(1))
        assertTrue(!repo.copyProgram(broken, "Иван"))
        assertEquals(mine, repo.store.programNow())
        // Absurd numbers are brought back to sane ones; unknown exercises are left out.
        val wild = other.copy(templates = other.templates.map { t -> t.copy(slots = t.slots.map { it.copy(sets = 500, rir = 40) } + t.slots.first().copy(exerciseId = "nope")) })
        assertTrue(repo.copyProgram(wild, "Иван"))
        val taken = repo.store.programNow()!!
        assertTrue(taken.templates.all { t -> t.slots.all { it.sets <= 10 && it.rir <= 5 && it.exerciseId != "nope" } })
    }
}
