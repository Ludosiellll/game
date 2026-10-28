package forma.core.fuzz

import forma.core.fuzz.Fuzz.chance
import forma.core.fuzz.Fuzz.pick
import forma.core.fuzz.Fuzz.range
import forma.core.model.RunGoal
import forma.core.run.Cue
import forma.core.run.Lap
import forma.core.run.RunCaps
import forma.core.run.RunExplain
import forma.core.run.RunLog
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunReview
import forma.core.run.RunState
import forma.core.run.RunTimeline
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import forma.core.run.Vdot
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test

/**
 * Running's rules for anybody, on random runners, plans and runs: the state stays sane, the form moves slowly without a
 * trial, a run done better (lighter effort, more of it, faster) never leaves the plan worse, a shorter loop never
 * flatters, a stopped trial never lowers the form, the goal is reached only by a time that reaches it, and the run's
 * clock, voice and debrief make sense.
 */
class RunRulesTest {
    data class Case(val state: RunState, val w: RunWorkout, val r: RunResult, val caps: RunCaps, val day: Long, val goal: RunGoal?) {
        override fun toString() = buildString {
            appendLine("бегун: форма ${state.vdot}, бег/шаг ${state.walkStage}, лёгкий ${state.easyMinutes} мин, длинный ${state.longMinutes} мин, " +
                "интервалы ${state.intervalStage}, темповый ${state.tempoStage}, темп цели ${state.goalStage}, тяжёлых ${state.qualityCount}, " +
                "пробежек ${state.runs}, контрольный ${state.lastTestDay?.let { day - it }} дн назад, последняя пробежка ${state.lastRunDay?.let { day - it }} дн назад, " +
                "промахи ${state.qualityFails}/${state.intervalFails}/${state.tempoFails}/${state.goalFails}, в темпе ${state.qualityOnPace}, " +
                "скоро контрольный ${state.testSoon}, после перерыва ${state.returnEasy}, оценки лёгких ${state.easyRpes}, знаки ${state.easySigns}")
            appendLine("цель: ${goal?.let { "${it.meters} м за ${it.seconds} с" } ?: "нет"}; лимиты ${caps.easyMax}/${caps.longMax}; день $day")
            appendLine("план: ${w.type} «${w.title}», ${w.totalSeconds} с, повторы ${w.reps}, круг ${w.lapMeters}, контрольный ${w.testMeters} м, облегчён ${w.cutback}, тяжесть ${w.targetRpe}")
            w.segments.forEach { s -> appendLine("  ${s.kind} ${s.seconds} с${s.pace?.let { p -> " темп ${p.fast}–${p.slow}" } ?: ""}${s.laps?.let { " ${it} кр" } ?: ""}${s.lapSec?.let { " по ${"%.1f".format(it)} с" } ?: ""}") }
            append("сделано: ${(r.completed * 100).roundToInt()} %, тяжесть ${r.rpe}, ${r.durationSec} с, темп отрезков ${r.workPace?.roundToInt()}, повторы ${r.reps}, " +
                "контрольный ${r.testSeconds} с, путь ${r.workDistanceM?.roundToInt()} м за ${r.workSec} с, круг по GPS ${r.loopM?.roundToInt()}")
            r.laps?.let { l -> append("\nкруги: " + l.joinToString(" ") { "${it.rep ?: "-"}/${it.number}:${"%.1f".format(it.seconds)}" }) }
        }
    }

