package forma.core.strength

import forma.core.floorTo
import forma.core.model.Exercise
import forma.core.model.LoadKind
import forma.core.roundTo
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@Serializable
enum class SlotRole { MAIN, SECONDARY, ACCESSORY }

/** One exercise inside a workout template: how many sets, which rep range, how far from failure. */
@Serializable
data class SlotSpec(
    val exerciseId: String,
    val sets: Int,
    val repMin: Int,
    val repMax: Int,
    /** Reps in reserve to aim for on working sets. */
    val rir: Int,
    val restSec: Int,
    val role: SlotRole,
    /** Load jump when the top of the range is reached; null — the exercise's own step. */
    val increment: Double? = null,
) {
    val key: String get() = "$exerciseId@$repMin-$repMax"
}

/**
 * Progress of one exercise in one rep range. [weight] is the working load in kg
 * (extra weight for body-weight exercises; null until the first session calibrates it).
 */
@Serializable
data class SlotState(
    val weight: Double? = null,
    val reps: Int,
    /** Sessions in a row that ended without progress. */
    val stalls: Int = 0,
    /** Sessions in a row clearly below the target. */
    val misses: Int = 0,
    val sessions: Int = 0,
    /** Linear phase: while it lasts, a novice adds weight after every successful session (ends at the first real stall). */
    val linear: Boolean = true,
    /** Successful sessions in a row with nothing to spare on the "+" set: the weight is being consolidated. */
    val steady: Int = 0,
    /** Weight before the last cut (Reduce/Reset) and the first-set reps that led to it. */
    val cutFrom: Double? = null,
    val cutReps: Int? = null,
) {
    companion object {
        fun fresh(spec: SlotSpec) = SlotState(reps = spec.repMin)
    }
}

@Serializable
/**
 * The kind of a session for one exercise: as usual, a lighter week, the days before a goal's date (half the sets, the
 * weight kept, no "+" set), or the goal's test itself (warm-ups and single attempts). The last two leave the progress as
 * it was: they serve the test, not the next weight.
 */
enum class Phase { NORMAL, DELOAD, TAPER, TEST }

/** What to do today for one exercise. */
@Serializable
data class Prescription(
    val weight: Double?,
    val reps: Int,
    val sets: Int,
    val rir: Int,
    /** First time: find the working weight — sets of as many reps as cleanly possible with 1–2 in reserve. */
    val calibrate: Boolean,
    /** Suggested starting point for calibration. */
    val startHint: Double? = null,
    val phase: Phase = Phase.NORMAL,
    /** The last set is "[reps]+": the target, then more while it goes well, stopping with [topRir] reps in reserve. */
    val top: Boolean = false,
    /** The first week of a block: a rep more in reserve than the plan's; a session short of the target is not a miss. */
    val intro: Boolean = false,
    /**
     * Reps left in reserve at the end of the "+" set: 1, or 2 on the legs a day or two before a hard run (legs fresher
     * for it). Its spare reps count the same either way, so the weights grow as fast.
     */
    val topRir: Int = 1,
    /** The test of the max ([Phase.TEST]): each attempt's weight at one rep, the first one is [weight]. */
    val attempts: List<Double>? = null,
)

/** A working set as logged. [rir] is optional self-report. */
@Serializable
data class DoneSet(val weight: Double, val reps: Int, val rir: Int? = null)

/** Why the next target changed. The UI turns these into short sentences. */
@Serializable
sealed interface Outcome {
    @Serializable data class Calibrated(val weight: Double, val reps: Int) : Outcome
    @Serializable data class AddReps(val weight: Double, val from: Int, val to: Int) : Outcome
    /** Missed the reps: the target comes down within the range, the weight stays. */
    @Serializable data class LowerReps(val weight: Double, val from: Int, val to: Int) : Outcome
    @Serializable data class AddWeight(val from: Double, val to: Double, val reps: Int) : Outcome
    @Serializable data class Jump(val from: Double, val to: Double, val reps: Int) : Outcome
    @Serializable data class Hold(val weight: Double, val reps: Int, val reason: HoldReason) : Outcome
    @Serializable data class Reduce(val from: Double, val to: Double, val reps: Int) : Outcome
    @Serializable data class Reset(val from: Double, val to: Double, val reps: Int) : Outcome
    @Serializable data object NotDone : Outcome
    @Serializable data object Deload : Outcome
}

@Serializable
enum class HoldReason {
    NEAR_MISS, FIRST_MISS, FEWER_SETS, LIGHTER, CONSOLIDATE, NOT_THE_WEIGHT, AT_TOP,
    /** Short of the target, but every short set was stopped with the reps left in reserve: a choice, not the weight. */
    STOPPED_EARLY,
    /** The first week of a block asks for a rep more in reserve: falling short of the target then is what was asked. */
    INTRO,
    /** The days before the goal's date: fewer sets at the same weight, the progress kept for after the test. */
    TAPER,
    /** The goal's test of the max: single attempts, the progress of the plan as it was. */
    TEST,
}

data class Evaluation(val state: SlotState, val outcome: Outcome, val e1rm: Double?)

/** In-session hint after a set: change the weight for the next sets. */
data class Adjustment(val weight: Double, val reps: Int, val heavier: Boolean)

