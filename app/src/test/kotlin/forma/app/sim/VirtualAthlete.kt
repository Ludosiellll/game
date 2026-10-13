package forma.app.sim

import forma.core.model.Catalog
import forma.core.model.Exercise
import forma.core.model.Experience
import forma.core.model.LoadKind
import forma.core.strength.OneRm
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** How hard the person works relative to what the app asks. */
enum class Effort {
    /** Does the target reps when able, otherwise as many as possible. */
    FOLLOW,
    /** Always goes one rep short of failure, even past the target ("did more than asked"). */
    OVERSHOOT,
    /** Stops a rep before the target even when able ("did less than asked"). */
    UNDERSHOOT,
}

/** What the person enters in "Сколько ещё мог?" (the app offers 0–4 and 5+). */
/** HONEST ±1 noise; NONE never rates; OPTIMISTIC says one rep more than there is; INFLATED two more. */
enum class Reporting { HONEST, NONE, OPTIMISTIC, INFLATED }

/**
 * A simulated person with a true one-rep max per exercise.
 *
 * Strength grows toward a ceiling with every session that has hard enough sets (logistic, calibrated so an
 * untrained person gains about 40 %, a trained one about 20 % and an advanced one about 10 % over 12 weeks of
 * twice-weekly practice: ACSM 2009 position stand on progression models). Rep capacity follows the same RTS
 * curve the app uses, stretched by an individual endurance factor, so the app's model and the person differ.
 * Strength fades by 0.25 %/day after three weeks without training an exercise. Each day has its own form.
 */
