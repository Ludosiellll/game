package forma.app.data

import forma.core.run.RunOutcome
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.Vdot

/** The way to a race goal on a stadium: the trials over its distance and the sessions of reps at its pace. */
object GoalProgress {
    /**
     * A counted trial over the goal distance, as run ([meters], [seconds]: on a 230 m track 1500 m is 6.5 laps, 1495 m)
     * and its time over the goal distance itself ([goalSeconds], by the same fitness).
     */
    data class Trial(val epochDay: Long, val meters: Int, val seconds: Int, val goalSeconds: Double)

    /**
     * A session of reps at the goal pace: its average rep lap as a pace (seconds a km, so laps of another stadium do not
     * mix in), the laps pressed against a target and how many of them were on it.
     */
    data class Session(val epochDay: Long, val paceSecKm: Double, val onPace: Int, val judged: Int)

    data class Result(val trials: List<Trial>, val sessions: List<Session>)

    /** From the finished [workouts] and their [runs], oldest first; trials only over [meters] (within 3 %: whole laps). */
    fun of(workouts: List<WorkoutEntity>, runs: List<RunEntity>, meters: Int): Result {
        val lapOf = runs.associate { r ->
            r.workoutId to runCatching { AppJson.decodeFromString(RunWorkout.serializer(), r.planJson).lapMeters }.getOrNull()
        }
        val paceOf = runs.associate { it.workoutId to it.workPace }
        val trials = mutableListOf<Trial>()
        val sessions = mutableListOf<Session>()
        workouts.filter { it.kind == Kind.RUN && it.status == Status.DONE }.sortedBy { it.startedAt }.forEach { w ->
            val s = w.summaryJson?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() } ?: return@forEach
            s.outcomes.filterIsInstance<RunOutcome.TestTime>().firstOrNull { kotlin.math.abs(it.meters - meters) <= meters * 0.03 }?.let {
                val same = if (it.meters == meters) it.seconds.toDouble()
                else Vdot.predictSeconds(Vdot.fromRace(it.meters.toDouble(), it.seconds.toDouble()), meters.toDouble())
                trials += Trial(w.epochDay, it.meters, it.seconds, same)
            }
            if (w.templateId != RunType.GOAL_PACE.name) return@forEach
            // The laps of the reps (a rep of 6.5 laps starts with a half: counted as half a lap).
            val reps = s.laps.filter { it.rep != null && it.part > 0 }
            val parts = reps.sumOf { it.part }
            val lapM = lapOf[w.id]
            if (lapM == null || parts < 1.0) {
                // No laps pressed: the reps' pace as entered by hand (a watch) or measured on the road; nothing to judge.
                paceOf[w.id]?.takeIf { it > 0 }?.let { sessions += Session(w.epochDay, it, 0, 0) }
                return@forEach
            }
            val lapSec = reps.sumOf { it.seconds } / parts
            // Laps the GPS closed are about ±2 s: they count in the average, not in the judging.
            val judged = reps.filter { !it.auto && it.verdict != null }
            sessions += Session(w.epochDay, lapSec / lapM * 1000.0, judged.count { it.verdict == 0 }, judged.size)
        }
        return Result(trials, sessions)
    }
}
