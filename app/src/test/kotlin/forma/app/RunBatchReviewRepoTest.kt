package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.AppJson
import forma.app.data.FormaDatabase
import forma.app.data.GoalProgress
import forma.app.data.RunSummary
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.Lap
import forma.core.run.LapEdit
import forma.core.run.RunLog
import forma.core.run.RunOutcome
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import forma.core.run.Vdot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The review of the loop / week-cap / re-rating batch, through the real repository with Denis's numbers (1500 m in 5:30,
 * a stadium set as 230 m whose loop GPS measures at about 205 m): what a runner would expect. Each test but the sanity
 * one failed on c8820fe and passes since.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RunBatchReviewRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private var clock = 0L

    private fun at(d: LocalDate, hour: Int = 12) { clock = d.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }

    private val goal = RunGoal(1500, 330)
    private val denis = Profile(
        name = "Денис", birthYear = 2008, goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE,
        trainingDays = setOf(1, 2, 4, 6), bodyWeightKg = 71.0, heightCm = 183, lapMeters = 230, runGoal = goal,
        aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
        doubleDays = true, gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
    )
    private val measured = 205

    private val after26 = RunState(
        walkStage = -1, easyMinutes = 20, longMinutes = 35, runs = 5, qualityCount = 1, lastRunDay = 20752, easyRpes = listOf(7),
        recent = listOf(RunLog(20738, 24), RunLog(20738, 2), RunLog(20748, 25), RunLog(20749, 17), RunLog(20752, 36)),
    )
    private val reps26 = listOf(46.399, 49.982, 48.993, 47.286, 46.614)

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "run-batch-probe")
        store.clear()
        at(LocalDate.of(2026, 10, 12))
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(denis)
        repo.store.setProgram(repo.store.programNow()!!.copy(startEpochDay = 20738, createdEpochDay = 20739))
    }

    @After fun tearDown() = db.close()

    private suspend fun summaryOf(id: Long) = AppJson.decodeFromString(RunSummary.serializer(), db.workouts().get(id)!!.summaryJson!!)
    private suspend fun planOf(id: Long) = AppJson.decodeFromString(RunWorkout.serializer(), db.runs().get(id)!!.planJson)

    /** 29.10: the trial «6,5 кругов (1495 м)», run on the loop GPS measures at 204,6 m: 1332 m in 300 s. */
    private suspend fun trialOnTheLoop(): Long {
        repo.store.setRunState(after26)
        val d = LocalDate.of(2026, 10, 29)
        at(d, 14)
        val w = repo.runFor(SessionKind.RUN_QUALITY, d)
        assertEquals(RunType.TEST, w.type, w.title)
        assertEquals(1495, w.testMeters)
        val work = w.segments.first { it.kind == SegmentKind.WORK }
        val secs = 300.0
        val laps = (0 until 7).map { i ->
            val part = if (i == 0) 0.5 else 1.0
            Lap(secs / 6.5 * part, work.lapSec!! * part, work.lapSec!! * part, i + 1, 7, 1, part)
        }
        val result = RunResult(1.0, 9, 1700, distanceM = 6.5 * 230 + 2000, laps = laps, testSeconds = secs.toInt(), loopM = 204.6)
        val (id, summary) = repo.saveRun(w, result, clock - 1700_000L, emptyList(), d)
        assertTrue(summary.outcomes.any { it is RunOutcome.LoopShorter }, "${summary.outcomes}")
        assertTrue(summary.outcomes.any { it is RunOutcome.TestTime && it.meters in 1320..1340 }, "${summary.outcomes}")
        return id
    }

    /** 26.10: race pace, 5 × 1 lap, with 9 warm-up and 6 cool-down laps pressed too (20 laps, 4600 m on the setting). */
    private suspend fun racePaceWithAllLaps(loopM: Double?): Long {
        repo.store.setRunState(after26.copy(qualityCount = 0, runs = 4, lastRunDay = 20749))
        val d = LocalDate.of(2026, 10, 26)
        at(d, 14)
        val w = repo.runFor(SessionKind.RUN_QUALITY, d)
        assertEquals("Темп цели · 5 × 1 круг", w.title)
        val reps = reps26.mapIndexed { i, s -> Lap(s, 49.91, 51.29, 1, 1, i + 1) }
        val laps = List(9) { Lap(84.0, number = it + 1) } + reps + List(6) { Lap(82.0, number = it + 1) }
        val pace = reps.sumOf { it.seconds } / (5 * 230 / 1000.0)
        return repo.saveRun(
            w, RunResult(1.0, 7, 2142, distanceM = 20 * 230.0, workPace = pace, laps = laps, reps = 5, loopM = loopM), clock - 2142_000L, emptyList(), d,
        ).first
    }

    /**
     * Finding: right after the trial read on the loop (stored on 205 m while the setting is still 230), «Сегодня» offers
     * to count «past runs» again on 230 m — the very run just read on the loop is the one «on another lap». The runner
     * sees two cards that contradict each other (set 205 / recount onto 230).
     */
    @Test fun aRunReadOnTheMeasuredLoopIsNotOfferedForRecountOntoTheSettingItWasReadOff() = runBlocking<Unit> {
        trialOnTheLoop()
        assertEquals(230, repo.store.profileNow()!!.lapMeters)
        assertTrue(repo.store.settingsNow().loopSeen != null, "the loop card is asked")
        val other = repo.runsOnOtherLap()
        assertNull(other, "the recount card is offered too: runsOnOtherLap = $other (n runs on that lap → onto ${repo.store.profileNow()!!.lapMeters} m)")
    }

    /**
     * Finding: the recount that card offers undoes the loop reading in the history — the trial's distance back to 1495 m,
     * its plan back on 230 — while the decision (VDOT) stays the one from 1332 m and the debrief still says the laps were
     * counted on 205 m. History and progress no longer agree.
     */
    @Test fun recountingOntoTheSettingKeepsTheHistoryConsistentWithTheDecision() = runBlocking<Unit> {
        val id = trialOnTheLoop()
        val vdotAfter = db.runs().get(id)!!.vdotAfter!!
        // The card's button, as the runner would tap it (the recount onto the 230 m set now, off the 205 the run holds).
        repo.recountLaps(measured)
        val s = summaryOf(id)
        val tt = s.outcomes.filterIsInstance<RunOutcome.TestTime>().single()
        val fromTrial = (Vdot.fromRace(tt.meters.toDouble(), tt.seconds.toDouble()) * 2).roundToInt() / 2.0
        assertEquals(
            vdotAfter, fromTrial, 0.6,
            "after the recount the history says «${tt.meters} м за ${tt.seconds} с» (VDOT $fromTrial) and the plan is on ${planOf(id).lapMeters} m, " +
                "but the progress was decided on 1332 m (VDOT $vdotAfter); outcomes still ${s.outcomes.filterIsInstance<RunOutcome.LoopShorter>()}",
        )
    }

    /**
     * Finding: a lap put right in the history of a run read on the loop (a press forgotten in the warm-up: one lap split
     * into two) sends the stored distance back to the setting's metres — 21 laps become 4600 m (20 × 230), not 4305
     * (21 × 205). The re-decision reads the distance from the record on 230 m, where the laps no longer match it.
     */
    @Test fun aLapEditOnARunReadOnTheLoopKeepsTheDistanceOnTheLoop() = runBlocking<Unit> {
        val id = racePaceWithAllLaps(loopM = measured.toDouble())
        assertEquals(measured, planOf(id).lapMeters)
        assertEquals(20 * measured.toDouble(), db.runs().get(id)!!.distanceM!!, 1.0)
        val stored = summaryOf(id).laps
        repo.editRunLaps(id, LapEdit.split(stored, 0))
        assertEquals(21, summaryOf(id).laps.size)
        val run = db.runs().get(id)!!
        assertEquals(
            21 * measured.toDouble(), run.distanceM!!, 1.0,
            "21 laps on the ${planOf(id).lapMeters} m the run is stored on, but the distance is ${run.distanceM} m (pace ${run.avgPace})",
        )
    }

    /**
     * Finding: the trial read on the loop (TestTime 1332 m) is left off «Путь к цели»: the goal path keeps only trials
     * within 3 % of the goal distance, which 1495 m was and 1332 m is not. The runner's trial — the one event of the goal
     * path — is missing from the progress screen and the report («Контрольного забега на 1500 м ещё не было»).
     */
    @Test fun theTrialReadOnTheLoopStaysOnTheWayToTheGoal() = runBlocking<Unit> {
        trialOnTheLoop()
        val path = GoalProgress.of(db.workouts().allDone(), db.runs().observeAll().first(), goal.meters)
        assertEquals(1, path.trials.size, "trials on the way to the goal: ${path.trials}")
    }

    /**
     * Finding: «Оставить 230 м» does not stick. The next run with GPS on the same stadium measures the same loop and the
     * question comes back on «Сегодня» (and the run is read on 205 m anyway).
     */
    @Test fun theLoopPutAsideIsNotAskedAgainAfterTheNextRun() = runBlocking<Unit> {
        racePaceWithAllLaps(loopM = measured.toDouble())
        assertTrue(repo.store.settingsNow().loopSeen != null)
        // The runner keeps 230 m («Оставить 230 м»).
        repo.keepLapLength()
        // Saturday's free run on the same stadium, GPS fitting the same loop.
        val sat = LocalDate.of(2026, 10, 31)
        at(sat, 18)
        repo.saveRun(
            repo.freeRun(230), RunResult(1.0, 4, 1517, distanceM = 18 * 230.0, laps = List(18) { Lap(84.3, number = it + 1) }, loopM = 204.8),
            clock - 1517_000L, emptyList(), sat,
        )
        assertNull(repo.store.settingsNow().loopSeen, "asked again after being put aside")
    }

    /**
     * Sanity: the loop reading survives a rating put right on the last run (the re-decision still reads it on the loop)
     * and the lap length set to the loop afterwards leaves distance, pace and plan as they were.
     */
    @Test fun aRatingAndTheLapLengthPutRightKeepTheLoopReading() = runBlocking<Unit> {
        val id = trialOnTheLoop()
        val before = db.runs().get(id)!!
        assertTrue(repo.setRating(id, 8))
        val s = summaryOf(id)
        assertTrue(s.outcomes.any { it is RunOutcome.LoopShorter } && s.outcomes.any { it is RunOutcome.TestTime && it.meters in 1320..1340 }, "${s.outcomes}")
        repo.setLapMeters(measured)
        val after = db.runs().get(id)!!
        assertEquals(before.distanceM!!, after.distanceM!!, 1.0)
        assertEquals(before.vdotAfter!!, after.vdotAfter!!, 1e-9)
        assertEquals(measured, planOf(id).lapMeters)
        assertTrue(summaryOf(id).outcomes.any { it is RunOutcome.TestTime && it.meters in 1320..1340 }, "${summaryOf(id).outcomes}")
        assertNull(repo.runsOnOtherLap()?.takeIf { it.second == measured }, "${repo.runsOnOtherLap()}")
        assertTrue(abs(after.distanceM!! - before.distanceM!!) < 1.0)
    }
}
