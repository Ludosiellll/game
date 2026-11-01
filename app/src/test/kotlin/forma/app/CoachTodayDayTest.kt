package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.coachContext
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Denis, Sunday 1 November: «тренер не всегда знает какой сегодня день». The brief said only «Сегодня 1 ноября 2026», and
 * the model worked the weekday out itself, wrongly: it called Sunday Monday and gave Monday's run. The brief now says the
 * weekday and the time on the phone in its first line.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CoachTodayDayTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private val sunday = LocalDate.of(2026, 11, 1)

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "coach-today-day")
        store.clear()
        val clock = sunday.atTime(15, 38).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 71.0, runGoal = RunGoal(1500, 330), lapMeters = 205,
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
    }

    @After fun tearDown() = db.close()

    @Test fun firstLineSaysWeekdayDateAndTime() = runBlocking {
        val first = coachContext(repo).lineSequence().first()
        assertEquals("Сегодня воскресенье, 1 ноября 2026, время 15:38.", first)
    }

    @Test fun planLineNamesTheDay() = runBlocking {
        val brief = coachContext(repo)
        assertTrue("По плану сегодня (воскресенье)" in brief, brief.take(600))
    }

    @Test fun anotherDayHasNoClock() = runBlocking {
        // A brief for a day other than the clock's (tests, simulations): the time of day would be wrong, so none.
        val first = coachContext(repo, today = sunday.plusDays(1)).lineSequence().first()
        assertEquals("Сегодня понедельник, 2 ноября 2026.", first)
    }
}
