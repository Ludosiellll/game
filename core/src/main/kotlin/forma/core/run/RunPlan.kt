package forma.core.run

import forma.core.model.RunGoal
import forma.core.text.Ru
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Training paces in seconds per km derived from VDOT. */
@Serializable
data class Paces(val easy: PaceRange, val marathon: Int, val threshold: Int, val interval: Int, val repetition: Int)

object PaceTable {
    /**
     * Shares of VDOT used for each zone, tuned so VDOT 50 reproduces Daniels' table
     * (T 4:15/km, I 3:55/km). Easy spans roughly 62–70 % of VDOT.
     */
    const val EASY_FAST = 0.70
    const val EASY_SLOW = 0.62
    const val THRESHOLD = 0.88
    const val INTERVAL = 0.975

    fun forVdot(vdot: Double): Paces {
        val e1 = Vdot.paceAtFraction(vdot, EASY_FAST).roundToInt()
        val e2 = Vdot.paceAtFraction(vdot, EASY_SLOW).roundToInt()
        val m = (Vdot.predictSeconds(vdot, 42195.0) / 42.195).roundToInt()
        val t = Vdot.paceAtFraction(vdot, THRESHOLD).roundToInt()
        val i = Vdot.paceAtFraction(vdot, INTERVAL).roundToInt()
        // Repetition pace: about 6 s per 400 m faster than interval pace (Daniels).
        val r = i - 15
        return Paces(PaceRange(e1, e2), m, t, i, r)
    }
}

/** Where the runner is in each progression ladder. */
@Serializable
data class RunState(
    val vdot: Double? = null,
    /** Index in the run/walk ladder; -1 once 30 minutes of continuous running are reached. */
    val walkStage: Int = 0,
    val easyMinutes: Int = 30,
    val longMinutes: Int = 45,
    val intervalStage: Int = 0,
    val tempoStage: Int = 0,
    /** Hard sessions done: the order of them counts by it ([RunPlan.hardCycle]; without a middle-distance goal intervals and tempo by turns). */
    val qualityCount: Int = 0,
    val week: Int = 0,
    val runs: Int = 0,
    /** Epoch day of the last 12-minute test. */
    val lastTestDay: Long? = null,
    /** Quality sessions in a row run on pace; three of them lift VDOT by 1. */
    val qualityOnPace: Int = 0,
    /** Quality sessions in a row that were not completed as planned (both ladders: three bring the paces down). */
    val qualityFails: Int = 0,
    /** Interval sessions in a row not completed as planned: two step the ladder back (tempo between them does not count). */
    val intervalFails: Int = 0,
    /** The same for tempo. */
    val tempoFails: Int = 0,
    /**
     * The tempo run for now instead of the ladder's step: the coach's, or the step split into reps half as long after a
     * run far too hard (Denis, 5 November: 3 × 5 min stopped in the second rep). Done well, it grows back to the step
     * ([RunPlan.towards]); null — the step.
     */
    val tempoOwn: TempoShape? = null,
    /**
     * Seconds a km added to the threshold pace for this runner: a speed type (fitness from a short race) holds intervals and
     * race pace but not the tempo the fitness gives. Learnt from tempo runs, 0–12.
     */
    val tempoSoft: Int = 0,
    /** Run/walk sessions in a row that were not completed comfortably. */
    val walkFails: Int = 0,
    val lastVdotUpDay: Long? = null,
    val lastRunDay: Long? = null,
    /** Runs of the last 30 days: the long-run cap is based on them. */
    val recent: List<RunLog> = emptyList(),
    /** Easy runs still to do after a long break before intervals and tempo come back. */
    val returnEasy: Int = 0,
    /** Stage in the race-pace ladder of a middle-distance goal. */
    val goalStage: Int = 0,
    /** Race-pace sessions in a row that were not completed as planned. */
    val goalFails: Int = 0,
    /** The race-pace ladder is done: the next quality session is the time trial. */
    val testSoon: Boolean = false,
    /** Hard sessions in a row run in full ahead of their paces (well quicker, or on them with effort to spare): three call a time trial. */
    val qualityAhead: Int = 0,
    /** A test was run on this day but could not be counted (no time): the next one waits two weeks, not one. */
    val lastTrialTryDay: Long? = null,
    /** Efforts given to the last easy runs done in full (at most 8): the runner's own scale, see [rpeOffset]. */
    val easyRpes: List<Int> = emptyList(),
    /**
     * The last easy runs with a measured pace against the easy range (at most 6): +1 well quicker and light, −1 well
     * slower and hard, 0 in it. Without a recent trial they move the paces ([EASY_SAYS]).
     */
    val easySigns: List<Int> = emptyList(),
    /**
     * The day race pace was put off from a hard day too soon after the legs to a fresher one later in the week (the next
     * type of the cycle was run instead): race pace is owed until it is run.
     */
    val goalDeferredOn: Long? = null,
    /**
     * The fitness the app goes by while no trial has been counted: from the goal at first ([RunPlan.estimated]), then
     * moved by how the runs go ([RunOutcome.EstChanged]). Every pace comes from it, so a goal far away is trained toward at
     * the runner's own level, and no trial is asked for until training says the goal is near (Denis, 23 November: «зачем
     * ставить контрольный? почему нельзя просто продолжать бегать чуть в более легком режиме но почти на пределе?»).
     * Null with a trial counted ([vdot]) or no goal to guess from.
     */
    val estVdot: Double? = null,
    /** The day the estimate last went up: not more often than once in two weeks. */
    val estUpDay: Long? = null,
    /** No trial is due before this day: the runner put it off («не хочу контрольный»). */
    val noTrialUntil: Long? = null,
    /**
     * The pace (s a km) the runner has held in the race-pace ladder's shortest reps (200 m for 1500 m: a lap of a small
     * loop; longer reps: [goalRepPaces]), done with the effort asked or less: the reps are never slower than that, and
     * never quicker than the goal ([RunPlan.stepPace]). Fitness, measured or estimated, is about endurance; a runner can
     * be quicker over a lap than it says (Denis, 16 November: 6 × 205 m at 42.6–44.4 s, effort 6 — the goal's 45 s and
     * quicker, while his estimate gave 52.6 s). Slower by a step after two misses in a row and after a break. Null: the
     * step from the fitness alone.
     */
    val goalRepPace: Double? = null,
    /**
     * The same for the ladder's longer reps, by their length as the ladder names it ([RunPlan.repLength]: 400, 600, 800 m
     * for 1500 m; 1000 — the race in two parts). A step goes by the pace held in reps as long as its own or longer: the
     * quick laps of one-lap reps say only that the runner is quick, not that he holds that pace over 3 laps (seen: two
     * misses of 3 × 3 laps eased their pace, the next 5 × 2 laps run quickly put the one-lap pace back on them, and they
     * failed again — a loop).
     */
    val goalRepPaces: Map<Int, Double> = emptyMap(),
) {
    val canRunContinuously: Boolean get() = walkStage < 0

    /** The fitness the paces come from: measured by a trial, or the estimate until one is. */
    val fitness: Double? get() = vdot ?: estVdot

    /** Going by an estimate: no trial counted yet. */
    val estimated: Boolean get() = vdot == null && estVdot != null

    /**
     * How far the runner's effort scale runs above the plan's: easy runs are meant to feel light to moderate (up to 5); a
     * median of 6–7 over five or more of them is a scale that rates everything harder (seen in half-year simulations: such
     * a runner never got past the first rungs while holding every pace). 0–2, added to the plan's effort thresholds.
     */
    val rpeOffset: Int get() = easyRpes.takeIf { it.size >= 5 }?.sorted()?.let { it[it.size / 2] - 5 }?.coerceIn(0, 2) ?: 0

    /** Longest run in the 30 days before [day], minutes; null if there was none. */
    fun longestRecent(day: Long): Int? = recent.filter { it.day in (day - 30) until day }.maxOfOrNull { it.minutes }
}

@Serializable
data class RunLog(
    val day: Long,
    /** The whole session: warm-up, rests and cool-down included. */
    val minutes: Int,
    /**
     * The running in it, minutes: the runs and the reps (RUN and WORK), not a hard session's warm-up, rests and cool-down
     * ([RunPlan.runningMinutes]). Null in logs kept before it was counted: the whole session stands for it then.
     */
    val running: Int? = null,
) {
    /** What the week's running counts of this run ([RunPlan.weekCap]). */
    val runMinutes: Int get() = running ?: minutes
}

@Serializable
data class RunResult(
    /** Share of the planned work that was done, 0..1. */
    val completed: Double,
    /** Session effort, CR-10. */
    val rpe: Int,
    val durationSec: Int,
    val distanceM: Double? = null,
    /** Average pace over WORK segments, if GPS measured it. */
    val workPace: Double? = null,
    /** Distance covered during WORK segments, for tests. */
    val workDistanceM: Double? = null,
    /** Seconds actually spent in WORK and RUN segments; null for results saved before it was recorded. */
    val workSec: Int? = null,
    /** Laps from the lap button on a track, in order. */
    val laps: List<Lap>? = null,
    /** Work reps done, as the runner reports it (more than planned counts). */
    val reps: Int? = null,
    /** A time trial over [RunWorkout.testMeters]: its time in seconds. */
    val testSeconds: Int? = null,
    /** Steps a minute while running, from the phone's step counter. */
    val cadence: Int? = null,
    /** Seconds of each whole kilometre (active time), as the voice said them. */
    val kmSplits: List<Int>? = null,
    /** On a stadium: the loop's length as GPS fitted it in this run (or an earlier one), metres; null when not measured. */
    val loopM: Double? = null,
    /** On a stadium: the distance GPS measured (the bends cut), when the laps' distance was shown instead; null if none. */
    val gpsM: Double? = null,
    /** The warm-up's strides started (see [RunTimeline.stride]): each one's speed by GPS, m/s (null where not measured). */
    val strideSpeeds: List<Double?>? = null,
    /** The warm-up's jog before the strides, m/s: what they are measured against. */
    val jogSpeed: Double? = null,
)

/** A run timed without the phone (a watch, a stopwatch) and entered by hand. */
data class ManualRun(
    /** The whole session, warm-up and cool-down included; null — as planned (a trial's time is often all one has). */
    val durationSec: Int?,
    val distanceM: Double?,
    /** Session effort, CR-10. */
    val rpe: Int,
    /** Work reps done, in a session of reps. */
    val reps: Int? = null,
    /** The reps' pace, seconds a km (on a stadium from the average lap). */
    val workPace: Double? = null,
    /** A time trial over [RunWorkout.testMeters]: its time, seconds. */
    val testSeconds: Int? = null,
    /** The 12-minute test: metres run in it. */
    val testDistanceM: Double? = null,
)

@Serializable
sealed interface RunOutcome {
    @Serializable data class StageUp(val ladder: String, val to: Int) : RunOutcome
    @Serializable data class StageRepeat(val ladder: String) : RunOutcome
    @Serializable data class StageDown(val ladder: String, val to: Int) : RunOutcome
    @Serializable data object Graduated : RunOutcome
    @Serializable data class Longer(val minutes: Int) : RunOutcome
    @Serializable data class Shorter(val minutes: Int) : RunOutcome
    @Serializable data class Same(val minutes: Int) : RunOutcome
    /** [easier]: raised because the hard runs went on pace with effort to spare, not by a test. */
    @Serializable data class VdotChanged(val from: Double?, val to: Double, val easier: Boolean = false) : RunOutcome
    /** A time trial: [meters] in [seconds]. */
    @Serializable data class TestTime(val meters: Int, val seconds: Int) : RunOutcome
    /** The race-pace ladder is done: the time trial comes next. */
    @Serializable data object TestNext : RunOutcome
    /** Hard sessions ahead of their paces, three in a row: the next hard day is a time trial. */
    @Serializable data object AheadOfPaces : RunOutcome
    /** The time trial was as fast as the goal or faster. */
    @Serializable data object GoalReached : RunOutcome
    /** The top of the race-pace ladder went well, but the last time trial was less than three weeks ago. */
    @Serializable data object TrialLater : RunOutcome
    /** The reps were faster than the goal pace: a step, not two — even laps are the skill. */
    @Serializable data object TooFast : RunOutcome
    /** The reps were measured more than 3 % slower than their target: the step is not taken, whatever the effort. */
    @Serializable data object TooSlow : RunOutcome
    /** Five of the last six easy runs said the paces are off (no trial lately): VDOT moved by one. */
    @Serializable data class EasyPaces(val from: Double, val to: Double) : RunOutcome
    /** Race-pace reps more than 3 % faster than the goal: the step is run again at the goal's lap time. */
    @Serializable data object TooFastRepeat : RunOutcome
    /** Tempo not held at a hard effort: its pace is [seconds] a km softer for this runner from now on (or firmer again). */
    @Serializable data class TempoPace(val seconds: Int, val softer: Boolean) : RunOutcome
    /** The runner rates easy runs harder than they are meant to feel: the effort thresholds are [offset] higher. */
    @Serializable data class OwnScale(val offset: Int) : RunOutcome
    /** No lap pressed and no GPS: the pace was not measured, only the effort and the reps decided. */
    @Serializable data object PaceUnknown : RunOutcome
    /** A lighter week's shorter session: done, and the ladder stays where it is. */
    @Serializable data class LighterWeek(val ladder: String) : RunOutcome
    /** The top of a ladder went well again: it stays there. */
    @Serializable data class LadderTop(val ladder: String) : RunOutcome
    /**
     * The next tempo run is [reps] × [workSec]: [split] — the last one was far too hard, the same minutes in reps half as
     * long; else the shorter reps went well and grow towards the plan's step (or are it again).
     */
    @Serializable data class TempoNext(val reps: Int, val workSec: Int, val split: Boolean = false) : RunOutcome
    /** The estimate of the fitness (no trial counted) moved by how the runs go: the paces with it. */
    @Serializable data class EstChanged(val from: Double, val to: Double) : RunOutcome
    /** By training the estimate has come within a point of the goal's fitness: the trial is worth running now. */
    @Serializable data object TrialEarned : RunOutcome
    /** Race-pace reps held with effort to spare at a quicker pace than the step: the step comes to it (s a km). */
    @Serializable data class RepPace(val from: Double, val to: Double) : RunOutcome
    /**
     * The loop GPS measured ([loop] m) is clearly shorter than the lap length set ([set] m): the laps were read on the loop
     * as run — the trial's distance, the reps' pace. Setting the lap length makes the plans right too.
     */
    @Serializable data class LoopShorter(val loop: Int, val set: Int) : RunOutcome
    /** A test result far from the current form ([vdot] it would give): more likely a typo, not counted. */
    @Serializable data class TestDoubtful(val vdot: Double) : RunOutcome
    /** A test that gave no time (laps missed, nothing typed): not counted. */
    @Serializable data object TestNotCounted : RunOutcome
    /**
     * A time trial counted on the stretch held from its start ([meters] in [seconds], see [RunPlan.held]): [finished] —
     * run to the end, but slower after it (the whole time would give a lower fitness); else stopped, walked or a lap not
     * trusted, and a full trial comes again. [kept]: the fitness known was higher, and a part never lowers it. [capped]:
     * run to the end far slower than the stretch, the fitness is below the stretch's — 3 above the whole time's at most.
     */
    @Serializable data class TestFromPart(val meters: Int, val seconds: Int, val finished: Boolean, val kept: Boolean = false, val capped: Boolean = false) : RunOutcome
    /** A time trial held from its start only [meters]: under half of it or three minutes, too little to count. */
    @Serializable data class TestStopped(val meters: Int) : RunOutcome
}