/**
 * The next set, said after every set — not only when the weight changes (silence read as "no idea"). [reserve]: the
 * reserve the last set really had (missed reps count below zero), null when unknown; [plus]: the next one is the "N+".
 */
data class NextSet(val weight: Double, val reps: Int, val change: Int, val reserve: Int?, val plannedReserve: Int, val plus: Boolean, val calibrate: Boolean)

/**
 * Progression on top of an e1RM model, with feedback from the last set.
 *
 * - First session of an exercise: find the working weight. Every set goes as far as it cleanly can with 1–2 reps in
 *   reserve, the weight is corrected between sets from the estimate, and the best estimate sets the working weight.
 * - The last set of main and secondary lifts is "N+" (autoregulation in the spirit of APRE, Mann et al. 2010): reps
 *   beyond the target show how much stronger the lifter got even when "how many more could you do" is skipped.
 * - Success: novices on compound lifts add weight after every session (linear phase; a double step when the "+" set
 *   shows 2+ spare reps); everybody else climbs the rep range, then adds weight. 4+ spare reps → straight to the
 *   weight the estimate supports (+10 % at most, novices +15 %). Nothing to spare on the "+" set → the weight stays
 *   (a rated grind is believed for two sessions, then the reps go up instead of the weight).
 * - Short: a near miss repeats the weight; a clear miss moves half way toward what was shown (all the way after two
 *   misses or when the first set was 3+ reps short), never more than −10 % at once; three stalls in a row → −10 % and
 *   back to the bottom of the range (Starting Strength reset). The linear phase ends at the first of these.
 * - Never below 85 % of what the best estimate of the last weeks supports: a few bad days or reps left on the table
 *   cannot drag the weights down session after session.
 * - Back after 2+ weeks off: 5–15 % lighter.
 */
object Progression {
    private const val CALIBRATION_RIR = 2
    private const val TOP_RIR = 1
    private const val FLOOR = 0.85
    /** Reps beyond the top of the range allowed while the next weight is out of reach. */
    private const val OVERFLOW = 4
    /** Reps to failure with the body alone from which an assisted exercise gets no help: sets of three, two in reserve. */
    private const val OWN_RTF = 5.0

    /** Total load in kg that the muscles move, used for e1RM (the moved share of body weight included). */
    fun effectiveLoad(ex: Exercise, weight: Double, bodyWeight: Double): Double =
        if (ex.load == LoadKind.BODYWEIGHT_PLUS) ex.bodyShare * bodyWeight + weight else weight

    /** Weight to put on (or, for assisted exercises, negative assistance) for a total [load]. */
    fun toWeight(ex: Exercise, load: Double, bodyWeight: Double): Double {
        if (ex.load != LoadKind.BODYWEIGHT_PLUS) return load
        val extra = load - ex.bodyShare * bodyWeight
        return if (ex.assisted) max(-maxAssist(ex, bodyWeight), extra) else max(0.0, extra)
    }

    /**
     * The lightest load the plan gives. For an assisted exercise (pull-ups, dips) it is the body alone once [e1rm] says
     * five or more reps to failure with it: help (a band, the gravitron) is for those who cannot pull themselves up yet.
     * Not every gym has one, and whoever does five builds up with the body alone, a few reps at a time — a friend doing
     * his sets with the body and +5 kg was given «помощь 7.5 кг» to keep two reps in reserve. Otherwise [minWeight].
     */
    fun lightest(ex: Exercise, bodyWeight: Double, e1rm: Double?): Double =
        if (ex.assisted && e1rm != null && e1rm > 0 && OneRm.rtfAt(e1rm, effectiveLoad(ex, 0.0, bodyWeight)) >= OWN_RTF - 1e-6) 0.0
        else minWeight(ex, bodyWeight)

    /** Reps with [rir] in reserve at [weight] by [e1rm], at least one, at most [most]. */
    private fun repsAt(ex: Exercise, e1rm: Double, weight: Double, rir: Int, bodyWeight: Double, most: Int): Int =
        floor(OneRm.rtfAt(e1rm, effectiveLoad(ex, weight, bodyWeight)) - rir).toInt().coerceIn(1, max(1, most))

    /** Most assistance an assisted machine or band gives: about 70 % of the moved body weight. */
    fun maxAssist(ex: Exercise, bodyWeight: Double): Double = floorTo(ex.bodyShare * bodyWeight * 0.7, ex.step)

    /** The person's own factor on start-weight guesses from how far off the earlier ones were ([ratios]: found / guessed). */
    fun ownStart(ratios: List<Double>): Double {
        if (ratios.size < 2) return 1.0
        val s = ratios.sorted()
        val m = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        return m.coerceIn(0.4, 2.5)
    }

    fun startHint(ex: Exercise, bodyWeight: Double, female: Boolean, trained: Double): Double? {
        if (ex.load != LoadKind.EXTERNAL) return if (ex.load == LoadKind.BODYWEIGHT_PLUS) 0.0 else null
        var w = ex.startRatio * bodyWeight * (if (female) 0.6 else 1.0) * trained
        if (ex.bar != null) w = max(ex.bar, w)
        val st = ex.stepAt(w)
        return max(st, roundTo(w, st))
    }

