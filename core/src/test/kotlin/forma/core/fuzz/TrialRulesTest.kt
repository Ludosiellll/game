package forma.core.fuzz

import forma.core.fuzz.Fuzz.chance
import forma.core.fuzz.Fuzz.pick
import forma.core.fuzz.Fuzz.range
import forma.core.model.RunGoal
import forma.core.run.Press
import forma.core.run.RunCaps
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunReview
import forma.core.run.RunState
import forma.core.run.RunTracker
import forma.core.run.Vdot
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test

/**
 * A time trial on a stadium pressed lap by lap on the tracker (no GPS), as anybody runs one: at the beeps, faster or
 * slower, faded after a while, a lap walked, stopped; the press on the line missed once, a thumb on the screen mid-lap
 * once or twice; the time typed over the laps' or not. The rules: the tracker never has more laps than the trial, the
 * time it gives is the time run (to a second) and only of a trial run to its end, a trial run to its end on clean laps
 * always gets its time, and the plan's reading of it is sane — the fitness never more than 3 above the whole time's,
 * a trial not run to the end comes again, and the debrief says nothing broken.
 */
class TrialRulesTest {
    data class Case(
        val state: RunState, val goal: RunGoal, val lap: Int, val laps: List<Double>, val of: Int,
        val strays: Map<Int, Double>, val missed: Set<Int>, val typed: Int?,
    ) {
        override fun toString() = buildString {
            appendLine("бегун: форма ${state.vdot}; цель ${goal.meters} м за ${goal.seconds} с; круг $lap м; контрольный $of кругов")
            appendLine("круги (с): " + laps.joinToString(" ") { "%.1f".format(it) } + if (laps.size < of) " — остановился" else "")
            if (strays.isNotEmpty()) appendLine("лишние нажатия: " + strays.entries.joinToString { "круг ${it.key} на ${"%.2f".format(it.value)}" })
            if (missed.isNotEmpty()) appendLine("пропущенные нажатия: круги $missed")
            typed?.let { appendLine("вписано время: $it с") }
        }
    }

    private val day = 20_500L
    private val caps = RunCaps(45, 90)

    private fun gen(r: Random): Case {
        val m = r.pick(800, 1500, 1500, 3000)
        val truth = r.range(28.0, 65.0)
        val goal = RunGoal(m, (Vdot.predictSeconds(truth, m.toDouble()) * r.range(0.9, 1.1)).roundToInt())
        val state = RunState(
            walkStage = -1, runs = r.nextInt(3, 60), lastRunDay = day - 2,
            vdot = if (r.chance(0.4)) null else r.range(28.0, 65.0).let { (it * 2).roundToInt() / 2.0 },
        )
        val lap = r.pick(200, 205, 230, 250, 400)
        val w = RunPlan.goalTest(state, goal, lap)
        val seg = w.segments.first { it.laps != null }
        val of = ceil(seg.laps!!).toInt()
        val first = if (seg.laps!! % 1.0 != 0.0) seg.laps!! % 1.0 else 1.0
        val pace = r.range(0.85, 1.15)
        val fadeAt = if (r.chance(0.5)) r.nextInt(of / 2, of + 1) else 99
        val walkAt = if (r.chance(0.2)) r.nextInt(2, of + 1) else 99
        val n = if (r.chance(0.25)) r.nextInt(1, of) else of
        var slow = 1.0
        val laps = (1..n).map { k ->
            val part = if (k == 1) first else 1.0
            if (k >= fadeAt) slow *= r.range(1.03, 1.2)
            seg.lapSec!! * part * pace * slow.coerceAtMost(1.55) * (if (k == walkAt) r.range(1.75, 2.6) else r.range(0.98, 1.02))
        }
        val missed = if (r.chance(0.15) && n >= 2) setOf(r.nextInt(1, n + 1)) else emptySet()
        // A thumb on the screen once, in a lap run (in a lap walked, or far slower than the lap before it — over 1,3 of
        // it — a touch half way round passes for a slow lap, and the line after it for another: not here), and not in
        // the last lap when its press on the line is missed too: the touch then is the only press of
        // the lap, and nothing tells it from the finish (the time is wrong and nobody can know it). In the last lap no
        // later than 0,7 of the way round: a touch nearer the line makes a lap like the others, and the press on the line
        // seconds after it cannot be told from a touch after the finish — the tracker keeps the earlier press (the time
        // short by those seconds) rather than let a touch after the finish make the time longer. Twice in a trial of
        // two laps the touches are the only laps there are to tell a touch by: not asked of the tracker either. In the
        // first lap, with only the planned lap to tell by, a touch between 0,55 and 0,65 of the way round for a runner
        // well slower than the beeps passes for a lap and the line after it for another — and a first lap over 1,3 of
        // the planned one is past what the planned lap can tell at all: slivers left as they are, so a runner well
        // faster than the beeps keeps a first lap that is his. GPS tells a touch from the line on the loop.
        val run = (1..n).filter { k ->
            laps[k - 1] / (seg.lapSec!! * (if (k == 1) first else 1.0)) < 1.6 && !(k == n && k in missed) && !(k == 1 && laps[0] / (seg.lapSec!! * first) > 1.3) &&
                (k == 1 || laps[k - 1] <= 1.3 * laps[k - 2] / (if (k == 2) first else 1.0))
        }
        val strays = if (r.chance(0.3) && run.isNotEmpty()) r.pick(run).let { k ->
            var f = r.range(0.1, if (k == n) 0.7 else 0.9)
            if (k == 1 && f in 0.55..0.65) f = if (r.chance(0.5)) 0.5 else 0.7
            mapOf(k to f)
        } else emptyMap()
        val typed = if (r.chance(0.3) && n == of) laps.sum().roundToInt() + r.nextInt(-3, 4) else null
        return Case(state, goal, lap, laps, of, strays, missed, typed)
    }

