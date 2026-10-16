package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Profile
import forma.core.plan.SessionKind
import forma.core.run.RunState
import forma.core.run.RunType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Back from being sick, on Denis's week as it is in the cloud on 16.10: a free run on Monday, «Верх · Б» on Tuesday,
 * «Верх · А» on Thursday (sick, marked «Хорошо»), no run since Monday. «Болею» on Friday with a pause to Sunday.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AfterSickTest {
    private val profileJson = """{"aim": {"run": "ENDURANCE", "lift": "POWERBUILDING", "emphasis": "BALANCED"}, "sex": "MALE", "goal": "HYBRID", "gymDays": [2, 4, 6], "runDays": [4, 6, 1], "runGoal": {"date": null, "meters": 1500, "seconds": 330}, "heightCm": 183, "runLevel": "BASE", "birthYear": 2008, "lapMeters": 230, "restingHr": 65, "doubleDays": true, "experience": "ADVANCED", "bodyWeightKg": 70.0, "trainingDays": [2, 4, 6, 1], "sessionMinutes": 75}"""
    private val runJson = """{"runs": 2, "vdot": null, "recent": [{"day": 20738, "minutes": 24}, {"day": 20738, "minutes": 2}], "walkStage": -1, "lastRunDay": 20738, "easyMinutes": 25, "longMinutes": 40, "qualityCount": 0}"""
    private val zone = ZoneId.systemDefault()
    private val mon = LocalDate.of(2026, 10, 12)
    private val fri = mon.plusDays(4)
    private var clock = mon.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
    private lateinit var db: FormaDatabase

    @After fun tearDown() { if (::db.isInitialized) db.close() }

    private fun at(d: LocalDate, hour: Int = 9) { clock = d.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli() }
    private fun ms(d: LocalDate) = d.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()

    private fun denis(name: String): TrainingRepository = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, name)
        store.clear()
        val repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(AppJson.decodeFromString(Profile.serializer(), profileJson))
        store.setRunState(AppJson.decodeFromString(RunState.serializer(), runJson))
        store.editSettings { it.withFeel(mon.plusDays(3), Feel.GOOD) }
        db.workouts().insert(WorkoutEntity(epochDay = mon.toEpochDay(), kind = Kind.RUN, templateId = "FREE", title = "Свободный бег", startedAt = ms(mon), finishedAt = ms(mon), status = Status.DONE, rpe = 7, durationSec = 1435))
        for ((d, t) in listOf(mon.plusDays(1) to "upper_b", mon.plusDays(3) to "upper_a")) {
            db.workouts().insert(WorkoutEntity(epochDay = d.toEpochDay(), kind = Kind.LIFT, templateId = t, title = t, startedAt = ms(d), finishedAt = ms(d), status = Status.DONE, rpe = 7, durationSec = 4700))
        }
        at(fri)
        repo
    }

    /** «Болею» on Friday as the Today screen does it: the mark and a pause of three days. */
    private suspend fun sick(repo: TrainingRepository) {
        repo.store.editSettings { it.withFeel(fri, Feel.SICK) }
        repo.pause(fri, fri.plusDays(2))
    }

    private suspend fun ran(repo: TrainingRepository, d: LocalDate) {
        db.workouts().insert(WorkoutEntity(epochDay = d.toEpochDay(), kind = Kind.RUN, templateId = RunType.EASY.name, title = "Лёгкий бег", startedAt = ms(d), finishedAt = ms(d), status = Status.DONE, rpe = 4, durationSec = 1500))
        repo.store.setRunState(repo.store.runStateNow().copy(lastRunDay = d.toEpochDay(), runs = repo.store.runStateNow().runs + 1))
    }

    @Test fun withoutSicknessMondayIsRacePace() = runBlocking<Unit> {
        val repo = denis("sick-none")
        assertEquals(RunType.GOAL_PACE, repo.runFor(SessionKind.RUN_QUALITY, mon.plusDays(7)).type)
        assertEquals(0, repo.easyAfterSick(mon.plusDays(7)))
        assertFalse(repo.liftAfterSick(mon.plusDays(8)))
    }

    @Test fun afterBeingSickTheFirstTwoRunsAreEasyAndTheFirstGymSessionLighter() = runBlocking<Unit> {
        val repo = denis("sick-back")
        sick(repo)
        val monday = mon.plusDays(7)
        at(monday)
        assertEquals(2, repo.easyAfterSick(monday))
        val first = repo.runFor(SessionKind.RUN_QUALITY, monday)
        assertEquals(RunType.EASY, first.type)
        assertTrue(first.note.startsWith("После болезни сначала втягиваемся: ещё 2 лёгкие пробежки"), first.note)
        assertTrue("ускорен" !in first.title, first.title)
        ran(repo, monday)
        // Tuesday's gym: the first after being sick — lighter, said why; the plan does not move.
        at(monday.plusDays(1))
        val tue = repo.liftPlan("upper_b", monday.plusDays(1))
        assertTrue(tue.deload)
        assertEquals(TrainingRepository.SICK_LIFT, tue.why)
        db.workouts().insert(WorkoutEntity(epochDay = monday.plusDays(1).toEpochDay(), kind = Kind.LIFT, templateId = "upper_b", title = "Верх · Б", startedAt = ms(monday.plusDays(1)), finishedAt = ms(monday.plusDays(1)), status = Status.DONE, rpe = 6, durationSec = 3600))
        // Thursday: the second easy run; the gym as planned.
        at(monday.plusDays(3))
        assertEquals(1, repo.easyAfterSick(monday.plusDays(3)))
        assertEquals(RunType.EASY, repo.runFor(SessionKind.RUN_QUALITY, monday.plusDays(3)).type)
        val thu = repo.liftPlan("upper_a", monday.plusDays(3))
        assertFalse(thu.deload)
        assertNull(thu.why)
        ran(repo, monday.plusDays(3))
        // Then the plan as it was: hard sessions back.
        at(monday.plusDays(7))
        assertEquals(0, repo.easyAfterSick(monday.plusDays(7)))
        assertNotEquals(RunType.EASY, repo.runFor(SessionKind.RUN_QUALITY, monday.plusDays(7)).type)
    }

    @Test fun noTrialWithinAWeekOfBeingSick() = runBlocking<Unit> {
        val repo = denis("sick-trial")
        // Before: the trial on Thursday 22.10 (Monday comes two days after the legs).
        assertEquals(mon.plusDays(10), repo.trialDay(fri))
        sick(repo)
        // While the easy runs after it are not done, no trial at all.
        assertNull(repo.trialDay(fri))
        ran(repo, mon.plusDays(7))
        ran(repo, mon.plusDays(10))
        at(mon.plusDays(11))
        val day = assertNotNull(repo.trialDay(mon.plusDays(11)))
        assertTrue(day.toEpochDay() - fri.plusDays(2).toEpochDay() > TrainingRepository.SICK_NO_TRIAL_DAYS, "trial on $day")
    }

    /** Ten days without running and no run planned before: Thursday is the first run back — not the trial. */
    @Test fun aTrialIsNotTheFirstRunAfterABreak() = runBlocking<Unit> {
        val repo = denis("sick-break")
        val thursday = mon.plusDays(10)
        at(thursday)
        assertNotEquals(thursday, repo.trialDay(thursday))
        // Not a hard session in its place either: easy, said why.
        val w = repo.runFor(SessionKind.RUN_QUALITY, thursday)
        assertEquals(RunType.EASY, w.type)
        assertTrue("Контрольный — не первой пробежкой после перерыва" in w.note, w.note)
        // Back running (Thursday done): the trial on the next hard day with fresh legs.
        ran(repo, thursday)
        assertNotNull(repo.trialDay(thursday.plusDays(1)))
    }

    /** «Болею» pressed by mistake and set back the same day: no sickness. */
    @Test fun aMistapSetBackIsNoSickness() = runBlocking<Unit> {
        val repo = denis("sick-mistap")
        repo.store.editSettings { it.withFeel(fri, Feel.SICK) }
        repo.store.editSettings { it.withFeel(fri, Feel.GOOD) }
        assertEquals(0, repo.easyAfterSick(mon.plusDays(7)))
        assertNull(repo.sickEnd(repo.store.settingsNow(), mon.plusDays(7)))
    }
}
