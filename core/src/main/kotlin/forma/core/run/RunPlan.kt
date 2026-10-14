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
    /** Alternates intervals and tempo for the weekly quality day. */
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
) {
    val canRunContinuously: Boolean get() = walkStage < 0

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
data class RunLog(val day: Long, val minutes: Int)

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
    /** A test result far from the current form ([vdot] it would give): more likely a typo, not counted. */
    @Serializable data class TestDoubtful(val vdot: Double) : RunOutcome
    /** A test that gave no time (laps missed, nothing typed): not counted. */
    @Serializable data object TestNotCounted : RunOutcome
}

data class RunAfter(val state: RunState, val outcomes: List<RunOutcome>)

/** Caps for easy and long runs by how much the person runs. */
@Serializable
data class RunCaps(val easyMax: Int, val longMax: Int)

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
     * longer one takes the next half lap (3,5 laps = 805 m). Null: the rep stays timed.
     */
    private fun goalRepLaps(meters: Double, scale: Double, lapMeters: Int): Double? {
        var prev = 0.0
        for (d in GOAL.dropLast(1).flatMap { it.reps }.distinct().sorted()) {
            val m = d * scale
            val laps = Track.repLaps(m, lapMeters)?.let { if (it <= prev) prev + 0.5 else it }
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

    /** Tempo ladder: (repeats, work seconds, recovery seconds) at threshold pace. */
    private val TEMPO = listOf(
        Triple(3, 300, 60), Triple(4, 300, 60), Triple(3, 480, 120), Triple(2, 720, 120),
        Triple(1, 1200, 0), Triple(3, 600, 60), Triple(1, 1500, 0), Triple(2, 900, 120),
    )

    fun paces(state: RunState): Paces? = state.vdot?.let { PaceTable.forVdot(it) }

    /**
     * Before the first test of a race goal: a fitness well below the goal's (8 points, about a sixth slower over 1500 m),
     * only so easy runs have a pace they should not beat — the first runs are where a fast starter overdoes it.
     */
    fun provisionalVdot(goal: RunGoal): Double =
        ((Vdot.fromRace(goal.meters.toDouble(), goal.seconds.toDouble()) - 8) * 2).roundToInt() / 2.0

    /** An easy or long run paced from [provisionalVdot]: the limit is said to be approximate. */
    fun provisional(w: RunWorkout): RunWorkout =
        w.copy(note = w.note + " Темп примерный: контрольного ещё не было, граница — от цели; после контрольного уточнится.")

    fun runWalk(state: RunState): RunWorkout {
        val stage = state.walkStage.coerceIn(0, WALK_LADDER.lastIndex)
        val segs = mutableListOf(Segment(SegmentKind.WARMUP, WALK_WARM))
        for ((run, walk) in WALK_LADDER[stage].blocks) {
            segs += Segment(SegmentKind.RUN, run, paces(state)?.easy)
            if (walk > 0) segs += Segment(SegmentKind.WALK, walk)
        }
        segs += Segment(SegmentKind.COOLDOWN, WALK_WARM)
        val note = "Бег в разговорном темпе, ходьба быстрым шагом. Разминка и заминка — ходьба."
        val title = "Бег с ходьбой · неделя ${stage / 3 + 1}, тренировка ${stage % 3 + 1}"
        return RunWorkout(RunType.RUN_WALK, title, segs, note, targetRpe = 6, stage = stage)
    }

    /**
     * An easy run; [strides] short accelerations after it (4–6 × about 80 m, 15–20 s): speed and form at no cost of
     * recovery, twice a week for runners with hard runs (Daniels' "strides").
     */
    fun easy(state: RunState, cutback: Boolean = false, day: Long? = null, strides: Int = 0): RunWorkout {
        val cap = singleRunCap(state, day)
        val min = scaled(state.easyMinutes, cutback).let { if (cap != null) minOf(it, maxOf(15, cap)) else it }
        val note = "Темп, в котором можно говорить предложениями. Медленнее — нормально." + if (strides > 0) {
            " В конце — $strides ${Ru.plural(strides, "ускорение", "ускорения", "ускорений")} по 80 м (15–20 с; на стадионе — по прямой): " +
                "быстро, но свободно, без напряжения; между ними — шагом, пока дыхание не успокоится (около минуты)."
        } else ""
        return RunWorkout(
            RunType.EASY, "Лёгкий бег · $min мин" + if (strides > 0) " + $strides ${Ru.plural(strides, "ускорение", "ускорения", "ускорений")}" else "",
            listOf(Segment(SegmentKind.RUN, min * 60, paces(state)?.easy)), note, targetRpe = 4,
        )
    }

    /** Strides after an easy run: 4 to start with and in a lighter week, 6 once running is regular. */
    fun strides(state: RunState, cutback: Boolean): Int = if (cutback || state.runs < 8) 4 else 6

    /** The goal race on its day: run as a time trial (its time is the fitness), said as the race. */
    fun race(state: RunState, goal: RunGoal): RunWorkout {
        val t = goalTest(state, goal, null)
        val name = if (goal.meters < 1000 || goal.meters % 1000 != 0) "${goal.meters} м" else "${goal.meters / 1000} км"
        return t.copy(
            title = "Забег · $name",
            note = "Забег-цель: $name за ${Ru.clock(goal.seconds)}. Разминка 12 минут и 3–4 ускорения по 15 с. Первая половина — ровно, " +
                "по темпу цели, без рывка со старта; вторая — сколько осталось. После финиша впиши время с табло: по нему пересчитаются темпы.",
        )
    }

    /**
     * Upper limit for any single run: 110 % of the longest run in the previous 30 days
     * (Frandsen et al., BJSM 2025: bigger single-session spikes raise overuse injury risk).
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
            "Ровно и спокойно, как лёгкий бег. Главное — время на ногах.", targetRpe = 5,
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

    private fun hardCap(state: RunState, floor: Int): Int? = weekMinutes(state)?.let { max(floor, (it * 60 * 0.10).roundToInt()) }

    fun intervals(state: RunState, cutback: Boolean = false): RunWorkout {
        val stage = state.intervalStage.coerceIn(0, INTERVALS.lastIndex)
        val (n0, work, rest) = INTERVALS[stage]
        val cap = hardCap(state, 12 * 60)
        val n = (if (cutback) max(2, n0 - 2) else n0).let { if (cap != null) min(it, max(2, cap / work)) else it }
        val p = paces(state)
        val segs = mutableListOf(Segment(SegmentKind.WARMUP, 10 * 60, p?.easy))
        repeat(n) { i ->
            segs += Segment(SegmentKind.WORK, work, p?.let { PaceRange(it.interval - 4, it.interval + 4) })
            if (i < n - 1) segs += Segment(SegmentKind.RECOVERY, rest, p?.easy)
        }
        segs += Segment(SegmentKind.COOLDOWN, 10 * 60, p?.easy)
        val note = "Ускорения тяжёлые, но ровные: последнее не медленнее первого. Между ними — трусца. В конце разминки — 3–4 коротких ускорения по 15 с."
        return RunWorkout(RunType.INTERVALS, "Интервалы · $n × ${work / 60} мин", segs, note, targetRpe = 8, stage = stage, cutback = cutback && n < n0)
    }

    fun tempo(state: RunState, cutback: Boolean = false): RunWorkout {
        val stage = state.tempoStage.coerceIn(0, TEMPO.lastIndex)
        val (n0, work, rest) = TEMPO[stage]
        val cap = hardCap(state, 20 * 60)
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
        val p = paces(state)
        val t = p?.let { it.threshold + state.tempoSoft }
        val segs = mutableListOf(Segment(SegmentKind.WARMUP, 10 * 60, p?.easy))
        repeat(n) { i ->
            segs += Segment(SegmentKind.WORK, workSec, t?.let { PaceRange(it - 5, it + 5) })
            if (i < n - 1 && rest > 0) segs += Segment(SegmentKind.RECOVERY, rest, p?.easy)
        }
        segs += Segment(SegmentKind.COOLDOWN, 10 * 60, p?.easy)
        val title = if (n == 1) "Темповый бег · ${workSec / 60} мин" else "Темповый бег · $n × ${workSec / 60} мин"
        val note = "«Комфортно тяжело»: можно сказать пару слов, но не болтать."
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
            return r.copy(note = "Самочувствие так себе — сегодня тренировка прошлой недели, план не сдвигается. " + r.note, light = true)
        }
        val minutes = max(15, min(state.easyMinutes, (w.workSeconds * 0.75 / 60 / 5).toInt() * 5))
        val e = easy(state.copy(easyMinutes = minutes))
        return e.copy(note = "Самочувствие так себе — сегодня только лёгкий бег, план не сдвигается. " + e.note, light = true)
    }

    /**
     * Reps at the goal race pace (a lap of 230 m at 51–52 s for 1500 m in 5:35). On a track the reps are whole laps with a
     * beep at every lap time; the last step runs the race in two parts, the second one from the middle of a lap.
     */
    fun goalPace(state: RunState, goal: RunGoal, lapMeters: Int?, cutback: Boolean = false): RunWorkout {
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
        val segs = mutableListOf(Segment(SegmentKind.WARMUP, 12 * 60, p?.easy))
        // On a track: whole laps where they fit, else half laps (started half a lap before the line); a rep no lap count
        // fits stays timed. The race in two parts: the rest of the race distance after the first part — or the race in
        // one go when the rest would be under half a lap.
        var laps: List<Double?> = reps.map { m -> lapMeters?.let { goalRepLaps(m, scale, it) } }
        var rest = step.rest
        if (!last && lapMeters != null) {
            val l = laps.first()
            if (l != null && laps.all { it == l }) {
                // Whole laps are longer than the rep they stand for (on 230 m: 400 → 460 m, 600 → 690 m): fewer reps, so
                // the session stays the volume it was meant to be, and the rest grows with the rep.
                val meant = reps.size * reps.first()
                val n = trackReps(meant, l * lapMeters, goal.meters.toDouble())
                reps = List(n) { reps.first() }
                laps = List(n) { l }
                rest = (((step.rest * (l * lapMeters) / reps.first()) / 15).roundToInt() * 15).coerceAtLeast(step.rest)
            }
        }
        if (last && lapMeters != null) {
            val race = Track.raceLaps(goal.meters.toDouble(), lapMeters)
            val first = laps.first() ?: race
            laps = if (race - first >= 0.5) listOf(first, race - first) else listOf(race)
            reps = laps.map { it!! * lapMeters }
        }
        reps.forEachIndexed { i, m ->
            val l = laps[i]
            val meters = if (l != null) l * lapMeters!! else (m / 50).roundToInt() * 50.0
            segs += Segment(SegmentKind.WORK, (meters / 1000 * pace).roundToInt(), range, l, if (l != null) lapSec else null)
            if (i < reps.lastIndex) segs += Segment(SegmentKind.RECOVERY, rest)
        }
        segs += Segment(SegmentKind.COOLDOWN, 8 * 60, p?.easy)
        val work = segs.filter { it.kind == SegmentKind.WORK }
        fun len(s: Segment) = s.laps?.let { Ru.laps(it) } ?: "${(s.seconds / pace * 1000 / 50).roundToInt() * 50} м"
        val title = "Темп цели · " + if (work.all { it.seconds == work.first().seconds }) "${work.size} × ${len(work.first())}"
        else work.joinToString(" + ") { len(it) }
        val restWords = Ru.clock(rest)
        val note = if (lapSec != null) {
            val lapRange = Ru.lapRange(Track.lapSec(range.fast.toDouble(), lapMeters), Track.lapSec(range.slow.toDouble(), lapMeters))
            "Каждый круг — $lapRange: на ${lapSec.roundToInt()}-й секунде пискнет, в этот момент ты должен быть на линии. " +
                "Быстрее не надо: ровный темп — главный навык этой дистанции. Между отрезками $restWords шагом или трусцой. " +
                    "В конце разминки — 3–4 ускорения по 15 с. " +
                "Нажимай «Круг» на линии — по кругам приложение решит, что дальше." +
                (work.lastOrNull()?.laps?.takeIf { it % 1.0 != 0.0 }?.let { " Отрезок в ${Ru.laps(it)} начни ${startWords(it % 1.0)}." } ?: "")
        } else {
            "Отрезки в темпе ${if (pace > goal.pace + 0.5) "ступени" else "цели"}, ${Ru.pace(pace)} на км: ровно, не быстрее. " +
                "Между ними $restWords шагом или трусцой."
        }.let { n ->
            // Far from the goal: the step toward it, said with both times.
            if (pace > goal.pace + 0.5) {
                "Ступень к цели: темп забега ${goal.meters} м за ${Ru.clock((pace * goal.meters / 1000).roundToInt())} " +
                    "(цель — ${Ru.clock(goal.seconds)}). После каждого контрольного ступень ближе к цели. $n"
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
     * the step catches up with the goal with every trial. Within reach, and before any trial, the goal's own pace.
     */
    fun stepPace(state: RunState, goal: RunGoal): Double {
        val v = state.vdot ?: return goal.pace
        val current = Vdot.predictSeconds(v, goal.meters.toDouble()) / (goal.meters / 1000.0)
        return max(goal.pace, current * (1 - STEP_AHEAD))
    }

    /**
     * The longest an easy or long run on [day] may be so that the last 7 days stay within 10 % of the busiest of the three
     * weeks before (one missed week does not shrink the plan). Null without running in those weeks. Seen in simulations:
     * a beginner graduating mid-week got a runner's runs on top of the week's run/walk — +60 % in one week.
     */
    fun weekCap(state: RunState, day: Long): Int? {
        val before = (1..3).map { k -> state.recent.filter { it.day in (day - 7 * k - 6)..(day - 7 * k) }.sumOf { it.minutes } }.maxOrNull() ?: 0
        if (before == 0) return null
        val soFar = state.recent.filter { it.day in (day - 6) until day }.sumOf { it.minutes }
        return maxOf(15, (before * 1.1).toInt() - soFar)
    }

    /** How far ahead of the current race pace the race-pace step is. */
    const val STEP_AHEAD = 0.025

    /**
     * Time trial over the goal distance, run evenly: the beeps give the lap time of the current fitness (or of the goal
     * plus 8 % before anything is known), the last laps are as fast as is left. Its time gives the fitness (VDOT).
     */
    fun goalTest(state: RunState, goal: RunGoal, lapMeters: Int?): RunWorkout {
        val p = paces(state)
        val predicted = state.vdot?.let { Vdot.predictSeconds(it, goal.meters.toDouble()) } ?: goal.seconds * 1.08
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
            append("Контрольный: $distance в полную силу, но ровно. В конце разминки — 3–4 ускорения по 15 с. ")
            if (lapSec != null) {
                append("Первые круги — по ${Ru.lap(lapSec)} (пищит на каждом), со второй половины — быстрее, если силы есть. ")
                if (laps != null && laps % 1.0 != 0.0) append("Старт — ${startWords(laps % 1.0)}. ")
                append("Нажимай «Круг» на каждой линии: время забега — по последнему нажатию.")
            } else {
                append("Первая половина — по ${Ru.pace(pace)} на км, дальше — сколько осталось.")
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
            "12 минут так быстро, как можешь держать ровно. По дистанции пересчитаю твои темпы.", targetRpe = 9,
        )
    }

    /**
     * The hard session; while coming back from a long break it is an easy run. With a middle-distance goal every other one
     * is at race pace and of the rest two of three are tempo (threshold), one intervals (VO2max): 1500 m is mostly aerobic.
     * [goalLater]: the legs are not fresh today and a fresher hard day comes later in the week — race pace due today goes
     * there, and today runs the cycle's next session ([RunState.goalDeferred]).
     */
    fun quality(state: RunState, cutback: Boolean = false, goal: RunGoal? = null, lapMeters: Int? = null, goalLater: Boolean = false): RunWorkout {
        val w = when {
            state.returnEasy > 0 -> easy(state, cutback).let {
                val n = state.returnEasy
                it.copy(note = "После перерыва сначала втягиваемся: интервалы и темп вернутся через $n " +
                    "${Ru.plural(n, "лёгкую пробежку", "лёгкие пробежки", "лёгких пробежек")}. " + it.note)
            }
            // Tempo twice for every intervals: fading after 800 m is the threshold more than VO2max.
            goal != null && goal.middleDistance -> {
                val pos = state.qualityCount % 6
                // Race pace when it is due or owed, unless the legs are tired and a fresher day comes; else the cycle's
                // other session (the next one when race pace was due).
                val other = if (pos % 2 == 0) pos + 1 else pos
                when {
                    !goalLater && (state.goalDeferredOn != null || pos % 2 == 0) -> goalPace(state, goal, lapMeters, cutback)
                    other == 3 -> intervals(state, cutback)
                    else -> tempo(state, cutback)
                }
            }
            state.qualityCount % 2 == 0 -> intervals(state, cutback)
            else -> tempo(state, cutback)
        }
        return if (lapMeters != null) onTrack(w, lapMeters) else w
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
        return "Перерыв $gap — сегодня короче и спокойнее, чтобы втянуться."
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
        val v = s.vdot ?: return s
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
        return s.copy(vdot = to, lastVdotUpDay = day, easySigns = emptyList())
    }

    fun after(state: RunState, w: RunWorkout, r: RunResult, caps: RunCaps, day: Long = 0, goal: RunGoal? = null): RunAfter {
        val out = mutableListOf<RunOutcome>()
        val minutes = (r.durationSec / 60.0).roundToInt()
        val original = state
        // Every decision below starts from the state after any break was taken into account.
        @Suppress("NAME_SHADOWING") val state = withBreak(original, day)
        if (state.vdot != original.vdot && state.vdot != null) out += RunOutcome.VdotChanged(original.vdot, state.vdot)
        var s = state.copy(
            runs = state.runs + 1,
            // A run saved late (an older day) does not move the last run back: it would look like a break.
            lastRunDay = maxOf(day, state.lastRunDay ?: day),
            recent = (state.recent + RunLog(day, minutes)).filter { it.day > day - 31 }.takeLast(40),
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
        // A run made lighter for a bad day counts as running, but moves no ladder.
        if (w.light) return RunAfter(s, out)
        when (w.type) {
            RunType.RUN_WALK -> {
                val stage = state.walkStage.coerceAtLeast(0)
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
                s = when {
                    rpe >= 7 -> s.copy(easyMinutes = max(20, state.easyMinutes - 5)).also { out += RunOutcome.Shorter(it.easyMinutes) }
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
                s = when {
                    rpe >= 8 || !done -> s.copy(longMinutes = max(30, state.longMinutes - 5)).also { out += RunOutcome.Shorter(it.longMinutes) }
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
                if (success && w.cutback) {
                    // A lighter week's shorter session proves nothing about the full step: the ladder waits, and a miss
                    // of the full step before it still counts (seen: misses and lighter weeks in turn kept a rung for months).
                    out += RunOutcome.LighterWeek(ladder)
                    fails = 0
                } else if (success) {
                    // More reps than planned at the planned effort: the next step is too small.
                    stage = min(size - 1, cur + if (repsDone > w.reps) 2 else 1)
                    out += if (stage > cur) RunOutcome.StageUp(ladder, stage) else RunOutcome.LadderTop(ladder)
                    fails = 0
                    own = 0
                    streak = if (onPace) streak + 1 else streak
                } else {
                    fails += 1
                    own += 1
                    streak = 0
                    if (own >= 2) {
                        stage = max(0, cur - 1)
                        out += RunOutcome.StageDown(ladder, stage)
                    } else {
                        out += RunOutcome.StageRepeat(ladder)
                    }
                }
                s = if (w.type == RunType.INTERVALS) s.copy(intervalStage = stage, intervalFails = own) else s.copy(tempoStage = stage, tempoFails = own)
                s = s.copy(qualityCount = state.qualityCount + 1, qualityFails = fails, qualityOnPace = streak)
                // Tempo measured slow at a hard effort: this runner's threshold is below what the fitness says (a speed type,
                // fitness from a short race) — softer by 3 s a km, up to 12; held easily on pace, firmer again.
                if (w.type == RunType.TEMPO && r.workPace != null) {
                    val soft = state.tempoSoft
                    if (tooSlow && r.rpe >= 8 && soft < TEMPO_SOFT_MAX) {
                        s = s.copy(tempoSoft = soft + 3)
                        out += RunOutcome.TempoPace(soft + 3, softer = true)
                    } else if (onPace && soft > 0 && rq <= w.targetRpe - 2) {
                        s = s.copy(tempoSoft = soft - 3)
                        out += RunOutcome.TempoPace(soft - 3, softer = false)
                    }
                }
                // Run in place of race pace put off to a fresher day: race pace comes next.
                if (goal?.middleDistance == true && state.qualityCount % 2 == 0 && state.goalDeferredOn == null) s = s.copy(goalDeferredOn = day)
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
            }
            RunType.TEST -> {
                val tm = w.testMeters
                if (tm != null) {
                    // A time trial over the race distance: its time is the fitness. A pace outside 2:10–15:00 a km is
                    // a typo ("552" for 5:52), not a result. Without a time (no laps on a road) the GPS distance in the
                    // trial's time says the same, as in the 12-minute test.
                    val t = r.testSeconds?.takeIf { it / (tm / 1000.0) in PLAUSIBLE_PACE }
                    val d = r.workDistanceM
                    val sec = r.workSec ?: w.workSeconds
                    val raw = when {
                        t != null -> Vdot.fromRace(tm.toDouble(), t.toDouble())
                        w.lapMeters == null && done && d != null && d >= tm * 0.8 && sec > 0 && sec / (d / 1000) in PLAUSIBLE_PACE -> Vdot.fromRace(d, sec.toDouble())
                        else -> null
                    }
                    when {
                        raw == null -> out += RunOutcome.TestNotCounted
                        !believable(raw, state.vdot) -> out += RunOutcome.TestDoubtful((raw * 2).roundToInt() / 2.0)
                        else -> {
                            val nv = (raw * 2).roundToInt() / 2.0
                            if (t != null) out += RunOutcome.TestTime(tm, t)
                            out += RunOutcome.VdotChanged(state.vdot, nv)
                            s = s.copy(vdot = nv, testSoon = false)
                            // Judged on the unrounded fitness: 1495 m in 5:33 is faster than 1500 m in 5:35.
                            val reached = goal != null && raw >= Vdot.fromRace(goal.meters.toDouble(), goal.seconds.toDouble())
                            if (reached) out += RunOutcome.GoalReached
                            // Not there yet after the whole ladder: back to the 400s to build up again — not to the longest
                            // reps, the hardest sessions of the ladder.
                            if (goal != null && !reached && state.goalStage >= GOAL.lastIndex) s = s.copy(goalStage = GOAL_RESTART, goalFails = 0)
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
                            s = s.copy(vdot = nv, testSoon = false)
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
                val fast = target != null && pace != null && pace < target * 0.98
                // More than 3 % faster: the laps were not the goal's. Even laps are the skill this ladder teaches (a fast
                // runner who fades after 800 m lacks exactly that), so the step is run again at the right lap time.
                // Measured against the goal's own pace too (a step slower than the goal run at the goal is not too fast);
                // GPS on short road reps is less exact than laps.
                val tooFast = target != null && pace != null && pace < min(target, goal?.pace ?: target) * (if (w.lapMeters != null) 0.97 else 0.95)
                val cur = state.goalStage.coerceIn(0, GOAL.lastIndex)
                if (pace == null) out += RunOutcome.PaceUnknown
                if (slow) out += RunOutcome.TooSlow
                val rq = if (pace != null && !slow) rpe else r.rpe
                if (enough && rq <= 8 && !slow && w.cutback) {
                    // A lighter week's shorter session: done, and the ladder waits for the full step.
                    out += RunOutcome.LighterWeek("goal")
                    s = s.copy(goalFails = 0)
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
                } else if (state.goalFails >= 1) {
                    // Second miss in a row: one step back.
                    val back = max(0, cur - 1)
                    out += RunOutcome.StageDown("goal", back)
                    s = s.copy(goalStage = back, goalFails = 0)
                } else {
                    out += RunOutcome.StageRepeat("goal")
                    s = s.copy(goalFails = 1)
                }
                s = s.copy(qualityCount = state.qualityCount + 1, goalDeferredOn = null)
            }
            RunType.FREE -> Unit
        }
        return RunAfter(s, out)
    }
}
