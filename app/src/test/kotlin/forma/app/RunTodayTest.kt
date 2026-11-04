package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.AppJson
import forma.app.data.CoachActions
import forma.app.data.CoachTerm
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.RunSummary
import forma.app.data.SettingsStore
import forma.app.data.Status
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.app.sync.CoachCall
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
import forma.core.plan.DayPlan
import forma.core.plan.Focus
import forma.core.plan.PlannedSession
import forma.core.plan.SessionKind
import forma.core.plan.WeekPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis, Wednesday 4 November: Monday's tempo run missed, the gym done on Tuesday, a tempo run again on Thursday. «Делай»:
 * the app offers the missed run as an easy one, the coach can put an easy run on today, and the easy run started for
 * the missed one is Monday's in the week.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RunTodayTest {
    private lateinit var db: FormaDatabase
    private lateinit var store: SettingsStore
    private lateinit var repo: TrainingRepository
    private val monday = LocalDate.of(2026, 11, 2)
    private val wednesday = monday.plusDays(2)
    private var clock = wednesday.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        store = SettingsStore(ctx, "run-today")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock++ })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 71.0, runGoal = RunGoal(1500, 330), lapMeters = 205,
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
        val p = store.programNow()!!
        // His days: two upper ones (no legs) and a lower one.
        val t = p.templates.first()
        val templates = listOf(
            t.copy(id = "upper_b", title = "Верх · Б", focus = Focus.UPPER), t.copy(id = "upper_a", title = "Верх · А", focus = Focus.UPPER),
            t.copy(id = "lower_a", title = "Низ", focus = Focus.LOWER),
        )
        val upper = listOf("upper_b", "upper_a")
        val lower = "lower_a"
        store.setProgram(
            p.copy(
                templates = templates,
                createdEpochDay = monday.minusWeeks(3).toEpochDay(),
                week = WeekPlan(
                    listOf(
                        DayPlan(1, listOf(PlannedSession(SessionKind.RUN_QUALITY))),
                        DayPlan(2, listOf(PlannedSession(SessionKind.LIFT, upper[0]))),
                        DayPlan(4, listOf(PlannedSession(SessionKind.RUN_QUALITY), PlannedSession(SessionKind.LIFT, upper.getOrElse(1) { upper[0] }))),
                        DayPlan(6, listOf(PlannedSession(SessionKind.LIFT, lower), PlannedSession(SessionKind.RUN_EASY))),
                    ),
                    p.week.notes,
                ),
            ),
        )
        add(monday.plusDays(1), Kind.LIFT, upper[0], null)
    }

    @After fun tearDown() = db.close()

    private suspend fun add(d: LocalDate, kind: String, template: String, summary: RunSummary?) {
        val at = d.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        db.workouts().insert(
            WorkoutEntity(
                epochDay = d.toEpochDay(), kind = kind, templateId = template, title = template, startedAt = at, finishedAt = at + 1_800_000,
                status = Status.DONE, rpe = 4, durationSec = 1800, load = 120.0,
                summaryJson = summary?.let { AppJson.encodeToString(RunSummary.serializer(), it) },
            ),
        )
    }

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) } + ("why" to JsonPrimitive("проверка"))),
    )

    @Test fun theMissedTempoIsOfferedEasyAndCountsForMonday() = runBlocking<Unit> {
        val c = assertNotNull(repo.catchUp(wednesday))
        assertEquals(SessionKind.RUN_QUALITY, c.session.kind)
        assertEquals(1, c.plannedOn)
        assertTrue(c.easy)
        assertTrue("Пропущено на этой неделе: тяжёлый бег (было в понедельник)" in coachContext(repo, wednesday))
        // Run as the card starts it: marked.
        add(wednesday, Kind.RUN, "EASY", RunSummary(emptyList(), 30, 120.0, instead = true))
        val week = repo.weekFlow(monday).first()
        assertTrue(week.first { it.date == monday }.isDone(PlannedSession(SessionKind.RUN_QUALITY)), "Monday's tempo is done (easier)")
        assertTrue(!week.first { it.date == monday.plusDays(5) }.isDone(PlannedSession(SessionKind.RUN_EASY)), "Saturday's easy run is still ahead")
        assertNull(repo.catchUp(wednesday))
    }

    @Test fun theCoachPutsAnEasyRunOnToday() = runBlocking<Unit> {
        val ready = assertIs<CoachActions.Ready>(CoachActions.prepare(repo, call(CoachActions.RUN_TODAY, "minutes" to 30), wednesday))
        val entry = CoachActions.make(repo, ready)
        assertEquals(CoachTerm(30, wednesday.toEpochDay()), store.settingsNow().coachRunToday)
        assertTrue("run_today" in coachContext(repo, wednesday))
        val w = repo.runFor(SessionKind.RUN_EASY, wednesday, minutes = 30)
        assertTrue(w.segments.sumOf { it.seconds } in 25 * 60..32 * 60, "about 30 min: ${w.segments.sumOf { it.seconds }} s")
        assertTrue(CoachActions.undo(repo, entry))
        assertNull(store.settingsNow().coachRunToday)
        // Not on a day with a run in the plan.
        assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call(CoachActions.RUN_TODAY, "minutes" to 30), monday.plusDays(3)))
    }
}
