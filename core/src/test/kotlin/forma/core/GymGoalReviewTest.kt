package forma.core

import forma.core.analytics.Point
import forma.core.model.Experience
import forma.core.model.LiftGoal
import forma.core.strength.GymGoal
import forma.core.strength.Phase
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import forma.core.model.Catalog
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import forma.core.strength.Prescription
import forma.core.strength.DoneSet
import kotlin.test.assertNull
import kotlin.test.assertFalse

/** The gym goal reckoning, as a review on 1cbf326 found it wanting (each a finding fixed). */
class GymGoalReviewTest {
    private val today = 20_000L

    private fun series(start: Double, weekly: Double, weeks: Int): List<Point> =
        (0..weeks * 7 step 4).map { d -> Point(today - weeks * 7 + d, start * (1 + weekly).pow(d / 7.0)) }

    /**
     * Finding: the AMBITIOUS zone is empty for anyone whose own growth is above fastRate (1.6 x usual) but within the cap
     * (2 x usual). A person growing 0.9 %/week (ADVANCED: usual 0.5, fast 0.8, cap 1.0) who needs 0.95 %/week for the date
     * is told "вряд ли" — one line after being told they grow at 0.9 %/week.
     */
    @Test fun aDateJustAboveTheOwnRateIsAmbitiousNotUnlikely() {
        val s = series(100.0, 0.009, 6)
        val now = s.last().value
        val weeks = 10
        // Needs 0.95 %/week: a hair above the own 0.9 %/week.
        val target = now * (1.0095).pow(weeks)
        val p = GymGoal.path(LiftGoal("chest_press", target, date = today + weeks * 7), s, Experience.ADVANCED, today)
        assertTrue(p.personal, "own growth is used")
        assertEquals(0.009, p.weekly, 0.0005)
        assertTrue(p.needed!! > p.weekly && p.needed!! < p.weekly * 1.1, "needed=${p.needed} weekly=${p.weekly}")
        val words = GymGoal.words(LiftGoal("chest_press", target, date = today + weeks * 7), p, "Жим в тренажёре", 5.0)
        assertNotEquals(GymGoal.Verdict.UNLIKELY, p.verdict, "verdict=${p.verdict}; words: $words")
    }

    /**
     * Finding: in the taper the plan halves the sets, but a planned lighter week (last of the block) halves them again and
     * takes 10 % off the weight: one set at 90 % for the days before the test — "при том же весе" is not kept.
     */
    @Test fun theTaperKeepsHalfTheSetsAtTheWeightWithoutAPlusSet() {
        val ex = Catalog["chest_press"]
        val heavy = GymGoal.heavy(Experience.ADVANCED)
        val spec = SlotSpec("chest_press", GymGoal.taperSets(heavy.sets), heavy.repMin, heavy.repMax, 2, heavy.restSec, SlotRole.MAIN)
        val st = SlotState(weight = 105.0, reps = 3)
        val p = Progression.prescribe(ex, spec, st, Phase.TAPER)
        assertEquals(2, p.sets, "taper sets should stay at half: got ${p.sets}")
        assertEquals(105.0, p.weight!!, 1e-9, "taper keeps the weight: got ${p.weight}")
        assertFalse(p.top, "no «+» set in the taper")
        // A good taper session leaves the weight and the reps as they were.
        val ev = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(105.0, 3), DoneSet(105.0, 7)))
        assertEquals(105.0, ev.state.weight!!, 1e-9)
        assertEquals(3, ev.state.reps)
    }

    @Test fun theTestDayGoesAttemptByAttemptAndLeavesThePlanAsItWas() {
        val ex = Catalog["chest_press"]
        val spec = SlotSpec("chest_press", 4, 3, 5, 2, 180, SlotRole.MAIN)
        val st = SlotState(weight = 115.0, reps = 4)
        val p = Prescription(125.0, 1, 2, 0, calibrate = false, phase = Phase.TEST, attempts = listOf(125.0, 135.0))
        // Made the opener: the goal next; missed it: the test is over.
        assertEquals(135.0, Progression.nextSet(ex, p, listOf(DoneSet(125.0, 1)))!!.weight, 1e-9)
        assertNull(Progression.nextSet(ex, p, listOf(DoneSet(125.0, 0))))
        val ev = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(125.0, 1), DoneSet(135.0, 1)))
        assertEquals(115.0, ev.state.weight!!, 1e-9)
        assertEquals(4, ev.state.reps)
        assertTrue(ev.e1rm!! >= 135.0)
    }

    /**
     * Finding: a goal typed off the lift's step (126 on a 2.5 kg bar, 128 on a 5 kg machine) is never attempted on the
     * test day: the last attempt is rounded down and the goal itself is skipped although it is within 2 % of the estimate.
     */
    @Test fun aGoalOffTheStepIsAttemptedOnTheTestDay() {
        val day = GymGoal.testDay(e1rm = 125.0, target = 126.0, step = 2.5)
        assertTrue(126.0 in day.attempts || day.attempts.last() >= 126.0, "attempts=${day.attempts}")
    }
}
