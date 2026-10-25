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
                    "Основа плана. Бег на ${goal!!.meters} м больше чем на три четверти держится на выносливости. Лёгкий бег её " +
                        "строит: сердце учится приносить мышцам больше кислорода. И почти не утомляет."
                } else {
                    "Основа бега. Лёгкий бег строит выносливость: сердце учится приносить мышцам больше кислорода. И почти не утомляет."
                },
                body = "Сердце за один удар качает больше крови. В мышцах прибавляется мелких сосудов, и кислорода к ним " +
                    "приходит больше. Это копится неделями, поэтому важно бегать регулярно.",
                how = "${easyLimit(w)}Быстрее — уже не лёгкий бег. Устанешь почти как от тяжёлого, и на тяжёлые дни не хватит сил.",
                recoveryHours = 24,
                next = "Пробежишь целиком, обычной длины и с оценкой до 5 — следующий будет на 3 минуты длиннее. Пробежишь " +
                    "дольше плана — прибавка больше. Расти будет до потолка плана. Если пробежка короче обычной (например, в " +
                    "облегчённую неделю), длина не растёт. Оценка 7 и выше — следующий на 5 минут короче.",
            )
            RunType.LONG -> Why(
                purpose = "Выносливость: бежать дольше и не уставать.",
                body = "То же, что лёгкий бег, только сильнее. Мышцы учатся дольше работать и бережнее тратить запасы энергии. " +
                    "Связки и сухожилия привыкают к долгой нагрузке.",
                how = "${easyLimit(w)}Под конец не ускоряйся, иначе она станет тяжёлой тренировкой.",
                recoveryHours = 48,
                next = "Длинная обычной длины с оценкой до 6 — следующая будет на 5 минут длиннее. Но не больше чем " +
                    "на 10 % длиннее самой долгой пробежки за месяц и не выше потолка плана. Оценка 8 и выше или пробежал не до " +
                    "конца — следующая на 5 минут короче.",
            )
            RunType.GOAL_PACE -> Why(
                purpose = (if (middle) stepWords(w, goal!!) else "Темп забега. ") +
                    "Отрезки короче забега, между ними отдых. Так темп держится, и ты учишься бежать ровно без перегрузки. " +
                    "Дальше по шагам: сначала отрезков больше, потом отдых короче, потом отрезки длиннее. В конце — забег из " +
                    "двух частей, а за ним контрольный.",
                body = "Мышцы привыкают к скорости забега, и на ту же скорость уходит меньше сил. А ещё тело учится терпеть " +
                    "усталость, так что на дистанции «забиваешься» позже.",
                how = "Не беги первые отрезки быстрее: на последние не хватит сил. Ускорения в конце разминки готовят ноги к " +
                    "быстрому бегу, не пропускай их.",
                recoveryHours = 48,
                next = ladder(w, RunPlan.goalLadderSize, 8, "в темпе цели") + " После последнего шага — контрольный забег на всю дистанцию.",
            )
            RunType.INTERVALS -> Why(
                purpose = "Поднимает твой «потолок»: сколько кислорода тело может взять за минуту." +
                    if (middle) " Для ${goal!!.meters} м это очень важно: чем выше потолок, тем легче тебе скорость забега." else "",
                body = "Отрезки по 2–5 минут держат сердце и дыхание почти на пределе. От этого сердце качает больше, а мышцы " +
                    "берут больше кислорода. Отдых трусцой даёт сделать столько работы, сколько подряд не выдержать.",
                how = "Первые быстрее плана — последние развалятся. Ровно важнее, чем быстро.",
                recoveryHours = 48,
                next = ladder(w, RunPlan.intervalLadderSize, w.targetRpe, "в темпе"),
            )
            RunType.TEMPO -> Why(
                purpose = "Поднимает самый быстрый темп, который ты можешь держать долго. Чем он выше, тем быстрее ты пробежишь " +
                    "длинную дистанцию.",
                body = "Мышцы учатся пускать в дело то, что копится при быстром беге. Поэтому быстрый темп держится дольше, а " +
                    "усталость приходит позже.",
                how = "Не превращай в гонку. Держи темп ровно, быстрее не надо.",
                recoveryHours = 48,
                next = ladder(w, RunPlan.tempoLadderSize, w.targetRpe, "в темпе"),
            )
            RunType.TEST -> Why(
                purpose = "Проверяем твою форму. По ${measure(w)} приложение пересчитает темпы всех пробежек." +
                    (if (middle) " Темп цели не меняется — это твоя цель." else "") + " Чем точнее замер, тем точнее план.",
                body = "Это нагрузка как на соревнованиях. Следующая тяжёлая тренировка — не раньше чем через 2–3 дня.",
                how = "Главная ошибка — рвануть со старта. Потом потеряешь больше, чем выиграл в начале.",
                recoveryHours = 72,
                next = "По ${measure(w)} пересчитаются темпы. Следующий контрольный — через " +
                    if (middle) "5 недель. Или раньше, когда пройдёшь все шаги темпа цели, но не чаще раза в три недели." else "8 недель.",
            )
            RunType.RUN_WALK -> Why(
                purpose = "Начинаешь бегать без травм. Каждую неделю бега больше, а ходьбы меньше. Цель — 30 минут бега без остановки.",
                body = "Сердце и дыхание привыкают к бегу быстрее, чем кости и связки. Ходьба между отрезками даёт им время окрепнуть.",
                how = "Тяжело — не беда: тренировка просто повторится.",
                recoveryHours = 48,
                next = "Пробежишь целиком с оценкой до 6 — дальше следующая тренировка. Не получится — повторишь. Не получится " +
                    "второй раз подряд — вернёшься на неделю назад.",
            )
            RunType.FREE -> Why(
                purpose = "Свободный бег план не двигает. Но нагрузка попадает в «Свежесть» — по ней видно, насколько ты устал.",
                body = "Зависит от того, как побежишь. Спокойно — это как лёгкий бег. Быстро — как тяжёлая тренировка, и тогда " +
                    "следующий тяжёлый день лучше сдвинуть на день.",
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
        val miss = "Не получится — повторишь. Не получится второй раз подряд — шаг назад."
        return if (w.cutback) {
            "Шаг ${w.stage + 1} из $size, облегчённая неделя: отрезков меньше обычного. Получится — останешься на этом шаге " +
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
        val goalWords = "${goal.meters} м за ${Ru.clock(goal.seconds)} — ${paceWords(w, goal)}"
        if (pace <= goal.pace + 1.0) return "Темп забега: $goalWords. "
        val lapM = w.lapMeters
        val step = if (lapM != null) "круг за ${Ru.lap(Track.lapSec(pace, lapM))}" else "${Ru.pace(pace)} на км"
        return "Ступень к цели: темп забега ${goal.meters} м за ${Ru.clock((pace * goal.meters / 1000).roundToInt())}, $step. " +
            "Это на 2,5 % быстрее твоей формы по последнему контрольному. Цель — $goalWords. В темпе цели отрезки пока не " +
            "получились бы, а ступень тяжёлая, но по силам. После каждого контрольного ступень ближе к цели. "
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
