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
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.run.RunType
import forma.core.strength.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Denis's week in the app: the trial on a day with fresh legs; a lighter week when lifts keep stepping back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DenisWeekRepoTest {
    private lateinit var db: FormaDatabase
    private val monday = LocalDate.of(2026, 10, 12)
    private var clock = monday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun repo(profile: Profile, name: String): TrainingRepository = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, name)
        store.clear()
        TrainingRepository(db, store, now = { clock }).also { it.completeOnboarding(profile) }
    }

    @After fun tearDown() { db.close() }

    private val denis = Profile(
        name = "Денис", aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
        runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6), runGoal = RunGoal(1500, 330), lapMeters = 230, doubleDays = true,
        gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
    )

    @Test fun theTrialGoesToAHardDayWithFreshLegs() = runBlocking<Unit> {
        val repo = repo(denis, "denis-trial")
        repo.store.setRunState(repo.store.runStateNow().copy(runs = 4, vdot = 47.0))
        val program = repo.store.programNow()!!
        fun legsOn(d: LocalDate) = program.week.on(d.dayOfWeek.value)
            .any { it.kind == SessionKind.LIFT && program.templates.first { t -> t.id == it.templateId }.legs }
        val day = assertNotNull(repo.trialDay(monday))
        // Not the hard day two days after the leg session.
        assertTrue((1..2).none { legsOn(day.minusDays(it.toLong())) }, "trial on $day")
        assertNotEquals(monday, day)
        // Today's plan agrees: no trial on Monday, the trial on its day.
        assertNotEquals(RunType.TEST, repo.runFor(SessionKind.RUN_QUALITY, monday).type)
        assertEquals(RunType.TEST, repo.runFor(SessionKind.RUN_QUALITY, day).type)
    }

    @Test fun repeatedSetbacksBringALighterWeekForAnIntermediateToo() = runBlocking<Unit> {
        val profile = Profile(
            aim = Aim(LiftFocus.STRENGTH, RunFocus.NONE, Emphasis.GYM), experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE,
            trainingDays = setOf(1, 3, 5), bodyWeightKg = 75.0,
        )
        val repo = repo(profile, "deload-intermediate")
        // The third week of a five-week block.
        val start = monday.minusWeeks(2)
        repo.store.setProgram(repo.store.programNow()!!.copy(startEpochDay = start.toEpochDay()))
        val program = repo.store.programNow()!!
        assertEquals(5, program.blockWeeks)
        assertTrue(!ProgramGenerator.isDeloadWeek(program, monday.plusWeeks(1)))
        // Two main lifts stepped back this week.
        val t = program.templates.first()
        val plan = repo.liftPlan(t.id, monday)
        val mains = plan.items.filter { it.slot.role == forma.core.strength.SlotRole.MAIN }.take(2)
        assertEquals(2, mains.size)
        val summary = LiftSummary(mains.map { SlotReport(it.exerciseId, it.name, Outcome.Reduce(100.0, 90.0, 5), null, false) }, 0.0, 6, 60, 420.0)
        db.workouts().insert(
            WorkoutEntity(epochDay = monday.toEpochDay(), kind = Kind.LIFT, templateId = t.id, title = t.title, startedAt = clock, finishedAt = clock,
                status = Status.DONE, rpe = 8, durationSec = 3600, load = 480.0,
                planJson = AppJson.encodeToString(LiftPlan.serializer(), plan), summaryJson = AppJson.encodeToString(LiftSummary.serializer(), summary)),
        )
        // The next session, finished on Wednesday, triggers the check.
        clock = monday.plusDays(2).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val id = repo.startLift(program.templates[1].id, monday.plusDays(2))
        val item = repo.planOf(db.workouts().get(id)!!)!!.items.first()
        repo.logSet(id, item, 60.0, item.prescription.reps, 2)
        repo.finishLift(id, 7)
        val next = monday.plusWeeks(1)
        assertEquals(next.toEpochDay(), repo.store.liftStateNow().reactiveDeloadStart)
        // The block starts again after it: no planned lighter week right after.
        val moved = repo.store.programNow()!!
        assertEquals(next.plusWeeks(1).toEpochDay(), moved.startEpochDay)
        assertTrue(!ProgramGenerator.isDeloadWeek(moved, next.plusWeeks(1)))
    }

    /**
     * Denis's own swaps stay as he shaped them: the Bulgarian split squat swapped for leg extensions keeps the plan's sets
     * and reps (made before the rule for single-joint swaps); the same swap made now gets an added exercise's numbers.
     */
    @Test fun hisSwapsKeepTheirNumbers() = runBlocking<Unit> {
        val repo = repo(denis, "denis-swaps")
        repo.updateSettings { it.copy(swaps = mapOf("bulgarian" to "leg_ext", "bench" to "chest_press"), removed = setOf("plank"), sets = mapOf("lat_pulldown" to 2)) }
        val program = repo.store.programNow()!!
        val lower = program.templates.first { it.id == "lower_a" }
        val bulgarian = lower.slots.first { it.exerciseId == "bulgarian" }
        val plan = repo.liftPlan(lower.id, monday.plusDays(5))
        val ext = plan.items.first { it.exerciseId == "leg_ext" }
        assertEquals(bulgarian.sets, ext.slot.sets)
        assertEquals(bulgarian.repMin to bulgarian.repMax, ext.slot.repMin to ext.slot.repMax)
        assertTrue(plan.items.none { it.exerciseId == "plank" })
        // Swapped again now (back and forth): the rule for single-joint swaps applies.
        repo.setSwap("bulgarian", null)
        repo.setSwap("bulgarian", "leg_ext")
        val again = repo.liftPlan(lower.id, monday.plusDays(5)).items.first { it.exerciseId == "leg_ext" }
        assertEquals(10 to 15, again.slot.repMin to again.slot.repMax)
    }
}
