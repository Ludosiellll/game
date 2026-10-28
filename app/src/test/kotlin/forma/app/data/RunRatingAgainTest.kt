package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import forma.core.run.RunType
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
 * An older run rated again (Denis: 1500 m in 5:30, runs Mon/Thu/Sat on a 230 m lap, the gym Tue/Thu/Sat). His easy run
 * of 22.10 was rated 7 on the day and put right to 4 on 26.10, after the race-pace session: only the last run was decided
 * again, so the easy runs stayed 5 minutes shorter and his scale kept the 7. What an easy or long run decides by itself is
 * decided again while no run since was built on it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RunRatingAgainTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private var clock = 0L

    private fun at(d: LocalDate, hour: Int = 12) { clock = d.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }

    private val denis = Profile(
        name = "Денис", birthYear = 2008, goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE,
        trainingDays = setOf(1, 2, 4, 6), bodyWeightKg = 71.0, heightCm = 183, lapMeters = 230, runGoal = RunGoal(1500, 330),
        aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
        doubleDays = true, gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
    )

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "run-rating-again")
        store.clear()
        at(LocalDate.of(2026, 10, 12))
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(denis)
        // The program as on his phone: the block from Monday 12.10, made on 13.10; two free runs on 12.10.
        repo.store.setProgram(repo.store.programNow()!!.copy(startEpochDay = 20738, createdEpochDay = 20739))
        repo.store.setRunState(repo.store.runStateNow().copy(runs = 2, lastRunDay = 20738, recent = listOf(RunLog(20738, 24), RunLog(20738, 2))))
    }

    @After fun tearDown() = db.close()

    private suspend fun summaryOf(id: Long) = AppJson.decodeFromString(RunSummary.serializer(), db.workouts().get(id)!!.summaryJson!!)

    /** 22.10 (Thursday): the plan's easy 25 minutes after the break, 18 laps in 25:17, rated [rpe]. */
    private suspend fun easy22(rpe: Int): Long {
        val d = LocalDate.of(2026, 10, 22)
        at(d, 15)
        val w = repo.runFor(SessionKind.RUN_QUALITY, d)
        assertEquals(RunType.EASY, w.type, w.title)
        assertEquals(25 * 60, w.workSeconds, w.title)
        val laps = List(18) { Lap(84.3, 82.8, null, it + 1) }
        return repo.saveRun(w, RunResult(1.0, rpe, 1517, distanceM = 18 * 230.0, laps = laps, cadence = 156), clock - 1517_000L, emptyList(), d).first
    }

    /** 26.10 (Monday): race pace, five reps of a lap, as he ran them. */
    private suspend fun racePace26(): Long {
        val d = LocalDate.of(2026, 10, 26)
        at(d, 14)
        val w = repo.runFor(SessionKind.RUN_QUALITY, d)
        assertEquals(RunType.GOAL_PACE, w.type, w.title)
        val laps = listOf(46.399, 49.982, 48.993, 47.286, 46.614).mapIndexed { i, s -> Lap(s, 49.91, 51.29, 1, 1, i + 1) }
        val pace = laps.sumOf { it.seconds } / (5 * 230 / 1000.0)
        return repo.saveRun(w, RunResult(1.0, 7, 2142, distanceM = 20 * 230.0, workPace = pace, laps = laps, reps = 5, cadence = 158), clock - 2142_000L, emptyList(), d).first
    }

    /** An easy run on [d] as planned, done in full and rated [rpe]. */
    private suspend fun easyOn(d: LocalDate, rpe: Int): Long {
        at(d, 18)
        val w = repo.runFor(SessionKind.RUN_EASY, d)
        assertEquals(RunType.EASY, w.type, w.title)
        return repo.saveRun(w, RunResult(1.0, rpe, w.totalSeconds), clock - w.totalSeconds * 1000L, emptyList(), d).first
    }

    @Test fun hisEasyRunRatedAgainAfterTheRacePaceSessionIsDecidedAgain() = runBlocking<Unit> {
        val easy = easy22(7)
        assertEquals(listOf<RunOutcome>(RunOutcome.Shorter(20)), summaryOf(easy).outcomes)
        val race = racePace26()
        val raceOutcomes = summaryOf(race).outcomes
        val before = repo.store.runStateNow()
        assertEquals(20, before.easyMinutes)
        assertEquals(listOf(7), before.easyRpes)
        // 26.10, 12:39: it was easy, a 4. Done in full and lightly: the easy run grows, 25 → 28.
        assertTrue(repo.setRating(easy, 4))
        val st = repo.store.runStateNow()
        assertEquals(4, db.workouts().get(easy)!!.rpe)
        assertEquals(28, st.easyMinutes)
        assertEquals(listOf(4), st.easyRpes)
        assertEquals(listOf<RunOutcome>(RunOutcome.Longer(28)), summaryOf(easy).outcomes)
        // The race-pace session did not read it: its verdict and the rest of the progress stay as they were.
        assertEquals(raceOutcomes, summaryOf(race).outcomes)
        assertEquals(before.copy(easyMinutes = 28, easyRpes = listOf(4)), st)
        // The race-pace session is still the last run, to be decided again when its own rating is put right.
        assertTrue(repo.setRating(race, 8))
        assertEquals(28, repo.store.runStateNow().easyMinutes)
        // And the easy run back to 7: shorter again, as at first.
        assertTrue(repo.setRating(easy, 7))
        assertEquals(20, repo.store.runStateNow().easyMinutes)
        assertEquals(listOf(7), repo.store.runStateNow().easyRpes)
        assertEquals(listOf<RunOutcome>(RunOutcome.Shorter(20)), summaryOf(easy).outcomes)
    }

    @Test fun aLongRunRatedAgainAfterAHardSessionIsDecidedAgain() = runBlocking<Unit> {
        easy22(4)
        racePace26()
        val sat = LocalDate.of(2026, 10, 31)
        at(sat, 10)
        val w = repo.runFor(SessionKind.RUN_LONG, sat)
        assertEquals(RunType.LONG, w.type, w.title)
        val longBefore = repo.store.runStateNow().longMinutes
        val long = repo.saveRun(w, RunResult(1.0, 8, w.totalSeconds), clock - w.totalSeconds * 1000L, emptyList(), sat).first
        assertTrue(summaryOf(long).outcomes.any { it is RunOutcome.Shorter }, "${summaryOf(long).outcomes}")
        // Monday's hard session is planned from the ladders, not from the long run's length.
        val mon = LocalDate.of(2026, 11, 2)
        at(mon, 14)
        val q = repo.runFor(SessionKind.RUN_QUALITY, mon)
        assertTrue(q.type != RunType.LONG && q.type != RunType.EASY, q.title)
        repo.saveRun(q, RunResult(1.0, 7, q.totalSeconds), clock - q.totalSeconds * 1000L, emptyList(), mon)
        // It was a 5, not an 8: not shorter.
        assertTrue(repo.setRating(long, 5))
        assertFalse(summaryOf(long).outcomes.any { it is RunOutcome.Shorter }, "${summaryOf(long).outcomes}")
        assertTrue(repo.store.runStateNow().longMinutes >= longBefore, "${repo.store.runStateNow().longMinutes} < $longBefore")
    }

    @Test fun anEasyRunPlannedFromItKeepsItsDecision() = runBlocking<Unit> {
        val easy = easy22(7)
        racePace26()
        // Saturday's easy run was planned at 20 minutes, from the 7.
        easyOn(LocalDate.of(2026, 10, 31), 5)
        val state = repo.store.runStateNow()
        val outcomes = summaryOf(easy).outcomes
        assertFalse(repo.setRating(easy, 4))
        assertEquals(state, repo.store.runStateNow())
        assertEquals(outcomes, summaryOf(easy).outcomes)
        assertEquals(4, db.workouts().get(easy)!!.rpe)
    }

    @Test fun aLengthChangedSinceIsNotTakenBack() = runBlocking<Unit> {
        val easy = easy22(7)
        racePace26()
        // The coach set the easy run since: the length is no longer the run's to decide.
        repo.store.setRunState(repo.store.runStateNow().copy(easyMinutes = 22))
        assertFalse(repo.setRating(easy, 4))
        assertEquals(22, repo.store.runStateNow().easyMinutes)
        assertEquals(listOf(7), repo.store.runStateNow().easyRpes)
    }

    @Test fun aRatingThatMovesTheRunnersScaleKeepsTheDecision() = runBlocking<Unit> {
        // Four easy runs on his scale: with the 7 of 22.10 its median is 7, the thresholds 2 higher for the session after.
        repo.store.setRunState(repo.store.runStateNow().copy(easyRpes = listOf(5, 5, 7, 7)))
        val easy = easy22(7)
        assertEquals(2, repo.store.runStateNow().rpeOffset)
        val race = racePace26()
        assertTrue(summaryOf(race).outcomes.contains(RunOutcome.OwnScale(2)), "${summaryOf(race).outcomes}")
        // Put right to 3, the scale would have been the plan's (median 5): the race-pace session was decided on the old one.
        val state = repo.store.runStateNow()
        assertFalse(repo.setRating(easy, 3))
        assertEquals(state, repo.store.runStateNow())
    }
}
