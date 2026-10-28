package forma.core.fuzz

import forma.core.floorTo
import forma.core.fuzz.Fuzz.chance
import forma.core.fuzz.Fuzz.pick
import forma.core.fuzz.Fuzz.range
import forma.core.model.Catalog
import forma.core.model.Exercise
import forma.core.model.LoadKind
import forma.core.strength.DoneSet
import forma.core.strength.Evaluation
import forma.core.strength.OneRm
import forma.core.strength.Outcome
import forma.core.strength.Phase
import forma.core.strength.Prescription
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test

/**
 * The gym's rules for anybody, on random exercises, rep ranges, states and sets: what was done is never lost, a
 * session done better never gives a worse next one, the weights move by sane steps, the lighter weeks and the goal's
 * days keep the progress, the hints between sets point the right way, and a whole training life of a lifter who can
 * clearly do more keeps moving.
 */
class GymRulesTest {
    private val pool: List<Exercise> = Catalog.all.filter { !it.custom }
    private val weighted: List<Exercise> = pool.filter { it.load != LoadKind.TIMED }

    /** One session of one exercise: the plan as the engine gives it and the sets as done. */
    data class Case(
        val ex: Exercise,
        val spec: SlotSpec,
        val state: SlotState,
        val p: Prescription,
        val done: List<DoneSet>,
        val bw: Double,
        val novice: Boolean,
        val recentBest: Double?,
    ) {
        override fun toString() = buildString {
            appendLine("упражнение ${ex.id} (${ex.load}, шаг ${ex.step}${ex.fineStep?.let { "/$it до ${ex.fineBelow}" } ?: ""}${if (ex.assisted) ", с помощью" else ""}${ex.bar?.let { ", гриф $it" } ?: ""}), вес тела ${"%.1f".format(bw)}")
            appendLine("диапазон ${spec.repMin}–${spec.repMax}, подходов ${spec.sets}, запас ${spec.rir}, роль ${spec.role}, шаг веса ${spec.increment ?: "свой"}, новичок $novice")
            appendLine("было: вес ${state.weight}, повторы ${state.reps}, застои ${state.stalls}, промахи ${state.misses}, сессий ${state.sessions}, линейно ${state.linear}, steady ${state.steady}, срез с ${state.cutFrom}/${state.cutReps}")
            appendLine("план: вес ${p.weight} × ${p.reps}, подходов ${p.sets}, запас ${p.rir}, фаза ${p.phase}, подбор ${p.calibrate}, «+» ${p.top} (запас ${p.topRir}), вводная ${p.intro}")
            appendLine("сделано: " + done.joinToString(", ") { "${it.weight}×${it.reps}${it.rir?.let { r -> " (ещё $r)" } ?: ""}" })
            append("лучшая оценка недель: ${recentBest?.let { "%.1f".format(it) }}")
        }
    }

    private fun onGrid(ex: Exercise, w: Double): Double = floorTo(w, ex.stepAt(w))

    private fun spec(r: Random, ex: Exercise): SlotSpec {
        if (ex.load == LoadKind.TIMED) {
            val lo = r.pick(15, 20, 30, 45, 60)
            return SlotSpec(ex.id, r.nextInt(1, 5), lo, lo + r.pick(10, 20, 30, 60), 0, 60, SlotRole.ACCESSORY)
        }
        val lo = r.pick(1, 3, 4, 5, 6, 8, 10, 12, 15)
        val hi = lo + r.pick(0, 1, 2, 3, 4, 5, 7)
        val role = r.pick(SlotRole.values().toList())
        val inc = if (r.chance(0.15)) ex.step * r.pick(2, 4) else null
        return SlotSpec(ex.id, r.nextInt(1, 6), lo, hi, r.nextInt(0, 4), r.pick(60, 90, 120, 180, 240), role, inc)
    }

