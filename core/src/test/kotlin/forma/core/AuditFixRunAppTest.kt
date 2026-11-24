package forma.core

import forma.core.run.Cue
import forma.core.run.PaceRange
import forma.core.run.RunEvent
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.Segment
import forma.core.run.SegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The auto-pause on the road stops the clock from the moment the stop began, six seconds and more before it is applied
 * ([forma.core.run.AutoPause.HOLD_MS]): the plan's clock goes back a few seconds. What was said in them is not said again.
 */
class AuditFixRunAppTest {
    private val easy = RunWorkout(RunType.EASY, "Лёгкий бег", listOf(Segment(SegmentKind.RUN, 1800, PaceRange(330, 360))))

    private fun cues(t: RunTracker, ms: Long) = t.tick(ms).mapNotNull { (it as? RunEvent.Timeline)?.cue }

    /**
     * run-05: an easy run of 30 minutes on the road. He slows to a stop on the last beeps (29:57,5); «План выполнен»
     * plays at 30:00 while he stands, and the auto-pause stops the clock six seconds later, from 29:57,5. Walking on, the
     * plan's clock passes 29:58, 29:59 and 30:00 again: no beeps and no «План выполнен» a second time.
     */
    @Test fun theEndOfThePlanIsNotSaidAgainAfterAPauseFromAMomentPast() {
        val t = RunTracker(easy)
        t.start(0)
        val said = mutableListOf<Cue>()
        // The service ticks every 250 ms.
        for (ms in 0L..1_804_000L step 250) said += cues(t, ms)
        assertEquals(3, said.count { it is Cue.Countdown })
        assertEquals(1, said.count { it is Cue.Finished })
        // The auto-pause, applied at 30:04: from when the stop began.
        t.pause(1_797_500)
        t.resume(1_810_000)
        assertEquals(1797, t.activeSec(1_810_000), "the clock stood from 29:57,5")
        val after = mutableListOf<Cue>()
        for (ms in 1_810_000L..1_814_000L step 250) after += cues(t, ms)
        assertEquals(emptyList<Cue>(), after, "nothing said again")
        assertEquals(1801, t.activeSec(1_814_000))
    }

    /**
     * run-05: a traffic light right after «Половина отрезка.» (the stop began 2,5 s before it): the half is not said again
     * when he runs on, and what comes later in the plan is still said, once each.
     */
    @Test fun aStopAtTheHalfSaysTheHalfOnceAndTheRestStillComes() {
        val t = RunTracker(easy)
        t.start(0)
        val said = mutableListOf<Cue>()
        for (ms in 0L..904_000L step 250) said += cues(t, ms)
        assertEquals(1, said.count { it is Cue.Halfway })
        t.pause(897_500)
        t.resume(915_000)
        for (ms in 915_000L..1_820_000L step 250) said += cues(t, ms)
        assertEquals(1, said.count { it is Cue.Halfway })
        assertEquals(1, said.count { it is Cue.MinuteLeft })
        assertEquals(3, said.count { it is Cue.Countdown })
        assertEquals(1, said.count { it is Cue.Finished })
    }
}
