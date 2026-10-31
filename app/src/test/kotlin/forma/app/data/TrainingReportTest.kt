package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.DayPlan
import forma.core.plan.PlannedSession
import forma.core.plan.SessionKind
import forma.core.plan.WeekPlan
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunWorkout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TrainingReportTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private val monday = LocalDate.of(2026, 10, 5)
    private var clock = monday.atTime(18, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "report-test")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(name = "Денис", birthYear = 2008, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 72.0, runGoal = RunGoal(1500, 330), lapMeters = 230),
        )
    }

    @After fun tearDown() { db.close() }

    /** Everything that happened is in the file, in words: each set with its reserve, each run with its laps, what the app decided. */
    @Test fun theReportTellsEverySessionInWords() = runBlocking {
        val program = repo.store.programNow()!!
        val t = program.templates.first()
        val id = repo.startLift(t.id, monday)
        val plan = repo.planOf(db.workouts().get(id)!!)!!
        val item = plan.items.first()
        repeat(3) { repo.logSet(id, item, 80.0, 8, 2) }
        clock += 3_600_000
        repo.finishLift(id, 7)
        clock += 86_400_000
        repo.saveRun(repo.freeRun(230), RunResult(1.0, 4, 1800, distanceM = 5000.0), clock, emptyList(), monday.plusDays(1))
        val text = TrainingReport.build(BackupManager(repo).snapshot(), monday.plusDays(2), "test")
        listOf(
            "# Splity — все тренировки и прогресс", "Беговая цель: 1500 м за 5:30", "круг 230 м", "## План", "## Где сейчас",
            "80 кг × 8 (запас 2)", "Итог:", "тяжесть 7/10", "свободный", "5,00 км", "Нагрузка и восстановление", "## Обозначения",
        ).forEach { assertTrue(text.contains(it), "no «$it» in:\n$text") }
        // The plan as done here: the first exercise of the template is in it by name.
        assertTrue(text.contains(item.name), text)
        // Denis is 18: "18 лет", the months on top, the way to the goal.
        listOf("Мужчина, 18 лет", "## Сводка по месяцам", "| октябрь 2026 | 2 (1 / 1) | 5,0 |", "## Путь к цели: 1500 м за 5:30",
            "Контрольного забега на 1500 м ещё не было", "### План и сделанное по неделям").forEach { assertTrue(text.contains(it), "no «$it» in:\n$text") }
        assertTrue(text.indexOf("## Сводка по месяцам") < text.indexOf("## Кто тренируется"))
    }

    @Test fun agesReadNaturally() {
        assertEquals("18 лет", TrainingReport.years(18))
        assertEquals("21 год", TrainingReport.years(21))
        assertEquals("22 года", TrainingReport.years(22))
        assertEquals("41 год", TrainingReport.years(41))
    }

    /** Denis's own week: hard run Mon, the gym Tue, hard run and the gym Thu, the gym and an easy run Sat — no long run. */
    private fun denisWeek(b: Backup): Backup {
        val prog = b.program!!
        val t = prog.templates.map { it.id }
        val week = WeekPlan(
            listOf(
                DayPlan(1, listOf(PlannedSession(SessionKind.RUN_QUALITY))),
                DayPlan(2, listOf(PlannedSession(SessionKind.LIFT, t[0]))),
                DayPlan(4, listOf(PlannedSession(SessionKind.RUN_QUALITY), PlannedSession(SessionKind.LIFT, t[1 % t.size]))),
                DayPlan(6, listOf(PlannedSession(SessionKind.LIFT, t[2 % t.size]), PlannedSession(SessionKind.RUN_EASY))),
            ),
            emptyList(),
        )
        return b.copy(program = prog.copy(week = week))
    }

    /** The gym only to who lifts and running only to who runs; the long run and the ladders only when the week holds them. */
    @Test fun eachPersonHearsOfWhatTheyDo() = runBlocking {
        val b = denisWeek(BackupManager(repo).snapshot())
        val p = b.profile!!
        val denis = TrainingReport.build(b, monday, "test")
        assertTrue(denis.contains("- Опыт в зале: опытный. Бег: бегает 20+ минут."), denis)
        assertTrue(denis.contains("- Лёгкий бег — ${b.runState!!.easyMinutes} мин. Шаги лестниц: интервалы"), denis)
        assertFalse(denis.contains("длинный —"), denis)
        val withLong = b.copy(program = b.program!!.copy(week = b.program!!.week.copy(days = b.program!!.week.days + DayPlan(7, listOf(PlannedSession(SessionKind.RUN_LONG))))))
        assertTrue(TrainingReport.build(withLong, monday, "test").contains(", длинный — ${b.runState!!.longMinutes} мин."))
        // Gym only: no running level, no VDOT, no ladders.
        val gym = TrainingReport.build(b.copy(profile = p.copy(aim = Aim(LiftFocus.MUSCLE, RunFocus.NONE, Emphasis.GYM), runGoal = null)), monday, "test")
        listOf("Бег: ", "### Бег", "VDOT ещё не рассчитан", "Шаги лестниц").forEach { assertFalse(gym.contains(it), "«$it» in:\n$gym") }
        assertTrue(gym.contains("- Опыт в зале: опытный."), gym)
        // Running only: no experience in the gym.
        val run = TrainingReport.build(b.copy(profile = p.copy(aim = Aim(LiftFocus.NONE, RunFocus.SPEED, Emphasis.RUN))), monday, "test")
        assertFalse(run.contains("Опыт в зале"), run)
        assertTrue(run.contains("- Бег: бегает 20+ минут."), run)
        // An own week without running days: no empty "бег — .".
        val own = TrainingReport.build(b.copy(profile = p.copy(gymDays = setOf(1, 3, 5), runDays = emptySet())), monday, "test")
        assertTrue(own.contains("- Своя неделя: зал — пн, ср, пт."), own)
    }

    /** The week under way: today's session not missed in the morning, counted once done; every pause's days left out. */
    @Test fun theWeekUnderWayCountsTodayOnceDoneAndEveryPause() = runBlocking {
        val b = denisWeek(BackupManager(repo).snapshot())
        val morning = TrainingReport.build(b, monday, "test")
        assertFalse(morning.contains("пропущено"), morning)
        val wednesday = monday.plusDays(2)
        assertTrue(TrainingReport.build(b, wednesday, "test").contains("пропущено: зал 1, бег 1"))
        // Monday and Tuesday in a pause before the last one (that one is next week): nothing missed.
        val paused = b.copy(
            settings = b.settings.copy(
                pauseFrom = monday.plusDays(7).toEpochDay(), pauseUntil = monday.plusDays(9).toEpochDay(),
                pastPauses = listOf(listOf(monday.toEpochDay(), monday.plusDays(1).toEpochDay())),
            ),
        )
        val text = TrainingReport.build(paused, wednesday, "test")
        assertFalse(text.contains("пропущено"), text)
        assertTrue(text.contains("пауза 2 дня"), text)
        // Monday's hard run done: counted that day.
        repo.saveRun(repo.freeRun(230), RunResult(1.0, 4, 1800, distanceM = 5000.0), clock, emptyList(), monday)
        val evening = TrainingReport.build(denisWeek(BackupManager(repo).snapshot()), monday, "test")
        assertTrue(evening.contains("бег 1 из 1"), evening)
        assertFalse(evening.contains("пропущено"), evening)
    }

    /** A 5 km goal never has sessions at the goal pace: none promised. A block restarted ahead says so. */
    @Test fun theGoalAndTheBlockAreSaidAsTheyAre() = runBlocking {
        val b = BackupManager(repo).snapshot()
        assertTrue(TrainingReport.build(b, monday, "test").contains("- Отрезков в темпе цели ещё не было."))
        val fiveK = TrainingReport.build(b.copy(profile = b.profile!!.copy(runGoal = RunGoal(5000, 1500))), monday, "test")
        assertTrue(fiveK.contains("## Путь к цели: 5 км за 25:00"), fiveK)
        assertFalse(fiveK.contains("Отрезков в темпе цели"), fiveK)
        val ahead = TrainingReport.build(b.copy(program = b.program!!.copy(startEpochDay = monday.plusWeeks(2).toEpochDay())), monday, "test")
        assertTrue(ahead.contains("Новый блок — с 19 октября."), ahead)
        assertFalse(ahead.contains("Начат "), ahead)
    }

    /** An easy run kept short: "the usual one stays 40 min", not "the length stays" — the file reads the run's own plan. */
    @Test fun aShortEasyRunIsSaidAsOnTheScreen() = runBlocking {
        repo.saveRun(repo.freeRun(230), RunResult(1.0, 4, 1500, distanceM = 4000.0), clock, emptyList(), monday)
        val b = BackupManager(repo).snapshot()
        val w = b.workouts.first { it.kind == Kind.RUN }
        val short = RunPlan.easy(b.runState!!.copy(easyMinutes = 25))
        val summary = RunSummary(listOf(RunOutcome.Same(40)), 25, 100.0)
        val edited = b.copy(
            workouts = b.workouts.map { if (it.id == w.id) it.copy(summaryJson = AppJson.encodeToString(RunSummary.serializer(), summary)) else it },
            runs = b.runs.map { if (it.workoutId == w.id) it.copy(planJson = AppJson.encodeToString(RunWorkout.serializer(), short)) else it },
        )
        val text = TrainingReport.build(edited, monday, "test")
        assertTrue(text.contains("Короткая пробежка длительность не меняет. Обычная лёгкая — 40 мин."), text)
        assertFalse(text.contains("Длительность пока прежняя"), text)
    }

    /** "Восстановлено тренировок" counts what the question before it counted: the finished ones. */
    @Test fun aRestoreCountsTheFinishedWorkouts() = runBlocking {
        repo.saveRun(repo.freeRun(230), RunResult(1.0, 4, 1800, distanceM = 5000.0), clock, emptyList(), monday)
        val b = BackupManager(repo).snapshot()
        val unfinished = b.workouts.first().copy(id = 999, uid = newUid(), status = Status.ACTIVE, summaryJson = null)
        assertEquals(1, BackupManager(repo).restore(b.copy(workouts = b.workouts + unfinished)))
    }

    /**
     * Pull-ups: the next target is the extra weight ("+12,5 кг", not "12,5 кг" next to a maximum of 114,5), the maximum
     * says it is with the body; a stadium session says "3 × 2 круга", not "кругов кр."; the forecast is for the goal.
     */
    @Test fun pullUpsLapsAndTheGoalForecastAreSaidRight() = runBlocking {
        repo.store.setRunState(repo.store.runStateNow().copy(vdot = 50.0))
        val goalPace = repo.runFor(forma.core.plan.SessionKind.RUN_QUALITY, monday)
        repo.saveRun(goalPace, RunResult(1.0, 7, goalPace.totalSeconds), clock, emptyList(), monday)
        val b = BackupManager(repo).snapshot()
        val withPullUps = b.copy(
            stats = b.stats + ExerciseStatEntity("pullup", 114.5, 110.0, clock),
            slots = b.slots + SlotStateEntity("pullup@6-10", "pullup", 12.5, 8, 0, 0, 3, clock),
        )
        val text = TrainingReport.build(withPullUps, monday.plusDays(1), "test", mapOf(monday.toEpochDay() to "бодро"))
        assertTrue(text.contains("Подтягивания: 114,5 кг (лучший 110, с весом тела); следующая цель +12,5 кг × 8"), text)
        assertTrue(!text.contains("кр."), text)
        assertTrue(text.contains("Прогноз: 1500 м — "), text)
        assertTrue(text.contains("самочувствие: бодро"), text)
        assertTrue(text.contains("вместе с весом тела"), text)
    }
}
