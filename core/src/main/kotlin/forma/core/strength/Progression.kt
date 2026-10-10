package forma.core.strength

import forma.core.floorTo
import forma.core.model.Exercise
import forma.core.model.LoadKind
import forma.core.roundTo
import kotlinx.serialization.Serializable
import kotlin.math.ceil
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
) {
    companion object {
        fun fresh(spec: SlotSpec) = SlotState(reps = spec.repMin)
    }
}

@Serializable
enum class Phase { NORMAL, DELOAD }

/** What to do today for one exercise. */
@Serializable
data class Prescription(
    val weight: Double?,
    val reps: Int,
    val sets: Int,
    val rir: Int,
    /** First time: the lifter picks a weight, the app learns from the first set. */
    val calibrate: Boolean,
    /** Suggested starting point for calibration. */
    val startHint: Double? = null,
    val phase: Phase = Phase.NORMAL,
)

/** A working set as logged. [rir] is optional self-report. */
@Serializable
data class DoneSet(val weight: Double, val reps: Int, val rir: Int? = null)

/** Why the next target changed. The UI turns these into short sentences. */
@Serializable
sealed interface Outcome {
    @Serializable data class Calibrated(val weight: Double, val reps: Int) : Outcome
    @Serializable data class AddReps(val weight: Double, val from: Int, val to: Int) : Outcome
    @Serializable data class AddWeight(val from: Double, val to: Double, val reps: Int) : Outcome
    @Serializable data class Jump(val from: Double, val to: Double, val reps: Int) : Outcome
    @Serializable data class Hold(val weight: Double, val reps: Int, val reason: HoldReason) : Outcome
    @Serializable data class Reduce(val from: Double, val to: Double, val reps: Int) : Outcome
    @Serializable data class Reset(val from: Double, val to: Double, val reps: Int) : Outcome
    @Serializable data object NotDone : Outcome
    @Serializable data object Deload : Outcome
}

@Serializable
enum class HoldReason { NEAR_MISS, FIRST_MISS, FEWER_SETS }

data class Evaluation(val state: SlotState, val outcome: Outcome, val e1rm: Double?)

/** In-session hint after a set: change the weight for the next sets. */
data class Adjustment(val weight: Double, val reps: Int, val heavier: Boolean)

/**
 * Double progression on top of an e1RM model.
 *
 * - Every set updates an estimate of the one-rep max from weight, reps and reps in reserve.
 * - All sets at the top of the range → more weight; all sets at target → one more rep next time.
 * - Far above target → jump straight to the weight the shown strength supports.
 * - Slightly short → repeat. Clearly short twice in a row → −5 %. Three or more reps short on the first set →
 *   straight down to what was shown (at most −10 %).
 * - Three sessions in a row without progress → step back 10 % and build up again (Starting Strength reset).
 * - First session after 2+ weeks off starts 5–15 % lighter.
 */
object Progression {
    /** Total load in kg that the muscles move, used for e1RM. */
    fun effectiveLoad(ex: Exercise, weight: Double, bodyWeight: Double): Double =
        if (ex.load == LoadKind.BODYWEIGHT_PLUS) bodyWeight + weight else weight

    private fun toWeight(ex: Exercise, load: Double, bodyWeight: Double): Double =
        if (ex.load == LoadKind.BODYWEIGHT_PLUS) max(0.0, load - bodyWeight) else load

    fun startHint(ex: Exercise, bodyWeight: Double, female: Boolean, trained: Double): Double? {
        if (ex.load != LoadKind.EXTERNAL) return if (ex.load == LoadKind.BODYWEIGHT_PLUS) 0.0 else null
        var w = ex.startRatio * bodyWeight * (if (female) 0.6 else 1.0) * trained
        if (ex.bar != null) w = max(ex.bar, w)
        return max(ex.step, roundTo(w, ex.step))
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
    ): Prescription {
        val rir = (spec.rir + rirOffset).coerceIn(0, 5)
        if (ex.load == LoadKind.TIMED) {
            return Prescription(null, state.reps, setsFor(spec, phase), rir, calibrate = false, phase = phase)
        }
        var weight = state.weight
        var calibrate = false
        if (weight == null) {
            if (e1rm != null && e1rm > 0) {
                weight = floorTo(toWeight(ex, OneRm.loadFor(e1rm, state.reps, rir), bodyWeight), ex.step)
            } else {
                calibrate = true
            }
        } else {
            // Back after a break: strength holds for about three weeks, then fades.
            val factor = layoffFactor(daysSinceLast)
            if (factor < 1.0) weight = floorTo(effectiveLoad(ex, weight, bodyWeight) * factor - (effectiveLoad(ex, weight, bodyWeight) - weight), ex.step).coerceAtLeast(0.0)
        }
        if (phase == Phase.DELOAD && weight != null) weight = floorTo(weight * 0.9, ex.step)
        return Prescription(weight, state.reps, setsFor(spec, phase), if (phase == Phase.DELOAD) rir + 2 else rir, calibrate, if (calibrate) hint else null, phase)
    }