    private fun shrink(c: Case): Sequence<Case> = sequence {
        if (c.strays.isNotEmpty()) yield(c.copy(strays = emptyMap()))
        if (c.missed.isNotEmpty()) yield(c.copy(missed = emptySet()))
        if (c.typed != null) yield(c.copy(typed = null))
        if (c.state.vdot != null) yield(c.copy(state = c.state.copy(vdot = null)))
    }

    /** The trial pressed on the tracker: the result as the finish screen saves it, and the time of the last real crossing. */
    private fun pressed(c: Case): Pair<RunResult, Int> {
        val w = RunPlan.goalTest(c.state, c.goal, c.lap)
        val t = RunTracker(w)
        t.start(0)
        val start = 12 * 60 * 1000L
        check(t.press(start) == Press.Start)
        var at = start
        c.laps.forEachIndexed { i, s ->
            c.strays[i + 1]?.let { f -> t.press(at + (s * f * 1000).roundToInt()) }
            at += (s * 1000).roundToInt()
            if (i + 1 !in c.missed) t.press(at)
        }
        t.finish(at + 3_000)
        return t.result(at + 3_000, 9) to ((at - start) / 1000.0).roundToInt()
    }

    @Test fun trialPressesGiveTheTimeRunOrNone() = Fuzz.check("run_trial_presses", ::gen, ::shrink, { it.toString() }) { c ->
        val w = RunPlan.goalTest(c.state, c.goal, c.lap)
        val seg = w.segments.first { it.laps != null }
        val first = if (seg.laps!! % 1.0 != 0.0) seg.laps!! % 1.0 else 1.0
        val (r, real) = pressed(c)
        val finished = c.laps.size == c.of
        val reps = r.laps.orEmpty().filter { it.rep == 1 }
        // Clean laps: each within half to 1,7 of the planned one (over it is a walk, and the time is rightly not trusted;
        // a hair under it the presses' milliseconds decide, so 1,69 here) — a touch late in a lap (past 0,9 of it, not
        // told from the line) leaves the rest of that lap on the lap after.
        val clean = c.laps.withIndex().all { (i, s) ->
            val expected = seg.lapSec!! * if (i == 0) first else 1.0
            val tail = c.strays[i]?.let { f -> (1 - f) * c.laps[i - 1] } ?: 0.0
            s / expected in 0.5..1.69 && (s + tail) / expected <= 1.69
        }
        val t = r.testSeconds
        when {
            reps.size > c.of -> return@check "кругов в контрольном ${reps.size}, а в нём ${c.of}"
            t != null && !finished -> return@check "контрольный остановлен на ${c.laps.size} из ${c.of} кругов, а время есть: $t с"
            t != null && abs(t - real) > 1 -> return@check "время контрольного $t с, пробежал за $real с (круги ${reps.map { "%.1f".format(it.seconds) }})"
            t == null && finished && clean && c.missed.isEmpty() -> return@check "контрольный добежан, круги чистые, нажатия все — а времени нет (круги ${reps.map { "%.1f".format(it.seconds) }})"
            else -> Unit
        }
        val saved = if (c.typed != null) r.copy(testSeconds = c.typed) else r
        val a = RunPlan.after(c.state, w, saved, caps, day, c.goal)
        val v = a.state.vdot
        if (v != null && (v.isNaN() || v < RunPlan.MIN_VDOT || v > RunPlan.MAX_VDOT)) return@check "форма $v"
        val time = saved.testSeconds
        if (time != null && a.outcomes.any { it is RunOutcome.VdotChanged }) {
            val whole = Vdot.fromRace(w.testMeters!!.toDouble(), time.toDouble())
            if (v!! > whole + RunPlan.PART_OVER_WHOLE + 0.26) return@check "форма $v, а по всему времени ${"%.1f".format(whole)}: больше чем на 3 выше"
        }
        val part = a.outcomes.filterIsInstance<RunOutcome.TestFromPart>().firstOrNull()
        if (part != null && !part.finished && !a.state.testSoon) return@check "контрольный не добежан ($part), а полный не назначен"
        if (part != null && part.finished && time == null) return@check "«добежал» без времени: $part"
        val held = part?.let { it.meters to it.seconds }
        val trial = a.outcomes.filterIsInstance<RunOutcome.TestTime>().firstOrNull()?.let { it.meters to it.seconds }
        val items = RunReview.of(RunReview.Run(w, saved.laps.orEmpty(), 9, saved.durationSec, null, saved.workPace, trial, saved.completed, RunOutcome.GoalReached in a.outcomes, held), c.goal)
        items.flatMap { listOf(it.title, it.text) }.firstOrNull { s -> listOf("NaN", "Infinity", "null", "–:––").any { it in s } }?.let { "в тексте мусор: «$it»" }
    }
}
