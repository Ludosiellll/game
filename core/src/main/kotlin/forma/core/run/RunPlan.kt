package forma.core.run

import forma.core.text.Ru
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
    /** Epoch day of the last 12-minute test. */
    val lastTestDay: Long? = null,
    /** Quality sessions in a row run on pace; three of them lift VDOT by 1. */
    val qualityOnPace: Int = 0,
    /** Quality sessions in a row that were not completed as planned. */
    val qualityFails: Int = 0,
    /** Run/walk sessions in a row that were not completed comfortably. */
    val walkFails: Int = 0,
    val lastVdotUpDay: Long? = null,
    val lastRunDay: Long? = null,
    /** Runs of the last 30 days: the long-run cap is based on them. */
    val recent: List<RunLog> = emptyList(),
    /** Easy runs still to do after a long break before intervals and tempo come back. */
    val returnEasy: Int = 0,
) {
    val canRunContinuously: Boolean get() = walkStage < 0

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
        val title = "Бег с ходьбой · неделя ${stage / 3 + 1}, тренировка ${stage % 3 + 1}"
        return RunWorkout(RunType.RUN_WALK, title, segs, note, targetRpe = 6, stage = stage)
    }

    fun easy(state: RunState, cutback: Boolean = false): RunWorkout {
        val min = scaled(state.easyMinutes, cutback)
        return RunWorkout(
            RunType.EASY, "Лёгкий бег · $min мин",
            listOf(Segment(SegmentKind.RUN, min * 60, paces(state)?.easy)),
            "Темп, в котором можно говорить предложениями. Медленнее — нормально.", targetRpe = 4,
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

    /** The weekly hard session; while coming back from a long break it is an easy run. */
    fun quality(state: RunState, cutback: Boolean = false): RunWorkout = when {
        state.returnEasy > 0 -> easy(state, cutback).let {
            val n = state.returnEasy
            it.copy(note = "После перерыва сначала втягиваемся: интервалы и темп вернутся через $n " +
                "${Ru.plural(n, "лёгкую пробежку", "лёгкие пробежки", "лёгких пробежек")}. " + it.note)
        }
        state.qualityCount % 2 == 0 -> intervals(state, cutback)
        else -> tempo(state, cutback)
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
            qualityOnPace = 0,
            qualityFails = 0,
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

    fun after(state: RunState, w: RunWorkout, r: RunResult, caps: RunCaps, day: Long = 0): RunAfter {
        val out = mutableListOf<RunOutcome>()
        val minutes = (r.durationSec / 60.0).roundToInt()
        val original = state
        // Every decision below starts from the state after any break was taken into account.
        @Suppress("NAME_SHADOWING") val state = withBreak(original, day)
        if (state.vdot != original.vdot && state.vdot != null) out += RunOutcome.VdotChanged(original.vdot, state.vdot)
        var s = state.copy(
            runs = state.runs + 1,
            lastRunDay = day,
            recent = (state.recent + RunLog(day, minutes)).filter { it.day > day - 31 }.takeLast(40),
            returnEasy = max(0, state.returnEasy - 1),
        )
        val done = r.completed >= 0.95
        when (w.type) {
            RunType.RUN_WALK -> {
                val stage = state.walkStage.coerceAtLeast(0)
                s = when {
                    done && r.rpe <= 6 -> {
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
                if (done && r.rpe <= 6) s = s.copy(walkFails = 0)
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
                // Grow the long run, but never past 110 % of the longest run of the last 30 days.
                val longest = maxOf(minutes, state.longestRecent(day) ?: 0)
                val next = minOf(caps.longMax, state.longMinutes + 5, (longest * 1.10).toInt())
                s = when {
                    r.rpe >= 8 || !done -> s.copy(longMinutes = max(30, state.longMinutes - 5)).also { out += RunOutcome.Shorter(it.longMinutes) }
                    r.rpe <= 6 && next > state.longMinutes ->
                        s.copy(longMinutes = next).also { out += RunOutcome.Longer(it.longMinutes) }
                    else -> s.also { out += RunOutcome.Same(state.longMinutes) }
                }
            }
            RunType.INTERVALS, RunType.TEMPO -> {
                val ladder = if (w.type == RunType.INTERVALS) "intervals" else "tempo"
                val size = if (w.type == RunType.INTERVALS) INTERVALS.size else TEMPO.size
                val cur = if (w.type == RunType.INTERVALS) state.intervalStage else state.tempoStage
                val target = paces(s)?.let { if (w.type == RunType.INTERVALS) it.interval else it.threshold }
                val success = done && r.rpe <= w.targetRpe
                // On pace: work pace measured and no more than 2 % slower than the target.
                val onPace = success && target != null && r.workPace != null && r.workPace <= target * 1.02
                var stage = cur
                var fails = state.qualityFails
                var streak = state.qualityOnPace
                if (success) {
                    stage = min(size - 1, cur + 1)
                    out += RunOutcome.StageUp(ladder, stage)
                    fails = 0
                    streak = if (onPace) streak + 1 else streak
                } else {
                    fails += 1
                    streak = 0
                    if (fails >= 2) {
                        stage = max(0, cur - 1)
                        out += RunOutcome.StageDown(ladder, stage)
                    } else {
                        out += RunOutcome.StageRepeat(ladder)
                    }
                }
                s = if (w.type == RunType.INTERVALS) s.copy(intervalStage = stage) else s.copy(tempoStage = stage)
                s = s.copy(qualityCount = state.qualityCount + 1, qualityFails = fails, qualityOnPace = streak)
                val v = s.vdot
                if (v != null && streak >= 3 && (state.lastVdotUpDay == null || day - state.lastVdotUpDay >= 21)) {
                    out += RunOutcome.VdotChanged(v, v + 1)
                    s = s.copy(vdot = v + 1, qualityOnPace = 0, lastVdotUpDay = day)
                }
                if (v != null && fails >= 3) {
                    out += RunOutcome.VdotChanged(v, v - 1)
                    s = s.copy(vdot = v - 1, qualityFails = 0)
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
