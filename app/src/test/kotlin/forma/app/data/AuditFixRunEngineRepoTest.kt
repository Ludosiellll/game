package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.PaceTable
import forma.core.run.RunState
import forma.core.run.RunType
import forma.core.run.SegmentKind
import forma.core.run.TempoShape
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The run engine's findings of the audit of 24 November (group D) that live in the repository, each fixed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AuditFixRunEngineRepoTest {
    /** Denis's profile and running as the cloud had them on 16.10 (see AfterSickTest). */
    private val profileJson = """{"aim": {"run": "ENDURANCE", "lift": "POWERBUILDING", "emphasis": "BALANCED"}, "sex": "MALE", "goal": "HYBRID", "gymDays": [2, 4, 6], "runDays": [4, 6, 1], "runGoal": {"date": null, "meters": 1500, "seconds": 330}, "heightCm": 183, "runLevel": "BASE", "birthYear": 2008, "lapMeters": 230, "restingHr": 65, "doubleDays": true, "experience": "ADVANCED", "bodyWeightKg": 70.0, "trainingDays": [2, 4, 6, 1], "sessionMinutes": 75}"""
    private val runJson = """{"runs": 2, "vdot": null, "recent": [{"day": 20738, "minutes": 24}, {"day": 20738, "minutes": 2}], "walkStage": -1, "lastRunDay": 20738, "easyMinutes": 25, "longMinutes": 40, "qualityCount": 0, "testSoon": true}"""
    private val zone = ZoneId.systemDefault()
    private val mon = LocalDate.of(2026, 10, 12)
    private val fri = mon.plusDays(4)
    private var clock = mon.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
    private lateinit var db: FormaDatabase

    @After fun tearDown() { if (::db.isInitialized) db.close() }

    private fun at(d: LocalDate, hour: Int = 9) { clock = d.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli() }
    private fun ms(d: LocalDate) = d.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()

    private suspend fun repo(name: String, profile: Profile): TrainingRepository {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, name)
        store.clear()
        val repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(profile)
        return repo
    }

    /** Denis's week of 12.10 with the race on [race]: a free run on Monday 12.10, none since. */
    private suspend fun denisRacing(name: String, race: LocalDate): TrainingRepository {
        val p = AppJson.decodeFromString(Profile.serializer(), profileJson)
        val repo = repo(name, p.copy(runGoal = RunGoal(1500, 330, race.toEpochDay())))
        repo.store.setRunState(AppJson.decodeFromString(RunState.serializer(), runJson))
        db.workouts().insert(WorkoutEntity(epochDay = mon.toEpochDay(), kind = Kind.RUN, templateId = "FREE", title = "Свободный бег", startedAt = ms(mon), finishedAt = ms(mon), status = Status.DONE, rpe = 7, durationSec = 1435))
        at(fri)
        return repo
    }

    /** run-level-change-resets-estimate */
    @Test fun anotherRunningLevelKeepsWhatTheRunsHaveShown() = runBlocking<Unit> {
        val repo = repo(
            "audit-level", Profile(goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 3, 5, 6), bodyWeightKg = 80.0, runGoal = RunGoal(1500, 330)),
        )
        val shown = repo.store.runStateNow().copy(
            estVdot = 38.0, estUpDay = 20700, goalRepPace = 220.0, goalRepPaces = mapOf(400 to 228.0), tempoOwn = TempoShape(6, 150, 60),
            tempoSoft = 3, easyRpes = listOf(6, 6, 6, 6, 6), easySigns = listOf(1, 0, 0), returnEasy = 2, lastTrialTryDay = 20750,
            noTrialUntil = 20800, goalDeferredOn = 20760, qualityAhead = 1, runs = 9,
        )
        repo.store.setRunState(shown)
        repo.updateProfile(repo.store.profileNow()!!.copy(runLevel = RunLevel.TRAINED))
        val st = repo.store.runStateNow()
        // The new level's starting lengths and steps.
        assertEquals(35, st.easyMinutes)
        assertEquals(2, st.intervalStage)
        // What the runs have shown stays.
        assertEquals(38.0, st.estVdot)
        assertEquals(shown.estUpDay, st.estUpDay)
        assertEquals(220.0, st.goalRepPace)
        assertEquals(mapOf(400 to 228.0), st.goalRepPaces)
        assertEquals(shown.tempoOwn, st.tempoOwn)
        assertEquals(3, st.tempoSoft)
        assertEquals(shown.easyRpes, st.easyRpes)
        assertEquals(shown.easySigns, st.easySigns)
        assertEquals(2, st.returnEasy)
        assertEquals(shown.lastTrialTryDay, st.lastTrialTryDay)
        assertEquals(shown.noTrialUntil, st.noTrialUntil)
        assertEquals(shown.goalDeferredOn, st.goalDeferredOn)
        assertEquals(1, st.qualityAhead)
        assertEquals(9, st.runs)
        // The next hard day: an easy run still owed after the break, paced from the estimate of 38 (not the goal's 41).
        val w = repo.runFor(SessionKind.RUN_QUALITY, repo.today())
        assertEquals(RunType.EASY, w.type, w.title)
        assertEquals(PaceTable.forVdot(38.0).easy, w.segments.first { it.kind == SegmentKind.RUN }.pace)
    }

    /** run-taper-skips-sick-and-break */
    @Test fun theWeekBeforeARaceKeepsTheSickRule() = runBlocking<Unit> {
        // «Болею» on Friday 16.10 with a pause to Sunday, the race on Friday 23.10.
        val repo = denisRacing("audit-taper-sick", fri.plusDays(7))
        repo.store.editSettings { it.withFeel(fri, Feel.SICK) }
        repo.pause(fri, fri.plusDays(2))
        val monday = mon.plusDays(7)
        at(monday)
        assertEquals(2, repo.easyAfterSick(monday))
        // Four days before the race: easy, no reps and no strides — not «Последняя тяжёлая перед забегом».
        val hard = repo.runFor(SessionKind.RUN_QUALITY, monday)
        assertEquals(RunType.EASY, hard.type, hard.title)
        assertTrue(hard.note.startsWith("После болезни"), hard.note)
        assertTrue("ускорен" !in hard.title, hard.title)
        val easy = repo.runFor(SessionKind.RUN_EASY, monday)
        assertTrue(easy.note.startsWith("После болезни"), easy.note)
        assertTrue("ускорен" !in easy.title, easy.title)
    }

    /** run-taper-skips-sick-and-break */
    @Test fun theWeekBeforeARaceKeepsTheBreakRules() = runBlocking<Unit> {
        // No run since Monday 12.10, the race on Saturday 24.10: Wednesday 21.10 is three days before it, nine days off.
        val wed = LocalDate.of(2026, 10, 21)
        val repo = denisRacing("audit-taper-break", wed.plusDays(3))
        at(wed)
        val back = repo.runFor(SessionKind.RUN_QUALITY, wed)
        assertEquals(RunType.EASY, back.type, back.title)
        assertTrue("После перерыва не начинаем с тяжёлой пробежки" in back.note, back.note)
        // Easy runs still owed after a long break: the hard day before the race is one of them, the easy day has no strides.
        repo.store.setRunState(repo.store.runStateNow().copy(returnEasy = 2, lastRunDay = wed.toEpochDay() - 1))
        val owed = repo.runFor(SessionKind.RUN_QUALITY, wed)
        assertEquals(RunType.EASY, owed.type, owed.title)
        assertTrue("тяжёлый бег вернётся через 2 лёгкие пробежки" in owed.note, owed.note)
        val easy = repo.runFor(SessionKind.RUN_EASY, wed)
        assertTrue("ускорен" !in easy.title, easy.title)
    }
}