    /** Load multiplier for the first session after [days] without this exercise. */
    fun layoffFactor(days: Long?): Double = when {
        days == null || days <= 14 -> 1.0
        days <= 28 -> 0.95
        days <= 56 -> 0.90
        else -> 0.85
    }

    private fun setsFor(spec: SlotSpec, phase: Phase) =
        if (phase == Phase.DELOAD) max(1, ceil(spec.sets / 2.0).toInt()) else spec.sets

    /** Reps in reserve to assume when the lifter did not say. */
    private fun rirOf(set: DoneSet, target: Int, plannedRir: Int): Int =
        set.rir ?: if (set.reps >= target) plannedRir else 0

    /**
     * Best e1RM of the session. Only sets of 1–12 reps with at most 4 in reserve count (estimates from longer
     * or easier sets are unreliable); if there are none, every set counts.
     */
    fun e1rmOf(ex: Exercise, sets: List<DoneSet>, target: Int, plannedRir: Int, bodyWeight: Double): Double? {
        val done = sets.filter { it.reps > 0 }
        val good = done.filter { it.reps <= 12 && rirOf(it, target, plannedRir) <= 4 }.ifEmpty { done }
        return good.maxOfOrNull { OneRm.estimate(effectiveLoad(ex, it.weight, bodyWeight), it.reps, rirOf(it, target, plannedRir)) }
    }

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

    fun evaluate(
        ex: Exercise,
        spec: SlotSpec,
        state: SlotState,
        p: Prescription,
        done: List<DoneSet>,
        bodyWeight: Double = 75.0,
    ): Evaluation {
        val work = done.filter { it.reps > 0 }
        if (work.isEmpty()) return Evaluation(state, Outcome.NotDone, null)
        val target = p.reps

        if (ex.load == LoadKind.TIMED) return evaluateTimed(spec, state, p, work)

        val e1rm = e1rmOf(ex, work, target, p.rir, bodyWeight)!!
        if (p.phase == Phase.DELOAD) return Evaluation(state.copy(sessions = state.sessions + 1), Outcome.Deload, e1rm)

        val step = ex.step
        if (p.calibrate || state.weight == null) {
            val reps = spec.repMin
            val w = max(0.0, roundTo(toWeight(ex, OneRm.loadFor(e1rm, reps, spec.rir), bodyWeight), step))
            return Evaluation(SlotState(w, reps, 0, 0, state.sessions + 1), Outcome.Calibrated(w, reps), e1rm)
        }

        // The weight the lifter actually worked with: the most used one, later sets win ties.
        val w = work.groupingBy { it.weight }.eachCount().entries
            .maxWith(compareBy<Map.Entry<Double, Int>> { it.value }.thenBy { e -> work.indexOfLast { it.weight == e.key } })
            .key
        val atW = work.filter { it.weight == w }
        val sessions = state.sessions + 1

        if (atW.size < p.sets) {
            val out = Outcome.Hold(w, target, HoldReason.FEWER_SETS)
            return Evaluation(state.copy(weight = w, sessions = sessions), out, e1rm)
        }
        val reps = atW.take(p.sets).map { it.reps }
        val first = reps.first()
        val minReps = reps.min()
        val deficit = reps.sumOf { max(0, target - it) }
        val reportedEasy = atW.all { (it.rir ?: -1) >= p.rir + 2 }

        fun jumpTarget(): Double {
            val support = floorTo(toWeight(ex, OneRm.loadFor(e1rm, target, p.rir), bodyWeight), step)
            val cap = floorTo(effectiveLoad(ex, w, bodyWeight) * 1.15 - (effectiveLoad(ex, w, bodyWeight) - w), step)
            return max(w + step, min(support, max(cap, w + step)))
        }

        val tooEasy = first >= target + 4 || reportedEasy || (minReps >= target + 2 && minReps >= spec.repMax)
        if (tooEasy) {
            val to = jumpTarget()
            if (to > w + step + 1e-9) {
                return Evaluation(SlotState(to, target, 0, 0, sessions), Outcome.Jump(w, to, target), e1rm)
            }
        }
        if (minReps >= spec.repMax) {
            val to = w + (spec.increment ?: step)
            val expected = OneRm.rtfAt(e1rm, effectiveLoad(ex, to, bodyWeight)) - p.rir
            val r = expected.toInt().coerceIn(spec.repMin, max(spec.repMin, min(spec.repMax - 1, minReps)))
            return Evaluation(SlotState(to, r, 0, 0, sessions), Outcome.AddWeight(w, to, r), e1rm)
        }
        if (minReps >= target) {
            val r = min(spec.repMax, max(target + 1, minReps + 1))
            return Evaluation(SlotState(w, r, 0, 0, sessions), Outcome.AddReps(w, target, r), e1rm)
        }

        val stalls = state.stalls + 1
        val nearMiss = first >= target - 1 && deficit <= max(1, p.sets - 1)
        if (nearMiss) return stallCheck(ex, spec, SlotState(w, target, stalls, 0, sessions), Outcome.Hold(w, target, HoldReason.NEAR_MISS), e1rm)

        val misses = state.misses + 1
        // Far below target on the first set: the weight was simply too heavy — go to what was shown (≤ −10 %).
        if (first <= target - 3) {
            val shown = roundTo(toWeight(ex, OneRm.loadFor(e1rm, target, p.rir), bodyWeight), step)
            val to = max(0.0, min(w - step, max(shown, floorTo(w * 0.9, step))))
            return Evaluation(SlotState(to, target, 0, 0, sessions), Outcome.Reduce(w, to, target), e1rm)
        }
        // Two sessions in a row short of target: −5 %.
        if (misses >= 2) {
            val to = max(0.0, min(w - step, floorTo(w * 0.95, step)))
            return Evaluation(SlotState(to, target, 0, 0, sessions), Outcome.Reduce(w, to, target), e1rm)
        }
        return stallCheck(ex, spec, SlotState(w, target, stalls, misses, sessions), Outcome.Hold(w, target, HoldReason.FIRST_MISS), e1rm)
    }

