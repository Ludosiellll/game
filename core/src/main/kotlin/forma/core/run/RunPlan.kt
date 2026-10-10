package forma.core.run

import kotlinx.serialization.Serializable
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
) {
    val canRunContinuously: Boolean get() = walkStage < 0
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
    @Serializable data class VdotChanged(val from: Double?, val to: Double) : RunOutcome
}

data class RunAfter(val state: RunState, val outcomes: List<RunOutcome>)

/** Caps for easy and long runs by how much the person runs. */
@Serializable
data class RunCaps(val easyMax: Int, val longMax: Int)

object RunPlan {
    private const val WALK_WARM = 5 * 60

    private data class WalkStep(val blocks: List<Pair<Int, Int>>)

    /** Run/walk ladder: (run seconds, walk seconds) blocks; ends with 30 minutes non-stop. */
    private val WALK_LADDER: List<WalkStep> = listOf(
        WalkStep(List(8) { 60 to 90 }),
        WalkStep(List(6) { 90 to 120 }),
        WalkStep(List(5) { 120 to 90 }),
        WalkStep(List(4) { 180 to 90 }),
        WalkStep(List(3) { 300 to 150 }),
        WalkStep(List(2) { 480 to 180 }),
        WalkStep(List(2) { 600 to 120 }),
        WalkStep(listOf(720 to 120, 480 to 0)),
        WalkStep(listOf(900 to 120, 420 to 0)),
        WalkStep(listOf(1200 to 0)),
        WalkStep(listOf(1320 to 0)),
        WalkStep(listOf(1500 to 0)),
        WalkStep(listOf(1680 to 0)),
        WalkStep(listOf(1800 to 0)),
    )
    val walkLadderSize: Int get() = WALK_LADDER.size

    /** Interval ladder: (repeats, work seconds, recovery seconds) at interval pace. */
    private val INTERVALS = listOf(
        Triple(5, 120, 120), Triple(6, 120, 120), Triple(5, 180, 120), Triple(6, 180, 120),
        Triple(4, 240, 180), Triple(5, 240, 180), Triple(4, 300, 180), Triple(5, 300, 180),
    )

    /** Tempo ladder: (repeats, work seconds, recovery seconds) at threshold pace. */
    private val TEMPO = listOf(
        Triple(3, 300, 60), Triple(4, 300, 60), Triple(3, 480, 120), Triple(2, 720, 120),
        Triple(1, 1200, 0), Triple(3, 600, 60), Triple(1, 1500, 0), Triple(2, 900, 120),
    )

    fun paces(state: RunState): Paces? = state.vdot?.let { PaceTable.forVdot(it) }

    fun runWalk(state: RunState): RunWorkout {
        val stage = state.walkStage.coerceIn(0, WALK_LADDER.lastIndex)
        val segs = mutableListOf(Segment(SegmentKind.WARMUP, WALK_WARM))
        for ((run, walk) in WALK_LADDER[stage].blocks) {
            segs += Segment(SegmentKind.RUN, run, paces(state)?.easy)
            if (walk > 0) segs += Segment(SegmentKind.WALK, walk)
        }
        segs += Segment(SegmentKind.COOLDOWN, WALK_WARM)
        val note = "Бег в разговорном темпе, ходьба быстрым шагом. Разминка и заминка — ходьба."
        return RunWorkout(RunType.RUN_WALK, "Бег с ходьбой · шаг ${stage + 1} из ${WALK_LADDER.size}", segs, note, targetRpe = 6, stage = stage)
    }

    fun easy(state: RunState, cutback: Boolean = false): RunWorkout {
        val min = scaled(state.easyMinutes, cutback)
        return RunWorkout(
            RunType.EASY, "Лёгкий бег · $min мин",
            listOf(Segment(SegmentKind.RUN, min * 60, paces(state)?.easy)),
            "Темп, в котором можно говорить предложениями. Медленнее — нормально.", targetRpe = 4,
        )
    }

    fun long(state: RunState, cutback: Boolean = false): RunWorkout {
        val min = scaled(state.longMinutes, cutback)
        return RunWorkout(
            RunType.LONG, "Длинная пробежка · $min мин",
            listOf(Segment(SegmentKind.RUN, min * 60, paces(state)?.easy)),
            "Ровно и спокойно, как лёгкий бег. Главное — время на ногах.", targetRpe = 5,
        )
    }

    fun intervals(state: RunState, cutback: Boolean = false): RunWorkout {
        val stage = state.intervalStage.coerceIn(0, INTERVALS.lastIndex)
        val (n0, work, rest) = INTERVALS[stage]
        val n = if (cutback) max(2, n0 - 2) else n0
        val p = paces(state)
        val segs = mutableListOf(Segment(SegmentKind.WARMUP, 10 * 60, p?.easy))
        repeat(n) { i ->
            segs += Segment(SegmentKind.WORK, work, p?.let { PaceRange(it.interval - 4, it.interval + 4) })
            if (i < n - 1) segs += Segment(SegmentKind.RECOVERY, rest, p?.easy)
        }
        segs += Segment(SegmentKind.COOLDOWN, 10 * 60, p?.easy)
        val note = "Ускорения тяжёлые, но ровные: последнее не медленнее первого. Между ними — трусца."
        return RunWorkout(RunType.INTERVALS, "Интервалы · $n × ${work / 60} мин", segs, note, targetRpe = 8, stage = stage)
    }

