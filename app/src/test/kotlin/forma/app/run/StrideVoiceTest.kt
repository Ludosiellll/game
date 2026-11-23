package forma.app.run

import forma.core.model.RunGoal
import forma.core.run.Cue
import forma.core.run.RunEvent
import forma.core.run.RunPlan
import forma.core.run.RunState
import forma.core.run.RunTimeline
import org.junit.Test
import kotlin.test.assertTrue

/** A stride lasts 15 s: the voice explains them before the first one and says only «пошёл» at it. */
class StrideVoiceTest {
    private val w = RunPlan.goalTest(RunState(walkStage = -1), RunGoal(1500, 330), 205)

    @Test fun theStridesAreExplainedBeforeTheFirstAndTheFirstIsAGo() {
        val cues = RunTimeline.allCues(w)
        val ahead = cues.filter { it.at == 495 - 30 }.mapNotNull { CoachText.say(w, RunEvent.Timeline(it), gps = true) }
        assertTrue(ahead.any { "4 по 15 секунд" in it && "30 секунд трусцы" in it }, "$ahead")
        val first = cues.filterIsInstance<Cue.Stride>().first { it.on && it.n == 1 }
        val go = CoachText.say(w, RunEvent.Timeline(first), gps = true)!!
        assertTrue("пошёл" in go && go.length < 60, go)
    }

    /**
     * 23 November: with GPS on, the voice said «Старт засчитаю сам, когда побежишь», the screen «старт засчитается сам»,
     * and a run with no laps «С GPS на стадионе они засчитываются сами». Since 30 October only a press starts a rep and
     * makes a lap: a runner waiting for the start to count itself loses the rep's time.
     */
    @Test fun theStartIsAlwaysAPress() {
        val toLine = RunTimeline.allCues(w).filterIsInstance<Cue.ToLine>().first()
        for (gps in listOf(true, false)) {
            val said = CoachText.say(w, RunEvent.Timeline(toLine), gps = gps)!!
            assertTrue("«Круг»" in said && "сам" !in said, said)
        }
        val unknown = forma.app.ui.Texts.run(forma.core.run.RunOutcome.PaceUnknown)
        assertTrue("сами" !in unknown && "«Круг»" in unknown, unknown)
    }
}