    private fun stallCheck(ex: Exercise, spec: SlotSpec, s: SlotState, hold: Outcome.Hold, e1rm: Double): Evaluation {
        if (s.stalls < 3 || s.weight == null) return Evaluation(s, hold, e1rm)
        val to = max(0.0, floorTo(s.weight * 0.9, ex.step))
        val reps = spec.repMin
        return Evaluation(SlotState(to, reps, 0, 0, s.sessions), Outcome.Reset(s.weight, to, reps), e1rm)
    }

    private fun evaluateTimed(spec: SlotSpec, state: SlotState, p: Prescription, work: List<DoneSet>): Evaluation {
        val secs = work.take(p.sets).map { it.reps }
        val minS = secs.min()
        return if (secs.size >= p.sets && minS >= p.reps) {
            val to = min(spec.repMax, max(p.reps + 5, minS + 5))
            Evaluation(state.copy(reps = to, stalls = 0, sessions = state.sessions + 1), Outcome.AddReps(0.0, p.reps, to), null)
        } else {
            Evaluation(state.copy(stalls = state.stalls + 1, sessions = state.sessions + 1), Outcome.Hold(0.0, p.reps, HoldReason.NEAR_MISS), null)
        }
    }

    /**
     * After a set: should the next sets use another weight? On the first session the app always answers from
     * what was lifted. Otherwise it follows the Helms et al. (2018) rule: each rep of difference from the plan
     * (missed reps, or reps in reserve above/below the target) changes the load by 4 %, at most ±10 %; it only
     * speaks up when the set was off by 2 or more.
     */
    fun adjustAfterSet(ex: Exercise, p: Prescription, last: DoneSet, bodyWeight: Double = 75.0): Adjustment? {
        if (ex.load == LoadKind.TIMED || last.reps <= 0) return null
        val step = ex.step
        if (p.calibrate) {
            val rir = last.rir ?: if (last.reps >= p.reps) p.rir else 0
            val e1rm = OneRm.estimate(effectiveLoad(ex, last.weight, bodyWeight), last.reps, rir)
            val w = max(0.0, roundTo(toWeight(ex, OneRm.loadFor(e1rm, p.reps, p.rir), bodyWeight), step))
            return if (kotlin.math.abs(w - last.weight) < step / 2) null else Adjustment(w, p.reps, w > last.weight)
        }
        // Reps in reserve as actually done: missed reps count as negative reserve, extra reps as extra reserve.
        val actual = when {
            last.reps < p.reps -> (last.rir ?: 0) - (p.reps - last.reps)
            else -> last.rir ?: (p.rir + (last.reps - p.reps))
        }
        val delta = p.rir - actual
        if (kotlin.math.abs(delta) < 2) return null
        val factor = (1 - 0.04 * delta).coerceIn(0.90, 1.10)
        val load = effectiveLoad(ex, last.weight, bodyWeight)
        val target = toWeight(ex, load * factor, bodyWeight)
        val w = max(0.0, if (delta > 0) floorTo(target, step) else floorTo(target, step))
        return when {
            delta > 0 && w <= last.weight - step + 1e-9 -> Adjustment(w, p.reps, false)
            delta < 0 && w >= last.weight + step - 1e-9 -> Adjustment(w, p.reps, true)
            else -> null
        }
    }
}
