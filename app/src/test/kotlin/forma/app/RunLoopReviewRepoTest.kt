package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.AppJson
import forma.app.data.FormaDatabase
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
import forma.core.run.RunLog
import forma.core.run.RunOutcome
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunType
import forma.core.run.SegmentKind
import forma.core.run.Track
import forma.core.run.Vdot
import forma.core.text.Ru
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The review of one runner's real runs (Denis: 1500 m in 5:30, runs Mon/Thu/Sat on a stadium set as 230 m whose loop GPS
 * measures at about 205 m, the gym Tue/Thu/Sat), replayed through the real repository with the dates and numbers of his
 * phone: what a coach would decide on what he did. Each test but the sanity one failed before the fix.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RunLoopReviewRepoTest {
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

    /** The loop as the app's own GPS fit measured it on his runs. */
    private val measured = 205

    /** His running progress as his phone holds it after 26.10 (run_state): no trial yet, the first race-pace step. */
    private val after26 = RunState(
        walkStage = -1, easyMinutes = 20, longMinutes = 35, runs = 5, qualityCount = 1, lastRunDay = 20752, easyRpes = listOf(7),
        recent = listOf(RunLog(20738, 24), RunLog(20738, 2), RunLog(20748, 25), RunLog(20749, 17), RunLog(20752, 36)),
    )

    /** 26.10, «Темп цели · 5 × 1 круг»: his five reps, one lap each, as pressed (seconds). */
    private val reps26 = listOf(46.399, 49.982, 48.993, 47.286, 46.614)

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "run-loop-review")
        store.clear()
        at(LocalDate.of(2026, 10, 12))
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(denis)
        // The program as on his phone: the block from Monday 12.10, made on 13.10.
        repo.store.setProgram(repo.store.programNow()!!.copy(startEpochDay = 20738, createdEpochDay = 20739))
    }

    @After fun tearDown() = db.close()

    private suspend fun summaryOf(id: Long) = AppJson.decodeFromString(RunSummary.serializer(), db.workouts().get(id)!!.summaryJson!!)

    /**
     * 22.10 (Thursday, a hard day, but the first run after his two free runs of 12.10): the plan gave an easy 25 minutes
     * «после перерыва». Saved rated [rpe]; his phone had 7.
     */
    private suspend fun easy22(rpe: Int): Long {
        repo.store.setRunState(repo.store.runStateNow().copy(runs = 2, lastRunDay = 20738, recent = listOf(RunLog(20738, 24), RunLog(20738, 2))))
        val d = LocalDate.of(2026, 10, 22)
        at(d, 15)
        val w = repo.runFor(SessionKind.RUN_QUALITY, d)
        assertEquals(RunType.EASY, w.type, w.title)
        assertEquals(25 * 60, w.workSeconds, w.title)
        val laps = List(18) { Lap(84.3, 82.8, null, it + 1) }
        return repo.saveRun(w, RunResult(1.0, rpe, 1517, distanceM = 18 * 230.0, laps = laps, cadence = 156), clock - 1517_000L, emptyList(), d).first
    }

    /** 26.10 (Monday): «Темп цели · 5 × 1 круг» as his phone planned and scored it. */
    private suspend fun racePace26(laps: List<Lap> = reps26.mapIndexed { i, s -> Lap(s, 49.91, 51.29, 1, 1, i + 1) }): Long {
        val d = LocalDate.of(2026, 10, 26)
        at(d, 14)
        val w = repo.runFor(SessionKind.RUN_QUALITY, d)
        assertEquals(RunType.GOAL_PACE, w.type, w.title)
        assertEquals("Темп цели · 5 × 1 круг", w.title)
        val pace = laps.filter { it.rep != null }.sumOf { it.seconds } / (5 * 230 / 1000.0)
        return repo.saveRun(w, RunResult(1.0, 7, 2142, distanceM = 20 * 230.0, workPace = pace, laps = laps, reps = 5, cadence = 158), clock - 2142_000L, emptyList(), d).first
    }

    /**
     * His trial, due Thursday 29.10, is «Контрольный забег · 6,5 кругов (1495 м)» on the 230 m setting. On the loop as GPS
     * measures it, 6,5 laps are 1332 m. Run flat out at 3:45/km (300 s), it was scored as 1495 m in 300 s: VDOT ≈ 54 and
     * the 5:30 goal reached, for a runner whose real VDOT from that run is ≈ 47 (the goal is 49). Now it is read on the
     * loop the tracker fitted in it — or, without GPS in it, on the loop an earlier run measured.
     */
    @Test fun theTrialOn29_10IsScoredOnTheLoopGpsMeasured() = runBlocking<Unit> {
        trial(loopInRun = true)
    }

    @Test fun aTrialWithoutGpsIsScoredOnTheLoopAnEarlierRunMeasured() = runBlocking<Unit> {
        repo.updateSettings { it.copy(loopSeen = forma.app.data.LoopSeen(measured, 230)) }
        trial(loopInRun = false)
    }

    private suspend fun trial(loopInRun: Boolean) {
        repo.store.setRunState(after26)
        val d = LocalDate.of(2026, 10, 29)
        at(d, 14)
        val w = repo.runFor(SessionKind.RUN_QUALITY, d)
        assertEquals(RunType.TEST, w.type, w.title)
        assertEquals(1495, w.testMeters)
        val work = w.segments.first { it.kind == SegmentKind.WORK }
        assertEquals(6.5, work.laps)
        // 6,5 laps of the loop at 3:45/km: 1332 m in 300 s. The laps as pressed: a half lap, then six.
        val secs = 300.0
        val laps = (0 until 7).map { i ->
            val part = if (i == 0) 0.5 else 1.0
            Lap(secs / 6.5 * part, work.lapSec!! * part, work.lapSec!! * part, i + 1, 7, 1, part)
        }
        val result = RunResult(1.0, 9, 1700, distanceM = 6.5 * 230 + 2000, laps = laps, testSeconds = secs.toInt(), loopM = if (loopInRun) 204.6 else null)
        val (id, summary) = repo.saveRun(w, result, clock - 1700_000L, emptyList(), d)
        assertTrue(summary.outcomes.any { it is RunOutcome.LoopShorter }, "${summary.outcomes}")
        assertTrue(summary.outcomes.any { it is RunOutcome.TestTime && it.meters in 1320..1340 }, "${summary.outcomes}")
        val st = repo.store.runStateNow()
        val real = Vdot.fromRace(6.5 * measured, secs)
        val goalVdot = Vdot.fromRace(goal.meters.toDouble(), goal.seconds.toDouble())
        // Saturday's easy run after it, on a leg day.
        val sat = LocalDate.of(2026, 10, 31)
        at(sat, 18)
        val easy = repo.runFor(SessionKind.RUN_EASY, sat)
        val limit = easy.segments.first { it.kind == SegmentKind.RUN }.pace!!.fast
        assertFalse(
            RunOutcome.GoalReached in summary.outcomes,
            "the trial (${w.title}) scored ${summary.outcomes}: VDOT ${st.vdot}, runs.vdot_after ${db.runs().get(id)!!.vdotAfter}. On the $measured m loop 6,5 laps are " +
                "${(6.5 * measured).toInt()} m in ${secs.toInt()} s = ${Ru.pace(secs / (6.5 * measured / 1000))}/km, VDOT ${"%.1f".format(real)} against the goal's " +
                "${"%.1f".format(goalVdot)}: not reached. Saturday's easy run is now «${easy.title}» with the limit ${Ru.pace(limit.toDouble())}/km = " +
                "${Ru.lap(Track.lapSec(limit.toDouble(), 230))} a lap, which on his loop is ${Ru.pace(limit * 230.0 / measured)}/km — on 22.10 he ran easy at " +
                "${Ru.pace(1517.0 / (18 * measured / 1000.0))}/km (84 s a lap).",
        )
    }

    /**
     * A touch mid-rep with the phone in the hand (30 s into a 50-s lap, past the 45 % that makes a press a lap)
     * makes the rep «fast» and the session «TooFastRepeat». The laps of reps cannot be put right in the app (LapEdit
     * keeps them), and even when the repository is handed the laps put right, the verdict and the progress stay on the
     * stray press; the history keeps its pace too.
     */
    @Test fun repLapsPutRightAfterTheRunDecideItAgain() = runBlocking<Unit> {
        repo.store.setRunState(after26.copy(qualityCount = 0, runs = 4, lastRunDay = 20749))
        val stray = Lap(30.0, 49.91, 51.29, 1, 1, 1)
        val laps = listOf(Lap(68.0, number = 1), stray) + (2..5).map { Lap(50.5, 49.91, 51.29, 1, 1, it) }
        val id = racePace26(laps)
        assertTrue(RunOutcome.TooFastRepeat in summaryOf(id).outcomes, "the stray press decides: ${summaryOf(id).outcomes}")
        val fixed = laps.map { if (it.rep == 1) it.copy(seconds = 50.5) else it }
        repo.editRunLaps(id, fixed)
        assertEquals(fixed, summaryOf(id).laps, "the laps are stored as put right")
        val st = repo.store.runStateNow()
        assertTrue(
            summaryOf(id).outcomes.any { it is RunOutcome.StageUp },
            "with the touch put right every rep is 50,5 s — on pace, the step up is earned; stored: outcomes ${summaryOf(id).outcomes}, goalStage ${st.goalStage}, " +
                "history pace ${db.runs().get(id)!!.workPace?.let { Ru.pace(it) }}/km",
        )
    }

    /**
     * Once the measured lap length is set (the card after a run: «Поставить 205 м»), the next plans use it, but
     * the run just saved — the one it was measured in — keeps its verdict, its distance and its pace from 230 m. A rating
     * put right decides the last run again; the lap length, a bigger correction of the same run, does not.
     */
    @Test fun theLapLengthPutRightDecidesTheLastRunAgain() = runBlocking<Unit> {
        repo.store.setRunState(after26.copy(qualityCount = 0, runs = 4, lastRunDay = 20749))
        val id = racePace26()
        assertTrue(RunOutcome.TooFastRepeat in summaryOf(id).outcomes, "reproduces his phone: ${summaryOf(id).outcomes}")
        assertEquals(20 * 230.0, db.runs().get(id)!!.distanceM!!, 1e-6)
        repo.setLapMeters(measured)
        assertEquals(measured, repo.store.profileNow()!!.lapMeters)
        val run = db.runs().get(id)!!
        val avg = reps26.average()
        assertFalse(
            RunOutcome.TooFastRepeat in summaryOf(id).outcomes,
            "the lap length put right to $measured m: the run it was measured in keeps ${summaryOf(id).outcomes}, distance ${run.distanceM} m (${20 * measured} m run), " +
                "pace ${run.workPace?.let { Ru.pace(it) }}/km (${Ru.pace(avg / measured * 1000)}/km run); goalFails ${repo.store.runStateNow().goalFails}",
        )
    }

    @Test fun hisWeekIsPlannedAsHisPhoneHadIt() = runBlocking<Unit> {
        repo.store.setRunState(after26.copy(qualityCount = 0, runs = 4, lastRunDay = 20749, recent = after26.recent.dropLast(1)))
        at(LocalDate.of(2026, 10, 26), 10)
        assertEquals(LocalDate.of(2026, 10, 29), repo.trialDay(LocalDate.of(2026, 10, 26)))
        assertEquals(RunType.GOAL_PACE, repo.runFor(SessionKind.RUN_QUALITY, LocalDate.of(2026, 10, 26)).type)
        assertEquals(RunType.TEST, repo.runFor(SessionKind.RUN_QUALITY, LocalDate.of(2026, 10, 29)).type)
    }
}
