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
                    "Основа плана. Бег на ${goal!!.meters} м больше чем на три четверти держится на аэробной системе — на том, " +
                        "сколько кислорода сердце доставляет мышцам и сколько они его берут. Лёгкий бег её строит, почти не утомляя."
                } else {
                    "Основа выносливости: аэробная система — сколько кислорода сердце доставляет мышцам и сколько они его берут. " +
                        "Лёгкий бег её строит, почти не утомляя."
                },
                body = "Сердце за удар выталкивает больше крови, в мышцах прибавляется капилляров и митохондрий — там топливо " +
                    "сжигается с кислородом. Это копится неделями: одна пробежка ничего не решает, регулярность решает.",
                how = "${easyLimit(w)}Быстрее — уже не лёгкий бег: устанешь почти как от тяжёлого, а тяжёлые дни потеряют качество.",
                recoveryHours = 24,
                next = "Длительность растёт на 3 минуты (больше, если пробежал дольше плана), когда бег лёгкий — оценка до 5. " +
                    "Тяжело (7 и выше) — следующий на 5 минут короче.",
            )
            RunType.LONG -> Why(
                purpose = "Выносливость: дольше держать темп без усталости.",
                body = "То же, что от лёгкого бега, но глубже: дольше работают медленные волокна, мышцы учатся экономнее " +
                    "тратить запасы углеводов, связки и сухожилия привыкают к долгой работе.",
                how = "${easyLimit(w)}Под конец не ускоряйся: ускорение в конце превращает её в тяжёлую тренировку.",
                recoveryHours = 48,
                next = "Растёт на 5 минут при оценке до 6, но не больше чем на 10 % длиннее самой длинной пробежки за месяц. " +
                    "Тяжело (8 и выше) — следующая на 5 минут короче.",
            )
            RunType.GOAL_PACE -> Why(
                purpose = (if (middle) "Темп забега: ${goal!!.meters} м за ${Ru.clock(goal.seconds)} — ${paceWords(w, goal)}. " else "Темп забега. ") +
                    "Отрезки короче забега и с отдыхом: темп держится, и навык ровного бега закрепляется без перегрузки. " +
                    "От тренировки к тренировке отрезки длиннее, а отдых короче — до забега из двух частей, потом контрольный.",
                body = "Мышцы и нервная система привыкают к скорости забега: шаг становится экономнее, на ту же скорость уходит " +
                    "меньше сил. И тело учится работать, когда копится утомление, — на дистанции «забиваешься» позже.",
                how = "Быстрые первые отрезки дорого стоят последним. Ускорения в конце разминки готовят ноги к быстрому бегу — " +
                    "их не пропускай.",
                recoveryHours = 48,
                next = ladder(w, RunPlan.goalLadderSize, 8, pace = true) + " На верху лестницы — контрольный забег целиком.",
            )
            RunType.INTERVALS -> Why(
                purpose = "МПК — сколько кислорода тело может взять за минуту, «потолок» аэробной системы." +
                    if (middle) " Для ${goal!!.meters} м он один из главных: чем выше потолок, тем больше запас на скорости забега." else "",
                body = "Отрезки по 2–5 минут держат сердце и дыхание у предела: так растёт МПК — сердце качает больше, мышцы " +
                    "берут больше кислорода. Отдых трусцой даёт повторить столько, сколько за один отрезок не выдержать.",
                how = "Первые быстрее плана — последние развалятся: важнее ровно, чем быстро.",
                recoveryHours = 48,
                next = ladder(w, RunPlan.intervalLadderSize, w.targetRpe),
            )
            RunType.TEMPO -> Why(
                purpose = "Порог — скорость, на которой лактат («кислота») ещё не копится быстрее, чем убирается. Чем он выше, " +
                    "тем быстрее можно бежать долго.",
                body = "Мышцы быстрее перерабатывают лактат, и высокий темп держится дольше без «закисления».",
                how = "Не превращай в гонку: смысл — долго держать темп на пороге, а не выше него.",
                recoveryHours = 48,
                next = ladder(w, RunPlan.tempoLadderSize, w.targetRpe),
            )
            RunType.TEST -> Why(
                purpose = "Замер формы. По времени пересчитываются все темпы: лёгкий, темп цели, интервалы. " +
                    (if (middle) "Пока контрольного не было, темпы примерные — от цели. " else "") +
                    "Чем точнее замер, тем точнее план.",
                body = "Нагрузка как в забеге: тяжёлая для мышц и нервной системы. Следующая тяжёлая — не раньше чем через 2–3 дня.",
                how = "Резкий старт — главная ошибка: потом круги падают сильнее, чем выиграл в начале.",
                recoveryHours = 72,
                next = "По времени пересчитаются все темпы. Следующий контрольный — через " +
                    if (middle) "5 недель или раньше, когда пройдена лестница темпа цели (но не чаще раза в три недели)." else "8 недель.",
            )
            RunType.RUN_WALK -> Why(
                purpose = "Втягивание в бег без травм: каждую неделю бега больше, ходьбы меньше — до 30 минут без остановки.",
                body = "Сердце и дыхание привыкают к бегу быстрее, чем кости, связки и сухожилия: ходьба между отрезками даёт " +
                    "им время окрепнуть.",
                how = "Тяжело — не беда: тренировка повторится, а не усложнится.",
                recoveryHours = 48,
                next = "Получится целиком с оценкой до 6 — следующая тренировка программы; не получится — повтор, второй раз " +
                    "подряд — неделя назад.",
            )
            RunType.FREE -> Why(
                purpose = "Свой бег: план не двигает, нагрузка учитывается в «Свежести».",
                body = "Как пробежишь: спокойно — как лёгкий бег, быстро — как тяжёлая тренировка (тогда следующий тяжёлый день " +
                    "лучше сделать на день позже).",
                how = w.targetPace?.let { "Цель — ${target(w, it)}." } ?: "Как захочется.",
                recoveryHours = 24,
            )
        }
    }

    /** A rung of a ladder of [size] and how it moves: up at an effort of [limit] or less, back after two misses. */
    private fun ladder(w: RunWorkout, size: Int, limit: Int, pace: Boolean = false): String =
        "Шаг ${w.stage + 1} из $size. Получится целиком${if (pace) ", в темпе цели" else ""} и с оценкой до $limit — следующий " +
            "шаг; не получится — повтор, второй раз подряд — шаг назад."

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

    /** The goal pace as the runner checks it: the lap time on a stadium, the pace a km on the road. */
    private fun paceWords(w: RunWorkout, goal: RunGoal): String {
        val lapM = w.lapMeters
        return if (lapM != null) "круг $lapM м за ${Ru.lap(Track.lapSec(goal.pace, lapM))}" else "${Ru.pace(goal.pace)} на км"
    }

    private fun target(w: RunWorkout, p: PaceRange): String {
        val lapM = w.lapMeters
        return if (lapM != null) "круг ${Ru.lapRange(Track.lapSec(p.fast.toDouble(), lapM), Track.lapSec(p.slow.toDouble(), lapM))}"
        else "${Ru.pace(p.fast.toDouble())}–${Ru.pace(p.slow.toDouble())} на км"
    }

    /** Hours rounded to whole days for "через 2 дня": used by the debrief. */
    fun days(hours: Int): Int = (hours / 24.0).roundToInt().coerceAtLeast(1)
}
