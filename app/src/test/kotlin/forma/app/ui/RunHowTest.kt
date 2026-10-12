package forma.app.ui

import forma.core.model.RunGoal
import forma.core.run.RunPlan
import forma.core.run.RunState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The one-line "how to run" of a session: what the plan, the day off and the screen after a run say. */
class RunHowTest {
    private val goal = RunGoal(1500, 330)
    private val st = RunState(walkStage = -1, vdot = 45.0, easyMinutes = 25, goalStage = 2)

    @Test fun anEasyRunOnTheStadiumSaysTheLapNotToBeat() {
        val w = RunPlan.onTrack(RunPlan.easy(st), 230)
        val how = RunHow.short(w)
        assertTrue(how.startsWith("25 мин спокойно"), how)
        assertTrue("круг не быстрее" in how, how)
    }

    @Test fun anEasyRunOnTheRoadSaysThePace() {
        val how = RunHow.short(RunPlan.easy(st))
        assertTrue("/км" in how && "круг" !in how, how)
    }

    @Test fun goalPaceRepsSayHowManyAndTheLapRange() {
        val w = RunPlan.onTrack(RunPlan.goalPace(st, goal, 230), 230)
        val how = RunHow.short(w)
        assertTrue(how.startsWith("Разминка 12:00, ${w.reps} × "), how)
        assertTrue(Regex("круг \\d\\d–\\d\\d с").containsMatchIn(how), how)
        assertTrue("отдых" in how, how)
    }

    @Test fun theTrialSaysEvenFirstThenFaster() {
        val how = RunHow.short(RunPlan.goalTest(st, goal, 230))
        assertTrue(how.startsWith("В полную силу, но ровно: первые круги по"), how)
        assertEquals("12 минут как можно дальше, ровно, без рывка в начале", RunHow.short(RunPlan.test(st)))
    }
}

/** A free run's own target from what was typed: a lap on the stadium, a pace on the road. */
class FreeTargetTest {
    @Test fun aLapTimeOnTheStadium() {
        val r = forma.app.ui.screens.freeTarget("75", 230)!!
        // 75 s on 230 m is 5:26 a km, give or take a second a lap.
        assertTrue(r.fast in 321..323 && r.slow in 330..332, r.toString())
        assertEquals(null, forma.app.ui.screens.freeTarget("10", 230), "10 s a lap is no pace")
        assertEquals(null, forma.app.ui.screens.freeTarget("", 230))
    }

    @Test fun aPaceOnTheRoad() {
        assertEquals(forma.core.run.PaceRange(325, 335), forma.app.ui.screens.freeTarget("5:30", null))
        assertEquals(null, forma.app.ui.screens.freeTarget("abc", null))
    }
}
