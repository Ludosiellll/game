package forma.core.plan

import forma.core.model.Emphasis

/**
 * What to do first on a day with both the gym and a run, and why. The session that needs freshness most goes first: a
 * race, a trial or a hard run before lifting that works the legs (reps after squats do not come off; upper-body work
 * barely suffers after a run); the gym before an easy run (the squat needs fresh legs, the easy run is a cool-down).
 * Where the two needs meet — a hard run and the legs, a long run — the person's priority ([Emphasis]) decides. Strength
 * work costs running economy for hours, running before lifting costs lower-body strength in the session (the
 * "interference" of concurrent training): the one that matters more goes first, six hours or more between them spares
 * both. On top: how tired the person is now, what the gym session does to the legs exercise by exercise, whether the
 * legs are still heavy from the last days, a lighter gym session.
 */
object SessionOrder {
    /** The run of the day as it stands: the race, a trial, a hard run, a long run, an easy one. */
    enum class Run { RACE, TRIAL, HARD, LONG, EASY }

    /** What the gym session does to the legs, by its exercises: nothing, a little (calves, curls), the heavy lifts. */
    enum class Legs { NONE, LIGHT, HEAVY }

    /** How tired the person is now: their freshness against their own rest line, and how they said they feel. */
    enum class Fatigue { FRESH, NORMAL, TIRED }

    /**
     * The day as it stands. [legNames]: the exercises that work the legs (to name them); [legsHeavyFrom]: the legs still
     * heavy from a session of the last two days ("вчера", "позавчера"), null when they are not; [gymLight]: the gym
     * session is a lighter one (a lighter week, a "so-so" day).
     */
    data class Day(
        val run: Run,
        val legs: Legs,
        val emphasis: Emphasis,
        val fatigue: Fatigue = Fatigue.NORMAL,
        val legNames: List<String> = emptyList(),
        val legsHeavyFrom: String? = null,
        val gymLight: Boolean = false,
    )

    /** [runFirst]: the run before the gym; [why]: the reasons; [tips]: what else to do with the day. */
    data class Advice(val runFirst: Boolean, val why: String, val tips: List<String> = emptyList())

