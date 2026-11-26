package forma.core

import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.plan.CatchUp
import forma.core.plan.DayPlan
import forma.core.plan.DayReasons
import forma.core.plan.DaySwap
import forma.core.plan.DaySwaps
import forma.core.plan.Focus
import forma.core.plan.PlannedSession
import forma.core.plan.Program
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.plan.Template
import forma.core.plan.WeekPlan
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis, 26 November: «например я пропустил день ног и он не скоро, смогу ли я попросить тренера поставить мне сегодня
 * день ног с бегом, а сегодняшний верх перенести на субботу». Two days of this week change places, whole: every session
 * of one is on the other and back; from the next week the days are as planned. His week: a hard run on Monday and
 * Thursday, «Верх · Б» on Tuesday, «Верх · А» on Thursday, «Низ» and an easy run on Saturday.
 */
class DaySwapsTest {
    private val quality = PlannedSession(SessionKind.RUN_QUALITY)
    private val easy = PlannedSession(SessionKind.RUN_EASY)
    private val upperB = PlannedSession(SessionKind.LIFT, "upper_b")
    private val upperA = PlannedSession(SessionKind.LIFT, "upper_a")
    private val lower = PlannedSession(SessionKind.LIFT, "lower_a")
    private val his = mapOf(1 to listOf(quality), 2 to listOf(upperB), 4 to listOf(quality, upperA), 6 to listOf(lower, easy))

    private val monday = LocalDate.of(2026, 11, 23)
    private val tuesday = monday.plusDays(1)
    private val wednesday = monday.plusDays(2)
    private val thursday = monday.plusDays(3)
    private val friday = monday.plusDays(4)
    private val saturday = monday.plusDays(5)
    private val sunday = monday.plusDays(6)
    private val week = monday.toEpochDay()
    private val thuSat = DaySwap(week, 4, 6)

    private fun program(plan: Map<Int, List<PlannedSession>> = his): Program = ProgramGenerator.generate(
        Profile(
            goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
            bodyWeightKg = 71.0, aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
        ),
        monday.minusWeeks(1),
    ).let { p ->
        p.copy(
            week = WeekPlan(plan.map { (d, s) -> DayPlan(d, s) }, p.week.notes),
            templates = listOf(
                Template("upper_a", "Верх · А", Focus.UPPER, emptyList()), Template("upper_b", "Верх · Б", Focus.UPPER, emptyList()),
                Template("lower_a", "Низ", Focus.LOWER, emptyList()),
            ),
        )
    }

    private fun refusal(
        a: Int, b: Int, today: LocalDate = thursday, swaps: List<DaySwap> = emptyList(), p: Program = program(),
        day: (LocalDate) -> DaySwaps.Day = { DaySwaps.Day() },
    ) = DaySwaps.refusal(p, swaps, DaySwap(week, a, b), today, day)

    @Test fun thursdayAndSaturdayChangePlacesThisWeekOnly() {
        val p = program()
        val swaps = listOf(thuSat)
        // The whole day moves, in its own order: the gym first on the leg day, the hard run first on the upper one.
        assertEquals(listOf(lower, easy), p.on(thursday, swaps))
        assertEquals(listOf(quality, upperA), p.on(saturday, swaps))
        assertEquals(listOf(quality), p.on(monday, swaps))
        assertEquals(listOf(upperB), p.on(tuesday, swaps))
        assertTrue(p.on(friday, swaps).isEmpty())
        // The plan's weekday whose sessions a date holds: the per-weekday settings follow the session.
        assertEquals(6, DaySwaps.origin(thursday, swaps))
        assertEquals(4, DaySwaps.origin(saturday, swaps))
        assertEquals(1, DaySwaps.origin(monday, swaps))
        // The week before and the week after are as planned.
        for (other in listOf(-1L, 1L)) {
            assertEquals(listOf(quality, upperA), p.on(thursday.plusWeeks(other), swaps))
            assertEquals(listOf(lower, easy), p.on(saturday.plusWeeks(other), swaps))
            assertEquals(4, DaySwaps.origin(thursday.plusWeeks(other), swaps))
        }
        // Without a swap: the template.
        assertEquals(listOf(quality, upperA), p.on(thursday, emptyList()))
    }

    @Test fun theWeeksOwnProgramHoldsTheSwappedLayout() {
        val p = program()
        val now = p.forWeek(monday, listOf(thuSat))
        assertEquals(listOf(lower, easy), now.week.on(4))
        assertEquals(listOf(quality, upperA), now.week.on(6))
        assertEquals(listOf(quality), now.week.on(1))
        assertEquals(p.templates, now.templates)
        // Another week, or no swap: the plan as it is.
        assertEquals(p, p.forWeek(monday.plusWeeks(1), listOf(thuSat)))
        assertEquals(p, p.forWeek(monday, emptyList()))
    }

