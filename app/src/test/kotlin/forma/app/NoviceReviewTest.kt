package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.ProfileDraft
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.model.Sex
import forma.core.plan.PlanAdvisor
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.run.RunType
import forma.core.strength.SlotRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first days of a total beginner through the real repository and the questionnaire (a review of the path from
 * installing the app to the first sessions; each test failed on the code before its fix): the first gym session, a light
 * woman's first press, a beginner runner's plan, the questionnaire tapped through, and «пробежать 5 км» with no time.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class NoviceReviewTest {
    /** Monday 26 Oct 2026. */
    private val monday = LocalDate.of(2026, 10, 26)
    private var clock = monday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val opened = mutableListOf<FormaDatabase>()

    private fun open(name: String, profile: Profile): TrainingRepository = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = FormaDatabase.create(ctx, inMemory = true).also { opened += it }
        val store = SettingsStore(ctx, "novice-review-$name")
        store.clear()
        TrainingRepository(db, store, now = { clock++ }).also { it.completeOnboarding(profile) }
    }

    @After fun tearDown() = opened.forEach { it.close() }

    /** 17, never lifted, wants muscle, three days, no running. */
    private val teen = Profile(
        sex = Sex.MALE, birthYear = 2009, bodyWeightKg = 60.0, goal = Goal.MUSCLE, experience = Experience.NOVICE, runLevel = RunLevel.NONE,
        trainingDays = setOf(1, 3, 5), sessionMinutes = 60, aim = Aim(LiftFocus.MUSCLE, RunFocus.NONE, Emphasis.GYM),
    )

    private suspend fun firstLift(repo: TrainingRepository): forma.app.data.LiftPlan {
        val program = repo.store.programNow()!!
        val day = program.week.days.first { d -> d.sessions.any { it.kind == SessionKind.LIFT } }
        return repo.liftPlan(day.sessions.first { it.kind == SessionKind.LIFT }.templateId!!, monday.plusDays(day.day - 1L))
    }

    /**
     * The first gym session of his life: the main lifts are warmed up toward the weight the search starts from (before,
     * a calibrating lift had no weight, so no ramp and no word), and every exercise is two sets three short of failure.
     */
    @Test fun theFirstGymSessionWarmsUpAndStartsEasy() = runBlocking {
        val plan = firstLift(open("teen", teen))
        val mains = plan.items.filter { it.slot.role == SlotRole.MAIN }
        assertTrue(mains.isNotEmpty() && mains.all { it.prescription.calibrate })
        val bare = mains.filter { it.warmups.isEmpty() && it.noWarmUp == null }
        assertTrue(bare.isEmpty(), "main lifts with neither warm-up sets nor a word: ${bare.map { "${it.name} (hint ${it.prescription.startHint})" }}")
        val squat = plan.items.first { it.exerciseId == "squat" }
        assertTrue(squat.warmups.isNotEmpty() && squat.warmups.all { it.weight < squat.prescription.startHint!! }, "${squat.warmups} before ${squat.prescription.startHint}")
        val calib = plan.items.filter { it.prescription.calibrate }
        assertTrue(calib.all { it.prescription.sets <= 2 && it.prescription.rir == 3 }, calib.joinToString { "${it.name} ${it.prescription.sets}× запас ${it.prescription.rir}" })
        // Sets the person set themselves stay theirs, the first time too.
        val repo = open("teen-own-sets", teen)
        repo.setPlanSets("squat", 4, 3)
        assertEquals(4, firstLift(repo).items.first { it.exerciseId == "squat" }.prescription.sets)
    }

    /** A 58-kg woman who never lifted, general fitness: the overhead press with dumbbells from a few kilos, not the 20-kg bar. */
    @Test fun aLightWomanPressesDumbbellsOverhead() = runBlocking {
        val woman = Profile(
            sex = Sex.FEMALE, birthYear = 2001, bodyWeightKg = 58.0, goal = Goal.FITNESS, experience = Experience.NOVICE, runLevel = RunLevel.NONE,
            trainingDays = setOf(2, 4, 6), sessionMinutes = 60, aim = Aim(LiftFocus.GENERAL, RunFocus.HEALTH, Emphasis.BALANCED),
        )
        val repo = open("woman", woman)
        val program = repo.store.programNow()!!
        val ids = program.templates.flatMap { t -> t.slots.map { it.exerciseId } }
        assertTrue("ohp" !in ids && "db_shoulder" in ids, "$ids")
        val t = program.templates.first { t -> t.slots.any { it.exerciseId == "db_shoulder" } }
        val item = repo.liftPlan(t.id, monday.plusDays(3)).items.first { it.exerciseId == "db_shoulder" }
        val hint = assertNotNull(item.prescription.startHint)
        assertTrue(hint <= 5.0, "a dumbbell each: $hint kg")
        assertTrue(program.week.notes.any { "Жим гантелей сидя" in it }, "${program.week.notes}")
    }

    /** 30, never lifted, strength and a 5 km run, four days: no Bulgarian split squats in the plan of his first weeks. */
    @Test fun aBeginnerRunnersPlanHasNothingHardToLearn() = runBlocking {
        val repo = open(
            "runner",
            Profile(
                sex = Sex.MALE, birthYear = 1996, bodyWeightKg = 80.0, goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.NONE,
                trainingDays = setOf(1, 2, 4, 6), sessionMinutes = 75, aim = Aim(LiftFocus.STRENGTH, RunFocus.ENDURANCE, Emphasis.BALANCED),
                runGoal = RunGoal(5000, 30 * 60),
            ),
        )
        val hard = repo.store.programNow()!!.templates.flatMap { t -> t.slots.filter { it.exerciseId in PlanAdvisor.HARD }.map { "${t.title}: ${Catalog[it.exerciseId].name}" } }
        assertTrue(hard.isEmpty(), "$hard")
    }

    /**
     * The questionnaire tapped through with only the year and the weight typed: running starts with run/walk (before, the
     * default was "can run 20–30 minutes": intervals and a 40-minute long run in week one for someone who never ran).
     */
    @Test fun theQuestionnaireTappedThroughStartsRunningWithRunWalk() {
        val d = ProfileDraft(Profile(), blankBody = true)
        d.birthYear = "2009"
        d.weight = "60"
        assertNull(d.problem)
        val p = d.toProfile(Profile())
        assertEquals(RunLevel.NONE, p.runLevel)
        assertEquals(RunType.RUN_WALK, forma.core.run.RunPlan.runWalk(ProgramGenerator.initialRunState(p)).type)
        // The profile screen keeps what the person has.
        assertEquals(RunLevel.TRAINED, ProfileDraft(Profile(runLevel = RunLevel.TRAINED)).runLevel)
    }

    /**
     * «Хочу пробежать 5 км», no idea of a time: said with the distance alone, kept as the goal and shown on «Прогресс». A
     * middle distance is a race against the clock — 1500 m without a time still asks for one (Denis's goal is never
     * dropped by a cleared field).
     */
    @Test fun fiveKmWithoutATimeIsAGoal() {
        val d = ProfileDraft(Profile(), blankBody = true)
        d.birthYear = "1996"
        d.weight = "80"
        d.run = RunFocus.ENDURANCE
        d.goalMeters = 5000
        d.goalTime = ""
        assertNull(d.goalProblem)
        val p = d.toProfile(Profile())
        assertNull(p.runGoal)
        assertEquals(5000, p.finish)
        // Opened again on the profile screen: the distance is there, still without a time.
        val again = ProfileDraft(p)
        assertEquals(5000, again.goalMeters)
        assertEquals(5000, again.toProfile(p).finish)
        // A time typed: the race goal, the distance-only goal gone.
        again.goalTime = "30:00"
        val timed = again.toProfile(p)
        assertEquals(RunGoal(5000, 1800), timed.runGoal)
        assertNull(timed.finish)
        // 1500 m with the time cleared: an error, not a silent change of the goal.
        val denis = ProfileDraft(Profile(aim = Aim(LiftFocus.POWERBUILDING, RunFocus.SPEED, Emphasis.BALANCED), runGoal = RunGoal(1500, 330)))
        denis.goalTime = ""
        assertNotNull(denis.goalProblem)
    }
}