    fun prescribe(
        ex: Exercise,
        spec: SlotSpec,
        state: SlotState,
        phase: Phase = Phase.NORMAL,
        rirOffset: Int = 0,
        e1rm: Double? = null,
        bodyWeight: Double = 75.0,
        hint: Double? = null,
        daysSinceLast: Long? = null,
        topRir: Int = TOP_RIR,
        novice: Boolean = false,
        recentBest: Double? = null,
    ): Prescription {
        val rir = (spec.rir + rirOffset).coerceIn(0, 5)
        if (ex.load == LoadKind.TIMED) {
            return Prescription(null, state.reps, setsFor(spec, phase), rir, calibrate = false, phase = phase)
        }
        // On the exercise's own steps: a step changed for this gym (5 kg plates only) applies to the stored weight too.
        var weight = state.weight?.let { floorTo(it, ex.stepAt(it)) }
        var reps = state.reps
        var calibrate = false
        // The best the person showed lately: whether help is needed at all ([lightest]).
        val known = listOfNotNull(e1rm, recentBest).filter { it > 0 }.maxOrNull()
        val lowest = lightest(ex, bodyWeight, known)
        if (weight == null) {
            if (e1rm != null && e1rm > 0) {
                // A new rep range of a known exercise: from the estimate, lighter after a break like any weight.
                val raw = toWeight(ex, OneRm.loadFor(e1rm, state.reps, rir) * layoffFactor(daysSinceLast), bodyWeight)
                weight = floorTo(raw, ex.stepAt(raw)).coerceAtLeast(lowest)
                // The body alone is more than the range's bottom asks for: the reps it allows, built up from there.
                if (raw < lowest - 1e-9 && known != null) reps = repsAt(ex, known, lowest, rir, bodyWeight, state.reps)
            } else {
                calibrate = true
            }
        } else {
            // Help left from before (an older version, a weaker month) while the sets of the last weeks show five and
            // more with the body alone: the body alone, at the reps that go with it.
            if (ex.assisted && weight < lowest - 1e-9 && known != null) {
                reps = repsAt(ex, known, lowest, rir, bodyWeight, state.reps)
                weight = lowest
            }
            // Back after a break: strength holds for about three weeks, then fades.
            val factor = layoffFactor(daysSinceLast)
            if (factor < 1.0) {
                val load = effectiveLoad(ex, weight, bodyWeight)
                weight = down(ex, weight, toWeight(ex, load * factor, bodyWeight), lowest)
            }
        }
        val deload = phase == Phase.DELOAD
        if (deload && weight != null) {
            weight = down(ex, weight, toWeight(ex, effectiveLoad(ex, weight, bodyWeight) * 0.9, bodyWeight), lowest)
        }
        // A novice's first time on an exercise: two sets, three reps short of failure. A beginner cannot tell "two left"
        // yet, and the first time is the one that makes the muscles sore; from the next session, the plan's sets.
        val gentle = calibrate && novice && !deload
        val sets = setsFor(spec, phase).let { if (gentle) min(it, 2) else it }
        val top = !deload && !calibrate && sets >= 2 && phase != Phase.TAPER
        val plannedRir = when {
            deload -> rir + 2
            calibrate -> CALIBRATION_RIR + if (gentle) 1 else 0
            else -> rir
        }
        return Prescription(weight, reps, sets, plannedRir, calibrate, if (calibrate) hint else null, phase, top, intro = rirOffset > 0 && !calibrate, topRir = topRir)
    }

    /** Lowest weight the exercise allows: the most assistance for assisted body-weight exercises, the empty bar, or 0. */
    fun minWeight(ex: Exercise, bodyWeight: Double): Double = when {
        ex.assisted -> -maxAssist(ex, bodyWeight)
        ex.load == LoadKind.EXTERNAL && ex.bar != null -> ex.bar
        // A machine, cable or dumbbell is never 0 kg: its lightest is one step (the lightest plate or dumbbell).
        ex.load == LoadKind.EXTERNAL -> ex.fineStep ?: ex.step
        else -> 0.0
    }

    /**
     * [raw] (lighter than [w]) on the exercise's steps: the step below, unless that cuts far more than meant — then the
     * nearest step, never above [w]. A coarse step keeps the weight rather than cutting it by half (10 % of a 5 kg leg
     * extension is not 5 kg); the lighter week then comes from fewer sets and more reps in reserve. Never below the
     * lightest load ([lowest]; with the body alone that is the body, not help).
     */
    private fun down(ex: Exercise, w: Double, raw: Double, lowest: Double): Double {
        val st = ex.stepAt(raw)
        val floor = floorTo(raw, st)
        val to = if (raw - floor > 0.05 * abs(w)) min(w, roundTo(raw, st)) else floor
        return to.coerceAtLeast(lowest)
    }

    /** 10 % lighter in total load (for body-weight exercises the body counts too). */
    private fun lighter(ex: Exercise, w: Double, bodyWeight: Double) = toWeight(ex, effectiveLoad(ex, w, bodyWeight) * 0.9, bodyWeight)

    /** Load multiplier for the first session after [days] without this exercise. */
    fun layoffFactor(days: Long?): Double = when {
        days == null || days <= 14 -> 1.0
        days <= 28 -> 0.95
        days <= 56 -> 0.90
        else -> 0.85
    }

