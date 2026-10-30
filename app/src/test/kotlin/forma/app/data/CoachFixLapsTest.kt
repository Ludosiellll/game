package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.sync.CoachCall
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.run.Lap
import forma.core.run.RunResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The coach puts laps right when told (Denis, 30.10: «пишешь, что пробежал 3 круга, но забыл разделить — он разделит»):
 * with «Принять», in the run's own lap edits, with its way back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CoachFixLapsTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "coach-fix-laps")
        store.clear()
        repo = TrainingRepository(db, store)
        repo.completeOnboarding(Profile(goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), bodyWeightKg = 80.0, lapMeters = 205))
    }

    @After fun tearDown() = db.close()

    private fun call(vararg a: Pair<String, Any>) = CoachCall(
        CoachActions.LAPS, JsonObject(a.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) } + ("why" to JsonPrimitive("сказал сам"))),
    )

    private suspend fun lapsOf(id: Long) = AppJson.decodeFromString(RunSummary.serializer(), db.workouts().get(id)!!.summaryJson!!).laps.map { it.seconds }

    /** A free run of 5 presses where 3 laps were run in the third: 7 laps after, the distance with them. */
    @Test fun threeLapsInOnePressAreSplitAndCanBeUndone() = runBlocking<Unit> {
        val laps = listOf(60.0, 61.0, 183.0, 62.0, 60.0).mapIndexed { i, s -> Lap(s, number = i + 1) }
        val id = repo.saveRun(repo.freeRun(205), RunResult(1.0, 5, laps.sumOf { it.seconds }.toInt(), distanceM = 5 * 205.0, laps = laps), System.currentTimeMillis(), emptyList()).first
        val ready = assertIs<CoachActions.Ready>(CoachActions.prepare(repo, call("action" to "split", "lap" to 3, "parts" to 3)))
        assertEquals(CoachActions.Mode.CONFIRM, ready.mode)
        assertTrue("3 круга по 61,0 с" in ready.text, ready.text)
        val entry = CoachActions.make(repo, ready)
        assertEquals(listOf(60.0, 61.0, 61.0, 61.0, 61.0, 62.0, 60.0), lapsOf(id))
        assertEquals(7 * 205.0, db.runs().get(id)!!.distanceM!!, 1e-6)
        assertTrue(CoachActions.undo(repo, entry))
        assertEquals(listOf(60.0, 61.0, 183.0, 62.0, 60.0), lapsOf(id))
    }

    @Test fun nonsenseIsRefusedWithAReason() = runBlocking<Unit> {
        val laps = listOf(Lap(150.0, number = 1), Lap(49.0, rep = 1, number = 1, of = 1), Lap(120.0, number = 1))
        repo.saveRun(repo.freeRun(205), RunResult(1.0, 5, 319, laps = laps), System.currentTimeMillis(), emptyList())
        // No lap 9; a rep's only lap is not split past its plan, nor removed.
        assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call("action" to "split", "lap" to 9)))
        val split = assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call("action" to "split", "lap" to 2)))
        assertTrue("по плану" in split.why, split.why)
        val remove = assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call("action" to "remove", "lap" to 2)))
        assertTrue("время пропадёт" in remove.why, remove.why)
        assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call("action" to "fly", "lap" to 1)))
        assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call("action" to "split", "lap" to 1, "day" to "2026-13-40")))
    }

    @Test fun theCoachIsOfferedIt() {
        val names = CoachActions.declarations.map { ((it as JsonObject)["name"] as JsonPrimitive).content }
        assertTrue(CoachActions.LAPS in names)
    }
}
