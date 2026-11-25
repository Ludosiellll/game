package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.BackupManager
import forma.app.data.Feel
import forma.app.data.FormaDatabase
import forma.app.data.Kind
import forma.app.data.SettingsStore
import forma.app.data.Status
import forma.app.data.TrainingReport
import forma.app.data.TrainingRepository
import forma.app.data.WorkoutEntity
import forma.app.ui.screens.coachContext
import forma.core.load.LoadExplain
import forma.core.load.LoadStory
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
import forma.core.plan.SessionKind
import forma.core.plan.WeekPlan
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
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Research spike-label, Denis's decision of 25 November («резкий скачок» без пугалок), through the repository: his block's
 * lighter week and a thin week before a full week by plan. «Прогресс» warned «На этой неделе нагрузка резко выше обычной»
 * at that week (1900 against a usual of 1400); now it reads as the usual one, the lighter week stays out of the usual,
 * and only a week above both the usual week and the plan's full week is warned of — in the same words on «Прогресс», for
 * the coach and in the export.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ResearchLoadWordsRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var store: SettingsStore
    private lateinit var repo: TrainingRepository
    /** Sunday 29 November, the week of 23 November done through Saturday. */
    private val today = LocalDate.of(2026, 11, 29)
    private val monday = LocalDate.of(2026, 11, 23)
    /** Tuesday of his block's lighter week (9–15 November). */
    private val lighterDay = LocalDate.of(2026, 11, 10)

    private suspend fun insert(day: String, template: String, load: Double, deload: Boolean = false) {
        val d = LocalDate.parse(day)
        val at = d.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val lift = template.contains('_')
        db.workouts().insert(
            WorkoutEntity(
                epochDay = d.toEpochDay(), kind = if (lift) Kind.LIFT else Kind.RUN, templateId = template, title = template,
                startedAt = at, finishedAt = at + 3_600_000, status = Status.DONE, rpe = 6, durationSec = 3600, load = load, deload = deload,
            ),
        )
    }

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        store = SettingsStore(ctx, "research-load-words")
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
        // A hard run Monday, the gym Tuesday, Thursday and Saturday: 300 + 560 + 600 + 440 = 1900 by his own sessions. The
        // block of four from 19 October: 9–15 November is its lighter week.
        val p = store.programNow()!!
        store.setProgram(
            p.copy(
                startEpochDay = LocalDate.of(2026, 10, 19).toEpochDay(), blockWeeks = 4,
                week = WeekPlan(
                    listOf(
                        DayPlan(1, listOf(PlannedSession(SessionKind.RUN_QUALITY))),
                        DayPlan(2, listOf(PlannedSession(SessionKind.LIFT, "upper_b"))),
                        DayPlan(4, listOf(PlannedSession(SessionKind.LIFT, "upper_a"))),
                        DayPlan(6, listOf(PlannedSession(SessionKind.LIFT, "lower_a"))),
                    ),
                    p.week.notes,
                ),
            ),
        )
        for (m in listOf("2026-10-26", "2026-11-02", "2026-11-23")) fullWeek(LocalDate.parse(m))
        // The lighter week: one gym session, done as a lighter one.
        insert(lighterDay.toString(), "upper_b", 257.0, deload = true)
        // A thin week: two sessions.
        insert("2026-11-16", "INTERVALS", 300.0)
        insert("2026-11-19", "upper_a", 600.0)
    }

    private suspend fun fullWeek(m: LocalDate) {
        insert(m.toString(), "INTERVALS", 300.0)
        insert(m.plusDays(1).toString(), "upper_b", 560.0)
        insert(m.plusDays(3).toString(), "upper_a", 600.0)
        insert(m.plusDays(5).toString(), "lower_a", 440.0)
    }

    @After fun tearDown() = db.close()

    @Test fun aFullWeekByPlanAfterALighterAndAThinOneIsUsualAndNotWarnedOf() = runBlocking<Unit> {
        val base = repo.loadBase().first()
        assertEquals(1900.0, base.plannedWeek!!, 1.0)
        // The block's lighter week is known as one: its session is the gym's lighter one, and the block says so too.
        assertTrue(lighterDay in base.lighter, base.lighter.toString())
        val st = repo.loadStoryNow(today)!!
        val week = st.weeks.last()
        assertEquals(monday, week.monday)
        // Before: 1900 against the median of 1900, 1900, 257 and 900 (1400) — 136 %, «резкий скачок».
        assertEquals(LoadStory.Level.USUAL, week.level, "ratio ${week.ratio}, usual ${st.usualWeek}")
        assertEquals(1.0, week.ratio!!, 0.01)
        assertFalse(st.warn)
        // «Прогресс» shows no line at its top.
        assertNull(LoadExplain.alert(st))
        // The lighter week still reads lighter than usual.
        assertEquals(LoadStory.Level.LIGHT, st.weeks.first { it.monday == LocalDate.of(2026, 11, 9) }.level)
    }

    @Test fun aLighterSessionOnASoSoDayIsNoLighterWeek() = runBlocking<Unit> {
        // «Так себе» makes the day's gym lighter: that is no lighter week by plan.
        insert("2026-11-04", "upper_b", 300.0, deload = true)
        repo.updateSettings { it.withFeel(LocalDate.of(2026, 11, 4), Feel.LOW) }
        assertFalse(LocalDate.of(2026, 11, 4) in repo.loadBase().first().lighter)
    }

    @Test fun aWeekAboveThePlanIsSaidTheSameOnProgressForTheCoachAndInTheExport() = runBlocking<Unit> {
        // An extra easy run of 800 on Wednesday (the plan has none, so its week stays 1900): 2700.
        insert("2026-11-25", "EASY", 800.0)
        val st = repo.loadStoryNow(today)!!
        val week = st.weeks.last()
        assertEquals(LoadStory.Level.SPIKE, week.level, "ratio ${week.ratio}, usual ${st.usualWeek}")
        assertTrue(st.warn)
        val by = ((week.ratio!! - 1) * 100).roundToInt()
        val alert = assertNotNull(LoadExplain.alert(st))
        val note = assertNotNull(LoadExplain.levelNote(week))
        assertTrue("$by\u00A0% больше обычной" in alert, alert)
        assertTrue("$by\u00A0% больше обычной" in note, note)
        // The export and the coach: the week's label and its line as «Прогресс» says them.
        val report = TrainingReport.build(BackupManager(repo).snapshot(points = false), today, "test")
        // «План и сделанное по неделям» starts its lines with the dates too: the load's line is the one with the label.
        val head = "- ${Ru.dates(monday, monday.plusDays(6))}: ${LoadExplain.level(week)} — "
        val line = assertNotNull(
            report.lines().firstOrNull { it.startsWith(head) },
            report.lines().filter { it.startsWith("- ${Ru.dates(monday, monday.plusDays(6))}") }.joinToString("\n"),
        )
        assertTrue(note in line, line)
        val brief = coachContext(repo, today)
        assertTrue(note in brief, brief.lines().filter { "обычной" in it }.joinToString("\n"))
        assertTrue(alert in brief, brief.lines().filter { "обычной" in it }.joinToString("\n"))
        // No forecast of overload and no scare words anywhere the week is told about.
        listOf(alert, line).forEach { t -> listOf("перегруж", "резк", "Так чаще", "скачок").forEach { assertFalse(it in t, t) } }
        listOf("резкий скачок", "Так чаще всего и перегружаются").forEach { assertFalse(it in brief, it); assertFalse(it in report, it) }
    }
}