    /** A weight the person may have stored: from far too light to heavy, on the exercise's steps. */
    private fun weight(r: Random, ex: Exercise, bw: Double): Double = when {
        ex.assisted -> onGrid(ex, r.range(-Progression.maxAssist(ex, bw), 40.0))
        ex.load == LoadKind.BODYWEIGHT_PLUS -> onGrid(ex, if (r.chance(0.4)) 0.0 else r.range(0.0, 60.0))
        else -> {
            val hint = Progression.startHint(ex, bw, false, 1.0) ?: 20.0
            max(Progression.minWeight(ex, bw), onGrid(ex, hint * r.range(0.3, 3.0)))
        }
    }

    private fun gen(r: Random, phase: Phase? = null, calibrate: Boolean? = null, list: List<Exercise> = weighted): Case {
        val ex = r.pick(list)
        val bw = r.range(45.0, 130.0)
        val spec = spec(r, ex)
        val novice = r.chance(0.3)
        val fresh = calibrate ?: r.chance(0.1)
        val w = if (fresh || ex.load == LoadKind.TIMED) null else weight(r, ex, bw)
        val reps = if (ex.load == LoadKind.TIMED) spec.repMin + 5 * r.nextInt(0, (spec.repMax - spec.repMin) / 5 + 1)
        else r.nextInt(max(1, spec.repMin - 3), spec.repMax + 5)
        val state = SlotState(
            w, reps, stalls = r.nextInt(0, 4), misses = r.nextInt(0, 3), sessions = if (fresh) 0 else r.nextInt(1, 40),
            linear = r.nextBoolean(), steady = r.nextInt(0, 4),
            cutFrom = if (w != null && r.chance(0.2)) w + ex.step * r.nextInt(1, 6) else null,
            cutReps = if (r.chance(0.2)) r.nextInt(1, 10) else null,
        )
        val e1rm = if (w != null && r.chance(0.7)) OneRm.estimate(Progression.effectiveLoad(ex, w, bw), reps, spec.rir) * r.range(0.8, 1.3) else null
        val ph = phase ?: r.pick(Phase.NORMAL, Phase.NORMAL, Phase.NORMAL, Phase.NORMAL, Phase.NORMAL, Phase.DELOAD, Phase.TAPER, Phase.TEST)
        val p = Progression.prescribe(
            ex, spec, state, ph, rirOffset = if (r.chance(0.15)) 1 else 0, e1rm = e1rm, bodyWeight = bw,
            hint = Progression.startHint(ex, bw, false, 1.0), daysSinceLast = if (r.chance(0.15)) r.pick(20L, 35L, 70L) else r.pick(2L, 3L, 7L),
            topRir = r.pick(1, 1, 2), novice = novice,
            ownBest = if (ex.assisted && r.chance(0.3)) OneRm.estimate(Progression.effectiveLoad(ex, 0.0, bw), r.nextInt(1, 15), 0) else null,
        )
        val done = sets(r, ex, p, bw)
        val best = if (r.chance(0.5)) e1rm?.let { it * r.range(0.9, 1.2) } else null
        return Case(ex, spec, state, p, done, bw, novice, best)
    }

    /** The sets as people do them: mostly the plan, some heavier after a hint, some lighter, short, long, rated or not. */
    private fun sets(r: Random, ex: Exercise, p: Prescription, bw: Double): List<DoneSet> {
        val n = when {
            r.chance(0.75) -> p.sets
            r.chance(0.5) -> max(1, p.sets - r.nextInt(1, 3))
            else -> p.sets + r.nextInt(1, 3)
        }
        val base = p.weight ?: p.startHint ?: if (ex.load == LoadKind.EXTERNAL) (ex.bar ?: ex.step * 4) else 0.0
        return List(n) {
            val step = ex.stepAt(base)
            val wt = when {
                ex.load == LoadKind.TIMED -> 0.0
                r.chance(0.7) -> base
                r.chance(0.5) -> base + step * r.nextInt(1, 4)
                r.chance(0.7) -> max(Progression.minWeight(ex, bw), base - step * r.nextInt(1, 4))
                else -> base + step * r.nextInt(-6, 7)
            }
            val reps = when {
                r.chance(0.05) -> 0
                r.chance(0.6) -> p.reps
                else -> max(0, p.reps + r.nextInt(-5, 7))
            }
            DoneSet(wt, reps, if (r.chance(0.45)) null else r.nextInt(0, 6))
        }
    }