class VirtualAthlete(
    val experience: Experience,
    var bodyWeight: Double,
    private val seed: Int,
    val effort: Effort = Effort.FOLLOW,
    val reporting: Reporting = Reporting.HONEST,
    /** >1 — more reps at a given %1RM than the app's table assumes. */
    val endurance: Double = 1.0,
    /** Multiplier on the gain rate. */
    val talent: Double = 1.0,
    val acceptsHints: Boolean = true,
) {
    private val rng = Random(seed)
    private val oneRm = HashMap<String, Double>()
    private val ceiling = HashMap<String, Double>()
    private val peak = HashMap<String, Double>()
    private val lastStimulus = HashMap<String, Long>()
    private var form = 1.0
    /** Extra fatigue from grinding sets to failure; decays daily. */
    private var fatigue = 0.0
    /** Imposed slump (illness, bad sleep, stress) while > 0 days. */
    var slumpDays = 0
    var slumpFactor = 0.9

    private val ceilingRatio = when (experience) {
        Experience.NOVICE -> 2.0
        Experience.INTERMEDIATE -> 1.4
        Experience.ADVANCED -> 1.15
    }
    private val startTraining = when (experience) {
        Experience.NOVICE -> 1.0
        Experience.INTERMEDIATE -> 1.5
        Experience.ADVANCED -> 1.9
    }

    /** True 1RM in effective load (body weight included for body-weight exercises). */
    fun trueOneRm(id: String): Double = oneRm.getOrPut(id) {
        val ex = Catalog[id]
        // startRatio is a conservative 8–10 rep weight for a novice man; an untrained 1RM is about that / 0.68.
        val base = when (ex.load) {
            LoadKind.EXTERNAL -> ex.startRatio * bodyWeight / 0.68
            // Untrained: about one clean rep with the moved share of body weight on pull-ups and dips, more elsewhere.
            LoadKind.BODYWEIGHT_PLUS -> ex.bodyShare * bodyWeight * (if (ex.assisted) 1.05 else 1.6)
            LoadKind.TIMED -> 45.0
        }
        val v = base * startTraining * (0.85 + 0.4 * rng.nextDouble())
        ceiling[id] = v * ceilingRatio
        peak[id] = v
        v
    }

    fun ceiling(id: String): Double { trueOneRm(id); return ceiling.getValue(id) }

    /** Start of a day: new form, slump, fatigue decay, detraining. */
    fun newDay(day: Long) {
        form = (1.0 + rng.nextGaussian() * 0.03).coerceIn(0.92, 1.07)
        if (slumpDays > 0) { form *= slumpFactor; slumpDays-- }
        fatigue *= 0.7
        for ((id, last) in lastStimulus) {
            if (day - last > 21) {
                val cur = oneRm.getValue(id)
                oneRm[id] = max(peak.getValue(id) * 0.8, cur * (1 - 0.0025))
            }
        }
    }

    private fun capacity(id: String): Double = trueOneRm(id) * form * (1 - fatigue)

    /** Share of 1RM the person can lift for [rtf] reps to failure (the app's curve, stretched). */
    private fun fraction(rtf: Double): Double = OneRm.fractionForRtf(1 + (rtf - 1) / endurance)

    private fun rtfAt(oneRm: Double, load: Double): Double {
        if (load >= oneRm) return if (load <= oneRm * 1.02) 1.0 else 0.0
        var lo = 1.0
        var hi = 60.0
        repeat(50) {
            val mid = (lo + hi) / 2
            if (fraction(mid) * oneRm > load) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    fun load(ex: Exercise, weight: Double): Double = if (ex.load == LoadKind.BODYWEIGHT_PLUS) ex.bodyShare * bodyWeight + weight else weight

    /** External weight that gives exactly [reps] with [rir] in reserve today without day form (the ideal prescription). */
    fun idealWeight(ex: Exercise, reps: Int, rir: Int): Double {
        val l = trueOneRm(ex.id) * fraction((reps + rir).toDouble())
        return if (ex.load == LoadKind.BODYWEIGHT_PLUS) l - ex.bodyShare * bodyWeight else l
    }

    data class SetResult(val reps: Int, val reportedRir: Int?, val actualRir: Int)

    /** What the app asked for on this set. */
    enum class SetKind {
        REGULAR,
        /** "N+": the target, then more while it goes well, stopping with one rep in reserve. */
        TOP,
        /** Finding the working weight: as many reps as cleanly possible with about two in reserve, at most 15. */
        CALIBRATE,
    }

    /** One working set: [setIndex] counts sets already done on this exercise today (fatigue). */
    fun perform(ex: Exercise, weight: Double, target: Int, setIndex: Int, kind: SetKind = SetKind.REGULAR): SetResult {
        if (ex.load == LoadKind.TIMED) {
            val cap = (capacity(ex.id) - setIndex * 3).roundToInt().coerceAtLeast(5)
            val reps = when (effort) {
                Effort.FOLLOW -> min(target, cap)
                Effort.OVERSHOOT -> max(target, cap - 5)
                Effort.UNDERSHOOT -> min(target - 5, cap)
            }.coerceAtLeast(5)
            return SetResult(reps, null, 0)
        }
        val rtf = rtfAt(capacity(ex.id), load(ex, weight)) - 0.6 * setIndex
        val can = floor(rtf).toInt().coerceAtLeast(0)
        val reps = when (kind) {
            SetKind.CALIBRATE -> when (effort) {
                Effort.FOLLOW -> min(15, can - 2)
                Effort.OVERSHOOT -> min(15, can - 1)
                Effort.UNDERSHOOT -> min(15, can - 4)
            }
            SetKind.TOP -> when (effort) {
                Effort.FOLLOW, Effort.OVERSHOOT -> max(min(target, can), min(target + 10, can - 1))
                Effort.UNDERSHOOT -> min(max(1, target - 1), can)
            }
            SetKind.REGULAR -> when (effort) {
                Effort.FOLLOW -> min(target, can)
                Effort.OVERSHOOT -> max(min(target, can), can - 1)
                Effort.UNDERSHOOT -> min(max(1, target - 1), can)
            }
        }.coerceAtLeast(if (can > 0) 1 else 0)
        val actual = max(0, floor(rtf - reps).toInt())
        val reported = when (reporting) {
            Reporting.NONE -> null
            Reporting.HONEST -> (actual + (rng.nextGaussian() * 0.7).roundToInt()).coerceIn(0, 5)
            Reporting.OPTIMISTIC -> (actual + 1).coerceIn(0, 5)
            Reporting.INFLATED -> (actual + 2).coerceIn(0, 5)
        }
        return SetResult(reps, reported, actual)
    }

    /** After a session: sets with at most 4 reps in reserve and at least 55 % of 1RM stimulate growth. */
    fun adapt(ex: Exercise, sets: List<Pair<Double, SetResult>>, day: Long) {
        if (ex.load == LoadKind.TIMED) {
            if (sets.isNotEmpty()) {
                val cur = trueOneRm(ex.id)
                oneRm[ex.id] = min(ceiling(ex.id), cur * 1.02)
                lastStimulus[ex.id] = day
            }
            return
        }
        val t = trueOneRm(ex.id)
        val c = ceiling(ex.id)
        val effective = sets.count { (w, r) -> r.reps > 0 && r.actualRir <= 4 && load(ex, w) >= 0.55 * t }
        val failures = sets.count { (_, r) -> r.actualRir == 0 }
        if (failures >= 2) fatigue = min(0.08, fatigue + 0.01 * failures)
        if (effective == 0) return
        val k = 0.035 * talent
        val next = t + k * min(1.0, effective / 3.0) * (1 - t / c) * t
        oneRm[ex.id] = next
        peak[ex.id] = max(peak.getValue(ex.id), next)
        lastStimulus[ex.id] = day
    }

    /** Gaussian from the shared generator (Box–Muller). */
    private fun Random.nextGaussian(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }
}
