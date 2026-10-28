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
}
