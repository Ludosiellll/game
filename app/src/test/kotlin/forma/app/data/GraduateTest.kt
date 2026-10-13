package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.RunOutcome
import forma.core.run.RunResult
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seen in a thousand simulated half-years: a beginner who reached 30 minutes of running kept a week of easy runs only —
 * no hard session, no trial — for months. Graduating makes the week a runner's.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class GraduateTest {
    @Test fun thirtyMinutesMakeTheWeekARunnersWeek() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "graduate")
        store.clear()
        val day = LocalDate.of(2026, 10, 12)
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now = { day.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() })
        repo.completeOnboarding(
            Profile(aim = Aim(LiftFocus.NONE, RunFocus.ENDURANCE, Emphasis.RUN), runLevel = RunLevel.NONE, trainingDays = setOf(1, 3, 5, 6), runGoal = RunGoal(1500, 480)),
        )
        assertTrue(store.programNow()!!.week.days.flatMap { it.sessions }.none { it.kind == SessionKind.RUN_QUALITY })
        // The last session of run/walk, done in full and comfortably.
        store.setRunState(store.runStateNow().copy(walkStage = forma.core.run.RunPlan.walkLadderSize - 1, runs = 30))
        val w = repo.runFor(SessionKind.RUN_EASY, day)
        val (_, summary) = repo.saveRun(w, RunResult(1.0, 5, w.totalSeconds), 1L, emptyList(), day)
        assertTrue(RunOutcome.Graduated in summary.outcomes, summary.outcomes.toString())
        assertEquals(RunLevel.BASE, store.profileNow()!!.runLevel)
        assertTrue(store.programNow()!!.week.days.flatMap { it.sessions }.any { it.kind == SessionKind.RUN_QUALITY })
        assertEquals(-1, store.runStateNow().walkStage)
        // Gradually: easy runs no longer than the last run/walk week allows, the hard sessions after three of them.
        assertTrue(store.runStateNow().returnEasy >= 3)
        assertTrue(store.runStateNow().easyMinutes <= 30)
    }
}
