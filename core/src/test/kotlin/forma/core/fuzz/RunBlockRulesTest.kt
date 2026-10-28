package forma.core.fuzz

import forma.core.fuzz.Fuzz.chance
import forma.core.fuzz.Fuzz.pick
import forma.core.fuzz.Fuzz.range
import forma.core.model.RunGoal
import forma.core.run.PaceTable
import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import forma.core.run.Vdot
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test

/**
 * A block of weeks of running for a runner with a true fitness the plan never sees, run as the app plans it (three runs
 * a week: easy, the hard session or a trial, the long one; the trial when it is due, as the app decides it), with the
 * runner doing what the fitness allows: reps held at the pace while the oxygen cost is within what Daniels' model says
 * can be held for that long, slower and very hard past it; trials at the fitness, a few per cent either way. The rules:
 * nothing breaks; the fitness the plan holds never drifts far from the truth (1 after a trial run to the second, more
 * by the trials' spread; 2 more between them: the ladders and the easy runs move it by one at a time); easy runs are
 * never asked faster than the truth allows; the week's running never jumps over the weeks before.
 */
class RunBlockRulesTest {
    data class Case(val truth: Double, val gain: Double, val noise: Double, val goal: RunGoal?, val lap: Int?, val weeks: Int, val caps: RunCaps, val known: Boolean, val rpeBias: Int) {
        override fun toString() = "бегун: форма $truth (+$gain в неделю, разброс ${(noise * 100).roundToInt()} %), оценки ${if (rpeBias >= 0) "+" else ""}$rpeBias; " +
            "цель ${goal?.let { "${it.meters} м за ${it.seconds} с" } ?: "нет"}; круг ${lap ?: "дорога"}; $weeks недель; лимиты ${caps.easyMax}/${caps.longMax}; форма известна с начала: $known"
    }

    private fun gen(r: Random): Case {
        val truth = r.range(28.0, 62.0)
        val m = r.pick(800, 1500, 1500, 3000, 5000)
        val goal = if (r.chance(0.6)) RunGoal(m, (Vdot.predictSeconds(truth, m.toDouble()) * r.range(0.9, 1.05)).roundToInt()) else null
        return Case(
            truth = (truth * 2).roundToInt() / 2.0, gain = r.pick(0.0, 0.0, 0.1, 0.2), noise = r.pick(0.0, 0.01, 0.02), goal = goal,
            lap = if (r.chance(0.5)) null else r.pick(200, 205, 230, 250, 400), weeks = r.nextInt(6, 21),
            caps = RunCaps(r.pick(45, 60), r.pick(75, 90, 120)), known = r.chance(0.3), rpeBias = r.pick(0, 0, 0, -1, 1),
        )
    }

    private fun shrink(c: Case): Sequence<Case> = sequence {
        if (c.weeks > 6) yield(c.copy(weeks = c.weeks - 2))
        if (c.noise > 0) yield(c.copy(noise = 0.0))
        if (c.gain > 0) yield(c.copy(gain = 0.0))
        if (c.rpeBias != 0) yield(c.copy(rpeBias = 0))
        if (c.goal != null) yield(c.copy(goal = null))
        if (c.lap != null) yield(c.copy(lap = null))
    }

    /** The runner: what the fitness [v] makes of the workout [w] (a day's form [form] on top of it). */
    private class Runner(var v: Double, val noise: Double, val bias: Int, val rnd: Random) {
        private fun gauss(): Double {
            val u = max(1e-12, rnd.nextDouble())
            return sqrt(-2 * ln(u)) * kotlin.math.cos(2 * Math.PI * rnd.nextDouble())
        }

        private fun rpe(felt: Double) = (felt.roundToInt() + bias).coerceIn(1, 10)

