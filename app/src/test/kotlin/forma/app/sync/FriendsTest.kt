package forma.app.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.AppJson
import forma.app.data.COPIED_TEMPLATE
import forma.app.data.FormaDatabase
import forma.app.data.LiftPlan
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Equipment
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.LoadKind
import forma.core.model.Muscle
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.plan.ProgramGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FriendsTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private var clock = 1_760_000_000_000L

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "friends-test")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(Profile(goal = forma.core.model.Goal.STRENGTH, experience = Experience.NOVICE, runLevel = RunLevel.NONE, trainingDays = setOf(1, 3, 5), bodyWeightKg = 75.0))
    }

    @After fun tearDown() {
        db.close()
        Catalog.setCustom(emptyList())
    }

    /** The friend's side: a program for muscle + endurance with an own exercise in it. */
    private val landmine = Catalog.makeCustom("custom_lm01", "Жим лэндмайн", Equipment.BARBELL, Muscle.SHOULDERS, LoadKind.EXTERNAL)
    private fun friendProgram() = ProgramGenerator.generate(
        Profile(aim = Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.INTERMEDIATE, trainingDays = setOf(2, 4, 6, 7)),
        LocalDate.of(2025, 10, 6),
    ).let { p -> p.copy(templates = p.templates.mapIndexed { i, t -> if (i == 0) t.copy(slots = t.slots + t.slots.last().copy(exerciseId = landmine.id)) else t }) }

    @Test fun serverAnswersDecode() {
        val plan = LiftPlan("full_a", "Всё тело · А", emptyList(), deload = false)
        val json = """
            [{"user_id":"u1","name":"Иван","workout_id":"w1","day":"2025-10-08","kind":"LIFT","title":"Всё тело · А",
              "started_at":"2025-10-08T18:00:00+00:00","duration_sec":3300,"rpe":7,"deload":false,
              "plan":${AppJson.encodeToString(LiftPlan.serializer(), plan)},"summary":null,"run":null,
              "sets":[{"exercise_id":"squat","position":0,"weight":100,"reps":5},{"exercise_id":"squat","position":0,"weight":100,"reps":6},
                      {"exercise_id":"bench","position":1,"weight":80,"reps":6},{"exercise_id":"bench","position":1,"weight":82.5,"reps":4}]},
             {"user_id":"u1","name":"Иван","workout_id":"w2","day":"2025-10-07","kind":"RUN","title":"Лёгкий бег · 40 мин",
              "started_at":"2025-10-07T07:00:00+00:00","duration_sec":2400,"plan":null,"summary":{"outcomes":[],"minutes":40,"load":160},
              "run":{"type":"EASY","distance_m":7200,"moving_sec":2390,"avg_pace":331.9,"vdot":44.5},"sets":[]}]
        """.trimIndent()
        val feed = AppJson.decodeFromString(ListSerializer(FeedItem.serializer()), json)
        assertEquals(2, feed.size)
        assertEquals(LocalDate.of(2025, 10, 8), feed[0].date)
        // Best set per exercise: the heaviest, then the most reps.
        assertEquals(listOf(100.0 to 6, 82.5 to 4), feed[0].topSets().map { it.weight to it.reps })
        assertEquals("Всё тело · А", feed[0].liftPlan()?.title)
        assertEquals(7200.0, feed[1].run?.distanceM)
        assertNull(feed[1].liftPlan())

        val progress = AppJson.decodeFromString(
            FriendProgress.serializer(),
            """{"name":"Иван","shared":true,"stats":[{"exercise_id":"squat","e1rm":121.5,"best":125,"changed_at":"2025-10-08T18:00:00Z"}],
               "vdot":44.5,"weeks":[{"week":"2025-10-06","lifts":2,"runs":1}],"program":${AppJson.encodeToString(forma.core.plan.Program.serializer(), friendProgram())},
               "custom":${AppJson.encodeToString(ListSerializer(forma.core.model.Exercise.serializer()), listOf(landmine))},"body":null}""",
        )
        assertEquals(LiftFocus.MUSCLE, progress.program()?.aim?.lift)
        assertEquals(listOf("custom_lm01"), progress.customExercises().map { it.id })
        assertNull(progress.body)
        assertEquals("Иван", AppJson.decodeFromString(FriendProgress.serializer(), """{"name":"Иван","shared":false}""").name)
    }

    @Test fun aFriendsWorkoutIsDoneWithOwnWeights() = runBlocking {
        val day = LocalDate.of(2025, 10, 9)
        val program = friendProgram()
        val theirs = LiftPlan(program.templates[0].id, program.templates[0].title, program.templates[0].slots.mapIndexed { i, s ->
            forma.app.data.LiftItem(i, s, s.exerciseId, Catalog.find(s.exerciseId)?.name ?: landmine.name,
                forma.core.strength.Prescription(100.0, s.repMin, s.sets, s.rir, calibrate = false), emptyList(), 2.5)
        }, deload = false)
        // Their own exercise arrives with the copy; their weights do not.
        val id = assertNotNull(repo.startCopiedLift(theirs, "Иван", listOf(landmine), day))
        val plan = repo.planOf(db.workouts().get(id)!!)!!
        assertEquals(COPIED_TEMPLATE, plan.templateId)
        assertTrue(plan.title.endsWith("как у Иван"))
        assertEquals(theirs.items.map { it.exerciseId }, plan.items.map { it.exerciseId })
        assertTrue(plan.items.all { it.prescription.weight != 100.0 || it.prescription.calibrate })
        assertEquals("Жим лэндмайн", Catalog["custom_lm01"].name)
        // While a session runs, another one does not start.
        assertNull(repo.startCopiedLift(theirs, "Иван", emptyList(), day))
        // It is finished and evaluated like any session.
        val first = plan.items.first()
        repo.logSet(id, first, 60.0, first.prescription.reps, 2)
        assertTrue(repo.finishLift(id, 7).reports.isNotEmpty())
    }

    @Test fun aFriendsProgramBecomesOwn() = runBlocking {
        repo.copyProgram(friendProgram(), "Иван", listOf(landmine))
        val program = repo.store.programNow()!!
        val profile = repo.store.profileNow()!!
        assertEquals("Иван", program.copiedFrom)
        assertEquals(LiftFocus.MUSCLE, profile.aim.lift)
        assertEquals(setOf(2, 4, 6, 7), profile.trainingDays)
        // Own experience decides the block length (a novice deloads on demand).
        assertEquals(Experience.NOVICE, program.experience)
        assertEquals(0, program.blockWeeks)
        assertTrue(program.templates[0].slots.any { it.exerciseId == "custom_lm01" })
        val plan = repo.liftPlan(program.templates[0].id, LocalDate.ofEpochDay(program.startEpochDay))
        assertTrue(plan.items.any { it.name == "Жим лэндмайн" })
    }
}
