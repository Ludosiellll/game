package forma.core.run

import forma.core.model.RunGoal
import forma.core.text.Ru
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * After a run: how it went against what it was for — the laps on target and how even they were, the easy pace kept or
 * not, the trial against the goal, the effort against the plan — what it trains in the body and how long before the next
 * hard day. Only what was measured is judged: no laps and no pace, nothing said about them.
 */
object RunReview {
    /** [tone]: 1 — as it should be, 0 — to know, −1 — to change next time. */
    data class Item(val title: String, val text: String, val tone: Int)

    data class Run(
        val workout: RunWorkout,
        val laps: List<Lap> = emptyList(),
        /** The effort, CR-10; null when not given. */
        val rpe: Int? = null,
        val durationSec: Int = 0,
        /** Average pace of the whole run, s/km. */
        val avgPace: Double? = null,
        /** Average pace of the reps, s/km (the laps' or GPS's, or typed in by hand). */
        val workPace: Double? = null,
        /** A time trial: metres and seconds. */
        val trial: Pair<Int, Int>? = null,
        /** Share of the plan done, 0..1, when known. */
        val completed: Double? = null,
        /** A trial: whether the plan counted the goal reached (`RunOutcome.GoalReached`); null when not known. */
        val goalReached: Boolean? = null,
        /** A trial counted on the stretch held from its start (`RunOutcome.TestFromPart`): metres and seconds. */
        val held: Pair<Int, Int>? = null,
        /** The warm-up's strides: each one's speed by GPS, m/s (null where not measured); the jog before them. */
        val strides: List<Double?>? = null,
        val jog: Double? = null,
        /** The runner's own effort scale the plan read this run on (`RunOutcome.OwnScale`), 0 when none was. */
        val ownScale: Int = 0,
    )

    /**
     * [gym] is no longer read: the rest after a hard run names no gym day (23 November: «зал на верх тела» for 48 hours
     * stood over a plan with squats the next day). Kept for the callers that still pass it.
     */
    fun of(r: Run, goal: RunGoal? = null, @Suppress("UNUSED_PARAMETER") gym: Boolean = true): List<Item> {
        val w = r.workout
        val out = mutableListOf<Item>()
        how(r, goal)?.let { out += it }
        effort(r)?.let { out += it }
        // After the run itself: the strides are a side of the warm-up, not what the session was for.
        strides(r)?.let { out += it }
        val why = RunExplain.of(w, goal)
        if (w.type == RunType.FREE) {
            out += Item("Что это даёт телу", freeBody(r.rpe), 0)
            // RunPlan.after: a free run counts as running (no break, the load), but moves no ladder and no minutes.
            out += Item("План", "Свободный бег план не двигает, но засчитан как бег.", 0)
        } else {
            out += Item("Что это даёт телу", why.body, 0)
        }
        out += recovery(r, why.recoveryHours)
        return out
    }

    /** A free run after the fact: as hard as it felt. */
    private fun freeBody(rpe: Int?): String = when {
        rpe == null -> "Спокойный бег работает как лёгкий: строит выносливость. Быстрый — как тяжёлая тренировка."
        rpe >= 8 -> "По тяжести — как тяжёлая тренировка. Тренирует скорость и выносливость, но и утомляет так же."
        rpe >= 6 -> "Бег средней тяжести. Выносливость тренирует сильнее лёгкого, но и утомляет больше."
        else -> "Спокойный бег — как лёгкий. Строит выносливость и почти не утомляет."
    }

    private fun how(r: Run, goal: RunGoal?): Item? {
        val w = r.workout
        return when (w.type) {
            RunType.GOAL_PACE, RunType.INTERVALS, RunType.TEMPO -> reps(r, goal)
            RunType.EASY, RunType.LONG -> easy(r)
            RunType.TEST -> trial(r, goal)
            RunType.FREE -> free(r)
            RunType.RUN_WALK -> r.completed?.let {
                if (it >= 0.95) Item("Как прошла пробежка", "Все беговые отрезки сделаны.", 1)
                else Item("Как прошла пробежка", "Сделано ${(it * 100).roundToInt()} % тренировки. Дальше пойдёшь, когда пробежишь её целиком.", 0)
            }
        }
    }

