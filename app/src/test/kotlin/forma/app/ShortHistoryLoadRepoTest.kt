package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.Feel
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.SettingsStore
import forma.app.data.Status
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.core.load.LoadStory
import forma.core.load.TrainingLoad
import forma.core.plan.DayPlan
import forma.core.plan.PlannedSession
import forma.core.plan.SessionKind
import forma.core.plan.WeekPlan
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import kotlinx.coroutines.flow.first
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
 * Denis, 1 November: «приложение считает что мой объем увеличился в 2 раза ... предлагает тренироваться легче». His own
 * sessions from the cloud: two short weeks (the app new, then ill), then a full week by plan. The plan is now the floor
 * of the usual week and of the starting fitness while history is short — on «Сегодня», for the coach, everywhere alike.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ShortHistoryLoadRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var store: SettingsStore
    private lateinit var repo: TrainingRepository
    private val today = LocalDate.of(2026, 11, 1)

    // day, kind, template, load — as in the cloud on 1 November.
    private val sessions = listOf(
        Triple("2026-10-12", "FREE", 182.0), Triple("2026-10-13", "upper_b", 560.0), Triple("2026-10-15", "upper_a", 468.0),
        Triple("2026-10-22", "upper_a", 372.0), Triple("2026-10-22", "EASY", 101.0), Triple("2026-10-23", "lower_a", 235.0),
        Triple("2026-10-23", "FREE", 68.0), Triple("2026-10-26", "GOAL_PACE", 250.0), Triple("2026-10-27", "upper_b", 644.0),
        Triple("2026-10-29", "TEST", 243.0), Triple("2026-10-29", "upper_a", 356.0), Triple("2026-10-31", "lower_a", 525.0),
        Triple("2026-10-31", "EASY", 100.0),
    )

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        store = SettingsStore(ctx, "short-history-load")
        store.clear()
        val clock = today.atTime(15, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 71.0, runGoal = RunGoal(1500, 330), lapMeters = 205,
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
        // His week (notes, 14.10): runs Monday, Thursday, Saturday; gym Tuesday, Thursday, Saturday. Thursday's run as an
        // easy one — this week's trial in its place made the week a little above it.
        val p = store.programNow()!!
        store.setProgram(
            p.copy(
                week = WeekPlan(
                    listOf(
                        DayPlan(1, listOf(PlannedSession(SessionKind.RUN_QUALITY))),
                        DayPlan(2, listOf(PlannedSession(SessionKind.LIFT, "upper_b"))),
                        DayPlan(4, listOf(PlannedSession(SessionKind.LIFT, "upper_a"), PlannedSession(SessionKind.RUN_EASY))),
                        DayPlan(6, listOf(PlannedSession(SessionKind.LIFT, "lower_a"), PlannedSession(SessionKind.RUN_EASY))),
                    ),
                    p.week.notes,
                ),
            ),
        )
        for ((day, template, load) in sessions) {
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
    }

    @After fun tearDown() = db.close()

    @Test fun hisFullWeekIsNotAJumpAndHeIsNotRunDown() = runBlocking<Unit> {
        val st = repo.loadStoryNow(today)!!
        val week = st.weeks.last()
        assertTrue(week.level != LoadStory.Level.SPIKE, "this week: ${week.level}, ratio ${week.ratio}, usual ${st.usualWeek}")
        assertTrue(st.usualFromPlan)
        // The week ill reads as lighter than usual.
        assertEquals(LoadStory.Level.LIGHT, st.weeks.first { it.monday == LocalDate.of(2026, 10, 19) }.level)
        assertTrue(st.today.formPct > LoadStory.COMMON_REST, "freshness ${st.today.formPct}")
        assertTrue(st.zone != LoadStory.Zone.REST)
        // «Сегодня» draws the same number.
        val loads = repo.dailyLoads().first()
        val shown = TrainingLoad.series(loads, today.minusDays(120), today, repo.loadBase().first()).last().formPct
        assertEquals(st.today.formPct, shown, 0.01)
    }

    @Test fun daysMarkedIllStayOutOfTheUsual() = runBlocking<Unit> {
        // Had he pressed «Болею» from 16 to 21 October, that week is no usual one either.
        repo.updateSettings { s -> (16..21).fold(s) { acc, d -> acc.withFeel(LocalDate.of(2026, 10, d), Feel.SICK) } }
        val base = repo.loadBase().first()
        assertTrue(LocalDate.of(2026, 10, 18) in base.off)
        assertTrue(repo.loadStoryNow(today)!!.weeks.last().level != LoadStory.Level.SPIKE)
    }
}
