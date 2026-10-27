package forma.app

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.CoachActions
import forma.app.sync.CoachCall
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Navigator
import forma.app.ui.Screen
import forma.app.ui.screens.MyExercisesScreen
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Gear
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Pattern
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «Мои упражнения» through the repository: likes and «не хочу» become replacements for good in the plan (the plan's own id
 * stays the key: own sets, rest, a place taken out stay), cleared picks bring the plan's own back while a hand-made
 * replacement stays, the plan laid out again keeps them, a home plan, and the coach's lever with «Вернуть».
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MyExercisesRepoTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private val day = LocalDate.of(2026, 10, 26)
    private var clock = day.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** A gym-goer who wants size, four days, upper/lower. */
    private val lifter = Profile(
        goal = Goal.MUSCLE, experience = Experience.INTERMEDIATE, runLevel = RunLevel.NONE, trainingDays = setOf(1, 2, 4, 5), bodyWeightKg = 75.0,
        sessionMinutes = 90, aim = Aim(LiftFocus.MUSCLE, RunFocus.NONE, Emphasis.GYM),
    )

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "my-exercises")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock++ })
        repo.completeOnboarding(lifter)
    }

    @After fun tearDown() = db.close()

    private suspend fun done(planId: String): String = repo.store.programNow()!!.templates.first { t -> t.slots.any { it.exerciseId == planId } }
        .let { t -> repo.liftPlan(t.id, day).items.first { it.slot.exerciseId == planId || it.planId == planId }.exerciseId }

    @Test fun aLikeGoesIntoThePlanAndClearedComesBack() = runBlocking<Unit> {
        // The person's own numbers for the place stay with it.
        repo.setPlanSets("leg_press", 4, 3)
        val r = repo.setPicks(mapOf(Pattern.SQUAT to listOf("hack_squat")), emptySet())
        assertEquals("hack_squat", r.swaps["squat"])
        assertEquals("hack_squat", done("squat"))
        val s = repo.store.settingsNow()
        assertEquals(listOf("hack_squat"), s.likes["SQUAT"])
        assertTrue("squat" in s.pickSwaps)
        assertEquals(4, s.sets["leg_press"])
        // A replacement made by hand stays when the picks are cleared; the picks' own goes back to the plan.
        repo.setSwap("bench", "db_bench")
        repo.setPicks(emptyMap(), emptySet())
        assertEquals("squat", done("squat"))
        assertEquals("db_bench", repo.store.settingsNow().swaps["bench"])
        assertTrue(repo.store.settingsNow().pickSwaps.isEmpty())
    }

    @Test fun notWantedIsReplacedAndAPlaceTakenOutStaysOut() = runBlocking<Unit> {
        repo.setRemoved("calf_raise", true)
        val r = repo.setPicks(mapOf(Pattern.CALF to listOf("seated_calf")), setOf("leg_press"))
        assertNull(r.swaps["calf_raise"], "the calves taken out stay out")
        assertTrue("calf_raise" in repo.store.settingsNow().removed)
        val to = r.swaps["leg_press"]!!
        assertTrue(Catalog[to].pattern == Pattern.SQUAT && to != "leg_press", to)
        // Not offered by «Идеальный план» either.
        val advice = repo.planAdvice()
        assertTrue(advice == null || advice.changes.none { c -> "leg_press" in c.options }, advice?.changes.toString())
    }

    /** The plan laid out again (another number of days): the picks are put in again by themselves. */
    @Test fun picksStayWhenThePlanIsLaidOutAgain() = runBlocking<Unit> {
        repo.setPicks(mapOf(Pattern.HORIZONTAL_PUSH to listOf("chest_press")), emptySet())
        assertEquals("chest_press", done("bench"))
        repo.updateProfile(repo.store.profileNow()!!.copy(trainingDays = setOf(1, 3, 5)))
        assertEquals("chest_press", done("bench"))
    }

    /** At home with dumbbells: the plan of dumbbells and the body; a gym machine cannot be liked. */
    @Test fun aHomePlanAndNoMachineToLike() = runBlocking<Unit> {
        repo.updateProfile(repo.store.profileNow()!!.copy(gear = Gear.DUMBBELLS))
        val ids = repo.store.programNow()!!.templates.flatMap { t -> t.slots.map { it.exerciseId } }
        assertTrue(ids.all { Gear.DUMBBELLS.fits(Catalog[it], false) }, ids.toString())
        val (_, rules) = repo.pickGroups()!!
        assertTrue(forma.core.plan.Picks.unavailable(Catalog["leg_press"], rules) != null)
        val r = CoachActions.prepare(repo, call(CoachActions.PICKS, "like" to "Жим ногами"), day)
        assertIs<CoachActions.Refused>(r)
    }

    private fun call(name: String, vararg a: Pair<String, Any>) = CoachCall(
        name, JsonObject(a.associate { (k, v) -> k to JsonPrimitive(v.toString()) } + ("why" to JsonPrimitive("проверка"))),
    )

    /** The coach: «нравится гакк, не хочу жим ногами» — the card says what changes, «Принять», then «Вернуть». */
    @Test fun theCoachMarksPicksWithAcceptAndUndo() = runBlocking<Unit> {
        val before = repo.store.settingsNow()
        val r = CoachActions.prepare(repo, call(CoachActions.PICKS, "like" to "Гакк-присед", "dislike" to "leg_press"), day)
            .let { assertIs<CoachActions.Ready>(it, (it as? CoachActions.Refused)?.why) }
        assertEquals(CoachActions.Mode.CONFIRM, r.mode)
        assertTrue("«Приседания со штангой» → «Гакк-присед»" in r.text, r.text)
        val e = CoachActions.make(repo, r)
        assertEquals("hack_squat", done("squat"))
        assertTrue("leg_press" in repo.store.settingsNow().dislikes)
        assertTrue(CoachActions.undo(repo, e))
        val after = repo.store.settingsNow()
        assertEquals(before.swaps, after.swaps)
        assertEquals(before.likes, after.likes)
        assertEquals(before.dislikes, after.dislikes)
        assertEquals("squat", done("squat"))
    }

    /** Strength as the aim (like Denis): the barbell main lifts stay, the card says so. */
    @Test fun strengthKeepsTheMainBarbellLifts() = runBlocking<Unit> {
        repo.updateProfile(repo.store.profileNow()!!.copy(aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), runGoal = RunGoal(1500, 330), runLevel = RunLevel.BASE))
        val r = repo.setPicks(mapOf(Pattern.SQUAT to listOf("hack_squat")), emptySet())
        assertNull(r.swaps["squat"])
        assertTrue(r.notes.any { "Цель — сила" in it }, r.notes.toString())
    }

    /** The screen: a movement opens, a like is ticked, «Что поменяется» says it, «Сохранить» saves it. */
    @Test fun theScreenTicksALikeAndSaves() {
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.MyExercises)) {
                FormaTheme(Looks.byId("carbon")) { MyExercisesScreen() }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Сейчас: Приседания со штангой", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Сейчас: Приседания со штангой", substring = true)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Нравится: Гакк-присед")).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("«Приседания со штангой» → «Гакк-присед»")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Сохранить") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { repo.store.settingsNow().likes["SQUAT"] } == listOf("hack_squat") }
        assertEquals("hack_squat", runBlocking { done("squat") })
    }
}
