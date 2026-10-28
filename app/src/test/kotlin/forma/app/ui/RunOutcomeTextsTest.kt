package forma.app.ui

import forma.core.run.RunOutcome
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The words of a run's outcomes say what happened: a form that did not move is not «чуть спокойнее». */
class RunOutcomeTextsTest {
    @Test fun aTrialThatGivesTheSameFormSaysSo() {
        val same = Texts.run(RunOutcome.VdotChanged(45.0, 45.0))
        assertTrue("спокойнее" !in same && "45,0" in same, same)
        assertEquals("Форма подтвердилась: VDOT 45,0, темпы прежние.", same)
        assertTrue("спокойнее" in Texts.run(RunOutcome.VdotChanged(45.0, 44.5)))
        assertTrue("выросла" in Texts.run(RunOutcome.VdotChanged(45.0, 45.5)))
        assertTrue("рассчитаны" in Texts.run(RunOutcome.VdotChanged(null, 45.0)))
    }

    @Test fun everyTrialOutcomeHasItsNumbersAndNoNull() {
        val outcomes = listOf(
            RunOutcome.TestTime(1538, 365), RunOutcome.TestFromPart(923, 219, finished = true, capped = true),
            RunOutcome.TestFromPart(923, 219, finished = false, kept = true), RunOutcome.TestFromPart(923, 219, finished = false, capped = true),
            RunOutcome.TestStopped(718), RunOutcome.TestNotCounted, RunOutcome.TestDoubtful(52.0), RunOutcome.LoopShorter(195, 205),
            RunOutcome.VdotChanged(null, 45.0), RunOutcome.GoalReached,
        )
        for (o in outcomes) {
            val t = Texts.run(o)
            assertTrue(listOf("null", "NaN", "Infinity").none { it in t }, t)
        }
    }
}