        fun run(w: RunWorkout): RunResult {
            val work = w.segments.filter { it.kind == SegmentKind.WORK }
            val tm = w.testMeters
            return when {
                w.type == RunType.TEST && tm != null -> {
                    val sec = Vdot.predictSeconds(v, tm.toDouble()) * (1 + gauss() * noise)
                    RunResult(1.0, rpe(9.5), w.totalSeconds, distanceM = tm + 3000.0, workSec = sec.roundToInt(), testSeconds = sec.roundToInt(), workDistanceM = tm.toDouble())
                }
                w.type == RunType.TEST -> {
                    var lo = 500.0
                    var hi = 6000.0
                    repeat(50) { val mid = (lo + hi) / 2; if (Vdot.predictSeconds(v, mid) < 720) lo = mid else hi = mid }
                    val d = lo * (1 + gauss() * noise)
                    RunResult(1.0, rpe(9.5), w.totalSeconds, distanceM = d + 3000, workDistanceM = d, workSec = 720)
                }
                work.isNotEmpty() -> {
                    val target = work.first().pace?.let { (it.fast + it.slow) / 2.0 } ?: Vdot.paceAtFraction(v, 0.95)
                    val longest = work.maxOf { it.seconds }
                    val total = work.sumOf { it.seconds }
                    val holdable = Vdot.fractionSustainable(min(total, longest * 3) / 60.0)
                    val x = Vdot.vo2(60_000.0 / target) / v / holdable
                    val held = x <= 1.0
                    val pace = if (held) target * (1 + gauss() * 0.006) else 60_000.0 / Vdot.velocityForVo2(holdable * v)
                    val felt = when {
                        x <= 0.85 -> 5.0
                        x <= 1.0 -> 5 + (x - 0.85) / 0.15 * 3
                        x <= 1.08 -> 9.0
                        else -> 10.0
                    }
                    val completed = if (x > 1.08) 0.75 else 1.0
                    RunResult(completed, rpe(felt), w.totalSeconds, distanceM = total / target * 1000 + 3000, workPace = pace, workSec = (total * completed).roundToInt())
                }
                else -> {
                    val seg = w.segments.firstOrNull { it.kind == SegmentKind.RUN }
                    val pace = seg?.pace?.let { (it.fast + it.slow) / 2.0 } ?: Vdot.paceAtFraction(v, 0.68)
                    val ratio = Vdot.vo2(60_000.0 / pace) / v
                    val felt = (2 + (ratio - 0.6) * 20 + w.totalSeconds / 60.0 / 40).coerceIn(1.0, 10.0)
                    RunResult(1.0, rpe(felt), w.totalSeconds, distanceM = w.totalSeconds / pace * 1000, workSec = w.workSeconds)
                }
            }
        }
    }

    /** A week's running of the log, minutes ([RunPlan.weekCap] counts it the same way). */
    private fun week(st: RunState, from: Long) = st.recent.filter { it.day in from until from + 7 }.sumOf { it.runMinutes }

    /** As the app decides whether a hard day is the trial (TrainingRepository.needsTest). */
    private fun needsTest(st: RunState, day: Long, goal: RunGoal?): Boolean {
        if (st.runs < 2 || st.returnEasy > 0) return false
        if (st.lastTrialTryDay?.let { day - it < 14 } == true) return false
        if (st.testSoon) return true
        val last = st.lastTestDay ?: return st.vdot == null || goal?.middleDistance == true
        return day - last >= if (goal?.middleDistance == true) 35 else 56
    }