    private fun shrink(c: Case): Sequence<Case> = sequence {
        if (c.done.size > 1) for (d in Fuzz.withoutOne(c.done)) yield(c.copy(done = d))
        c.done.forEachIndexed { i, s ->
            if (s.rir != null) yield(c.copy(done = c.done.toMutableList().also { it[i] = s.copy(rir = null) }))
            if (c.p.weight != null && s.weight != c.p.weight) yield(c.copy(done = c.done.toMutableList().also { it[i] = s.copy(weight = c.p.weight) }))
            if (s.reps != c.p.reps) yield(c.copy(done = c.done.toMutableList().also { it[i] = s.copy(reps = c.p.reps) }))
        }
        if (c.recentBest != null) yield(c.copy(recentBest = null))
        if (c.novice) yield(c.copy(novice = false))
    }

    private fun eval(c: Case, done: List<DoneSet> = c.done): Evaluation =
        Progression.evaluate(c.ex, c.spec, c.state, c.p, done, c.bw, c.novice, c.recentBest)

    /** The load the next session asks for, in kg moved (the body included), and its reps: what "harder" means. */
    private fun load(c: Case, s: SlotState): Double = Progression.effectiveLoad(c.ex, s.weight ?: 0.0, c.bw)

    /**
     * What the plan [s] asks of the muscles: the one-rep max its reps at its load with the planned reserve take. 55 × 3
     * asks more than 57.5 × 1; 28 × 10 less than 28 × 12.
     */
    private fun demand(c: Case, s: SlotState): Double =
        load(c, s) / OneRm.fractionForRtf((s.reps + c.spec.rir).toDouble())

    /**
     * [a] asks at least as much as [b] by one of the two measures: heavier (or as heavy with as many reps), or more of
     * the muscles ([demand]). Only a plan lighter by both is "less": a heavier weight with fewer reps is progress whatever
     * the model says of high reps, and 55 × 3 after 57.5 × 1 is no step back.
     */
    private fun atLeast(c: Case, a: SlotState, b: SlotState): Boolean {
        val la = load(c, a)
        val lb = load(c, b)
        val heavier = la > lb + 1e-6 || abs(la - lb) <= 1e-6 && a.reps >= b.reps
        return heavier || demand(c, a) >= demand(c, b) * (1 - 1e-9) - 1e-9
    }

    private fun normal(c: Case) = c.p.phase == Phase.NORMAL && !c.p.calibrate && c.p.weight != null && c.ex.load != LoadKind.TIMED

    private fun check(name: String, make: (Random) -> Case = { r -> gen(r) }, rule: (Case) -> String?) =
        Fuzz.check(name, make, ::shrink, { it.toString() }, rule = rule)

    // ---------- one session ----------

    @Test fun gymNextIsSane() = check("gym_next_is_sane", { gen(it, list = pool) }) { c ->
        val e = eval(c)
        val s = e.state
        when {
            s.reps < 1 -> "повторов в плане ${s.reps}"
            c.ex.load == LoadKind.TIMED -> null
            s.weight == null -> if (c.done.any { it.reps > 0 }) "после сделанных подходов вес не задан" else null
            s.weight!!.isNaN() || s.weight!!.isInfinite() -> "вес ${s.weight}"
            s.weight!! < Progression.minWeight(c.ex, c.bw) - 1e-6 -> "вес ${s.weight} легче самого лёгкого ${Progression.minWeight(c.ex, c.bw)}"
            s.reps > c.spec.repMax + 4 -> "повторов ${s.reps} при диапазоне до ${c.spec.repMax}"
            else -> when (val o = e.outcome) {
                is Outcome.AddWeight -> if (abs(o.to - s.weight!!) > 1e-6 || o.reps != s.reps) "исход $o, а в плане ${s.weight}×${s.reps}" else null
                is Outcome.Jump -> if (abs(o.to - s.weight!!) > 1e-6) "исход $o, а в плане ${s.weight}" else null
                is Outcome.Reduce -> if (abs(o.to - s.weight!!) > 1e-6) "исход $o, а в плане ${s.weight}" else null
                is Outcome.Reset -> if (abs(o.to - s.weight!!) > 1e-6 || o.reps != s.reps) "исход $o, а в плане ${s.weight}×${s.reps}" else null
                is Outcome.AddReps -> if (o.to != s.reps) "исход $o, а в плане ×${s.reps}" else null
                is Outcome.LowerReps -> if (o.to != s.reps) "исход $o, а в плане ×${s.reps}" else null
                is Outcome.Calibrated -> if (abs(o.weight - s.weight!!) > 1e-6 || o.reps != s.reps) "исход $o, а в плане ${s.weight}×${s.reps}" else null
                else -> null
            }
        }
    }

