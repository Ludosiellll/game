package forma.core.plan

import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import kotlin.math.min

/** How many sessions of each kind a week holds. */
data class Mix(val lifts: Int, val easy: Int, val quality: Int, val long: Int) {
    val runs: Int get() = easy + quality + long
    val total: Int get() = lifts + runs
}

/**
 * Lays the week out: which days get a lift, which get which run.
 *
 * Every possible layout is scored with penalties and the cheapest one wins. Penalties encode
 * concurrent-training practice: no heavy legs the day before intervals or a long run, no two hard runs
 * back to back, at least a day between full-body sessions, easy runs may share a day with lifting
 * (lift first), at least one full rest day, the long run on a weekend if possible.
 */
object Scheduler {
    /**
     * Sessions of each kind for [days] training days.
     *
     * The priority decides how the days are shared; running focus decides what the runs are. Interference between
     * running and lifting grows with how often and how long one runs (Wilson et al. 2012), while on average it costs
     * neither muscle nor maximal strength (Schumann et al. 2022) — so the gym-first plans keep running to 1–2 sessions
     * and the balanced ones get both in full, kept apart by the layout below.
     *
     * [shared]: the gym and a run may share a day, so few days still hold the full week of the priority (what seven days
     * would): three balanced days give three lifts and three runs, the hard runs on upper-body days; a run-first week
     * keeps its one or two lifts. [middleGoal]: training for a race of up to 3 km — two quality sessions (race pace and
     * intervals or tempo) before any long run.
     */
    fun mix(aim: Aim, days: Int, exp: Experience, runLevel: RunLevel, shared: Boolean = false, middleGoal: Boolean = false): Mix {
        val a = aim.normalized()
        val n = days.coerceIn(1, 7)
        val maxLifts = when {
            exp == Experience.NOVICE -> 3
            a.lift == LiftFocus.MUSCLE && exp == Experience.ADVANCED -> 5
            else -> 4
        }
        val (lifts, runs) = when {
            !a.runs -> min(n, maxLifts) to 0
            !a.lifts -> 0 to min(n, 6)
            shared -> mix(a, 7, exp, runLevel, middleGoal = middleGoal).let { full -> min(n, full.lifts) to min(n, full.runs) }
            a.emphasis == Emphasis.GYM -> min(n, maxLifts) to when (n) { 1 -> 0; 2, 3, 4 -> 1; else -> 2 }
            a.emphasis == Emphasis.BALANCED -> intArrayOf(1, 2, 2, 2, 3, 3, 3)[n - 1] to intArrayOf(1, 2, 2, 3, 3, 3, 3)[n - 1]
            else -> intArrayOf(0, 1, 1, 2, 2, 2, 2)[n - 1] to intArrayOf(1, 2, 3, 4, 4, 5, 6)[n - 1]
        }
        // Endurance builds on the long run; speed on intervals and tempo, with a long run once there are three runs.
        val (easy, quality, long) = when (a.run) {
            RunFocus.NONE -> Triple(0, 0, 0)
            RunFocus.HEALTH -> Triple(runs, 0, 0)
            RunFocus.ENDURANCE -> when (runs) {
                0 -> Triple(0, 0, 0)
                1 -> Triple(0, 0, 1)
                2 -> Triple(1, 0, 1)
                3 -> Triple(1, 1, 1)
                4 -> Triple(2, 1, 1)
                5 -> Triple(3, 1, 1)
                else -> Triple(runs - 3, 2, 1)
            }
            RunFocus.SPEED -> when {
                runs == 0 -> Triple(0, 0, 0)
                runs == 1 -> Triple(0, 1, 0)
                runs == 2 -> Triple(1, 1, 0)
                // 1500 m is mostly aerobic but specific: race pace once a week, intervals or tempo once, the rest easy.
                middleGoal && runs <= 4 -> Triple(runs - 2, 2, 0)
                middleGoal -> Triple(runs - 3, 2, 1)
                runs == 3 -> Triple(1, 1, 1)
                else -> Triple(runs - 3, 2, 1)
            }
        }
        // Intervals and long runs need a day without lifting (never heavy legs and a hard run together): when the
        // days run out they become easy runs — the speed plan keeps its intervals, the endurance plan its long run.
        var (e, q, l) = Triple(easy, quality, long)
        // Shared days: hard runs may also go after an upper-body session (see Templates.forWeek).
        val free = (if (n == 7) 6 else n) - lifts + if (shared) Templates.upperDays(lifts) else 0
        while (q + l > maxOf(0, free)) {
            if (a.run == RunFocus.SPEED) { if (l > 0) l-- else q-- } else { if (q > 0) q-- else l-- }
            e++
        }
        var m = Mix(lifts, e, q, l)
        if (runLevel == RunLevel.NONE) {
            // Until 30 minutes of running are possible every run is a run/walk session, three a week at most.
            m = m.copy(easy = m.runs.coerceAtMost(3), quality = 0, long = 0)
        }
        return m
    }

