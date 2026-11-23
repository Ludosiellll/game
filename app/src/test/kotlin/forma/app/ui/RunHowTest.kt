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
        val how = RunHow.short(w).replace('\u00A0', ' ')
        // The minutes are the title's («Лёгкий бег · 25 мин»), right above the line: not said twice.
        assertTrue("25 мин" in w.title && "мин" !in how, "${w.title} / $how")
        assertTrue(how.startsWith("Круг не быстрее"), how)
    }

    @Test fun anEasyRunOnTheRoadSaysThePace() {
        val how = RunHow.short(RunPlan.easy(st)).replace('\u00A0', ' ')
        assertTrue("/км" in how && "круг" !in how, how)
    }

    @Test fun goalPaceRepsSayTheLapRangeAndTheTitleHowMany() {
        val w = RunPlan.onTrack(RunPlan.goalPace(st, goal, 230), 230)
        val how = RunHow.short(w).replace('\u00A0', ' ')
        // How many is the title's («Темп цели · 6 × 1 круг»), right above the line; the line has the lap time to keep,
        // and the warm-up and the rest in minutes, not as a clock (23 November: «Разминка 12:00» read as noon).
        assertTrue("${w.reps} × " in w.title && " × " !in how && "${w.reps} круг" !in how, "${w.title} / $how")
        assertTrue(how.startsWith("Разминка 12 мин, круг "), how)
        assertTrue(Regex("круг \\d\\d(,\\d)?–\\d\\d(,\\d)? с").containsMatchIn(how), how)
        assertTrue(Regex("отдых \\d+ (мин|с)").containsMatchIn(how) && "—" !in how && ":00" !in how, how)
        // Longer reps: the lap time to keep, the reps again in the title only.
        val longerRun = RunPlan.onTrack(RunPlan.goalPace(st.copy(goalStage = 5), goal, 230), 230)
        val longer = RunHow.short(longerRun).replace('\u00A0', ' ')
        assertTrue(Regex("^Разминка \\d+ мин, круг \\d\\d").containsMatchIn(longer), longer)
        assertTrue(Regex("\\d × \\d круга?").containsMatchIn(longerRun.title) && " × " !in longer, "${longerRun.title} / $longer")
    }

    /** Under the line before the start: the cool-down, the whole time in minutes, the effort as «тяжесть N из 10». */
    @Test fun theSetupSaysTheRestInOneGreyLine() {
        val w = RunPlan.onTrack(RunPlan.goalPace(st, goal, 230), 230)
        assertEquals("Заминка 8 мин · всего ${w.totalSeconds / 60} мин · тяжесть ${w.targetRpe} из 10", RunHow.totals(w).replace('\u00A0', ' '))
        // An easy run is its running alone, its minutes in the title: the effort up to the plan's limit.
        assertEquals("Тяжесть до 5 из 10", RunHow.totals(RunPlan.easy(st)).replace('\u00A0', ' '))
        // A trial's line has no warm-up: said here.
        assertTrue(RunHow.totals(RunPlan.goalTest(st, goal, 230)).replace('\u00A0', ' ').startsWith("Разминка "))
    }

    @Test fun theTrialSaysEvenFirstThenFaster() {
        val how = RunHow.short(RunPlan.goalTest(st, goal, 230)).replace('\u00A0', ' ')
        assertTrue(how.startsWith("В полную силу, но ровно: первые круги по"), how)
        assertEquals("12 минут как можно дальше, ровно, без рывка в начале", RunHow.short(RunPlan.test(st)).replace('\u00A0', ' '))
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
