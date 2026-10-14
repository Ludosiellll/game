package forma.core

import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Muscle
import forma.core.model.RunFocus
import forma.core.plan.PlanAdvisor
import forma.core.plan.PlanAdvisor.Day
import forma.core.plan.PlanAdvisor.Item
import forma.core.plan.PlanAdvisor.Kind
import forma.core.plan.Templates
import forma.core.strength.SlotRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Идеальный план»: what could be better in a gym plan, why, with options — and nothing changed by itself. */
class PlanAdvisorTest {
    private val hybrid = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED)

    /** Denis's week: upper / legs / upper with three runs, and the leg extension he put in the place of the Bulgarian split squat. */
    private fun denis(): List<Day> = Templates.forWeek(hybrid, Experience.ADVANCED, 3, shared = true).map { t ->
        Day(t.id, t.title, t.slots.map { s ->
            Item(if (s.exerciseId == "bulgarian") "leg_ext" else s.exerciseId, s.exerciseId, s.sets, s.repMin, s.repMax, s.restSec, s.role)
        })
    }

    private fun input(days: List<Day>, aim: Aim = hybrid, runs: Int = 3, exp: Experience = Experience.ADVANCED) =
        PlanAdvisor.Input(days, aim, exp, runs = runs, sessionMinutes = 75, find = Catalog::find)

    @Test fun denisGetsTheOneLegLiftBackNordicsTheSoleusAndAChestSet() {
        val a = PlanAdvisor.advise(input(denis()))
        val ids = a.changes.map { it.id }
        val oneLeg = a.changes.first { it.id == "oneleg:bulgarian" }
        assertEquals(Kind.SWAP, oneLeg.kind)
        assertEquals("leg_ext", oneLeg.from)
        // His own exercise back first; the easier ones as options.
        assertEquals(listOf("bulgarian", "split_squat", "step_up", "reverse_lunge"), oneLeg.options)
        val nordic = a.changes.first { it.id == "add:nordic" }
        assertEquals("lower_a", nordic.templateId)
        assertEquals(2, nordic.setsTo)
        assertEquals(4 to 8, nordic.repsTo)
        assertEquals("lower_a", a.changes.first { it.id == "add:seated_calf" }.templateId)
        // Chest: 6 sets a week against 8–18: a set more where it works already, not on the bench (a main lift).
        val chest = a.changes.first { it.id.startsWith("vol:CHEST") }
        assertEquals("db_incline", chest.planId)
        assertEquals(3 to 4, chest.setsFrom to chest.setsTo)
        // Nothing else: the back, shoulders, arms are in range, pushing and pulling are even, the sessions fit.
        assertEquals(4, ids.size, ids.toString())
        assertTrue(a.fine.any { "Жимы и тяги в балансе" in it }, a.fine.toString())
        assertTrue(a.fine.any { "По времени укладываешься" in it }, a.fine.toString())
        val calves = a.volume.first { it.muscle == Muscle.CALVES }
        assertEquals(2.0, calves.now)
        assertEquals(5.0, calves.after)
        assertTrue(a.changes.all { it.why.isNotBlank() })
    }

    @Test fun onlyTheChosenChangesCountAndAPickedOptionIsTheOneTaken() {
        val a = PlanAdvisor.advise(input(denis()))
        val swap = a.changes.first { it.id == "oneleg:bulgarian" }.pick("split_squat")
        assertEquals("split_squat", swap.to)
        val after = PlanAdvisor.apply(a.days, listOf(swap))
        assertTrue(after.first { it.templateId == "lower_a" }.items.any { it.exerciseId == "split_squat" && it.planId == "bulgarian" })
        // Nothing chosen: the plan as it is.
        assertEquals(a.days, PlanAdvisor.apply(a.days, emptyList()))
        val table = PlanAdvisor.table(a, after, Catalog::find)
        val glutes = table.first { it.muscle == Muscle.GLUTES }
        assertEquals(glutes.now + 3, glutes.after)
    }

    @Test fun aMainLiftWithoutANewBestForWeeksGetsAVariationFirst() {
        val a = PlanAdvisor.advise(input(denis()).copy(stalled = mapOf("bench" to 6, "squat" to 2)))
        val first = a.changes.first()
        assertEquals("stall:upper_a:bench", first.id)
        assertEquals(1, first.priority)
        assertEquals("pause_bench", first.to)
        assertTrue("6 недель" in first.why, first.why)
        // Two weeks is not a stall.
        assertTrue(a.changes.none { it.id.startsWith("stall:") && it.planId == "squat" })
    }

    /** Many lifts stuck at once: said to be recovery; only the longest stuck gets another variation. */
    @Test fun manyLiftsStuckAtOnceIsRecoveryNotTheExercises() {
        val a = PlanAdvisor.advise(input(denis()).copy(stalled = mapOf("bench" to 6, "row" to 5, "ohp" to 8, "squat" to 5)))
        assertEquals(listOf("stall:upper_b:ohp"), a.changes.filter { it.id.startsWith("stall:") }.map { it.id })
        assertTrue(a.warning!!.startsWith("Застой сразу в 4 основных упражнениях"), a.warning)
        assertEquals(null, PlanAdvisor.advise(input(denis())).warning)
    }

    @Test fun nothingNewForTheLegsBeforeTheRace() {
        val a = PlanAdvisor.advise(input(denis()).copy(daysToRace = 12, stalled = mapOf("squat" to 6, "bench" to 6)))
        // Not even his own one-leg lift back, nor another squat: only the upper body's stall.
        assertTrue(a.changes.none { it.id == "add:nordic" || it.id == "add:seated_calf" || it.id.startsWith("oneleg") }, a.changes.map { it.id }.toString())
        assertEquals(listOf("stall:upper_a:bench"), a.changes.filter { it.id.startsWith("stall:") }.map { it.id })
        assertTrue(a.fine.any { "До забега 12 дней" in it }, a.fine.toString())
    }

    /** A muscle short and another with sets to spare: one exercise for the other, the session no longer. */
    @Test fun setsMoveFromAMuscleWithSetsToSpareToOneShortOfThem() {
        val A = SlotRole.ACCESSORY
        val day = Day("upper", "Верх", listOf(
            Item("bench", "bench", 3, 6, 10, 150, SlotRole.MAIN),
            Item("row", "row", 3, 6, 10, 150, SlotRole.MAIN),
            Item("db_curl", "db_curl", 4, 10, 15, 75, A),
            Item("hammer_curl", "hammer_curl", 4, 10, 15, 75, A),
            Item("bb_curl", "bb_curl", 4, 10, 15, 75, A),
            Item("preacher_curl", "preacher_curl", 4, 10, 15, 75, A),
        ))
        val a = PlanAdvisor.advise(input(listOf(day), Aim(LiftFocus.MUSCLE, RunFocus.NONE, Emphasis.GYM), runs = 0, exp = Experience.INTERMEDIATE))
        val c = a.changes.first { it.id.startsWith("rebalance:CHEST") }
        assertEquals(Kind.SWAP, c.kind)
        assertEquals("db_curl", c.from)
        assertEquals("cable_fly", c.to)
        assertTrue("Бицепс" in c.why || "бицепс" in c.why, c.why)
        // Not a runner: nothing about running.
        assertTrue(a.changes.none { it.id == "add:nordic" || it.id.startsWith("oneleg") })
    }

    @Test fun manyPressesAndFewPullsGetARearDeltExercise() {
        val day = Day("upper", "Верх", listOf(
            Item("bench", "bench", 4, 5, 8, 150, SlotRole.MAIN),
            Item("ohp", "ohp", 4, 5, 8, 150, SlotRole.MAIN),
            Item("db_incline", "db_incline", 3, 8, 12, 120, SlotRole.SECONDARY),
            Item("lat_pulldown", "lat_pulldown", 3, 8, 12, 120, SlotRole.SECONDARY),
        ))
        val a = PlanAdvisor.advise(input(listOf(day), Aim(LiftFocus.STRENGTH, RunFocus.NONE, Emphasis.GYM), runs = 0))
        val c = a.changes.first { it.id == "add:pull" }
        assertEquals("face_pull", c.to)
        assertTrue("11 подходов" in c.why && "тяг — 3 (с предложенными выше — 7)" in c.why, c.why)
    }

    @Test fun aSessionLongerThanTheTimeThereIsLosesAnAccessorySet() {
        val days = denis()
        val a = PlanAdvisor.advise(input(days).copy(sessionMinutes = 40, timeRatio = 1.3))
        assertTrue(a.changes.any { it.id.startsWith("time:") }, a.changes.map { it.id }.toString())
        assertTrue(a.changes.first { it.id.startsWith("time:") }.why.contains(" мин и почти ничего не стоит"))
        // A day that fits as it is is not cut for what the advisor itself adds: the addition says how long the day gets.
        val fits = PlanAdvisor.advise(input(days).copy(sessionMinutes = 44))
        assertTrue(fits.changes.none { it.id.startsWith("time:lower_a") }, fits.changes.map { it.id }.toString())
        assertTrue(fits.fine.any { "По времени укладываешься" in it }, fits.fine.toString())
        assertTrue(fits.changes.first { it.id == "add:seated_calf" }.why.contains("С ним «Низ» — около"), fits.changes.first { it.id == "add:seated_calf" }.why)
        val tired = PlanAdvisor.advise(input(days).copy(tiredOften = true))
        // Denis's accessories are two sets each already (running comes too): nothing to take.
        assertTrue(tired.changes.none { it.id.startsWith("rest:") }, tired.changes.map { it.id + " " + it.setsFrom }.toString())
    }

    @Test fun aWeekWithoutTheGymHasNothingToSay() {
        val a = PlanAdvisor.advise(input(emptyList()))
        assertTrue(a.changes.isEmpty())
        assertEquals("В неделе нет зала — разбирать нечего.", a.summary)
    }
}
