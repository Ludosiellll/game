package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.sync.CoachCall
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Gear
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Muscle
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.model.Split
import forma.core.plan.ProgramGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit fixes, group J: a program built again. Saved from the wizard on a friend's program it is the program the wizard
 * showed; what the person kept per session (an exercise added for good, their own order) goes to the session of the new
 * layout that takes that session's place; «Вернуть» on a rating finds the same workout on another phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AuditFixRebuildTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    /** Monday 26 Oct 2026. */
    private val monday = LocalDate.of(2026, 10, 26)
    private var clock = monday.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun make(profile: Profile, name: String) = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, name)
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(profile)
    }

    @After fun tearDown() { if (::db.isInitialized) db.close() }

    /** Denis: gym Tue/Thu/Sat, runs Mon/Thu/Sat (shared days) — «Верх · А», «Низ», «Верх · Б». */
    private val denis = Profile(
        name = "Денис", goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
        bodyWeightKg = 71.0, lapMeters = 230, runGoal = RunGoal(1500, 330), aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
        doubleDays = true, gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
    )

    /** Egor's own program before copying: the gym on four days, upper and lower twice. */
    private val egor = Profile(
        name = "Егор", aim = Aim(LiftFocus.MUSCLE, RunFocus.NONE, Emphasis.GYM), experience = Experience.INTERMEDIATE,
        trainingDays = setOf(1, 2, 4, 5), bodyWeightKg = 80.0,
    )

    /** Denis's program as Egor copies it. */
    private fun denisProgram() = ProgramGenerator.generate(denis, monday)

    /** SHELL-1: a new split and accents saved on a friend's program build that layout, not the own program put aside. */
    @Test fun aNewSplitOnAFriendsProgramBuildsThatSplit() = runBlocking<Unit> {
        make(egor, "rebuild-split")
        val own = repo.store.programNow()!!
        assertTrue(repo.copyProgram(denisProgram(), "Денис"))
        val copied = repo.store.profileNow()!!
        // What the wizard saves when only the split and the accents change.
        repo.updateProfile(copied.copy(split = Split.FULL, accents = setOf(Muscle.CHEST)))
        val profile = repo.store.profileNow()!!
        val program = repo.store.programNow()!!
        assertNull(program.copiedFrom)
        assertEquals(copied.trainingDays, profile.trainingDays, "the days were not changed: the copy's stay")
        assertEquals(ProgramGenerator.generate(profile, repo.today()).templates.map { it.id }, program.templates.map { it.id })
        assertTrue(program.templates.all { it.id.startsWith("full") }, program.templates.map { it.id }.toString())
        assertNotEquals(own.templates.map { it.id }, program.templates.map { it.id })
        assertNull(repo.store.settingsNow().ownPlan)
    }

    /** SHELL-1: the wizard saved on a friend's program with nothing changed saves the program it showed; own edits come back. */
    @Test fun theWizardOnAFriendsProgramSavesTheProgramItShowed() = runBlocking<Unit> {
        make(egor, "rebuild-wizard")
        val first = repo.store.programNow()!!.templates[0].slots[0].exerciseId
        repo.updateSettings { it.copy(swaps = mapOf(first to "chest_press")) }
        assertTrue(repo.copyProgram(denisProgram(), "Денис"))
        val copied = repo.store.profileNow()!!
        // The answers as the wizard had them: the copy's aim and days, nothing changed.
        repo.updateProfile(copied, asAnswered = true)
        val profile = repo.store.profileNow()!!
        val program = repo.store.programNow()!!
        assertNull(program.copiedFrom)
        assertEquals(copied.trainingDays, profile.trainingDays, "the days the wizard showed, not the own program's")
        val shown = ProgramGenerator.generate(profile, repo.today())
        assertEquals(shown.templates.map { it.id }, program.templates.map { it.id })
        assertEquals(shown.week, program.week)
        // One's own edits come back with it, as with new days.
        assertEquals(mapOf(first to "chest_press"), repo.store.settingsNow().swaps)
        assertNull(repo.store.settingsNow().ownPlan)
    }

    /** SHELL-1: another place to train saved on a friend's program builds a plan of what is there, not the own gym plan. */
    @Test fun anotherPlaceOnAFriendsProgramBuildsAPlanOfWhatIsThere() = runBlocking<Unit> {
        make(egor, "rebuild-gear")
        assertTrue(repo.copyProgram(denisProgram(), "Денис"))
        repo.updateProfile(repo.store.profileNow()!!.copy(gear = Gear.DUMBBELLS))
        val program = repo.store.programNow()!!
        assertNull(program.copiedFrom)
        val off = program.templates.flatMap { it.slots }.map { Catalog[it.exerciseId] }.filter { !Gear.DUMBBELLS.fits(it, false) }
        assertTrue(off.isEmpty(), "not for home with dumbbells: ${off.map { it.id }}")
    }

    /** SHELL-2: an exercise added for good to «Верх · А», its sets and the own order follow a new split there and back. */
    @Test fun whatWasAddedAndOrderedFollowsANewSplit() = runBlocking<Unit> {
        make(denis, "rebuild-added")
        assertEquals(listOf("upper_a", "lower_a", "upper_b"), repo.store.programNow()!!.templates.map { it.id })
        assertTrue(repo.addToPlan("upper_a", "face_pull"))
        val spec = repo.addedPlanned("face_pull")
        repo.tunePlan(TrainingRepository.addedKey("upper_a", "face_pull"), spec, 4, spec.repMin to spec.repMax, spec.restSec)
        // His own order (as the coach's move_exercise keeps it): the pushdown before the bench.
        repo.updateSettings { it.copy(order = mapOf("upper_a" to listOf("pushdown", "bench"))) }
        val day = LocalDate.ofEpochDay(repo.store.programNow()!!.startEpochDay)

        suspend fun laidOutAs(split: Split): List<String> {
            repo.updateProfile(repo.store.profileNow()!!.copy(split = split))
            val program = repo.store.programNow()!!
            val ids = program.templates.map { it.id }
            val plans = program.templates.map { repo.liftPlan(it.id, day) }
            val holding = plans.filter { p -> p.items.any { it.addedForGood && it.exerciseId == "face_pull" } }
            assertEquals(1, holding.size, "face_pull added for good in ${holding.map { it.templateId }} of $ids")
            assertTrue(plans.all { p -> p.items.count { it.exerciseId == "face_pull" } <= 1 }, "never twice in one session")
            assertEquals(4, holding.single().items.single { it.exerciseId == "face_pull" }.slot.sets, "its own sets came along")
            val both = plans.single { p -> p.items.any { it.exerciseId == "pushdown" } && p.items.any { it.exerciseId == "bench" } }
            val order = both.items.map { it.exerciseId }
            assertTrue(order.indexOf("pushdown") < order.indexOf("bench"), "his order in «${both.title}»: $order")
            val s = repo.store.settingsNow()
            assertTrue(s.added.keys.all { it in ids }, "left under a session the plan has no more: ${s.added}")
            assertTrue(s.order.keys.all { it in ids }, "left under a session the plan has no more: ${s.order}")
            return ids
        }
        assertEquals(listOf("push_a", "pull_a", "legs_a"), laidOutAs(Split.PPL))
        assertEquals(listOf("upper_a", "lower_a", "upper_b"), laidOutAs(Split.AUTO))
    }

    /** undo-rate-local-id: «Вернуть» on the coach's rating after the workouts came to another phone with other local ids. */
    @Test fun undoingARatingFindsTheSameWorkoutOnAnotherPhone() = runBlocking<Unit> {
        make(denis, "rebuild-undo-rate")
        val id = db.workouts().insert(
            WorkoutEntity(
                epochDay = monday.toEpochDay(), kind = Kind.LIFT, templateId = "upper_a", title = "Верх · А", startedAt = clock - 3_600_000,
                finishedAt = clock, status = Status.DONE, rpe = 6, durationSec = 3600,
            ),
        )
        val call = CoachCall(
            CoachActions.RATE,
            JsonObject(mapOf("which" to JsonPrimitive("last_gym"), "rpe" to JsonPrimitive(4), "why" to JsonPrimitive("сказал сам"))),
        )
        val entry = CoachActions.make(repo, assertIs<CoachActions.Ready>(CoachActions.prepare(repo, call)))
        assertEquals(4, db.workouts().get(id)!!.rpe)
        // Another phone: the rated workout came from the cloud under another local id, and the id it had here belongs to
        // another workout there, rated 4 as well.
        val rated = db.workouts().get(id)!!
        db.workouts().delete(id)
        val moved = db.workouts().insert(rated.copy(id = 0))
        db.workouts().insert(rated.copy(id = id, uid = newUid(), templateId = "lower_a", title = "Низ"))
        assertTrue(CoachActions.undo(repo, entry))
        assertEquals(6, db.workouts().get(moved)!!.rpe, "the rated workout has its rating back")
        assertEquals(4, db.workouts().get(id)!!.rpe, "another workout with the old local id keeps its own rating")
    }
}
