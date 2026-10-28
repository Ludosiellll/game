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
import forma.core.load.LoadStory
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
 * Review probe for a2dc0a7: the day a dip starts. Eight steady weeks, then today (a Monday) one very hard session: the
 * freshness «Сегодня» shows drops under the rest line for the first time. The brief must not say both «нужен отдых,
 * свежесть ниже линии отдыха» and «за 8 недель ни разу не опускался ниже линии отдыха».
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CoachFreshnessReviewTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    /** Monday 26 Oct 2026. */
    private val today = LocalDate.of(2026, 10, 26)
    private var clock = today.atTime(21, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private suspend fun session(d: LocalDate, load: Double, rpe: Int, i: Int) {
        val at = d.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        db.workouts().insert(
            WorkoutEntity(
                epochDay = d.toEpochDay(), kind = if (i % 2 == 0) Kind.LIFT else Kind.RUN, templateId = null, title = "Тренировка",
                startedAt = at, finishedAt = at + 3_600_000, status = Status.DONE, rpe = rpe, durationSec = 3600, load = load,
            ),
        )
    }

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "coach-freshness-probe")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 71.0, aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
        var d = today.minusDays(56)
        var i = 0
        while (d < today) {
            if (d.dayOfWeek.value in setOf(1, 2, 4, 6)) { session(d, 300.0, 6, i); i++ }
            d = d.plusDays(1)
        }
        // Today: a very hard, long session (10/10 for 90 minutes).
        session(today, 900.0, 10, i)
    }

    @After fun tearDown() = db.close()

    @Test fun theBriefDoesNotDenyTheDipThatStartedToday() = runBlocking<Unit> {
        val loads = repo.dailyLoads().first()
        val series = TrainingLoad.series(loads, today.minusDays(120), today)
        val shownPct = series.last().formPct
        val yesterday = series[series.lastIndex - 1].formPct
        // The precondition of the scenario: the first day under the line is today.
        assertTrue(yesterday >= LoadStory.COMMON_REST && shownPct < LoadStory.COMMON_REST, "yesterday ${Ru.pct(yesterday)}, today ${Ru.pct(shownPct)}")
        val brief = coachContext(repo, today)
        val top = brief.substringBefore("## Цели человека")
        assertTrue("Свежесть сейчас: ${Ru.pct(shownPct)}" in top, top)
        val load = brief.substringAfter("### Нагрузка и восстановление").substringBefore("## Тренировки")
        val needsRest = "Нужен отдых" in load && "ниже твоей линии отдыха" in load
        val never = "ни разу не опускался ниже линии отдыха" in load
        assertTrue(!(needsRest && never), "«Сегодня» shows ${Ru.pct(shownPct)} (нужен отдых); the brief's load section says both:\n$load")
    }
}
