package forma.core

import forma.core.analytics.SetRow
import forma.core.analytics.Stats
import forma.core.model.Catalog
import forma.core.strength.DoneSet
import forma.core.strength.Outcome
import forma.core.strength.Phase
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import kotlin.test.Test
import kotlin.test.assertTrue

/** Review probes on cac12ca (the taper and the test of the gym goal), each one a finding. */
class SessionNowReviewTest {
    private val press = Catalog["chest_press"]

    /**
     * Probe: a made single on the test day, the reserve not marked (the second attempt is what decides, nobody taps
     * «мог ещё» on a max attempt) — the way to the goal ([Stats.e1rmSeries], what [forma.core.strength.GymGoal.path]
     * measures) reads it with the regular-set default of TWO in reserve: a single at 100 counts as a 3-rep set.
     * The engine's own evaluation of the same set ([Progression.e1rmOf] with the test's rir 0) says 100.
     */
    @Test fun aTestSingleWithoutAReserveMarkCountsAsTwoInReserveOnTheWay() {
        val row = SetRow(workoutId = 1, epochDay = 20_000, exerciseId = press.id, weight = 100.0, reps = 1, rir = null, targetReps = 1)
        val point = Stats.e1rmSeries(listOf(row), press) { 71.0 }.single()
        assertTrue(point.value <= 100.0 * 1.01 + 1e-9, "a made single at 100 kg on the test day is ${"%.1f".format(point.value)} kg on the way to the goal")
    }

    /**
     * Probe: the goal's lift never done before (a goal set on it, the date within the taper, or a new lift the plan took
     * for the goal): the taper session calibrates (no weight to keep), but [Progression.evaluate] in [Phase.TAPER] holds
     * «the weight as it was» — null — so the weight found is thrown away and the outcome says «продолжим с 0 кг».
     */
    @Test fun aTaperOnALiftWithNoStateLosesTheWeightItFound() {
        val spec = SlotSpec(press.id, 4, 3, 5, 2, 180, SlotRole.MAIN)
        val fresh = SlotState.fresh(spec)
        val p = Progression.prescribe(press, spec.copy(sets = 2), fresh, Phase.TAPER, hint = 60.0)
        assertTrue(p.calibrate, "no weight yet: the session searches for one ($p)")
        val ev = Progression.evaluate(press, spec, fresh, p, listOf(DoneSet(60.0, 5, 2), DoneSet(70.0, 5, 2)), 71.0)
        assertTrue(
            ev.state.weight != null || ev.outcome is Outcome.Calibrated,
            "the weight found in the taper is lost: outcome ${ev.outcome}, state weight ${ev.state.weight}",
        )
    }
}