    @Test fun gymWhatWasDoneIsNeverLost() = check("gym_done_never_lost", { gen(it, Phase.NORMAL, false) }) { c ->
        if (!normal(c)) return@check null
        val planned = c.done.filter { it.reps > 0 }.take(c.p.sets)
        val pw = c.p.weight!!
        if (planned.size < c.p.sets || planned.any { it.weight < pw - 1e-9 || it.reps < c.p.reps }) return@check null
        val reached = max(pw, floorTo(planned.minOf { it.weight }, c.ex.stepAt(pw)))
        val s = eval(c).state
        val w = s.weight ?: return@check "вес пропал"
        when {
            w < reached - 1e-6 -> "все подходы ${planned.joinToString { "${it.weight}×${it.reps}" }} при плане $pw×${c.p.reps}, а дальше $w×${s.reps}"
            abs(w - reached) <= 1e-6 && s.reps < c.p.reps -> "все подходы по плану или тяжелее, а дальше тот же $w и меньше повторов: ${s.reps} < ${c.p.reps}"
            else -> null
        }
    }

    @Test fun gymStepsAreSane() = check("gym_steps_sane", { gen(it, Phase.NORMAL, false) }) { c ->
        if (!normal(c)) return@check null
        val e = eval(c)
        val s = e.state
        val from = Progression.effectiveLoad(c.ex, c.p.weight!!, c.bw)
        val to = load(c, s)
        val step = max(c.ex.stepAt(c.p.weight!!), c.spec.increment ?: 0.0)
        val credited = c.done.filter { it.reps > 0 }.take(c.p.sets).maxOfOrNull { it.weight }?.let { Progression.effectiveLoad(c.ex, it, c.bw) } ?: from
        val top = max(from, credited)
        when {
            to > top + max(abs(top - Progression.effectiveLoad(c.ex, 0.0, c.bw)) * 0.15 + 1e-6, 2 * step + 1e-6) + 1e-6 && c.ex.load == LoadKind.EXTERNAL ->
                "за одну тренировку вес вырос с ${c.p.weight} до ${s.weight} (${e.outcome})"
            to < from * 0.9 - step - 1e-6 -> "за одну тренировку вес упал с ${c.p.weight} до ${s.weight}, больше чем на 10 % (${e.outcome})"
            else -> null
        }
    }

    /** A copy of the session with one set done one rep more (same weight, same rating). */
    private fun moreReps(c: Case, r: Random): List<DoneSet>? {
        val k = c.done.indices.filter { c.done[it].reps in 1..40 }.takeIf { it.isNotEmpty() }?.let { r.pick(it) } ?: return null
        return c.done.toMutableList().also { it[k] = it[k].copy(reps = it[k].reps + 1) }
    }

    @Test fun gymMoreRepsNeverAskLess() = Fuzz.check(
        "gym_more_reps_never_less", { r -> gen(r, Phase.NORMAL, false).let { c -> c to moreReps(c, r) } },
        { (c, better) -> shrink(c).map { s -> s to better?.let { b -> if (b.size == s.done.size) b else null } } },
        { (c, better) -> "$c\nлучше: " + (better?.joinToString(", ") { "${it.weight}×${it.reps}${it.rir?.let { r -> " (ещё $r)" } ?: ""}" } ?: "—") },
    ) { (c, better) ->
        if (!normal(c) || better == null) return@check null
        val a = eval(c).state
        val b = eval(c, better).state
        if (atLeast(c, b, a)) null else "на повтор больше — а план легче: было бы ${a.weight}×${a.reps}, стало ${b.weight}×${b.reps}"
    }