data class RunAfter(val state: RunState, val outcomes: List<RunOutcome>)

/** Caps for easy and long runs by how much the person runs. */
@Serializable
data class RunCaps(val easyMax: Int, val longMax: Int)

/**
 * A run of the coach's own making, as asked for («хочу сегодня 8 по 200»): [reps] of [workSec] seconds or [workMeters]
 * metres at [pace], [restSec] of jog between them, easy running before and after.
 */
data class CustomRun(
    val reps: Int,
    val workSec: Int?,
    val workMeters: Int?,
    val pace: Pace,
    val restSec: Int,
    val warmupMin: Int,
    val cooldownMin: Int,
) {
    /** What a rep is run at: the app's own paces for the runner's fitness ([FREE]: by feel, no pace). */
    enum class Pace(val ru: String, val rpe: Int) {
        EASY("лёгкий темп", 4), TEMPO("темп «тяжело, но держишь»", 7), INTERVAL("темп интервалов", 8), RACE("темп забега", 8), FAST("быстрый темп", 8), FREE("по ощущениям", 6);

        val hard: Boolean get() = this != EASY
    }
}

/** A tempo run's reps: [reps] × [workSec] at threshold, [restSec] of easy jog between them. */
@Serializable
data class TempoShape(val reps: Int, val workSec: Int, val restSec: Int) {
    /** Seconds at tempo in all. */
    val total: Int get() = reps * workSec
}

object RunPlan {
    private const val WALK_WARM = 5 * 60

    private data class WalkStep(val blocks: List<Pair<Int, Int>>)

    private fun alternate(times: Int, run: Int, walk: Int, lastRun: Int? = null): WalkStep {
        val b = MutableList(times) { run to walk }
        if (lastRun != null) b += lastRun to 0 else b[b.lastIndex] = run to 0
        return WalkStep(b)
    }

