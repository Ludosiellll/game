package forma.app.data

import forma.core.run.Lap
import forma.core.run.RunOutcome
import forma.core.run.RunType
import forma.core.run.RunWorkout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The way to a 1500 m goal on a 230 m stadium: trials, the average lap and the laps on pace. */
class GoalProgressTest {
    private var nextId = 1L

    private fun run(
        day: Long, type: RunType, outcomes: List<RunOutcome> = emptyList(), laps: List<Lap> = emptyList(), lapM: Int? = 230,
    ): Pair<WorkoutEntity, RunEntity> {
        val id = nextId++
        val w = WorkoutEntity(
            id = id, epochDay = day, kind = Kind.RUN, templateId = type.name, title = type.name, startedAt = day * 86_400_000L,
            status = Status.DONE, summaryJson = AppJson.encodeToString(RunSummary.serializer(), RunSummary(outcomes, 40, 50.0, laps)),
        )
        val plan = AppJson.encodeToString(RunWorkout.serializer(), RunWorkout(type, type.name, emptyList(), lapMeters = lapM))
        return w to RunEntity(id, type.name, plan, 1.0, 2400, null, null, null, null, null)
    }

    private fun of(vararg runs: Pair<WorkoutEntity, RunEntity>) = GoalProgress.of(runs.map { it.first }, runs.map { it.second }, 1500)

    /** A rep lap against a 51–52 s target. */
    private fun lap(sec: Double, rep: Int, part: Double = 1.0, auto: Boolean = false) =
        Lap(sec, fast = 51.0 * part, slow = 52.0 * part, rep = rep, part = part, auto = auto)

    @Test fun trialsOverTheGoalDistanceInOrder() {
        val p = of(
            run(40, RunType.TEST, listOf(RunOutcome.TestTime(1500, 333))),
            run(10, RunType.TEST, listOf(RunOutcome.TestTime(1500, 341))),
            // Another distance (an older goal) and a trial that did not count are not in the line.
            run(20, RunType.TEST, listOf(RunOutcome.TestTime(3000, 700))),
            run(30, RunType.TEST, listOf(RunOutcome.TestNotCounted)),
        )
        assertEquals(listOf(GoalProgress.Trial(10, 341), GoalProgress.Trial(40, 333)), p.trials)
    }

    @Test fun theAverageLapAndTheLapsOnPace() {
        val laps = listOf(
            // Rep 1: 6.5 laps start with a half — 27.2 s for half a lap is a 54.4 s lap, too slow.
            lap(27.2, 1, part = 0.5), lap(51.5, 1), lap(52.4, 1), lap(51.8, 1), lap(51.2, 1), lap(51.9, 1), lap(52.0, 1),
            // Rep 2: one lap closed by the GPS — in the average, not judged.
            lap(51.0, 2), lap(53.5, 2, auto = true),
            // A lap of the recovery jog is not a rep lap.
            Lap(70.0, number = 1),
        )
        val p = of(run(12, RunType.GOAL_PACE, laps = laps))
        val s = p.sessions.single()
        val reps = laps.filter { it.rep != null }
        val avgLap = reps.sumOf { it.seconds } / reps.sumOf { it.part }
        assertTrue(abs(s.paceSecKm - avgLap / 230 * 1000) < 1e-9, "pace ${s.paceSecKm}")
        // Judged: the 8 pressed laps; the slow half is off, the others (51.0–52.4 s, ±0.5 s allowed) are on.
        assertEquals(8, s.judged)
        assertEquals(7, s.onPace)
    }

    @Test fun lapsOfAnotherStadiumCompareByPace() {
        // 60 s on a 400 m track and 34.5 s on 230 m are the same 2:30 a km.
        val a = run(1, RunType.GOAL_PACE, laps = listOf(Lap(60.0, rep = 1), Lap(60.0, rep = 1)), lapM = 400)
        val b = run(8, RunType.GOAL_PACE, laps = listOf(Lap(34.5, rep = 1), Lap(34.5, rep = 1)), lapM = 230)
        val p = of(a, b)
        assertEquals(2, p.sessions.size)
        assertTrue(abs(p.sessions[0].paceSecKm - p.sessions[1].paceSecKm) < 1e-9)
        // No target on these laps: nothing judged.
        assertEquals(0, p.sessions[0].judged)
    }

    @Test fun aSessionWithoutLapsIsLeftOut() {
        // On the road (no lap button) or less than a lap pressed: no average to show.
        val p = of(
            run(5, RunType.GOAL_PACE, laps = emptyList(), lapM = null),
            run(6, RunType.GOAL_PACE, laps = listOf(lap(26.0, 1, part = 0.5))),
            run(7, RunType.EASY, laps = listOf(Lap(80.0))),
        )
        assertTrue(p.sessions.isEmpty())
        assertTrue(p.trials.isEmpty())
    }
}
