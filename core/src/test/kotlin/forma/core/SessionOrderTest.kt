package forma.core

import forma.core.model.Emphasis
import forma.core.plan.SessionOrder
import forma.core.plan.SessionOrder.Day
import forma.core.plan.SessionOrder.Fatigue
import forma.core.plan.SessionOrder.Legs
import forma.core.plan.SessionOrder.Run
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which goes first on a day with the gym and a run: the run's kind, the legs in the gym, priority, fatigue, the last days. */
class SessionOrderTest {
    @Test fun aHardRunGoesBeforeTheGymUnlessTheGymIsThePriorityAndWorksTheLegs() {
        // Denis's Thursday: a hard run and the upper body.
        val thu = SessionOrder.advise(Day(Run.HARD, Legs.NONE, Emphasis.BALANCED))
        assertTrue(thu.runFirst)
        assertTrue(thu.tips.any { "сначала зал" in it.lowercase() }, thu.tips.toString())
        // A hard run and squats: the run, with the advice to split the day.
        val legs = SessionOrder.advise(Day(Run.HARD, Legs.HEAVY, Emphasis.BALANCED, legNames = listOf("приседания со штангой")))
        assertTrue(legs.runFirst)
        assertTrue("приседания со штангой" in legs.why)
        assertTrue(legs.tips.any { "6 часов" in it })
        // The gym first for whom the gym matters more.
        assertFalse(SessionOrder.advise(Day(Run.HARD, Legs.HEAVY, Emphasis.GYM)).runFirst)
        // The race always first.
        assertTrue(SessionOrder.advise(Day(Run.RACE, Legs.HEAVY, Emphasis.GYM)).runFirst)
    }

    @Test fun anEasyRunComesAfterTheGymALongOneByPriority() {
        // Denis's Saturday: the legs, then the easy run as a cool-down.
        val sat = SessionOrder.advise(Day(Run.EASY, Legs.HEAVY, Emphasis.BALANCED))
        assertFalse(sat.runFirst)
        assertTrue("заминка" in sat.why)
        assertTrue(SessionOrder.advise(Day(Run.LONG, Legs.HEAVY, Emphasis.RUN)).runFirst)
        assertFalse(SessionOrder.advise(Day(Run.LONG, Legs.NONE, Emphasis.BALANCED)).runFirst)
    }

    @Test fun fatigueAndHeavyLegsAreSaid() {
        val tired = SessionOrder.advise(Day(Run.HARD, Legs.NONE, Emphasis.BALANCED, Fatigue.TIRED, legsHeavyFrom = "позавчера"))
        assertTrue(tired.tips.any { "уставший" in it })
        assertTrue(tired.tips.any { "позавчера" in it })
        val light = SessionOrder.advise(Day(Run.EASY, Legs.NONE, Emphasis.BALANCED, Fatigue.TIRED, gymLight = true))
        assertTrue(light.tips.any { "сократи" in it })
        assertTrue(light.tips.any { "облегчённый" in it })
    }

    @Test fun afterTheFirstTheOtherIsAdjusted() {
        val d = Day(Run.HARD, Legs.HEAVY, Emphasis.BALANCED)
        assertNotNull(SessionOrder.afterFirst(d, runDone = false))
        assertTrue("1 повтор" in SessionOrder.afterFirst(d, runDone = true)!!)
        assertNull(SessionOrder.afterFirst(Day(Run.HARD, Legs.NONE, Emphasis.BALANCED), runDone = true))
        assertEquals("Сначала бег, потом зал.", SessionOrder.words(SessionOrder.advise(d)))
    }
}