    private data class Slot(val day: Int, val lift: PlannedSession?, val run: PlannedSession?)

    /** Days are ISO numbers 1..7. Template ids are assigned to lift days in order. */
    fun schedule(available: Set<Int>, mix: Mix, templates: List<Template>, aim: Aim): WeekPlan {
        val days = available.filter { it in 1..7 }.sorted()
        require(days.isNotEmpty()) { "no training days" }
        // Leave one day of the week without any session.
        val usable = if (days.size == 7) days.dropLast(1) else days
        val lifts = mix.lifts.coerceAtMost(usable.size)
        val runKinds = buildList {
            repeat(mix.long) { add(SessionKind.RUN_LONG) }
            repeat(mix.quality) { add(SessionKind.RUN_QUALITY) }
            repeat(mix.easy) { add(SessionKind.RUN_EASY) }
        }.take(usable.size)

        var best: List<Slot>? = null
        var bestScore = Double.MAX_VALUE
        val tplOrders = templateOrders(templates.take(lifts))
        for (liftDays in combinations(usable, lifts)) {
            for (runDays in combinations(usable, runKinds.size)) {
                for (perm in distinctPermutations(runKinds)) {
                    for (order in tplOrders) {
                        val slots = (1..7).map { d ->
                            val li = liftDays.indexOf(d)
                            val ri = runDays.indexOf(d)
                            Slot(
                                d,
                                if (li >= 0) PlannedSession(SessionKind.LIFT, order.getOrNull(li)?.id) else null,
                                if (ri >= 0) PlannedSession(perm[ri]) else null,
                            )
                        }
                        val sc = score(slots, templates, aim)
                        if (sc < bestScore - 1e-9) {
                            bestScore = sc
                            best = slots
                        }
                    }
                }
            }
        }
        val chosen = best!!
        val runFirst = aim.normalized().emphasis == Emphasis.RUN
        val plan = chosen.filter { it.lift != null || it.run != null }.map { s ->
            // A hard run goes before an upper-body session: the legs are fresh, and a run before lifting tires only the
            // muscles it used (Sporer & Wenger 2003). Legs go before an easy run.
            val first = runFirst || s.run?.isHardRun == true && s.lift != null && !legs(s.lift, templates)
            val list = if (first) listOfNotNull(s.run, s.lift) else listOfNotNull(s.lift, s.run)
            DayPlan(s.day, list)
        }
        return WeekPlan(plan, notes(chosen, templates, aim.normalized()))
    }

    private fun legs(s: PlannedSession?, templates: List<Template>): Boolean =
        s != null && s.kind == SessionKind.LIFT && (templates.firstOrNull { it.id == s.templateId }?.legs ?: true)

    private fun score(slots: List<Slot>, templates: List<Template>, aim: Aim): Double {
        val emphasis = aim.normalized().emphasis
        var p = 0.0
        fun at(d: Int) = slots[((d - 1) % 7 + 7) % 7]
        for (s in slots) {
            val next = at(s.day + 1)
            val legDay = legs(s.lift, templates)
            // Same-day pairs.
            if (s.lift != null && s.run != null) {
                p += when {
                    s.run.kind == SessionKind.RUN_EASY -> if (legDay) 3.0 else 2.0
                    legDay -> 40.0
                    else -> 8.0
                }
            }
            // No intervals or long run within 24 h after heavy legs (running is impaired up to a day later).
            if (legDay && next.run?.isHardRun == true) p += 8.0
            // A hard run the day before heavy legs blunts the lift; it matters most when the gym comes first.
            if (legs(next.lift, templates) && s.run?.isHardRun == true) {
                p += when (emphasis) {
                    Emphasis.GYM -> 8.0
                    Emphasis.BALANCED -> 6.0
                    Emphasis.RUN -> 4.0
                }
            }
            // Lifting the same muscles on consecutive days.
            if (s.lift != null && next.lift != null) {
                val a = templates.firstOrNull { it.id == s.lift.templateId }?.focus
                val b = templates.firstOrNull { it.id == next.lift.templateId }?.focus
                p += when {
                    a == Focus.UPPER && b == Focus.LOWER || a == Focus.LOWER && b == Focus.UPPER -> 1.0
                    a == Focus.UPPER && b == Focus.UPPER -> 6.0
                    else -> 10.0
                }
            }
            // Two hard runs back to back.
            if (s.run?.isHardRun == true && next.run?.isHardRun == true) p += 10.0
            // Long run prefers the weekend.
            if (s.run?.kind == SessionKind.RUN_LONG && s.day < 6) p += 4.0
        }
        // No three hard days in a row (heavy legs, intervals or the long run).
        val hard = slots.map { legs(it.lift, templates) || it.run?.isHardRun == true }
        for (start in 0 until 7) if (hard[start] && hard[(start + 1) % 7] && hard[(start + 2) % 7]) p += 6.0
        // Streaks of training days without a break.
        val busy = slots.map { it.lift != null || it.run != null }
        if (busy.all { it }) p += 20.0
        for (start in 0 until 7) {
            var len = 0
            while (len < 7 && busy[(start + len) % 7]) len++
            if (len > 3 && !busy[(start + 6) % 7]) p += (len - 3) * 3.0
        }
        // Even spacing of lifting days around the week.
        val liftDays = slots.filter { it.lift != null }.map { it.day }
        if (liftDays.size >= 2) {
            val gaps = liftDays.zipWithNext { a, b -> b - a } + (liftDays.first() + 7 - liftDays.last())
            p += (gaps.max() - gaps.min()) * 0.8
        }
        // Spread hard runs too.
        val hardDays = slots.filter { it.run?.isHardRun == true }.map { it.day }
        if (hardDays.size >= 2) {
            val gaps = hardDays.zipWithNext { a, b -> b - a } + (hardDays.first() + 7 - hardDays.last())
            p += (gaps.max() - gaps.min()) * 0.5
        }
        // Mild preference for lifting earlier in the week when the gym comes first.
        if (emphasis == Emphasis.GYM) p += liftDays.sum() * 0.01
        return p
    }