    private fun setsFor(spec: SlotSpec, phase: Phase) =
        if (phase == Phase.DELOAD) max(1, ceil(spec.sets / 2.0).toInt()) else spec.sets

    /**
     * Reps in reserve for a set the lifter did not rate. Calibration sets and "+" sets were told to stop with 2 (3 the
     * first time for a novice) and 1 in reserve; regular sets at the target count as planned, extra reps come out of the
     * reserve; short sets are taken as done to failure.
     */
    fun assumedRir(set: DoneSet, index: Int, p: Prescription): Int = set.rir ?: when {
        p.calibrate -> p.rir
        // Past the target it stopped where told; at the target only, it may have had less left: the closer one.
        p.top && index == p.sets - 1 && set.reps >= p.reps -> if (set.reps > p.reps) p.topRir else TOP_RIR
        set.reps >= p.reps -> max(0, p.rir - (set.reps - p.reps))
        else -> 0
    }

    /**
     * Best e1RM of the session. Only sets of 1–12 reps with at most 4 in reserve count (estimates from longer
     * or easier sets are unreliable); if there are none, every set counts.
     */
    fun e1rmOf(ex: Exercise, sets: List<DoneSet>, p: Prescription, bodyWeight: Double): Double? {
        val rated = sets.mapIndexedNotNull { i, s -> if (s.reps > 0) s to assumedRir(s, i, p) else null }
        val good = rated.filter { (s, rir) -> s.reps <= 12 && rir <= 4 }.ifEmpty { rated }
        return good.maxOfOrNull { (s, rir) -> OneRm.estimate(effectiveLoad(ex, s.weight, bodyWeight), s.reps, rir) }
    }

    /** Sets rated against [target] and [plannedRir] alone (no calibration, no "+" set). */
    fun e1rmOf(ex: Exercise, sets: List<DoneSet>, target: Int, plannedRir: Int, bodyWeight: Double): Double? =
        e1rmOf(ex, sets, Prescription(null, target, sets.size, plannedRir, calibrate = false), bodyWeight)

    /**
     * Working e1RM after a session. Within ±10 % of the current value it moves half way up or 30 % down;
     * outliers move it only 15 %. One session changes it by at most +5 % (+7.5 % for novices) or −7.5 %.
     */
    fun smoothE1rm(old: Double?, observed: Double, novice: Boolean = false): Double {
        if (old == null || old <= 0) return observed
        val ratio = observed / old
        val alpha = if (ratio in 0.90..1.10) (if (observed > old) 0.5 else 0.3) else 0.15
        val next = old + alpha * (observed - old)
        return next.coerceIn(old * 0.925, old * (if (novice) 1.075 else 1.05))
    }

    /** Linear phase: novices, compound lifts with external load, while one step is at most 7.5 % of the weight (simulations: bigger steps cause more misses and less gain). */
    private fun linearPhase(ex: Exercise, spec: SlotSpec, state: SlotState, novice: Boolean, w: Double, inc: Double) =
        novice && state.linear && ex.compound && ex.load == LoadKind.EXTERNAL && spec.role != SlotRole.ACCESSORY &&
            w > 0 && inc / w <= 0.075