    private fun state(r: Random, day: Long): RunState {
        // The whole run/walk ladder, its last rungs too (a graduation); the top of the race-pace ladder now and then (the
        // trial next, or one too soon); easy runs that agree five times in a row (the paces follow them).
        val walk = if (r.chance(0.15)) (if (r.chance(0.3)) RunPlan.walkLadderSize - 1 else r.nextInt(0, RunPlan.walkLadderSize)) else -1
        val recent = (1..r.nextInt(0, 14)).map { RunLog(day - r.nextInt(1, 30), r.nextInt(10, 90)) }.sortedBy { it.day }
        val top = r.chance(0.2)
        val signs = if (r.chance(0.2)) (if (r.chance(0.5)) 1 else -1).let { sign -> List(r.nextInt(4, 7)) { sign } } else List(r.nextInt(0, 7)) { r.nextInt(-1, 2) }
        return RunState(
            vdot = if (r.chance(0.2)) null else r.range(28.0, 70.0).let { (it * 2).roundToInt() / 2.0 },
            walkStage = walk, easyMinutes = r.nextInt(20, 61), longMinutes = r.nextInt(30, 121),
            intervalStage = r.nextInt(0, RunPlan.intervalLadderSize), tempoStage = r.nextInt(0, RunPlan.tempoLadderSize), qualityCount = r.nextInt(0, 20), runs = r.nextInt(0, 200),
            lastTestDay = if (r.chance(0.4)) day - r.nextInt(5, 90) else if (top && r.chance(0.5)) day - r.nextInt(1, 21) else null,
            qualityOnPace = r.nextInt(0, 3), qualityFails = r.nextInt(0, 3), intervalFails = r.nextInt(0, 2), tempoFails = r.nextInt(0, 2),
            tempoSoft = 3 * r.nextInt(0, 5), walkFails = r.nextInt(0, 2), lastVdotUpDay = if (r.chance(0.3)) day - r.nextInt(1, 60) else null,
            lastRunDay = if (r.chance(0.85)) day - r.nextInt(1, 5) else if (r.chance(0.5)) day - r.nextInt(8, 60) else null,
            recent = recent, returnEasy = if (r.chance(0.1)) r.nextInt(1, 3) else 0,
            goalStage = if (top) RunPlan.goalLadderSize - 1 else r.nextInt(0, RunPlan.goalLadderSize),
            goalFails = r.nextInt(0, 2), testSoon = r.chance(0.2), lastTrialTryDay = if (r.chance(0.1)) day - r.nextInt(1, 30) else null,
            easyRpes = List(r.nextInt(0, 9)) { r.nextInt(2, 9) }, easySigns = signs,
            goalDeferredOn = if (r.chance(0.1)) day - r.nextInt(1, 5) else null,
        )
    }

    private fun goal(r: Random, st: RunState): RunGoal? {
        if (r.chance(0.35)) return null
        val m = r.pick(800, 1500, 1500, 3000, 5000, 10000)
        val now = Vdot.predictSeconds(st.vdot ?: 40.0, m.toDouble())
        return RunGoal(m, (now * r.range(0.8, 1.02)).roundToInt())
    }

    private fun workout(r: Random, st: RunState, goal: RunGoal?, day: Long): RunWorkout {
        val lap = if (r.chance(0.5)) null else r.pick(200, 205, 230, 250, 400)
        val cut = r.chance(0.15)
        fun track(w: RunWorkout) = if (lap != null) RunPlan.onTrack(w, lap) else w
        val type = r.pick(RunType.EASY, RunType.LONG, RunType.INTERVALS, RunType.TEMPO, RunType.GOAL_PACE, RunType.TEST, RunType.TEST, RunType.RUN_WALK)
        return when {
            type == RunType.RUN_WALK || st.walkStage >= 0 -> RunPlan.runWalk(st)
            type == RunType.EASY -> RunPlan.easy(st, cut, day, if (r.chance(0.3)) RunPlan.strides(st, cut) else 0)
            type == RunType.LONG -> RunPlan.long(st, cut, day)
            type == RunType.INTERVALS -> track(RunPlan.intervals(st, cut))
            type == RunType.TEMPO -> track(RunPlan.tempo(st, cut))
            type == RunType.GOAL_PACE && goal != null && goal.middleDistance -> RunPlan.goalPace(st, goal, lap, cut)
            type == RunType.TEST && goal != null && goal.middleDistance -> RunPlan.goalTest(st, goal, lap)
            type == RunType.TEST -> RunPlan.test(st)
            else -> RunPlan.quality(st, cut, goal, lap)
        }
    }

