package forma.core

import forma.core.analytics.Point
import forma.core.model.Experience
import forma.core.model.LiftGoal
import forma.core.model.RunGoal
import forma.core.plan.GoalRisk
import forma.core.strength.GymGoal
import forma.core.strength.OneRm
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The way to a goal in the gym, and what changes cost the goals. */
class GymGoalTest {
    private val today = 20_000L

    /** Sessions every 4 days for [weeks] weeks up to today, the max growing [weekly] a week from [start]. */
    private fun series(start: Double, weekly: Double, weeks: Int): List<Point> =
        (0..weeks * 7 step 4).map { d -> Point(today - weeks * 7 + d, start * (1 + weekly).pow(d / 7.0)) }

    @Test fun aGoalOfSeveralRepsIsItsOneRepMax() {
        assertEquals(OneRm.estimate(100.0, 5), GymGoal.target(LiftGoal("bench", 100.0, 5)), 1e-9)
        assertEquals(150.0, GymGoal.target(LiftGoal("bench", 150.0)), 1e-9)
    }

    @Test fun withNoSessionTheWayIsShownAfterTheFirst() {
        val p = GymGoal.path(LiftGoal("bench", 150.0), emptyList(), Experience.INTERMEDIATE, today)
        assertEquals(GymGoal.Verdict.NO_DATA, p.verdict)
        assertTrue("после первой тренировки" in GymGoal.words(LiftGoal("bench", 150.0), p, "Жим", 2.5))
    }

    @Test fun ownGrowthSaysWhenTheGoalComes() {
        val s = series(100.0, 0.01, 6)
        val now = s.last().value
        val goal = LiftGoal("chest_press", Math.round(now * 1.1).toDouble())
        val p = GymGoal.path(goal, s, Experience.ADVANCED, today)
        assertTrue(p.personal)
        assertEquals(0.01, p.weekly, 0.001)
        val weeks = assertNotNull(p.weeks)
        assertTrue(weeks in 8..12, "$weeks")
        assertEquals(GymGoal.Verdict.ON_TRACK, p.verdict)
        val words = GymGoal.words(goal, p, "Жим в тренажёре", 2.5)
        assertTrue("По твоим тренировкам" in words && "через" in words, words)
    }

    @Test fun noGrowthIsSaidAndTheUsualRateEstimates() {
        val flat = (0..8).map { Point(today - 40 + it * 5, 120.0) }
        val p = GymGoal.path(LiftGoal("bench", 130.0), flat, Experience.INTERMEDIATE, today)
        assertTrue(p.stalled)
        assertTrue(!p.personal)
        assertEquals(GymGoal.usualRate(Experience.INTERMEDIATE), p.weekly, 1e-9)
        assertTrue("без роста" in GymGoal.words(LiftGoal("bench", 130.0), p, "Жим", 2.5))
    }

    @Test fun aDateIsOnTrackAmbitiousOrUnlikely() {
        val s = series(120.0, 0.005, 6)
        val now = s.last().value
        fun verdict(target: Double, weeks: Int) = GymGoal.path(LiftGoal("bench", target, date = today + weeks * 7), s, Experience.ADVANCED, today)
        assertEquals(GymGoal.Verdict.ON_TRACK, verdict(now * 1.04, 10).verdict)
        assertEquals(GymGoal.Verdict.AMBITIOUS, verdict(now * 1.07, 10).verdict)
        val far = verdict(now * 1.25, 10)
        assertEquals(GymGoal.Verdict.UNLIKELY, far.verdict)
        // Said with what is real by the date.
        val words = GymGoal.words(LiftGoal("bench", now * 1.25, date = today + 70), far, "Жим", 2.5)
        assertTrue("вряд ли" in words && "реально к этому дню" in words, words)
    }

    @Test fun aFarGoalGetsANearerOne() {
        val s = series(100.0, 0.005, 6)
        val p = GymGoal.path(LiftGoal("bench", 150.0), s, Experience.ADVANCED, today)
        val near = assertNotNull(p.nearer)
        assertTrue(near > s.last().value && near < 110, "$near")
        assertTrue("Ближняя цель" in GymGoal.words(LiftGoal("bench", 150.0), p, "Жим", 2.5))
    }

    @Test fun aGoalBelowTheMaxIsTaken() {
        val p = GymGoal.path(LiftGoal("bench", 100.0), listOf(Point(today - 3, 104.0)), Experience.NOVICE, today)
        assertEquals(GymGoal.Verdict.DONE, p.verdict)
    }