    private fun seq(vararg minutes: Double): WalkStep {
        // run, walk, run, walk, … in minutes
        val b = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < minutes.size) {
            val run = (minutes[i] * 60).roundToInt()
            val walk = if (i + 1 < minutes.size) (minutes[i + 1] * 60).roundToInt() else 0
            b += run to walk
            i += 2
        }
        return WalkStep(b)
    }

    /**
     * Run/walk ladder modelled on the NHS Couch to 5K plan: nine weeks, three runs a week, each with a 5-minute
     * walk before and after. One stage = one run; the last stage is 30 minutes non-stop.
     */
    private val WALK_LADDER: List<WalkStep> = buildList {
        repeat(3) { add(alternate(7, 60, 90, lastRun = 60)) }
        repeat(3) { add(alternate(5, 90, 120, lastRun = 90)) }
        repeat(3) { add(seq(1.5, 1.5, 3.0, 3.0, 1.5, 1.5, 3.0)) }
        repeat(3) { add(seq(3.0, 1.5, 5.0, 2.5, 3.0, 1.5, 5.0)) }
        add(seq(5.0, 3.0, 5.0, 3.0, 5.0)); add(seq(8.0, 5.0, 8.0)); add(seq(20.0))
        add(seq(5.0, 3.0, 8.0, 3.0, 5.0)); add(seq(10.0, 3.0, 10.0)); add(seq(25.0))
        repeat(3) { add(seq(25.0)) }
        repeat(3) { add(seq(28.0)) }
        repeat(3) { add(seq(30.0)) }
    }

    val walkLadderSize: Int get() = WALK_LADDER.size

    /** Interval ladder: (repeats, work seconds, recovery seconds) at interval pace. */
    private val INTERVALS = listOf(
        Triple(5, 120, 120), Triple(6, 120, 120), Triple(5, 180, 120), Triple(6, 180, 120),
        Triple(4, 240, 180), Triple(5, 240, 180), Triple(4, 300, 180), Triple(5, 300, 180),
    )

    /** Where a rep of a fractional lap count starts: "с середины круга", "за четверть круга до линии". */
    fun startWords(fraction: Double): String = when {
        abs(fraction - 0.5) < 0.01 -> "с середины круга"
        abs(fraction - 0.25) < 0.01 -> "за четверть круга до линии"
        else -> "за три четверти круга до линии"
    }

    /** One step of the race-pace ladder: rep lengths in metres (for 1500 m) and the recovery between them. */
    private data class GoalStep(val reps: List<Int>, val rest: Int)

    /**
     * Race-pace ladder for a middle-distance goal, written for 1500 m and scaled to the goal distance: short reps at the
     * goal pace with full recovery first (the pace is learnt where it is easy to hold), then more of them, then less
     * rest, then longer reps, and finally the race in two parts. Goal-pace volume stays at 0.8–1.6 race distances — also on
 * a track, where reps are whole laps: the number of reps follows the real rep length ([trackReps]).
     */
    private val GOAL = listOf(
        GoalStep(List(6) { 200 }, 90),
        GoalStep(List(8) { 200 }, 90),
        GoalStep(List(8) { 200 }, 60),
        GoalStep(List(5) { 400 }, 120),
        GoalStep(List(6) { 400 }, 120),
        GoalStep(List(4) { 600 }, 180),
        GoalStep(List(3) { 600 }, 120),
        GoalStep(List(2) { 800 }, 240),
        GoalStep(listOf(1000, 500), 60),
    )

    val goalLadderSize: Int get() = GOAL.size

    /** Where the ladder starts again after a trial short of the goal: 5 × 400 m. */
    private const val GOAL_RESTART = 3

    /** The most the threshold pace is softened for a runner, s/km (see [RunState.tempoSoft]). */
    private const val TEMPO_SOFT_MAX = 12
    val intervalLadderSize: Int get() = INTERVALS.size
    val tempoLadderSize: Int get() = TEMPO.size

    /**
     * A race-pace rep of [meters] in laps of [lapMeters], always longer than a shorter rep of the ladder gets there: on a
     * 230 m lap 600 m and 800 m both round to 3 laps, and "2 × 800" would be easier than "3 × 600" before it — the
     * longer one takes the next lap (4 laps = 920 m). Null: the rep stays timed.
     */
    private fun goalRepLaps(meters: Double, scale: Double, lapMeters: Int): Double? {
        var prev = 0.0
        for (d in GOAL.dropLast(1).flatMap { it.reps }.distinct().sorted()) {
            val m = d * scale
            // The next lap too far from the rep (a long loop): the rep stays timed rather than half as long again.
            val laps = Track.repLaps(m, lapMeters)?.let { if (it <= prev) prev + 1.0 else it }?.takeIf { abs(it * lapMeters - m) <= 0.25 * m }
            if (abs(m - meters) < 0.5) return laps
            if (laps != null) prev = laps
        }
        return Track.repLaps(meters, lapMeters)
    }

    /**
     * Reps of [repMeters] for a session meant as [meant] metres: the nearest count, never more than 1.6 race distances
     * ([raceMeters]) and at least one.
     */
    fun trackReps(meant: Double, repMeters: Double, raceMeters: Double): Int {
        val near = (meant / repMeters).roundToInt()
        val cap = (1.6 * raceMeters / repMeters + 1e-6).toInt()
        return near.coerceAtMost(cap).coerceAtLeast(1)
    }

    /** The rest of a race-pace step of [rest] s between reps of [meters] m run as [lapsM] m of whole laps: it grows with the rep. */
    private fun restOnLaps(rest: Int, lapsM: Double, meters: Double): Int = (((rest * lapsM / meters) / 15).roundToInt() * 15).coerceAtLeast(rest)

    /**
     * How many reps the full step [stage] has on a track of [lapMeters]: as many whole-lap reps as make its volume
     * ([trackReps]) — one more when that would make it the session of the step before it (on 205 m 5 × 400 and 6 × 400
     * both came out 5 × 2 laps under the cap of 1.6 races, and «шаг 5 из 9» was the step just passed: a step is a step
     * up, by a rep over the cap). Null for the race in two parts and for reps no lap count fits (they stay timed).
     */
    private fun lapsCount(stage: Int, scale: Double, lapMeters: Int, raceMeters: Double): Int? {
        // The step's reps on this track: whole laps a rep, how many, the rest between them.
        fun onLaps(i: Int): Triple<Double, Int, Int>? {
            if (i !in 0 until GOAL.lastIndex) return null
            val step = GOAL[i]
            val m = step.reps.first() * scale
            val l = goalRepLaps(m, scale, lapMeters) ?: return null
            return Triple(l, trackReps(step.reps.size * m, l * lapMeters, raceMeters), restOnLaps(step.rest, l * lapMeters, m))
        }
        val now = onLaps(stage) ?: return null
        return if (onLaps(stage - 1) == now) now.second + 1 else now.second
    }

    /** The length the reps of the race-pace ladder's step [stage] go by, as the ladder is written for 1500 m: 200–800 m, 1000 — the race in two parts. */
    fun repLength(stage: Int): Int = GOAL[stage.coerceIn(0, GOAL.lastIndex)].reps.first()

    /** The paces held in race-pace reps by their length ([RunState.goalRepPace] the shortest reps', [RunState.goalRepPaces] the longer ones'). */
    private fun heldPaces(state: RunState): Map<Int, Double> =
        state.goalRepPaces + (state.goalRepPace?.let { mapOf(repLength(0) to it) } ?: emptyMap())

    /**
     * The pace for race-pace reps of the step [stage], s a km, from what the runner has held: the quickest held in reps as
     * long or longer (held over 3 laps, a pace holds over 2). Reps of a length never run yet take the pace of the longest
     * shorter ones (Denis, 23.11: race-pace reps at the pace he holds, not slower): a miss on them then eases that length
     * alone ([decide]), so a quick session of shorter reps does not bring the quicker pace back (the audit of 24.11). Null
     * when nothing is held.
     */
    fun heldRepPace(state: RunState, stage: Int = state.goalStage): Double? {
        val held = heldPaces(state)
        val length = repLength(stage)
        return held.filterKeys { it >= length }.values.minOrNull()
            ?: held.filterKeys { it < length }.maxByOrNull { it.key }?.value
    }

    /** [state] with [pace] held in reps of [length] (null: none held). */
    private fun withHeld(state: RunState, length: Int, pace: Double?): RunState =
        if (length <= repLength(0)) state.copy(goalRepPace = pace)
        else state.copy(goalRepPaces = if (pace == null) state.goalRepPaces - length else state.goalRepPaces + (length to pace))

    /** Paces (s/km) a time trial can believably be run at: faster than 2:10 or slower than 15:00 is a typo. */
    val PLAUSIBLE_PACE = 130.0..900.0

    /** Fitness (VDOT) people run at: below and above is a typo or a GPS failure. */
    const val MIN_VDOT = 20.0
    const val MAX_VDOT = 85.0

    /**
     * A test result to count: within what people run, and within 8 points of the current form when it is known — a
     * minute off in a typed time ("3:52" for "5:52") is 10 points or more; a real month of training is one or two.
     */
    fun believable(vdot: Double, current: Double?): Boolean =
        vdot in MIN_VDOT..MAX_VDOT && (current == null || abs(vdot - current) <= 8.0)

    /**
     * A trial's typed time while the app goes by an estimate ([RunState.estimated]): with no trial counted [believable]
     * had nothing to hold it against, and a minute off («4:52» for «5:52», 1435 m: 53 for 43) jumped from the estimate
     * past the goal — «цель достигнута», and every pace from it. Within 8 of the estimate, as of a fitness known, or up to
     * 3 above the goal's own (the estimate starts 8 below the goal, where the runner may well be already).
     */
    private fun believableByEstimate(vdot: Double, state: RunState, goal: RunGoal?): Boolean {
        val est = state.estVdot?.takeIf { state.vdot == null } ?: return true
        val pastGoal = goal?.let { Vdot.fromRace(it.meters.toDouble(), it.seconds.toDouble()) + 3.0 } ?: 0.0
        return vdot <= max(est + 8.0, pastGoal)
    }

    /** A stretch of a time trial held from its start: [meters] in [seconds], and the fitness ([vdot]) it gives. */
    data class Held(val meters: Double, val seconds: Double, val vdot: Double)

    /**
     * What the runner held of a time trial [w] from its start: of the laps from the start up to the first one that is no
     * lap (under half or over 1.7 times its planned time — a press doubled or missed, a walk, a stop), the stretch ending
     * on a lap that gives the highest fitness, at least half the trial and three minutes (shorter efforts are no measure
     * of a race). On the road, without laps, the distance GPS measured in the trial's time. Running at a pace until it
     * cannot be held is what a race measures (Daniels' model: the share of VO2max held for that long), so a trial run off
     * too fast and faded, or stopped, still says how fit the runner is; null when nothing of that length was held.
     */
    fun held(w: RunWorkout, r: RunResult): Held? {
        val tm = w.testMeters ?: return null
        val lapM = w.lapMeters
        // By distance, not by time: a floor in seconds let a stretch run 2 % faster fall under it, and the longer one with
        // the fade in it counted instead — faster, a lower fitness.
        fun counted(m: Double, sec: Double) = m >= max(tm / 2.0, HELD_MIN_M) - 1e-6 && sec / (m / 1000) in PLAUSIBLE_PACE
        if (lapM == null) {
            val d = r.workDistanceM ?: return null
            val sec = (r.workSec ?: return null).toDouble()
            return if (d <= tm * 1.05 && counted(d, sec)) Held(d, sec, Vdot.fromRace(d, sec)) else null
        }
        var m = 0.0
        var sec = 0.0
        var best: Held? = null
        heldLaps(w, r).forEach { l ->
            m += l.part * lapM
            sec += l.seconds
            if (counted(m, sec)) Vdot.fromRace(m, sec).let { v -> if (best?.let { v > it.vdot } != false) best = Held(m, sec, v) }
        }
        return best
    }

    /** The trial's laps from its start, up to the first that is no lap (see [held]). */
    fun heldLaps(w: RunWorkout, r: RunResult): List<Lap> = heldLaps(w, r.laps)

    private fun heldLaps(w: RunWorkout, all: List<Lap>?): List<Lap> {
        val lapSec = w.segments.firstOrNull { it.kind == SegmentKind.WORK }?.lapSec ?: return emptyList()
        val laps = all.orEmpty().filter { it.rep == 1 }
        return laps.withIndex().takeWhile { (i, l) -> l.number == i + 1 && l.seconds / (lapSec * l.part) in 0.5..1.7 }.map { it.value }
    }

    /**
     * A time trial's time from its [laps], as the timer reads it: they close the trial's last lap and each is a lap (see
     * [heldLaps]). Null when the trial was stopped before its end or a lap is not trusted (a press missed, a walk): the
     * laps' sum is then no time of the trial, and what was held from the start is the measure ([held]).
     */
    fun trialLapsTime(w: RunWorkout, laps: List<Lap>?): Int? {
        if (w.testMeters == null) return null
        val reps = laps.orEmpty().filter { it.rep == 1 }
        if (reps.none { it.closesRep } || heldLaps(w, laps).size != reps.size) return null
        return reps.sumOf { it.seconds }.roundToInt().takeIf { it > 0 }
    }

    /**
     * The time a trial's [laps] took to its end, a lap walked (far slower than a lap, see [heldLaps]) or a press missed
     * and all: no press doubled, the last lap closing the trial. With a lap walked it is not the trial's time — a walk is
     * no race — but how far the runner fell back after the stretch held.
     */
    private fun lapsToTheEnd(w: RunWorkout, laps: List<Lap>?): Double? {
        val reps = lapsRun(w, laps) ?: return null
        if (reps.none { it.closesRep }) return null
        return reps.sumOf { it.seconds }.takeIf { it > 0 }
    }

    /**
     * A trial stopped before its end ([laps] not closing it): its time to the end had the rest been run at the pace of
     * the last lap run — the pace the runner had come down to. Null without laps, or with a press doubled.
     */
    private fun projectedEnd(w: RunWorkout, laps: List<Lap>?): Double? {
        val lapM = w.lapMeters ?: return null
        val tm = w.testMeters ?: return null
        val reps = lapsRun(w, laps)?.takeIf { it.isNotEmpty() && it.none { l -> l.closesRep } } ?: return null
        val last = reps.last().takeIf { it.seconds > 0 && it.part > 0 } ?: return null
        val rest = tm - reps.sumOf { it.part } * lapM
        return reps.sumOf { it.seconds } + max(0.0, rest) / (last.part * lapM) * last.seconds
    }

    /** A trial's laps in order with no press doubled (a lap under half its time), or null. */
    private fun lapsRun(w: RunWorkout, laps: List<Lap>?): List<Lap>? {
        val lapSec = w.segments.firstOrNull { it.kind == SegmentKind.WORK }?.lapSec ?: return null
        val reps = laps.orEmpty().filter { it.rep == 1 }
        return reps.takeIf { reps.withIndex().none { (i, l) -> l.number != i + 1 || l.seconds / (lapSec * l.part) < 0.5 } }
    }

    /** The shortest stretch of a time trial that counts as a measure of it, besides half of it (see [held]). */
    const val HELD_MIN_M = 600.0

    /** How far above the whole time a stretch held may put a trial run to its end and faded (see [held]). */
    const val PART_OVER_WHOLE = 3.0

    /** A hard session's measured pace under this share of its target is well quicker than it (see [RunState.qualityAhead]). */
    const val FASTER = 0.97

    /** Tempo ladder: (repeats, work seconds, recovery seconds) at threshold pace. */
    private val TEMPO = listOf(
        Triple(3, 300, 60), Triple(4, 300, 60), Triple(3, 480, 120), Triple(2, 720, 120),
        Triple(1, 1200, 0), Triple(3, 600, 60), Triple(1, 1500, 0), Triple(2, 900, 120),
    )

    fun paces(state: RunState): Paces? = state.fitness?.let { PaceTable.forVdot(it) }

    /**
     * The state with a fitness to go by before any trial ([RunState.estVdot]): from the goal ([provisionalVdot]), a point
     * lower for each hard run in a row already missed (two at most) — they say the start was too high. Unchanged with a
     * trial counted, an estimate already there, or no goal to guess from.
     */
    fun estimated(state: RunState, goal: RunGoal?): RunState {
        if (state.vdot != null || state.estVdot != null || goal == null) return state
        return state.copy(estVdot = max(MIN_VDOT, provisionalVdot(goal) - min(2, state.qualityFails)))
    }

    /**
     * The estimate moved [by] points: up no more often than once in two weeks (form grows slowly, and one good day is no
     * proof), down as often as the runs say; never past the goal's own fitness. Within a point of it the trial is earned.
     */
    private fun moveEst(s: RunState, by: Double, day: Long, goal: RunGoal?, out: MutableList<RunOutcome>): RunState {
        val from = s.estVdot ?: return s
        if (s.vdot != null) return s
        if (by > 0 && s.estUpDay?.let { day - it < 14 } == true) return s
        val top = goal?.let { Vdot.fromRace(it.meters.toDouble(), it.seconds.toDouble()) } ?: MAX_VDOT
        val to = ((from + by).coerceIn(MIN_VDOT, max(MIN_VDOT, top)) * 2).roundToInt() / 2.0
        if (to == from) return s
        out += RunOutcome.EstChanged(from, to)
        var n = s.copy(estVdot = to, estUpDay = if (by > 0) day else s.estUpDay)
        if (goal != null && by > 0 && to >= top - 1.0 && !n.testSoon) {
            n = n.copy(testSoon = true)
            out += RunOutcome.TrialEarned
        }
        return n
    }

    /**
     * Before the first test of a race goal: a fitness well below the goal's (8 points, about a sixth slower over 1500 m)
     * — where the estimate starts ([estimated]): the first runs are where a fast starter overdoes it.
     */
    fun provisionalVdot(goal: RunGoal): Double =
        ((Vdot.fromRace(goal.meters.toDouble(), goal.seconds.toDouble()) - 8) * 2).roundToInt() / 2.0

    /** An easy or long run paced from [provisionalVdot]: the limit is said to be approximate. */
    fun provisional(w: RunWorkout): RunWorkout =
        w.copy(note = w.note + " Темп примерный.")

    fun runWalk(state: RunState): RunWorkout {
        val stage = state.walkStage.coerceIn(0, WALK_LADDER.lastIndex)
        val segs = mutableListOf(Segment(SegmentKind.WARMUP, WALK_WARM))
        for ((run, walk) in WALK_LADDER[stage].blocks) {
            segs += Segment(SegmentKind.RUN, run, paces(state)?.easy)
            if (walk > 0) segs += Segment(SegmentKind.WALK, walk)
        }
        segs += Segment(SegmentKind.COOLDOWN, WALK_WARM)
        val note = "Беги спокойно, чтобы получалось болтать. Иди быстрым шагом. Разминка и заминка — ходьба."
        val title = "Бег с ходьбой · неделя ${stage / 3 + 1}, тренировка ${stage % 3 + 1}"
        return RunWorkout(RunType.RUN_WALK, title, segs, note, targetRpe = 6, stage = stage)
    }

    /**
     * An easy run; [strides] short accelerations after it (4–6 of about 10 s, on a straight): speed and form at no cost of
     * recovery, twice a week for runners with hard runs (Daniels' "strides"). 10 s, not 80 m: the straight of a small loop
     * is about 50 m (Denis's 205 m, fitted by GPS), and a stride into its bend loads the inner leg most.
     */
    fun easy(state: RunState, cutback: Boolean = false, day: Long? = null, strides: Int = 0): RunWorkout {
        val cap = singleRunCap(state, day)
        val min = scaled(state.easyMinutes, cutback).let { if (cap != null) minOf(it, maxOf(15, cap)) else it }
        val note = "Спокойно, чтобы получалось болтать." + if (strides > 0) {
            " В конце $strides ${Ru.plural(strides, "ускорение", "ускорения", "ускорений")} секунд по 10, по прямой. " +
                "Быстро, но не в полную силу. Между ними около минуты шагом."
        } else ""
        return RunWorkout(
            RunType.EASY, "Лёгкий бег · $min мин" + if (strides > 0) " + $strides ${Ru.plural(strides, "ускорение", "ускорения", "ускорений")}" else "",
            listOf(Segment(SegmentKind.RUN, min * 60, paces(state)?.easy)), note, targetRpe = 4,
        )
    }

    /** Strides after an easy run: 4 to start with and in a lighter week, 6 once running is regular. */
    fun strides(state: RunState, cutback: Boolean): Int = if (cutback || state.runs < 8) 4 else 6

    /** The warm-up's strides as the clock runs them before fast running ([RunTimeline.stridesFrom]), for the notes. */
    private val STRIDES_NOTE: String get() =
        "В конце разминки ${RunTimeline.STRIDES} ${Ru.plural(RunTimeline.STRIDES, "ускорение", "ускорения", "ускорений")} по " +
            "${RunTimeline.STRIDE_SEC} с, между ними ${RunTimeline.STRIDE_JOG} с трусцы."

    /** The goal race on its day: run as a time trial (its time is the fitness), said as the race. */
    fun race(state: RunState, goal: RunGoal): RunWorkout {
        val t = goalTest(state, goal, null)
        val name = if (goal.meters < 1000 || goal.meters % 1000 != 0) "${goal.meters} м" else "${goal.meters / 1000} км"
        // Run at the goal's pace, as the note says: the clock, the voice and the pace alarm with it. Paced from a fitness
        // below the goal (as a trial is), a race run at the goal heard «Быстрее цели, чуть сбавь» every minute and «Осталась
        // минута» a minute late.
        val pace = goal.pace
        val segments = t.segments.map { s ->
            if (s.kind == SegmentKind.WORK) s.copy(seconds = goal.seconds, pace = PaceRange((pace - 3).roundToInt(), (pace + 3).roundToInt())) else s
        }
        return t.copy(
            segments = segments,
            title = "Забег · $name",
            note = "Твой забег: $name за ${Ru.clock(goal.seconds)}. Разминка 12 минут. $STRIDES_NOTE Первую половину беги " +
                "ровно, в темпе цели. Вторую — на сколько хватит сил. После финиша впиши время с табло.",
        )
    }

    /**
     * Upper limit for any single run: 110 % of the longest run in the previous 30 days
     * (Frandsen et al., BJSM 2025: bigger single-session spikes were linked with more overuse injuries — an observed
     * link, hard sessions counted too). Easy and long runs, and hard training sessions as a whole ([hardSession], MD-3).
     */
    fun singleRunCap(state: RunState, day: Long?): Int? {
        if (day == null) return null
        val longest = state.longestRecent(day) ?: return state.easyMinutes + 10
        return (longest * 1.10).toInt()
    }

    fun long(state: RunState, cutback: Boolean = false, day: Long? = null): RunWorkout {
        val cap = singleRunCap(state, day)
        val min = scaled(state.longMinutes, cutback).let { if (cap != null) minOf(it, maxOf(15, cap)) else it }
        return RunWorkout(
            RunType.LONG, "Длинная пробежка · $min мин",
            listOf(Segment(SegmentKind.RUN, min * 60, paces(state)?.easy)),
            "Ровно и спокойно, как лёгкий бег. Пробеги всё время, скорость не важна.", targetRpe = 5,
        )
    }

    /**
     * The running of an average recent week, minutes (the last two weeks), null with fewer than three runs known. The
     * hard work of one session is kept to a share of it: intervals (VO2max) to about 10 % and tempo to about 10 % of the
     * week, at least 12 and 20 minutes — the usual limits for a small weekly volume (Daniels: 8 % and 10 %, made milder
     * here), so the top of a ladder does not outgrow the running around it.
     */
    fun weekMinutes(state: RunState): Int? {
        if (state.recent.size < 3) return null
        val last = state.recent.maxOf { it.day }
        return state.recent.filter { it.day > last - 14 }.sumOf { it.minutes } / 2
    }

    /**
     * The hard work of one session at most, seconds: [floor] or about 10 % of the week ([weekMinutes]). Built for a [day]
     * of the plan with fewer than three runs known, the plan's own week ([ownWeek]) is the week, not no limit at all
     * (hard-session-spike / MD-3 of the research of 24 November, approved by the owner on 25 November: a full step of
     * 45–53 minutes came with no limit then). Without a day (a ladder's step shown as written) as before.
     */
    private fun hardCap(state: RunState, floor: Int, day: Long? = null): Int? =
        (weekMinutes(state) ?: day?.let { ownWeek(state) })?.let { max(floor, (it * 60 * 0.10).roundToInt()) }

    /** The warm-up and cool-down of intervals and tempo, minutes. */
    private const val HARD_WARM = 10
    private const val HARD_COOL = 10

    /** The warm-up and cool-down of race-pace reps, minutes. */
    private const val GOAL_WARM = 12
    private const val GOAL_COOL = 8

    /** The shortest warm-up a hard session is cut to, minutes: it still holds its strides ([RunTimeline.stridesFrom] needs 8). */
    private const val WARM_FLOOR = 8

    /** The shortest cool-down a hard session is cut to, minutes. */
    private const val COOL_FLOOR = 5

    /** A session [fit] within a cap: [workout], built with [warm] and [cool] minutes and [option]; [cut] — shorter than it was. */
    private class Fitted<T>(val workout: RunWorkout, val warm: Int, val cool: Int, val option: T, val cut: Boolean)

    /**
     * The session [build] makes, as a whole no longer than [cap] minutes (null: no cap): as it is when it fits; else the
     * cool-down shorter first, then the warm-up (to [COOL_FLOOR] and [WARM_FLOOR], or as they are when shorter), then the
     * next of [options] (the session's own first, then less of it), the minutes left given back to the warm-up first.
     * Null when even the least of the last option is over the cap.
     */
    private fun <T> fit(cap: Int?, warm: Int, cool: Int, options: List<T>, build: (Int, Int, T) -> RunWorkout): Fitted<T>? {
        val full = build(warm, cool, options.first())
        if (cap == null || full.totalSeconds <= cap * 60) return Fitted(full, warm, cool, options.first(), cut = false)
        val w0 = min(warm, WARM_FLOOR)
        val c0 = min(cool, COOL_FLOOR)
        for (o in options) {
            val least = build(w0, c0, o).totalSeconds
            if (least > cap * 60) continue
            val spare = (cap * 60 - least) / 60
            val w = min(warm, w0 + spare)
            val c = min(cool, c0 + spare - (w - w0))
            return Fitted(build(w, c, o), w, c, o, cut = true)
        }
        return null
    }

    /** Reps from [n] down to 2 (or [n] alone when it is less): what a hard session may drop to fit its cap. */
    private fun fewer(n: Int): List<Int> = (n downTo min(n, 2)).toList()

    /**
     * A tempo run of [n] × [work] seconds and less of it: fewer reps (2 at least), then those reps shorter by whole
     * minutes (5 at least, one long rep 10) — the tempo's own way to fewer minutes, as the week's share cuts it ([tempo]).
     */
    private fun tempoCuts(n: Int, work: Int): List<Pair<Int, Int>> = buildList {
        for (k in fewer(n)) add(k to work)
        val k = min(n, 2)
        val floor = if (k == 1) 10 * 60 else 5 * 60
        var s = (work - 1) / 60 * 60
        while (s >= floor) {
            add(k to s)
            s -= 60
        }
    }

    /**
     * The order of hard sessions with a middle-distance goal (MD-1 of the research of 24 November, approved by the owner
     * on 25 November), counted by hard sessions done ([RunState.qualityCount]: a day missed only shifts it) and chosen by
     * how far the fitness ([RunState.fitness]: the trial counted or the estimate) is below the goal's own. 1500 m rests
     * mostly on the aerobic ceiling, and intervals near it raise it best (Milanović 2015, Helgerud 2007; studied two or
     * three times a week, not once in six hard runs as before). More than 3 points below: intervals every other session,
     * race pace and tempo by turns between them ([FAR]; Denis, hard runs on Monday and Thursday: intervals on one day, tempo
     * and race pace by turns on the other). Within 3: intervals and race pace by turns, tempo one in six ([WITHIN]). Within
     * a point, or the race 8 weeks away or nearer: race pace every other session, as near the goal before ([NEAR]). Race
     * pace stays first, as before: the gentlest start for a new runner.
     */
    private val FAR = listOf(RunType.GOAL_PACE, RunType.INTERVALS, RunType.TEMPO, RunType.INTERVALS)
    private val WITHIN = listOf(RunType.GOAL_PACE, RunType.INTERVALS, RunType.GOAL_PACE, RunType.INTERVALS, RunType.GOAL_PACE, RunType.TEMPO)
    private val NEAR = listOf(RunType.GOAL_PACE, RunType.TEMPO, RunType.GOAL_PACE, RunType.INTERVALS, RunType.GOAL_PACE, RunType.TEMPO)

    /** The race this close or closer, days: the hard sessions go by [NEAR]. */
    const val RACE_NEAR_DAYS = 56

    /** The cycle of hard sessions for a middle-distance [goal] on [day] ([FAR], [WITHIN], [NEAR]); with no fitness known, [NEAR]. */
    fun hardCycle(state: RunState, goal: RunGoal, day: Long? = null): List<RunType> {
        val v = state.fitness ?: return NEAR
        val gap = Vdot.fromRace(goal.meters.toDouble(), goal.seconds.toDouble()) - v
        val raceSoon = day != null && goal.date?.let { it - day in 0..RACE_NEAR_DAYS } == true
        return when {
            raceSoon || gap <= 1.0 -> NEAR
            gap <= 3.0 -> WITHIN
            else -> FAR
        }
    }

    /** The hard session the cycle ([hardCycle]) holds after [count] hard sessions done. */
    fun hardDue(state: RunState, goal: RunGoal, day: Long? = null, count: Int = state.qualityCount): RunType =
        hardCycle(state, goal, day).let { it[count.coerceAtLeast(0) % it.size] }

    fun intervals(state: RunState, cutback: Boolean = false): RunWorkout = intervalsOf(state, cutback, null, HARD_WARM, HARD_COOL, null)

    /** [intervals] for [day] (its week's share, [hardCap]) with [warmMin] and [coolMin] minutes and at most [maxReps] reps. */
    private fun intervalsOf(state: RunState, cutback: Boolean, day: Long?, warmMin: Int, coolMin: Int, maxReps: Int?): RunWorkout {
        val stage = state.intervalStage.coerceIn(0, INTERVALS.lastIndex)
        val (n0, work, rest) = INTERVALS[stage]
        val cap = hardCap(state, 12 * 60, day)
        val n = (if (cutback) max(2, n0 - 2) else n0).let { if (cap != null) min(it, max(2, cap / work)) else it }
            .let { if (maxReps != null) min(it, maxReps) else it }
        val p = paces(state)
        val segs = mutableListOf(Segment(SegmentKind.WARMUP, warmMin * 60, p?.easy))
        repeat(n) { i ->
            segs += Segment(SegmentKind.WORK, work, p?.let { PaceRange(it.interval - 4, it.interval + 4) })
            if (i < n - 1) segs += Segment(SegmentKind.RECOVERY, rest, p?.easy)
        }
        segs += Segment(SegmentKind.COOLDOWN, coolMin * 60, p?.easy)
        val note = "Отрезки тяжёлые, но ровные: последний не медленнее первого. Отдых трусцой. $STRIDES_NOTE" +
            if (state.estimated) " Темп примерный." else ""
        return RunWorkout(RunType.INTERVALS, "Интервалы · $n × ${work / 60} мин", segs, note, targetRpe = 8, stage = stage, cutback = cutback && n < n0)
    }

    /** The plan's tempo step [stage] as reps. */
    private fun rung(stage: Int): TempoShape = TEMPO[stage.coerceIn(0, TEMPO.lastIndex)].let { (n, w, r) -> TempoShape(n, w, r) }

    /** The tempo run as it stands: the runner's own ([RunState.tempoOwn]) or the ladder's step. */
    fun tempoNow(state: RunState): TempoShape = state.tempoOwn ?: rung(state.tempoStage)

    /**
     * Shorter reps done well grow towards the step [to]: about 1.6 times as long, rounded to half a minute, as many as
     * make the step's minutes (6 × 2.5 → 4 × 4 → 3 × 5). Null — the next is the step itself.
     */
    fun towards(own: TempoShape, to: TempoShape): TempoShape? {
        if (own.workSec >= to.workSec) return null
        val work = ((own.workSec * 1.6) / 30).roundToInt() * 30
        if (work >= to.workSec) return null
        return TempoShape(max(2, (to.total.toDouble() / work).roundToInt()), work, max(60, to.restSec))
    }

    /** The pace of a rep of [c] for this runner, s/km (the middle and the half-width of its range); null by feel or with no fitness known. */
    private fun customPace(state: RunState, goal: RunGoal?, pace: CustomRun.Pace): Pair<Double, Int>? {
        val p = paces(state) ?: return null
        return when (pace) {
            CustomRun.Pace.EASY -> (p.easy.fast + p.easy.slow) / 2.0 to (p.easy.slow - p.easy.fast) / 2
            CustomRun.Pace.TEMPO -> p.threshold + state.tempoSoft.toDouble() to 5
            CustomRun.Pace.INTERVAL -> p.interval.toDouble() to 4
            // The race pace of the form now (a step toward the goal's), or the repetition pace without a goal.
            CustomRun.Pace.RACE -> (goal?.let { stepPace(state, it) } ?: p.repetition.toDouble()) to 3
            CustomRun.Pace.FAST -> p.repetition.toDouble() to 5
            CustomRun.Pace.FREE -> null
        }
    }

    /** Seconds a rep of [c] lasts: as given, or its metres at its pace; null when metres are given and no pace is known. */
    fun customRepSeconds(state: RunState, goal: RunGoal?, c: CustomRun): Int? =
        c.workSec ?: c.workMeters?.let { m -> customPace(state, goal, c.pace)?.let { (it.first * m / 1000.0).roundToInt() } }

    /**
     * A run of the coach's own making ([CustomRun]): easy running, its reps with a jog between, easy running again. Paced
     * from the runner's fitness like the plan's runs; on a stadium its reps become whole laps ([onTrack]). It counts as a
     * run and moves no ladder ([RunWorkout.custom]). Null when a rep's length cannot be told (metres with no pace known).
     */
    fun custom(state: RunState, goal: RunGoal?, c: CustomRun, lapMeters: Int? = null): RunWorkout? {
        val sec = customRepSeconds(state, goal, c) ?: return null
        val p = paces(state)
        val range = customPace(state, goal, c.pace)?.let { (mid, half) -> PaceRange((mid - half).roundToInt(), (mid + half).roundToInt()) }
        val length = c.workMeters?.let { "$it м" } ?: (if (sec % 60 == 0 || sec >= 120) "${minutes((sec / 30.0).roundToInt() * 30)} мин" else "$sec с")
        val w = if (c.reps == 1 && c.pace == CustomRun.Pace.EASY) {
            RunWorkout(
                RunType.EASY, "От тренера · лёгкий бег $length", listOf(Segment(SegmentKind.RUN, sec, range)),
                "Спокойно, чтобы получалось болтать.", targetRpe = 4, custom = true,
            )
        } else {
            val segs = mutableListOf<Segment>()
            if (c.warmupMin > 0) segs += Segment(SegmentKind.WARMUP, c.warmupMin * 60, p?.easy)
            repeat(c.reps) { i ->
                segs += Segment(SegmentKind.WORK, sec, range)
                if (i < c.reps - 1 && c.restSec > 0) segs += Segment(SegmentKind.RECOVERY, c.restSec, p?.easy)
            }
            if (c.cooldownMin > 0) segs += Segment(SegmentKind.COOLDOWN, c.cooldownMin * 60, p?.easy)
            val note = "Отрезки: ${c.pace.ru}" + (range?.let { ", ${Ru.pace(it.fast.toDouble())}–${Ru.pace(it.slow.toDouble())} на км" } ?: "") +
                (if (c.restSec > 0 && c.reps > 1) ", между ними ${RunExplain.timeWords(c.restSec)} трусцой или шагом." else ".") +
                " Эта пробежка план не двигает." + (if (state.estimated && range != null) " Темп примерный." else "")
            RunWorkout(
                if (c.pace == CustomRun.Pace.TEMPO) RunType.TEMPO else RunType.INTERVALS,
                "От тренера · " + (if (c.reps == 1) length else "${c.reps} × $length"), segs, note, targetRpe = c.pace.rpe, custom = true,
            )
        }
        return if (lapMeters != null) onTrack(w, lapMeters) else w
    }

    /** «5», «2,5»: a rep's minutes as said. */
    private fun minutes(sec: Int): String = if (sec % 60 == 0) "${sec / 60}" else Ru.one(sec / 60.0)

    /**
     * Before the first trial the reps get their pace from the estimate (approximate, said so): without one, a tempo run by
     * feel is easily run far too fast (Denis, 5 November: 59 s laps for 61).
     */
    fun tempo(state: RunState, cutback: Boolean = false): RunWorkout =
        tempoReps(state, cutback, null).let { (n, work) -> tempoOf(state, cutback, n, work, HARD_WARM, HARD_COOL) }

    /** The tempo run's reps and their seconds for [day]: [tempoNow], lighter in a lighter week and kept to the week's share. */
    private fun tempoReps(state: RunState, cutback: Boolean, day: Long?): Pair<Int, Int> {
        val (n0, work, _) = tempoNow(state)
        val cap = hardCap(state, 20 * 60, day)
        var n = if (cutback && n0 > 1) n0 - 1 else n0
        var workSec = if (cutback && n0 == 1) (work * 0.75).roundToInt() else work
        if (cap != null && n * workSec > cap) {
            // Over the week's share: shorter reps of the same number while they stay 5 minutes or more (2 × 12 → 2 × 10,
            // 3 × 8 → 3 × 6 in whole minutes), so a step holds about the share, not one rep of it; else fewer reps.
            val each = (cap / n / 60) * 60
            if (each >= 5 * 60) workSec = each
            else {
                n = max(1, cap / workSec)
                if (n == 1) workSec = min(workSec, (cap / 60) * 60)
            }
        }
        return n to workSec
    }

    /** The tempo run of [n] × [workSec] with [warmMin] and [coolMin] minutes of easy running before and after. */
    private fun tempoOf(state: RunState, cutback: Boolean, n: Int, workSec: Int, warmMin: Int, coolMin: Int): RunWorkout {
        val stage = state.tempoStage.coerceIn(0, TEMPO.lastIndex)
        val (n0, work, rest) = tempoNow(state)
        val guessed = state.estimated
        val p = paces(state)
        val t = p?.let { it.threshold + state.tempoSoft }
        val segs = mutableListOf(Segment(SegmentKind.WARMUP, warmMin * 60, p?.easy))
        repeat(n) { i ->
            segs += Segment(SegmentKind.WORK, workSec, t?.let { PaceRange(it - 5, it + 5) })
            if (i < n - 1 && rest > 0) segs += Segment(SegmentKind.RECOVERY, rest, p?.easy)
        }
        segs += Segment(SegmentKind.COOLDOWN, coolMin * 60, p?.easy)
        val title = if (n == 1) "Темповый бег · ${minutes(workSec)} мин" else "Темповый бег · $n × ${minutes(workSec)} мин"
        val note = "Тяжело, но держишь: пару слов сказать можешь, болтать уже нет." +
            if (guessed) " Темп примерный, быстрее не беги." else ""
        return RunWorkout(RunType.TEMPO, title, segs, note, targetRpe = 7, stage = stage, cutback = cutback && (n < n0 || workSec < work))
    }

    /**
     * The same day when the body says no: an easy run of at most the usual easy length and 3/4 of the planned running
     * time; for run/walk, last week's session. Either way the plan does not move.
     */
    fun lighter(state: RunState, w: RunWorkout): RunWorkout {
        if (w.type == RunType.FREE) return w
        if (w.type == RunType.RUN_WALK) {
            val r = runWalk(state.copy(walkStage = max(0, state.walkStage - 3)))
            return r.copy(note = "Самочувствие так себе, поэтому сегодня тренировка прошлой недели. План не сдвигается. " + r.note, light = true)
        }
        val minutes = max(15, min(state.easyMinutes, (w.workSeconds * 0.75 / 60 / 5).toInt() * 5))
        val e = easy(state.copy(easyMinutes = minutes))
        return e.copy(note = "Самочувствие так себе, поэтому сегодня только лёгкий бег. План не сдвигается. " + e.note, light = true)
    }

    /**
     * Reps at the goal race pace (a lap of 230 m at 51–52 s for 1500 m in 5:35). On a track the reps are whole laps with a
     * beep at every lap time; the last step runs the race in two parts, both from the line.
     */
    fun goalPace(state: RunState, goal: RunGoal, lapMeters: Int?, cutback: Boolean = false): RunWorkout =
        goalPaceOf(state, goal, lapMeters, cutback, GOAL_WARM, GOAL_COOL, null)

    /** [goalPace] with [warmMin] and [coolMin] minutes before and after the reps, and at most [maxReps] of them (not the race in two parts). */
    private fun goalPaceOf(state: RunState, goal: RunGoal, lapMeters: Int?, cutback: Boolean, warmMin: Int, coolMin: Int, maxReps: Int?): RunWorkout {
        val stage = state.goalStage.coerceIn(0, GOAL.lastIndex)
        val step = GOAL[stage]
        val scale = (goal.meters / 1500.0).coerceIn(0.5, 2.0)
        val last = stage == GOAL.lastIndex
        var reps: List<Double> = if (last) listOf(goal.meters * 2 / 3.0, goal.meters / 3.0) else step.reps.map { it * scale }
        // A lighter week: a quarter fewer reps (never the two parts of the race).
        if (cutback && !last) reps = reps.dropLast(max(1, reps.size / 4))
        val pace = stepPace(state, goal)
        val range = PaceRange((pace - 3).roundToInt(), (pace + 3).roundToInt())
        val lapSec = lapMeters?.let { Track.lapSec(pace, it) }
        val p = paces(state)
        val segs = mutableListOf(Segment(SegmentKind.WARMUP, warmMin * 60, p?.easy))
        // On a track: whole laps, from the line to the line; a rep no lap count fits stays timed. The race in two parts:
        // the rest of the race laps after the first part — or the race in one go when nothing is left.
        var laps: List<Double?> = reps.map { m -> lapMeters?.let { goalRepLaps(m, scale, it) } }
        var rest = step.rest
        if (!last && lapMeters != null) {
            val l = laps.first()
            if (l != null && laps.all { it == l }) {
                // Whole laps are longer than the rep they stand for (on 230 m: 400 → 460 m, 600 → 690 m): fewer reps, so
                // the session stays the volume it was meant to be, and the rest grows with the rep.
                val meant = reps.size * reps.first()
                val near = trackReps(meant, l * lapMeters, goal.meters.toDouble())
                val fullReps = lapsCount(stage, scale, lapMeters, goal.meters.toDouble()) ?: near
                // A lighter week is lighter on a track too: its quarter fewer can round back up to the full step's count of
                // whole laps (6 × 400 less one: 5 × 2 laps of 205 m, as the full step) — then it is one rep fewer than that.
                val n = if (!cutback) fullReps else if (near < fullReps) near else max(1, fullReps - 1)
                reps = List(n) { reps.first() }
                laps = List(n) { l }
                rest = restOnLaps(step.rest, l * lapMeters, reps.first())
            }
        }
        if (last && lapMeters != null) {
            val race = Track.raceLaps(goal.meters.toDouble(), lapMeters)
            val first = laps.first() ?: race
            laps = if (race - first >= 0.5) listOf(first, race - first) else listOf(race)
            reps = laps.map { it!! * lapMeters }
        }
        // Fewer reps to fit the cap of one run ([hardSession]); the race in two parts is never cut.
        if (maxReps != null && !last && reps.size > maxReps) {
            reps = reps.take(maxReps)
            laps = laps.take(maxReps)
        }
        reps.forEachIndexed { i, m ->
            val l = laps[i]
            val meters = if (l != null) l * lapMeters!! else (m / 50).roundToInt() * 50.0
            segs += Segment(SegmentKind.WORK, (meters / 1000 * pace).roundToInt(), range, l, if (l != null) lapSec else null)
            if (i < reps.lastIndex) segs += Segment(SegmentKind.RECOVERY, rest)
        }
        segs += Segment(SegmentKind.COOLDOWN, coolMin * 60, p?.easy)
        val work = segs.filter { it.kind == SegmentKind.WORK }
        fun len(s: Segment) = s.laps?.let { Ru.laps(it) } ?: "${(s.seconds / pace * 1000 / 50).roundToInt() * 50} м"
        val title = "Темп цели · " + if (work.all { it.seconds == work.first().seconds }) "${work.size} × ${len(work.first())}"
        else work.joinToString(" + ") { len(it) }
        // In minutes, as the card's line says it («отдых 2 мин»), not as a clock.
        val restWords = RunExplain.timeWords(rest)
        val note = if (lapSec != null) {
            val lapRange = Ru.lapRange(Track.lapSec(range.fast.toDouble(), lapMeters), Track.lapSec(range.slow.toDouble(), lapMeters))
            "Круг $lapRange, не быстрее. На писке будь на линии. Между отрезками $restWords шагом или трусцой. " +
                    "$STRIDES_NOTE " +
                "Жми «Круг» на каждой линии." +
                (work.lastOrNull()?.laps?.takeIf { it % 1.0 != 0.0 }?.let { " Отрезок в ${Ru.laps(it)} начни ${startWords(it % 1.0)}." } ?: "")
        } else {
            "Отрезки ${if (pace > goal.pace + 0.5) "по" else "в темпе цели,"} ${Ru.pace(pace)} на км. Ровно, не быстрее. " +
                "Между ними $restWords шагом или трусцой."
        }.let { n ->
            // Far from the goal: the step toward it, said with both times.
            if (pace > goal.pace + 0.5) {
                "Ступень к цели: пока это темп забега ${goal.meters} м за ${Ru.clock((pace * goal.meters / 1000).roundToInt())} " +
                    "(цель — ${Ru.clock(goal.seconds)}). " + n
            } else n
        }
        // Lighter only when it has fewer reps than the full session on this track (whole laps change the count).
        val full = if (cutback) goalPace(state, goal, lapMeters).segments.count { it.kind == SegmentKind.WORK } else 0
        return RunWorkout(
            RunType.GOAL_PACE, title, segs, note, targetRpe = if (stage <= 2) 7 else 8, stage = stage, lapMeters = lapMeters,
            cutback = cutback && reps.size < full,
        )
    }

    /**
     * The pace of race-pace reps: a step toward the goal. Far from it, reps at the goal's own pace fail session after session
     * (seen in half-year simulations: a 6:40 runner stayed on the first rung all along); a coach runs them at the race pace
     * of the current form a little faster — about what one block of training adds (2.5 %) — so they are hard but held, and
     * the step catches up with the goal with every trial, or as the estimate grows with training ([RunState.estVdot]).
     * Within reach, and with nothing known of the runner at all, the goal's own pace.
     */
    fun stepPace(state: RunState, goal: RunGoal): Double {
        val byFitness = fitnessStep(state, goal)
        // What the runner has held in reps as long as these or longer: the step is never slower than that (nor quicker
        // than the goal). A pace held in shorter reps is not put on these ([RunState.goalRepPaces]).
        val shown = heldRepPace(state) ?: return byFitness
        return max(goal.pace, min(byFitness, shown))
    }

    /** The step from the fitness alone: the race pace of the current form a step quicker, not quicker than the goal. */
    private fun fitnessStep(state: RunState, goal: RunGoal): Double {
        val v = state.fitness ?: return goal.pace
        val current = Vdot.predictSeconds(v, goal.meters.toDouble()) / (goal.meters / 1000.0)
        return max(goal.pace, current * (1 - STEP_AHEAD))
    }

    /**
     * The pace race-pace reps [r] of [w] showed the runner holds, s a km: the session done (almost) in full, at the pace
     * asked or quicker (measured), with the effort asked or less — the runner's own effort scale applied, as [decide]
     * does. Never quicker than the goal's own pace. Null when the session says nothing of it (not measured, missed, too
     * hard, not race-pace reps).
     */
    fun shownRepPace(state: RunState, w: RunWorkout, r: RunResult, goal: RunGoal): Double? {
        if (w.type != RunType.GOAL_PACE || w.light || w.custom) return null
        val target = w.segments.firstOrNull { it.kind == SegmentKind.WORK }?.pace?.let { (it.fast + it.slow) / 2.0 } ?: return null
        val pace = r.workPace ?: return null
        val enough = r.reps?.let { it >= w.reps && r.completed >= 0.9 } ?: (r.completed >= 0.95)
        val rpe = (r.rpe - state.rpeOffset).coerceAtLeast(1)
        if (!enough || pace > target * 1.03 || rpe > w.targetRpe) return null
        return max(goal.pace, min(target, pace))
    }

    /**
     * [state] with the race-pace reps' pace taken from the last race-pace session of [records] (oldest first) when it is not
     * kept yet: for a runner who ran such reps before the app kept it (24 November). Unchanged with one kept or none shown.
     */
    fun withShownRepPace(state: RunState, goal: RunGoal?, records: List<Pair<RunWorkout, RunResult>>): RunState {
        if (state.goalRepPace != null || state.goalRepPaces.isNotEmpty() || goal == null || !goal.middleDistance) return state
        val (w, r) = records.lastOrNull { it.first.type == RunType.GOAL_PACE && !it.first.light && !it.first.custom } ?: return state
        // Kept with the length of the reps it was held in.
        return shownRepPace(state, w, r, goal)?.let { withHeld(state, repLength(w.stage), it) } ?: state
    }

    /**
     * The longest an easy or long run on [day] may be so that the running of the last 7 days stays within 10 % of the
     * busiest of the three weeks before (one missed week does not shrink the plan). Null without running in those weeks.
     * Seen in simulations: a beginner graduating mid-week got a runner's runs on top of the week's run/walk — +60 % in one
     * week.
     *
     * Only running counts ([RunLog.runMinutes]): the cap shortens easy and long runs alone, so a hard session's warm-up,
     * rests and cool-down counted in full were paid for by the week's easy run (seen: a runner with two hard sessions and
     * one easy run a week had the easy run cut to 15 minutes, where it could not grow either). And while the log does not
     * reach back over the three weeks (the first run logged within them), they are not taken as less than the plan's own
     * week ([ownWeek]): the first weeks with the app are partial — a free run or two — and a runner who logged them must not
     * be held below one who logged nothing (no cap at all). Once the three weeks are known, they alone are the base: a
     * runner who missed sessions comes back by 10 % a week, not to the plan's whole week at once (seen in simulations: +78 %
     * in one week with the plan's week as the base all along).
     */
    fun weekCap(state: RunState, day: Long): Int? {
        val before = (1..3).map { k -> state.recent.filter { it.day in (day - 7 * k - 6)..(day - 7 * k) }.sumOf { it.runMinutes } }.maxOrNull() ?: 0
        if (before == 0) return null
        val soFar = state.recent.filter { it.day in (day - 6) until day }.sumOf { it.runMinutes }
        val young = state.recent.minOf { it.day } > day - 21
        val base = if (young) maxOf(before, ownWeek(state)) else before
        return maxOf(15, (base * 1.1).toInt() - soFar)
    }

    /**
     * The running of a week the plan itself holds at its present lengths, minutes: two easy runs and a long one (a new
     * runner of base level starts with 25 + 25 + 40). The week cap's base while the runner's log is younger than its
     * three weeks ([weekCap]).
     */
    fun ownWeek(state: RunState): Int = 2 * state.easyMinutes + state.longMinutes

    /**
     * Minutes of running in a session, for the week's count ([RunLog.running]): an easy, long or free run is running all
     * through, its time past the plan too; in the others only the runs and reps — as timed, or their share of the session
     * when it was typed in by hand.
     */
    fun runningMinutes(w: RunWorkout, r: RunResult): Int {
        val minutes = (r.durationSec / 60.0).roundToInt()
        if (w.workSeconds >= w.totalSeconds) return minutes
        val sec = r.workSec ?: (r.durationSec.toDouble() * w.workSeconds / w.totalSeconds).roundToInt()
        return min(minutes, (sec / 60.0).roundToInt())
    }

    /** How far ahead of the current race pace the race-pace step is. */
    const val STEP_AHEAD = 0.025

    /**
     * Time trial over the goal distance, run evenly: the beeps give the lap time of the current fitness — measured, or the
     * estimate from training (the goal plus 8 % only with nothing known at all: Denis's first trial was paced so, ten
     * points above his form, and he held three laps) — the last laps are as fast as is left. Its time gives the fitness.
     */
    fun goalTest(state: RunState, goal: RunGoal, lapMeters: Int?): RunWorkout {
        val p = paces(state)
        val predicted = state.fitness?.let { Vdot.predictSeconds(it, goal.meters.toDouble()) } ?: goal.seconds * 1.08
        val pace = predicted / (goal.meters / 1000.0)
        val laps = lapMeters?.let { Track.raceLaps(goal.meters.toDouble(), it) }
        val meters = if (laps != null) laps * lapMeters else goal.meters.toDouble()
        val lapSec = lapMeters?.let { Track.lapSec(pace, it) }
        // The race has room for a slower finish (15 %): the cool-down starts at the last lap press, not over the runner.
        val segs = listOf(
            Segment(SegmentKind.WARMUP, 12 * 60, p?.easy),
            Segment(
                SegmentKind.WORK, (meters / 1000 * pace * if (laps != null) 1.15 else 1.0).roundToInt(),
                PaceRange((pace - 3).roundToInt(), (pace + 3).roundToInt()), laps, lapSec,
            ),
            Segment(SegmentKind.COOLDOWN, 8 * 60, p?.easy),
        )
        val distance = if (laps != null) "${Ru.laps(laps)} (${meters.roundToInt()} м)" else "${goal.meters} м"
        val note = buildString {
            append("$distance в полную силу, но ровно. $STRIDES_NOTE ")
            if (lapSec != null) {
                append("Первые круги по ${Ru.lap(lapSec)}, телефон пищит на каждом. Со второй половины прибавь, если есть силы. ")
                append("Если не держишь темп, сбавь, но добеги. ")
                if (laps != null && laps % 1.0 != 0.0) append("Старт ${startWords(laps % 1.0)}. ")
                append("Жми «Круг» на старте и на каждой линии, последний раз на финише.")
            } else {
                append("Первую половину беги по ${Ru.pace(pace)} на км, дальше на сколько хватит сил.")
            }
        }
        return RunWorkout(
            RunType.TEST, "Контрольный забег · $distance", segs, note, targetRpe = 9,
            lapMeters = lapMeters, testMeters = meters.roundToInt(),
        )
    }

    /**
     * The workout on a stadium of [lapMeters]: timed reps become whole laps at their pace (three minutes at 58 s a lap are
     * three laps), every paced segment gets its lap time, and the titles count laps.
     */
    fun onTrack(w: RunWorkout, lapMeters: Int): RunWorkout {
        if (w.isOpenEnded) return w.copy(lapMeters = lapMeters)
        val segs = w.segments.map { s ->
            val pace = s.pace ?: return@map s
            val lap = s.lapSec ?: Track.lapSec((pace.fast + pace.slow) / 2.0, lapMeters)
            // A timed rep becomes the laps that cover about as much ground (a rep no lap count fits stays timed).
            val laps = if (s.kind == SegmentKind.WORK && s.laps == null) Track.repLaps(s.seconds / lap * lapMeters, lapMeters) else null
            when {
                laps != null -> s.copy(seconds = (laps * lap).roundToInt(), laps = laps, lapSec = lap)
                // A rep no lap count fits stays timed, and a whole lap is not its target.
                s.kind == SegmentKind.WORK && s.laps == null -> s
                else -> s.copy(lapSec = lap)
            }
        }
        val work = segs.filter { it.kind == SegmentKind.WORK && it.laps != null }
        val title = when {
            w.type != RunType.INTERVALS && w.type != RunType.TEMPO || work.isEmpty() -> w.title
            work.size == 1 -> w.title.substringBefore(" · ") + " · " + Ru.laps(work.first().laps!!)
            work.all { it.laps == work.first().laps } -> w.title.substringBefore(" · ") + " · ${work.size} × ${Ru.laps(work.first().laps!!)}"
            else -> w.title
        }
        return w.copy(segments = segs, title = title, lapMeters = lapMeters)
    }

    /**
     * A run entered by hand, as [after] reads a timed one: a session of reps is as complete as its reps, an easy or long
     * run as its time against the plan (longer counts, as with the timer), a trial or a free run is complete.
     */
    fun manual(w: RunWorkout, m: ManualRun): RunResult {
        val duration = (m.durationSec ?: w.totalSeconds).coerceAtLeast(60)
        val repsKind = w.type == RunType.INTERVALS || w.type == RunType.TEMPO || w.type == RunType.GOAL_PACE
        val reps = if (repsKind && w.reps > 0) (m.reps ?: w.reps).coerceAtLeast(0) else null
        val completed = when {
            w.isOpenEnded || w.type == RunType.TEST -> 1.0
            reps != null -> reps.toDouble() / w.reps
            else -> duration.toDouble() / w.totalSeconds.coerceAtLeast(1)
        }.coerceIn(0.0, 1.0)
        return RunResult(
            completed = completed, rpe = m.rpe, durationSec = duration, distanceM = m.distanceM?.takeIf { it > 0 },
            workPace = if (repsKind) m.workPace else null,
            // The 12-minute test: its distance over its twelve minutes.
            workDistanceM = m.testDistanceM?.takeIf { w.type == RunType.TEST && w.testMeters == null && it > 0 },
            workSec = if (w.type == RunType.TEST && w.testMeters == null) w.workSeconds else null,
            reps = reps, testSeconds = m.testSeconds?.takeIf { w.testMeters != null },
        )
    }

    /** 12-minute time trial: the distance gives a fresh VDOT. */
    fun test(state: RunState): RunWorkout {
        val p = paces(state)
        return RunWorkout(
            RunType.TEST, "Контрольный забег · 12 мин",
            listOf(
                Segment(SegmentKind.WARMUP, 10 * 60, p?.easy),
                Segment(SegmentKind.WORK, 12 * 60),
                Segment(SegmentKind.COOLDOWN, 8 * 60, p?.easy),
            ),
            "12 минут так быстро, как можешь держать ровно.", targetRpe = 9,
        )
    }

    /**
     * The hard session; while coming back from a long break it is an easy run. With a middle-distance goal the cycle of
     * [hardCycle] decides it (MD-1, 25 November: it was race pace every other one, tempo two of the rest and intervals one,
     * whatever the gap to the goal); without one, intervals and tempo by turns. [goalLater]: the legs are not fresh today
     * and a fresher hard day comes later in the week — race pace due today goes there, and today runs the cycle's next
     * session ([RunState.goalDeferredOn]). [day]: the day it is for — the race date and the cap of one run ([hardSession])
     * go by it; without one, the session as the ladder has it.
     */
    fun quality(state: RunState, cutback: Boolean = false, goal: RunGoal? = null, lapMeters: Int? = null, goalLater: Boolean = false, day: Long? = null): RunWorkout {
        if (state.returnEasy > 0) {
            val n = state.returnEasy
            val e = easy(state, cutback, day)
            val w = e.copy(note = "После перерыва тяжёлый бег вернётся через $n " +
                "${Ru.plural(n, "лёгкую пробежку", "лёгкие пробежки", "лёгких пробежек")}. " + e.note)
            return if (lapMeters != null) onTrack(w, lapMeters) else w
        }
        val type = when {
            goal != null && goal.middleDistance -> {
                val due = hardDue(state, goal, day)
                // Race pace when it is due or owed, unless the legs are tired and a fresher day comes; else the cycle's
                // other session (the next one when race pace was due).
                val other = if (due == RunType.GOAL_PACE) hardDue(state, goal, day, state.qualityCount + 1) else due
                when {
                    !goalLater && (state.goalDeferredOn != null || due == RunType.GOAL_PACE) -> RunType.GOAL_PACE
                    other == RunType.INTERVALS -> RunType.INTERVALS
                    else -> RunType.TEMPO
                }
            }
            state.qualityCount % 2 == 0 -> RunType.INTERVALS
            else -> RunType.TEMPO
        }
        val w = hardSession(state, type, cutback, goal, lapMeters, day)
        return if (lapMeters != null) onTrack(w, lapMeters) else w
    }

    /**
     * The hard training session of [type] — intervals, tempo, or race-pace reps of [goal] (tempo without one) — for [day]:
     * as a whole, warm-up, rests and cool-down in it, no longer than one run may be ([singleRunCap]: 110 % of the longest
     * of the last 30 days), as easy and long runs already were (hard-session-spike / MD-3 of the research of 24 November,
     * approved by the owner on 25 November: Frandsen 2025 counted every session, hard ones too, and a step of a ladder
     * could be 16–30 % over the month's longest). Over it: the cool-down shorter first, then the warm-up jog (8 minutes at
     * least, so its strides before fast reps stay), then fewer reps (2 at least; tempo's reps then shorter), else the day's
     * easy run (the cycle waits: it moves only when a hard session is done). In minutes, not metres: on a small loop GPS
     * cuts the bends. A race and a trial are not built here, so they are never cut. Without a [day], as the ladder has it.
     */
    fun hardSession(state: RunState, type: RunType, cutback: Boolean = false, goal: RunGoal? = null, lapMeters: Int? = null, day: Long? = null): RunWorkout {
        val cap = singleRunCap(state, day)
        fun track(x: RunWorkout) = if (lapMeters != null) onTrack(x, lapMeters) else x
        val fitted: Fitted<*>? = when {
            type == RunType.GOAL_PACE && goal != null -> {
                val reps = goalPaceOf(state, goal, lapMeters, cutback, GOAL_WARM, GOAL_COOL, null).reps
                fit(cap, GOAL_WARM, GOAL_COOL, fewer(reps)) { w, c, n -> goalPaceOf(state, goal, lapMeters, cutback, w, c, n) }
            }
            type == RunType.INTERVALS -> {
                val reps = intervalsOf(state, cutback, day, HARD_WARM, HARD_COOL, null).reps
                fit(cap, HARD_WARM, HARD_COOL, fewer(reps)) { w, c, n -> track(intervalsOf(state, cutback, day, w, c, n)) }
            }
            else -> {
                val (n, work) = tempoReps(state, cutback, day)
                fit(cap, HARD_WARM, HARD_COOL, tempoCuts(n, work)) { w, c, s -> track(tempoOf(state, cutback, s.first, s.second, w, c)) }
            }
        }
        if (cap == null || day == null) return fitted!!.workout
        if (fitted == null) {
            val e = easy(state, cutback, day)
            return e.copy(note = "${capWords(state, day, cap)}, поэтому сегодня лёгкий бег вместо тяжёлого. " + e.note)
        }
        val w = fitted.workout
        return if (fitted.cut) w.copy(note = w.note + " ${capWords(state, day, cap)}.") else w
    }

    /** «27 мин на всю пробежку, это 110 % самой длинной за месяц»: one run's [cap] on [day] and what it comes from ([singleRunCap]). */
    fun capWords(state: RunState, day: Long, cap: Int): String =
        "$cap\u00A0мин на всю пробежку, " + (if (state.longestRecent(day) != null) "это 110\u00A0% самой длинной за месяц" else "пока пробежек за месяц нет")

    /**
     * A hard run of the coach's own making ([custom], on a stadium of [lapMeters]) within the cap of one run on [day], as
     * the plan's hard sessions are ([hardSession], MD-3 of 25 November): the cool-down shorter first, then the warm-up
     * (8 minutes at least), then fewer reps (2 at least). [c] itself when it fits or is easy; null when even the least of
     * it is over the cap, or its reps' length cannot be told.
     */
    fun customWithinCap(state: RunState, goal: RunGoal?, c: CustomRun, day: Long, lapMeters: Int? = null): CustomRun? {
        if (!c.pace.hard) return c
        if (custom(state, goal, c, lapMeters) == null) return null
        val f = fit(singleRunCap(state, day), c.warmupMin, c.cooldownMin, fewer(c.reps)) { w, cool, n ->
            custom(state, goal, c.copy(reps = n, warmupMin = w, cooldownMin = cool), lapMeters)!!
        } ?: return null
        return if (f.cut) c.copy(reps = f.option, warmupMin = f.warm, cooldownMin = f.cool) else c
    }

    private fun scaled(min: Int, cutback: Boolean) = if (cutback) max(15, (min * 0.75 / 5).roundToInt() * 5) else min

    /** Days since the last run when that is more than a week (a break), else null. */
    fun breakDays(state: RunState, day: Long): Int? {
        val last = state.lastRunDay ?: return null
        val off = day - last
        return if (off > 7) off.toInt() else null
    }

    /** Share of aerobic fitness lost after [off] days without running. */
    fun fitnessLoss(off: Int): Double = when {
        off <= 7 -> 0.0
        off <= 28 -> 0.0025 * (off - 7)
        else -> min(0.15, 0.0525 + 0.0015 * (off - 28))
    }

    /**
     * Back after a break of more than a week. Fitness fades — VO2max drops about 7 % in the first 2–3 weeks
     * without training and about 15 % by 8–12 weeks (Coyle et al. 1984) — so paces slow down accordingly, easy and
     * long runs restart at 50–90 % of their length, interval and tempo stages go back one per two weeks off, the
     * run/walk plan goes back a week per two weeks off (Couch to 5K: repeat a week when it feels too hard), and after
     * four weeks or more the first 2–3 runs are easy. The usual rules then build everything back up.
     * Applied once: the result counts the break as taken.
     */
    /**
     * The running state as it stands on [day]: the estimate first (from the goal, less the hard runs missed since: it stands
     * for what the runner is shown to hold), then a break past a week ([withBreak]) on it. The other way round a break
     * raised the estimate: it clears the misses, and the estimate worked out after it no longer took them off (Denis on
     * 24.11: 41 after eight days off, 40 before; the check of the progression, 24.11).
     */
    fun current(state: RunState, goal: RunGoal?, day: Long): RunState = withBreak(estimated(state, goal), day)

    fun withBreak(state: RunState, day: Long): RunState {
        val off = breakDays(state, day) ?: return state
        val keep = when {
            off < 14 -> 0.9
            off < 28 -> 0.75
            off < 56 -> 0.6
            else -> 0.5
        }
        val back = off / 14
        fun cut(minutes: Int, floor: Int) = max(min(minutes, floor), (minutes * keep / 5).roundToInt() * 5)
        return state.copy(
            vdot = state.vdot?.let { (it * (1 - fitnessLoss(off)) * 2).roundToInt() / 2.0 },
            estVdot = state.estVdot?.let { (it * (1 - fitnessLoss(off)) * 2).roundToInt() / 2.0 },
            goalRepPace = state.goalRepPace?.let { it * (1 + fitnessLoss(off)) },
            goalRepPaces = state.goalRepPaces.mapValues { (_, p) -> p * (1 + fitnessLoss(off)) },
            walkStage = if (state.walkStage >= 0) max(0, state.walkStage - 3 * back) else state.walkStage,
            easyMinutes = cut(state.easyMinutes, 20),
            longMinutes = cut(state.longMinutes, 30),
            intervalStage = max(0, state.intervalStage - back),
            tempoStage = max(0, state.tempoStage - back),
            // The race-pace ladder steps back like the others; a trial due before a month off is not run cold.
            goalStage = max(0, state.goalStage - back),
            goalFails = 0,
            testSoon = state.testSoon && off < 28,
            qualityOnPace = 0,
            // Hard runs ahead before the break say nothing of the paces after it (cut by it): one ahead after it was the
            // third of a streak, the estimate up again a week after coming back.
            qualityAhead = 0,
            qualityFails = 0,
            intervalFails = 0,
            tempoFails = 0,
            walkFails = 0,
            returnEasy = max(state.returnEasy, if (off >= 56) 3 else if (off >= 28) 2 else 0),
            lastRunDay = day,
        )
    }

    /** A line for the first run after a break, or null. */
    fun comebackNote(state: RunState, day: Long): String? {
        val off = breakDays(state, day) ?: return null
        val weeks = off / 7
        val gap = if (weeks >= 2) Ru.weeks(weeks) else Ru.days(off)
        return "Перерыв $gap. Сегодня короче и спокойнее."
    }

    /**
     * Next state of the ladders after a run. Doing more than planned counts: an easy or long run kept going comfortably
     * grows by half of the extra (3–8 minutes; the 30-day cap still applies to the next one), extra reps or reps clearly
     * faster than asked at a moderate effort move a ladder two steps. [goal] is the race trained for, if any.
     */
    /** Easy runs that agree (5 of the last 6) and no trial for six weeks: the paces follow them. */
    const val EASY_SAYS = 5
    private const val VDOT_MIN = 20.0
    private const val VDOT_MAX = 85.0

    /**
     * Paces from easy runs, for someone without trials (a health runner has no hard days, so no trial ever comes; a
     * trial missed for weeks leaves paces stale): five of the last six easy runs well quicker than the easy range
     * (more than 3 %) and light (4 or less on the runner's own scale) — VDOT +1; five of six well slower and hard
     * (6 or more) — −1. At most once in four weeks; a trial in the last six weeks decides instead. Easy pace is only
     * loosely tied to VDOT (heat, hills), hence the five of six.
     */
    private fun easyPaceSays(s: RunState, w: RunWorkout, r: RunResult, rpe: Int, day: Long, out: MutableList<RunOutcome>): RunState {
        val v = s.fitness ?: return s
        val dist = r.distanceM ?: return s
        if (dist < 1000 || r.completed < 0.95 || r.durationSec < 600) return s
        val pace = r.durationSec / (dist / 1000.0)
        val easy = PaceTable.forVdot(v).easy
        val sign = when {
            pace < easy.fast * 0.97 && rpe <= 4 -> 1
            pace > easy.slow * 1.03 && rpe >= 6 -> -1
            else -> 0
        }
        val signs = (s.easySigns + sign).takeLast(6)
        val trialRecent = s.lastTestDay?.let { day - it < 42 } == true
        val movedRecent = s.lastVdotUpDay?.let { day - it < 28 } == true
        if (trialRecent || movedRecent || signs.size < EASY_SAYS) return s.copy(easySigns = signs)
        val up = signs.count { it > 0 } >= EASY_SAYS
        val down = signs.count { it < 0 } >= EASY_SAYS
        if (!up && !down) return s.copy(easySigns = signs)
        val to = (v + if (up) 1.0 else -1.0).coerceIn(VDOT_MIN, VDOT_MAX)
        out += RunOutcome.EasyPaces(v, to)
        return if (s.vdot != null) s.copy(vdot = to, lastVdotUpDay = day, easySigns = emptyList())
        else s.copy(estVdot = to, lastVdotUpDay = day, easySigns = emptyList())
    }

    /** The loop GPS fitted shorter than the lap length set by more than this share (and 5 m): the setting is off. */
    private const val LOOP_OFF = 0.04

    /**
     * The run as it was on a loop of [meters] instead of the lap length it was planned on: the lap length, the trial's
     * distance, the lap times aimed at (in the plan and in each lap), the reps' pace and the distance from laps — the time
     * of every lap as pressed.
     */
    fun onLoop(w: RunWorkout, r: RunResult, meters: Int): Pair<RunWorkout, RunResult> {
        val set = w.lapMeters ?: return w to r
        if (meters <= 0 || meters == set) return w to r
        val k = meters.toDouble() / set
        // The distance from laps — pressed, and those GPS counted around them — is whole half laps of the setting; GPS
        // metres are not, and stay as measured.
        val fromLaps = r.distanceM?.let { d -> abs(d / (set / 2.0) - (d / (set / 2.0)).roundToInt()) * (set / 2.0) < 1.0 } == true
        return w.copy(
            lapMeters = meters,
            testMeters = w.testMeters?.let { (it * k).roundToInt() },
            segments = w.segments.map { it.copy(lapSec = it.lapSec?.let { s -> s * k }) },
        ) to r.copy(
            workPace = r.workPace?.let { it / k },
            // The laps on the loop, or GPS where it saw more (a warm-up partly off the loop).
            distanceM = if (fromLaps) r.distanceM?.let { maxOf(it * k, r.gpsM ?: 0.0) } else r.distanceM,
            // The lap times aimed at, as on the loop: each lap's verdict in the debrief follows.
            laps = r.laps?.map { it.copy(fast = it.fast?.let { f -> f * k }, slow = it.slow?.let { sl -> sl * k }) },
        )
    }

    /**
     * [onLoop] on the loop GPS fitted ([RunResult.loopM]) when it is clearly shorter than the lap length set — Denis: set
     * 230 m, his loop ≈205 m. On the setting his trial's 6,5 laps would score as 1495 m while he ran 1332 (a fitness seven
     * points too high, the 5:30 goal "reached"), and race-pace reps 6 % slower than the goal read as too fast. Only
     * shorter: a loop GPS sees longer than set is more likely GPS than the setting, and the shorter of the two never makes
     * a runner fitter than they are. Null when there is nothing to change.
     */
    fun onMeasuredLoop(w: RunWorkout, r: RunResult): Pair<RunWorkout, RunResult>? {
        val set = w.lapMeters ?: return null
        val loop = r.loopM ?: return null
        if (loop >= set * (1 - LOOP_OFF) || set - loop < 5) return null
        return onLoop(w, r, loop.roundToInt())
    }

    /**
     * What an easy or long run decides by itself in [after], from how it went: the length of its kind, and for an easy run
     * its rating on the runner's scale ([RunState.easyRpes]) and its pace sign ([RunState.easySigns]). [into] with those of
     * [from]; other kinds of run are left as they are (their ladders are what the next session of the cycle reads).
     */
    fun withOwnDecision(type: RunType, into: RunState, from: RunState): RunState = when (type) {
        RunType.EASY -> into.copy(easyMinutes = from.easyMinutes, easyRpes = from.easyRpes, easySigns = from.easySigns)
        RunType.LONG -> into.copy(longMinutes = from.longMinutes)
        else -> into
    }

    /**
     * Whether the reps' average lap would be said fast, as each lap is ([Lap.verdict]: to the whole second, against the
     * lap range shown): on a track, with the reps' laps pressed and judged. Null without such laps (typed by hand, on the
     * road, timed reps): nothing was said lap by lap.
     */
    private fun avgLapSaidFast(w: RunWorkout, r: RunResult): Boolean? {
        val lapM = w.lapMeters ?: return null
        val pace = r.workPace ?: return null
        val range = w.segments.firstOrNull { it.kind == SegmentKind.WORK && it.lapSec != null }?.pace ?: return null
        if (r.laps.orEmpty().count { it.rep != null && !it.rough && (it.fast != null || it.slow != null) } < 2) return null
        return Lap(pace * lapM / 1000.0, Track.lapSec(range.fast.toDouble(), lapM)).verdict == -1
    }

    fun after(state: RunState, w: RunWorkout, r: RunResult, caps: RunCaps, day: Long = 0, goal: RunGoal? = null): RunAfter {
        val fixed = onMeasuredLoop(w, r) ?: return decide(state, w, r, caps, day, goal)
        val a = decide(state, fixed.first, fixed.second, caps, day, goal)
        return a.copy(outcomes = listOf(RunOutcome.LoopShorter(fixed.first.lapMeters!!, w.lapMeters!!)) + a.outcomes)
    }

    private fun decide(state: RunState, w: RunWorkout, r: RunResult, caps: RunCaps, day: Long, goal: RunGoal?): RunAfter {
        val out = mutableListOf<RunOutcome>()
        val minutes = (r.durationSec / 60.0).roundToInt()
        val original = state
        // Every decision below starts from the state after any break was taken into account.
        // Without a trial counted, the estimate to go by (kept from here on).
        @Suppress("NAME_SHADOWING") val state = current(original, goal, day)
        val est = state.estimated
        if (state.vdot != original.vdot && state.vdot != null) out += RunOutcome.VdotChanged(original.vdot, state.vdot)
        var s = state.copy(
            runs = state.runs + 1,
            // A run saved late (an older day) does not move the last run back: it would look like a break.
            lastRunDay = maxOf(day, state.lastRunDay ?: day),
            recent = (state.recent + RunLog(day, minutes, runningMinutes(w, r))).filter { it.day > day - 31 }.takeLast(40),
            returnEasy = max(0, state.returnEasy - 1),
        )
        val done = r.completed >= 0.95
        // The week's running against the week before (this run included): a run grows only while the week stays within
        // 10 % of the one before. Seen in simulations: +3 minutes after every easy run doubled the week in two weeks for
        // someone running six days.
        val thisWeek = state.recent.filter { it.day in (day - 6)..day }.sumOf { it.minutes } + minutes
        val lastWeek = state.recent.filter { it.day in (day - 13)..(day - 7) }.sumOf { it.minutes }
        val weekAllowsMore = lastWeek == 0 || thisWeek <= lastWeek * 1.1
        // The runner's own effort scale (from easy runs), applied to the thresholds below; said when it is not the plan's.
        val offset = state.rpeOffset
        val rpe = (r.rpe - offset).coerceAtLeast(1)
        if (offset > 0 && w.type != RunType.TEST && w.type != RunType.FREE && (w.type != RunType.RUN_WALK || done)) out += RunOutcome.OwnScale(offset)
        // Easy runs and run/walk done in full set the scale (both meant light to moderate); a lighter one for a bad day too.
        if ((w.type == RunType.EASY || w.type == RunType.RUN_WALK) && done) s = s.copy(easyRpes = (state.easyRpes + r.rpe).takeLast(8))
        // A run made lighter for a bad day counts as running, but moves no ladder; nor does one of the coach's own making.
        if (w.light || w.custom) return RunAfter(s, out)
        when (w.type) {
            // Running without a break already (a run/walk session saved late, for a day before the graduation): it moves
            // nothing — the ladder is done, and a session done in full must not put the runner back on it.
            RunType.RUN_WALK -> if (state.walkStage >= 0) {
                val stage = state.walkStage
                s = when {
                    // Done in full: the runner's own scale counts (a whole session done is the proof).
                    done && rpe <= 6 -> {
                        val next = stage + 1
                        if (next >= WALK_LADDER.size) {
                            out += RunOutcome.Graduated
                            s.copy(walkStage = -1, easyMinutes = 30, longMinutes = max(state.longMinutes, 35))
                        } else {
                            out += RunOutcome.StageUp("walk", next)
                            s.copy(walkStage = next)
                        }
                    }
                    state.walkFails >= 1 -> {
                        // Second miss in a row: back one week of the plan.
                        val back = max(0, stage - 3)
                        out += RunOutcome.StageDown("walk", back)
                        s.copy(walkStage = back, walkFails = 0)
                    }
                    else -> { out += RunOutcome.StageRepeat("walk"); s.copy(walkFails = 1) }
                }
                if (done && rpe <= 6) s = s.copy(walkFails = 0)
            }
            RunType.EASY -> {
                s = easyPaceSays(s, w, r, rpe, day, out)
                // Only a run of the full usual length says it can grow: one kept short (after the gym, a cutback
                // week, the 30-day cap) proves nothing about a longer one.
                val full = w.workSeconds >= state.easyMinutes * 60
                val extra = minutes - w.workSeconds / 60
                val step = if (extra >= 6) (extra / 2).coerceIn(3, 8) else 3
                // Shorter down to 20 minutes, never longer: one at 15 (the coach's, or just out of run/walk) rated hard became
                // 20 with «короче: 20 мин»; one at the floor stays as it is.
                val shorter = min(state.easyMinutes, max(20, state.easyMinutes - 5))
                s = when {
                    rpe >= 7 && shorter < state.easyMinutes -> s.copy(easyMinutes = shorter).also { out += RunOutcome.Shorter(it.easyMinutes) }
                    rpe >= 7 -> s.also { out += RunOutcome.Same(min(state.easyMinutes, caps.easyMax)) }
                    done && full && rpe <= 5 && state.easyMinutes < caps.easyMax && weekAllowsMore ->
                        s.copy(easyMinutes = min(caps.easyMax, state.easyMinutes + step)).also { out += RunOutcome.Longer(it.easyMinutes) }
                    else -> s.also { out += RunOutcome.Same(min(state.easyMinutes, caps.easyMax)) }
                }
            }
            RunType.LONG -> {
                // Grow the long run, but never past 110 % of the longest run of the last 30 days; run longer than
                // planned and easily, it grows by half of the extra.
                val longest = maxOf(minutes, state.longestRecent(day) ?: 0)
                val extra = minutes - w.workSeconds / 60
                val step = if (extra >= 10) (extra / 2).coerceIn(5, 10) else 5
                val next = minOf(caps.longMax, state.longMinutes + step, (longest * 1.10).toInt())
                // Shorter down to 30 minutes, never longer (a long run of 25 stopped early became 30).
                val shorter = min(state.longMinutes, max(30, state.longMinutes - 5))
                s = when {
                    (rpe >= 8 || !done) && shorter < state.longMinutes -> s.copy(longMinutes = shorter).also { out += RunOutcome.Shorter(it.longMinutes) }
                    rpe >= 8 || !done -> s.also { out += RunOutcome.Same(min(state.longMinutes, caps.longMax)) }
                    rpe <= 6 && next > state.longMinutes && w.workSeconds >= state.longMinutes * 60 && weekAllowsMore ->
                        s.copy(longMinutes = next).also { out += RunOutcome.Longer(it.longMinutes) }
                    else -> s.also { out += RunOutcome.Same(min(state.longMinutes, caps.longMax)) }
                }
            }
            RunType.INTERVALS, RunType.TEMPO -> {
                val ladder = if (w.type == RunType.INTERVALS) "intervals" else "tempo"
                val size = if (w.type == RunType.INTERVALS) INTERVALS.size else TEMPO.size
                val cur = if (w.type == RunType.INTERVALS) state.intervalStage else state.tempoStage
                val target = paces(s)?.let { if (w.type == RunType.INTERVALS) it.interval.toDouble() else it.threshold + s.tempoSoft.toDouble() }
                // Reps as the runner reported them count, but only for a session run (almost) through: reps cut short
                // by skipping are not reps done.
                val repsDone = r.reps ?: w.reps
                // Measured (laps or GPS) more than 3 % slower than the reps' target: the paces are not held yet — no step,
                // however the effort felt. Three misses in a row bring the paces down (below).
                val repTarget = w.segments.firstOrNull { it.kind == SegmentKind.WORK }?.pace?.let { (it.fast + it.slow) / 2.0 }
                val tooSlow = repTarget != null && r.workPace != null && r.workPace > repTarget * 1.03
                if (tooSlow) out += RunOutcome.TooSlow
                if (r.workPace == null) out += RunOutcome.PaceUnknown
                // The runner's own scale counts only where the pace was measured and held: the pace is the proof.
                val rq = if (r.workPace != null && !tooSlow) rpe else r.rpe
                val success = !tooSlow && rq <= w.targetRpe && (r.reps?.let { it >= w.reps && r.completed >= 0.9 } ?: done)
                // On pace: measured, not slower than the target, and with effort to spare — only that says the form
                // has outgrown the paces.
                val onPace = success && target != null && r.workPace != null && r.workPace <= target && rq <= w.targetRpe - 1
                var stage = cur
                var fails = state.qualityFails
                // Misses of this ladder in a row: tempo run well between two hard interval sessions does not make them
                // fine (seen in simulations: intervals on one rung for months, a miss always "the first").
                var own = if (w.type == RunType.INTERVALS) state.intervalFails else state.tempoFails
                var streak = state.qualityOnPace
                // The tempo run's own reps (the coach's, or a split step): they lead back to the step, not up the ladder.
                var shape = state.tempoOwn
                val step = rung(cur)
                val ownRun = w.type == RunType.TEMPO && shape != null
                val ownAsHard = shape != null && shape.workSec >= step.workSec && shape.total >= step.total
                // Far too hard: not even 60 % done at an 8 or more. The same minutes in reps half as long next time, not
                // the same run again (Denis, 5 November: 3 × 5 min, stopped in the second rep at an 8).
                val planned = w.segments.firstOrNull { it.kind == SegmentKind.WORK }?.seconds ?: 0
                val farTooHard = w.type == RunType.TEMPO && !success && !w.cutback && r.completed < 0.6 && r.rpe >= 8 && planned >= 240
                if (success && w.cutback) {
                    // A lighter week's shorter session proves nothing about the full step: the ladder waits, and a miss
                    // of the full step before it still counts (seen: misses and lighter weeks in turn kept a rung for months).
                    out += RunOutcome.LighterWeek(ladder)
                    fails = 0
                } else if (success && ownRun && !ownAsHard) {
                    val next = towards(shape!!, step)
                    shape = next
                    (next ?: step).let { out += RunOutcome.TempoNext(it.reps, it.workSec) }
                    fails = 0
                    own = 0
                    streak = if (onPace) streak + 1 else streak
                } else if (success) {
                    // More reps than planned at the planned effort: the next step is too small.
                    stage = min(size - 1, cur + if (repsDone > w.reps) 2 else 1)
                    out += if (stage > cur) RunOutcome.StageUp(ladder, stage) else RunOutcome.LadderTop(ladder)
                    if (w.type == RunType.TEMPO) shape = null
                    fails = 0
                    own = 0
                    streak = if (onPace) streak + 1 else streak
                } else if (farTooHard) {
                    val n = w.segments.count { it.kind == SegmentKind.WORK }
                    val rest = w.segments.firstOrNull { it.kind == SegmentKind.RECOVERY }?.seconds ?: 0
                    val split = TempoShape(n * 2, max(90, ((planned / 2.0) / 30).roundToInt() * 30), max(60, rest))
                    shape = split
                    out += RunOutcome.TempoNext(split.reps, split.workSec, split = true)
                    fails += 1
                    own = 0
                    streak = 0
                } else {
                    fails += 1
                    own += 1
                    streak = 0
                    val o = shape
                    if (own >= 2 && ownRun && o != null) {
                        // Own reps missed twice: one fewer (not below 2); the ladder's step stays.
                        if (o.reps > 2) {
                            shape = o.copy(reps = o.reps - 1)
                            out += RunOutcome.TempoNext(o.reps - 1, o.workSec)
                        } else out += RunOutcome.StageRepeat(ladder)
                        own = 0
                    } else if (own >= 2) {
                        stage = max(0, cur - 1)
                        out += RunOutcome.StageDown(ladder, stage)
                    } else {
                        out += RunOutcome.StageRepeat(ladder)
                    }
                }
                s = if (w.type == RunType.INTERVALS) s.copy(intervalStage = stage, intervalFails = own) else s.copy(tempoStage = stage, tempoFails = own, tempoOwn = shape)
                s = s.copy(qualityCount = state.qualityCount + 1, qualityFails = fails, qualityOnPace = streak)
                // Ahead of the paces, done in full, three hard sessions running: well quicker than them (3 % and more), or
                // on them (within 1 %) with effort to spare. The paces lag behind the runner, and the time trial says by
                // how much — the next hard day, from four weeks after the last one. A runner growing fast (5 points in two
                // months) ran his reps on pace at a 6 where an 8 was asked for eight weeks, the fitness where the last
                // trial had left it (on pace to the second is what the monthly step asks, and a second over it is noise).
                // A session at the effort asked, or a miss, says the paces fit; one not measured or a lighter week, nothing.
                val ahead = success && !w.cutback && target != null && r.workPace != null &&
                    (r.workPace < target * FASTER || r.workPace <= target * 1.01 && rq <= w.targetRpe - 1)
                val aheadRun = when {
                    ahead -> state.qualityAhead + 1
                    !success || r.workPace != null && !w.cutback && rq >= w.targetRpe -> 0
                    else -> state.qualityAhead
                }
                if (aheadRun >= 3 && est) {
                    // Going by an estimate: it steps up, no trial is called for that.
                    val moved = moveEst(s, 1.0, day, goal, out)
                    s = if (moved.estVdot != s.estVdot) moved.copy(qualityAhead = 0) else s.copy(qualityAhead = aheadRun)
                } else if (aheadRun >= 3 && !s.testSoon && (state.lastTestDay == null || day - state.lastTestDay >= 28)) {
                    s = s.copy(testSoon = true, qualityAhead = 0)
                    out += RunOutcome.AheadOfPaces
                } else {
                    s = s.copy(qualityAhead = aheadRun)
                }
                // Tempo measured slow at a hard effort: this runner's threshold is below what the fitness says (a speed type,
                // fitness from a short race) — softer by 3 s a km, up to 12; held easily on pace, firmer again.
                // Going by an estimate, a hard run far too hard at the pace asked (not run quicker than it), or measured slow
                // at a hard effort, says the estimate is too high: a point down, and every pace with it.
                val asAsked = r.workPace == null || repTarget == null || r.workPace >= repTarget * FASTER
                if (est && !success && !w.cutback && r.rpe >= 8 && asAsked && (r.completed < 0.6 || tooSlow)) s = moveEst(s, -1.0, day, goal, out)
                if (w.type == RunType.TEMPO && r.workPace != null && !est) {
                    val soft = state.tempoSoft
                    if (tooSlow && r.rpe >= 8 && soft < TEMPO_SOFT_MAX) {
                        s = s.copy(tempoSoft = soft + 3)
                        out += RunOutcome.TempoPace(soft + 3, softer = true)
                    } else if (onPace && soft > 0 && rq <= w.targetRpe - 2) {
                        s = s.copy(tempoSoft = soft - 3)
                        out += RunOutcome.TempoPace(soft - 3, softer = false)
                    }
                }
                // Run in place of race pace put off to a fresher day: race pace comes next. Due by the cycle of this fitness
                // (MD-1, 25 November), no longer by an even count alone.
                if (goal != null && goal.middleDistance && state.goalDeferredOn == null && hardDue(state, goal, day) == RunType.GOAL_PACE) {
                    s = s.copy(goalDeferredOn = day)
                }
                val v = s.vdot
                // Paces held a month (Daniels raises them after about four weeks); with a race goal the time trial measures
                // the form instead, and its first half must not be paced from a guess.
                val month = state.lastVdotUpDay == null || day - state.lastVdotUpDay >= 28
                if (v != null && streak >= 3 && month && goal?.middleDistance != true) {
                    out += RunOutcome.VdotChanged(v, v + 1, easier = true)
                    s = s.copy(vdot = v + 1, qualityOnPace = 0, lastVdotUpDay = day)
                }
                if (v != null && fails >= 3 && v > MIN_VDOT) {
                    out += RunOutcome.VdotChanged(v, v - 1)
                    s = s.copy(vdot = v - 1, qualityFails = 0)
                }
                // The same by the estimate: three on pace with effort to spare lift it (with a race goal too — no trial
                // measures it), three misses bring it down.
                if (est && streak >= 3) {
                    val moved = moveEst(s, 1.0, day, goal, out)
                    if (moved.estVdot != s.estVdot) s = moved.copy(qualityOnPace = 0)
                }
                // (Not a second point for the same run: a blow-up that is also the third miss lowers it once.)
                if (est && fails >= 3) s = (if (s.estVdot == state.estVdot) moveEst(s, -1.0, day, goal, out) else s).copy(qualityFails = 0)
            }
            RunType.TEST -> {
                val tm = w.testMeters
                if (tm != null) {
                    // A time trial over the race distance: its time is the fitness. A pace outside 2:10–15:00 a km is
                    // a typo ("552" for 5:52), not a result. Without a time (no laps on a road) the GPS distance in the
                    // trial's time says the same, as in the 12-minute test.
                    // The time given, or the laps' when they run the trial to its end (typed in by hand, put right after).
                    val t = (r.testSeconds ?: trialLapsTime(w, r.laps))?.takeIf { it / (tm / 1000.0) in PLAUSIBLE_PACE }
                    val d = r.workDistanceM
                    val sec = r.workSec ?: w.workSeconds
                    // The laps' own time to the tenth where none was typed: rounded to the second, a trial run to its end
                    // and the same trial stopped a lap earlier (the rest taken at the last lap's pace) came out a rounding
                    // step apart (44 against 44.5).
                    val exact = if (r.testSeconds == null && t != null) lapsToTheEnd(w, r.laps) ?: t.toDouble() else t?.toDouble()
                    val raw = when {
                        exact != null -> Vdot.fromRace(tm.toDouble(), exact)
                        // Run to the end of its time: the distance in it is the result, however short of the trial's (a
                        // runner slower than the plan) — 79 % kept the form, 81 % lowered it. Half of it at least: less
                        // is GPS lost more likely than a runner that slow. Or run to its distance before its time (a runner
                        // quicker than the plan's guess stops at the finish): the trial run, not a stretch of it — 1500 m
                        // in 5:40 against an estimate's 6:35 was «не добежан», a full trial again and no goal reached.
                        w.lapMeters == null && d != null && (done || d >= tm) && d >= tm * 0.5 && sec > 0 && sec / (d / 1000) in PLAUSIBLE_PACE ->
                            Vdot.fromRace(d, sec.toDouble())
                        else -> null
                    }
                    // What was held from the start: faded after it, the whole time underrates the runner; stopped or
                    // walked, it is all there is. On the road the GPS distance in the trial's time is the same run as
                    // the time typed, not a stretch of it: only without a time does it stand in. Laps that run the whole
                    // trial in another time than the one typed are no measure either: the time typed is the runner's
                    // correction of them (a press late or missed) — they gave «the first 1500 m» of a 1500 m trial in
                    // 3:45 over the 4:04 typed.
                    // Brought to it in proportion (the error spread over the laps) rather than trusted or dropped on a few
                    // seconds: a stretch that counted at 2 s apart and vanished at 3 moved the fitness by 6.
                    // A lap walked or a press missed is no reason to read the laps apart from the time typed: they are
                    // brought to it all the same (dropped there, a trial run 2 % faster had its stretch 5 % slower).
                    val lapsTime = lapsToTheEnd(w, r.laps)
                    val read = when {
                        r.testSeconds == null || lapsTime == null || lapsTime.roundToInt() == r.testSeconds -> r
                        // Laps far from the time typed (by more than a sixth: 13 min of laps, 7 typed) are no record of
                        // that trial at all — brought to it, a lap turned into a "doubled press" for a trial 2 % faster.
                        abs(lapsTime / r.testSeconds - 1) > 0.15 -> r.copy(laps = null)
                        else -> r.copy(laps = r.laps?.map { if (it.rep == 1) it.copy(seconds = it.seconds * r.testSeconds / lapsTime) else it })
                    }
                    val part = if (w.lapMeters == null && raw != null) null else held(w, read)
                    // A stretch is a part of the trial: the whole of it is the whole time.
                    val better = part != null && raw != null && part.meters < tm - 1 && (part.vdot * 2).roundToInt() > (raw * 2).roundToInt()
                    // A time typed that no laps bear out (none, or far from it): a typo is possible.
                    val typedAlone = r.testSeconds != null && (lapsTime == null || abs(lapsTime / r.testSeconds - 1) > 0.15)
                    when {
                        raw != null -> {
                            // Faded, the stretch corrects the whole time by 3 at most, and never past the fitness known
                            // by more than belief allows (away far too fast, then a jog): a runner who died after half
                            // lacks some of the endurance the stretch alone would credit. Capped, not dropped: a stretch
                            // just past belief once dropped, the form 6–8 lower for a trial run 2 % faster.
                            val used = if (better) minOf(part!!.vdot, raw + PART_OVER_WHOLE, (state.vdot ?: Double.MAX_VALUE) + 8.0) else raw
                            val capped = better && (used * 2).roundToInt() < (part!!.vdot * 2).roundToInt()
                            if (!believable(used, state.vdot) || typedAlone && !believableByEstimate(used, state, goal)) {
                                out += RunOutcome.TestDoubtful((used * 2).roundToInt() / 2.0)
                            } else {
                                val nv = (used * 2).roundToInt() / 2.0
                                if (t != null) out += RunOutcome.TestTime(tm, t)
                                if (better) out += RunOutcome.TestFromPart(part!!.meters.roundToInt(), part.seconds.roundToInt(), finished = true, capped = capped)
                                out += RunOutcome.VdotChanged(state.vdot, nv)
                                s = s.copy(vdot = nv, estVdot = null, testSoon = false)
                                // Judged on the unrounded fitness: 1495 m in 5:33 is faster than 1500 m in 5:35 — and on
                                // the whole time only: a stretch at the goal's pace is not the goal run. The time shown
                                // must reach it too: laps to the tenth (186,8 s) reached 800 m in 3:02 by a hair under
                                // «820 м за 3:07», which does not — a press is not that exact, the next trial says it.
                                val need = goal?.let { Vdot.fromRace(it.meters.toDouble(), it.seconds.toDouble()) }
                                val reached = need != null && raw >= need && (t == null || Vdot.fromRace(tm.toDouble(), t.toDouble()) >= need)
                                if (reached) out += RunOutcome.GoalReached
                                // Not there yet after the whole ladder: back to the 400s to build up again — not to the
                                // longest reps, the hardest sessions of the ladder.
                                if (goal != null && !reached && state.goalStage >= GOAL.lastIndex) s = s.copy(goalStage = GOAL_RESTART, goalFails = 0)
                            }
                        }
                        part != null -> {
                            // Stopped, walked, or a lap not trusted: the stretch held is the measure, but only as far as
                            // it goes — a runner who stops may have had more, so a part raises the fitness or sets it,
                            // never lowers it. A full trial comes again. Walked to the end, the time to it holds the
                            // stretch back as a slower end does (3 above it at most): a lap walked is no better than one
                            // jogged at a breath slower (that one counted the whole time, this one the stretch alone).
                            // Stopped after slowing, the rest is taken at the pace come down to: a stop is no better
                            // than running on at it to the end (48 stopped, 47.5 run to the end, before).
                            val end = (lapsToTheEnd(w, read.laps) ?: projectedEnd(w, read.laps))?.takeIf { it / (tm / 1000.0) in PLAUSIBLE_PACE }
                            val v = if (end == null) part.vdot else min(part.vdot, Vdot.fromRace(tm.toDouble(), end) + PART_OVER_WHOLE)
                            val nv = (v * 2).roundToInt() / 2.0
                            val kept = state.vdot != null && nv <= state.vdot
                            // Lower than the fitness known it changes nothing, so it is no typo either; far above it is.
                            if (!kept && !believable(v, state.vdot)) {
                                out += RunOutcome.TestDoubtful(nv)
                            } else {
                                out += RunOutcome.TestFromPart(part.meters.roundToInt(), part.seconds.roundToInt(), finished = false, kept = kept,
                                    capped = nv < (part.vdot * 2).roundToInt() / 2.0)
                                if (!kept) {
                                    out += RunOutcome.VdotChanged(state.vdot, nv)
                                    s = s.copy(vdot = nv, estVdot = null)
                                }
                                s = s.copy(testSoon = true)
                            }
                        }
                        else -> {
                            val run = heldLaps(w, r).sumOf { it.part } * (w.lapMeters ?: 0)
                            out += if (run > 0) RunOutcome.TestStopped(run.roundToInt()) else RunOutcome.TestNotCounted
                        }
                    }
                } else {
                    // Only a test run to the end counts: a shortened one would overrate the runner (short efforts are
                    // faster) and block the next test for eight weeks.
                    val d = r.workDistanceM
                    val sec = r.workSec ?: w.workSeconds
                    if (done && d != null && d > 500 && sec > 0) {
                        val raw = Vdot.fromRace(d, sec.toDouble())
                        if (believable(raw, state.vdot)) {
                            val nv = (raw * 2).roundToInt() / 2.0
                            out += RunOutcome.VdotChanged(state.vdot, nv)
                            s = s.copy(vdot = nv, estVdot = null, testSoon = false)
                            // A 5 or 10 km goal: the form the test measured against the goal's (it was never said before).
                            if (goal != null && raw >= Vdot.fromRace(goal.meters.toDouble(), goal.seconds.toDouble())) out += RunOutcome.GoalReached
                        } else {
                            out += RunOutcome.TestDoubtful((raw * 2).roundToInt() / 2.0)
                        }
                    } else {
                        out += RunOutcome.TestNotCounted
                    }
                }
            }
            RunType.GOAL_PACE -> {
                val planned = w.reps
                // Reported reps count for a session run (almost) through; without them the timer decides.
                val enough = r.reps?.let { it >= planned && r.completed >= 0.9 } ?: done
                val repsDone = r.reps ?: if (done) planned else (r.completed * planned).toInt()
                val target = w.segments.firstOrNull { it.kind == SegmentKind.WORK }?.pace?.let { (it.fast + it.slow) / 2.0 }
                val pace = r.workPace
                // Measured (laps or GPS): more than 3 % slower is not the goal pace; 2 % faster at a moderate effort
                // means the goal pace is comfortable already.
                val slow = target != null && pace != null && pace > target * 1.03
                val quick = target != null && pace != null && pace < target * 0.98
                // On a track every lap was said against the lap range shown, to the whole second («Круг 44–46 с»: «44. В
                // темпе.»): reps whose average lap is said on pace so are not called fast here either. 43,6-s laps, each
                // «в темпе» on the voice and in the debrief, were repeated as «заметно быстрее» (3 % on the unrounded
                // average). Without laps said (by hand, on the road) the pace alone decides.
                val saidFast = avgLapSaidFast(w, r) != false
                val fast = quick && saidFast
                // More than 3 % faster: the laps were not the goal's. Even laps are the skill this ladder teaches (a fast
                // runner who fades after 800 m lacks exactly that), so the step is run again at the right lap time.
                // Measured against the goal's own pace too (a step slower than the goal run at the goal is not too fast);
                // GPS on short road reps is less exact than laps.
                val tooFast = saidFast && target != null && pace != null && pace < min(target, goal?.pace ?: target) * (if (w.lapMeters != null) 0.97 else 0.95)
                val cur = state.goalStage.coerceIn(0, GOAL.lastIndex)
                if (pace == null) out += RunOutcome.PaceUnknown
                if (slow) out += RunOutcome.TooSlow
                val rq = if (pace != null && !slow) rpe else r.rpe
                if (enough && rq <= 8 && !slow && w.cutback) {
                    // A lighter week's shorter session: done, and the ladder waits for the full step — a miss of the full
                    // step before it still counts, as for intervals and tempo (miss, lighter week, miss was a repeat).
                    out += RunOutcome.LighterWeek("goal")
                } else if (enough && tooFast) {
                    out += RunOutcome.TooFastRepeat
                    s = s.copy(goalFails = 0)
                } else if (enough && rq <= 8 && !slow) {
                    // Extra reps at the goal pace: the next step is too small. Faster than the goal is not a reason to
                    // jump: even laps are the skill of this distance, and the note asks for exactly that.
                    if (fast) out += RunOutcome.TooFast
                    val big = repsDone > planned && !fast
                    val next = min(GOAL.lastIndex, cur + if (big) 2 else 1)
                    // A time trial at most every three weeks, however often the top of the ladder goes well.
                    val trialRecently = state.lastTestDay?.let { day - it < 21 } == true
                    if (cur == GOAL.lastIndex && !trialRecently) {
                        // The race in two parts at goal pace: time to run it in one go.
                        out += RunOutcome.TestNext
                        s = s.copy(goalStage = cur, goalFails = 0, testSoon = true)
                    } else if (cur == GOAL.lastIndex) {
                        out += RunOutcome.TrialLater
                        s = s.copy(goalFails = 0)
                    } else {
                        out += RunOutcome.StageUp("goal", next)
                        s = s.copy(goalStage = next, goalFails = 0)
                    }
                    // Going by an estimate: reps of a minute and a half or more held with effort to spare say the step
                    // can come nearer the goal (short ones say only that the runner is quick).
                    val long = (w.segments.firstOrNull { it.kind == SegmentKind.WORK }?.seconds ?: 0) >= 90
                    if (est && long && pace != null && rq <= w.targetRpe - 1) s = moveEst(s, if (quick) 1.0 else 0.5, day, goal, out)
                } else if (state.goalFails >= 1) {
                    // Second miss in a row: one step back.
                    val back = max(0, cur - 1)
                    out += RunOutcome.StageDown("goal", back)
                    s = s.copy(goalStage = back, goalFails = 0)
                    // And by an estimate the step's pace itself was too quick.
                    if (est) s = moveEst(s, -1.0, day, goal, out)
                    // The pace held before was too quick for these reps: a step slower, back to the fitness's step at most —
                    // the paces held in reps as long as these or longer, the ones their pace came from. A pace carried over
                    // from shorter reps is eased for this length alone and kept for it (capped at the fitness's step, never
                    // dropped), so the shorter reps' quicker pace does not come back to these after a step back.
                    if (goal != null) {
                        val top = fitnessStep(s, goal)
                        val length = repLength(cur)
                        val held = heldPaces(s)
                        s = held.filterKeys { it >= length }.entries.fold(s) { x, (len, p) ->
                            withHeld(x, len, min(p * (1 + STEP_AHEAD), top))
                        }
                        if (held.keys.none { it >= length }) {
                            held.filterKeys { it < length }.maxByOrNull { it.key }?.value?.let { carried ->
                                s = withHeld(s, length, min(carried * (1 + STEP_AHEAD), top))
                            }
                        }
                    }
                } else {
                    out += RunOutcome.StageRepeat("goal")
                    s = s.copy(goalFails = 1)
                }
                // The pace the reps showed the runner holds, kept for reps of their length: the quickest so far, and a step
                // of reps that long or shorter comes to it — not a step of longer reps (see [RunState.goalRepPaces]).
                if (goal != null) shownRepPace(state, w, r, goal)?.let { shown ->
                    val was = stepPace(s, goal)
                    val length = repLength(w.stage)
                    s = withHeld(s, length, min(shown, heldPaces(s)[length] ?: shown))
                    val now = stepPace(s, goal)
                    if (now < was * 0.99) out += RunOutcome.RepPace(was, now)
                }
                s = s.copy(qualityCount = state.qualityCount + 1, goalDeferredOn = null)
            }
            RunType.FREE -> Unit
        }
        return RunAfter(s, out)
    }
}