    /**
     * Next target from a finished session.
     * [novice] enables the linear phase and bigger jumps; [recentBest] is the best e1RM of the last weeks (the floor).
     */
    fun evaluate(
        ex: Exercise,
        spec: SlotSpec,
        state: SlotState,
        p: Prescription,
        done: List<DoneSet>,
        bodyWeight: Double = 75.0,
        novice: Boolean = false,
        recentBest: Double? = null,
    ): Evaluation {
        val work = done.filter { it.reps > 0 }
        if (work.isEmpty()) return Evaluation(state, Outcome.NotDone, null)
        val sessions = state.sessions + 1
        if (ex.load == LoadKind.TIMED) {
            if (p.phase == Phase.DELOAD) return Evaluation(state.copy(sessions = sessions), Outcome.Deload, null)
            return evaluateTimed(spec, state, p, work)
        }

        val e1rm = e1rmOf(ex, done, p, bodyWeight)!!
        if (p.phase == Phase.DELOAD) return Evaluation(state.copy(sessions = sessions), Outcome.Deload, e1rm)
        // The lightest load to go to: for pull-ups and dips the body alone once this or recent sets show five and more.
        val lowest = lightest(ex, bodyWeight, listOfNotNull(e1rm, recentBest).maxOrNull())
        // The taper and the test serve the goal's day: the weight and the reps to aim for stay as they were.
        if (p.phase == Phase.TEST || p.phase == Phase.TAPER && !p.calibrate) {
            val reason = if (p.phase == Phase.TAPER) HoldReason.TAPER else HoldReason.TEST
            return Evaluation(state.copy(sessions = sessions), Outcome.Hold(state.weight ?: p.weight ?: 0.0, state.reps, reason), e1rm)
        }

        fun supported(e: Double, reps: Int, rir: Int) = toWeight(ex, OneRm.loadFor(e, reps, rir), bodyWeight)

        if (p.calibrate || state.weight == null || p.weight == null) {
            // Sets of very different reps were mixed while searching: trust the heaviest one (fewest reps to
            // failure, least extrapolation), not the most optimistic estimate.
            val rated = done.mapIndexedNotNull { i, s -> if (s.reps > 0) s to assumedRir(s, i, p) else null }
            // A second set at a weight already done is a tired repeat: the first set at that weight speaks for it.
            val fresh = rated.filterIndexed { i, (s, _) -> rated.take(i).none { abs(it.first.weight - s.weight) < 1e-9 } }
            val best = fresh.minWith(compareBy<Pair<DoneSet, Int>> { (s, rir) -> s.reps + rir }.thenByDescending { it.first.weight })
            val estimate = OneRm.estimate(effectiveLoad(ex, best.first.weight, bodyWeight), best.first.reps, best.second)
            val raw = supported(estimate, spec.repMin, spec.rir)
            // Help only for whoever the trusted set says needs it (five and more with the body alone need none).
            val low = lightest(ex, bodyWeight, listOfNotNull(estimate, recentBest).maxOrNull())
            val w = max(low, floorTo(raw, ex.stepAt(raw)))
            // The range's bottom asks for more than the lightest load allows (an empty bar too heavy for 8, five
            // pull-ups with the body alone): the reps there with the planned reserve, built up from there — not a
            // target missed session after session.
            val reps = if (raw < low - 1e-9) repsAt(ex, estimate, w, spec.rir, bodyWeight, spec.repMin) else spec.repMin
            return Evaluation(SlotState(w, reps, 0, 0, sessions, state.linear), Outcome.Calibrated(w, reps), estimate)
        }

        val target = p.reps
        // The planned sets in the order done (the index tells which one was the "+" set). Sets done lighter than
        // planned — usually after the app's own hint — count as small misses at the planned weight; within half a step
        // below it is the plan's weight (a 65 kg plan on a stack that goes 64, 68). Every set at the plan or above it:
        // the lowest weight they all reached is what was done, and what the reps are read against.
        val planned = done.filter { it.reps > 0 }.take(p.sets)
        val tol = ex.stepAt(p.weight) / 2
        val lighter = planned.count { it.weight < p.weight - tol }
        val w = if (lighter == 0 && planned.isNotEmpty()) max(p.weight, floorTo(planned.minOf { it.weight }, ex.stepAt(p.weight))) else p.weight
        @Suppress("NAME_SHADOWING") val state = if (w > (state.weight ?: 0.0)) state.copy(weight = w) else state
        val step = ex.stepAt(w)
        val inc = spec.increment ?: step
        // 85 % of the total load (for assisted exercises that means more help, not 85 % of it).
        val floor = recentBest?.let { floorTo(toWeight(ex, OneRm.loadFor(it, target, p.rir) * FLOOR, bodyWeight), step) }
        fun floored(x: Double) = if (floor != null) max(x, min(floor, w)) else x

        if (lighter == planned.size) {
            return Evaluation(state.copy(sessions = sessions), Outcome.Hold(w, target, HoldReason.LIGHTER), e1rm)
        }
        val regularCount = if (p.top) p.sets - 1 else p.sets
        val regular = planned.take(regularCount)
        val top = if (p.top) planned.getOrNull(regularCount) else null
        val allAtTarget = lighter == 0 && planned.all { it.reps >= target }
        if (allAtTarget && planned.size < p.sets) {
            return Evaluation(state.copy(sessions = sessions), Outcome.Hold(w, target, HoldReason.FEWER_SETS), e1rm)
        }
        val hitAll = allAtTarget
        // The block's first week asked for more in reserve: a rep or two short of the target is following it, not a miss —
        // the weight and the target stay (before, this lowered the reps or the weight of whoever did as told). Further
        // short than that is a miss as in any week.
        val introNear = max(target - 2, kotlin.math.ceil(target * 0.75).toInt())
        if (p.intro && !hitAll && lighter == 0 && planned.size >= p.sets && planned.all { it.reps >= introNear }) {
            return Evaluation(state.copy(sessions = sessions), Outcome.Hold(w, target, HoldReason.INTRO), e1rm)
        }

        if (hitAll) {
            // Spare reps: on the "+" set, reps beyond the target with one kept in reserve; otherwise the reported
            // reserve beyond the plan; unknown when neither is there.
            val spare: Int? = when {
                // Counted against one in reserve whatever the set was told: stopping earlier does not slow the weights.
                top != null -> {
                    val topRir = top.rir ?: if (top.reps > target) p.topRir else TOP_RIR
                    // Done at another weight than [w] (heavier, after a hint): its reps as they would be at [w].
                    val reps = if (abs(top.weight - w) < 1e-9) top.reps.toDouble()
                    else OneRm.rtfAt(OneRm.estimate(effectiveLoad(ex, top.weight, bodyWeight), top.reps, topRir), effectiveLoad(ex, w, bodyWeight)) - topRir
                    kotlin.math.floor(reps - target + (topRir - TOP_RIR)).toInt()
                }
                regular.all { it.rir != null } -> regular.minOf { it.rir!! - p.rir + (it.reps - target) }
                else -> null
            }
            // ...unless the "+" set, the set nearest failure, was rated with nothing to spare: ratings far from failure run high.
            val reportedEasy = regular.isNotEmpty() && regular.all { (it.rir ?: -1) >= p.rir + 2 } && !(top?.rir != null && spare != null && spare <= 0)
            val linear = linearPhase(ex, spec, state, novice, w, inc)
            // One step of this exercise is a big share of the weight (light dumbbells): progress through reps first.
            val coarse = w > 0 && step / w > 0.10
            // Below the range at the lightest load (see the short branch below): reps, not weight, come first.
            val belowAtMin = target < spec.repMin && w <= lowest + 1e-9
            if ((spare != null && spare >= 4 || reportedEasy) && !belowAtMin) {
                if (coarse) {
                    // A big step to the next weight: take it if the estimate says enough reps are there,
                    // otherwise a few more reps with this weight.
                    val expectedNext = OneRm.rtfAt(e1rm, effectiveLoad(ex, w + inc, bodyWeight)) - p.rir
                    if (expectedNext >= spec.repMin - 1) {
                        val r = expectedNext.toInt().coerceIn(max(1, spec.repMin - 1), max(spec.repMin, spec.repMax - 1))
                        return Evaluation(SlotState(w + inc, r, 0, 0, sessions, state.linear), Outcome.AddWeight(w, w + inc, r), e1rm)
                    }
                    val r = min(spec.repMax + OVERFLOW, target + min(3, max(2, (spare ?: 3) - 1)))
                    if (r > target) return Evaluation(SlotState(w, r, 0, 0, sessions, state.linear), Outcome.AddReps(w, target, r), e1rm)
                }
                val cap = floorTo(w * (if (novice) 1.15 else 1.10), step)
                val to = max(w + step, min(floorTo(supported(e1rm, target, p.rir), step), cap))
                if (to > w + inc + 1e-9 || !linear) {
                    return Evaluation(SlotState(to, target, 0, 0, sessions, state.linear), Outcome.Jump(w, to, target), e1rm)
                }
            }
            // Nothing to spare (or a grind): keep the weight while the body catches up. An unrated "+" set may simply
            // not have been pushed, so after one such hold the weight goes up anyway; a rated one is believed for two
            // sessions, then progress continues through reps — the gentler step.
            val rated = if (top != null) top.rir != null else regular.all { it.rir != null }
            val stuck = spare != null && spare <= 0
            if (stuck && state.steady < (if (rated) 2 else 1)) {
                return Evaluation(state.copy(stalls = 0, misses = 0, sessions = sessions, steady = state.steady + 1),
                    Outcome.Hold(w, target, HoldReason.CONSOLIDATE), e1rm)
            }
            val plenty = spare != null && spare >= 2
            if (belowAtMin) {
                val r = min(spec.repMax, target + when {
                    spare != null && spare >= 4 -> 3
                    plenty -> 2
                    else -> 1
                })
                return Evaluation(SlotState(w, r, 0, 0, sessions, state.linear), Outcome.AddReps(w, target, r), e1rm)
            }
            if (linear && !(stuck && rated)) {
                val to = min(w + inc * (if (plenty) 2 else 1), max(w + inc, floorTo(w * 1.10, step)))
                return Evaluation(SlotState(to, target, 0, 0, sessions, true), Outcome.AddWeight(w, to, target), e1rm)
            }
            if (target < spec.repMax) {
                val r = min(spec.repMax, target + if (plenty) 2 else 1)
                return Evaluation(SlotState(w, r, 0, 0, sessions, state.linear), Outcome.AddReps(w, target, r), e1rm)
            }
            // Top of the range: more weight, and as many reps as the estimate says are there at the new weight
            // (below the range for a coarse step: build back up from there).
            val to = w + inc
            val expected = OneRm.rtfAt(e1rm, effectiveLoad(ex, to, bodyWeight)) - p.rir
            if (expected < spec.repMin - 1 && target < spec.repMax + OVERFLOW) {
                // The next weight is still out of reach (a big step): more reps with this one first.
                return Evaluation(SlotState(w, target + 1, 0, 0, sessions, state.linear), Outcome.AddReps(w, target, target + 1), e1rm)
            }
            val r = expected.toInt().coerceIn(max(1, spec.repMin - 1), max(spec.repMin, spec.repMax - 1))
            return Evaluation(SlotState(to, r, 0, 0, sessions, state.linear), Outcome.AddWeight(w, to, r), e1rm)
        }

        // Short of the target — but every set at the weight was stopped with the missing reps still in reserve (8 done,
        // "2 more" said, 9 planned with 1 left): the lifter stopped, the weight did not stop them. Seen in half-year
        // simulations: such a lifter's weights sank to 60–80 % of what they could lift. The weight and the target stay.
        val atW = planned.filter { it.weight >= w - tol }
        // Most of the sets is enough: a reported reserve is a guess, off by one now and then.
        if (atW.isNotEmpty() && atW.count { s -> s.rir != null && s.reps + s.rir >= target + p.rir } * 2 > atW.size) {
            return Evaluation(state.copy(sessions = sessions), Outcome.Hold(w, target, HoldReason.STOPPED_EARLY), e1rm)
        }
        val first = planned.firstOrNull { it.weight >= w - tol }?.reps ?: 0
        val deficit = planned.sumOf { if (it.weight < w - tol) 1 else max(0, target - it.reps) }
        val stalls = state.stalls + 1
        val near = first >= target - 1 && deficit <= max(1, p.sets - 1)
        val cutFrom = state.cutFrom
        if (cutFrom != null && w < cutFrom - 1e-9 && first <= (state.cutReps ?: 0)) {
            // The weight already came down and the reps did not come up: the weight is not what limits.
            // Keep it and meet the lifter at the reps they do; progress starts again from there.
            val r = max(1, min(target, first))
            return Evaluation(state.copy(reps = r, stalls = 0, misses = 0, sessions = sessions, linear = false, steady = 0),
                Outcome.Hold(w, r, HoldReason.NOT_THE_WEIGHT), e1rm)
        }
        if (target > spec.repMin && !(near && stalls < 2)) {
            // Room in the rep range: bring the rep target down to what was done before touching the weight
            // (double progression: the weight goes down only when even the bottom of the range is out of reach).
            val doneAtW = planned.filter { it.weight >= w - tol }.map { it.reps }
            val r = max(spec.repMin, min(target - 1, doneAtW.minOrNull() ?: spec.repMin))
            return Evaluation(SlotState(w, r, 0, 0, sessions, false, cutFrom = state.cutFrom, cutReps = state.cutReps), Outcome.LowerReps(w, target, r), e1rm)
        }
        if (w <= lowest + 1e-9 && !(near && stalls < 2)) {
            // Already at the lightest load (own body weight, the most help, the empty bar): the weight cannot come
            // down, so the rep target goes below the range to what was done and climbs from there.
            val doneAtW = planned.filter { it.weight >= w - tol }.map { it.reps }
            val r = max(1, min(target - 1, doneAtW.minOrNull() ?: 1))
            if (r < target) {
                return Evaluation(SlotState(w, r, 0, 0, sessions, false, cutFrom = state.cutFrom, cutReps = state.cutReps),
                    Outcome.LowerReps(w, target, r), e1rm)
            }
        }
        if (near) {
            if (stalls >= 3) {
                val to = floored(max(lowest, floorTo(lighter(ex, w, bodyWeight), step)))
                // Already the lightest load: it cannot step back, the target stays.
                if (to >= w - 1e-9) {
                    return Evaluation(SlotState(w, target, stalls, 0, sessions, state.linear, cutFrom = state.cutFrom, cutReps = state.cutReps),
                        Outcome.Hold(w, target, HoldReason.NEAR_MISS), e1rm)
                }
                return Evaluation(SlotState(to, spec.repMin, 0, 0, sessions, false, cutFrom = w, cutReps = first), Outcome.Reset(w, to, spec.repMin), e1rm)
            }
            return Evaluation(SlotState(w, target, stalls, 0, sessions, state.linear, cutFrom = state.cutFrom, cutReps = state.cutReps),
                Outcome.Hold(w, target, HoldReason.NEAR_MISS), e1rm)
        }
        val misses = state.misses + 1
        val shown = floorTo(supported(e1rm, target, p.rir), step)
        val big = first <= target - 3
        // Half way toward what was shown on a first miss, all the way after two or when far short; −10 % at most.
        val goal = if (big || misses >= 2) shown else floorTo(w - (w - shown) / 2, step)
        val to = floored(max(lowest, min(w - step, max(goal, floorTo(lighter(ex, w, bodyWeight), step)))))
        if (to >= w - 1e-9) {
            return Evaluation(SlotState(w, target, stalls, misses, sessions, false, cutFrom = state.cutFrom, cutReps = state.cutReps),
                Outcome.Hold(w, target, HoldReason.FIRST_MISS), e1rm)
        }
        return Evaluation(SlotState(to, target, 0, 0, sessions, false, cutFrom = w, cutReps = first), Outcome.Reduce(w, to, target), e1rm)
    }

