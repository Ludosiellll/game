package forma.core

import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Muscle
import forma.core.model.Pattern
import forma.core.model.RunFocus
import forma.core.plan.DayPlan
import forma.core.plan.PlanAdvisor
import forma.core.plan.PlanAdvisor.Day
import forma.core.plan.PlanAdvisor.Item
import forma.core.plan.PlanAdvisor.Kind
import forma.core.plan.PlannedSession
import forma.core.plan.SessionKind
import forma.core.plan.Templates
import forma.core.plan.WeekPlan
import forma.core.strength.SlotRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Research legs-norm-for-runners+D3 and part (a) of one-leg-day+D1+legs-volume-one-day, approved by the owner on 25 Nov:
 * a runner's leg band follows the aim (8–12 for «Сила и масса», 10–12 for «Мышцы», 4–12 otherwise, with 4–7 never called
 * «в норме»), one table for every screen; «Идеальный план» offers a runner who wants bigger legs and is below the band a
 * set more of the leg curl and of the leg extension, the hamstrings first, and two light sets of one of them at the end of
 * the upper session after the week's second hard run. Taken, they change those sets and nothing else; no calf or shin work.
 */
class ResearchLegsTest {
    private val hybrid = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED)
    private val thighs = listOf(Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES)

    /** Denis's week: hard runs Mon and Thu (the run first, then «Верх · А»), «Верх · Б» on Tue, «Низ» then an easy run on Sat. */
    private val denisWeek = WeekPlan(
        listOf(
            DayPlan(1, listOf(PlannedSession(SessionKind.RUN_QUALITY))),
            DayPlan(2, listOf(PlannedSession(SessionKind.LIFT, "upper_b"))),
            DayPlan(4, listOf(PlannedSession(SessionKind.RUN_QUALITY), PlannedSession(SessionKind.LIFT, "upper_a"))),
            DayPlan(6, listOf(PlannedSession(SessionKind.LIFT, "lower_a"), PlannedSession(SessionKind.RUN_EASY))),
        ),
        emptyList(),
    )

    /** His templates with the leg extension he put in the Bulgarian split squat's place (as in PlanAdvisorTest). */
    private fun denis(): List<Day> = Templates.forWeek(hybrid, Experience.ADVANCED, 3, shared = true).map { t ->
        Day(t.id, t.title, t.slots.map { s ->
            Item(if (s.exerciseId == "bulgarian") "leg_ext" else s.exerciseId, s.exerciseId, s.sets, s.repMin, s.repMax, s.restSec, s.role)
        })
    }

    private fun input(days: List<Day>, aim: Aim = hybrid, week: WeekPlan? = denisWeek, accents: Set<Muscle> = emptySet()) =
        PlanAdvisor.Input(days, aim, Experience.ADVANCED, runs = 3, sessionMinutes = 75, accents = accents, week = week, find = Catalog::find)

    @Test fun aRunnersLegBandFollowsTheAim() {
        fun band(lift: LiftFocus, m: Muscle, accents: Set<Muscle> = emptySet()) =
            PlanAdvisor.range(Aim(lift, RunFocus.ENDURANCE, Emphasis.BALANCED), Experience.ADVANCED, 3, accents, m)
        for (m in thighs) {
            assertEquals(8 to 12, band(LiftFocus.POWERBUILDING, m), "${m.name}, «Сила и масса»")
            assertEquals(10 to 12, band(LiftFocus.MUSCLE, m), "${m.name}, «Мышцы»")
            assertEquals(4 to 12, band(LiftFocus.STRENGTH, m), "${m.name}, «Сила»")
            assertEquals(4 to 12, band(LiftFocus.GENERAL, m), "${m.name}, general fitness")
        }
        // A leg accent: the size floor whatever the focus, and room above 12 for what the accent itself adds.
        assertEquals(8 to 22, band(LiftFocus.STRENGTH, Muscle.QUADS, setOf(Muscle.QUADS)))
        assertEquals(4 to 12, band(LiftFocus.STRENGTH, Muscle.HAMSTRINGS, setOf(Muscle.QUADS)))
        // The calves keep a runner's own band; the legs of one who does not run keep the focus's.
        assertEquals(4 to 12, band(LiftFocus.POWERBUILDING, Muscle.CALVES))
        assertEquals(8 to 18, PlanAdvisor.range(Aim(LiftFocus.POWERBUILDING, RunFocus.NONE, Emphasis.GYM), Experience.ADVANCED, 0, emptySet(), Muscle.QUADS))
        // The advisor's own input reads the same table, and so do the screens ([PlanAdvisor.ranges]).
        assertEquals(8 to 12, PlanAdvisor.range(input(denis()), Muscle.HAMSTRINGS))
        assertEquals(8 to 12, PlanAdvisor.ranges(hybrid, Experience.ADVANCED, 3, emptySet()).getValue(Muscle.GLUTES))
    }

    /** «Сила» and running: 5 leg sets a week work, but are low for growth — never «в норме», and nothing is pushed. */
    @Test fun fiveLegSetsOfARunnerWithoutASizeAimAreNotCalledInNorm() {
        val aim = Aim(LiftFocus.STRENGTH, RunFocus.ENDURANCE, Emphasis.BALANCED)
        val upper = Day("upper", "Верх", listOf(
            Item("bench", "bench", 3, 4, 6, 180, SlotRole.MAIN),
            Item("row", "row", 3, 4, 6, 180, SlotRole.MAIN),
            Item("ohp", "ohp", 3, 6, 8, 150, SlotRole.SECONDARY),
            Item("lat_pulldown", "lat_pulldown", 3, 6, 8, 150, SlotRole.SECONDARY),
        ))
        // Quads 3 + 2 = 5, hamstrings 1.5 + 2 + 2 = 5.5, glutes 3 + 2 = 5.
        val lower = Day("lower", "Низ", listOf(
            Item("squat", "squat", 3, 4, 6, 180, SlotRole.MAIN),
            Item("rdl", "rdl", 2, 6, 8, 150, SlotRole.SECONDARY),
            Item("leg_ext", "leg_ext", 2, 8, 12, 90, SlotRole.ACCESSORY),
            Item("leg_curl", "leg_curl", 2, 8, 12, 90, SlotRole.ACCESSORY),
        ))
        val a = PlanAdvisor.advise(input(listOf(upper, lower), aim, week = null))
        val quads = a.volume.first { it.muscle == Muscle.QUADS }
        assertEquals(5.0, quads.now)
        assertEquals(4 to 12, quads.low to quads.high)
        val norm = a.fine.firstOrNull { it.startsWith("Подходов в неделю в норме") }.orEmpty()
        assertTrue(listOf("квадрицепс", "бицепс бедра", "ягодицы").none { it in norm }, norm)
        assertTrue(
            a.held.any { it.startsWith("5–5,5\u00A0подхода в неделю на квадрицепс") && it.endsWith("работают, но для роста мышц это мало.") },
            a.held.toString(),
        )
        assertTrue(a.changes.none { it.id.startsWith("legs:") }, a.changes.map { it.id }.toString())
    }

    /**
     * Denis (the template plan, quads 6, hamstrings 6,5, glutes 6 against 8–12): the leg curl a set more, then the leg
     * extension, then two sets at the end of «Верх · А» after Thursday's hard run — with 2–3 in reserve and no «+» set.
     */
    @Test fun denisIsOfferedTheLegCurlAndExtensionSetsAndTwoSetsAfterThursdaysRun() {
        val a = PlanAdvisor.advise(input(denis()))
        val legs = a.changes.filter { it.id.startsWith("legs:") }
        assertEquals(listOf("legs:HAMSTRINGS", "legs:QUADS", "legs:after-run"), legs.map { it.id }, a.changes.map { it.id }.toString())
        val (curl, ext, extra) = legs
        assertEquals(Kind.SETS, curl.kind)
        assertEquals("lower_a" to "leg_curl", curl.templateId to curl.planId)
        assertEquals(2 to 3, curl.setsFrom to curl.setsTo)
        assertTrue(curl.why.startsWith("6,5\u00A0подхода в неделю на бицепс бедра при норме 8–12"), curl.why)
        assertEquals(Kind.SETS, ext.kind)
        assertEquals(Triple("lower_a", "bulgarian", "leg_ext"), Triple(ext.templateId, ext.planId, ext.from))
        assertEquals(3 to 4, ext.setsFrom to ext.setsTo)
        assertTrue(ext.why.startsWith("6\u00A0подходов в неделю на квадрицепс при норме 8–12"), ext.why)
        assertEquals(Kind.ADD, extra.kind)
        assertEquals("upper_a", extra.templateId)
        // The hamstrings still short after the leg day's set: the curl first, the extension the other option.
        assertEquals(listOf("leg_curl", "leg_ext"), extra.options)
        assertEquals(2, extra.setsTo)
        assertEquals(10 to 15, extra.repsTo)
        assertTrue(extra.why.startsWith("2\u00A0подхода в четверг") && "2–3\u00A0повтора" in extra.why && "без «+»" in extra.why, extra.why)
        // No calf or shin work in any of them.
        assertTrue(legs.flatMap { it.options }.none { Catalog[it].pattern == Pattern.CALF }, legs.map { it.options }.toString())
        // Below the band is not «в норме»; the plan's card says so in one line.
        val norm = a.fine.firstOrNull { it.startsWith("Подходов в неделю в норме") }.orEmpty()
        assertTrue(listOf("квадрицепс", "бицепс бедра", "ягодицы").none { it in norm }, norm)
        val nudge = assertNotNull(a.nudge)
        assertTrue(nudge.startsWith("3\u00A0предложения для ног: 6\u00A0подходов в неделю на квадрицепс при норме 8–12"), nudge)
    }

    /** Taken, the three change their own sets and add the one exercise: the leg day's other exercises, the upper days stay. */
    @Test fun takingThemChangesThoseSetsAndNothingElse() {
        val a = PlanAdvisor.advise(input(denis()))
        val legs = a.changes.filter { it.id.startsWith("legs:") }
        val after = PlanAdvisor.apply(a.days, legs)
        fun day(ds: List<Day>, id: String) = ds.first { it.templateId == id }
        assertEquals(
            day(a.days, "lower_a").items.map { i ->
                when (i.planId) {
                    "leg_curl" -> i.copy(sets = 3)
                    "bulgarian" -> i.copy(sets = 4)
                    else -> i
                }
            },
            day(after, "lower_a").items,
        )
        val upperA = day(after, "upper_a").items
        assertEquals(day(a.days, "upper_a").items, upperA.dropLast(1))
        assertEquals(Item("leg_curl", "leg_curl", 2, 10, 15, 90, SlotRole.ACCESSORY, added = true), upperA.last())
        assertEquals(day(a.days, "upper_b"), day(after, "upper_b"))
        // The hamstrings reach the band (6,5 → 9,5), the quads come nearer (6 → 7), the glutes as they were.
        val t = PlanAdvisor.table(a, after, Catalog::find).associateBy { it.muscle }
        assertEquals(9.5, t.getValue(Muscle.HAMSTRINGS).after)
        assertEquals(7.0, t.getValue(Muscle.QUADS).after)
        assertEquals(6.0, t.getValue(Muscle.GLUTES).after)
    }

    /**
     * His plan as it is now (the leg press the coach added on 7.11, the stiff-legged deadlift at 2 sets, the leg curl at 3):
     * quads 9 and glutes 8 are in the band, hamstrings 6,5 are not — only the curl and the add-on, the curl first.
     */
    @Test fun withTheLegPressAddedOnlyTheHamstringsAreShort() {
        val days = denis().map { d ->
            if (d.templateId != "lower_a") d else d.copy(items = d.items.map { i ->
                when (i.planId) {
                    "rdl" -> i.copy(exerciseId = "sldl", sets = 2)
                    "leg_curl" -> i.copy(sets = 3)
                    else -> i
                }
            } + Item("leg_press", "leg_press", 3, 6, 10, 120, SlotRole.SECONDARY, added = true))
        }
        val a = PlanAdvisor.advise(input(days))
        val legs = a.changes.filter { it.id.startsWith("legs:") }
        assertEquals(listOf("legs:HAMSTRINGS", "legs:after-run"), legs.map { it.id }, a.changes.map { it.id }.toString())
        assertEquals(3 to 4, legs[0].setsFrom to legs[0].setsTo)
        assertEquals("leg_curl", legs[1].to)
    }

    /** Leg work and a hard run within about 48 h stay apart; without the week there is no day after a hard run to put it on. */
    @Test fun noAddOnRightBeforeAHardRunOrWithoutTheWeek() {
        val friday = WeekPlan(denisWeek.days + DayPlan(5, listOf(PlannedSession(SessionKind.RUN_QUALITY))), emptyList())
        assertTrue(PlanAdvisor.advise(input(denis(), week = friday)).changes.none { it.id == "legs:after-run" })
        val none = PlanAdvisor.advise(input(denis(), week = null)).changes.map { it.id }
        assertTrue("legs:after-run" !in none && "legs:HAMSTRINGS" in none, none.toString())
    }

    /** Far over the band: growth is small while fatigue builds — not «мышцы уже не растут»; an accent may go higher. */
    @Test fun tooManyLegSetsSayGrowthIsSmallAndAnAccentMayGoHigher() {
        // Quads 4 + 4 + 4 + 4 = 16 against 8–12.
        val day = Day("lower", "Низ", listOf(
            Item("squat", "squat", 4, 5, 8, 180, SlotRole.MAIN),
            Item("hack_squat", "hack_squat", 4, 8, 10, 120, SlotRole.SECONDARY),
            Item("leg_press", "leg_press", 4, 8, 10, 120, SlotRole.SECONDARY),
            Item("leg_ext", "leg_ext", 4, 10, 15, 90, SlotRole.ACCESSORY),
        ))
        val a = PlanAdvisor.advise(input(listOf(day), week = null))
        val less = a.changes.first { it.id == "less:QUADS:leg_ext" }
        assertTrue("растут уже мало, а усталость копится" in less.why && "уже не растут" !in less.why, less.why)
        val accent = PlanAdvisor.advise(input(listOf(day), week = null, accents = setOf(Muscle.QUADS)))
        assertTrue(accent.changes.none { it.id.startsWith("less:QUADS") }, accent.changes.map { it.id }.toString())
    }

    /** «Мышцы за 7 дней»: the week's leg sets against the same band, in one line. */
    @Test fun theWeeksLegSetsAreSaidAgainstTheBand() {
        val size = PlanAdvisor.ranges(hybrid, Experience.ADVANCED, 3, emptySet())
        val span = "за 7\u00A0дней"
        assertEquals(
            "6\u00A0подходов на квадрицепс за 7\u00A0дней при норме 8–12.",
            PlanAdvisor.legsLine(mapOf(Muscle.QUADS to 6.0, Muscle.HAMSTRINGS to 6.5, Muscle.GLUTES to 6.0, Muscle.CHEST to 9.0), size, span),
        )
        // A missed leg day: none at all.
        assertEquals("0\u00A0подходов на ноги за 7\u00A0дней при норме 8–12.", PlanAdvisor.legsLine(mapOf(Muscle.CHEST to 9.0), size, span))
        assertEquals(
            "8–10\u00A0подходов на ноги за 7\u00A0дней при норме 8–12.",
            PlanAdvisor.legsLine(mapOf(Muscle.QUADS to 10.0, Muscle.HAMSTRINGS to 8.0, Muscle.GLUTES to 9.0), size, span),
        )
        // A runner without a size aim: 5 sets work, low for growth.
        val other = PlanAdvisor.ranges(Aim(LiftFocus.STRENGTH, RunFocus.ENDURANCE, Emphasis.BALANCED), Experience.ADVANCED, 3, emptySet())
        assertEquals(
            "5\u00A0подходов на квадрицепс за 7\u00A0дней работают, но для роста мышц это мало.",
            PlanAdvisor.legsLine(mapOf(Muscle.QUADS to 5.0, Muscle.HAMSTRINGS to 5.5, Muscle.GLUTES to 5.0), other, span),
        )
    }
}
