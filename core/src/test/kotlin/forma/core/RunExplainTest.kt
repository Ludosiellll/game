package forma.core

import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.DayReasons
import forma.core.plan.ProgramGenerator
import forma.core.run.Lap
import forma.core.run.RunExplain
import forma.core.run.RunPlan
import forma.core.run.RunReview
import forma.core.run.RunState
import forma.core.run.RunType
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The words behind a session: why it is there, what it trains, why on its day, and after it how it went. */
class RunExplainTest {
    private val goal = RunGoal(1500, 330)
    private val state = RunState(vdot = 50.0, walkStage = -1, runs = 6)

    @Test fun everyRunSaysWhyWhatAndHowLong() {
        val runs = listOf(
            RunPlan.easy(state), RunPlan.long(state), RunPlan.intervals(state), RunPlan.tempo(state),
            RunPlan.goalPace(state, goal, 230), RunPlan.goalTest(state, goal, 230), RunPlan.runWalk(RunState()),
        )
        for (w in runs) {
            val why = RunExplain.of(w, goal)
            assertTrue(why.purpose.isNotBlank() && why.body.isNotBlank() && why.how.isNotBlank(), w.type.name)
            assertTrue(why.recoveryHours in 24..72, w.type.name)
        }
        // Personal: the goal pace says the goal and the lap to run; an easy run its limit.
        val gp = RunExplain.of(RunPlan.goalPace(state, goal, 230), goal)
        assertTrue("1500 м за 5:30" in gp.purpose && "круг 230 м за 51 с" in gp.purpose, gp.purpose)
        val easy = RunExplain.of(RunPlan.onTrack(RunPlan.easy(state), 230), goal)
        assertTrue("Круг — не быстрее" in easy.how, easy.how)
        // A trial needs the most rest.
        assertEquals(72, RunExplain.of(RunPlan.goalTest(state, goal, 230), goal).recoveryHours)
    }

    /** Denis's own week: Monday the hard run alone, Tuesday the gym and an easy run — each day says why. */
    @Test fun eachDaySaysWhyItHoldsWhatItHolds() {
        val p = ProgramGenerator.generate(
            Profile(
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
                runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6), bodyWeightKg = 80.0, doubleDays = true, runGoal = goal,
                lapMeters = 230, gymDays = setOf(2, 4, 6), runDays = setOf(1, 2, 4, 6),
            ),
            LocalDate.of(2026, 10, 12),
        )
        val monday = DayReasons.of(p, 1)
        assertTrue(monday.any { "День без зала" in it }, monday.toString())
        assertTrue(monday.any { "Завтра — ни тяжёлого бега, ни ног" in it || "Накануне" in it }, monday.toString())
        val tuesday = DayReasons.of(p, 2)
        assertTrue(tuesday.any { "Лёгкий бег" in it }, tuesday.toString())
        val thursday = DayReasons.of(p, 4)
        assertTrue(thursday.any { "в день верха" in it && "Сначала бег" in it }, thursday.toString())
        val saturday = DayReasons.of(p, 6)
        assertTrue(saturday.any { "в день ног" in it && "Сначала зал" in it }, saturday.toString())
        assertTrue(DayReasons.of(p, 3).isEmpty())
    }

    /** Race-pace reps on the stadium: the laps on target and how even, the effort against the plan, the rest needed. */
    @Test fun aSessionOfRepsIsReviewedByItsLaps() {
        val w = RunPlan.goalPace(state, goal, 230)
        // 8 reps of a lap at 51–52 s: the first three fast (49 s), the last slowing (53.5 s).
        val laps = (1..8).map { rep ->
            val sec = when {
                rep <= 3 -> 49.0
                rep >= 7 -> 53.5
                else -> 51.5
            }
            Lap(sec, w.segments.first { it.lapSec != null }.lapSec!! - 0.5, w.segments.first { it.lapSec != null }.lapSec!! + 0.5, 1, 1, rep)
        }
        val review = RunReview.of(RunReview.Run(w, laps, rpe = 9, durationSec = 1800), goal)
        val how = review.first { it.title == "Как пробежал" }
        assertTrue("3 из 8 в темпе" in how.text && "быстрее — 3" in how.text && "медленнее — 2" in how.text, how.text)
        assertTrue("Последний отрезок медленнее первого" in how.text, how.text)
        assertEquals(-1, how.tone)
        val effort = review.first { it.title == "Тяжесть" }
        assertTrue("шаг не засчитан" in effort.text, effort.text)
        // Made lighter for a bad day: it moves nothing, and the debrief says no more.
        val light = RunReview.of(RunReview.Run(RunPlan.lighter(state, w), rpe = 9), goal).first { it.title == "Тяжесть" }
        assertTrue("облегчённой" in light.text && "не двигает" in light.text, light.text)
        assertTrue(review.any { it.title == "Что это даёт телу" })
        assertTrue(review.first { it.title == "Восстановление" }.text.contains("2–3 дня"), "a 9 adds a day to the 48 h")
    }

    @Test fun anEasyRunTooFastIsSaidSo() {
        val w = RunPlan.onTrack(RunPlan.easy(state), 230)
        val limit = w.segments.first().pace!!.fast
        val fast = RunReview.of(RunReview.Run(w, rpe = 6, avgPace = limit - 30.0), goal).first { it.title == "Как пробежал" }
        assertEquals(-1, fast.tone)
        assertTrue("быстрее лёгкого" in fast.text, fast.text)
        val ok = RunReview.of(RunReview.Run(w, rpe = 4, avgPace = limit + 20.0), goal)
        assertEquals(1, ok.first { it.title == "Как пробежал" }.tone)
        assertEquals("Оценка 4 — легко, как и должно быть.", ok.first { it.title == "Тяжесть" }.text)
        assertTrue(ok.first { it.title == "Восстановление" }.text.startsWith("Такой бег проходит за сутки"))
    }

    @Test fun aTrialIsMeasuredAgainstTheGoal() {
        val w = RunPlan.goalTest(state, goal, 230)
        val lapSec = w.segments.first { it.laps != null }.lapSec!!
        val laps = listOf(Lap(lapSec / 2, rep = 1, part = 0.5)) + (1..6).map { Lap(if (it <= 3) 52.0 else 55.0, rep = 1) }
        val how = RunReview.of(RunReview.Run(w, laps, rpe = 10, trial = 1495 to 348), goal).first { it.title == "Как пробежал" }
        assertTrue("1495 м за 5:48" in how.text && "до цели 5:30 — ещё около" in how.text.lowercase(), how.text)
        assertTrue("Вторая половина медленнее" in how.text, how.text)
        val done = RunReview.of(RunReview.Run(w, rpe = 10, trial = 1495 to 328), goal).first { it.title == "Как пробежал" }
        assertEquals(1, done.tone)
        assertNotNull(RunReview.of(RunReview.Run(RunPlan.easy(state)), goal).firstOrNull { it.title == "Что это даёт телу" })
        assertTrue(RunReview.of(RunReview.Run(RunPlan.easy(state)), goal).none { it.title == "Как пробежал" }, "nothing measured, nothing judged")
        assertEquals(RunType.TEST, w.type)
    }
}