    /**
     * Holds (plank): every set held → +5 s (more if the holds were longer), up to the top of the range, where the
     * target stays. Short twice in a row → the target comes down to what the weakest set held (5 s steps, 10 s at least).
     */
    private fun evaluateTimed(spec: SlotSpec, state: SlotState, p: Prescription, work: List<DoneSet>): Evaluation {
        val secs = work.take(p.sets).map { it.reps }
        val minS = secs.min()
        val sessions = state.sessions + 1
        if (secs.size >= p.sets && minS >= p.reps) {
            if (p.reps >= spec.repMax) {
                return Evaluation(state.copy(stalls = 0, misses = 0, sessions = sessions), Outcome.Hold(0.0, p.reps, HoldReason.AT_TOP), null)
            }
            val to = min(spec.repMax, max(p.reps + 5, minS / 5 * 5 + 5))
            return Evaluation(state.copy(reps = to, stalls = 0, misses = 0, sessions = sessions), Outcome.AddReps(0.0, p.reps, to), null)
        }
        val misses = state.misses + 1
        if (misses >= 2) {
            val to = max(10, min(p.reps - 5, minS / 5 * 5))
            if (to < p.reps) {
                return Evaluation(state.copy(reps = to, stalls = 0, misses = 0, sessions = sessions), Outcome.LowerReps(0.0, p.reps, to), null)
            }
        }
        return Evaluation(state.copy(stalls = state.stalls + 1, misses = misses, sessions = sessions), Outcome.Hold(0.0, p.reps, HoldReason.NEAR_MISS), null)
    }

