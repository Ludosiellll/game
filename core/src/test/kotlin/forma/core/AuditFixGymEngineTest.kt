package forma.core

import forma.core.model.Catalog
import forma.core.strength.DoneSet
import forma.core.strength.GymGoal
import forma.core.strength.HoldReason
import forma.core.strength.Outcome
import forma.core.strength.Phase
import forma.core.strength.Prescription
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import forma.core.strength.WarmUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The gym engine's findings of the November audit (group A), each as the case it was found on. */
class AuditFixGymEngineTest {
    private val bench = Catalog["bench"]

    /** gym-intro-hint-stall */
    @Test fun inTheFirstWeekOfABlockFollowingTheLighterHintIsNoStall() {
        val spec = SlotSpec("bench", 3, 6, 10, 2, 180, SlotRole.MAIN)
        val state = SlotState(80.0, 8, sessions = 12, linear = false)
        val p = Progression.prescribe(bench, spec, state, rirOffset = 1)
        assertTrue(p.intro)
        assertEquals(3, p.rir)
        assertTrue(p.top)
        // His usual «ещё 1» on the first set, three asked this week: the app takes the next sets lighter.
        val hint = assertNotNull(Progression.adjustAfterSet(bench, p, DoneSet(80.0, 8, 1), 80.0, 0))
        assertFalse(hint.heavier)
        assertEquals(75.0, hint.weight)
        val followed = listOf(DoneSet(80.0, 8, 1), DoneSet(75.0, 8), DoneSet(75.0, 9))
        val e = Progression.evaluate(bench, spec, state, p, followed, 80.0)
        assertEquals(Outcome.Hold(80.0, 8, HoldReason.INTRO), e.outcome)
        assertEquals(0, e.state.stalls)
        assertEquals(80.0, e.state.weight)
        assertEquals(8, e.state.reps)
        // A stall carried in through the lighter week before: still the plan followed, not a second stall that takes a rep
        // of the target off.
        val carried = Progression.evaluate(bench, spec, state.copy(stalls = 1), p, followed, 80.0)
        assertEquals(Outcome.Hold(80.0, 8, HoldReason.INTRO), carried.outcome)
        assertEquals(1, carried.state.stalls)
        assertEquals(8, carried.state.reps)
        // The same sets with the hint ignored add a rep: following the app is never worse than the plan's own numbers.
        val ignored = Progression.evaluate(bench, spec, state, p, listOf(DoneSet(80.0, 8, 1), DoneSet(80.0, 8), DoneSet(80.0, 9)), 80.0)
        assertEquals(Outcome.AddReps(80.0, 8, 9), ignored.outcome)
        // Lighter by more than the hint ever goes (a tenth), or short of the target there: a miss as in any week.
        val far = Progression.evaluate(bench, spec, state, p, listOf(DoneSet(80.0, 8, 1), DoneSet(70.0, 8), DoneSet(70.0, 9)), 80.0)
        assertFalse((far.outcome as? Outcome.Hold)?.reason == HoldReason.INTRO, "${far.outcome}")
        val short = Progression.evaluate(bench, spec, state, p, listOf(DoneSet(80.0, 8, 1), DoneSet(75.0, 7), DoneSet(75.0, 7)), 80.0)
        assertFalse((short.outcome as? Outcome.Hold)?.reason == HoldReason.INTRO, "${short.outcome}")
    }

