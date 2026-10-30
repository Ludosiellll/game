package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.sync.CoachCall
import forma.app.ui.screens.coachContext
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Gear
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis (30.10): «добавить возможность просить тренера составить план, тот расспросит тебя и запишет, так же как и цель
 * и вес и всё остальное». The coach records what the plan is built from (set_profile) and the app builds the plan by its
 * own rules — after «Принять», with its way back; it sees the profile it asks about.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CoachProfileTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "coach-profile")
        store.clear()
        repo = TrainingRepository(db, store)
        repo.completeOnboarding(
            Profile(goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), bodyWeightKg = 80.0, sessionMinutes = 75),
        )
    }

    @After fun tearDown() = db.close()

    private fun call(vararg a: Pair<String, Any>) = CoachCall(
        CoachActions.PROFILE,
        JsonObject(a.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) } + ("why" to JsonPrimitive("сказал сам"))),
    )

    private suspend fun planDays(kind: (SessionKind) -> Boolean) =
        repo.store.programNow()!!.week.days.filter { d -> d.sessions.any { kind(it.kind) } }.map { it.day }.toSet()

    /** «Составь план»: muscle and endurance, running first, four days, an hour, 82 kg — one card, the plan by the app. */
    @Test fun aPlanIsBuiltFromWhatThePersonSaidAndCanBeUndone() = runBlocking<Unit> {
        val before = repo.store.profileNow()!!
        val ready = assertIs<CoachActions.Ready>(
            CoachActions.prepare(
                repo,
                call(
                    "lift_focus" to "muscle", "run_focus" to "endurance", "priority" to "run", "days" to "пн, вт, чт, сб",
                    "session_minutes" to 60, "body_weight" to 82.0,
                ),
            ),
        )
        assertEquals(CoachActions.Mode.CONFIRM, ready.mode)
        listOf("мышцы", "выносливость", "главное — бег", "вт, чт, сб → пн, вт, чт, сб", "до 75 мин → до 60 мин", "80 кг → 82 кг", "Неделя:", "План пересчитается")
            .forEach { assertTrue(it in ready.text, "«$it» in: ${ready.text}") }
        // Nothing changes before «Принять».
        assertEquals(before, repo.store.profileNow())

        val entry = CoachActions.make(repo, ready)
        val p = repo.store.profileNow()!!
        assertEquals(Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.RUN), p.aim)
        assertEquals(setOf(1, 2, 4, 6), p.trainingDays)
        assertEquals(60, p.sessionMinutes)
        assertEquals(82.0, p.bodyWeightKg)
        assertEquals(82.0, db.state().lastBodyWeight()!!.kg)
        // The app laid the week out on those days.
        assertTrue(setOf(1, 2, 4, 6).containsAll(planDays { it == SessionKind.LIFT } + planDays { it != SessionKind.LIFT }))
        assertTrue(planDays { it != SessionKind.LIFT }.isNotEmpty() && planDays { it == SessionKind.LIFT }.isNotEmpty())

        assertTrue(CoachActions.undo(repo, entry))
        val back = repo.store.profileNow()!!
        assertEquals(before.aim.normalized(), back.aim.normalized())
        assertEquals(before.trainingDays, back.trainingDays)
        assertEquals(75, back.sessionMinutes)
        assertEquals(80.0, back.bodyWeightKg)
    }

    /** Only what was said changes; what moved since in another field stays when it is undone. */
    @Test fun theWayBackPutsBackOnlyItsOwnFields() = runBlocking<Unit> {
        val entry = CoachActions.make(repo, assertIs<CoachActions.Ready>(CoachActions.prepare(repo, call("experience" to "intermediate"))))
        assertEquals(Experience.INTERMEDIATE, repo.store.profileNow()!!.experience)
        repo.logBodyWeight(79.0)
        assertTrue(CoachActions.undo(repo, entry))
        assertEquals(Experience.NOVICE, repo.store.profileNow()!!.experience)
        assertEquals(79.0, repo.store.profileNow()!!.bodyWeightKg)
        // Changed again since by the person: no way back over it.
        val again = CoachActions.make(repo, assertIs<CoachActions.Ready>(CoachActions.prepare(repo, call("experience" to "advanced"))))
        repo.updateProfile(repo.store.profileNow()!!.copy(experience = Experience.NOVICE))
        assertTrue(!CoachActions.undo(repo, again))
    }

    @Test fun nonsenseIsRefusedWithAReason() = runBlocking<Unit> {
        for (bad in listOf(
            call("body_weight" to 500), call("days" to "никогда"), call("session_minutes" to 5),
            call("lift_focus" to "none", "run_focus" to "none"), call("lift_focus" to "banana"), call("height" to 40),
            call("race_meters" to 1500), call("race_meters" to 1500, "race_time" to "0:30"), call("birth_year" to 1800),
        )) {
            val r = CoachActions.prepare(repo, bad)
            assertIs<CoachActions.Refused>(r, "${bad.args}: ${(r as? CoachActions.Ready)?.text}")
        }
        // Fields a model fills when not said (empty texts, zeros) are not said: only the weight changes.
        val filled = assertIs<CoachActions.Ready>(
            CoachActions.prepare(repo, call("days" to "", "lift_focus" to "", "session_minutes" to 0, "height" to 0, "body_weight" to 81.0)),
        )
        assertEquals("Вес тела: 80 кг → 81 кг.", filled.text)
        // The same as now: nothing to accept.
        assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call("body_weight" to 80.0)))
        assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call()))
    }

    /** A race typed sets the fitness the paces come from; its way back puts the running as it was. */
    @Test fun aBestRaceIsRecordedAndUndone() = runBlocking<Unit> {
        assertNull(repo.store.runStateNow().vdot)
        val ready = assertIs<CoachActions.Ready>(CoachActions.prepare(repo, call("race_meters" to 5000, "race_time" to "24:00", "finish_meters" to 10000)))
        assertTrue("5 км за 24:00" in ready.text && "10 км" in ready.text, ready.text)
        val entry = CoachActions.make(repo, ready)
        assertTrue(repo.store.runStateNow().vdot != null)
        assertEquals(5000.0, repo.store.profileNow()!!.raceMeters)
        assertTrue(CoachActions.undo(repo, entry))
        assertNull(repo.store.runStateNow().vdot)
        assertNull(repo.store.profileNow()!!.raceMeters)
        assertNull(repo.store.profileNow()!!.finishMeters)
    }

    /** On a friend's program its shape stays (it is theirs); the body's numbers are the person's own and go. */
    @Test fun aFriendsProgramKeepsItsShapeAndTakesTheWeight() = runBlocking<Unit> {
        val program = repo.store.programNow()!!
        repo.store.setProgram(program.copy(copiedFrom = "friend"))
        assertIs<CoachActions.Refused>(CoachActions.prepare(repo, call("days" to "пн, ср, пт")))
        val entry = CoachActions.make(repo, assertIs<CoachActions.Ready>(CoachActions.prepare(repo, call("body_weight" to 83.0, "height" to 181))))
        assertEquals(83.0, repo.store.profileNow()!!.bodyWeightKg)
        assertEquals(181, repo.store.profileNow()!!.heightCm)
        assertEquals("friend", repo.store.programNow()!!.copiedFrom)
        assertEquals(program.templates.map { it.id }, repo.store.programNow()!!.templates.map { it.id })
        assertTrue(CoachActions.undo(repo, entry))
        assertEquals(80.0, repo.store.profileNow()!!.bodyWeightKg)
        assertEquals("friend", repo.store.programNow()!!.copiedFrom)
    }

    /** The coach sees what it would ask about: the profile, field by field. */
    @Test fun theCoachSeesTheProfile() = runBlocking<Unit> {
        repo.updateProfile(repo.store.profileNow()!!.copy(gear = Gear.DUMBBELLS, pullupBar = true, heightCm = 180))
        val brief = coachContext(repo)
        val part = brief.substringAfter("## Анкета").substringBefore("\n## ")
        listOf("set_profile", "Для чего: зал — сила и масса, бег — выносливость", "Дни: вт, чт, сб", "Тренировка: до 75 мин", "Дома: гантели и скамья, есть турник",
            "Опыт в зале: новичок", "Бег сейчас: пробегает 20–30 минут", "Вес тела: 80 кг", "Рост: 180 см", "Лучший забег: нет")
            .forEach { assertTrue(it in part, "«$it» in:\n$part") }
    }

    /** Every function fits what the server takes (a bigger one is dropped there without a word), set_profile too. */
    @Test fun everyFunctionFitsTheServer() {
        val all = CoachActions.declarations
        assertTrue(all.size <= 40, "${all.size} functions")
        assertTrue(all.any { it.jsonObject["name"]!!.jsonPrimitive.content == CoachActions.PROFILE })
        for (f in all) {
            val o = f.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            assertTrue(Regex("^[a-z_]{1,40}$").matches(name), name)
            assertTrue(o["description"]!!.jsonPrimitive.content.length <= 800, "$name: ${o["description"]!!.jsonPrimitive.content.length}")
            assertTrue(o["parameters"].toString().length <= 4000, "$name: ${o["parameters"].toString().length}")
        }
        assertIs<JsonArray>(all)
    }
}