    fun tempo(state: RunState, cutback: Boolean = false): RunWorkout {
        val stage = state.tempoStage.coerceIn(0, TEMPO.lastIndex)
        val (n0, work, rest) = TEMPO[stage]
        val n = if (cutback && n0 > 1) n0 - 1 else n0
        val workSec = if (cutback && n0 == 1) (work * 0.75).roundToInt() else work
        val p = paces(state)
        val segs = mutableListOf(Segment(SegmentKind.WARMUP, 10 * 60, p?.easy))
        repeat(n) { i ->
            segs += Segment(SegmentKind.WORK, workSec, p?.let { PaceRange(it.threshold - 5, it.threshold + 5) })
            if (i < n - 1 && rest > 0) segs += Segment(SegmentKind.RECOVERY, rest, p?.easy)
        }
        segs += Segment(SegmentKind.COOLDOWN, 10 * 60, p?.easy)
        val title = if (n == 1) "Темповый бег · ${workSec / 60} мин" else "Темповый бег · $n × ${workSec / 60} мин"
        val note = "«Комфортно тяжело»: можно сказать пару слов, но не болтать."
        return RunWorkout(RunType.TEMPO, title, segs, note, targetRpe = 7, stage = stage)
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

    fun quality(state: RunState, cutback: Boolean = false): RunWorkout =
        if (state.qualityCount % 2 == 0) intervals(state, cutback) else tempo(state, cutback)

    private fun scaled(min: Int, cutback: Boolean) = if (cutback) max(15, (min * 0.75 / 5).roundToInt() * 5) else min

    fun after(state: RunState, w: RunWorkout, r: RunResult, caps: RunCaps): RunAfter {
        val out = mutableListOf<RunOutcome>()
        var s = state.copy(runs = state.runs + 1)
        val done = r.completed >= 0.95
        when (w.type) {
            RunType.RUN_WALK -> {
                val stage = state.walkStage.coerceAtLeast(0)
                s = when {
                    done && r.rpe <= 7 -> {
                        val next = stage + 1
                        if (next >= WALK_LADDER.size) {
                            out += RunOutcome.Graduated
                            s.copy(walkStage = -1, easyMinutes = 30, longMinutes = max(state.longMinutes, 35))
                        } else {
                            out += RunOutcome.StageUp("walk", next)
                            s.copy(walkStage = next)
                        }
                    }
                    r.completed < 0.8 || r.rpe >= 10 -> {
                        val back = max(0, stage - 1)
                        out += RunOutcome.StageDown("walk", back)
                        s.copy(walkStage = back)
                    }
                    else -> { out += RunOutcome.StageRepeat("walk"); s }
                }
            }
            RunType.EASY -> {
                s = when {
                    r.rpe >= 7 -> s.copy(easyMinutes = max(20, state.easyMinutes - 5)).also { out += RunOutcome.Shorter(it.easyMinutes) }
                    done && r.rpe <= 5 && state.easyMinutes < caps.easyMax ->
                        s.copy(easyMinutes = min(caps.easyMax, state.easyMinutes + 3)).also { out += RunOutcome.Longer(it.easyMinutes) }
                    else -> s.also { out += RunOutcome.Same(state.easyMinutes) }
                }
            }
            RunType.LONG -> {
                // Grow the long run by at most ~10 % at a time: single-session spikes raise injury risk.
                val step = max(5, (state.longMinutes * 0.1 / 5).roundToInt() * 5)
                s = when {
                    r.rpe >= 8 || !done -> s.copy(longMinutes = max(30, state.longMinutes - 5)).also { out += RunOutcome.Shorter(it.longMinutes) }
                    r.rpe <= 6 && state.longMinutes < caps.longMax ->
                        s.copy(longMinutes = min(caps.longMax, state.longMinutes + step)).also { out += RunOutcome.Longer(it.longMinutes) }
                    else -> s.also { out += RunOutcome.Same(state.longMinutes) }
                }
            }
            RunType.INTERVALS, RunType.TEMPO -> {
                val ladder = if (w.type == RunType.INTERVALS) "intervals" else "tempo"
                val size = if (w.type == RunType.INTERVALS) INTERVALS.size else TEMPO.size
                val cur = if (w.type == RunType.INTERVALS) state.intervalStage else state.tempoStage
                val next = when {
                    done && r.rpe <= w.targetRpe -> min(size - 1, cur + 1).also { out += RunOutcome.StageUp(ladder, it) }
                    !done && (r.rpe >= 10 || r.completed < 0.7) -> max(0, cur - 1).also { out += RunOutcome.StageDown(ladder, it) }
                    else -> cur.also { out += RunOutcome.StageRepeat(ladder) }
                }
                s = if (w.type == RunType.INTERVALS) s.copy(intervalStage = next) else s.copy(tempoStage = next)
                s = s.copy(qualityCount = state.qualityCount + 1)
                val v = state.vdot
                val target = paces(state)?.let { if (w.type == RunType.INTERVALS) it.interval else it.threshold }
                if (v != null && target != null && r.workPace != null && r.workPace > 0) {
                    val faster = (target - r.workPace) / target
                    val nv = when {
                        done && faster > 0.02 && r.rpe <= w.targetRpe -> v + 0.5
                        faster < -0.03 && r.rpe >= 9 -> v - 0.5
                        else -> v
                    }
                    if (nv != v) { out += RunOutcome.VdotChanged(v, nv); s = s.copy(vdot = nv) }
                }
            }
            RunType.TEST -> {
                val d = r.workDistanceM
                if (d != null && d > 500) {
                    val nv = (Vdot.fromRace(d, 12 * 60.0) * 2).roundToInt() / 2.0
                    out += RunOutcome.VdotChanged(state.vdot, nv)
                    s = s.copy(vdot = nv)
                }
            }
            RunType.FREE -> Unit
        }
        return RunAfter(s, out)
    }
}