    @Test fun gymHeavierNeverAsksLess() = Fuzz.check(
        "gym_heavier_never_less", { r -> gen(r, Phase.NORMAL, false) }, ::shrink, { it.toString() },
    ) { c ->
        if (!normal(c) || c.done.none { it.reps > 0 }) return@check null
        // Sets all lighter than the plan say nothing of the plan's weight (held as it was): no comparison to make.
        val tol = c.ex.stepAt(c.p.weight!!) / 2
        if (c.done.filter { it.reps > 0 }.take(c.p.sets).any { it.weight < c.p.weight!! - tol }) return@check null
        val heavier = c.done.map { it.copy(weight = it.weight + c.ex.stepAt(it.weight)) }
        val a = eval(c).state
        val b = eval(c, heavier).state
        if (atLeast(c, b, a)) null else "те же подходы на шаг тяжелее — а план легче: было бы ${a.weight}×${a.reps}, стало ${b.weight}×${b.reps}"
    }

    @Test fun gymMoreReserveNeverAsksLess() = Fuzz.check(
        "gym_more_reserve_never_less", { r -> gen(r, Phase.NORMAL, false) }, ::shrink, { it.toString() },
    ) { c ->
        if (!normal(c)) return@check null
        val k = c.done.indexOfFirst { it.rir != null && it.reps > 0 }.takeIf { it >= 0 } ?: return@check null
        val more = c.done.toMutableList().also { it[k] = it[k].copy(rir = it[k].rir!! + 1) }
        val a = eval(c).state
        val b = eval(c, more).state
        if (atLeast(c, b, a)) null else "подход $k с запасом на 1 больше — а план легче: было бы ${a.weight}×${a.reps}, стало ${b.weight}×${b.reps}"
    }

    @Test fun gymLighterWeeksAndGoalDaysKeepProgress() = check("gym_phases_keep", { gen(it, it.pick(Phase.DELOAD, Phase.TAPER, Phase.TEST), false) }) { c ->
        if (c.p.calibrate || c.done.none { it.reps > 0 }) return@check null
        val e = eval(c)
        val s = e.state
        if (s.weight != c.state.weight || s.reps != c.state.reps || s.stalls != c.state.stalls || s.misses != c.state.misses) {
            "${c.p.phase}: прогресс сдвинулся: ${c.state.weight}×${c.state.reps} → ${s.weight}×${s.reps} (${e.outcome})"
        } else null
    }

    @Test fun gymHintsPointTheRightWay() = check("gym_hints_right_way", { gen(it, Phase.NORMAL, false) }) { c ->
        if (!normal(c)) return@check null
        c.done.forEachIndexed { i, last ->
            if (last.reps <= 0) return@forEachIndexed
            val adj = Progression.adjustAfterSet(c.ex, c.p, last, c.bw, i) ?: return@forEachIndexed
            val rir = Progression.assumedRir(last, i, c.p)
            val actual = if (last.reps < c.p.reps) (last.rir ?: 0) - (c.p.reps - last.reps) else (last.rir ?: c.p.rir) + (last.reps - c.p.reps)
            val planned = Progression.effectiveLoad(c.ex, c.p.weight!!, c.bw)
            val lastLoad = Progression.effectiveLoad(c.ex, last.weight, c.bw)
            val anchor = max(planned, lastLoad)
            val l = Progression.effectiveLoad(c.ex, adj.weight, c.bw)
            val step = c.ex.stepAt(adj.weight)
            val why = when {
                adj.heavier && actual < c.p.rir + 2 -> "подсказка тяжелее после подхода ${last.weight}×${last.reps} (запас $rir, по плану ${c.p.rir})"
                !adj.heavier && actual > c.p.rir - 2 -> "подсказка легче после подхода ${last.weight}×${last.reps} (запас $rir, по плану ${c.p.rir})"
                l > max(anchor * 0.9, min(anchor * 1.1, planned * 1.2)) + step + 1e-6 -> "подсказка ${adj.weight}: больше +10 % от ${c.p.weight}"
                l < anchor * 0.9 - step - 1e-6 -> "подсказка ${adj.weight}: меньше −10 % от ${c.p.weight}"
                adj.weight < Progression.minWeight(c.ex, c.bw) - 1e-6 -> "подсказка ${adj.weight} легче самого лёгкого"
                else -> null
            }
            if (why != null) return@check why
        }
        null
    }

