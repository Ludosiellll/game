package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.SettingsStore
import forma.app.data.Status
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.DayPlan
import forma.core.plan.PlannedSession
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.plan.WeekPlan
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis, Monday 2 November: «почему до сих пор так, типа вдвое меньше нагрузка пишет» — «Неделя 4 из 4. Разгрузка»
 * after a block he was ill in (12.10 start, ill from 15.10): of its three hard weeks one was full. The missed days were
 * rest already: the lighter week moves a week on.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class UnearnedLighterWeekTest {
    private lateinit var db: FormaDatabase
    private lateinit var store: SettingsStore
    private lateinit var repo: TrainingRepository
    private val monday = LocalDate.of(2026, 11, 2)
    private val blockStart = LocalDate.of(2026, 10, 12)

    // His sessions from the cloud: day, template, load.
    private val his = listOf(
        Triple("2026-10-12", "FREE", 182.0), Triple("2026-10-13", "upper_b", 560.0), Triple("2026-10-15", "upper_a", 468.0),
        Triple("2026-10-22", "upper_a", 372.0), Triple("2026-10-22", "EASY", 101.0), Triple("2026-10-23", "lower_a", 235.0),
        Triple("2026-10-23", "FREE", 68.0), Triple("2026-10-26", "GOAL_PACE", 250.0), Triple("2026-10-27", "upper_b", 644.0),
        Triple("2026-10-29", "TEST", 243.0), Triple("2026-10-29", "upper_a", 356.0), Triple("2026-10-31", "lower_a", 525.0),
        Triple("2026-10-31", "EASY", 100.0),
    )

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        store = SettingsStore(ctx, "unearned-lighter-week")
        store.clear()
        val clock = monday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 71.0, runGoal = RunGoal(1500, 330), lapMeters = 205,
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
        val p = store.programNow()!!
        store.setProgram(
            p.copy(
                startEpochDay = blockStart.toEpochDay(), blockWeeks = 4,
                week = WeekPlan(
                    listOf(
                        DayPlan(1, listOf(PlannedSession(SessionKind.RUN_QUALITY))),
                        DayPlan(2, listOf(PlannedSession(SessionKind.LIFT, "upper_b"))),
                        DayPlan(4, listOf(PlannedSession(SessionKind.LIFT, "upper_a"), PlannedSession(SessionKind.RUN_QUALITY))),
                        DayPlan(6, listOf(PlannedSession(SessionKind.LIFT, "lower_a"), PlannedSession(SessionKind.RUN_EASY))),
                    ),
                    p.week.notes,
                ),
            ),
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun add(day: String, template: String, load: Double) {
        val d = LocalDate.parse(day)
        val at = d.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val lift = template.contains('_') && template != "GOAL_PACE"
        db.workouts().insert(
            WorkoutEntity(
                epochDay = d.toEpochDay(), kind = if (lift) Kind.LIFT else Kind.RUN, templateId = template, title = template,
                startedAt = at, finishedAt = at + 3_600_000, status = Status.DONE, rpe = 6, durationSec = 3600, load = load,
            ),
        )
    }

    @Test fun hisLighterWeekMovesOn() = runBlocking<Unit> {
        his.forEach { (d, t, l) -> add(d, t, l) }
        assertTrue(ProgramGenerator.isDeloadWeek(store.programNow()!!, monday), "the precondition: this week is the planned lighter one")
        repo.moveUnearnedLighterWeek(monday)
        val p = store.programNow()!!
        assertFalse(ProgramGenerator.isDeloadWeek(p, monday), "this week is not lighter any more")
        assertTrue(ProgramGenerator.isDeloadWeek(p, monday.plusWeeks(1)), "the lighter week is next week")
        assertEquals(monday.toEpochDay(), store.liftStateNow().deloadMovedFrom)
        // Once is enough: called again it changes nothing.
        repo.moveUnearnedLighterWeek(monday)
        assertTrue(ProgramGenerator.isDeloadWeek(store.programNow()!!, monday.plusWeeks(1)))
    }

    @Test fun aBlockTrainedInFullKeepsItsLighterWeek() = runBlocking<Unit> {
        // The same full week three times: the lighter week stays.
        for (w in 0..2L) his.drop(7).forEach { (d, t, l) -> add(LocalDate.parse(d).minusWeeks(w).toString(), t, l) }
        repo.moveUnearnedLighterWeek(monday)
        assertTrue(ProgramGenerator.isDeloadWeek(store.programNow()!!, monday))
        assertNull(store.liftStateNow().deloadMovedFrom)
    }

    @Test fun notOnceTheGymOfTheWeekHasBegun() = runBlocking<Unit> {
        his.forEach { (d, t, l) -> add(d, t, l) }
        add("2026-11-02", "upper_b", 300.0)
        repo.moveUnearnedLighterWeek(monday)
        assertTrue(ProgramGenerator.isDeloadWeek(store.programNow()!!, monday))
    }
}
