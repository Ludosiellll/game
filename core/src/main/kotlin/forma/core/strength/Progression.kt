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
 * - Slightly short → repeat. Clearly short twice (or once by 3+ reps) → lower the weight to what was shown.
 * - Three sessions in a row without progress → step back 10 % and build up again.
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
    ): Prescription {
        val rir = (spec.rir + rirOffset).coerceIn(0, 5)
        if (ex.load == LoadKind.TIMED) {
            return Prescription(null, state.reps, setsFor(spec, phase), rir, calibrate = false, phase = phase)
        }
        var weight = state.weight
        var calibrate = false
        if (weight == null) {
            if (e1rm != null && e1rm > 0) {
                weight = roundTo(toWeight(ex, OneRm.loadFor(e1rm, state.reps, rir), bodyWeight), ex.step)
            } else {
                calibrate = true
            }
        }
        if (phase == Phase.DELOAD && weight != null) weight = floorTo(weight * 0.9, ex.step)
        return Prescription(weight, state.reps, setsFor(spec, phase), if (phase == Phase.DELOAD) rir + 2 else rir, calibrate, if (calibrate) hint else null, phase)
    }

    private fun setsFor(spec: SlotSpec, phase: Phase) =
        if (phase == Phase.DELOAD) max(1, ceil(spec.sets / 2.0).toInt()) else spec.sets

    /** Reps in reserve to assume when the lifter did not say. */
    private fun rirOf(set: DoneSet, target: Int, plannedRir: Int): Int =
        set.rir ?: if (set.reps >= target) plannedRir else 0

    fun e1rmOf(ex: Exercise, sets: List<DoneSet>, target: Int, plannedRir: Int, bodyWeight: Double): Double? =
        sets.filter { it.reps > 0 }
            .maxOfOrNull { OneRm.estimate(effectiveLoad(ex, it.weight, bodyWeight), it.reps, rirOf(it, target, plannedRir)) }

    /** Gains are accepted quickly, drops slowly: one bad day should not erase weeks of work. */
    fun smoothE1rm(old: Double?, observed: Double): Double = when {
        old == null || old <= 0 -> observed
        observed >= old -> old + 0.7 * (observed - old)
        else -> old + 0.35 * (observed - old)
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
            val to = w + step
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
        val bigMiss = first <= target - 3
        if (bigMiss || misses >= 2) {
            val shown = roundTo(toWeight(ex, OneRm.loadFor(e1rm, target, p.rir), bodyWeight), step)
            val floor = floorTo(w * 0.85, step)
            val to = max(0.0, min(w - step, max(shown, floor)))
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
     * After a set: should the next sets use another weight? Calibration always answers;
     * otherwise only clear misses (3+ reps short) or clear surpluses (4+ reps over, or 3+ more in reserve) do.
     */
    fun adjustAfterSet(ex: Exercise, p: Prescription, last: DoneSet, bodyWeight: Double = 75.0): Adjustment? {
        if (ex.load == LoadKind.TIMED || last.reps <= 0) return null
        val step = ex.step
        val rir = last.rir ?: if (last.reps >= p.reps) p.rir else 0
        val e1rm = OneRm.estimate(effectiveLoad(ex, last.weight, bodyWeight), last.reps, rir)
        val ideal = toWeight(ex, OneRm.loadFor(e1rm, p.reps, p.rir), bodyWeight)
        if (p.calibrate) {
            val w = max(0.0, roundTo(ideal, step))
            return if (kotlin.math.abs(w - last.weight) < step / 2) null else Adjustment(w, p.reps, w > last.weight)
        }
        val short = last.reps <= p.reps - 3 || (last.rir == 0 && last.reps < p.reps - 1)
        val over = last.reps >= p.reps + 4 || (last.rir != null && last.rir >= p.rir + 3)
        return when {
            short -> {
                val w = max(0.0, floorTo(ideal, step))
                if (w <= last.weight - step + 1e-9) Adjustment(w, p.reps, false) else null
            }
            over -> {
                val cap = floorTo(last.weight + (effectiveLoad(ex, last.weight, bodyWeight)) * 0.15, step)
                val w = min(floorTo(ideal, step), cap)
                if (w >= last.weight + step - 1e-9) Adjustment(w, p.reps, true) else null
            }
            else -> null
        }
    }
}