    /**
     * Laps of the reps as run: on the planned time, faster or slower, now and then a fade, a walk (far over the planned
     * lap), a stop, a press missed (a lap doubled). A trial walks and stops more often than reps do: its stretch held,
     * walked to the end or stopped after slowing, is what the plan reads most carefully.
     */
    private fun laps(r: Random, w: RunWorkout): List<Lap>? {
        if (w.lapMeters == null) return null
        val reps = w.segments.filter { it.kind == SegmentKind.WORK && it.laps != null }
        if (reps.isEmpty()) return null
        val trial = w.testMeters != null
        val out = mutableListOf<Lap>()
        val pace = r.range(0.85, 1.15)
        val fadeAt = if (r.chance(0.4)) r.nextInt(1, 8) else 99
        val walkAt = if (r.chance(if (trial) 0.25 else 0.08)) r.nextInt(2, 9) else 99
        val stopRep = if (r.chance(if (trial) 0.3 else 0.2)) r.nextInt(1, reps.size + 1) else 99
        reps.forEachIndexed { k, s ->
            if (k + 1 > stopRep) return out
            val of = ceil(s.laps!!).toInt()
            val first = if (s.laps!! % 1.0 != 0.0) s.laps!! % 1.0 else 1.0
            val stopLap = if (k + 1 == stopRep) r.nextInt(1, of + 1) else of
            for (n in 1..stopLap) {
                val part = if (n == 1) first else 1.0
                var sec = s.lapSec!! * part * pace * (if (n >= fadeAt) r.range(1.05, 1.5) else r.range(0.97, 1.03))
                if (n == walkAt || n == walkAt + 1 && r.chance(0.5)) sec *= r.range(1.6, 2.6)
                if (r.chance(0.03)) sec *= 2.0
                out += Lap(sec, s.lapSec!! * part * 0.98, s.lapSec!! * part * 1.02, n, of, k + 1, part)
            }
        }
        return out
    }

    private fun result(r: Random, w: RunWorkout, st: RunState, goal: RunGoal?): RunResult {
        val done = if (r.chance(0.65)) 1.0 else r.range(0.2, 1.0)
        val target = w.segments.firstOrNull { it.kind == SegmentKind.WORK }?.pace?.let { (it.fast + it.slow) / 2.0 }
        val laps = laps(r, w)
        val tm = w.testMeters
        // A time typed: most trials on the road, fewer with laps (the laps alone are the trial then — or none of it).
        val trialSec = if (tm != null && r.chance(if (laps != null) 0.4 else 0.7)) (Vdot.predictSeconds(st.vdot ?: 40.0, tm.toDouble()) * r.range(0.85, 1.25)).roundToInt() else null
        val road = w.type == RunType.TEST && w.lapMeters == null
        val workSec = if (road) (w.workSeconds * r.range(0.4, 1.0)).roundToInt() else null
        return RunResult(
            completed = done, rpe = r.nextInt(1, 11), durationSec = max(60, (w.totalSeconds * r.range(0.5, 1.3)).roundToInt()),
            distanceM = if (r.chance(0.7)) r.range(500.0, 25000.0) else null,
            workPace = if (target != null && r.chance(0.8)) target * r.range(0.9, 1.12) else null,
            workDistanceM = if (road && r.chance(0.8)) (workSec!! / (Vdot.predictSeconds(st.vdot ?: 40.0, 1000.0) * r.range(0.9, 1.3))) * 1000 else null,
            workSec = workSec, laps = laps, reps = if (w.reps > 0 && r.chance(0.5)) max(0, w.reps + r.nextInt(-2, 3)) else null,
            testSeconds = trialSec,
            loopM = if (w.lapMeters != null && r.chance(0.2)) w.lapMeters!! * r.range(0.85, 1.02) else null,
        )
    }

    private fun gen(r: Random): Case {
        val day = 20_000L + r.nextInt(0, 2000)
        val st = state(r, day)
        val goal = goal(r, st)
        val w = workout(r, st, goal, day)
        return Case(st, w, result(r, w, st, goal), RunCaps(r.pick(30, 45, 60, 75), r.pick(60, 90, 120, 150)), day, goal)
    }

