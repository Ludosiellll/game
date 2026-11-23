package forma.core.run

import forma.core.model.RunGoal
import forma.core.text.Ru
import kotlin.math.roundToInt

/**
 * Why a run is in the plan, what it trains in the body, how to run it and how long the body needs after it — the plain
 * words behind a session, said before it (the day screen) and after it (the debrief). General training knowledge (the
 * zones and sessions of Daniels' running formula, the energy systems of middle distance), not a forecast for one body:
 * how a runner answers shows in their own laps and trials over the weeks.
 */
object RunExplain {
    data class Why(
        /** What the session is for, on the way to the goal. */
        val purpose: String,
        /** What it trains in the body. */
        val body: String,
        /** What spoils it (the workout's own note says how to run it). */
        val how: String,
        /** Hours the body usually needs before the next hard session. */
        val recoveryHours: Int,
        /** How the plan moves on from it (the rules of [RunPlan.after]). */
        val next: String = "",
    )

    fun of(w: RunWorkout, goal: RunGoal? = null): Why {
        val middle = goal?.middleDistance == true
        return when (w.type) {
            RunType.EASY -> Why(
                purpose = if (middle) {
                    "Основа плана: бег на ${goal!!.meters} м больше чем на три четверти держится на выносливости. Лёгкий бег " +
                        "строит её и почти не утомляет."
                } else {
                    "Основа бега. Лёгкий бег строит выносливость и почти не утомляет."
                },
                body = "Сердце за один удар качает больше крови. В мышцах прибавляется мелких сосудов, и кислорода к ним " +
                    "приходит больше. Это копится неделями.",
                how = "${easyLimit(w)}Если быстрее, это уже не лёгкий бег.",
                recoveryHours = 24,
                next = "Пробежишь целиком, обычной длины и с оценкой до 5: следующий будет на 3 минуты длиннее, до потолка " +
                    "плана. Если пробежишь дольше плана, прибавка больше. Пробежка короче обычной (например, в " +
                    "лёгкую неделю) длину не прибавляет. При оценке 7 и выше следующий на 5 минут короче.",
            )
            RunType.LONG -> Why(
                purpose = "Выносливость: бежать дольше и не уставать.",
                body = "То же, что лёгкий бег, только сильнее. Мышцы учатся дольше работать и бережнее тратить запасы энергии. " +
                    "Связки и сухожилия привыкают к долгой нагрузке.",
                how = "${easyLimit(w)}Под конец не ускоряйся.",
                recoveryHours = 48,
                next = "Длинная обычной длины с оценкой до 6: следующая будет на 5 минут длиннее. Но не больше чем " +
                    "на 10 % длиннее самой долгой пробежки за месяц и не выше потолка плана. Если оценка 8 и выше или пробежка не " +
                    "закончена, следующая на 5 минут короче.",
            )
            RunType.GOAL_PACE -> Why(
                purpose = (if (middle) stepWords(w, goal!!) else "Темп забега. ") +
                    "Отрезки короче забега, между ними отдых. Так темп держится, и ты учишься бежать ровно. " +
                    "Дальше отрезки постепенно усложняются. В конце забег из двух частей, а за ним контрольный.",
                body = "Мышцы привыкают к скорости забега, и на ту же скорость уходит меньше сил. А ещё тело учится терпеть " +
                    "усталость, так что на дистанции «забиваешься» позже.",
                how = "Не беги первые отрезки быстрее, иначе на последние не хватит сил. Ускорения в конце разминки не пропускай.",
                recoveryHours = 48,
                next = ladder(w, RunPlan.goalLadderSize, 8, "в темпе цели") + " После последнего шага контрольный забег на всю дистанцию.",
            )
            RunType.INTERVALS -> Why(
                purpose = "Поднимает твой «потолок»: сколько кислорода тело может взять за минуту." +
                    if (middle) " Чем он выше, тем легче тебе скорость забега на ${goal!!.meters} м." else "",
                body = "Отрезки по 2–5 минут держат сердце и дыхание почти на пределе. От этого сердце качает больше, а мышцы " +
                    "берут больше кислорода. Отдых трусцой даёт сделать столько работы, сколько подряд не выдержать.",
                how = "Не беги первые отрезки быстрее плана, иначе последние развалятся.",
                recoveryHours = 48,
                next = ladder(w, RunPlan.intervalLadderSize, w.targetRpe, "в темпе"),
            )
            RunType.TEMPO -> Why(
                purpose = "Поднимает самый быстрый темп, который ты можешь держать долго. Чем он выше, тем быстрее ты пробежишь " +
                    "длинную дистанцию.",
                body = "Мышцы учатся пускать в дело то, что копится при быстром беге. Поэтому быстрый темп держится дольше, а " +
                    "усталость приходит позже.",
                how = "Держи темп ровно, без гонки.",
                recoveryHours = 48,
                next = ladder(w, RunPlan.tempoLadderSize, w.targetRpe, "в темпе"),
            )
            RunType.TEST -> Why(
                purpose = "Проверяем твою форму. По ${measure(w)} пересчитаются темпы всех пробежек." +
                    (if (middle) " Темп цели не меняется." else ""),
                // The rest after it is said in its own line (the day's «Восстановление», the debrief's), not twice.
                body = "Это нагрузка как на соревнованиях.",
                how = "Не рви со старта, потом потеряешь больше.",
                recoveryHours = 72,
                next = "Следующий контрольный через " +
                    if (middle) "5 недель. Или раньше, когда получится забег из двух частей, но не чаще раза в три недели." else "8 недель.",
            )
            RunType.RUN_WALK -> Why(
                purpose = "Начинаешь бегать без травм. Каждую неделю бега больше, а ходьбы меньше. Цель — 30 минут бега без остановки.",
                body = "Сердце и дыхание привыкают к бегу быстрее, чем кости и связки. Ходьба между отрезками даёт им время окрепнуть.",
                how = "Если тяжело, тренировка повторится.",
                recoveryHours = 48,
                next = "Пробежишь целиком с оценкой до 6: дальше следующая тренировка. Если не получится, повторишь. После двух " +
                    "неудач подряд вернёшься на неделю назад.",
            )
            RunType.FREE -> Why(
                purpose = "Свободный бег план не двигает, но засчитан как бег.",
                body = "Зависит от того, как побежишь. Спокойный бег работает как лёгкий, быстрый — как тяжёлая тренировка. " +
                    "После быстрого следующий тяжёлый день лучше сдвинуть на день.",
                how = w.targetPace?.let { "Цель — ${target(w, it)}." } ?: "Как захочется.",
                recoveryHours = 24,
            )
        }
    }