    @Test fun severalSwapsOfAWeekApplyInOrder() {
        val p = program()
        // Thursday ↔ Saturday, then Saturday ↔ Sunday: Thursday's day ends on Sunday.
        val swaps = listOf(thuSat, DaySwap(week, 6, 7))
        assertEquals(listOf(lower, easy), p.on(thursday, swaps))
        assertTrue(p.on(saturday, swaps).isEmpty())
        assertEquals(listOf(quality, upperA), p.on(sunday, swaps))
        assertEquals(4, DaySwaps.origin(sunday, swaps))
        assertEquals(listOf(thuSat, DaySwap(week, 6, 7)), DaySwaps.of(friday, swaps))
        assertTrue(DaySwaps.of(friday.plusWeeks(1), swaps).isEmpty())
        // An entry that names no day (a damaged document) moves nothing.
        assertEquals(listOf(quality, upperA), p.on(thursday, listOf(DaySwap(week, 4, 9), DaySwap(week, 0, 6))))
    }

    @Test fun aNewSwapKeepsThisWeekAndTheNextAndASwapMadeTwiceIsNone() {
        val old = DaySwap(monday.minusWeeks(1).toEpochDay(), 2, 3)
        val next = DaySwap(monday.plusWeeks(1).toEpochDay(), 1, 3)
        val far = DaySwap(monday.plusWeeks(2).toEpochDay(), 1, 3)
        assertEquals(listOf(next, thuSat), DaySwaps.with(listOf(old, next, far), thuSat, thursday))
        // The same two days again: the week is as planned, and nothing is kept of it.
        assertEquals(listOf(next), DaySwaps.with(listOf(next, thuSat), DaySwap(week, 6, 4), thursday))
        // Taken back: the week's last one only.
        assertEquals(listOf(next), DaySwaps.without(listOf(next, thuSat), thuSat))
        assertNull(DaySwaps.without(listOf(thuSat, DaySwap(week, 6, 7)), thuSat), "another swap of the week was made after it")
        assertNull(DaySwaps.without(emptyList(), thuSat))
    }

    @Test fun thursdayAndSaturdayAreAllowed() {
        assertNull(refusal(4, 6))
        assertNull(refusal(6, 4, today = wednesday))
        // A rest day takes a day's sessions («перенеси сегодняшнее на завтра»): Tuesday's upper body on Wednesday.
        assertNull(refusal(2, 3, today = tuesday))
    }

    @Test fun aDayOverOrOfAnotherKindIsRefused() {
        assertTrue(assertNotNull(refusal(2, 6)).startsWith("Вторник этой недели уже позади"), "Tuesday is over on Thursday")
        // On Sunday no other day of the week is left: «на завтра» is the next week's Monday.
        assertTrue(assertNotNull(refusal(1, 7, today = sunday)).startsWith("Понедельник этой недели уже позади"))
        // A swap of another week than this one is not made.
        assertNotNull(DaySwaps.refusal(program(), emptyList(), DaySwap(monday.plusWeeks(1).toEpochDay(), 4, 6), thursday))
        assertNotNull(refusal(4, 4), "one day")
        assertNotNull(refusal(0, 6), "no such day")
        assertNotNull(refusal(4, 8), "no such day")
        // The same plan on both days: nothing to change (two rest days; two days of the same session).
        assertTrue("одно и то же" in assertNotNull(refusal(5, 7)))
        val twice = program(his + (5 to listOf(quality, upperA)))
        assertTrue("одно и то же" in assertNotNull(refusal(4, 5, p = twice)))
    }

    @Test fun aDayPausedSickBegunOrHeldStays() {
        fun on(date: LocalDate, d: DaySwaps.Day): (LocalDate) -> DaySwaps.Day = { if (it == date) d else DaySwaps.Day() }
        assertTrue("пауза" in assertNotNull(refusal(4, 6, day = on(saturday, DaySwaps.Day(paused = true)))))
        assertTrue("Болею" in assertNotNull(refusal(4, 6, day = on(thursday, DaySwaps.Day(sick = true)))))
        val begun = assertNotNull(refusal(4, 6, day = on(thursday, DaySwaps.Day(begun = true))))
        assertTrue(begun.startsWith("Сегодня") && "уже" in begun, begun)
        assertTrue("В субботу" in assertNotNull(refusal(4, 6, day = on(saturday, DaySwaps.Day(begun = true)))))
        // A time trial due or the race: the day is not moved.
        val trial = assertNotNull(refusal(4, 6, day = on(thursday, DaySwaps.Day(held = "контрольный забег"))))
        assertTrue("контрольный забег" in trial, trial)
        // Paused or held on a day the swap does not touch: no matter.
        assertNull(refusal(4, 6, day = on(friday, DaySwaps.Day(begun = true, held = "забег", sick = true))))
    }

