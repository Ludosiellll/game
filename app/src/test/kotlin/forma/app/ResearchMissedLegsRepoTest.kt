package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.MovedRun
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
import forma.core.plan.DayPlan
import forma.core.plan.Focus
import forma.core.plan.PlannedSession
import forma.core.plan.SessionKind
import forma.core.plan.WeekPlan
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Research one-leg-day+D1 (c), approved by Denis on 25 November, on his week: a hard run on Monday and Thursday, «Верх · Б»
 * on Tuesday, «Верх · А» on Thursday, «Низ» and an easy run on Saturday. His last «Низ» on 7.11; the week of 16.11 done but
 * Saturday. On Sunday 22.11 the missed «Низ» is offered with Monday's hard run easy for that day, or run on Tuesday
 * (counted for Monday, Thursday's stays). With legs three days before, Sunday offers nothing against the 48 h rule.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ResearchMissedLegsRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var store: SettingsStore
    private lateinit var repo: TrainingRepository
    private val monday = LocalDate.of(2026, 11, 16)
    private val sunday = monday.plusDays(6)
    private val nextMonday = monday.plusDays(7)
    private val tuesday = monday.plusDays(8)
    private val thursday = monday.plusDays(10)
    private var clock = sunday.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val hard = PlannedSession(SessionKind.RUN_QUALITY)

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        store = SettingsStore(ctx, "research-missed-legs")
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
        // His days: two upper ones (no legs), a lower one, and a full-body one for the test of legs three days before.
        val t = p.templates.first()
        store.setProgram(
            p.copy(
                templates = listOf(
                    t.copy(id = "upper_b", title = "Верх · Б", focus = Focus.UPPER), t.copy(id = "upper_a", title = "Верх · А", focus = Focus.UPPER),
                    t.copy(id = "lower_a", title = "Низ", focus = Focus.LOWER), t.copy(id = "full_x", title = "Всё тело", focus = Focus.FULL),
                ),
                createdEpochDay = monday.minusWeeks(4).toEpochDay(),
                week = WeekPlan(
                    listOf(
                        DayPlan(1, listOf(hard)),
                        DayPlan(2, listOf(PlannedSession(SessionKind.LIFT, "upper_b"))),
                        DayPlan(4, listOf(hard, PlannedSession(SessionKind.LIFT, "upper_a"))),
                        DayPlan(6, listOf(PlannedSession(SessionKind.LIFT, "lower_a"), PlannedSession(SessionKind.RUN_EASY))),
                    ),
                    p.week.notes,
                ),
            ),
        )
        // His last «Низ»: 7 November.
        add(LocalDate.of(2026, 11, 7), Kind.LIFT, "lower_a")
    }

    @After fun tearDown() = db.close()

    private var seq = 0L

    /** A workout done on [d]; those of one day in the order added. */
    private suspend fun add(d: LocalDate, kind: String, template: String): Long {
        val at = d.atTime(16, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 60_000 * seq++
        return db.workouts().insert(
            WorkoutEntity(
                epochDay = d.toEpochDay(), kind = kind, templateId = template, title = template, startedAt = at, finishedAt = at + 1_800_000,
                status = Status.DONE, rpe = 6, durationSec = 1800, load = 120.0,
            ),
        )
    }

    /** The week of 16.11 as he did it, but Saturday. */
    private suspend fun hisWeek() {
        add(monday, Kind.RUN, "INTERVALS")
        add(monday.plusDays(1), Kind.LIFT, "upper_b")
        add(monday.plusDays(3), Kind.RUN, "TEMPO")
        add(monday.plusDays(3), Kind.LIFT, "upper_a")
    }

    @Test fun theMissedLowerIsOfferedOnSundayWithBothChoices() = runBlocking<Unit> {
        hisWeek()
        assertEquals(15, repo.legsDaysAgo(sunday))
        val c = assertNotNull(repo.catchUp(sunday))
        assertEquals(PlannedSession(SessionKind.LIFT, "lower_a"), c.session, "«Низ» first, not «Верх · А» or the easy run")
        assertEquals(6, c.plannedOn)
        assertTrue(c.beforeHard)
        assertTrue(c.moveOk, "Tuesday: 48 h after the legs and 48 h before Thursday")
        assertEquals(15, c.legsDaysAgo)
        // The coach says the same in one line: the «Низ» today, Monday's hard run easy or on Tuesday.
        val brief = coachContext(repo, sunday)
        assertTrue("а завтрашний тяжёлый бег тогда лёгкий или переносится во вторник" in brief, brief.lines().firstOrNull { "Пропущено" in it })
    }

    @Test fun anEasyMondayIsEasyThatDayOnly() = runBlocking<Unit> {
        hisWeek()
        repo.chooseForLegs(sunday, move = false)
        val s = store.settingsNow()
        assertEquals(nextMonday.toEpochDay(), s.easyRunDays[1])
        assertNotNull(repo.easedUntil(s, nextMonday))
        assertNull(repo.easedUntil(s, nextMonday.plusWeeks(1)), "the Monday after is hard again")
        assertNull(repo.easedUntil(s, thursday), "Thursday's hard run stays")
        assertTrue(repo.runFor(SessionKind.RUN_QUALITY, nextMonday).note.startsWith("По твоей просьбе в этот день лёгкий бег"))
        assertFalse("По твоей просьбе" in repo.runFor(SessionKind.RUN_QUALITY, nextMonday.plusWeeks(1)).note)
        assertNull(s.movedRun)
        // The catch-up sees the eased Monday (not only the plan): the «Низ» fits on Sunday as it is.
        val c = assertNotNull(repo.catchUp(sunday))
        assertEquals("lower_a", c.session.templateId)
        assertFalse(c.beforeHard)
    }

    @Test fun aHardRunOnTuesdayCountsForMondayAndThursdayStays() = runBlocking<Unit> {
        hisWeek()
        repo.chooseForLegs(sunday, move = true)
        assertEquals(MovedRun(nextMonday.toEpochDay(), tuesday.toEpochDay()), store.settingsNow().movedRun)
        assertTrue(store.settingsNow().easyRunDays.isEmpty(), "Monday not eased: its run is on Tuesday")
        // Monday holds no hard run now: the «Низ» fits on Sunday as it is.
        assertFalse(assertNotNull(repo.catchUp(sunday)).beforeHard)
        add(sunday, Kind.LIFT, "lower_a")
        assertEquals(0, repo.legsDaysAgo(sunday))
        // Monday: the run is not today's; Tuesday: it is, before «Верх · Б».
        assertNotNull(repo.movedRun(nextMonday))
        assertEquals(tuesday.toEpochDay(), repo.movedRun(tuesday)?.to)
        val brief = coachContext(repo, nextMonday)
        assertTrue("Тяжёлый бег сегодня не делать" in brief, brief)
        val run = add(tuesday, Kind.RUN, "INTERVALS")
        add(tuesday, Kind.LIFT, "upper_b")
        val week = repo.weekFlow(nextMonday).first()
        val mon = week.first { it.date == nextMonday }
        assertTrue(mon.isDone(hard), "Monday's hard run is done, on Tuesday")
        assertEquals(tuesday.toEpochDay(), mon.doneLaterFor(hard)?.epochDay)
        assertEquals(nextMonday, week.first { it.date == tuesday }.catchUps[run], "Tuesday's run is booked for Monday")
        assertTrue(week.first { it.date == tuesday }.isDone(PlannedSession(SessionKind.LIFT, "upper_b")))
        val thu = week.first { it.date == thursday }
        assertTrue(hard in thu.planned && !thu.isDone(hard), "Thursday's hard run stays")
        assertNull(repo.easedUntil(store.settingsNow(), thursday))
        // Done: nothing moved is left.
        assertNull(repo.movedRun(tuesday))
    }

    @Test fun withLegsThreeDaysAgoSundayOffersNothingAgainstTheRule() = runBlocking<Unit> {
        // Tuesday's «Верх · Б» missed; on Thursday a full-body session after the hard run and «Верх · А» (it is Tuesday's).
        add(monday, Kind.RUN, "INTERVALS")
        add(monday.plusDays(3), Kind.RUN, "TEMPO")
        add(monday.plusDays(3), Kind.LIFT, "upper_a")
        add(monday.plusDays(3), Kind.LIFT, "full_x")
        assertEquals(3, repo.legsDaysAgo(sunday))
        val c = repo.catchUp(sunday)
        // Saturday's easy run: no legs and no hard run the day before Monday's hard run.
        assertEquals(PlannedSession(SessionKind.RUN_EASY), c?.session)
        assertFalse(c!!.beforeHard)
    }
}