    private fun shrink(c: Case): Sequence<Case> = sequence {
        c.r.laps?.let { l -> if (l.size > 1) yield(c.copy(r = c.r.copy(laps = l.dropLast(1)))) }
        if (c.r.loopM != null) yield(c.copy(r = c.r.copy(loopM = null)))
        if (c.r.reps != null) yield(c.copy(r = c.r.copy(reps = null)))
        if (c.r.completed < 1.0) yield(c.copy(r = c.r.copy(completed = 1.0)))
        if (c.state.recent.isNotEmpty()) yield(c.copy(state = c.state.copy(recent = c.state.recent.drop(1))))
        if (c.state.easySigns.isNotEmpty()) yield(c.copy(state = c.state.copy(easySigns = emptyList())))
        if (c.state.easyRpes.isNotEmpty()) yield(c.copy(state = c.state.copy(easyRpes = emptyList())))
    }

    private fun after(c: Case, r: RunResult = c.r) = RunPlan.after(c.state, c.w, r, c.caps, c.day, c.goal)

    private fun check(name: String, rule: (Case) -> String?) = Fuzz.check(name, ::gen, ::shrink, { it.toString() }, rule = rule)

    /** No break before this run: the form and the minutes are what the state says (a break cuts them, on purpose). */
    private fun noBreak(c: Case) = RunPlan.breakDays(c.state, c.day) == null

    /** What an outcome is for the reach count: its kind, and for a trial's stretch and a form change which way it went. */
    private fun kind(o: RunOutcome): String = when (o) {
        is RunOutcome.TestFromPart -> "TestFromPart(finished=${o.finished}, kept=${o.kept}, capped=${o.capped})"
        is RunOutcome.StageUp -> "StageUp(${o.ladder})"
        is RunOutcome.StageDown -> "StageDown(${o.ladder})"
        is RunOutcome.StageRepeat -> "StageRepeat(${o.ladder})"
        is RunOutcome.LighterWeek -> "LighterWeek(${o.ladder})"
        is RunOutcome.LadderTop -> "LadderTop(${o.ladder})"
        is RunOutcome.VdotChanged -> "VdotChanged(" + (if (o.from == null) "set" else if (o.to > o.from) "up" else if (o.to < o.from) "down" else "same") + if (o.easier) ", easier)" else ")"
        is RunOutcome.TempoPace -> "TempoPace(softer=${o.softer})"
        is RunOutcome.EasyPaces -> "EasyPaces(${if (o.to > o.from) "up" else "down"})"
        else -> o::class.simpleName ?: o.toString()
    }

    /**
     * The cases the rules see reach every decision that matters: a rule on cases that never get to a branch says nothing
     * of it (seen: the stretch of a stopped trial held back by the time to its end, for a form not known, was reached by
     * none of 4000 cases; the paces moved by the easy runs by one). Counted on [REACH] cases, written to
     * `build/fuzz/reach/outcomes.txt`; a kind never reached fails.
     */
    @Test fun runCasesReachEveryDecisionThatMatters() {
        val seen = sortedMapOf<String, Int>()
        val types = sortedMapOf<RunType, Int>()
        // The same cases every time, whatever seed the background run has: the count is of the generator, not of the
        // engine, and a rare kind missed by one seed must not fail a run with no report to show for it.
        for (i in 0 until REACH) {
            val c = gen(Random(REACH_SEED + i))
            types.merge(c.w.type, 1, Int::plus)
            after(c).outcomes.forEach { seen.merge(kind(it), 1, Int::plus) }
        }
        val must = listOf(
            "TestTime", "GoalReached", "TestDoubtful", "TestNotCounted", "TestStopped", "TestNext", "TrialLater", "LoopShorter",
            "TestFromPart(finished=true, kept=false, capped=false)", "TestFromPart(finished=true, kept=false, capped=true)",
            "TestFromPart(finished=false, kept=false, capped=false)", "TestFromPart(finished=false, kept=false, capped=true)",
            "TestFromPart(finished=false, kept=true, capped=false)",
            "VdotChanged(set)", "VdotChanged(up)", "VdotChanged(down)", "VdotChanged(up, easier)", "EasyPaces(up)", "EasyPaces(down)",
            "StageUp(goal)", "StageDown(goal)", "StageRepeat(goal)", "StageUp(intervals)", "StageDown(intervals)", "StageUp(tempo)", "StageDown(tempo)",
            "StageUp(walk)", "StageDown(walk)", "Graduated", "TooFast", "TooFastRepeat", "TooSlow", "PaceUnknown", "Longer", "Shorter", "Same",
            "LighterWeek(goal)", "LighterWeek(intervals)", "LighterWeek(tempo)", "LadderTop(intervals)", "TempoPace(softer=true)", "TempoPace(softer=false)", "OwnScale",
        )
        val text = buildString {
            appendLine("случаев $REACH")
            types.forEach { (t, n) -> appendLine("$t\t$n") }
            seen.forEach { (k, n) -> appendLine("$k\t$n") }
        }
        // In its own folder: the background run opens an issue for every `build/fuzz/*.txt` when a rule is broken.
        File("build/fuzz/reach").mkdirs()
        File("build/fuzz/reach/outcomes.txt").writeText(text)
        val missing = must.filter { (seen[it] ?: 0) == 0 }
        if (missing.isNotEmpty()) throw AssertionError("случаи не доходят до: $missing\n$text")
    }

