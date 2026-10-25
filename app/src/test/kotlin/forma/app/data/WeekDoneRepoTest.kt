package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.ui.Texts
import forma.app.ui.screens.dayState
import forma.app.ui.screens.deloadText
import forma.app.ui.screens.goalGap
import forma.app.ui.screens.soonDay
import forma.app.ui.screens.lapTenth
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunState
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The week as it was done: caught up, another session, a friend's workout, a pause; the day's feeling kept. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class WeekDoneRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private val monday = LocalDate.of(2026, 10, 12)
    private var clock = monday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "week-done")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(
                name = "Денис", aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
                runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6), bodyWeightKg = 80.0, birthYear = 2008, doubleDays = true,
                runGoal = RunGoal(1500, 330), lapMeters = 230, gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
            ),
        )
    }

    @After fun tearDown() = db.close()

    private fun at(day: LocalDate) {
        clock = day.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** A gym session of [templateId] done on [day]: one set of each exercise. */
    private suspend fun lift(templateId: String, day: LocalDate): Long {
        at(day)
        val id = repo.startLift(templateId, day)
        repo.planOf(db.workouts().get(id)!!)!!.items.forEach { clock += 60_000; repo.logSet(id, it, it.prescription.weight ?: 20.0, it.prescription.reps, 2) }
        repo.finishLift(id, 7)
        return id
    }

    private fun tuesdayLift() = runBlocking { repo.store.programNow()!!.week.on(2).first { it.kind == SessionKind.LIFT } }

    @Test fun aLiftCaughtUpOnWednesdayClosesTuesday() = runBlocking {
        val tue = tuesdayLift()
        val id = lift(tue.templateId!!, monday.plusDays(2))
        val week = repo.weekFlow(monday).first()
        val tuesday = week[1]
        assertEquals(id, tuesday.doneLaterFor(tue)?.id)
        assertTrue(tuesday.isDone(tue))
        assertTrue(tuesday.missed(monday.plusDays(3)).isEmpty())
        val wednesday = week[2]
        assertEquals(listOf(id), wednesday.offPlan.map { it.id })
        assertEquals(monday.plusDays(1), wednesday.catchUps[id])
        // Friday: Tuesday's gym is not offered again.
        at(monday.plusDays(4))
        assertNotEquals(2, repo.catchUp(monday.plusDays(4))?.plannedOn)
    }

    @Test fun anotherLiftOnItsDayIsTheDaysLift() = runBlocking {
        val tue = tuesdayLift()
        val other = repo.store.programNow()!!.templates.first { it.id != tue.templateId }
        lift(other.id, monday.plusDays(1))
        val week = repo.weekFlow(monday).first()
        assertTrue(week[1].isDone(tue))
        // Wednesday: nothing of the gym is "missed".
        at(monday.plusDays(2))
        assertNull(repo.catchUp(monday.plusDays(2))?.takeIf { it.session.kind == SessionKind.LIFT })
    }

    @Test fun aFriendsWorkoutClosesTheMissedLift() = runBlocking {
        val tue = tuesdayLift()
        val friend = repo.liftPlan(tue.templateId!!, monday)
        at(monday.plusDays(2))
        val id = repo.startCopiedLift(friend, "Аня", date = monday.plusDays(2))!!
        repo.planOf(db.workouts().get(id)!!)!!.items.forEach { clock += 60_000; repo.logSet(id, it, it.prescription.weight ?: 20.0, it.prescription.reps, 2) }
        repo.finishLift(id, 7)
        val w = db.workouts().get(id)!!
        // Its legs are its own exercises', not "legs" by default.
        val legs = friend.items.any { item ->
            forma.core.model.Catalog.find(item.exerciseId)?.primary?.any { it.name in setOf("QUADS", "HAMSTRINGS", "GLUTES") } == true
        }
        assertEquals(legs, TrainingRepository.legsOf(w))
        assertEquals(id, repo.weekFlow(monday).first()[1].doneLaterFor(tue)?.id)
    }

    @Test fun pauseDaysHoldNoSessions() = runBlocking {
        repo.pause(monday.plusDays(1), monday.plusDays(3))
        val week = repo.weekFlow(monday).first()
        assertTrue(week[1].paused && week[1].planned.isEmpty())
        assertTrue(week[3].paused && week[3].planned.isEmpty())
        assertFalse(week[5].paused)
        assertTrue(week[5].planned.isNotEmpty())
    }

    @Test fun theDaysFeelingIsKeptPerDay() = runBlocking {
        repo.updateSettings { it.withFeel(monday, Feel.LOW) }
        val s = repo.store.settingsNow()
        assertEquals(Feel.LOW, s.feelOn(monday))
        assertEquals(Feel.UNSET, s.feelOn(monday.plusDays(1)))
        // The export reads the days as they are kept.
        assertEquals(mapOf(monday.toEpochDay() to "LOW"), s.feels)
        // Unset forgets it; old days drop out.
        assertEquals(Feel.UNSET, s.withFeel(monday, Feel.UNSET).feelOn(monday))
        val later = s.withFeel(monday.plusDays(Settings.FEEL_DAYS + 1L), Feel.GOOD)
        assertEquals(Feel.UNSET, later.feelOn(monday))
    }

    @Test fun wordsOfTheMainScreen() {
        // Denis's goal lap: 330 s for 1500 m on 230 m — 50.6 s, never rounded to 51.
        assertEquals("50,6 с", lapTenth(330.0 * 230 / 1500))
        assertEquals("1:28,3", lapTenth(88.3))
        assertEquals("59,9 с", lapTenth(59.94))
        assertEquals("1:00,0", lapTenth(59.96))
        assertEquals("цель 5:30 · запас 3 с", goalGap(327.4, 330))
        assertEquals("до цели 9 с", goalGap(339.2, 330))
        // Right on the goal: no "запас 0 с".
        assertEquals("цель 5:30 · без запаса", goalGap(330.3, 330))
        assertTrue(deloadText(gym = true, runs = true)!!.contains("пробежки короче"))
        assertFalse(deloadText(gym = true, runs = false)!!.contains("пробежк"))
        assertNull(deloadText(gym = false, runs = false))
        // Only easy runs: no ladder to speak of.
        assertTrue(deloadText(gym = false, runs = true)!!.contains("тяжёлые пробежки не усложняются"))
        assertFalse(deloadText(gym = false, runs = true, ladder = false)!!.contains("не усложняются"))
        // "тест 16 мая", not "16 май".
        assertEquals("16 мая", soonDay(LocalDate.of(2027, 5, 16), LocalDate.of(2027, 5, 1)))
        assertEquals("16 окт", soonDay(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 1)))
        // A run done on a pause day is said, not hidden behind the pause.
        val run = WorkoutEntity(epochDay = monday.toEpochDay(), kind = Kind.RUN, templateId = null, title = "Свободный бег", startedAt = 0, status = Status.DONE)
        assertEquals("пауза, сделано вне плана", dayState(DayItem(monday, emptyList(), listOf(run), paused = true), monday.plusDays(1)))
        assertEquals("пауза", dayState(DayItem(monday, emptyList(), emptyList(), paused = true), monday.plusDays(1)))
    }

    @Test fun aShortEasyRunSaysItMovesNoLength() {
        val st = RunState(runs = 6, walkStage = -1, easyMinutes = 50)
        val short = RunPlan.easy(st.copy(easyMinutes = 30))
        assertEquals("Короткая пробежка длительность не меняет; обычная лёгкая — 50 мин.", Texts.run(RunOutcome.Same(50), plan = short))
        assertEquals("Длительность пока прежняя: 50 мин.", Texts.run(RunOutcome.Same(50), plan = RunPlan.easy(st)))
    }
}
