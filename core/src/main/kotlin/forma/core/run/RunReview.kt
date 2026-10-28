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
    )

    /** [gym]: the person also lifts (the advice between hard runs names the gym only then). */
    fun of(r: Run, goal: RunGoal? = null, gym: Boolean = true): List<Item> {
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
            out += Item(
                "План", "Свободный бег план не двигает: шаги и длина пробежек растут только от тренировок по плану. Но он " +
                    "засчитан как бег, так что перерыва нет. И его нагрузка попала в «Свежесть».", 0,
            )
        } else {
            out += Item("Что это даёт телу", why.body, 0)
        }
        out += recovery(r, why.recoveryHours, gym)
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
                if (it >= 0.95) Item("Как пробежал", "Все беговые отрезки сделаны.", 1)
                else Item("Как пробежал", "Сделано ${(it * 100).roundToInt()} % тренировки. Дальше пойдёшь, когда пробежишь её целиком.", 0)
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
            if (range != null) parts += "Средний круг — ${if (avg < 59.95) "${Ru.one(avg)} с" else Ru.lap(avg)} при цели " +
                "${Ru.lapRange(range.first, range.second)}."
            var tone = if (on * 5 >= judged.size * 4) 1 else 0
            // The reps from first to last: a fall-off means the start was too quick.
            val byRep = repLaps.groupBy { it.rep!! }.toSortedMap().values.map { ls -> ls.sumOf { it.seconds } / ls.sumOf { it.part } }
            if (byRep.size >= 2) {
                val drift = byRep.last() - byRep.first()
                parts += when {
                    drift > 1.0 -> "Последний отрезок медленнее первого на ${Ru.one(drift)} с на круге. Так бывает, когда " +
                        "начинаешь слишком быстро. В следующий раз беги первый ровно по графику."
                    drift < -1.0 -> "Последний отрезок быстрее первого на ${Ru.one(-drift)} с на круге. Силы оставались, так что " +
                        "можно бежать ровнее с самого начала."
                    else -> "Отрезки ровные: первый и последний отличаются меньше чем на секунду на круге."
                }
                if (drift > 1.0) tone = minOf(tone, 0)
            }
            when {
                fast * 10 >= judged.size * 3 -> {
                    parts += "Много кругов быстрее цели. На ${goal?.let { "${it.meters} м" } ?: "дистанции"} важнее всего ровные круги: за быстрые сейчас платишь в конце."
                    tone = -1
                }
                slow * 10 >= judged.size * 3 -> {
                    parts += "Много кругов медленнее цели. Этот темп пока тяжёлый, и эта тренировка как раз учит держать его ровно."
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
            pace < p.fast - 3 -> Item("Как пробежал", "$words — быстрее цели. Пользы больше, когда бежишь ровно в цели.", -1)
            pace > p.slow + 3 -> Item("Как пробежал", "$words — медленнее цели. Этот темп пока даётся тяжело.", -1)
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
            Item("Как пробежал", "$said — быстрее лёгкого ($limitWords). Это уже не лёгкий бег. Сбавь: пользы столько же, " +
                "а силы останутся на тяжёлые дни.", -1)
        } else {
            Item("Как пробежал", "$said — лёгкий темп, так и надо.", 1)
        }
    }

    /** A time trial: its time, against the goal, and how even it was. */
    private fun trial(r: Run, goal: RunGoal?): Item? {
        val held = r.held
        val (m, sec) = r.trial ?: return held?.let { (hm, hs) ->
            // Not run in pace to the end: what was held from the start is the measure.
            Item(
                "Как пробежал",
                "Контрольный не добежан в темпе до конца. Ровно от старта — $hm м за ${Ru.clock(hs)}. " +
                    "Полный контрольный — через две недели: начни его чуть спокойнее, чтобы хватило до конца.",
                0,
            )
        }
        val parts = mutableListOf("Контрольный: $m м за ${Ru.clock(sec)}.")
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
            parts += if (reached) "Это цель: ${goal.meters} м за ${Ru.clock(goal.seconds)} или быстрее!".also { tone = 1 }
            else "До цели ${Ru.clock(goal.seconds)} — ещё ${if (gap < 60) "$gap с" else Ru.clock(gap)}." +
                if (held != null) "" else " Темпы пересчитаны по этому времени."
        }
        // Faded: the stretch held from the start says more of the form than the whole time (3 above it at most).
        if (held != null) parts += "Первые ${held.first} м ты держал темп (${Ru.clock(held.second)}), потом сбавил — темпы посчитаны с учётом этого."
        val laps = r.laps.filter { it.rep != null }
        if (laps.size >= 4) {
            val half = laps.size / 2
            val a = laps.take(half).let { ls -> ls.sumOf { it.seconds } / ls.sumOf { it.part } }
            val b = laps.drop(half).let { ls -> ls.sumOf { it.seconds } / ls.sumOf { it.part } }
            parts += when {
                b - a > 2.0 -> "Вторая половина медленнее первой на ${Ru.one(b - a)} с на круге. Старт был слишком быстрым: если бежать ровнее, выйдет быстрее."
                a - b > 0.5 -> "Вторая половина быстрее первой: разложил силы правильно."
                else -> "Круги ровные, так и надо бежать забег."
            }
        }
        return Item("Как пробежал", parts.joinToString(" "), tone)
    }

    /**
     * The warm-up's strides: how many were run faster than the jog (a fifth and more — else it was a jog), their pace
     * and how much faster than the jog. They wake the legs for fast running; they do not measure the form (15 seconds
     * are speed, not endurance), so nothing in the plan moves by them.
     */
    private fun strides(r: Run): Item? {
        val s = r.strides ?: return null
        val got = s.filterNotNull()
        if (got.isEmpty()) return Item("Ускорения", "Сделано ${s.size} из ${RunTimeline.STRIDES}. Темп не измерен: GPS их не поймал.", 0)
        val jog = r.jog
        val slow = if (jog != null) s.withIndex().filter { (_, v) -> v != null && v < jog * 1.2 }.map { it.index + 1 } else emptyList()
        val parts = mutableListOf(
            "${s.size} из ${RunTimeline.STRIDES}: " +
                if (got.size == 1) "${Ru.pace(1000 / got[0])}/км." else "от ${Ru.pace(1000 / got.min())} до ${Ru.pace(1000 / got.max())}/км.",
        )
        if (jog != null) parts += "Трусца была ${Ru.pace(1000 / jog)}/км — ускорения быстрее в ${Ru.one(got.average() / jog)} раза."
        parts += when {
            slow.isNotEmpty() -> (if (slow.size == 1) "Ускорение ${slow[0]}" else "Ускорения ${slow.joinToString(", ")}") +
                " почти не быстрее трусцы: в следующий раз смелее, но не в полную силу."
            s.size < RunTimeline.STRIDES -> "Не все сделаны — ноги к быстрому бегу готовы хуже. Их 4 по 15 секунд в конце разминки."
            else -> "Ноги готовы к быстрому бегу. На форму и темпы ускорения не влияют: 15 секунд — это скорость, а не выносливость."
        }
        return Item("Ускорения", parts.joinToString(" "), if (slow.isEmpty() && s.size == RunTimeline.STRIDES) 1 else 0)
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
        if (w.light) return Item("Тяжесть", "Оценка $rpe. Тренировка была облегчённой из-за самочувствия, поэтому план она не двигает.", 0)
        return when (w.type) {
            RunType.TEST -> null
            RunType.FREE -> if (rpe >= 8) Item("Тяжесть", "Оценка $rpe — тяжёлая пробежка. Считай этот день тяжёлым.", 0) else null
            // As RunPlan.after decides: an easy run grows at 5 or less and shortens at 7 or more; a long run grows at 6 or
            // less and shortens at 8 or more; run/walk steps on at 6 or less.
            RunType.EASY -> when {
                rpe >= 7 -> Item("Тяжесть", "Оценка $rpe — для лёгкого бега тяжело, следующий будет короче. Тело ещё привыкает к такой длине, это нормально.", -1)
                rpe <= 5 -> Item("Тяжесть", "Оценка $rpe — легко, как и должно быть.", 1)
                else -> Item("Тяжесть", "Оценка $rpe — длина пока прежняя. Она растёт, когда лёгкий бег правда лёгкий: оценка до 5.", 0)
            }
            RunType.LONG -> when {
                rpe >= 8 -> Item("Тяжесть", "Оценка $rpe — тяжело: следующая длинная будет короче.", -1)
                rpe <= 6 -> Item("Тяжесть", "Оценка $rpe — спокойно, как и должно быть.", 1)
                else -> Item("Тяжесть", "Оценка $rpe — длинная пробежка растёт только при оценке до 6, так что пока без прибавки.", 0)
            }
            RunType.RUN_WALK -> if (rpe >= 7) {
                Item("Тяжесть", "Оценка $rpe — тяжело, шаг вперёд не засчитан: нужно до 6. Тело ещё привыкает, это нормально.", -1)
            } else {
                Item("Тяжесть", "Оценка $rpe — по силам.", 1)
            }
            else -> {
                // A step on needs the effort no harder than this (race pace: up to 8; intervals and tempo: their own).
                val limit = if (w.type == RunType.GOAL_PACE) 8 else t
                when {
                    rpe > limit -> Item(
                        "Тяжесть", "Оценка $rpe — тяжелее, чем нужно для шага вперёд (до $limit), поэтому шаг не засчитан. Так " +
                            "бывает из-за сна, еды или усталости после зала.", -1,
                    )
                    // Race pace early on is meant at 7, but a step on counts up to 8.
                    rpe > t -> Item("Тяжесть", "Оценка $rpe — тяжелее задуманной ($t), но шаг вперёд засчитывается до $limit.", 0)
                    rpe <= t - 2 -> Item("Тяжесть", "Оценка $rpe при задуманной $t — легче плана, силы в запасе.", 1)
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
        val text = if (h <= 24) "Такой бег проходит за сутки. Завтра можно тренироваться по плану."
        else "Следующая тяжёлая тренировка — не раньше чем через ${RunExplain.recoveryWords(h)}. До неё — лёгкий бег${if (gym) ", зал на верх тела" else ""} или отдых."
        return Item("Восстановление", text, 0)
    }
}