    /**
     * After a set: should the next sets use another weight?
     *
     * While calibrating, always: the next weight comes from what the set showed (at most +30 % per step). Otherwise it
     * follows Helms et al. (2018) — each rep of difference from the plan is worth about 4 % — by reading the set as an
     * estimate and suggesting the weight it supports for the target, kept within ±10 % of the planned weight so hints
     * never compound. It speaks up only when the set was off by 2+ reps.
     */
    /**
     * What to do in the next set of this exercise after [done] (its sets so far): the hint's weight when there is one,
     * else the same weight for the planned reps. Null when the planned sets are done.
     */
    fun nextSet(ex: Exercise, p: Prescription, done: List<DoneSet>, bodyWeight: Double = 75.0): NextSet? {
        val last = done.lastOrNull { it.reps > 0 } ?: return null
        if (done.size >= p.sets) return null
        val reserve = when {
            last.reps < p.reps -> last.rir?.let { it - (p.reps - last.reps) } ?: -(p.reps - last.reps)
            else -> last.rir?.let { it + (last.reps - p.reps) } ?: if (last.reps > p.reps) p.rir + (last.reps - p.reps) else null
        }
        val plus = p.top && done.size == p.sets - 1
        if (ex.load == LoadKind.TIMED) return NextSet(last.weight, p.reps, 0, null, p.rir, plus, p.calibrate)
        // The test: the next attempt after a made one; after a miss the test is over (a heavier one would miss too).
        p.attempts?.let { at ->
            val made = done.last().reps >= 1
            val next = at.getOrNull(done.size)?.takeIf { made } ?: return null
            return NextSet(next, 1, if (next > last.weight) 1 else 0, null, 0, false, false)
        }
        // The taper keeps the weight: a set with reps to spare is no reason to go heavier days before the test.
        if (p.phase == Phase.TAPER && !p.calibrate) return NextSet(last.weight, p.reps, 0, reserve, p.rir, false, false)
        val adj = adjustAfterSet(ex, p, last, bodyWeight, done.size - 1)
        return if (adj != null) NextSet(adj.weight, adj.reps, if (adj.heavier) 1 else -1, reserve, p.rir, plus, p.calibrate)
        else NextSet(last.weight, p.reps, 0, reserve, p.rir, plus, p.calibrate)
    }