    @Test fun legsNeverLandNextToAHardRunOrOtherLegs() {
        // Saturday ↔ Sunday: «Низ» on Sunday, the hard run on next week's Monday.
        val sun = assertNotNull(refusal(6, 7))
        assertTrue("ноги в воскресенье" in sun && "тяжёлый бег в понедельник" in sun, sun)
        // Today's hard run and upper body to Friday: the hard run the day before Saturday's legs.
        val fri = assertNotNull(refusal(4, 5))
        assertTrue("тяжёлый бег в пятницу" in fri && "ноги в субботу" in fri, fri)
        // Two hard runs in a row: Monday's hard run on Wednesday, the day before Thursday's.
        val hard = assertNotNull(refusal(1, 3, today = monday))
        assertTrue("тяжёлый бег два дня подряд" in hard, hard)
        // Legs on two days in a row: a second leg day on Thursday, Saturday's moved to Friday.
        val legs = program(mapOf(1 to listOf(quality), 2 to listOf(upperB), 4 to listOf(lower), 6 to listOf(lower, easy)))
        val two = assertNotNull(refusal(5, 6, today = thursday, p = legs))
        assertTrue("ноги два дня подряд" in two, two)
    }

    @Test fun theDaysAroundAreReadAsTheyStand() {
        // Next week has its own swap: its Monday holds no hard run, so Sunday's «Низ» is next to none.
        val nextWeek = DaySwap(monday.plusWeeks(1).toEpochDay(), 1, 3)
        assertNull(refusal(6, 7, swaps = listOf(nextWeek)))
        // The app says how a day stands (an eased hard run is an easy one, a pause day holds nothing).
        val p = program()
        val eased = DaySwaps.refusal(p, emptyList(), DaySwap(week, 6, 7), thursday, { DaySwaps.Day() }) { d, s ->
            p.on(d, s).map { if (it.kind == SessionKind.RUN_QUALITY && d == monday.plusWeeks(1)) easy else it }
        }
        assertNull(eased)
        // A day already over counts by what was done on it, not by its plan: legs done yesterday, a hard run moved to today.
        val done = refusal(4, 6, today = thursday, p = program(his + (4 to listOf(easy)) + (6 to listOf(quality)))) {
            if (it == wednesday) DaySwaps.Day(done = listOf(lower)) else DaySwaps.Day()
        }
        assertTrue("ноги в среду" in assertNotNull(done), done)
        // Planned yesterday and not done: no legs there.
        assertNull(refusal(4, 6, today = thursday, p = program(his + (3 to listOf(lower)) + (4 to listOf(easy)) + (6 to listOf(quality)))))
    }

    @Test fun theWordsOfASwap() {
        val p = program()
        assertEquals("«Низ» и лёгкий бег", DaySwaps.words(p, p.on(thursday, listOf(thuSat))))
        assertEquals("тяжёлый бег и «Верх · А»", DaySwaps.words(p, p.on(saturday, listOf(thuSat))))
        assertEquals("отдых", DaySwaps.words(p, emptyList()))
        assertEquals("четверг и суббота", DaySwaps.names(listOf(thuSat)))
        assertEquals("четверг и суббота, потом суббота и воскресенье", DaySwaps.names(listOf(thuSat, DaySwap(week, 6, 7))))
    }

    @Test fun theDaysReasonsFollowTheSwappedWeek() {
        val p = program()
        val swaps = listOf(thuSat)
        // Thursday holds the leg day with its easy run; the reasons are of what the day holds now.
        val thu = DayReasons.of(p, thursday, swaps)
        assertTrue(thu.any { "Лёгкий бег — в день ног" in it }, thu.toString())
        val sat = DayReasons.of(p, saturday, swaps)
        assertTrue(sat.any { "Тяжёлый бег — в день верха тела" in it }, sat.toString())
        // No leg day before Saturday's hard run now (Friday rests), none after it (Sunday rests).
        assertTrue(sat.any { "Завтра отдых" in it }, sat.toString())
        // Without swaps: as by the weekday.
        assertEquals(DayReasons.of(p, 4), DayReasons.of(p, thursday, emptyList()))
        assertEquals(DayReasons.of(p, 1), DayReasons.of(p, monday, emptyList()))
        // Sunday looks ahead into next week, which has its own days: a leg day on Sunday is before next Monday's hard run
        // as planned, whatever this week's Monday held (here: nothing, its hard run went to Wednesday).
        val sun = DayReasons.of(program(his + (7 to listOf(lower))), sunday, listOf(DaySwap(week, 1, 3)))
        assertTrue(sun.any { "Завтра тяжёлый бег" in it }, sun.toString())
    }

    @Test fun legsAreWhatTheCatchUpCallsLegs() {
        val p = program()
        assertTrue(CatchUp.legs(p, lower))
        assertTrue(!CatchUp.legs(p, upperA) && !CatchUp.legs(p, easy))
        // A template the plan no longer has counts as legs; a friend's copied workout by its exercises.
        assertTrue(CatchUp.legs(p, PlannedSession(SessionKind.LIFT, "gone")))
        assertTrue(!CatchUp.legs(p, PlannedSession(SessionKind.LIFT, "gone"), own = false))
    }
}
