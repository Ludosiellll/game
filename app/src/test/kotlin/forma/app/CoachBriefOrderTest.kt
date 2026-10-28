package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.BodyWeightEntity
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.SettingsStore
import forma.app.data.Status
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
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
import kotlin.test.assertTrue

/**
 * Denis: «он в целом все видит?» The coach's brief has a size limit, and the report's list of workouts (newest first) is
 * long: what stood after it — the body weight over time — was cut with months of workouts. Now the list goes last: the
 * cut drops the oldest workouts only, the summaries before it stay.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CoachBriefOrderTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private val today = LocalDate.of(2026, 10, 28)

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "coach-brief-order")
        store.clear()
        val clock = today.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 71.0, runGoal = RunGoal(1500, 330), lapMeters = 205,
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
        // Half a year of sessions: four a week.
        var d = today.minusWeeks(26)
        while (d < today) {
            if (d.dayOfWeek.value in setOf(1, 2, 4, 6)) {
                val at = d.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                db.workouts().insert(
                    WorkoutEntity(
                        epochDay = d.toEpochDay(), kind = Kind.LIFT, templateId = null, title = "Тренировка ${d}", startedAt = at,
                        finishedAt = at + 3_600_000, status = Status.DONE, rpe = 7, durationSec = 3600, load = 420.0,
                    ),
                )
            }
            d = d.plusDays(1)
        }
        // His weight, logged now and then.
        listOf(26L, 13L, 1L).forEach { w -> db.state().upsertBodyWeight(BodyWeightEntity(today.minusWeeks(w).toEpochDay(), 71.0 + w / 26.0)) }
    }

    @After fun tearDown() = db.close()

    @Test fun theSummariesStayWhenALongHistoryIsCut() = runBlocking<Unit> {
        val brief = coachContext(repo, today, reportChars = 6_000)
        val goal = brief.indexOf("## Путь к цели")
        val months = brief.indexOf("## Сводка по месяцам")
        val list = brief.indexOf("## Тренировки (сначала новые)")
        assertTrue(goal >= 0 && months >= 0, "the way to the goal and the months are in the brief: goal $goal, months $months")
        assertTrue("## Вес тела" in brief, "the body weight over time stays")
        assertTrue(list < 0 || list > goal, "the list of workouts comes after them")
        // The newest workout is there, the oldest cut.
        assertTrue("Тренировка ${today.minusDays(1)}" in brief || "Тренировка ${today.minusDays(2)}" in brief, "the newest workouts stay")
        assertTrue("Тренировка ${today.minusWeeks(26)}" !in brief, "the oldest are cut")
    }
}