    @Test fun aBlockOfWeeksKeepsTheFitnessNearTheTruth() = Fuzz.check("run_block_tracks_truth", ::gen, ::shrink, { it.toString() }) { c ->
        val rnd = Random(c.truth.hashCode() * 31 + c.weeks)
        val runner = Runner(c.truth, c.noise, c.rpeBias, rnd)
        val day0 = 20_001L
        // A fitness typed in at the start counts as a trial that day (as the app has it): the next comes in its time.
        var st = RunState(walkStage = -1, vdot = if (c.known) c.truth else null, easyMinutes = 30, longMinutes = 45, lastRunDay = 20_000L, lastTestDay = if (c.known) day0 - 1 else null)
        val log = StringBuilder()
        for (wk in 0 until c.weeks) {
            runner.v += c.gain
            for ((k, kind) in listOf("easy", "hard", "long").withIndex()) {
                val day = day0 + wk * 7L + k * 2
                // The app's own caps on the state it plans from: the plan's and the week's.
                val base = RunPlan.withBreak(st, day).let { s -> s.copy(easyMinutes = min(s.easyMinutes, c.caps.easyMax), longMinutes = min(s.longMinutes, c.caps.longMax)) }
                    .let { s -> RunPlan.weekCap(st, day)?.let { cap -> s.copy(easyMinutes = min(s.easyMinutes, cap), longMinutes = min(s.longMinutes, cap)) } ?: s }
                val guess = if (base.vdot == null && c.goal != null) base.copy(vdot = RunPlan.provisionalVdot(c.goal)) else base
                val w = when (kind) {
                    "easy" -> RunPlan.easy(guess, false, day)
                    "long" -> RunPlan.long(guess, false, day)
                    else -> when {
                        !needsTest(base, day, c.goal) -> RunPlan.quality(base, false, c.goal, c.lap)
                        c.goal?.middleDistance == true -> RunPlan.goalTest(base, c.goal, c.lap)
                        else -> RunPlan.test(base)
                    }
                }
                val r = runner.run(w)
                val a = RunPlan.after(st, w, r, c.caps, day, c.goal)
                var next = a.state
                // As the app keeps the trial's day (TrainingRepository.decideRun).
                val part = a.outcomes.any { it is RunOutcome.TestFromPart && !it.finished }
                if (w.type == RunType.TEST && !part && a.outcomes.any { it is RunOutcome.VdotChanged }) next = next.copy(lastTestDay = day)
                else if (w.type == RunType.TEST) next = next.copy(lastTrialTryDay = day)
                log.appendLine("нед. ${wk + 1} $kind: ${w.title} → ${a.outcomes.joinToString()}; форма ${next.vdot}, правда ${"%.1f".format(runner.v)}")
                val v = next.vdot
                if (v != null) {
                    if (v.isNaN() || v < RunPlan.MIN_VDOT || v > RunPlan.MAX_VDOT) return@check "форма $v\n$log"
                    val gap = abs(v - runner.v)
                    // A trial's time a few per cent off (three times the runner's spread) is about 0,8 of fitness a per cent.
                    val tol = 1.0 + 250 * c.noise
                    if (w.type == RunType.TEST && a.outcomes.any { it is RunOutcome.VdotChanged } && gap > tol) return@check "после контрольного форма $v, а правда ${"%.1f".format(runner.v)}\n$log"
                    if (gap > tol + 2.0) return@check "форма $v ушла от правды ${"%.1f".format(runner.v)}\n$log"
                    // Easy runs never faster than the truth allows: the fast end of the easy range at 80 % of the true fitness at most.
                    val easyFast = PaceTable.forVdot(v).easy.fast
                    if (easyFast < Vdot.paceAtFraction(runner.v, 0.80) - 1) return@check "лёгкий темп ${easyFast} с/км быстрее, чем позволяет правда ${"%.1f".format(runner.v)} (${Vdot.paceAtFraction(runner.v, 0.80).roundToInt()})\n$log"
                }
                if (next.easyMinutes > c.caps.easyMax || next.longMinutes > c.caps.longMax) return@check "лёгкий ${next.easyMinutes} / длинный ${next.longMinutes} за лимитами ${c.caps}\n$log"
                st = next
            }
            // The week's running against the busiest of the three before, once there are three: no jump over them.
            if (wk >= 3) {
                val now = week(st, day0 + wk * 7L)
                val before = (1..3).maxOf { week(st, day0 + (wk - it) * 7L) }
                if (before > 0 && now > before * 1.1 + 30) return@check "неделя ${wk + 1}: бега $now мин при $before в самой большой из трёх до неё\n$log"
            }
        }
        null
    }
}
