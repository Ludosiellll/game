package forma.app.sim

import forma.core.run.RunResult
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import forma.core.run.Vdot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A simulated runner with a true fitness (VDOT) the app never sees: it sees only what a phone would — the pace of the
 * reps, how much was done, the time of a trial and the effort the runner reports.
 *
 * What a session demands is the oxygen cost of its target pace against today's fitness ([Vdot.vo2]); what can be held is
 * Daniels' sustainable fraction for the reps' length (a rep with rests ≈ a continuous run three times its length, at most
 * the session's total work). Within it the pace is held and the effort follows the margin; past it the reps slow down to
 * what can be held and feel very hard. Fitness grows with every well-dosed session toward a personal ceiling (less room —
 * slower gains), more for quality held at the intended intensity, less for one far too easy or failed, and fades after a
 * week without running. Fatigue from each session and a heavy-legs gym day the day before lower today's fitness.
 *
 * A model, not physiology: calibrated so a consistent beginner-to-intermediate runner gains about 8–12 % VDOT in half a
 * year, and only used to see how the plan steers.
 */
class VirtualRunner(
    var vdot: Double,
    seed: Int,
    /** Multiplier on the gain rate. */
    val talent: Double = 1.0,
    /** How far fitness can rise above the start, as a share of it. */
    ceilingGain: Double = 0.18,
    /** Added to the effort the runner reports (+2: rates everything harder than it was). */
    val rpeBias: Int = 0,
    /** Runs the first reps too fast: the same average costs more. */
    val startsFast: Boolean = false,
) {
    private val rng = Random(seed)
    private val start = vdot
    private val ceiling = vdot * (1 + ceilingGain)
    private var fatigue = 0.0
    private var form = 0.0
    private var legsDay: Long? = null
    private var lastRun: Long? = null
    /** Imposed illness: days left with fitness lowered and no running. */
    var sickDays = 0

    /** Quality sessions: demanded over holdable (x); for the report. */
    val quality = mutableListOf<Pair<RunType, Double>>()

    fun newDay(day: Long) {
        fatigue *= 0.65
        form = (gauss() * 0.5).coerceIn(-1.2, 1.2)
        if (sickDays > 0) sickDays--
        // A week without running: fitness fades (about 0.5 % a week of it at first).
        if (lastRun != null && day - lastRun!! > 7) vdot = max(start * 0.9, vdot - 0.035)
    }

    fun legs(day: Long) { legsDay = day }

    /** Fitness on this day: form, fatigue, yesterday's heavy legs. */
    private fun today(day: Long): Double = vdot + form - 0.25 * fatigue - if (legsDay == day - 1) 0.6 else 0.0

    /** Runs [w] on [day] as the app asks; the result as the phone and the runner would report it. */
    fun perform(w: RunWorkout, day: Long): RunResult {
        lastRun = day
        val v = today(day)
        val room = ((ceiling - vdot) / (ceiling - start)).coerceIn(0.0, 1.0)
        val minutes = w.totalSeconds / 60.0
        return when (w.type) {
            RunType.TEST -> trial(w, v)
            RunType.GOAL_PACE, RunType.INTERVALS, RunType.TEMPO -> reps(w, v, room, minutes)
            else -> steady(w, v, room, minutes)
        }
    }

    private fun reps(w: RunWorkout, v: Double, room: Double, minutes: Double): RunResult {
        val work = w.segments.filter { it.kind == SegmentKind.WORK }
        val target = work.first().pace?.let { (it.fast + it.slow) / 2.0 } ?: Vdot.paceAtFraction(v, 0.95)
        val longest = work.maxOf { it.seconds }
        val total = work.sumOf { it.seconds }
        val holdable = Vdot.fractionSustainable(min(total, longest * 3) / 60.0)
        val demanded = Vdot.vo2(60_000.0 / target) / v
        var x = demanded / holdable * if (startsFast) 1.03 else 1.0
        quality += w.type to x
        val held = x <= 1.0
        // Past what can be held: the reps slow to it; far past it, the last reps are not done.
        val pace = if (held) target * (1 + gauss() * 0.006) else 60_000.0 / Vdot.velocityForVo2(holdable * v)
        val completed = if (x > 1.08) 0.75 else 1.0
        val felt = when {
            x <= 0.85 -> 5.0
            x <= 1.0 -> 5 + (x - 0.85) / 0.15 * 3
            x <= 1.08 -> 9.0
            else -> 10.0
        } + gauss() * 0.5
        val rpe = report(felt)
        // Training effect: held near the intended effort counts fully; far too easy half; failed less, and tires.
        val stim = when {
            x < 0.8 -> 0.5
            held -> 1.0
            else -> 0.6
        }
        gain(stim, room)
        fatigue += minutes * felt / 120 + if (!held) 2.0 else 0.0
        val dist = total / target * 1000
        return RunResult(completed, rpe, w.totalSeconds, distanceM = dist + (w.totalSeconds - total) / 400.0 * 1000,
            workPace = pace, workDistanceM = total / pace * 1000 * completed, workSec = (total * completed).roundToInt())
    }

    private fun steady(w: RunWorkout, v: Double, room: Double, minutes: Double): RunResult {
        val seg = w.segments.firstOrNull { it.kind == SegmentKind.RUN }
        val pace = seg?.pace?.let { (it.fast + it.slow) / 2.0 } ?: Vdot.paceAtFraction(v, 0.68)
        val r = Vdot.vo2(60_000.0 / pace) / v
        val felt = (2 + (r - 0.6) * 20 + minutes / 40 + 0.15 * fatigue + gauss() * 0.6).coerceIn(1.0, 10.0)
        gain(minutes * 0.02, room)
        fatigue += minutes * felt / 150
        return RunResult(1.0, report(felt), w.totalSeconds, distanceM = w.totalSeconds / pace * 1000, workSec = w.workSeconds)
    }

    private fun trial(w: RunWorkout, v: Double): RunResult {
        if (w.testMeters == null) {
            // The 12 minutes: as far as today's form goes in them.
            var lo = 500.0
            var hi = 6000.0
            repeat(60) { val mid = (lo + hi) / 2; if (Vdot.predictSeconds(v, mid) < 720) lo = mid else hi = mid }
            fatigue += 3.0
            gain(0.8, ((ceiling - vdot) / (ceiling - start)).coerceIn(0.0, 1.0))
            return RunResult(1.0, report(9.0), w.totalSeconds, distanceM = lo + 3500, workDistanceM = lo, workSec = 720)
        }
        val meters = w.testMeters!!.toDouble()
        val sec = Vdot.predictSeconds(v, meters) * (if (startsFast) 1.015 else 1.0) * (1 + gauss() * 0.005)
        fatigue += 3.0
        gain(0.8, ((ceiling - vdot) / (ceiling - start)).coerceIn(0.0, 1.0))
        return RunResult(1.0, report(9.5), w.totalSeconds, distanceM = meters + 4000, workSec = sec.roundToInt(),
            testSeconds = sec.roundToInt(), workDistanceM = meters)
    }

    private fun gain(stim: Double, room: Double) {
        vdot += talent * 0.12 * stim * room
    }

    private fun report(felt: Double) = (felt.roundToInt() + rpeBias).coerceIn(1, 10)

    /** 1500 m today at full fitness (no fatigue, average form). */
    fun race1500(): Double = Vdot.predictSeconds(vdot, 1500.0)

    private fun gauss(): Double {
        val u = max(1e-12, rng.nextDouble())
        return sqrt(-2 * ln(u)) * kotlin.math.cos(2 * Math.PI * rng.nextDouble())
    }
}
