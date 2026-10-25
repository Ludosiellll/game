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
            Run.RACE, Run.TRIAL -> true to (if (d.run == Run.RACE) "Сегодня забег." else "Сегодня контрольный забег — проверка формы.") +
                " Бег на результат делай первым, пока силы свежие. Зал после — коротко и легко" + (if (d.legs != Legs.NONE) ", без ног." else ".")
            Run.HARD -> when {
                d.legs == Legs.HEAVY && gymFirstWanted ->
                    false to "Зал для тебя важнее, поэтому сначала ноги${paren(names)}. После них тяжёлый бег в полную силу не выйдет. Беги по самочувствию, отрезки можно сократить."
                d.legs == Legs.HEAVY ->
                    true to "После ног${paren(names)} отрезки не получатся. А ноги после бега слабеют совсем немного, веса в зале подстроятся сами."
                gymFirstWanted ->
                    false to "Зал для тебя важнее, поэтому сначала он. " + (if (d.legs == Legs.LIGHT) "Только ноги ($names) оставь на после бега. " else "") +
                        "Руки и спина бегу почти не мешают, ноги останутся свежими для отрезков."
                d.legs == Legs.LIGHT ->
                    true to "Для отрезков нужны свежие ноги. В зале сегодня немного ног ($names) — после бега это не страшно."
                else ->
                    true to "Для отрезков нужны свежие ноги и голова. А руки и спину бег почти не утомляет."
            }
            Run.LONG -> if (d.emphasis == Emphasis.RUN) {
                true to "Длинная пробежка для тебя важнее, поэтому сначала она, спокойно. Зал после: " +
                    if (d.legs == Legs.HEAVY) "в упражнениях на ноги оставляй на 1–2 повтора больше запаса." else "как обычно."
            } else {
                false to "Зал — на свежие силы. Длинную пробежку после беги спокойно: ей свежесть не так важна."
            }
            Run.EASY -> false to if (d.legs == Legs.HEAVY) {
                "Ноги${paren(names)} — на свежие силы. Лёгкий бег после — спокойная заминка, он помогает ногам восстановиться."
            } else {
                "Лёгкий бег после зала — как заминка. А подходы в зале идут в полную силу."
            }
        }
        // What else the day asks for.
        when {
            d.run == Run.HARD && d.legs == Legs.HEAVY -> tips += "Если можешь, разнеси их на 6 часов и больше: утром бег, вечером зал. Тогда оба пойдут в полную силу."
            d.run == Run.HARD && !runFirst -> tips += "Перед бегом разминайся дольше: 12–15 минут трусцой и 3–4 коротких ускорения."
            d.run == Run.HARD && d.legs == Legs.NONE -> tips += "Хочешь сначала зал — можно: руки и спина ноги не утомляют. Тогда подходы не до отказа, а перед бегом разминайся дольше."
            d.run == Run.EASY && d.legs != Legs.HEAVY -> tips += "Можно и наоборот: 10–15 минут лёгкого бега перед залом как разминка."
            d.run == Run.LONG && !runFirst && d.legs == Legs.HEAVY -> tips += "После ног длинная пробежка может идти тяжелее. Сбавь темп, это нормально."
            d.run == Run.RACE || d.run == Run.TRIAL -> tips += "Можно и перенести зал на завтра — результат важнее."
        }
        if (d.legsHeavyFrom != null && d.run != Run.EASY && d.run != Run.LONG) {
            tips += "Ноги ещё не отошли после тренировки ${d.legsHeavyFrom}. Разминайся дольше, отрезки — по самочувствию."
        }
        if (d.fatigue == Fatigue.TIRED) tips += when {
            d.run == Run.RACE || d.run == Run.TRIAL -> "Ты сейчас устал. После бега на результат зал сегодня лучше пропустить."
            d.run == Run.HARD && !runFirst -> "Ты сейчас устал. Зал — первым, а тяжёлый бег сегодня лучше заменить лёгким."
            d.run == Run.HARD -> "Ты сейчас устал: «Свежесть» ниже твоей линии отдыха. Главное сегодня — бег. Зал после сделай короче, последние упражнения можно пропустить."
            else -> "Ты сейчас устал. Главное — зал. Лёгкий бег сократи до 20 минут или пропусти: отдых важнее."
        }
        if (d.gymLight) tips += "Зал сегодня лёгкий, так что порядок не так важен: бегу он почти не помешает."
        return Advice(runFirst, why, tips)
    }

    /**
     * One of the two done, the other ahead: how to run or lift after it. [runDone]: the run was first. Null when there is
     * nothing to adjust.
     */
    fun afterFirst(d: Day, runDone: Boolean): String? = when {
        !runDone && d.legs == Legs.HEAVY && d.run != Run.EASY && d.run != Run.LONG ->
            "Ноги уже сделаны, так что тяжёлый бег — по самочувствию. Разминайся дольше, отрезки можно сократить. Темп — не быстрее плана."
        !runDone && d.run == Run.EASY -> "Зал сделан. Теперь лёгкий бег как заминка: спокойно, чтобы можно было говорить."
        runDone && d.legs == Legs.HEAVY && d.run != Run.EASY ->
            "Бег сделан. В упражнениях на ноги оставляй на 1 повтор больше запаса: после бега ноги слабее. Веса подстроятся сами."
        runDone && (d.run == Run.RACE || d.run == Run.TRIAL) -> "Бег на результат позади. Зал сегодня — коротко и легко, или перенеси его на завтра."
        else -> null
    }

    private fun paren(names: String) = if (names.isEmpty()) "" else " ($names)"

    /** The order in words: "Сначала бег, потом зал." */
    fun words(a: Advice): String = if (a.runFirst) "Сначала бег, потом зал." else "Сначала зал, потом бег."
}
