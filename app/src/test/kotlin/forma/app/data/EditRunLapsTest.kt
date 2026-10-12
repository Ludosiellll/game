package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.run.Lap
import forma.core.run.LapEdit
import forma.core.run.RunResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Laps put right after the run: on a stadium the distance is the laps or GPS, whichever is more — before and after. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class EditRunLapsTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "edit-run-laps")
        store.clear()
        repo = TrainingRepository(db, store)
        repo.completeOnboarding(Profile(goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), bodyWeightKg = 80.0, lapMeters = 230))
    }

    @After fun tearDown() = db.close()

    private suspend fun saved(laps: List<Lap>, distanceM: Double): Long =
        repo.saveRun(repo.freeRun(230), RunResult(1.0, 6, laps.sumOf { it.seconds }.toInt(), distanceM = distanceM, laps = laps), System.currentTimeMillis(), emptyList()).first

    private val laps = listOf(76.0, 75.0, 141.0, 74.0).mapIndexed { i, s -> Lap(s, number = i + 1) }

    @Test fun gpsThatSawMoreKeepsTheDistanceUntilTheLapsAreMore() = runBlocking {
        // GPS saw 950 m, the 4 laps pressed are 920 m: the distance is GPS's.
        val id = saved(laps, 950.0)
        // A lap that was two: 5 laps, 1150 m — more than GPS saw, the laps win.
        repo.editRunLaps(id, LapEdit.split(laps, 2))
        assertEquals(5 * 230.0, repo.db.runs().get(id)!!.distanceM!!, 1e-6)
        val r = repo.db.runs().get(id)!!
        assertEquals(r.movingSec / 1.15, r.avgPace!!, 1e-6)
    }

    @Test fun gpsThatSawMoreStaysWhenLapsAreMerged() = runBlocking {
        val id = saved(laps, 950.0)
        repo.editRunLaps(id, LapEdit.merge(laps, 0))
        assertEquals(950.0, repo.db.runs().get(id)!!.distanceM!!, 1e-6)
    }

    /**
     * An easy run kept on past its plan, the extra minutes not counted: the distance saved is that part of the laps.
     * Laps put right keep the same part — the distance does not jump to all the laps while the time stays the part.
     */
    @Test fun aRunWithTheExtraLeftOutKeepsItsPart() = runBlocking {
        val laps = List(12) { Lap(72.0, number = it + 1) }
        val share = 720.0 / 864.0
        val id = repo.saveRun(repo.freeRun(230), RunResult(1.0, 4, 720, distanceM = 12 * 230 * share, laps = laps), 123_456L, emptyList()).first
        val before = repo.db.runs().get(id)!!
        // The same laps back (merged and split again): nothing changes.
        repo.editRunLaps(id, LapEdit.split(LapEdit.merge(laps, 0), 0))
        assertEquals(before.distanceM!!, repo.db.runs().get(id)!!.distanceM!!, 1.0)
        // A lap that was two: 13 laps, the same part of them.
        repo.editRunLaps(id, LapEdit.split(laps, 3))
        assertEquals(13 * 230 * share, repo.db.runs().get(id)!!.distanceM!!, 1.0)
    }
}
