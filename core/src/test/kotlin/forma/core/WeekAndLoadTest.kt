package forma.core

import forma.core.analytics.SetRow
import forma.core.analytics.Stats
import forma.core.load.LoadExplain
import forma.core.load.LoadStory
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Muscle
import forma.core.model.RunFocus
import forma.core.plan.CatchUp
import forma.core.plan.DayPlan
import forma.core.plan.DayReasons
import forma.core.plan.Focus
import forma.core.plan.PlannedSession
import forma.core.plan.Program
import forma.core.plan.SessionKind
import forma.core.plan.Template
import forma.core.plan.WeekPlan
import forma.core.run.RunCaps
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The week as done (caught up, another session, a friend's workout), the reasons of a day, the load of a week under way. */
class WeekAndLoadTest {
    private val a = Template("a", "Силовая А", Focus.FULL, emptyList())
    private val b = Template("b", "Силовая Б", Focus.UPPER, emptyList())
    private val liftA = PlannedSession(SessionKind.LIFT, "a")
    private val liftB = PlannedSession(SessionKind.LIFT, "b")
    private val copy = PlannedSession(SessionKind.LIFT, "copy")
    private val easy = PlannedSession(SessionKind.RUN_EASY)
    private val quality = PlannedSession(SessionKind.RUN_QUALITY)
    private val long = PlannedSession(SessionKind.RUN_LONG)
    private val hybrid = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED)

    private fun program(aim: Aim = hybrid, vararg days: Pair<Int, List<PlannedSession>>) = Program(
        aim.nearestGoal(), Experience.INTERMEDIATE, listOf(a, b),
        WeekPlan(days.map { DayPlan(it.first, it.second) }, emptyList()), 0, RunCaps(45, 90), 0, aim,
    )

    private fun week(p: Program, done: Map<Int, List<PlannedSession>>, legs: Map<Int, List<Boolean?>> = emptyMap()) =
        (1..7).map { d -> CatchUp.Day(d, p.week.on(d), done[d].orEmpty(), legs[d].orEmpty()) }

    /** Denis's week: hard run Monday, the gym Tuesday/Thursday/Saturday with runs Thursday and Saturday. */
    private val denis = program(
        hybrid, 1 to listOf(quality), 2 to listOf(liftA), 4 to listOf(quality, liftB), 6 to listOf(liftA, long),
    )

    @Test fun aLiftOfAnotherTemplateOnItsDayIsTheDaysLift() {
        // Tuesday: "another lift" — B instead of A. The day is done; Wednesday nothing is missed.
        val w = week(denis, mapOf(1 to listOf(quality), 2 to listOf(liftB)))
        assertTrue(CatchUp.closes(w).any { it.day == 2 && it.doneDay == 2 })
        assertNull(CatchUp.suggest(denis, 3, w))
    }

    @Test fun aCaughtUpLiftClosesTheMissedOneOfItsWeek() {
        // Tuesday missed, Wednesday done as a catch-up (the same template): Tuesday is closed by Wednesday.
        val w = week(denis, mapOf(1 to listOf(quality), 3 to listOf(liftA)))
        assertEquals(listOf(CatchUp.Close(2, 0, 3, 0)), CatchUp.closes(w).filter { it.day == 2 })
        // A friend's copied workout counts as the gym done too.
        val c = week(denis, mapOf(1 to listOf(quality), 3 to listOf(copy)), mapOf(3 to listOf(false)))
        assertEquals(listOf(CatchUp.Close(2, 0, 3, 0)), CatchUp.closes(c).filter { it.day == 2 })
        assertNotEquals(2, CatchUp.suggest(denis, 5, c)?.plannedOn)
    }

    @Test fun aSessionDoneAheadDoesNotCloseItsDay() {
        // Wednesday an extra lift: Thursday's gym is still to do.
        val w = week(denis, mapOf(1 to listOf(quality), 2 to listOf(liftA), 3 to listOf(liftA)))
        assertTrue(CatchUp.closes(w).none { it.day == 4 })
    }

    @Test fun aCopiedWorkoutsLegsAreItsOwn() {
        // Missed Monday's intervals; Tuesday a friend's upper-body workout: the intervals fit on Wednesday.
        val p = program(hybrid, 1 to listOf(quality), 6 to listOf(long))
        val upper = week(p, mapOf(2 to listOf(copy)), mapOf(2 to listOf(false)))
        assertEquals(CatchUp.Suggestion(quality, 1), CatchUp.suggest(p, 3, upper))
        // With legs in it they do not.
        val legs = week(p, mapOf(2 to listOf(copy)), mapOf(2 to listOf(true)))
        assertNull(CatchUp.suggest(p, 3, legs))
    }

    @Test fun aHardRunCaughtUpClosesAMissedEasyOne() {
        val p = program(hybrid, 1 to listOf(easy), 6 to listOf(long))
        val w = week(p, mapOf(3 to listOf(quality)))
        assertEquals(listOf(CatchUp.Close(1, 0, 3, 0)), CatchUp.closes(w))
    }

    @Test fun noRunWordsWithoutRunningAndNoGymWordsWithoutTheGym() {
        val gym = program(Aim(LiftFocus.MUSCLE, RunFocus.NONE, Emphasis.GYM), 1 to listOf(liftA), 3 to listOf(liftB), 5 to listOf(liftA))
        (1..7).flatMap { DayReasons.of(gym, it) }.forEach { assertFalse("бег" in it.lowercase(), it) }
        val run = program(Aim(LiftFocus.NONE, RunFocus.ENDURANCE, Emphasis.RUN), 1 to listOf(quality), 3 to listOf(easy), 6 to listOf(long))
        val lines = (1..7).flatMap { DayReasons.of(run, it) }
        assertTrue(lines.isNotEmpty())
        lines.forEach { assertFalse("зал" in it.lowercase() || "ног:" in it || "ноги —" in it.lowercase(), it) }
    }

    /** A run-only week with two hard days in a row: neither says "the day before and after — no hard running". */
    @Test fun twoHardDaysInARowAreNotCalledApart() {
        val speed = Aim(LiftFocus.NONE, RunFocus.SPEED, Emphasis.RUN)
        val run = program(speed, 6 to listOf(quality), 7 to listOf(quality))
        val sat = DayReasons.of(run, 6)
        val sun = DayReasons.of(run, 7)
        (sat + sun).forEach { assertFalse("накануне и назавтра" in it, it) }
        assertTrue(sat.any { "накануне — без тяжёлого бега" in it }, sat.toString())
        assertTrue(sun.any { "Накануне тоже тяжёлый бег" in it }, sun.toString())
        // Days apart: said as before.
        val apart = program(speed, 2 to listOf(quality), 6 to listOf(quality))
        assertTrue(DayReasons.of(apart, 2).any { "накануне и назавтра — без тяжёлого бега" in it })
    }

    // ---------- load ----------

    /** Someone who trains at weekends, on a Thursday after an extra session: the week is under way, not "only just begun". */
    @Test fun aWeekendTrainersThursdayIsNotTheStartOfTheWeek() {
        val loads = mutableMapOf<LocalDate, Double>()
        repeat(6) { w -> loads[start.plusDays(w * 7L + 5)] = 400.0; loads[start.plusDays(w * 7L + 6)] = 400.0 }
        val thursday = start.plusWeeks(6).plusDays(3)
        loads[thursday.minusDays(2)] = 300.0
        val w = LoadStory.of(loads, thursday).weeks.last()
        assertEquals(LoadStory.Level.ONGOING, w.level)
        val note = LoadExplain.levelNote(w)!!
        assertFalse("только началась" in note, note)
        assertTrue("обычно тренируешься" in note, note)
    }

    private val start = LocalDate.of(2026, 7, 6)

    /** Denis's usual week: Monday 300, Tuesday 400, Thursday 500, Saturday 500. */
    private fun denisLoads(weeks: Int) = buildMap {
        repeat(weeks) { w -> listOf(0 to 300.0, 1 to 400.0, 3 to 500.0, 5 to 500.0).forEach { (d, l) -> put(start.plusDays(w * 7L + d), l) } }
    }

    @Test fun theWeekUnderWayIsSetAgainstTheSameDaysOfUsualOnes() {
        val loads = denisLoads(10).toMutableMap()
        val monday = start.plusWeeks(10)
        // Monday morning: nothing yet — the week is under way, not "light".
        val mon = LoadStory.of(loads, monday).weeks.last()
        assertEquals(LoadStory.Level.ONGOING, mon.level)
        assertEquals("неделя идёт", LoadExplain.level(mon))
        // Monday evening and Tuesday morning: Monday's session as usual.
        loads[monday] = 300.0
        assertEquals(LoadStory.Level.USUAL, LoadStory.of(loads, monday).weeks.last().level)
        assertEquals(LoadStory.Level.USUAL, LoadStory.of(loads, monday.plusDays(1)).weeks.last().level)
        // Tuesday evening: no spike for two sessions in two days.
        loads[monday.plusDays(1)] = 400.0
        val tue = LoadStory.of(loads, monday.plusDays(1)).weeks.last()
        assertEquals(LoadStory.Level.USUAL, tue.level, tue.toString())
        assertTrue(LoadExplain.week(tue).contains("к этому дню"))
        // Thursday morning, Thursday's session still ahead: usual, not light.
        assertEquals(LoadStory.Level.USUAL, LoadStory.of(loads, monday.plusDays(3)).weeks.last().level)
        // Thursday evening with a double Thursday: more than usual so far.
        loads[monday.plusDays(3)] = 1500.0
        assertEquals(LoadStory.Level.SPIKE, LoadStory.of(loads, monday.plusDays(3)).weeks.last().level)
    }

    @Test fun fatigueWordsNeverContradictTheZone() {
        // A week a little heavier than usual (freshness about −7 %): balance, and the words say "as usual".
        val loads = denisLoads(10).toMutableMap()
        val monday = start.plusWeeks(10)
        listOf(0 to 330.0, 1 to 440.0, 3 to 550.0).forEach { (d, l) -> loads[monday.plusDays(d.toLong())] = l }
        for (d in 0..6) {
            val s = LoadStory.of(loads, monday.plusDays(d.toLong()))
            val text = LoadExplain.fatigue(s)
            when (s.zone) {
                LoadStory.Zone.BALANCE -> assertTrue("примерно равна" in text, "$d ${s.today.formPct} $text")
                LoadStory.Zone.BUILDING, LoadStory.Zone.REST -> assertTrue("выше" in text, text)
                else -> assertTrue("ниже" in text, text)
            }
        }
        assertTrue(LoadExplain.fitness(LoadStory.of(loads, monday)).startsWith("Тренированность"))
    }

    @Test fun aDipStartsTheMorningAfterTheHardSession() {
        // Steady weeks with one very hard Thursday: the dip is of the mornings after it, not of Thursday's own evening.
        val loads = denisLoads(10).toMutableMap()
        val thursday = start.plusWeeks(9).plusDays(3)
        loads[thursday] = 1400.0
        val s = LoadStory.of(loads, start.plusWeeks(10).plusDays(2))
        s.dips.forEach { assertNotEquals(thursday, it.from, s.dips.toString()) }
        assertTrue(s.dips.any { it.from == thursday.plusDays(1) }, s.dips.toString())
    }

    // ---------- strength ----------

    private val day0 = LocalDate.of(2026, 9, 7).toEpochDay()
    private fun row(w: Long, d: Long, ex: String, kg: Double, reps: Int) = SetRow(w, day0 + d, ex, kg, reps, null, reps)

    @Test fun setsPerMuscleAreOfTheLastSevenDays() {
        // Bench on Saturday; on Monday the week's card still holds it.
        val rows = List(3) { row(1, 5, "bench", 60.0, 8) }
        val monday = LocalDate.ofEpochDay(day0 + 7)
        assertEquals(3.0, Stats.setsLast7Days(rows, monday)[Muscle.CHEST])
        assertEquals(0.0, Stats.setsLast7Days(rows, monday.plusDays(6))[Muscle.CHEST])
    }

    @Test fun anAddedExerciseDoesNotMoveTheStrengthIndex() {
        // Squat and bench grow for four weeks; in week 3 two new exercises come in at their own start.
        val base = (0 until 4).flatMap { i ->
            listOf(row(i.toLong(), i * 7L, "squat", 60.0 + 5 * i, 8), row(i.toLong(), i * 7L, "bench", 50.0 + 2.5 * i, 8))
        }
        val added = (2 until 4).flatMap { i ->
            listOf(row(i.toLong(), i * 7L, "deadlift", 100.0, 5), row(i.toLong(), i * 7L, "ohp", 40.0, 8))
        }
        val alone = Stats.strengthIndex(base) { 80.0 }
        val withNew = Stats.strengthIndex(base + added) { 80.0 }
        // Up to week 3 the same; after it the new ones (unchanged) only slow the growth, never drop the index.
        assertEquals(alone[2].value, withNew[2].value, 1e-9)
        assertTrue(withNew.zipWithNext().all { (x, y) -> y.value >= x.value - 1e-9 }, withNew.toString())
    }
}