    /** gym-layoff-lost-after-lighter-session */
    @Test fun backAfterABreakALighterFirstSessionKeepsTheBreaksCut() {
        val spec = SlotSpec("bench", 4, 5, 8, 2, 180, SlotRole.MAIN)
        val state = SlotState(100.0, 6, sessions = 20, linear = false)
        // Five weeks ill, the first session back is the lighter one: −10 % for the break, −10 % for the lighter session.
        val light = Progression.prescribe(bench, spec, state, Phase.DELOAD, daysSinceLast = 35)
        assertEquals(80.0, light.weight)
        val e = Progression.evaluate(bench, spec, state, light, List(light.sets) { DoneSet(80.0, light.reps) }, 80.0)
        assertEquals(Outcome.Deload, e.outcome)
        // Three days later the break is behind (the session moved the day last done): the weight the break made lighter,
        // as after a normal first session back — not the 100 from before it.
        val next = Progression.prescribe(bench, spec, e.state, daysSinceLast = 3)
        assertEquals(90.0, next.weight)
        val normal = Progression.prescribe(bench, spec, state, daysSinceLast = 35)
        assertEquals(90.0, normal.weight)
        // Ten weeks off: 85, not 100.
        val longer = Progression.prescribe(bench, spec, state, Phase.DELOAD, daysSinceLast = 70)
        val afterLonger = Progression.evaluate(bench, spec, state, longer, List(longer.sets) { DoneSet(longer.weight!!, longer.reps) }, 80.0)
        assertEquals(85.0, Progression.prescribe(bench, spec, afterLonger.state, daysSinceLast = 3).weight)
        // The goal's taper back after a break too: held at the break's weight, not the one before.
        val taper = Progression.prescribe(bench, spec, state, Phase.TAPER, daysSinceLast = 35)
        assertEquals(90.0, taper.weight)
        val tapered = Progression.evaluate(bench, spec, state, taper, List(2) { DoneSet(90.0, 6) }, 80.0)
        assertEquals(Outcome.Hold(90.0, 6, HoldReason.TAPER), tapered.outcome)
        assertEquals(90.0, tapered.state.weight)
        // A lighter session without a break before it keeps the progress as it was.
        val usual = Progression.prescribe(bench, spec, state, Phase.DELOAD, daysSinceLast = 4)
        val kept = Progression.evaluate(bench, spec, state, usual, List(usual.sets) { DoneSet(usual.weight!!, usual.reps) }, 80.0)
        assertEquals(100.0, kept.state.weight)
    }

    /** gym-test-day-first-time-max-single */
    @Test fun aTestDayAsTheFirstTimeInTheGoalsRangeIsReadFromTheBestSingle() {
        val ex = Catalog["chest_press"]
        val spec = SlotSpec("chest_press", 4, 3, 5, 2, 180, SlotRole.MAIN)
        val state = SlotState(null, 3)
        val p = Prescription(95.0, 1, 2, 0, calibrate = false, phase = Phase.TEST, attempts = listOf(95.0, 102.5))
        val e = Progression.evaluate(ex, spec, state, p, listOf(DoneSet(95.0, 1), DoneSet(102.5, 1)), 80.0)
        val w = assertNotNull(e.state.weight)
        // 102.5 × 1 is the max: three with two in reserve are about 88 there — not the opener for one.
        assertTrue(w <= 90.0, "${e.outcome}")
        assertTrue(e.state.reps >= 3, "${e.outcome}")
        assertIs<Outcome.Calibrated>(e.outcome)
    }

    /** gym-test-day-off-stack */
    @Test fun theTestDayIsOnTheStackAndNeverUnderTheBar() {
        val stack = (0..60).map { 9.0 + 5 * it }
        val day = GymGoal.testDay(e1rm = 100.0, target = 104.0, step = 5.0, from = 9.0)
        val weights = day.warmups.map { it.first } + day.attempts
        assertTrue(weights.isNotEmpty())
        weights.forEach { assertTrue(stack.any { s -> kotlin.math.abs(s - it) < 1e-9 }, "$it is not on the stack 9, 14, 19 …: $day") }
        assertTrue(weights.zipWithNext().all { (a, b) -> a < b }, "$day")
        // A goal on the stack is attempted as it is (104 is the stack's own weight).
        assertEquals(104.0, GymGoal.testDay(e1rm = 103.0, target = 104.0, step = 5.0, from = 9.0).attempts.last())
        // A light barbell lift: the first warm-up is the empty bar, not 17.5 under it.
        val light = GymGoal.testDay(e1rm = 35.0, target = 37.5, step = 2.5, bar = 20.0)
        assertTrue(light.warmups.isNotEmpty())
        assertTrue(light.warmups.all { it.first >= 20.0 }, "$light")
        assertTrue((light.warmups.map { it.first } + light.attempts).zipWithNext().all { (a, b) -> a < b }, "$light")
    }