    /**
     * A rung of a ladder of [size] and how it moves: up at an effort of [limit] or less, back after two misses. A lighter
     * week's shorter session moves no rung up (`RunOutcome.LighterWeek`); a miss counts as always.
     */
    private fun ladder(w: RunWorkout, size: Int, limit: Int, pace: String): String {
        val miss = "Если не получится, повторишь. После двух неудач подряд шаг назад."
        return if (w.cutback) {
            "Шаг ${w.stage + 1} из $size, облегчённая неделя: отрезков меньше обычного. Если получится, останешься на этом шаге " +
                "до обычной недели. $miss"
        } else {
            "Шаг ${w.stage + 1} из $size. Сделаешь всё $pace (медленнее не больше чем на 3 %) и с оценкой до $limit — " +
                "следующий шаг. $miss"
        }
    }

    /** What a trial is measured by: the race distance by its time, the 12 minutes by their distance. */
    private fun measure(w: RunWorkout): String = if (w.testMeters != null) "времени забега" else "дистанции за 12 минут"

    /** "48 часов", "сутки", "2–3 дня". */
    fun recoveryWords(hours: Int): String = when {
        hours <= 24 -> "сутки"
        hours <= 48 -> "48 часов"
        else -> "2–3 дня"
    }

    /**
     * A warm-up, a cool-down or a rest in plain words: "12 мин", "90 с" under two minutes, "2 мин 15 с" — never as on a
     * clock (23 November: «Разминка 12:00» read as a warm-up at noon). The number keeps its unit on one line.
     */
    fun timeWords(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return when {
            s == 0 && m > 0 -> "$m\u00A0мин"
            m < 2 -> "$sec\u00A0с"
            else -> "$m\u00A0мин\u00A0$s\u00A0с"
        }
    }

    /** An easy run's limit: "Круг — не быстрее 1:23. ". */
    private fun easyLimit(w: RunWorkout): String {
        val pace = w.segments.firstOrNull { it.kind == SegmentKind.RUN }?.pace ?: return ""
        val lapM = w.lapMeters
        return if (lapM != null) "Круг — не быстрее ${Ru.lap(Track.lapSec(pace.fast.toDouble(), lapM))}. "
        else "Не быстрее ${Ru.pace(pace.fast.toDouble())} на км. "
    }

    /**
     * The pace of the reps against the goal: the goal's own once within reach; before that a step toward it (the race pace
     * of the current form a little faster), said with both times.
     */
    private fun stepWords(w: RunWorkout, goal: RunGoal): String {
        val pace = w.segments.firstOrNull { it.kind == SegmentKind.WORK }?.pace?.let { (it.fast + it.slow) / 2.0 } ?: goal.pace
        val goalWords = "${goal.meters} м за ${Ru.clock(goal.seconds)}, ${paceWords(w, goal)}"
        if (pace <= goal.pace + 1.0) return "Темп забега: $goalWords. "
        val lapM = w.lapMeters
        val step = if (lapM != null) "круг за ${Ru.lap(Track.lapSec(pace, lapM))}" else "${Ru.pace(pace)} на км"
        return "Ступень к цели: пока это темп забега ${goal.meters} м за ${Ru.clock((pace * goal.meters / 1000).roundToInt())}, $step. " +
            "Цель $goalWords. "
    }

    /** The goal pace as the runner checks it: the lap time on a stadium, the pace a km on the road. */
    private fun paceWords(w: RunWorkout, goal: RunGoal): String {
        val lapM = w.lapMeters
        return if (lapM != null) "круг $lapM м за ${Ru.lapTenth(Track.lapSec(goal.pace, lapM))}" else "${Ru.pace(goal.pace)} на км"
    }

    private fun target(w: RunWorkout, p: PaceRange): String {
        val lapM = w.lapMeters
        return if (lapM != null) "круг ${Ru.lapRange(Track.lapSec(p.fast.toDouble(), lapM), Track.lapSec(p.slow.toDouble(), lapM))}"
        else "${Ru.pace(p.fast.toDouble())}–${Ru.pace(p.slow.toDouble())} на км"
    }

    /** Hours rounded to whole days for "через 2 дня": used by the debrief. */
    fun days(hours: Int): Int = (hours / 24.0).roundToInt().coerceAtLeast(1)
}