    @Test fun gymPlanForTheDayIsSane() = check("gym_prescribe_sane", { gen(it, Phase.NORMAL) }) { c ->
        if (c.ex.load == LoadKind.TIMED || c.state.weight == null) return@check null
        val normal = Progression.prescribe(c.ex, c.spec, c.state, Phase.NORMAL, bodyWeight = c.bw)
        val deload = Progression.prescribe(c.ex, c.spec, c.state, Phase.DELOAD, bodyWeight = c.bw)
        val back = Progression.prescribe(c.ex, c.spec, c.state, Phase.NORMAL, bodyWeight = c.bw, daysSinceLast = 60)
        val nw = normal.weight!!
        when {
            nw < Progression.minWeight(c.ex, c.bw) - 1e-6 -> "вес плана $nw легче самого лёгкого"
            deload.weight!! > nw + 1e-6 -> "лёгкая неделя тяжелее обычной: ${deload.weight} > $nw"
            deload.sets > normal.sets -> "в лёгкую неделю подходов больше: ${deload.sets} > ${normal.sets}"
            back.weight!! > nw + 1e-6 -> "после двух месяцев перерыва тяжелее: ${back.weight} > $nw"
            Progression.effectiveLoad(c.ex, back.weight!!, c.bw) < Progression.effectiveLoad(c.ex, nw, c.bw) * 0.85 - c.ex.stepAt(nw) - 1e-6 ->
                "после перерыва легче больше чем на 15 %: ${back.weight} при $nw"
            normal.reps < 1 || deload.reps < 1 || back.reps < 1 -> "повторов меньше одного"
            else -> null
        }
    }

    @Test fun gymNoHelpForWhoDoesFiveAlone() = check("gym_no_help_needed", { r -> gen(r, Phase.NORMAL, false, weighted.filter { it.assisted }) }) { c ->
        // Whoever the latest session without help shows doing five and more with the body alone gets no help.
        val own = OneRm.estimate(Progression.effectiveLoad(c.ex, 0.0, c.bw), 6, 0)
        val p = Progression.prescribe(c.ex, c.spec, c.state, Phase.NORMAL, bodyWeight = c.bw, ownBest = own, e1rm = own)
        if ((p.weight ?: 0.0) < -1e-9) "делает 6 сам — а в плане помощь ${p.weight}" else null
    }

    // ---------- a training life ----------

    /** A lifter whose true strength the engine does not know: [oneRm] (kg moved), growing [gain] a session. */
    data class Life(val ex: Exercise, val spec: SlotSpec, val bw: Double, val oneRm: Double, val gain: Double, val honest: Boolean, val novice: Boolean, val seed: Int) {
        override fun toString() =
            "${ex.id} (${ex.load}, шаг ${ex.step}), вес тела ${"%.1f".format(bw)}, диапазон ${spec.repMin}–${spec.repMax} × ${spec.sets}, запас ${spec.rir}, " +
                "роль ${spec.role}, истинный максимум ${"%.1f".format(oneRm)} кг (+${"%.1f".format(gain * 100)} % за тренировку), честно оценивает: $honest, новичок $novice"
    }

    private fun life(r: Random): Life {
        val ex = r.pick(weighted.filter { it.load == LoadKind.EXTERNAL || it.load == LoadKind.BODYWEIGHT_PLUS })
        val bw = r.range(50.0, 110.0)
        val spec = spec(r, ex).let { it.copy(repMin = max(3, it.repMin), repMax = max(max(3, it.repMin), it.repMax)) }
        val body = Progression.effectiveLoad(ex, 0.0, bw)
        val start = Progression.startHint(ex, bw, false, 1.0) ?: 20.0
        val oneRm = if (ex.load == LoadKind.BODYWEIGHT_PLUS) body * r.range(1.05, 1.8) else max(start / 0.6, (ex.bar ?: 0.0) * 1.5) * r.range(0.7, 2.5)
        return Life(ex, spec, bw, oneRm, r.pick(0.0, 0.003, 0.01), r.chance(0.7), r.chance(0.3), r.nextInt())
    }