    /** gym-weighted-pullups-no-warmup */
    @Test fun weightedPullUpsWarmUpFromTheBodyAlone() {
        val ramp = WarmUp.extra(25.0, 2.5)
        assertTrue(ramp.size >= 2, "$ramp")
        assertEquals(0.0, ramp.first().weight)
        assertTrue(ramp.all { it.weight < 25.0 && it.reps >= 2 }, "$ramp")
        assertTrue(ramp.zipWithNext().all { (a, b) -> a.weight < b.weight }, "$ramp")
        assertTrue(ramp.all { kotlin.math.abs(it.weight / 2.5 - Math.round(it.weight / 2.5)) < 1e-9 }, "$ramp")
        // A few kilos on the belt: the body alone is the ramp.
        assertEquals(listOf(0.0), WarmUp.extra(2.5, 2.5).map { it.weight })
        assertTrue(WarmUp.extra(0.0, 2.5).isEmpty())
    }

    /** apps-2: the machine's heaviest weight (Denis's lat pulldown ends at 92 kg, on a 4 kg step). */
    @Test fun atTheMachinesHeaviestTheRepsGrowInsteadOfTheWeight() {
        val ex = Catalog["lat_pulldown"].copy(step = 4.0, fineStep = null, heaviest = 92.0)
        val spec = SlotSpec("lat_pulldown", 3, 8, 10, 2, 120, SlotRole.SECONDARY)
        // The top of the range at the stack's last weight: no 96, a rep more — up to four past the range, then held there.
        var state = SlotState(92.0, 10, sessions = 10, linear = false)
        val seen = mutableListOf<Outcome>()
        repeat(6) {
            val p = Progression.prescribe(ex, spec, state)
            assertEquals(92.0, p.weight)
            val e = Progression.evaluate(ex, spec, state, p, listOf(DoneSet(92.0, p.reps), DoneSet(92.0, p.reps), DoneSet(92.0, p.reps + 1)), 80.0)
            seen += e.outcome
            assertEquals(92.0, e.state.weight, "${e.outcome}")
            state = e.state
        }
        assertEquals(
            listOf(
                Outcome.AddReps(92.0, 10, 11), Outcome.AddReps(92.0, 11, 12), Outcome.AddReps(92.0, 12, 13), Outcome.AddReps(92.0, 13, 14),
                Outcome.Hold(92.0, 14, HoldReason.AT_TOP), Outcome.Hold(92.0, 14, HoldReason.AT_TOP),
            ),
            seen,
        )
        // A weight stored above it (before it was set, or the coach's): the plan asks the heaviest there is.
        assertEquals(92.0, Progression.prescribe(ex, spec, SlotState(96.0, 8)).weight)
        // No heavier hint between sets past it.
        val p = Progression.prescribe(ex, spec, SlotState(92.0, 8))
        val adj = Progression.adjustAfterSet(ex, p, DoneSet(92.0, 8, 5), 80.0, 0)
        assertTrue(adj == null || adj.weight <= 92.0, "$adj")
        assertNotNull(Progression.adjustAfterSet(ex.copy(heaviest = null), p, DoneSet(92.0, 8, 5), 80.0, 0)?.takeIf { it.heavier })
        // A jump stops at it.
        val easy = SlotState(88.0, 8, sessions = 10, linear = false)
        val jumped = Progression.evaluate(ex, spec, easy, Progression.prescribe(ex, spec, easy), listOf(DoneSet(88.0, 8, 5), DoneSet(88.0, 8, 5), DoneSet(88.0, 14)), 80.0)
        assertTrue(jumped.state.weight!! <= 92.0, "${jumped.outcome}")
        // The first time: the weight found is no heavier than the stack, the reps the sets show there.
        val fresh = SlotState.fresh(spec)
        val first = Progression.prescribe(ex, spec, fresh, hint = 60.0)
        assertTrue(first.calibrate)
        val found = Progression.evaluate(ex, spec, fresh, first, listOf(DoneSet(70.0, 12, 2), DoneSet(85.0, 10, 2), DoneSet(92.0, 10, 2)), 80.0)
        assertTrue(found.state.weight!! <= 92.0 && found.state.reps >= 8, "${found.outcome}")
        // A new rep range from the estimate: the heaviest there is, with the reps the estimate gives there.
        val known = Progression.prescribe(ex, spec.copy(repMin = 3, repMax = 5), SlotState(null, 3), e1rm = 135.0)
        assertEquals(92.0, known.weight)
        assertTrue(known.reps > 3, "$known")
        // A start guess past the stack is the stack's last weight.
        assertTrue(Progression.startHint(ex.copy(heaviest = 20.0), 80.0, false, 1.7)!! <= 20.0)
    }
}
