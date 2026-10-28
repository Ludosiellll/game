package forma.core

import forma.core.model.Catalog
import forma.core.strength.DoneSet
import forma.core.strength.OneRm
import forma.core.strength.Outcome
import forma.core.strength.Phase
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Help on pull-ups (a band, the gravitron) is for those who cannot pull themselves up yet. Seen on a friend's phone
 * (55 kg, 5–6 pull-ups with the body alone): «помощь 7.5 кг» session after session, while he did his sets with the
 * body alone and with +5 kg — not everybody has a band, and whoever does five does not need one.
 */
class AssistedPullupsTest {
    private val ex = Catalog["pullup"]
    private val spec = SlotSpec("pullup", 3, 5, 8, 2, 150, SlotRole.MAIN)
    private val bw = 55.0

    /** His first time: 5 (1 left), 5 (0 left), 3 with the body alone. */
    @Test fun fivePullUpsCalibrateWithTheBodyAlone() {
        val fresh = SlotState.fresh(spec)
        val p = Progression.prescribe(ex, spec, fresh, bodyWeight = bw, hint = 0.0)
        val ev = Progression.evaluate(ex, spec, fresh, p, listOf(DoneSet(0.0, 5, 1), DoneSet(0.0, 5, 0), DoneSet(0.0, 3, 0)), bw)
        val c = assertIs<Outcome.Calibrated>(ev.outcome)
        assertTrue(c.weight >= 0.0, "5 (1 left), 5, 3 with the body alone → $c")
        assertTrue(c.reps in 3..5, "$c")
        // Nor help between the sets of the search.
        val next = Progression.nextSet(ex, p, listOf(DoneSet(0.0, 5, 1)), bw)!!
        assertTrue(next.weight >= 0.0, "after 5 with 1 left: ${next.weight}")
    }

    /** The help an older version left (7.5 kg), while his last weeks show 9 to failure with the body alone. */
    @Test fun helpLeftFromBeforeGoesWhenOwnSetsShowNoNeed() {
        val stale = SlotState(-7.5, 5, sessions = 4, steady = 1)
        val p = Progression.prescribe(ex, spec, stale, e1rm = 64.14, bodyWeight = bw, ownBest = OneRm.estimate(bw, 5, 4))
        assertEquals(0.0 to 5, p.weight to p.reps)
    }

    /** Short with the body alone, for someone who does five and more: fewer reps, not help. */
    @Test fun aMissWithTheBodyAloneLowersTheRepsNotAddsHelp() {
        val st = SlotState(0.0, 5, sessions = 3, linear = false)
        val p = Progression.prescribe(ex, spec, st, e1rm = 68.0, bodyWeight = bw)
        val ev = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(0.0, 4, 0), DoneSet(0.0, 4, 0), DoneSet(0.0, 3, 0)), bw, recentBest = 70.0)
        assertTrue(ev.state.weight!! >= 0.0, "4, 4, 3 with the body alone → ${ev.outcome}")
        // A hard set does not bring a hint of help either.
        val hint = Progression.nextSet(ex, p, listOf(DoneSet(0.0, 5, 0)), bw)!!
        assertTrue(hint.weight >= 0.0, "after 5 with nothing left: ${hint.weight}")
    }

    /** A lighter week with the body alone: fewer sets and more in reserve, not 5 kg of help. */
    @Test fun aLighterWeekWithTheBodyAloneIsNotHelp() {
        val st = SlotState(0.0, 5, sessions = 3)
        assertEquals(0.0, Progression.prescribe(ex, spec, st, Phase.DELOAD, e1rm = 70.0, bodyWeight = bw).weight)
        assertEquals(0.0, Progression.prescribe(ex, spec, st, e1rm = 70.0, bodyWeight = bw, daysSinceLast = 40).weight)
    }

    /** One or two pull-ups: help is what lets the sets be done. */
    @Test fun oneOrTwoPullUpsStillGetHelp() {
        val fresh = SlotState.fresh(spec)
        val p = Progression.prescribe(ex, spec, fresh, bodyWeight = bw, hint = 0.0)
        val ev = Progression.evaluate(ex, spec, fresh, p, listOf(DoneSet(0.0, 2, 0)), bw)
        assertTrue(assertIs<Outcome.Calibrated>(ev.outcome).weight < 0, "${ev.outcome}")
    }
}
