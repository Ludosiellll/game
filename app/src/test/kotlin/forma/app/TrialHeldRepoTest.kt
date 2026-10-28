package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.Texts
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
import forma.core.run.RunReview
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
 * Denis's first trial on 29.10 (205 m loop, 7,5 laps, no fitness yet) through the real repository, as he expects it: the
 * beeps' pace for half of it, then slower — or a stop. What he held counts; a trial not run to the end comes again in two
 * weeks; a faded one run to the end is a trial like any.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TrialHeldRepoTest {
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

    /** His running progress as his phone holds it on 28.10 (run_state): no trial yet. */
    private val now = RunState(
        walkStage = -1, easyMinutes = 20, longMinutes = 35, runs = 5, qualityCount = 1, lastRunDay = 20752, easyRpes = listOf(7),
        goalFails = 1, recent = listOf(RunLog(20738, 24), RunLog(20738, 2), RunLog(20748, 25), RunLog(20749, 17), RunLog(20752, 36)),
    )

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "trial-held")
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
        assertEquals(1538, it.testMeters)
    }

    /** The trial's laps as run (seconds, the first the half lap). */
    private fun laps(w: RunWorkout, secs: List<Double>): List<Lap> {
        val lap = w.segments.first { it.kind == SegmentKind.WORK }.lapSec!!
        return secs.mapIndexed { i, s ->
            val part = if (i == 0) 0.5 else 1.0
            Lap(s, lap * part, lap * part, i + 1, 8, 1, part)
        }
    }

    private val held = listOf(24.35) + List(4) { 48.7 }

    @Test fun stoppedAfterHalfHisFitnessIsSetAndTheFullTrialComesInTwoWeeks() = runBlocking<Unit> {
        val w = trial()
        val (_, summary) = repo.saveRun(w, RunResult(0.6, 9, 1400, distanceM = 2900.0, laps = laps(w, held)), clock - 1400_000L, emptyList(), day)
        val part = summary.outcomes.filterIsInstance<RunOutcome.TestFromPart>().single()
        assertTrue(!part.finished && part.meters == 923 && part.seconds == 219, "${summary.outcomes}")
        val st = repo.store.runStateNow()
        assertEquals(42.5, st.vdot)
        assertNull(st.lastTestDay)
        assertEquals(day.toEpochDay(), st.lastTrialTryDay)
        // No trial before two weeks are out; then on his next hard day.
        assertTrue(repo.runFor(SessionKind.RUN_QUALITY, LocalDate.of(2026, 11, 2)).type != RunType.TEST)
        assertEquals(LocalDate.of(2026, 11, 12), repo.trialDay(LocalDate.of(2026, 10, 30)))
        // The run is in his history and load whatever the trial gave.
        assertTrue(summary.load > 0)
        // Told so, in words.
        assertTrue("держал" in Texts.run(part), Texts.run(part))
    }

    @Test fun runToTheEndButSlowerTheHeldStretchIsTheFitness() = runBlocking<Unit> {
        val w = trial()
        val secs = held + listOf(56.0, 62.0, 66.0)
        val (_, summary) = repo.saveRun(
            w, RunResult(1.0, 9, 1700, distanceM = 3500.0, laps = laps(w, secs), testSeconds = secs.sum().toInt()), clock - 1700_000L, emptyList(), day,
        )
        assertTrue(summary.outcomes.any { it is RunOutcome.TestTime && it.seconds == 403 }, "${summary.outcomes}")
        assertTrue(summary.outcomes.filterIsInstance<RunOutcome.TestFromPart>().single().finished)
        val st = repo.store.runStateNow()
        assertEquals(42.5, st.vdot)
        assertEquals(day.toEpochDay(), st.lastTestDay)
        // The debrief says what was held and what the paces come from.
        val review = RunReview.of(
            RunReview.Run(w, summary.laps.orEmpty(), 9, 1700, null, null, 1538 to 403, 1.0, false, 923 to 219), denis.runGoal,
        ).joinToString(" ") { it.text }
        assertTrue("Первые 923 м" in review && "темпы посчитаны по ним" in review, review)
    }

    @Test fun theWarmUpStridesAreKeptWithTheRun() = runBlocking<Unit> {
        val w = trial()
        val secs = listOf(26.0) + List(7) { 52.0 }
        val (_, summary) = repo.saveRun(
            w, RunResult(1.0, 9, 1700, distanceM = 3500.0, laps = laps(w, secs), testSeconds = secs.sum().toInt(), strideSpeeds = listOf(5.4, 5.6, null, 5.5), jogSpeed = 2.9),
            clock - 1700_000L, emptyList(), day,
        )
        assertEquals(listOf(5.4, 5.6, null, 5.5), summary.strides)
        assertEquals(2.9, summary.jog)
        val items = RunReview.of(
            RunReview.Run(w, summary.laps, 9, 1700, null, null, 1538 to 390, 1.0, false, null, summary.strides, summary.jog), denis.runGoal,
        )
        // The trial leads its debrief; the strides come after it.
        assertEquals("Как пробежал", items.first().title)
        val review = items.single { it.title == "Ускорения" }
        assertTrue("4 из 4" in review.text, review.text)
    }

    @Test fun stoppedEarlyItSaysWhyNotThatLapsWereMissed() = runBlocking<Unit> {
        val w = trial()
        val (_, summary) = repo.saveRun(
            w, RunResult(0.4, 9, 1300, distanceM = 2700.0, laps = laps(w, listOf(24.35) + List(3) { 48.7 })), clock - 1300_000L, emptyList(), day,
        )
        val stop = summary.outcomes.filterIsInstance<RunOutcome.TestStopped>().single()
        assertTrue("меньше половины" in Texts.run(stop), Texts.run(stop))
        assertNull(repo.store.runStateNow().vdot)
    }
}
