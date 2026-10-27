package forma.core

import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Cues
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Gear
import forma.core.model.LiftFocus
import forma.core.model.LiftGoal
import forma.core.model.Pattern
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.plan.PlanAdvisor
import forma.core.plan.Picks
import forma.core.plan.Program
import forma.core.plan.ProgramGenerator
import forma.core.strength.SlotRole
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «Мои упражнения» (Denis: «чтобы он выбирал упражнения которые ему нравятся на каждую группу мышц чтобы исходя из всех
 * выбранных составлялся план индивидуальный»): one plain line of how to do every exercise, a plan made of what is there
 * where the person trains, and the picks per movement resolved into the plan by rules that keep it doing its job.
 */
class MyExercisesTest {
    private val monday = LocalDate.of(2026, 10, 26)

    private fun profile(lift: LiftFocus = LiftFocus.MUSCLE, exp: Experience = Experience.INTERMEDIATE, days: Set<Int> = setOf(1, 2, 4, 5)) = Profile(
        birthYear = 2000, bodyWeightKg = 75.0, experience = exp, runLevel = RunLevel.NONE, trainingDays = days, sessionMinutes = 90,
        aim = Aim(lift, RunFocus.NONE, Emphasis.GYM),
    )

    private fun places(p: Program, swaps: Map<String, String> = emptyMap()) =
        p.templates.flatMap { t -> t.slots.map { Picks.Place(t.id, it.exerciseId, swaps[it.exerciseId] ?: it.exerciseId, it.role) } }

    private fun ids(p: Program) = p.templates.flatMap { t -> t.slots.map { it.exerciseId } }

    @Test fun everyExerciseSaysHowToDoIt() {
        val missing = Catalog.builtIn.filter { !it.archived && Cues.of(it.id) == null }.map { it.id }
        assertTrue(missing.isEmpty(), "no cue: $missing")
        val unknown = Cues.ids.filter { Catalog.find(it) == null }
        assertTrue(unknown.isEmpty(), "cues of no exercise: $unknown")
        assertTrue(Cues.ids.all { Cues.of(it)!!.length in 30..240 }, Cues.ids.filter { Cues.of(it)!!.length !in 30..240 }.toString())
    }

    /** At home with dumbbells and a bench: every exercise of the plan can be done there, main places stay compound. */
    @Test fun aHomeWithDumbbellsGetsAPlanOfDumbbellsAndBody() {
        val p = ProgramGenerator.generate(profile(exp = Experience.NOVICE).copy(gear = Gear.DUMBBELLS), monday)
        val all = p.templates.flatMap { it.slots }
        assertTrue(all.all { Gear.DUMBBELLS.fits(Catalog[it.exerciseId], bar = false) }, ids(p).filterNot { Gear.DUMBBELLS.fits(Catalog[it], false) }.toString())
        assertTrue(all.filter { it.role == SlotRole.MAIN }.all { Catalog[it.exerciseId].compound }, ids(p).toString())
        assertTrue(all.none { it.exerciseId in PlanAdvisor.HARD }, ids(p).toString())
        assertTrue(p.week.notes.any { it.startsWith("Дома: гантели и скамья: план собран из того, что там есть") }, p.week.notes.toString())
        // Every movement of the gym plan is still there.
        val gymPatterns = ProgramGenerator.generate(profile(exp = Experience.NOVICE), monday).templates.flatMap { t -> t.slots.map { Catalog[it.exerciseId].pattern } }.toSet()
        val homePatterns = all.map { Catalog[it.exerciseId].pattern }.toSet()
        assertTrue((gymPatterns - homePatterns - setOf(Pattern.VERTICAL_PULL, Pattern.REAR_DELT, Pattern.KNEE_FLEXION, Pattern.KNEE_EXTENSION)).isEmpty(), "${gymPatterns - homePatterns}")
    }

    /** The body alone, no bar: push-ups, squats without weight, rows under a table; what has no stand-in is said. */
    @Test fun aHomeWithTheBodyAloneSaysWhatHasNoStandIn() {
        val p = ProgramGenerator.generate(profile(exp = Experience.NOVICE).copy(gear = Gear.BODY), monday)
        val all = ids(p)
        assertTrue(all.all { Gear.BODY.fits(Catalog[it], bar = false) }, all.toString())
        assertTrue("pushup" in all || "knee_pushup" in all || "incline_pushup" in all, all.toString())
        assertTrue(all.none { it in setOf("pullup", "chinup", "leg_raise") }, "no bar at home: $all")
        // With a bar: pull-ups come in.
        val bar = ids(ProgramGenerator.generate(profile(exp = Experience.NOVICE).copy(gear = Gear.BODY, pullupBar = true), monday))
        assertTrue(bar.any { it in setOf("pullup", "chinup", "neutral_pullup") }, bar.toString())
        // The gym: nothing changes, nothing said.
        val gym = ProgramGenerator.generate(profile(), monday)
        assertTrue(gym.week.notes.none { "план собран из того" in it })
    }

    /** A liked squat stands in the squat places; a liked leg extension never does (the movement is locked). */
    @Test fun aPickStandsInOnlyForItsOwnMovement() {
        val p = ProgramGenerator.generate(profile(), monday)
        val r = Picks.resolve(places(p), mapOf(Pattern.SQUAT to listOf("hack_squat"), Pattern.KNEE_EXTENSION to listOf("sissy_squat")), emptySet(), Picks.Rules())
        assertEquals("hack_squat", r.swaps["squat"])
        assertTrue(r.swaps.values.none { it == "sissy_squat" } || r.swaps.filterValues { it == "sissy_squat" }.keys.all { Catalog[it].pattern == Pattern.KNEE_EXTENSION }, r.swaps.toString())
        assertTrue(r.swaps.keys.all { Catalog[it].pattern == Catalog[r.swaps.getValue(it)].pattern }, r.swaps.toString())
    }

