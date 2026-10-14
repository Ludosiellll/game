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
        assertTrue(plan.title.endsWith("· копия: Иван"), plan.title)
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

    /**
     * Denis and Egor, 13.10: Egor copied Denis's program and got other exercises — Denis's swaps (the chest press for the
     * bench...) lived in his phone's settings, never in the program. The program now goes up with them put in.
     */
    @Test fun aCopiedProgramHasTheExercisesTheFriendDoes() = runBlocking {
        val theirs = friendProgram()
        val t = theirs.templates[0]
        val first = t.slots[0].exerciseId
        val dropped = t.slots[1].exerciseId
        val friendSettings = forma.app.data.Settings(swaps = mapOf(first to "chest_press"), removed = setOf(dropped), sets = mapOf(first to 5))
        // What the friend's phone uploads.
        val uploaded = TrainingRepository.shareable(theirs, friendSettings)
        val json = AppJson.encodeToString(forma.core.plan.Program.serializer(), uploaded)
        // Own edits made before on this phone: they must not land on the friend's exercises.
        repo.updateSettings { it.copy(swaps = mapOf("chest_press" to "bench"), removed = setOf("chest_press")) }
        assertTrue(repo.copyProgram(AppJson.decodeFromString(forma.core.plan.Program.serializer(), json), "Денис", listOf(landmine)))
        val program = repo.store.programNow()!!
        val copied = program.templates[0].slots
        assertEquals("chest_press", copied[0].exerciseId)
        assertEquals(5, copied[0].sets)
        assertTrue(copied.none { it.exerciseId == dropped })
        val plan = repo.liftPlan(program.templates[0].id, LocalDate.ofEpochDay(program.startEpochDay))
        assertTrue(plan.items.any { it.exerciseId == "chest_press" }, plan.items.map { it.exerciseId }.toString())
        assertTrue(repo.store.settingsNow().swaps.isEmpty())
    }

    private fun planOf(items: List<forma.core.strength.SlotSpec>, deload: Boolean) = LiftPlan("upper_a", "Верх А", items.mapIndexed { i, s ->
        forma.app.data.LiftItem(i, s, s.exerciseId, Catalog.find(s.exerciseId)?.name ?: "Тяга друга",
            forma.core.strength.Prescription(100.0, s.repMin, s.sets, s.rir, calibrate = false), emptyList(), 2.5)
    }, deload = deload)

    /**
     * Egor copies Denis's session: in Egor's own lighter week it is a lighter session (fewer sets), Denis's lighter
     * session stays lighter too; what cannot be done here is named; of Denis's own exercises only the session's come.
     */
    @Test fun aCopyFollowsTheLighterWeekAndNamesWhatIsLeftOut() = runBlocking {
        val day = LocalDate.of(2025, 10, 9)
        val slots = friendProgram().templates[0].slots
        val unknown = slots.first().copy(exerciseId = "custom_zz99")
        val other = Catalog.makeCustom("custom_ot02", "Жим друга", Equipment.DUMBBELL, Muscle.CHEST, LoadKind.EXTERNAL)
        val theirs = planOf(slots + unknown, deload = false)
        // Egor's own lighter week (the app scheduled it after setbacks).
        repo.store.setLiftState(forma.app.data.LiftState(reactiveDeloadStart = LocalDate.of(2025, 10, 6).toEpochDay()))
        val id = assertNotNull(repo.startCopiedLift(theirs, "Денис", listOf(landmine, other), day))
        val plan = repo.planOf(db.workouts().get(id)!!)!!
        assertTrue(plan.deload && db.workouts().get(id)!!.deload)
        assertTrue(plan.items.zip(slots).all { (item, s) -> item.prescription.sets <= (s.sets + 1) / 2 }, plan.items.map { it.prescription.sets }.toString())
        assertEquals("Верх А · копия: Денис", plan.title)
        assertEquals(listOf("Тяга друга"), repo.copiedLeftOut(theirs))
        assertNotNull(Catalog.find(landmine.id))
        assertNull(Catalog.find(other.id), "a friend's exercise not in the session is not taken over")
        repo.cancelWorkout(id)

        // Not Egor's lighter week, but Denis's session was a lighter one: it stays lighter.
        repo.store.setLiftState(forma.app.data.LiftState())
        val id2 = assertNotNull(repo.startCopiedLift(planOf(slots, deload = true), "Денис", emptyList(), day))
        assertTrue(repo.planOf(db.workouts().get(id2)!!)!!.deload)
        repo.cancelWorkout(id2)
        // A plain session in a plain week is a plain one.
        val id3 = assertNotNull(repo.startCopiedLift(planOf(slots, deload = false), "Денис", emptyList(), day))
        assertTrue(!repo.planOf(db.workouts().get(id3)!!)!!.deload)
        // More than 12 exercises: the ones after the twelfth are named.
        val many = (slots + slots + slots).take(14)
        assertEquals(many.drop(12).map { Catalog.find(it.exerciseId)?.name ?: "Тяга друга" }, repo.copiedLeftOut(planOf(many, deload = false)))
    }

    /** Egor takes Denis's program and later wants his own back: the program, days and his swaps return as they were. */
    @Test fun theOwnProgramComesBackWithItsEdits() = runBlocking {
        val ownProgram = repo.store.programNow()!!
        val ownProfile = repo.store.profileNow()!!
        val first = ownProgram.templates[0].slots[0].exerciseId
        repo.updateSettings { it.copy(swaps = mapOf(first to "chest_press"), sets = mapOf(first to 5), rest = mapOf(first to 150)) }
        assertTrue(repo.copyProgram(friendProgram(), "Денис", listOf(landmine)))
        assertEquals("Денис", repo.store.programNow()!!.copiedFrom)
        assertTrue(repo.store.settingsNow().swaps.isEmpty())
        // A second friend's program: the own one stays the one put aside.
        assertTrue(repo.copyProgram(friendProgram(), "Аня"))
        assertEquals(ownProgram.templates, repo.store.settingsNow().ownPlan!!.program.templates)

        repo.restoreOwnProgram()
        val back = repo.store.programNow()!!
        assertNull(back.copiedFrom)
        assertEquals(ownProgram.templates, back.templates)
        assertEquals(ownProgram.week, back.week)
        assertEquals(ownProfile.aim, repo.store.profileNow()!!.aim)
        assertEquals(ownProfile.trainingDays, repo.store.profileNow()!!.trainingDays)
        val s = repo.store.settingsNow()
        assertEquals(mapOf(first to "chest_press"), s.swaps)
        assertEquals(mapOf(first to 5), s.sets)
        assertEquals(mapOf(first to 150), s.rest)
        assertNull(s.ownPlan)
    }

    /** Leaving a friend's program by saving the profile: the own edits put aside come back with the own plan. */
    @Test fun savingTheProfileAfterACopyBringsTheOwnEditsBack() = runBlocking {
        val first = repo.store.programNow()!!.templates[0].slots[0].exerciseId
        repo.updateSettings { it.copy(swaps = mapOf(first to "chest_press")) }
        repo.copyProgram(friendProgram(), "Денис")
        repo.updateProfile(repo.store.profileNow()!!.copy(trainingDays = setOf(1, 3, 5)))
        assertNull(repo.store.programNow()!!.copiedFrom)
        assertEquals(mapOf(first to "chest_press"), repo.store.settingsNow().swaps)
        assertNull(repo.store.settingsNow().ownPlan)
    }

    /** The name for friends goes up only to a friends profile created just now; a name changed for friends stays. */
    @Test fun theNameIsSentOnlyToANewFriendsProfile() = runBlocking {
        class Api(var name: String) : FriendsApi {
            val sent = mutableListOf<String>()
            override suspend fun me(name: String?): FriendMe {
                if (name != null) { sent += name; this.name = name }
                return FriendMe(this.name, "K7QM4XZ2")
            }
            override suspend fun setSharing(activity: Boolean, body: Boolean) = me()
            override suspend fun add(code: String) = AddResult.NOT_FOUND
            override suspend fun answer(userId: String, accept: Boolean) {}
            override suspend fun remove(userId: String) {}
            override suspend fun list() = emptyList<Friend>()
            override suspend fun feed(before: String?, limit: Int, userId: String?) = emptyList<FeedItem>()
            override suspend fun progress(userId: String) = FriendProgress("x", shared = false)
        }
        // Renamed for friends to "Егор К." earlier: opening the screen again does not put the profile's name back.
        val named = Api("Егор К.")
        val ui = forma.app.ui.screens.loadFriends(named, "Егор")
        assertEquals(emptyList(), named.sent)
        assertEquals("Егор К.", ui.me?.name)
        assertTrue(!ui.fresh)
        // A friends profile just created (no name yet): the profile's name goes up once.
        val fresh = Api("")
        val first = forma.app.ui.screens.loadFriends(fresh, "Егор")
        assertEquals(listOf("Егор"), fresh.sent)
        assertTrue(first.fresh)
        forma.app.ui.screens.loadFriends(fresh, "Егор")
        assertEquals(listOf("Егор"), fresh.sent)
        // No name anywhere (the app never asks for one): what friends see is explained on the first visit, not on each.
        val nameless = Api("")
        assertTrue(forma.app.ui.screens.loadFriends(nameless, "").fresh)
        assertTrue(!forma.app.ui.screens.loadFriends(nameless, "", explained = true).fresh)
        assertEquals(emptyList(), nameless.sent)
    }

    /** Offline, a friends call says so (and that nothing changed) instead of the sync's "will retry by itself". */
    @Test fun offlineFriendsCallsSaySo() {
        assertTrue(describeFriends(java.io.IOException("no network")).startsWith("Нет связи с сервером: ничего не изменилось"))
    }

    /** Deleting the account without an answer: try again when online, safely — not "will retry by itself", not "not deleted". */
    @Test fun anAccountDeletionWithoutAnAnswerSaysToTryAgain() {
        val text = Cloud.describeDelete(java.io.IOException("timeout"))
        assertTrue("ещё раз, когда появится интернет" in text && "безопасно" in text, text)
        assertTrue("повторится сама" !in text && "не удалён" !in text, text)
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