    fun advise(d: Day): Advice {
        val gymFirstWanted = d.emphasis == Emphasis.GYM
        val names = d.legNames.take(2).joinToString(", ")
        val tips = mutableListOf<String>()
        val (runFirst, why) = when (d.run) {
            Run.RACE, Run.TRIAL -> true to (if (d.run == Run.RACE) "Сегодня забег" else "Сегодня контрольный") +
                " — бег на результат: он на свежие силы, первым. Зал после — коротко и легко" + (if (d.legs != Legs.NONE) ", без ног." else ".")
            Run.HARD -> when {
                d.legs == Legs.HEAVY && gymFirstWanted ->
                    false to "Зал у тебя в приоритете — сначала ноги${paren(names)}. Тяжёлый бег после них в полную силу не получится: беги его по самочувствию, отрезки можно сократить."
                d.legs == Legs.HEAVY ->
                    true to "После ног${paren(names)} отрезки не получатся, а ноги после бега теряют немного — веса подстроятся сами."
                gymFirstWanted ->
                    false to "Зал у тебя в приоритете — сначала он: " + (if (d.legs == Legs.LIGHT) "только ноги ($names) оставь на после бега, " else "") +
                        "верх тела бегу почти не мешает, ноги останутся свежими для отрезков."
                d.legs == Legs.LIGHT ->
                    true to "Отрезкам нужны свежие ноги. В зале сегодня есть и ноги понемногу ($names) — после бега это не страшно."
                else ->
                    true to "Отрезкам нужны свежие ноги и голова, а верху тела бег почти не мешает."
            }
            Run.LONG -> if (d.emphasis == Emphasis.RUN) {
                true to "Длинная у тебя важнее — сначала она, в спокойном темпе. Зал после: " +
                    if (d.legs == Legs.HEAVY) "ноги — на 1–2 повтора дальше от отказа." else "как обычно."
            } else {
                false to "Зал — на свежие силы, длинная после — спокойно: это аэробная работа, ей свежесть нужна меньше."
            }
            Run.EASY -> false to if (d.legs == Legs.HEAVY) {
                "Ноги${paren(names)} — на свежих силах. Лёгкий бег после — как заминка: спокойно, им ноги восстанавливаются."
            } else {
                "Лёгкий бег после зала — как заминка, а подходы идут в полную силу."
            }
        }
        // What else the day asks for.
        when {
            d.run == Run.HARD && d.legs == Legs.HEAVY -> tips += "Если можно — разнеси на 6 часов и больше: утром бег, вечером зал. Тогда и то и другое в полную силу."
            d.run == Run.HARD && !runFirst -> tips += "Перед бегом — разминка подольше: 12–15 минут трусцой и 3–4 ускорения."
            d.run == Run.HARD && d.legs == Legs.NONE -> tips += "Удобнее сначала зал — можно: верх ноги не утомляет. Тогда подходы без отказа, а разминка перед бегом подольше."
            d.run == Run.EASY && d.legs != Legs.HEAVY -> tips += "Можно и наоборот — 10–15 минут лёгкого бега перед залом как разминка."
            d.run == Run.LONG && !runFirst && d.legs == Legs.HEAVY -> tips += "После ног длинная может идти тяжелее — сбавь темп, это нормально."
            d.run == Run.RACE || d.run == Run.TRIAL -> tips += "Можно и перенести зал на завтра — результат важнее."
        }
        if (d.legsHeavyFrom != null && d.run != Run.EASY && d.run != Run.LONG) {
            tips += "Ноги ещё не отошли после тренировки ${d.legsHeavyFrom}: разминка длиннее, отрезки — по самочувствию."
        }
        if (d.fatigue == Fatigue.TIRED) tips += when {
            d.run == Run.RACE || d.run == Run.TRIAL -> "Ты сейчас уставший: после бега на результат зал сегодня лучше пропустить."
            d.run == Run.HARD && !runFirst -> "Ты сейчас уставший: зал первым, а тяжёлый бег сегодня лучше заменить лёгким."
            d.run == Run.HARD -> "Ты сейчас уставший (свежесть ниже твоей линии отдыха): главное сегодня — бег, зал после короче — подсобные можно пропустить."
            else -> "Ты сейчас уставший: зал — главное, лёгкий бег сократи до 20 минут или пропусти — восстановление важнее."
        }
        if (d.gymLight) tips += "Зал сегодня облегчённый — порядок важен меньше, бегу он почти не помешает."
        return Advice(runFirst, why, tips)
    }

    /**
     * One of the two done, the other ahead: how to run or lift after it. [runDone]: the run was first. Null when there is
     * nothing to adjust.
     */
    fun afterFirst(d: Day, runDone: Boolean): String? = when {
        !runDone && d.legs == Legs.HEAVY && d.run != Run.EASY && d.run != Run.LONG ->
            "Ноги уже сделаны: тяжёлый бег после них — по самочувствию. Разминка длиннее, отрезки можно сократить, темп — не быстрее плана."
        !runDone && d.run == Run.EASY -> "Зал сделан — теперь лёгкий бег как заминка: спокойно, разговорный темп."
        runDone && d.legs == Legs.HEAVY && d.run != Run.EASY ->
            "Бег сделан — в зале ноги на 1 повтор дальше от отказа: после бега они слабее, веса подстроятся сами."
        runDone && (d.run == Run.RACE || d.run == Run.TRIAL) -> "Бег на результат позади — зал сегодня коротко и легко или завтра."
        else -> null
    }

    private fun paren(names: String) = if (names.isEmpty()) "" else " ($names)"

    /** The order in words: "Сначала бег, потом зал." */
    fun words(a: Advice): String = if (a.runFirst) "Сначала бег, потом зал." else "Сначала зал, потом бег."
}