    /** Two liked squats over three squat places: in turn, never the same twice in one session. */
    @Test fun severalLikesShareThePlacesOfTheirMovement() {
        val p = ProgramGenerator.generate(profile(), monday)
        val squatPlaces = places(p).filter { Catalog[it.planId].pattern == Pattern.SQUAT }.map { it.planId }.distinct()
        assertTrue(squatPlaces.size >= 2, squatPlaces.toString())
        val r = Picks.resolve(places(p), mapOf(Pattern.SQUAT to listOf("hack_squat", "goblet_squat")), emptySet(), Picks.Rules())
        val got = squatPlaces.map { r.swaps[it] ?: it }
        assertTrue("hack_squat" in got && "goblet_squat" in got, got.toString())
        p.templates.forEach { t ->
            val done = t.slots.map { r.swaps[it.exerciseId] ?: it.exerciseId }
            assertEquals(done.size, done.distinct().size, "${t.title}: $done")
        }
    }

    /** An isolation exercise liked for a main place: kept, said why. */
    @Test fun aMainPlaceKeepsACompoundExercise() {
        val p = ProgramGenerator.generate(profile(exp = Experience.NOVICE, days = setOf(1, 3, 5)), monday)
        val r = Picks.resolve(places(p), mapOf(Pattern.VERTICAL_PULL to listOf("straight_arm_pulldown")), emptySet(), Picks.Rules(novice = true))
        val pulldown = places(p).first { it.planId == "lat_pulldown" }
        assertTrue(pulldown.role != SlotRole.ACCESSORY)
        assertNull(r.swaps["lat_pulldown"])
        assertTrue(r.notes.any { "на одну мышцу" in it }, r.notes.toString())
    }

    /** Strength as the aim: the barbell main lifts stay (a hand-made replacement too); the gym goal's lift stays. */
    @Test fun strengthKeepsTheBarbellMainsAndTheGoalLiftStays() {
        val p = ProgramGenerator.generate(profile(lift = LiftFocus.STRENGTH), monday)
        val rules = Picks.Rules(strength = true)
        val r = Picks.resolve(places(p), mapOf(Pattern.SQUAT to listOf("hack_squat")), emptySet(), rules)
        assertNull(r.swaps["squat"])
        assertTrue(r.notes.any { "Цель — сила" in it }, r.notes.toString())
        // A replacement the person made by hand on a main lift is theirs: picks do not undo it.
        val own = Picks.resolve(places(p, mapOf("squat" to "box_squat")), mapOf(Pattern.SQUAT to listOf("hack_squat")), emptySet(), rules)
        assertNull(own.swaps["squat"])
        // The gym goal's lift.
        val m = ProgramGenerator.generate(profile(), monday)
        val g = Picks.resolve(places(m), mapOf(Pattern.HORIZONTAL_PUSH to listOf("chest_press")), emptySet(), Picks.Rules(goalLift = "bench"))
        assertNull(g.swaps["bench"])
        assertTrue(g.notes.any { "цели в зале" in it }, g.notes.toString())
    }

    /** A beginner cannot like the hard, unusual ones yet; the option says why. */
    @Test fun hardOnesWaitForABeginnersFirstWeeks() {
        val rules = Picks.Rules(novice = true)
        assertTrue(Picks.unavailable(Catalog["bulgarian"], rules)!!.startsWith("после первых недель"))
        assertNull(Picks.unavailable(Catalog["bulgarian"], Picks.Rules()))
        assertTrue(Picks.unavailable(Catalog["leg_press"], Picks.Rules(gear = Gear.DUMBBELLS))!!.startsWith("нечем делать"))
        val p = ProgramGenerator.generate(profile(exp = Experience.NOVICE, days = setOf(1, 3, 5)).copy(aim = Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.BALANCED)), monday)
        val r = Picks.resolve(places(p), mapOf(Pattern.LUNGE to listOf("bulgarian")), emptySet(), rules)
        assertTrue(r.swaps.values.none { it == "bulgarian" }, r.swaps.toString())
    }

    /** A place taken out of the plan stays out (Denis took his calves out: a liked calf raise does not bring them back). */
    @Test fun aPlaceTakenOutStaysOut() {
        val p = ProgramGenerator.generate(profile(), monday)
        assertTrue("calf_raise" in ids(p))
        val r = Picks.resolve(places(p), mapOf(Pattern.CALF to listOf("seated_calf")), emptySet(), Picks.Rules(removed = setOf("calf_raise")))
        assertNull(r.swaps["calf_raise"])
    }

    /** «Не хочу»: the place gets the closest fine exercise; earlier picks with nothing behind them go back to the plan's own. */
    @Test fun notWantedIsReplacedAndOldPicksGoBack() {
        val p = ProgramGenerator.generate(profile(), monday)
        val r = Picks.resolve(places(p), emptyMap(), setOf("leg_press"), Picks.Rules())
        val to = r.swaps["leg_press"]
        assertTrue(to != null && Catalog[to].pattern == Pattern.SQUAT && Catalog[to].compound && to != "leg_press", r.swaps.toString())
        // A swap the picks made, the like now cleared: back to the plan's own; a swap made by hand stays.
        val back = Picks.resolve(places(p, mapOf("squat" to "hack_squat", "bench" to "db_bench")), emptyMap(), emptySet(), Picks.Rules(), made = setOf("squat"))
        assertEquals("squat", back.swaps["squat"])
        assertFalse("bench" in back.swaps)
    }
}
