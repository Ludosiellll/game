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
    )

    /** [gym]: the person also lifts (the advice between hard runs names the gym only then). */
    fun of(r: Run, goal: RunGoal? = null, gym: Boolean = true): List<Item> {
        val w = r.workout
        val out = mutableListOf<Item>()
        how(r, goal)?.let { out += it }
        effort(r)?.let { out += it }
        val why = RunExplain.of(w, goal)
        if (w.type == RunType.FREE) {
            out += Item("Что это даёт телу", freeBody(r.rpe), 0)
            // RunPlan.after: a free run counts as running (no break, the load), but moves no ladder and no minutes.
            out += Item(
                "План", "Свой бег шагов плана не двигает: лестницы и длительность растут по плановым тренировкам. Он засчитан " +
                    "как бег — перерыва нет — и в нагрузку («Свежесть»).", 0,
            )
        } else {
            out += Item("Что это даёт телу", why.body, 0)
        }
        out += recovery(r, why.recoveryHours, gym)
        return out
    }

    /** A free run after the fact: as hard as it felt. */
    private fun freeBody(rpe: Int?): String = when {
        rpe == null -> "Спокойный бег действует как лёгкий — строит аэробную базу; быстрый — как тяжёлая тренировка."
        rpe >= 8 -> "По тяжести — как тяжёлая тренировка: тренирует скорость и выносливость, но и утомляет так же."
        rpe >= 6 -> "Бег средней тяжести: аэробную систему тренирует сильнее лёгкого, но и утомляет больше."
        else -> "Спокойный бег — как лёгкий: строит аэробную базу почти без утомления."
    }

    private fun how(r: Run, goal: RunGoal?): Item? {
        val w = r.workout
        return when (w.type) {
            RunType.GOAL_PACE, RunType.INTERVALS, RunType.TEMPO -> reps(r, goal)
            RunType.EASY, RunType.LONG -> easy(r)
            RunType.TEST -> trial(r, goal)
            RunType.FREE -> free(r)
            RunType.RUN_WALK -> r.completed?.let {
                if (it >= 0.95) Item("Как пробежал", "Все беговые отрезки сделаны.", 1)
                else Item("Как пробежал", "Сделано ${(it * 100).roundToInt()} % тренировки: шаг вперёд — когда получится целиком.", 0)
            }
        }
    }

    /** Reps: the laps pressed on target, how even the reps were; on the road, the reps' pace against the target. */
    private fun reps(r: Run, goal: RunGoal?): Item? {
        val w = r.workout
        val lapM = w.lapMeters
        val judged = r.laps.filter { it.rep != null && !it.auto && (it.fast != null || it.slow != null) }
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
            if (range != null) parts += "Средний круг — ${if (avg < 59.95) "${Ru.one(avg)} с" else Ru.lap(avg)} при цели " +
                "${Ru.lapRange(range.first, range.second)}."
            var tone = if (on * 5 >= judged.size * 4) 1 else 0
            // The reps from first to last: a fall-off means the start was too quick.
            val byRep = repLaps.groupBy { it.rep!! }.toSortedMap().values.map { ls -> ls.sumOf { it.seconds } / ls.sumOf { it.part } }
            if (byRep.size >= 2) {
                val drift = byRep.last() - byRep.first()
                parts += when {
                    drift > 1.0 -> "Последний отрезок медленнее первого на ${Ru.one(drift)} с на круге — так бывает, когда " +
                        "начало быстрее нужного. В следующий раз первый — ровно по графику."
                    drift < -1.0 -> "Последний отрезок быстрее первого на ${Ru.one(-drift)} с на круге: запас был — ровнее можно с первого."
                    else -> "Отрезки ровные: первый и последний — в пределах секунды на круге."
                }
                if (drift > 1.0) tone = minOf(tone, 0)
            }
            when {
                fast * 10 >= judged.size * 3 -> {
                    parts += "Много кругов быстрее цели: на ${goal?.let { "${it.meters} м" } ?: "дистанции"} решают ровные круги — быстрые сейчас дорого стоят в конце."
                    tone = -1
                }
                slow * 10 >= judged.size * 3 -> {
                    parts += "Много кругов медленнее цели: этот темп пока тяжёл — держать его ровно и учит эта тренировка."
                    tone = -1
                }
            }
            return Item("Как пробежал", parts.joinToString(" "), tone)
        }
        // The road, or reps entered by hand: the reps' pace against the target.
        val pace = r.workPace ?: return null
        val p = w.segments.firstOrNull { it.kind == SegmentKind.WORK }?.pace ?: return null
        val words = "Темп отрезков ${Ru.pace(pace)}/км при цели ${Ru.pace(p.fast.toDouble())}–${Ru.pace(p.slow.toDouble())}"
        return when {
            pace < p.fast - 3 -> Item("Как пробежал", "$words — быстрее цели. Ровно в цели — полезнее: быстрее не значит лучше.", -1)
            pace > p.slow + 3 -> Item("Как пробежал", "$words — медленнее цели: этот темп пока тяжёл.", -1)
            else -> Item("Как пробежал", "$words — в темпе.", 1)
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
        if (limit == null) return Item("Как пробежал", "$said.", 0)
        val limitWords = if (byLaps) "не быстрее ${Ru.lap(Track.lapSec(limit, lapM!!))} на круг" else "не быстрее ${Ru.pace(limit)}/км"
        return if (pace < limit - 2) {
            Item("Как пробежал", "$said — быстрее лёгкого ($limitWords): это уже не лёгкий бег. Сбавь — пользы для базы " +
                "столько же, а усталость не съест тяжёлые дни.", -1)
        } else {
            Item("Как пробежал", "$said — лёгкий темп, так и надо.", 1)
        }
    }

    /** A time trial: its time, against the goal, and how even it was. */
    private fun trial(r: Run, goal: RunGoal?): Item? {
        val (m, sec) = r.trial ?: return null
        val parts = mutableListOf("Контрольный: $m м за ${Ru.clock(sec)}.")
        var tone = 0
        if (goal != null && abs(m - goal.meters) <= goal.meters * 0.03) {
            // Over the goal distance at the same pace: a few metres short of 1500 are a second or so. Reached is what the
            // plan counted (fitness against the goal's, unrounded); the seconds are only to say how far.
            val est = sec * goal.meters.toDouble() / m
            val reached = r.goalReached ?: (est <= goal.seconds)
            val gap = kotlin.math.ceil(est - goal.seconds).toInt().coerceAtLeast(1)
            parts += if (reached) "Это цель: ${goal.meters} м за ${Ru.clock(goal.seconds)} или быстрее!".also { tone = 1 }
            else "До цели ${Ru.clock(goal.seconds)} — ещё ${if (gap < 60) "$gap с" else Ru.clock(gap)}. Темпы пересчитаны по этому времени."
        }
        val laps = r.laps.filter { it.rep != null }
        if (laps.size >= 4) {
            val half = laps.size / 2
            val a = laps.take(half).let { ls -> ls.sumOf { it.seconds } / ls.sumOf { it.part } }
            val b = laps.drop(half).let { ls -> ls.sumOf { it.seconds } / ls.sumOf { it.part } }
            parts += when {
                b - a > 2.0 -> "Вторая половина медленнее первой на ${Ru.one(b - a)} с на круге: старт был быстрым — ровнее выйдет быстрее."
                a - b > 0.5 -> "Вторая половина быстрее первой: разложил силы правильно."
                else -> "Круги ровные — так и бегут забег."
            }
        }
        return Item("Как пробежал", parts.joinToString(" "), tone)
    }

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
            if (judged.isNotEmpty()) parts += "В своей цели — ${judged.count { it.verdict == 0 }} из ${judged.size}."
        }
        return if (parts.isEmpty()) null else Item("Как пробежал", parts.joinToString(" "), 0)
    }

    /** The effort against the one the session was meant to be. */
    private fun effort(r: Run): Item? {
        val rpe = r.rpe ?: return null
        val w = r.workout
        val t = w.targetRpe
        // A session made lighter for a bad day moves nothing in the plan: nothing to say about the next step.
        if (w.light) return Item("Тяжесть", "Оценка $rpe. Тренировка была облегчённой (самочувствие так себе) — план она не двигает.", 0)
        return when (w.type) {
            RunType.TEST -> null
            RunType.FREE -> if (rpe >= 8) Item("Тяжесть", "Оценка $rpe — тяжёлая пробежка: её считай тяжёлым днём.", 0) else null
            // As RunPlan.after decides: an easy run grows at 5 or less and shortens at 7 or more; a long run grows at 6 or
            // less and shortens at 8 or more; run/walk steps on at 6 or less.
            RunType.EASY -> when {
                rpe >= 7 -> Item("Тяжесть", "Оценка $rpe — для лёгкого бега тяжело: следующий будет короче. Тело ещё привыкает к этому объёму — это нормально.", -1)
                rpe <= 5 -> Item("Тяжесть", "Оценка $rpe — легко, как и должно быть.", 1)
                else -> Item("Тяжесть", "Оценка $rpe — длительность пока прежняя: она растёт, когда лёгкий бег действительно лёгкий (оценка до 5).", 0)
            }
            RunType.LONG -> when {
                rpe >= 8 -> Item("Тяжесть", "Оценка $rpe — тяжело: следующая длинная будет короче.", -1)
                rpe <= 6 -> Item("Тяжесть", "Оценка $rpe — спокойно, как и должно быть.", 1)
                else -> Item("Тяжесть", "Оценка $rpe — длительная растёт при оценке до 6, при этой — нет.", 0)
            }
            RunType.RUN_WALK -> if (rpe >= 7) {
                Item("Тяжесть", "Оценка $rpe — тяжело: шаг вперёд не засчитан (нужно до 6). Тело ещё привыкает — это нормально.", -1)
            } else {
                Item("Тяжесть", "Оценка $rpe — по силам.", 1)
            }
            else -> {
                // A step on needs the effort no harder than this (race pace: up to 8; intervals and tempo: their own).
                val limit = if (w.type == RunType.GOAL_PACE) 8 else t
                when {
                    rpe > limit -> Item(
                        "Тяжесть", "Оценка $rpe — тяжелее, чем нужно для шага вперёд (до $limit): шаг не засчитан. Бывает: сон, " +
                            "еда, усталость от зала.", -1,
                    )
                    // Race pace early on is meant at 7, but a step on counts up to 8.
                    rpe > t -> Item("Тяжесть", "Оценка $rpe — тяжелее задуманной ($t), но шаг вперёд засчитывается до $limit.", 0)
                    rpe <= t - 2 -> Item("Тяжесть", "Оценка $rpe при задуманной $t — легче плана: запас есть.", 1)
                    else -> Item("Тяжесть", "Оценка $rpe при задуманной $t — как задумано.", 1)
                }
            }
        }
    }

    /** How long before the next hard session: longer after a very hard one. */
    private fun recovery(r: Run, base: Int, gym: Boolean): Item {
        val hard = (r.rpe ?: 0) >= 8
        val h = when {
            r.workout.type == RunType.FREE && hard -> 48
            (r.rpe ?: 0) >= 9 && base < 72 -> base + 24
            else -> base
        }
        val text = if (h <= 24) "Такой бег проходит за сутки: завтра можно тренироваться по плану."
        else "Следующая тяжёлая тренировка — не раньше чем через ${RunExplain.recoveryWords(h)}. До неё — лёгкий бег${if (gym) ", верх в зале" else ""} или отдых."
        return Item("Восстановление", text, 0)
    }
}
