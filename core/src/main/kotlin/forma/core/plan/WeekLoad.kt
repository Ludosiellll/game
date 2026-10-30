package forma.core.plan

/**
 * How hard a week is as it is laid out, said plainly: a program put together from two (someone's gym and one's own
 * running, or the other way round) can ask more than either did. A hard day has a hard run (intervals, tempo, race
 * pace, a long run) or a gym session that works the legs; the legs need about two days to be fresh for a hard run, and
 * the body a day off in the week. Two ways to make it lighter: one hard run easy, one gym day less.
 */
object WeekLoad {
    data class Load(val sessions: Int, val restDays: Int, val hardDays: Int, val warnings: List<String>) {
        val tooMuch: Boolean get() = warnings.isNotEmpty()
    }

    private val NAMES = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

    /** More sessions than this in a week is a lot for anyone training the gym and running together. */
    const val MAX_SESSIONS = 8

    /** More hard days than this in a week leave too little to recover in. */
    const val MAX_HARD_DAYS = 4

    private fun legs(s: PlannedSession, templates: List<Template>) =
        s.kind == SessionKind.LIFT && templates.firstOrNull { it.id == s.templateId }?.legs != false

    private fun sessionsOn(week: WeekPlan, day: Int) = week.on(day)

    private fun hard(week: WeekPlan, templates: List<Template>, day: Int) =
        sessionsOn(week, day).any { it.isHardRun || legs(it, templates) }

    fun of(week: WeekPlan, templates: List<Template>): Load {
        val days = 1..7
        val sessions = days.sumOf { sessionsOn(week, it).size }
        val rest = days.count { sessionsOn(week, it).isEmpty() }
        val hardDays = days.filter { hard(week, templates, it) }
        val warnings = mutableListOf<String>()
        if (sessions > MAX_SESSIONS) warnings += "$sessions тренировок в неделю, это много."
        if (rest == 0 && sessions > 0) warnings += "Нет ни одного дня отдыха."
        if (hardDays.size > MAX_HARD_DAYS) warnings += "Тяжёлых дней ${hardDays.size} из 7, восстановиться некогда."
        // Three hard days in a row, the week around (Sunday then Monday).
        val row = days.firstOrNull { d -> (0..2).all { hard(week, templates, (d - 1 + it) % 7 + 1) } }
        if (row != null && hardDays.size < 7) warnings += "Три тяжёлых дня подряд: ${(0..2).joinToString(", ") { NAMES[(row - 1 + it) % 7] }}."
        for (d in days) {
            val next = d % 7 + 1
            val legsToday = sessionsOn(week, d).any { legs(it, templates) }
            if (legsToday && sessionsOn(week, d).any { it.isHardRun }) warnings += "В ${NAMES[d - 1]} и ноги, и тяжёлый бег."
            if (legsToday && sessionsOn(week, next).any { it.isHardRun }) warnings += "Ноги в ${NAMES[d - 1]}, а в ${NAMES[next - 1]} тяжёлый бег."
        }
        return Load(sessions, rest, hardDays.size, warnings)
    }

    /**
     * One hard run made easy: the one right after a legs day (or on it) first, else the last hard run of the week.
     * Null when there is no hard run.
     */
    fun easeRun(week: WeekPlan, templates: List<Template>): WeekPlan? {
        val hardRuns = (1..7).filter { d -> sessionsOn(week, d).any { it.isHardRun } }
        if (hardRuns.isEmpty()) return null
        val prev = { d: Int -> (d + 5) % 7 + 1 }
        val day = hardRuns.firstOrNull { d -> (sessionsOn(week, prev(d)) + sessionsOn(week, d)).any { legs(it, templates) } } ?: hardRuns.last()
        return week.copy(days = week.days.map { dp ->
            val i = dp.sessions.indexOfFirst { it.isHardRun }
            if (dp.day != day) dp else dp.copy(sessions = dp.sessions.mapIndexed { j, s -> if (j == i) s.copy(kind = SessionKind.RUN_EASY) else s })
        })
    }

    /**
     * One gym day less: the legs session before a hard run (or on its day) first, else the last gym session of the week.
     * Null when there is one gym session or none (the gym would be gone from the week).
     */
    fun dropLift(week: WeekPlan, templates: List<Template>): WeekPlan? {
        val lifts = (1..7).filter { d -> sessionsOn(week, d).any { it.kind == SessionKind.LIFT } }
        if (lifts.size < 2) return null
        val next = { d: Int -> d % 7 + 1 }
        val day = lifts.firstOrNull { d ->
            sessionsOn(week, d).any { legs(it, templates) } && (sessionsOn(week, d) + sessionsOn(week, next(d))).any { it.isHardRun }
        } ?: lifts.last()
        return week.copy(days = week.days.map { dp ->
            val i = dp.sessions.indexOfFirst { it.kind == SessionKind.LIFT }
            if (dp.day != day) dp else dp.copy(sessions = dp.sessions.filterIndexed { j, _ -> j != i })
        })
    }
}