    @Test fun theTestDayWarmsUpToAnOpenerThenTheGoal() {
        val day = GymGoal.testDay(125.0, 150.0, 2.5)
        assertEquals(listOf(62.5 to 5, 87.5 to 3, 100.0 to 2, 112.5 to 1), day.warmups)
        // The goal is far past the estimate: an opener under it and a little over it, no miss at 150.
        assertEquals(listOf(117.5, 127.5), day.attempts)
        // A goal under the estimate: the goal itself is the one attempt.
        val near = GymGoal.testDay(125.0, 115.0, 2.5)
        assertEquals(listOf(115.0), near.attempts)
        assertTrue((near.warmups.map { it.first } + near.attempts).zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun theTaperIsTheDaysBeforeTheDate() {
        val g = LiftGoal("bench", 150.0, date = today)
        assertTrue(GymGoal.tapering(g, today - 1))
        assertTrue(GymGoal.tapering(g, today - GymGoal.TAPER_DAYS))
        assertTrue(!GymGoal.tapering(g, today))
        assertTrue(!GymGoal.tapering(g, today - GymGoal.TAPER_DAYS - 1))
        assertEquals(2, GymGoal.taperSets(4))
        assertEquals(2, GymGoal.taperSets(3))
        assertEquals(3, GymGoal.taperSets(5))
    }

    // ---------- what changes cost ----------

    private val race = RunGoal(1500, 330)
    private val press = LiftGoal("chest_press", 150.0)

    @Test fun aWeekWithNoRunningForARunGoalIsSaidWithTheWayToKeepIt() {
        val r = assertNotNull(GoalRisk.week(race, null, null, runs = 0, lifts = 3))
        assertTrue("останется без бега" in r && "2 пробежки" in r, r)
        assertTrue("Одна пробежка" in assertNotNull(GoalRisk.week(race, null, null, runs = 1, lifts = 3)))
        assertNull(GoalRisk.week(race, null, null, runs = 2, lifts = 3))
        assertNull(GoalRisk.week(null, null, null, runs = 0, lifts = 3))
    }

    @Test fun aWeekWithNoGymOrNoGoalLiftForAGymGoalIsSaid() {
        val r = assertNotNull(GoalRisk.week(null, press, "Жим в тренажёре", runs = 3, lifts = 0))
        assertTrue("одна силовая в неделю" in r, r)
        assertNotNull(GoalRisk.week(null, press, "Жим в тренажёре", runs = 3, lifts = 2, goalLiftDays = 0))
        assertNull(GoalRisk.week(null, press, "Жим в тренажёре", runs = 3, lifts = 2, goalLiftDays = 1))
    }

    @Test fun theGoalLiftTakenOutCutOrPausedIsSaid() {
        assertNotNull(GoalRisk.liftGone(press, "Жим в тренажёре", left = 0))
        assertNull(GoalRisk.liftGone(press, "Жим в тренажёре", left = 1))
        assertTrue("Жим лёжа" in assertNotNull(GoalRisk.liftGone(press, "Жим в тренажёре", left = 0, to = "Жим лёжа")))
        assertNotNull(GoalRisk.liftCut(press, "Жим в тренажёре", from = 4, to = 2))
        assertNull(GoalRisk.liftCut(press, "Жим в тренажёре", from = 4, to = 5))
        assertNotNull(GoalRisk.liftReps(press, "Жим в тренажёре", 8))
        assertNull(GoalRisk.liftReps(press, "Жим в тренажёре", 5))
        assertNotNull(GoalRisk.liftRest(press, "Жим в тренажёре", 90))
        assertNull(GoalRisk.liftRest(press, "Жим в тренажёре", 150))
        assertTrue("14 %" in assertNotNull(GoalRisk.liftDown(press, "Жим в тренажёре", 100.0, 86.0)))
        assertNull(GoalRisk.liftDown(press, "Жим в тренажёре", 100.0, 95.0))
        assertNotNull(GoalRisk.pause(14, race, 30, null, null))
        assertNull(GoalRisk.pause(14, race, 120, null, null))
        assertNull(GoalRisk.pause(5, race, 10, null, null))
        assertTrue(abs(GymGoal.usualRate(Experience.ADVANCED) - 0.005) < 1e-12)
    }
}