    /** Reps: the laps pressed on target, how even the reps were; on the road, the reps' pace against the target. */
    private fun reps(r: Run, goal: RunGoal?): Item? {
        val w = r.workout
        val lapM = w.lapMeters
        val judged = r.laps.filter { it.rep != null && !it.rough && (it.fast != null || it.slow != null) }
        if (judged.size >= 2) {
            val on = judged.count { it.verdict == 0 }
            val fast = judged.count { it.verdict == -1 }
            val slow = judged.count { it.verdict == 1 }
            val parts = mutableListOf("Круги отрезков: $on из ${judged.size} в темпе" + listOfNotNull(
                fast.takeIf { it > 0 }?.let { "быстрее — $it" }, slow.takeIf { it > 0 }?.let { "медленнее — $it" },
            ).joinToString(", ").let { if (it.isEmpty()) "." else ", $it." })
            val target = w.segments.firstOrNull { it.kind == SegmentKind.WORK && it.lapSec != null }
            val repLaps = r.laps.filter { it.rep != null }
            val avg = repLaps.sumOf { it.seconds } / repLaps.sumOf { it.part }
            val range = target?.pace?.let { p -> lapM?.let { Track.lapSec(p.fast.toDouble(), it) to Track.lapSec(p.slow.toDouble(), it) } }
            // Under a minute to a tenth ("49,6 с" against "50–51 с"), a minute and up as the target is written ("1:03").
            if (range != null) parts += "Средний круг ${if (avg < 59.95) "${Ru.one(avg)} с" else Ru.lap(avg)}, цель " +
                "${Ru.lapRange(range.first, range.second)}."
            var tone = if (on * 5 >= judged.size * 4) 1 else 0
            // The reps from first to last: a fall-off means the start was too quick.
            val byRep = repLaps.groupBy { it.rep!! }.toSortedMap().values.map { ls -> ls.sumOf { it.seconds } / ls.sumOf { it.part } }
            if (byRep.size >= 2) {
                val drift = byRep.last() - byRep.first()
                parts += when {
                    drift > 1.0 -> "Последний отрезок медленнее первого на ${Ru.one(drift)} с на круге. В следующий раз " +
                        "держи время круга с самого начала."
                    drift < -1.0 -> "Последний отрезок быстрее первого на ${Ru.one(-drift)} с на круге. Силы оставались, " +
                        "можно бежать ровнее с самого начала."
                    else -> "Отрезки ровные, разница меньше секунды на круге."
                }
                if (drift > 1.0) tone = minOf(tone, 0)
            }
            when {
                fast * 10 >= judged.size * 3 -> {
                    parts += "Много кругов быстрее нужного."
                    tone = -1
                }
                slow * 10 >= judged.size * 3 -> {
                    parts += "Много кругов медленнее нужного."
                    tone = -1
                }
            }
            return Item("Как прошла пробежка", parts.joinToString(" "), tone)
        }
        // The road, or reps entered by hand: the reps' pace against the target.
        val pace = r.workPace ?: return null
        val p = w.segments.firstOrNull { it.kind == SegmentKind.WORK }?.pace ?: return null
        val words = "Темп отрезков ${Ru.pace(pace)}/км, цель ${Ru.pace(p.fast.toDouble())}–${Ru.pace(p.slow.toDouble())}"
        return when {
            pace < p.fast - 3 -> Item("Как прошла пробежка", "$words. Быстрее нужного.", -1)
            pace > p.slow + 3 -> Item("Как прошла пробежка", "$words. Медленнее нужного.", -1)
            else -> Item("Как прошла пробежка", "$words. В темпе.", 1)
        }
    }

    /** An easy or long run: kept slower than its limit or not. */
    private fun easy(r: Run): Item? {
        val w = r.workout
        val limit = w.segments.firstOrNull { it.kind == SegmentKind.RUN }?.pace?.fast?.toDouble()
        val lapM = w.lapMeters
        val free = r.laps.filter { it.rep == null }
        // On a stadium the laps say it best; else the GPS pace of the run — then said a km, as measured.
        val byLaps = lapM != null && free.size >= 2
        val pace = if (byLaps) free.sumOf { it.seconds } / free.sumOf { it.part } / lapM!! * 1000 else r.avgPace
        pace ?: return null
        val said = if (byLaps) "Средний круг ${Ru.lap(Track.lapSec(pace, lapM!!))}" else "Средний темп ${Ru.pace(pace)}/км"
        if (limit == null) return Item("Как прошла пробежка", "$said.", 0)
        val limitWords = if (byLaps) "не быстрее ${Ru.lap(Track.lapSec(limit, lapM!!))} на круг" else "не быстрее ${Ru.pace(limit)}/км"
        return if (pace < limit - 2) {
            Item("Как прошла пробежка", "$said, быстрее лёгкого ($limitWords). В следующий раз сбавь.", -1)
        } else {
            Item("Как прошла пробежка", "$said. Лёгкий темп, так и надо.", 1)
        }
    }

