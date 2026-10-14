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

    @After fun tearDown() { if (::db.isInitialized) db.close() }

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

    /** Two main lifts stepped back on Monday of the week [weeksIn] weeks after the block's start; Wednesday's session checks it. */
    private fun setbacks(profile: Profile, name: String, weeksIn: Long): TrainingRepository = runBlocking {
        val repo = repo(profile, name)
        repo.store.setProgram(repo.store.programNow()!!.copy(startEpochDay = monday.minusWeeks(weeksIn).toEpochDay()))
        val program = repo.store.programNow()!!
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
        clock = monday.plusDays(2).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val id = repo.startLift(program.templates[1].id, monday.plusDays(2))
        val item = repo.planOf(db.workouts().get(id)!!)!!.items.first()
        repo.logSet(id, item, 60.0, item.prescription.reps, 2)
        repo.finishLift(id, 7)
        repo
    }

    @Test fun repeatedSetbacksBringALighterWeekForAnIntermediateToo() = runBlocking<Unit> {
        val profile = Profile(
            aim = Aim(LiftFocus.STRENGTH, RunFocus.NONE, Emphasis.GYM), experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE,
            trainingDays = setOf(1, 3, 5), bodyWeightKg = 75.0,
        )
        // The third week of a five-week block.
        val repo = setbacks(profile, "deload-intermediate", 2)
        val next = monday.plusWeeks(1)
        assertEquals(next.toEpochDay(), repo.store.liftStateNow().reactiveDeloadStart)
        // The lighter week is the block's last one, a new block after it: no planned lighter week right after.
        val moved = repo.store.programNow()!!
        assertTrue(ProgramGenerator.isDeloadWeek(moved, next))
        assertTrue(!ProgramGenerator.isDeloadWeek(moved, next.plusWeeks(1)))
        assertEquals(1, ProgramGenerator.rirOffset(moved, next.plusWeeks(1)))
    }

    /** Denis lifts as an advanced lifter (4-week blocks): the lighter week the app sets itself comes for him too. */
    @Test fun repeatedSetbacksBringALighterWeekForAnAdvancedLifter() = runBlocking<Unit> {
        // The second week of his block.
        val repo = setbacks(denis, "deload-advanced", 1)
        val next = monday.plusWeeks(1)
        assertEquals(next.toEpochDay(), repo.store.liftStateNow().reactiveDeloadStart)
        val moved = repo.store.programNow()!!
        assertTrue(ProgramGenerator.isDeloadWeek(moved, next))
        assertTrue(ProgramGenerator.isRunCutbackWeek(moved, next), "the runs are lighter that week too")
        // The rest of this week is not the block's peak: the reserve as planned.
        val sat = repo.liftPlan("upper_b", monday.plusDays(5))
        assertTrue(sat.items.none { it.prescription.intro }, "no first-week reserve before it")
        val plain = repo.liftPlan("upper_b", monday.plusDays(5).plusWeeks(3))
        assertEquals(plain.items.first().slot.rir, sat.items.first().prescription.rir)
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

    /** His week: race pace on Thursday (legs five days back), not on Monday two days after Saturday's legs. */
    @Test fun racePaceGoesToThursdayAndTheEasyRunAfterLegsStaysShort() = runBlocking<Unit> {
        val repo = repo(denis, "denis-race-pace")
        repo.store.setRunState(repo.store.runStateNow().copy(runs = 10, vdot = 49.0, lastTestDay = monday.toEpochDay() - 3, easyMinutes = 55))
        val program = repo.store.programNow()!!
        val days = program.week.days.associate { it.day to it.sessions.map { s -> s.kind } }
        assertEquals(listOf(SessionKind.RUN_QUALITY), days[1])
        assertTrue(SessionKind.RUN_QUALITY in days[4].orEmpty())
        assertNotEquals(RunType.GOAL_PACE, repo.runFor(SessionKind.RUN_QUALITY, monday).type)
        assertEquals(RunType.GOAL_PACE, repo.runFor(SessionKind.RUN_QUALITY, monday.plusDays(3)).type)
        // Saturday: the easy run after «Низ» is at most 45 minutes.
        val sat = repo.runFor(SessionKind.RUN_EASY, monday.plusDays(5))
        assertTrue(sat.workSeconds <= TrainingRepository.AFTER_LEGS_EASY * 60, "${sat.workSeconds / 60} min")
    }

    @Test fun noTrialDateBeforeTheTrialIsDue() = runBlocking<Unit> {
        val repo = repo(denis, "denis-no-trial")
        repo.store.setRunState(repo.store.runStateNow().copy(runs = 0))
        assertEquals(null, repo.trialDay(monday))
    }

    @Test fun theSameSwapPickedAgainChangesNothing() = runBlocking<Unit> {
        val repo = repo(denis, "denis-same-swap")
        repo.updateSettings { it.copy(swaps = mapOf("bulgarian" to "leg_ext"), sets = mapOf("bulgarian" to 4)) }
        repo.setSwap("bulgarian", "leg_ext")
        val s = repo.store.settingsNow()
        assertEquals(4, s.sets["bulgarian"])
        assertTrue("bulgarian" !in s.demoted)
    }

    /** A friend's program and back: the own swaps come back with whether they are done as added exercises. */
    @Test fun theOwnPlanComesBackWithItsSwapsAsTheyWere() = runBlocking<Unit> {
        val repo = repo(denis, "denis-own-plan")
        repo.setSwap("squat", "leg_ext")
        assertTrue("squat" in repo.store.settingsNow().demoted)
        val own = repo.store.programNow()!!
        assertTrue(repo.copyProgram(own.copy(copiedFrom = null), "Егор"))
        assertTrue(repo.store.settingsNow().demoted.isEmpty())
        // Level up on the friend's program: it stays.
        repo.setExperience(Experience.INTERMEDIATE)
        assertEquals("Егор", repo.store.programNow()!!.copiedFrom)
        // The profile saved without new days: one's own program and days come back.
        repo.updateProfile(repo.store.profileNow()!!.copy(bodyWeightKg = 70.0))
        val back = repo.store.programNow()!!
        assertEquals(null, back.copiedFrom)
        assertEquals(setOf(2, 4, 6), repo.store.profileNow()!!.gymDays)
        val s = repo.store.settingsNow()
        assertEquals("leg_ext", s.swaps["squat"])
        assertTrue("squat" in s.demoted)
    }

    @Test fun aSessionLeftOpenCountsItsSetsNotTheDays() {
        val min = 60_000L
        val sets = listOf(90 * min, 93 * min, 96 * min)
        // Started at home at 0, first set 90 minutes later: the warm-up gap counts 5 minutes.
        assertEquals(15, TrainingRepository.liftMinutes(0, sets, 100 * min))
        // Carried on two days later: the gap between counts 5 minutes too.
        val two = sets + listOf(2 * 1440 * min, 2 * 1440 * min + 3 * min)
        assertEquals(26, TrainingRepository.liftMinutes(0, two, 2 * 1440 * min + 10 * min))
    }

    /** Saturday's legs come two days before Monday's hard run: their "+" sets keep 2 in reserve; the upper body as before. */
    @Test fun legPlusSetsBeforeTheHardRunKeepTwoInReserve() = runBlocking<Unit> {
        val repo = repo(denis, "denis-plus")
        val lower = repo.liftPlan("lower_a", monday.plusDays(5))
        lower.items.filter { forma.core.model.Catalog[it.exerciseId].lowerBody }.forEach { assertEquals(2, it.prescription.topRir, it.exerciseId) }
        assertTrue(lower.items.any { it.exerciseId == "squat" && it.prescription.topRir == 2 })
        repo.liftPlan("upper_b", monday.plusDays(1)).items.forEach { assertEquals(1, it.prescription.topRir, it.exerciseId) }
        // The plan itself is the same: sets, reps and rest of each exercise.
        val program = repo.store.programNow()!!
        val slots = program.templates.first { it.id == "lower_a" }.slots
        assertEquals(slots.map { it.exerciseId to it.sets }, lower.items.map { it.slot.exerciseId to it.slot.sets }.filter { (id, _) -> slots.any { it.exerciseId == id } })
    }

    /** A week or more away is rest enough: the lighter week set before it goes. */
    @Test fun aLongPauseTakesThePlannedLighterWeekAway() = runBlocking<Unit> {
        val repo = setbacks(denis, "deload-pause", 1)
        assertNotNull(repo.store.liftStateNow().reactiveDeloadStart)
        repo.pause(monday.plusDays(3), monday.plusDays(12))
        assertEquals(null, repo.store.liftStateNow().reactiveDeloadStart)
    }

    /** Novices have no blocks: the app's lighter week is the gym's only, the count of lighter running weeks stays. */
    @Test fun aNovicesLighterWeekLeavesTheirWeeksAsTheyAre() = runBlocking<Unit> {
        val novice = Profile(
            aim = Aim(LiftFocus.STRENGTH, RunFocus.NONE, Emphasis.GYM), experience = Experience.NOVICE, runLevel = RunLevel.BASE,
            trainingDays = setOf(1, 3, 5), bodyWeightKg = 75.0,
        )
        val repo = setbacks(novice, "deload-novice", 2)
        assertEquals(monday.plusWeeks(1).toEpochDay(), repo.store.liftStateNow().reactiveDeloadStart)
        assertEquals(monday.minusWeeks(2).toEpochDay(), repo.store.programNow()!!.startEpochDay)
    }

    /** The exercise already in place picked again in a session: nothing changes, today or in the plan. */
    @Test fun theSameExercisePickedAgainInASessionChangesNothing() = runBlocking<Unit> {
        val repo = repo(denis, "denis-same-in-session")
        repo.updateSettings { it.copy(swaps = mapOf("bulgarian" to "leg_ext")) }
        val id = repo.startLift("lower_a", monday.plusDays(5))
        val before = repo.planOf(db.workouts().get(id)!!)!!.items.first { it.exerciseId == "leg_ext" }
        assertEquals(null, repo.swapChange(id, before.position, "leg_ext"))
        assertTrue(repo.swapExercise(id, before.position, "leg_ext", forever = true))
        val after = repo.planOf(db.workouts().get(id)!!)!!.items.first { it.exerciseId == "leg_ext" }
        assertEquals(before.slot, after.slot)
        assertTrue("bulgarian" !in repo.store.settingsNow().demoted)
    }

    /** A race on Saturday the 14th of November: the two weeks before it lighter, its week a short race-pace run, the day the race. */
    @Test fun theWeeksBeforeTheRaceAreATaper() = runBlocking<Unit> {
        val race = LocalDate.of(2026, 11, 14)
        val repo = repo(denis.copy(runGoal = RunGoal(1500, 330, race.toEpochDay())), "denis-race")
        repo.store.setRunState(repo.store.runStateNow().copy(runs = 10, vdot = 49.0, lastTestDay = monday.toEpochDay() - 3))
        // The race day itself (a Saturday, a gym and easy-run day in the plan): the race.
        val day = repo.runFor(SessionKind.RUN_EASY, race)
        assertEquals(RunType.TEST, day.type)
        assertEquals("Забег · 1500 м", day.title)
        // Thursday before: two days to go — 20 minutes easy with strides, not a hard run.
        val thu = repo.runFor(SessionKind.RUN_QUALITY, race.minusDays(2))
        assertEquals(RunType.EASY, thu.type)
        assertTrue(thu.workSeconds <= 20 * 60 && "ускорени" in thu.title, thu.title)
        // Monday before: five days to go — race pace, short.
        val mon = repo.runFor(SessionKind.RUN_QUALITY, race.minusDays(5))
        assertEquals(RunType.GOAL_PACE, mon.type)
        assertTrue(mon.cutback)
        // Ten days before: a lighter week, the ladders wait.
        assertTrue(repo.runFor(SessionKind.RUN_QUALITY, race.minusDays(9)).cutback)
        // Three weeks before: no trial, the race is the test.
        repo.store.setRunState(repo.store.runStateNow().copy(lastTestDay = null))
        assertEquals(null, repo.trialDay(race.minusDays(20)))
        // The legs on the race day (his Saturday «Низ»): a lighter session; two weeks before as planned.
        assertTrue(!repo.liftPlan("lower_a", race.minusDays(14)).deload)
        assertTrue(repo.liftPlan("lower_a", race).deload)
    }

    /** His Saturday easy run (his one easy run, in a week with hard runs) ends with strides. */
    @Test fun hisEasyRunEndsWithStrides() = runBlocking<Unit> {
        val repo = repo(denis, "denis-strides")
        repo.store.setRunState(repo.store.runStateNow().copy(runs = 10, vdot = 49.0))
        val sat = repo.runFor(SessionKind.RUN_EASY, monday.plusDays(5))
        assertTrue("6 ускорений" in sat.title, sat.title)
    }

    /** Four runs for 1500 m, two of them easy: the last easy run is a long easy one of up to an hour. */
    @Test fun aSecondEasyRunBecomesTheLongEasyOne() = runBlocking<Unit> {
        val runner = Profile(
            aim = Aim(LiftFocus.NONE, RunFocus.SPEED, Emphasis.RUN), experience = Experience.INTERMEDIATE, runLevel = RunLevel.TRAINED,
            trainingDays = setOf(1, 3, 5, 7), runGoal = RunGoal(1500, 330), lapMeters = 400,
        )
        val repo = repo(runner, "long-easy")
        val program = repo.store.programNow()!!
        val easy = program.week.days.filter { d -> d.sessions.any { it.kind == SessionKind.RUN_EASY } }.map { it.day }
        if (easy.size >= 2 && program.week.days.none { d -> d.sessions.any { it.kind == SessionKind.RUN_LONG } }) {
            assertEquals(RunType.LONG, repo.runFor(SessionKind.RUN_EASY, monday.plusDays(easy.last() - 1L)).type)
            assertEquals(RunType.EASY, repo.runFor(SessionKind.RUN_EASY, monday.plusDays(easy.first() - 1L)).type)
        }
    }
}