    @Test fun runStateStaysSane() = check("run_state_sane") { c ->
        val a = after(c)
        val s = a.state
        // The form is said to move by a trial, the ladder, the easy runs' pace or a break: the last said is the form.
        val vs = a.outcomes.mapNotNull { o -> when (o) { is RunOutcome.VdotChanged -> o.to; is RunOutcome.EasyPaces -> o.to; else -> null } }
        when {
            s.vdot?.let { it.isNaN() || it < RunPlan.MIN_VDOT - 1e-9 || it > RunPlan.MAX_VDOT + 1e-9 } == true -> "форма ${s.vdot}"
            s.easyMinutes !in 10..300 || s.longMinutes !in 10..400 -> "лёгкий ${s.easyMinutes}, длинный ${s.longMinutes}"
            s.intervalStage < 0 || s.tempoStage < 0 || s.goalStage !in 0 until RunPlan.goalLadderSize || s.walkStage < -1 -> "ступени ${s.intervalStage}/${s.tempoStage}/${s.goalStage}/${s.walkStage}"
            s.runs != c.state.runs + 1 -> "пробежек ${s.runs} после ${c.state.runs}"
            vs.isNotEmpty() && vs.last() != s.vdot -> "последнее «форма → ${vs.last()}», а форма ${s.vdot}"
            vs.isEmpty() && s.vdot != c.state.vdot -> "форма изменилась ${c.state.vdot} → ${s.vdot} без слова об этом"
            s.qualityFails < 0 || s.goalFails < 0 || s.walkFails < 0 -> "отрицательные промахи"
            else -> null
        }
    }

    @Test fun runFormMovesSlowlyWithoutATrial() = check("run_form_slow") { c ->
        if (c.w.type == RunType.TEST || !noBreak(c)) return@check null
        val s = after(c).state
        val from = c.state.vdot ?: return@check null
        val to = s.vdot ?: return@check "форма пропала"
        if (abs(to - from) > 1.0 + 1e-9) "без контрольного форма $from → $to" else null
    }