    /** A time trial: its time, against the goal, and how even it was. */
    private fun trial(r: Run, goal: RunGoal?): Item? {
        val held = r.held
        val (m, sec) = r.trial ?: return held?.let { (hm, hs) ->
            // Not run in pace to the end: what was held from the start is the measure.
            Item(
                "Как прошла пробежка",
                "Контрольный не добежан в темпе до конца: $hm м за ${Ru.clock(hs)} от старта. Следующий начни чуть спокойнее.",
                0,
            )
        }
        val parts = mutableListOf("$m м за ${Ru.clock(sec)}.")
        var tone = 0
        // The goal's distance in whole laps — up to 15 % off on a loop shorter than the lap length set (read on the loop GPS
        // measured, 6,5 laps of 205 m are 1332 m).
        if (goal != null && abs(m - goal.meters) <= goal.meters * 0.15) {
            // Over the goal distance at the same pace: a few metres short of 1500 are a second or so; further off, the time
            // the fitness gives over it. Reached is what the plan counted (fitness against the goal's, unrounded); the
            // seconds are only to say how far.
            val est = if (abs(m - goal.meters) <= goal.meters * 0.03) sec * goal.meters.toDouble() / m
            else Vdot.predictSeconds(Vdot.fromRace(m.toDouble(), sec.toDouble()), goal.meters.toDouble())
            val reached = r.goalReached ?: (est <= goal.seconds)
            val gap = kotlin.math.ceil(est - goal.seconds).toInt().coerceAtLeast(1)
            parts += if (reached) "Это цель: ${goal.meters} м за ${Ru.clock(goal.seconds)} или быстрее.".also { tone = 1 }
            else "До цели ${Ru.clock(goal.seconds)} — ещё ${if (gap < 60) "$gap с" else Ru.clock(gap)}."
        }
        // Faded: the stretch held from the start says more of the form than the whole time (3 above it at most).
        if (held != null) parts += "Первые ${held.first} м темп держался (${Ru.clock(held.second)}), потом упал, и темпы посчитаны с учётом этого."
        val laps = r.laps.filter { it.rep != null }
        if (laps.size >= 4) {
            val half = laps.size / 2
            val a = laps.take(half).let { ls -> ls.sumOf { it.seconds } / ls.sumOf { it.part } }
            val b = laps.drop(half).let { ls -> ls.sumOf { it.seconds } / ls.sumOf { it.part } }
            parts += when {
                b - a > 2.0 -> "Вторая половина медленнее первой на ${Ru.one(b - a)} с на круге. В следующий раз начни спокойнее."
                a - b > 0.5 -> "Вторая половина быстрее первой, так и надо."
                else -> "Круги ровные, так и надо."
            }
        }
        return Item("Как прошла пробежка", parts.joinToString(" "), tone)
    }

    /**
     * The warm-up's strides: how many, their pace from the quickest to the slowest; and the ones no faster than the jog
     * (by less than a fifth — that was a jog), with the jog's pace. They wake the legs for fast running; they do not
     * measure the form (15 seconds are speed, not endurance), so nothing in the plan moves by them. Kept even when all is
     * well: a stride far too fast shows in its pace (26 October, Denis asked to be told of one).
     */
    private fun strides(r: Run): Item? {
        val s = r.strides ?: return null
        val got = s.filterNotNull()
        if (got.isEmpty()) return Item("Ускорения", "Сделано ${s.size} из ${RunTimeline.STRIDES}. Темп не измерен: GPS их не поймал.", 0)
        val jog = r.jog
        val slow = if (jog != null) s.withIndex().filter { (_, v) -> v != null && v < jog * 1.2 }.map { it.index + 1 } else emptyList()
        val quickest = Ru.pace(1000 / got.max())
        val slowest = Ru.pace(1000 / got.min())
        val parts = mutableListOf(
            "${s.size} из ${RunTimeline.STRIDES}: " + if (quickest == slowest) "$quickest/км." else "от $quickest до $slowest/км.",
        )
        when {
            slow.isNotEmpty() && jog != null -> parts += (if (slow.size == 1) "Ускорение ${slow[0]}" else "Ускорения ${andList(slow)}") +
                " почти не быстрее трусцы (${Ru.pace(1000 / jog)}/км). В следующий раз смелее, но не в полную силу."
            s.size < RunTimeline.STRIDES -> parts += "В следующий раз сделай все 4."
        }
        return Item("Ускорения", parts.joinToString(" "), if (slow.isEmpty() && s.size == RunTimeline.STRIDES) 1 else 0)
    }

    /** "2 и 3", "1, 2 и 4". */
    private fun andList(xs: List<Int>): String =
        if (xs.size < 2) xs.joinToString(", ") else xs.dropLast(1).joinToString(", ") + " и " + xs.last()

