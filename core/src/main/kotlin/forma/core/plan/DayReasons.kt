package forma.core.plan

/**
 * Why the sessions of a day sit on it: the rules the week was laid out by ([Scheduler]), said of that one day — a hard
 * run on an upper-body day or a day without the gym, the legs on a day of easy running, the order within the day, and
 * what the days before and after hold.
 */
object DayReasons {
    /**
     * [hard]: whether a run is a hard one as things stand — after a long break the hard sessions are easy runs for a while,
     * and before running continuously every run is run/walk; the reasons follow what the day holds now.
     */
    fun of(program: Program, day: Int, hard: (PlannedSession) -> Boolean = { it.isHardRun }): List<String> {
        val today = program.week.on(day)
        if (today.isEmpty()) return emptyList()
        // Whose week has no running hears nothing of runs, and whose has no gym nothing of the gym.
        val sessions = program.week.days.flatMap { it.sessions }
        val runs = sessions.any { it.isRun }
        val gym = sessions.any { it.kind == SessionKind.LIFT }
        val prev = program.week.on(if (day == 1) 7 else day - 1)
        val next = program.week.on(if (day == 7) 1 else day + 1)
        fun template(s: PlannedSession) = program.templates.firstOrNull { it.id == s.templateId }
        fun legs(list: List<PlannedSession>) = list.any { it.kind == SessionKind.LIFT && template(it)?.legs != false }
        val lift = today.firstOrNull { it.kind == SessionKind.LIFT }
        val run = today.firstOrNull { it.isRun }
        val legDay = lift != null && template(lift)?.legs != false
        val runFirst = today.first().isRun
        val out = mutableListOf<String>()
        if (run != null && hard(run)) {
            out += when {
                lift == null && !gym -> "Тяжёлому бегу нужны свежие ноги: накануне и назавтра — без тяжёлого бега."
                lift == null -> "День без зала: все силы — на бег, тяжёлому бегу нужны свежие ноги."
                !legDay -> "Тяжёлый бег — в день верха: руки и спина ему не мешают. " +
                    if (runFirst) "Сначала бег, пока ноги свежие, потом зал." else "Сначала зал, потом бег."
                else -> "Тяжёлый бег и зал в один день: " + if (runFirst) "сначала бег, потом зал." else "сначала зал, потом бег."
            }
            if (gym) {
                out += if (legs(prev)) "Накануне — ноги: если они тяжёлые, сделай разминку длиннее и спокойнее."
                else "Накануне нет тренировки ног: бег будет качественнее."
            }
            when {
                next.isEmpty() -> out += "Завтра — отдых: тело восстановится после тяжёлой работы."
                next.none { hard(it) } && !legs(next) ->
                    out += if (gym) "Завтра — ни тяжёлого бега, ни ног: ногам есть время восстановиться."
                    else "Завтра — без тяжёлого бега: ногам есть время восстановиться."
            }
            if (run.kind == SessionKind.RUN_LONG && day >= 6) out += "Длинная — в выходной: на неё больше времени."
        } else if (run != null) {
            out += when {
                lift == null && !gym -> "Лёгкий день: аэробная база без лишней усталости."
                lift == null -> "Отдельный лёгкий день: аэробная база без лишней усталости."
                legDay -> "Лёгкий бег — в день ног: тяжёлый бег в этот день не ставится, а лёгкий почти не мешает ногам " +
                    "восстанавливаться. " + if (runFirst) "Сначала бег, потом зал." else "Сначала зал, потом бег."
                else -> "Лёгкий бег в день верха: зал в этот день ноги не нагружает, а лёгкий бег не мешает верху. " +
                    if (runFirst) "Сначала бег, потом зал." else "Сначала зал, потом бег."
            }
        }
        if (lift != null && run == null && legDay && runs) {
            out += if (next.any { hard(it) }) "Завтра тяжёлый бег — ноги сегодня не до отказа."
            else "Ноги — не накануне тяжёлого бега: к нему они успеют восстановиться."
        }
        if (lift != null && run == null && legDay && !runs && !legs(prev) && !legs(next)) {
            out += "Ноги — не два дня подряд: между тренировками ног мышцы успевают восстановиться."
        }
        return out
    }
}