    /**
     * [a] is at least as far along as [b] in what the run moves: ladders, minutes, form ([form]: an easy run done in full
     * may rightly bring the paces down — run very hard at the easy pace, it says they are too fast).
     */
    private fun notBehind(c: Case, a: RunState, b: RunState, form: Boolean = true): String? = when {
        form && (a.vdot ?: 0.0) < (b.vdot ?: 0.0) - 1e-9 -> "форма ${a.vdot} < ${b.vdot}"
        c.w.type == RunType.INTERVALS && a.intervalStage < b.intervalStage -> "интервалы ${a.intervalStage} < ${b.intervalStage}"
        c.w.type == RunType.TEMPO && a.tempoStage < b.tempoStage -> "темповый ${a.tempoStage} < ${b.tempoStage}"
        c.w.type == RunType.GOAL_PACE && a.goalStage < b.goalStage && !(a.testSoon && !b.testSoon) -> "темп цели ${a.goalStage} < ${b.goalStage}"
        c.w.type == RunType.EASY && a.easyMinutes < b.easyMinutes -> "лёгкий ${a.easyMinutes} < ${b.easyMinutes}"
        c.w.type == RunType.LONG && a.longMinutes < b.longMinutes -> "длинный ${a.longMinutes} < ${b.longMinutes}"
        c.w.type == RunType.RUN_WALK && a.walkStage >= 0 && b.walkStage >= 0 && a.walkStage < b.walkStage -> "бег/шаг ${a.walkStage} < ${b.walkStage}"
        c.w.type == RunType.RUN_WALK && a.walkStage >= 0 && b.walkStage < 0 -> "бег/шаг: легче — а выпуска нет"
        else -> null
    }

    @Test fun runLighterEffortNeverLeavesThePlanWorse() = check("run_lighter_never_worse") { c ->
        if (c.r.rpe <= 1 || c.w.type == RunType.TEST) return@check null
        val hard = after(c).state
        val light = after(c, c.r.copy(rpe = c.r.rpe - 1)).state
        notBehind(c, light, hard)?.let { "та же пробежка на 1 легче по ощущениям — а план хуже: $it" }
    }

    @Test fun runMoreDoneNeverLeavesThePlanWorse() = check("run_more_never_worse") { c ->
        if (c.r.completed >= 1.0 || c.w.type == RunType.TEST) return@check null
        val part = after(c).state
        val full = after(c, c.r.copy(completed = 1.0, reps = c.r.reps?.let { max(it, c.w.reps) })).state
        notBehind(c, full, part, form = c.w.type != RunType.EASY && c.w.type != RunType.LONG)?.let { "та же пробежка до конца — а план хуже, чем после неполной: $it" }
    }

    @Test fun runFasterTrialNeverLowersTheForm() = check("run_trial_faster") { c ->
        if (c.w.type != RunType.TEST || c.w.testMeters == null) return@check null
        val faster = c.r.copy(
            testSeconds = c.r.testSeconds?.let { (it * 0.98).roundToInt() },
            laps = c.r.laps?.map { it.copy(seconds = it.seconds * 0.98) },
            workDistanceM = c.r.workDistanceM?.let { it * 1.02 },
        )
        val a = after(c)
        val b = after(c, faster)
        // A result too far from the form known is taken for a typo, faster or not — the whole time or a stretch of it:
        // that is the guard, not the rule. And one not counted (or a stretch below the form known, kept) leaves the form
        // as it was, where one counted may rightly lower it: the rule compares two results counted.
        val notCounted = { x: List<RunOutcome> -> x.any { it is RunOutcome.TestDoubtful || it == RunOutcome.TestNotCounted || it is RunOutcome.TestStopped || it is RunOutcome.TestFromPart && it.kept } }
        if (notCounted(a.outcomes) || notCounted(b.outcomes)) return@check null
        // Faster than anybody runs (a stretch at 2:13 a km; the world's record is 2:17): at the pace the engine takes for a
        // typo, 2 % faster may fall out of what it counts — the guard, not the rule. A stretch only past belief next to the
        // form known counts, capped (it was dropped, and a faster trial gave a lower form).
        val human = { x: List<RunOutcome> -> x.none { o -> o is RunOutcome.TestFromPart && o.seconds / (o.meters / 1000.0) < 140.0 ||
            o is RunOutcome.TestTime && o.seconds / (o.meters / 1000.0) < 140.0 } }
        if (!human(a.outcomes) || !human(b.outcomes)) return@check null
        // Laps far from the time typed (a tenth and more) are no record of that trial (past a sixth the engine drops them):
        // which side of that line a trial 2 % faster falls on is the seconds' rounding, not the rule.
        val laps = c.r.laps?.filter { it.rep == 1 }?.sumOf { it.seconds }
        if (laps != null && c.r.testSeconds != null && abs(laps / c.r.testSeconds!! - 1) > 0.10) return@check null
        val x = a.state.vdot ?: 0.0
        val y = b.state.vdot ?: 0.0
        // A stretch held is the best of those of half the trial and 600 m at least: faster, another one with more of the
        // fade in it may be the best — up to a point lower.
        if (y < x - 1.0 - 1e-9) "контрольный на 2 % быстрее — а форма ниже больше чем на 1: $y < $x (${a.outcomes} / ${b.outcomes})" else null
    }

