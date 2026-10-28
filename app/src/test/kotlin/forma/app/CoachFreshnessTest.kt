package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.SettingsStore
import forma.app.data.Status
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.app.ui.screens.coachContext
import forma.core.load.TrainingLoad
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.text.Ru
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertTrue

/**
 * Denis: «в самом приложении показывает -18 свежесть (вчера было -33) однако когда задаю вопрос тренеру, то он до сих пор
 * говорит что свежесть -33». The coach's brief says the freshness «Сегодня» shows, on a rest day after a hard one too.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CoachFreshnessTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    /** Wednesday 28 Oct 2026, a rest day after Tuesday's hard session. */
    private val today = LocalDate.of(2026, 10, 28)
    private var clock = today.atTime(7, 10).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "coach-freshness")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 71.0, aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
        // Four weeks of sessions, the last ones heavy, the last of all yesterday.
        var d = today.minusDays(28)
        var i = 0
        while (d < today) {
            if (d.dayOfWeek.value in setOf(1, 2, 4, 6)) {
                val heavy = d >= today.minusDays(6)
                val at = d.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                db.workouts().insert(
                    WorkoutEntity(
                        epochDay = d.toEpochDay(), kind = if (i % 2 == 0) Kind.LIFT else Kind.RUN, templateId = null, title = "Тренировка",
                        startedAt = at, finishedAt = at + 3_600_000, status = Status.DONE, rpe = if (heavy) 9 else 6, durationSec = 3600,
                        load = if (heavy) 540.0 else 300.0,
                    ),
                )
                i++
            }
            d = d.plusDays(1)
        }
    }

    @After fun tearDown() = db.close()

    @Test fun theCoachSaysTheFreshnessTodayShows() = runBlocking<Unit> {
        // As «Сегодня» works it out (Today.kt: the series of the daily loads up to today).
        val loads = repo.dailyLoads().first()
        val shown = Ru.pct(TrainingLoad.series(loads, today.minusDays(120), today).last().formPct)
        val brief = coachContext(repo, today)
        // Said at the top, as the screen shows it.
        val top = brief.substringBefore("## Цели человека")
        assertTrue("Свежесть сейчас: $shown" in top, "«Сегодня» shows $shown; the top of the brief: $top")
        // Nowhere else a freshness of today that is not that one, and never "below the line now" while above it.
        val load = brief.substringAfter("### Нагрузка и восстановление").substringBefore("## Тренировки")
        assertTrue(!("выше линии отдыха" in load && "ты ниже линии отдыха" in load), "the brief says both above and below the rest line now: $load")
        assertTrue("свежесть до " !in load, "«свежесть до X» reads as the freshness now: $load")
        Regex("(−?[0-9]+ %) \\(28 октября\\)").findAll(load).forEach {
            assertTrue(it.groupValues[1] == shown, "a freshness dated today that is not today's: ${it.value} vs $shown")
        }
    }
}
