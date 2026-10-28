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
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
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

/**
 * Saturday's easy run in Denis's week (1500 m in 5:30; race pace on Monday, the trial on Thursday, the gym Tue/Thu/Sat),
 * as the repository plans it: the week cap took his first partial weeks as the base and counted the hard sessions'
 * warm-ups and rests as running, so his only easy run of the week was planned at 15 minutes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RunWeekCapRepoTest {
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

    /** His running progress after 26.10 as his phone holds it (logs kept before the running in them was counted apart). */
    private val after26 = RunState(
        walkStage = -1, easyMinutes = 20, longMinutes = 35, runs = 5, qualityCount = 1, lastRunDay = 20752, easyRpes = listOf(7),
        recent = listOf(RunLog(20738, 24), RunLog(20738, 2), RunLog(20748, 25), RunLog(20749, 17), RunLog(20752, 36)),
    )

    private val saturday = LocalDate.of(2026, 10, 31)

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "run-week-cap")
        store.clear()
        at(LocalDate.of(2026, 10, 12))
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(denis)
        repo.store.setProgram(repo.store.programNow()!!.copy(startEpochDay = 20738, createdEpochDay = 20739))
    }

    @After fun tearDown() = db.close()

    @Test fun hisOnlyEasyRunOfTheWeekIsHisWholeEasyLength() = runBlocking<Unit> {
        repo.store.setRunState(after26)
        at(saturday, 18)
        val easy = repo.runFor(SessionKind.RUN_EASY, saturday)
        assertEquals(RunType.EASY, easy.type)
        assertEquals(20 * 60, easy.workSeconds, "${easy.title}; week cap ${RunPlan.weekCap(after26, saturday.toEpochDay())}")
        assertEquals("Лёгкий бег · 20 мин + 4 ускорения", easy.title)
    }

    @Test fun afterRacePaceAndTheTrialHisEasyRunIsStillHisWholeEasyLength() = runBlocking<Unit> {
        // The week as it goes: race pace on Monday, the trial on Thursday, both saved through the repository.
        repo.store.setRunState(after26.copy(qualityCount = 0, runs = 4, lastRunDay = 20749, recent = after26.recent.dropLast(1)))
        val mon = LocalDate.of(2026, 10, 26)
        at(mon, 14)
        val race = repo.runFor(SessionKind.RUN_QUALITY, mon)
        assertEquals(RunType.GOAL_PACE, race.type, race.title)
        val laps = listOf(46.399, 49.982, 48.993, 47.286, 46.614).mapIndexed { i, s -> Lap(s, 49.91, 51.29, 1, 1, i + 1) }
        repo.saveRun(
            race, RunResult(1.0, 7, 2142, distanceM = 20 * 230.0, workPace = laps.sumOf { it.seconds } / 1.15, workSec = 240, laps = laps, reps = 5),
            clock - 2142_000L, emptyList(), mon,
        )
        val thu = LocalDate.of(2026, 10, 29)
        at(thu, 14)
        val trial = repo.runFor(SessionKind.RUN_QUALITY, thu)
        assertEquals(RunType.TEST, trial.type, trial.title)
        repo.saveRun(trial, RunResult(1.0, 9, 1700, workSec = 330, testSeconds = 330), clock - 1700_000L, emptyList(), thu)
        val st = repo.store.runStateNow()
        assertEquals(listOf(RunLog(20752, 36, 4), RunLog(20755, 28, 6)), st.recent.takeLast(2))
        at(saturday, 18)
        val easy = repo.runFor(SessionKind.RUN_EASY, saturday)
        assertEquals(st.easyMinutes * 60, easy.workSeconds, "${easy.title}; week cap ${RunPlan.weekCap(st, saturday.toEpochDay())}")
    }
}