    fun adjustAfterSet(ex: Exercise, p: Prescription, last: DoneSet, bodyWeight: Double = 75.0, index: Int = 0): Adjustment? {
        if (ex.load == LoadKind.TIMED || last.reps <= 0) return null
        val step = ex.stepAt(last.weight)
        val rir = assumedRir(last, index, p)
        val e1rm = OneRm.estimate(effectiveLoad(ex, last.weight, bodyWeight), last.reps, rir)
        // No hint of help to whoever the set shows does five and more with the body alone.
        val lowest = lightest(ex, bodyWeight, e1rm)
        if (p.calibrate) {
            val raw = toWeight(ex, OneRm.loadFor(e1rm, p.reps, p.rir), bodyWeight)
            val capped = min(raw, max(last.weight + step, last.weight + abs(effectiveLoad(ex, last.weight, bodyWeight)) * 0.3))
            val w = max(lowest, floorTo(capped, ex.stepAt(capped)))
            return if (abs(w - last.weight) < step / 2) null else Adjustment(w, p.reps, w > last.weight)
        }
        // Reserve as actually done: missed reps count as negative reserve, extra reps as extra reserve.
        val actual = when {
            last.reps < p.reps -> (last.rir ?: 0) - (p.reps - last.reps)
            else -> (last.rir ?: p.rir) + (last.reps - p.reps)
        }
        if (abs(p.rir - actual) < 2) return null
        val planned = p.weight ?: last.weight
        val raw = toWeight(ex, OneRm.loadFor(e1rm, p.reps, p.rir), bodyWeight)
        // ±10 % of the total load the plan asked for (body weight included for body-weight exercises).
        // Once a set at a heavier weight (the earlier hint) still shows reserve, the next step is measured from that
        // set: each step is backed by a set at that weight. Never beyond +20 % of the plan in one session.
        val plannedLoad = effectiveLoad(ex, planned, bodyWeight)
        val anchor = max(plannedLoad, effectiveLoad(ex, last.weight, bodyWeight))
        val lo = toWeight(ex, anchor * 0.9, bodyWeight) - 1e-9
        val hi = toWeight(ex, min(anchor * 1.1, plannedLoad * 1.2), bodyWeight) + 1e-9
        val bounded = raw.coerceIn(lo, hi)
        // Nearest weight on the step grid that stays within ±10 % of the plan; none — no hint.
        val down = floorTo(bounded, ex.stepAt(bounded))
        val up = down + ex.stepAt(down)
        val w = listOf(down, up).filter { it in lo..hi && it >= min(lowest, last.weight) }.minByOrNull { abs(it - raw) } ?: return null
        return when {
            w <= last.weight - step + 1e-9 -> Adjustment(w, p.reps, false)
            w >= last.weight + step - 1e-9 -> Adjustment(w, p.reps, true)
            else -> null
        }
    }
}