    /** A free run: its pace, its laps, its own target. */
    private fun free(r: Run): Item? {
        val w = r.workout
        val lapM = w.lapMeters
        val laps = r.laps.filter { it.rep == null }
        val parts = mutableListOf<String>()
        r.avgPace?.let { parts += "Средний темп ${Ru.pace(it)}/км." }
        if (lapM != null && laps.size >= 3) {
            val per = laps.map { it.seconds / it.part }
            parts += "Круги от ${Ru.lap(per.min())} до ${Ru.lap(per.max())}."
            val judged = laps.filter { it.slow != null }
            if (judged.isNotEmpty()) parts += "Кругов в цели ${judged.count { it.verdict == 0 }} из ${judged.size}."
        }
        return if (parts.isEmpty()) null else Item("Как прошла пробежка", parts.joinToString(" "), 0)
    }

    /**
     * The rating against the one the session was meant for, and nothing more: what the plan does with it is said once,
     * in its own block (13 October: the debrief announces no decisions). Read as [RunPlan.after] reads it, on the
     * runner's own scale ([Run.ownScale] taken off) — for reps only where the pace was measured and held, the pace being
     * the proof. Easy, long and run/walk have a limit, not a mark to hit: up to 5, 6 and 6. Never in a warning colour: a
     * rating is no mistake.
     */
    private fun effort(r: Run): Item? {
        val rpe = r.rpe ?: return null
        val w = r.workout
        // A session made lighter for a bad day moves nothing in the plan.
        if (w.light) return Item("Тяжесть", "$rpe из 10. Тренировка была облегчённой, план она не двигает.", 0)
        val own = when (w.type) {
            RunType.GOAL_PACE, RunType.INTERVALS, RunType.TEMPO -> if (paceHeld(r)) r.ownScale else 0
            RunType.TEST, RunType.FREE -> 0
            RunType.EASY, RunType.LONG, RunType.RUN_WALK -> r.ownScale
        }
        val read = (rpe - own).coerceAtLeast(1)
        return when (w.type) {
            RunType.TEST -> null
            RunType.FREE -> if (rpe >= 8) Item("Тяжесть", "$rpe из 10: считай этот день тяжёлым.", 0) else null
            RunType.EASY, RunType.LONG, RunType.RUN_WALK -> {
                val limit = ratingLimit(w.type) ?: w.targetRpe
                if (read <= limit) Item("Тяжесть", "$rpe из 10, как задумано.", 1)
                else Item("Тяжесть", "$rpe из 10, задумано до $limit.", 0)
            }
            RunType.GOAL_PACE, RunType.INTERVALS, RunType.TEMPO -> {
                val t = w.targetRpe
                when {
                    read <= t - 2 -> Item("Тяжесть", "$rpe из 10, задумано $t: силы были в запасе.", 1)
                    read <= t -> Item("Тяжесть", "$rpe из 10, как задумано.", 1)
                    else -> Item("Тяжесть", "$rpe из 10, задумано $t.", 0)
                }
            }
        }
    }

    /**
     * The rating an easy run, a long run or run/walk is meant to stay within, as [RunPlan.after] moves them on: up to 5, 6
     * and 6. Null for the others, which have a mark ([RunWorkout.targetRpe]).
     */
    fun ratingLimit(type: RunType): Int? = when (type) {
        RunType.EASY -> 5
        RunType.LONG, RunType.RUN_WALK -> 6
        else -> null
    }

    /**
     * The reps' pace measured and no more than 3 % slower than their target: as [RunPlan.after] decides whether the
     * runner's own scale counts for a session of reps.
     */
    private fun paceHeld(r: Run): Boolean {
        val pace = r.workPace ?: return false
        val target = r.workout.segments.firstOrNull { it.kind == SegmentKind.WORK }?.pace?.let { (it.fast + it.slow) / 2.0 } ?: return true
        return pace <= target * 1.03
    }

    /**
     * How long before the next hard run: longer after a very hard one. Nothing about the gym in between: the plan may put
     * legs the next day (23 November: «зал на верх тела» for 48 hours stood over «Всё тело · А» the next morning).
     */
    private fun recovery(r: Run, base: Int): Item {
        val hard = (r.rpe ?: 0) >= 8
        val h = when {
            r.workout.type == RunType.FREE && hard -> 48
            (r.rpe ?: 0) >= 9 && base < 72 -> base + 24
            else -> base
        }
        val text = if (h <= 24) "Такой бег проходит за сутки. Завтра можно тренироваться по плану."
        else "Следующий тяжёлый бег не раньше чем через ${Ru.nobreak(RunExplain.recoveryWords(h))}."
        return Item("Восстановление", text, 0)
    }
}