    /**
     * A lifter who can clearly do more keeps moving: over any eight sessions in a row in which every planned set had
     * three or more reps to spare at the planned weight, the plan asks for more at least once. And the plan never drifts
     * far below what the lifter can do (75 % of the load his strength supports for the target, after the first sessions).
     */
    @Test fun gymLifeKeepsMoving() = Fuzz.check("gym_life_moves", ::life, show = { it.toString() }, cases = 120) { l ->
        val rng = Random(l.seed)
        var state = SlotState.fresh(l.spec)
        var e1rm: Double? = null
        var true1 = l.oneRm
        var easyRun = 0
        var last: Pair<Double, Int>? = null
        for (k in 0 until 40) {
            val p = Progression.prescribe(l.ex, l.spec, state, e1rm = e1rm, bodyWeight = l.bw, hint = Progression.startHint(l.ex, l.bw, false, 1.0), novice = l.novice)
            var weight = p.weight ?: p.startHint ?: if (l.ex.load == LoadKind.EXTERNAL) (l.ex.bar ?: l.ex.step * 4) else 0.0
            val done = mutableListOf<DoneSet>()
            var spare = Int.MAX_VALUE
            for (i in 0 until p.sets) {
                val rtf = OneRm.rtfAt(true1, Progression.effectiveLoad(l.ex, weight, l.bw)).toInt()
                val goal = when {
                    p.calibrate -> rtf - p.rir
                    p.top && i == p.sets - 1 -> rtf - p.topRir
                    else -> p.reps
                }
                val reps = min(rtf, max(1, goal))
                val rir = if (l.honest) (rtf - reps).coerceIn(0, 5) else if (rng.chance(0.5)) null else (rtf - reps + rng.nextInt(-1, 3)).coerceIn(0, 5)
                done += DoneSet(weight, reps, rir)
                if (!p.calibrate && abs(weight - (p.weight ?: weight)) < 1e-9) spare = min(spare, rtf - p.reps - p.rir)
                Progression.adjustAfterSet(l.ex, p, done.last(), l.bw, i)?.let { weight = it.weight }
            }
            val ev = Progression.evaluate(l.ex, l.spec, state, p, done, l.bw, l.novice, e1rm)
            ev.e1rm?.let { e1rm = Progression.smoothE1rm(e1rm, it, l.novice) }
            val next = ev.state
            val now = l.load(next) to next.reps
            // Clearly able and nothing moved: counted; any step up resets the count.
            val moved = last?.let { (lw, lr) -> now.first > lw + 1e-6 || abs(now.first - lw) < 1e-6 && now.second > lr } ?: true
            easyRun = if (!p.calibrate && spare >= 3 && !moved) easyRun + 1 else 0
            if (easyRun >= 8) return@check "8 тренировок подряд с запасом 3+ повтора — а план стоит на ${next.weight}×${next.reps} (тренировка ${k + 1})"
            if (k >= 6 && next.weight != null) {
                val ideal = OneRm.loadFor(true1, next.reps, l.spec.rir)
                val step = l.ex.stepAt(next.weight!!)
                val asked = Progression.effectiveLoad(l.ex, next.weight!!, l.bw)
                if (asked < ideal * 0.75 - step && asked > Progression.effectiveLoad(l.ex, Progression.minWeight(l.ex, l.bw), l.bw) + 1e-6) {
                    return@check "план ${next.weight}×${next.reps} — ${(asked / ideal * 100).roundToInt()} % от того, что он может на ${next.reps} (тренировка ${k + 1})"
                }
            }
            last = now
            state = next
            true1 *= 1 + l.gain
        }
        null
    }

    private fun Life.load(s: SlotState): Double = Progression.effectiveLoad(ex, s.weight ?: 0.0, bw)
}
