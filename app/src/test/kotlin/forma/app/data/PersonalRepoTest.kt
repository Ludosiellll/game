package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Experience
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.strength.Outcome
import forma.core.strength.Prescription
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The personal rules wired into the app: they learn from what is in the database and fall back without it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PersonalRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private val start = LocalDate.of(2026, 9, 7)
    private var clock = start.atTime(18, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "personal-test")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(Profile(experience = Experience.INTERMEDIATE, runLevel = RunLevel.NONE, trainingDays = setOf(1, 3, 5), bodyWeightKg = 75.0))
    }

    @After fun tearDown() { db.close() }

    /** A finished gym session on [day] with [item]'s sets as given (weight, reps, rir), lasting [minutes]. */
    private suspend fun session(day: LocalDate, item: LiftItem, sets: List<Triple<Double, Int, Int?>>, minutes: Int = 60, summary: LiftSummary? = null): Long {
        val t = day.atTime(18, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val plan = LiftPlan("t", "Т", listOf(item), deload = false)
        val id = db.workouts().insert(
            WorkoutEntity(epochDay = day.toEpochDay(), kind = Kind.LIFT, templateId = "t", title = "Т", startedAt = t, finishedAt = t + minutes * 60_000L,
                status = Status.DONE, rpe = 7, durationSec = minutes * 60, load = 7.0 * minutes,
                planJson = AppJson.encodeToString(LiftPlan.serializer(), plan),
                summaryJson = summary?.let { AppJson.encodeToString(LiftSummary.serializer(), it) }),
        )
        sets.forEachIndexed { i, (w, r, rir) ->
            db.sets().insert(SetEntity(workoutId = id, exerciseId = item.exerciseId, slotKey = item.slot.key, position = item.position,
                weight = w, reps = r, rir = rir, targetWeight = item.prescription.weight, targetReps = item.prescription.reps, doneAt = t + i * 180_000L))
        }
        return id
    }

    private suspend fun firstItem(): LiftItem {
        val program = repo.store.programNow()!!
        return repo.liftPlan(program.templates.first().id, start).items.first()
    }

    @Test fun anOverRatedReserveIsReadDown() = runBlocking<Unit> {
        val base = firstItem()
        val item = base.copy(prescription = Prescription(80.0, 8, 3, 2, calibrate = false))
        // Says "4 more" after 8, and two sets later at the same weight only 7 come — five sessions.
        repeat(5) { session(start.plusDays(it * 2L), item, listOf(Triple(80.0, 8, 4), Triple(80.0, 8, 2), Triple(80.0, 7, null))) }
        assertEquals(2, repo.rirBias())
        assertNotNull(forma.core.strength.RirScale.explain(2))
    }

    @Test fun sessionsLongerThanPlannedShowTheirTime() = runBlocking<Unit> {
        val item = firstItem()
        val plan = LiftPlan("t", "Т", listOf(item), deload = false)
        val est = TrainingRepository.estimateOf(plan)
        repeat(4) { session(start.plusDays(it * 2L), item, listOf(Triple(80.0, 8, 2)), minutes = (est * 1.5).toInt()) }
        val r = repo.timeRatio()
        assertTrue(r in 1.4..1.6, "ratio $r")
    }

    @Test fun repsFallingOffSessionAfterSessionGiveLongerRest() = runBlocking<Unit> {
        val item = firstItem()
        assertNull(item.restWhy)
        assertEquals(0, item.restShift)
        // 8 with 2 left, then 6 and 4 to failure, three minutes apart: 3–4 reps lost a set.
        repeat(4) { session(start.plusDays(it * 2L), item, listOf(Triple(80.0, 8, 2), Triple(80.0, 6, 0), Triple(80.0, 4, 0))) }
        val again = repo.liftPlan(repo.store.programNow()!!.templates.first().id, start.plusDays(10)).items.first { it.exerciseId == item.exerciseId }
        assertEquals(60, again.restShift)
        assertNotNull(again.restWhy)
        // A rest the person set themselves stays theirs.
        repo.updateSettings { it.copy(rest = mapOf(item.exerciseId to 120)) }
        val own = repo.liftPlan(repo.store.programNow()!!.templates.first().id, start.plusDays(10)).items.first { it.exerciseId == item.exerciseId }
        assertEquals(120, own.slot.restSec)
        assertTrue(own.restOwn)
        assertEquals(0, own.restShift)
        assertNull(own.restWhy)
    }

    @Test fun startGuessesLearnFromFirstSessions() = runBlocking<Unit> {
        val program = repo.store.programNow()!!
        val profile = repo.store.profileNow()!!
        assertEquals(1.0, repo.startFactor(profile, 1.35))
        // Two first sessions where the weight found was about twice the guess.
        val items = repo.liftPlan(program.templates.first().id, start).items.filter { it.prescription.calibrate && it.prescription.startHint != null }.take(2)
        assertEquals(2, items.size)
        items.forEachIndexed { i, it ->
            val found = it.prescription.startHint!! * 2
            session(start.plusDays(i.toLong()), it, listOf(Triple(found, 5, 2)),
                summary = LiftSummary(listOf(SlotReport(it.exerciseId, it.name, Outcome.Calibrated(found, it.prescription.reps), null, false)), 0.0, 1, 60, 420.0))
        }
        val f = repo.startFactor(profile, 1.35)
        assertTrue(f in 1.8..2.2, "factor $f")
    }

    @Test fun verdictsReadWhatASessionChanged() {
        fun w(summary: LiftSummary) = WorkoutEntity(epochDay = 1, kind = Kind.LIFT, templateId = "t", title = "Т", startedAt = 0, status = Status.DONE,
            summaryJson = AppJson.encodeToString(LiftSummary.serializer(), summary))
        fun r(o: Outcome) = SlotReport("squat", "Присед", o, null, false)
        assertEquals(true, TrainingRepository.verdict(w(LiftSummary(listOf(r(Outcome.AddWeight(80.0, 82.5, 5)), r(Outcome.AddWeight(60.0, 62.5, 5)), r(Outcome.Reduce(80.0, 72.5, 5))), 0.0, 9, 60, 420.0))))
        assertEquals(false, TrainingRepository.verdict(w(LiftSummary(listOf(r(Outcome.Reduce(80.0, 72.5, 5)), r(Outcome.Reset(80.0, 70.0, 5))), 0.0, 6, 60, 420.0))))
        assertNull(TrainingRepository.verdict(w(LiftSummary(listOf(r(Outcome.Deload)), 0.0, 3, 40, 200.0))))
    }
}
