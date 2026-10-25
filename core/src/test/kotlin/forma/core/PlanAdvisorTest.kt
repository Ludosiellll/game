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

    /**
     * Denis swapped the Bulgarian split squat for the leg extension himself: it is not undone, and no hard unusual exercise
     * (Nordic curls) is pushed on him — said why, with what he can do instead. What is proposed: the soleus, a chest set.
     */
    @Test fun denisChoicesStayNoHardUnusualExercisesTheSoleusAndAChestSet() {
        val a = PlanAdvisor.advise(input(denis()))
        val ids = a.changes.map { it.id }
        assertTrue(ids.none { it.startsWith("oneleg") || it == "add:nordic" }, ids.toString())
        assertTrue(a.changes.none { "bulgarian" in it.options || "nordic_curl" in it.options })
        assertTrue(
            a.held.any { "«Болгарские сплит-приседания» ты сам заменил на «Разгибание ног» — это твой выбор, обратно не предлагаю" in it && "«Зашагивания на тумбу»" in it },
            a.held.toString(),
        )
        assertTrue(a.held.any { it.startsWith("Нордические сгибания не предлагаю") && "«Сгибание ног лёжа», если опускать вес медленно" in it }, a.held.toString())
        assertEquals("lower_a", a.changes.first { it.id == "add:seated_calf" }.templateId)
        // Chest: 6 sets a week against 8–18: a set more where it works already, not on the bench (a main lift).
        val chest = a.changes.first { it.id.startsWith("vol:CHEST") }
        assertEquals("db_incline", chest.planId)
        assertEquals(3 to 4, chest.setsFrom to chest.setsTo)
        // Nothing else: the back, shoulders, arms are in range, pushing and pulling are even, the sessions fit.
        assertEquals(2, ids.size, ids.toString())
        assertTrue(a.fine.any { "Жимы и тяги в балансе" in it }, a.fine.toString())
        assertTrue(a.fine.any { "По времени укладываешься" in it }, a.fine.toString())
        val calves = a.volume.first { it.muscle == Muscle.CALVES }
        assertEquals(2.0, calves.now)
        assertEquals(5.0, calves.after)
        assertTrue(a.changes.all { it.why.isNotBlank() })
    }

    /** A runner with no one-leg lift and no sign either way: one is proposed, the hard Bulgarian last; Nordic said to be hard. */
    @Test fun withoutASignTheHardOnesComeLastAndAreSaidToBeHard() {
        val days = denis().map { d -> d.copy(items = d.items.filter { it.planId != "bulgarian" }) }
        val a = PlanAdvisor.advise(input(days))
        assertEquals(listOf("split_squat", "step_up", "reverse_lunge", "bulgarian"), a.changes.first { it.id == "oneleg:add" }.options)
        assertTrue("тяжёлое и непривычное" in a.changes.first { it.id == "add:nordic" }.why)
        // One who does hard unusual exercises gets them first.
        val likes = PlanAdvisor.advise(input(days).copy(habits = PlanAdvisor.Habits(likesHard = true)))
        assertEquals("bulgarian", likes.changes.first { it.id == "oneleg:add" }.to)
    }

    @Test fun onlyTheChosenChangesCountAndAPickedOptionIsTheOneTaken() {
        val days = denis().map { d -> d.copy(items = d.items.filter { it.planId != "bulgarian" }) }
        val a = PlanAdvisor.advise(input(days))
        val add = a.changes.first { it.id == "oneleg:add" }.pick("step_up")
        assertEquals("step_up", add.to)
        val after = PlanAdvisor.apply(a.days, listOf(add))
        assertTrue(after.first { it.templateId == "lower_a" }.items.any { it.exerciseId == "step_up" && it.added })
        // Nothing chosen: the plan as it is.
        assertEquals(a.days, PlanAdvisor.apply(a.days, emptyList()))
        val table = PlanAdvisor.table(a, after, Catalog::find)
        val glutes = table.first { it.muscle == Muscle.GLUTES }
        assertEquals(glutes.now + 3, glutes.after)
    }

    /** What he really does: an exercise skipped often — another way for the same muscle; sets cut short — the plan set to them. */
    @Test fun thePlanFollowsWhatHeDoes() {
        val habits = PlanAdvisor.Habits(
            skipped = mapOf("hammer_curl" to (3 to 4)),
            doneSets = mapOf("lateral_raise" to 1),
            avoided = mapOf("hammer_curl" to "ты пропускаешь в 3 из 4 тренировок", "leg_curl" to "ты убрал из плана"),
        )
        val a = PlanAdvisor.advise(input(denis()).copy(habits = habits))
        val skip = a.changes.first { it.id == "skip:hammer_curl" }
        assertEquals(Kind.SWAP, skip.kind)
        assertTrue("hammer_curl" !in skip.options && skip.options.isNotEmpty(), skip.options.toString())
        assertTrue("в 3 из 4 последних тренировок" in skip.why)
        val real = a.changes.first { it.id == "real:lateral_raise" }
        assertEquals(2 to 1, real.setsFrom to real.setsTo)
        // Nothing removed or skipped comes back as an option anywhere.
        assertTrue(a.changes.none { c -> "leg_curl" in c.options || ("hammer_curl" in c.options) })
    }

    /** «Не предлагать»: hidden, counted, not shown. */
    @Test fun aChangeTheyHidIsNotProposed() {
        val a = PlanAdvisor.advise(input(denis()).copy(habits = PlanAdvisor.Habits(hidden = setOf("add:seated_calf"))))
        assertTrue(a.changes.none { it.id == "add:seated_calf" })
        assertEquals(1, a.hidden)
    }

    @Test fun aMainLiftWithoutANewBestForWeeksGetsAVariationFirst() {
        val a = PlanAdvisor.advise(input(denis()).copy(stalled = mapOf("bench" to 6, "squat" to 2)))
        val first = a.changes.first()
        assertEquals("stall:upper_a:bench", first.id)
        assertEquals(1, first.priority)
        assertEquals("pause_bench", first.to)
        assertTrue("6 недель" in first.why, first.why)
        // Not a variation done on another day already: the shoulder press is stuck, the standing press is on Верх · Б.
        val shoulders = PlanAdvisor.advise(input(denis()).copy(stalled = mapOf("db_shoulder" to 6))).changes.first { it.id.startsWith("stall:") }
        assertEquals(listOf("arnold_press", "machine_shoulder_press"), shoulders.options)
        // Two weeks is not a stall.
        assertTrue(a.changes.none { it.id.startsWith("stall:") && it.planId == "squat" })
    }

    /** Many lifts stuck at once: said to be recovery; only the longest stuck gets another variation. */
    @Test fun manyLiftsStuckAtOnceIsRecoveryNotTheExercises() {
        val a = PlanAdvisor.advise(input(denis()).copy(stalled = mapOf("bench" to 6, "row" to 5, "ohp" to 8, "squat" to 5)))
        assertEquals(listOf("stall:upper_b:ohp"), a.changes.filter { it.id.startsWith("stall:") }.map { it.id })
        assertTrue(a.warning!!.startsWith("Сразу в 4 основных упражнениях веса стоят на месте"), a.warning)
        assertEquals(null, PlanAdvisor.advise(input(denis())).warning)
    }

    @Test fun nothingNewForTheLegsBeforeTheRace() {
        val a = PlanAdvisor.advise(input(denis()).copy(daysToRace = 12, stalled = mapOf("squat" to 6, "bench" to 6)))
        // Not even his own one-leg lift back, nor another squat: only the upper body's stall.
        assertTrue(a.changes.none { it.id == "add:nordic" || it.id == "add:seated_calf" || it.id.startsWith("oneleg") }, a.changes.map { it.id }.toString())
        assertEquals(listOf("stall:upper_a:bench"), a.changes.filter { it.id.startsWith("stall:") }.map { it.id })
        assertTrue(a.held.any { "До забега 12 дней" in it }, a.held.toString())
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
        // Running for health only is not a runner's legs either.
        assertTrue(PlanAdvisor.advise(input(denis(), Aim(LiftFocus.POWERBUILDING, RunFocus.HEALTH, Emphasis.BALANCED))).changes.none { it.id == "add:nordic" })
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
        assertTrue("11 подходов" in c.why && "тяг — 3 (с предложенными выше — 4)" in c.why, c.why)
    }

    @Test fun aSessionLongerThanTheTimeThereIsLosesAnAccessorySet() {
        val days = denis()
        val a = PlanAdvisor.advise(input(days).copy(sessionMinutes = 40, timeRatio = 1.3))
        assertTrue(a.changes.any { it.id.startsWith("time:") }, a.changes.map { it.id }.toString())
        assertTrue(a.changes.first { it.id.startsWith("time:") }.why.contains(" мин. Главное останется."))
        // A day that fits as it is is not cut for what the advisor itself adds: the addition says how long the day gets.
        val fits = PlanAdvisor.advise(input(days).copy(sessionMinutes = 44))
        assertTrue(fits.changes.none { it.id.startsWith("time:lower_a") }, fits.changes.map { it.id }.toString())
        assertTrue(fits.fine.any { "По времени укладываешься" in it }, fits.fine.toString())
        assertTrue(fits.changes.first { it.id == "add:seated_calf" }.why.contains("С ним «Низ» займёт около"), fits.changes.first { it.id == "add:seated_calf" }.why)
        val tired = PlanAdvisor.advise(input(days).copy(tiredOften = true))
        // Denis's accessories are two sets each already (running comes too): nothing to take.
        assertTrue(tired.changes.none { it.id.startsWith("rest:") }, tired.changes.map { it.id + " " + it.setsFrom }.toString())
    }

    /** A short gym day of a runner, twice a week: what keeps the legs whole comes first; more for the chest would not fit — said, not proposed. */
    @Test fun whatDoesNotFitTheTimeIsSaidNotProposed() {
        val day = Day("full_a", "Всё тело", listOf(
            Item("squat", "squat", 3, 5, 8, 150, SlotRole.MAIN),
            Item("bench", "bench", 3, 5, 8, 150, SlotRole.MAIN),
            Item("row", "row", 3, 8, 10, 120, SlotRole.SECONDARY),
            Item("lateral_raise", "lateral_raise", 2, 10, 15, 75, SlotRole.ACCESSORY),
            Item("plank", "plank", 3, 20, 60, 60, SlotRole.ACCESSORY),
        ), times = 2)
        val a = PlanAdvisor.advise(input(listOf(day)).copy(sessionMinutes = 45))
        val ids = a.changes.map { it.id }
        assertTrue("add:nordic" in ids && "oneleg:add" in ids, ids.toString())
        assertTrue(ids.none { it.startsWith("add:CHEST") }, ids.toString())
        assertTrue(a.held.any { it.startsWith("Грудь: 6 подходов") && "не влезет" in it }, a.held.toString())
        // The same day as the only one of the week, with time for everything: a second day, not one crammed session.
        val once = PlanAdvisor.advise(input(listOf(day.copy(times = 1))).copy(sessionMinutes = 120))
        assertTrue(once.changes.none { it.id.startsWith("add:CHEST") || it.id.startsWith("add:BICEPS") }, once.changes.map { it.id }.toString())
        assertTrue(once.held.any { it.startsWith("Зал раз в неделю") && "второй день" in it }, once.held.toString())
    }

    @Test fun aWeekWithoutTheGymHasNothingToSay() {
        val a = PlanAdvisor.advise(input(emptyList()))
        assertTrue(a.changes.isEmpty())
        assertEquals("В неделе нет зала — разбирать нечего.", a.summary)
    }
}
