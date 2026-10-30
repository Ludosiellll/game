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
import forma.core.run.LapEdit
import forma.core.run.RunLog
import forma.core.run.RunOutcome
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis's trial stopped after half of it (205 m loop, 7 laps from the line, no fitness yet), then put right after the fact: a lap's
 * time corrected, the lap length set anew. What was held stays what was held — never the whole trial in half its time.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TrialHeldEditRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private var clock = 0L

    private fun at(d: LocalDate, hour: Int = 12) { clock = d.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }

    private val day = LocalDate.of(2026, 10, 29)
    private val denis = Profile(
        name = "Денис", birthYear = 2008, goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE,
        trainingDays = setOf(1, 2, 4, 6), bodyWeightKg = 71.0, heightCm = 183, lapMeters = 205, runGoal = RunGoal(1500, 330),
        aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
        doubleDays = true, gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
    )

    private val now = RunState(
        walkStage = -1, easyMinutes = 20, longMinutes = 35, runs = 5, qualityCount = 1, lastRunDay = 20752, easyRpes = listOf(7),
        goalFails = 1, recent = listOf(RunLog(20738, 24), RunLog(20738, 2), RunLog(20748, 25), RunLog(20749, 17), RunLog(20752, 36)),
    )

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "trial-held-edit")
        store.clear()
        at(LocalDate.of(2026, 10, 12))
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(denis)
        repo.store.setProgram(repo.store.programNow()!!.copy(startEpochDay = 20738, createdEpochDay = 20739))
        repo.store.setRunState(now)
        at(day, 14)
    }

    @After fun tearDown() = db.close()

    private suspend fun trial(): RunWorkout = repo.runFor(SessionKind.RUN_QUALITY, day).also {
        assertEquals(RunType.TEST, it.type, it.title)
        assertEquals(1435, it.testMeters)
    }

    private fun laps(w: RunWorkout, secs: List<Double>): List<Lap> {
        val lap = w.segments.first { it.kind == SegmentKind.WORK }.lapSec!!
        return secs.mapIndexed { i, s -> Lap(s, lap, lap, i + 1, 7, 1) }
    }

    private val held = List(4) { 48.7 }

    private suspend fun summaryOf(id: Long): RunSummary = AppJson.decodeFromString(RunSummary.serializer(), db.workouts().get(id)!!.summaryJson!!)

    /** Stopped after four laps (820 m in 3:15), then lap 3 put right by a third of a second: still a trial stopped at half. */
    @Test fun aLapPutRightOnAStoppedTrialDoesNotMakeItAFullOne() = runBlocking<Unit> {
        val w = trial()
        val (id, summary) = repo.saveRun(w, RunResult(0.6, 9, 1400, distanceM = 2900.0, laps = laps(w, held)), clock - 1400_000L, emptyList(), day)
        assertTrue(summary.outcomes.any { it is RunOutcome.TestFromPart && !it.finished }, "${summary.outcomes}")
        assertEquals(42.0, repo.store.runStateNow().vdot)
        repo.editRunLaps(id, LapEdit.setTime(summary.laps, 2, 49.0))
        val after = summaryOf(id)
        assertTrue(after.outcomes.none { it is RunOutcome.TestTime || it == RunOutcome.GoalReached }, "${after.outcomes}")
        assertTrue(after.outcomes.any { it is RunOutcome.TestFromPart && !it.finished }, "${after.outcomes}")
        val st = repo.store.runStateNow()
        assertEquals(42.0, st.vdot)
        assertNull(st.lastTestDay)
        assertEquals(day.toEpochDay(), st.lastTrialTryDay)
    }

    /** The lap length set to the loop's 200 m after an easy run since: the stretch held is said in the new metres too. */
    @Test fun theStretchHeldIsCountedAgainOnANewLapLength() = runBlocking<Unit> {
        val w = trial()
        val (id, _) = repo.saveRun(w, RunResult(0.6, 9, 1400, distanceM = 2900.0, laps = laps(w, held)), clock - 1400_000L, emptyList(), day)
        val next = day.plusDays(1)
        at(next, 14)
        repo.saveRun(repo.runFor(SessionKind.RUN_EASY, next), RunResult(1.0, 4, 1200, distanceM = 3000.0), clock - 1200_000L, emptyList(), next)
        repo.store.setProfile(denis.copy(lapMeters = 200))
        repo.recountLaps(205)
        val part = summaryOf(id).outcomes.filterIsInstance<RunOutcome.TestFromPart>().single()
        assertEquals(800, part.meters, "$part")
    }
}