    private fun notes(slots: List<Slot>, templates: List<Template>, aim: Aim): List<String> {
        val out = mutableListOf<String>()
        val liftDays = slots.filter { it.lift != null }
        if (liftDays.size >= 2) {
            val consecutiveFull = liftDays.any { s ->
                val n = slots[s.day % 7]
                n.lift != null && legs(s.lift, templates) && legs(n.lift, templates)
            }
            if (!consecutiveFull) out += "Силовые не идут подряд на одни и те же мышцы: между ними есть день на восстановление."
        }
        val hard = slots.filter { it.run?.isHardRun == true }
        if (hard.isNotEmpty() && hard.none { h -> legs(slots[(h.day + 5) % 7].lift, templates) }) {
            out += "Накануне интервалов и длинной пробежки нет тренировки ног — бег будет качественнее."
        }
        val long = slots.firstOrNull { it.run?.kind == SessionKind.RUN_LONG }
        if (long != null && long.day >= 6) out += "Длинная пробежка — в выходной, когда больше времени."
        if (aim.seriousRunning && aim.lift != LiftFocus.GENERAL && hard.isNotEmpty() && slots.any { it.lift != null }) {
            out += "Бег почти не мешает росту мышц и силы, если тяжёлые ноги и тяжёлый бег разнесены хотя бы на 6 часов, лучше на сутки."
        }
        val hardWithLift = slots.filter { it.lift != null && it.run?.isHardRun == true }
        if (hardWithLift.isNotEmpty() && hardWithLift.none { legs(it.lift, templates) }) {
            out += "Тяжёлый бег — в дни верха: руки и спина ему не мешают. Ноги — в день лёгкого бега."
        }
        if (slots.any { it.lift != null && it.run != null }) {
            val hardUpper = slots.any { it.lift != null && it.run?.isHardRun == true && !legs(it.lift, templates) }
            val easyAfter = slots.any { it.lift != null && it.run != null && !it.run.isHardRun }
            out += when {
                aim.emphasis == Emphasis.RUN -> "В дни «бег + силовая» сначала бег. Если получится, разнеси их на 6+ часов."
                hardUpper -> "В день верха тяжёлый бег — первым: ноги свежие, а верху бег почти не мешает." +
                    if (easyAfter) " В день ног — сначала зал, потом лёгкий бег." else ""
                else -> "В дни «силовая + лёгкий бег» за один заход — сначала силовая. Если можешь разнести, бег утром, зал через 6+ часов."
            }
        }
        val rest = slots.count { it.lift == null && it.run == null }
        if (rest > 0) out += "Дней полного отдыха: $rest. Прогресс случается во время восстановления."
        return out
    }

    private fun templateOrders(t: List<Template>): List<List<Template>> {
        if (t.size <= 1) return listOf(t)
        // Rotations keep the A → B → C order but let any template open the week.
        return t.indices.map { k -> t.drop(k) + t.take(k) }
    }

    internal fun combinations(items: List<Int>, k: Int): List<List<Int>> {
        if (k == 0) return listOf(emptyList())
        if (k > items.size) return emptyList()
        val out = mutableListOf<List<Int>>()
        fun rec(start: Int, acc: List<Int>) {
            if (acc.size == k) { out += acc; return }
            for (i in start until items.size) rec(i + 1, acc + items[i])
        }
        rec(0, emptyList())
        return out
    }

    internal fun <T> distinctPermutations(items: List<T>): List<List<T>> {
        if (items.isEmpty()) return listOf(emptyList())
        val out = LinkedHashSet<List<T>>()
        fun rec(rest: List<T>, acc: List<T>) {
            if (rest.isEmpty()) { out += acc; return }
            for (i in rest.indices) rec(rest.take(i) + rest.drop(i + 1), acc + rest[i])
        }
        rec(items, emptyList())
        return out.toList()
    }
}
