package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.load.TrainingLoad
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.RunOutcome
import forma.core.run.RunResult
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

/** "How hard it was" put right after the workout: the load follows; the last run is decided again, an older one not. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RatingEditTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private val day = LocalDate.of(2026, 10, 13)
    private var clock = day.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "rating-edit")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(Profile(goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), bodyWeightKg = 70.0, lapMeters = 230))
        store.setRunState(store.runStateNow().copy(walkStage = -1, runs = 4, easyMinutes = 30, lastRunDay = day.toEpochDay() - 2))
    }

    @After fun tearDown() = db.close()

    private suspend fun easyRun(d: LocalDate, rpe: Int): Long {
        clock = d.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val w = repo.runFor(SessionKind.RUN_EASY, d)
        return repo.saveRun(w, RunResult(1.0, rpe, w.totalSeconds), clock - w.totalSeconds * 1000L, emptyList(), d).first
    }

    @Test fun theLastRunRatedAgainIsDecidedAgain() = runBlocking {
        val id = easyRun(day, 7)
        // Rated "hard": the easy runs got 5 minutes shorter.
        assertEquals(25, repo.store.runStateNow().easyMinutes)
        assertTrue(summaryOf(id).outcomes.any { it is RunOutcome.Shorter })
        // It was a mistap: 4. Decided again — not shorter; the load and the debrief follow.
        assertTrue(repo.setRating(id, 4))
        assertTrue(repo.store.runStateNow().easyMinutes >= 30, "${repo.store.runStateNow().easyMinutes}")
        val w = db.workouts().get(id)!!
        assertEquals(4, w.rpe)
        assertEquals(TrainingLoad.sessionLoad(4, w.durationSec!! / 60.0), w.load!!, 1e-9)
        assertFalse(summaryOf(id).outcomes.any { it is RunOutcome.Shorter })
        // And back again: shorter again, as at first.
        assertTrue(repo.setRating(id, 7))
        assertEquals(25, repo.store.runStateNow().easyMinutes)
    }

    @Test fun anOlderRunKeepsItsDecisions() = runBlocking {
        val first = easyRun(day, 7)
        easyRun(day.plusDays(2), 5)
        val state = repo.store.runStateNow()
        // A run after it was planned from its decision: only the rating and the load change.
        assertFalse(repo.setRating(first, 4))
        assertEquals(state, repo.store.runStateNow())
        assertEquals(4, db.workouts().get(first)!!.rpe)
        assertEquals(TrainingLoad.sessionLoad(4, db.workouts().get(first)!!.durationSec!! / 60.0), db.workouts().get(first)!!.load!!, 1e-9)
    }

    @Test fun aGymSessionRatedAgainChangesItsLoad() = runBlocking {
        val program = repo.store.programNow()!!
        val t = program.templates.first()
        val id = repo.startLift(t.id, day)
        repo.finishLift(id, 8)
        val before = db.workouts().get(id)!!
        assertFalse(repo.setRating(id, 4))
        val after = db.workouts().get(id)!!
        assertEquals(4, after.rpe)
        assertEquals(TrainingLoad.sessionLoad(4, (after.durationSec ?: 0) / 60.0), after.load!!, 1e-9)
        assertEquals(before.summaryJson != null, after.summaryJson != null)
    }

    private suspend fun summaryOf(id: Long) = AppJson.decodeFromString(RunSummary.serializer(), db.workouts().get(id)!!.summaryJson!!)
}