    @Test fun runATrialRunFurtherNeverLowersTheForm() = check("run_trial_further") { c ->
        val laps = c.r.laps
        if (c.w.type != RunType.TEST || c.w.testMeters == null || laps == null || laps.isEmpty() || c.r.testSeconds != null) return@check null
        val last = laps.last()
        if (last.of == null || last.number >= last.of!!) return@check null
        // A trial run to its end may rightly lower a form known (it measures it), one stopped never does: the rule is
        // for a form not known yet.
        if (RunPlan.withBreak(c.state, c.day).vdot != null) return@check null
        val further = c.r.copy(laps = laps + last.copy(number = last.number + 1, part = 1.0, seconds = last.seconds / last.part))
        val a = after(c)
        val b = after(c, further)
        val guarded = { x: List<RunOutcome> -> x.any { it is RunOutcome.TestDoubtful || it == RunOutcome.TestNotCounted || it is RunOutcome.TestFromPart && it.kept } }
        if (guarded(a.outcomes) || guarded(b.outcomes)) return@check null
        val x = a.state.vdot ?: 0.0
        val y = b.state.vdot ?: 0.0
        if (y < x - 1e-9) "контрольный на круг дальше в том же темпе — а форма ниже: $y < $x (${a.outcomes} / ${b.outcomes})" else null
    }

    @Test fun runAStoppedTrialNeverLowersTheForm() = check("run_stopped_trial_keeps") { c ->
        if (c.w.type != RunType.TEST) return@check null
        val a = after(c)
        val part = a.outcomes.filterIsInstance<RunOutcome.TestFromPart>().firstOrNull { !it.finished } ?: return@check null
        val before = RunPlan.withBreak(c.state, c.day).vdot ?: return@check null
        val now = a.state.vdot ?: return@check "форма пропала"
        if (now < before - 1e-9) "недобежанный контрольный ($part) опустил форму $before → $now" else null
    }

    @Test fun runAShorterLoopNeverFlatters() = check("run_loop_never_flatters") { c ->
        val lap = c.w.lapMeters ?: return@check null
        val loop = c.r.loopM?.takeIf { it < lap } ?: return@check null
        val measured = after(c)
        val set = after(c, c.r.copy(loopM = null))
        // A result taken for a typo (too far from the form known), not counted, or a stretch below the form known kept:
        // the guards at work, on either loop — a lap over 1.7 times the shorter loop's may turn a trial counted into one kept.
        val guarded = { x: List<RunOutcome> -> x.any { it is RunOutcome.TestDoubtful || it == RunOutcome.TestNotCounted || it is RunOutcome.TestStopped || it is RunOutcome.TestFromPart && it.kept } }
        if (guarded(measured.outcomes) || guarded(set.outcomes)) return@check null
        val a = measured.state
        val b = set.state
        when {
            (a.vdot ?: 0.0) > (b.vdot ?: 0.0) + 1e-9 -> "на круге ${loop.roundToInt()} м вместо $lap форма выше: ${a.vdot} > ${b.vdot}"
            // Reps too fast on the lap length set may be on pace on the loop as run: a step up then is right.
            c.w.type == RunType.GOAL_PACE && a.goalStage > b.goalStage && RunOutcome.TooFastRepeat !in set.outcomes && RunOutcome.TooFast !in set.outcomes ->
                "на круге ${loop.roundToInt()} м вместо $lap ступень темпа цели выше"
            c.w.type == RunType.INTERVALS && a.intervalStage > b.intervalStage -> "на круге ${loop.roundToInt()} м интервалы выше"
            c.w.type == RunType.TEMPO && a.tempoStage > b.tempoStage -> "на круге ${loop.roundToInt()} м темповый выше"
            measured.outcomes.contains(RunOutcome.GoalReached) && !set.outcomes.contains(RunOutcome.GoalReached) -> "на круге короче — «цель достигнута»"
            else -> null
        }
    }

