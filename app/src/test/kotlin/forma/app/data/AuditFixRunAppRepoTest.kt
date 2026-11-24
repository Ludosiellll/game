package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.run.Lap
import forma.core.run.LapEdit
import forma.core.run.ManualLaps
import forma.core.run.PaceRange
import forma.core.run.RunResult
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.Segment
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/** A rep's lap put right in «История» on a run that is not the last one: what the screens read of the reps follows it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AuditFixRunAppRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private val monday = LocalDate.of(2026, 11, 16)
    private var clock = 0L

    private fun at(d: LocalDate, hour: Int) {
        clock = d.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    @Before fun setUp() = runBlocking {
        at(monday, 18)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "audit-e-rep-laps")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 3, 5), bodyWeightKg = 80.0,
                lapMeters = 205, runGoal = RunGoal(1500, 330),
            ),
        )
    }

    @After fun tearDown() = db.close()

    /** «Темп цели · 6 × 1 круг» on 205 m: the warm-up, six reps of a lap with a rest after each, the cool-down. */
    private val goalPace = RunWorkout(
        RunType.GOAL_PACE, "Темп цели · 6 × 1 круг",
        listOf(Segment(SegmentKind.WARMUP, 720)) +
            List(6) { listOf(Segment(SegmentKind.WORK, 45, PaceRange(215, 222), laps = 1.0, lapSec = 44.6), Segment(SegmentKind.RECOVERY, 120)) }.flatten() +
            Segment(SegmentKind.COOLDOWN, 600),
        lapMeters = 205,
    )

    private suspend fun lapsOf(id: Long): List<Lap> =
        AppJson.decodeFromString(RunSummary.serializer(), assertNotNull(db.workouts().get(id)?.summaryJson)).laps

    /**
     * older-run-lap-edit-stale-rep-pace: Monday's six reps of a lap, the third pressed late (52,4 s). On Thursday, after
     * another run, he puts it right in «История» (44,4 s). The reps' pace the History tile, the week's card and the
     * debrief's own-scale check read follows the laps, as the debrief's average lap and the goal chart do.
     */
    @Test fun aRepLapPutRightOnAnOlderRunMovesTheRepsPace() = runBlocking<Unit> {
        val laps = listOf(45.1, 44.8, 52.4, 44.9, 45.3, 44.7).mapIndexed { i, s -> Lap(s, 43.6, 45.5, 1, 1, i + 1) }
        val pace = assertNotNull(ManualLaps.repPace(laps, 205))
        val id = repo.saveRun(goalPace, RunResult(1.0, 7, 2400, distanceM = 5200.0, workPace = pace, laps = laps), clock - 2_400_000, emptyList(), monday).first
        assertEquals(pace, assertNotNull(db.runs().get(id)?.workPace), 1e-9)
        // Thursday's run, saved after it: Monday's is no longer the last one.
        val thursday = monday.plusDays(3)
        at(thursday, 18)
        repo.saveRun(repo.freeRun(null), RunResult(1.0, 4, 1800, distanceM = 5000.0), clock - 1_800_000, emptyList(), thursday)
        val fixed = LapEdit.setTime(laps, 2, 44.4)
        repo.editRunLaps(id, fixed)
        assertEquals(fixed, lapsOf(id))
        val now = assertNotNull(ManualLaps.repPace(fixed, 205))
        assertNotEquals(pace, now)
        assertEquals(now, assertNotNull(db.runs().get(id)?.workPace), 1e-9)
    }
}