    @Test fun runTheGoalIsReachedOnlyByATimeThatReachesIt() = check("run_goal_reached_honest") { c ->
        val g = c.goal ?: return@check null
        val a = after(c)
        if (RunOutcome.GoalReached !in a.outcomes) return@check null
        val need = Vdot.fromRace(g.meters.toDouble(), g.seconds.toDouble())
        val t = a.outcomes.filterIsInstance<RunOutcome.TestTime>().firstOrNull()
        val shown = when {
            t != null -> Vdot.fromRace(t.meters.toDouble(), t.seconds.toDouble())
            c.r.workDistanceM != null -> Vdot.fromRace(c.r.workDistanceM!!, (c.r.workSec ?: c.w.workSeconds).toDouble())
            else -> return@check "«цель достигнута» без времени забега: ${a.outcomes}"
        }
        if (shown < need - 0.01) "«цель достигнута», а время даёт ${"%.1f".format(shown)} при цели ${"%.1f".format(need)}" else null
    }

    @Test fun runClockAndVoiceMakeSense() = check("run_clock_and_voice") { c ->
        val w = c.w
        val cues = RunTimeline.allCues(w)
        val total = w.totalSeconds
        if (cues.zipWithNext().any { (a, b) -> b.at < a.at }) return@check "сигналы не по порядку"
        cues.firstOrNull { it.at < 0 || it.at > total }?.let { return@check "сигнал $it вне тренировки (${total} с)" }
        // The strides' words and the other things said must not talk over each other.
        val strides = cues.filter { it is Cue.Stride || it is Cue.StridesAhead }
        val said = cues.filter { it is Cue.MinuteLeft || it is Cue.Halfway || it is Cue.ToLine || it is Cue.Countdown || it is Cue.SegmentStart && it.index > 0 }
        for (s in strides) for (o in said) if (abs(s.at - o.at) < 8) return@check "голос накладывается: $s и $o"
        if (strides.isNotEmpty() && w.type !in setOf(RunType.GOAL_PACE, RunType.INTERVALS, RunType.TEST)) return@check "ускорения в разминке ${w.type}"
        // Every second of the plan is in exactly one segment.
        if (w.segments.sumOf { it.seconds } != total) return@check "сумма отрезков ${w.segments.sumOf { it.seconds }} ≠ $total"
        null
    }

    companion object {
        /** Cases counted for the reach of the decisions, and their fixed seed (see [runCasesReachEveryDecisionThatMatters]). */
        const val REACH = 3000
        const val REACH_SEED = 20_261_028L * 7
    }

    @Test fun runDebriefAndExplanationSayNothingBroken() = check("run_texts_sane") { c ->
        val a = after(c)
        val held = a.outcomes.filterIsInstance<RunOutcome.TestFromPart>().firstOrNull()?.let { it.meters to it.seconds }
        val trial = a.outcomes.filterIsInstance<RunOutcome.TestTime>().firstOrNull()?.let { it.meters to it.seconds }
        val items = RunReview.of(
            RunReview.Run(c.w, c.r.laps.orEmpty(), c.r.rpe, c.r.durationSec, c.r.distanceM?.let { c.r.durationSec / (it / 1000) }, c.r.workPace, trial, c.r.completed,
                if (trial != null) RunOutcome.GoalReached in a.outcomes else null, held, c.r.strideSpeeds, c.r.jogSpeed),
            c.goal,
        )
        val why = RunExplain.of(c.w, c.goal)
        val texts = items.flatMap { listOf(it.title, it.text) } + listOf(why.toString(), c.w.title, c.w.note)
        texts.firstOrNull { t -> listOf("NaN", "Infinity", "null", "–:––").any { it in t } }?.let { "в тексте мусор: «$it»" }
    }
}
