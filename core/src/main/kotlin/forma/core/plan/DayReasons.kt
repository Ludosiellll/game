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
        val out = mutableListOf<String>()
        if (run != null && hard(run)) {
            // The days around said as they are: a run-only week may hold two hard days in a row.
            val prevHard = prev.any { hard(it) }
            val nextHard = next.any { hard(it) }
            out += when {
                lift == null && !gym && prevHard -> "Накануне тоже тяжёлый бег. Разминайся дольше и спокойнее."
                lift == null && !gym && nextHard -> "Тяжёлому бегу нужны свежие ноги. Поэтому накануне тяжёлого бега нет."
                lift == null && !gym -> "Тяжёлому бегу нужны свежие ноги. Поэтому за день до и на следующий день тяжёлого бега нет."
                lift == null -> "В этот день нет зала. Все силы — на бег: тяжёлому бегу нужны свежие ноги."
                // Which first — said with its reasons in the day's order card (SessionOrder), not here.
                !legDay -> "Тяжёлый бег — в день верха тела. Зал для рук и спины бегу не мешает."
                else -> "В этот день и тяжёлый бег, и зал. Что делать первым — в карточке «Порядок»."
            }
            if (gym) {
                out += if (legs(prev)) "Накануне тренировка ног. Если ноги тяжёлые, разминайся дольше и спокойнее."
                else "Накануне нет тренировки ног, так что бежать будет легче."
            }
            when {
                next.isEmpty() -> out += "Завтра отдых: тело восстановится после тяжёлой работы."
                next.none { hard(it) } && !legs(next) ->
                    out += if (gym) "Завтра нет ни тяжёлого бега, ни ног в зале. Ноги успеют восстановиться."
                    else "Завтра без тяжёлого бега. Ноги успеют восстановиться."
            }
            if (run.kind == SessionKind.RUN_LONG && day >= 6) out += "Длинная пробежка — в выходной, когда больше времени."
        } else if (run != null) {
            out += when {
                lift == null && !gym -> "Лёгкий день. Спокойный бег растит выносливость и почти не утомляет."
                lift == null -> "Лёгкий бег в отдельный день. Он растит выносливость и почти не утомляет."
                legDay -> "Лёгкий бег — в день ног. Тяжёлый бег сюда не ставим, а лёгкий почти не мешает ногам " +
                    "восстанавливаться."
                else -> "Лёгкий бег — в день верха тела. Зал в этот день не трогает ноги, а бег не мешает рукам и спине."
            }
        }
        if (lift != null && run == null && legDay && runs) {
            out += if (next.any { hard(it) }) "Завтра тяжёлый бег. Ноги сегодня — с запасом, не до отказа."
            else "Тренировка ног не стоит накануне тяжёлого бега. К нему ноги успеют восстановиться."
        }
        if (lift != null && run == null && legDay && !runs && !legs(prev) && !legs(next)) {
            out += "Тренировки ног не идут два дня подряд, так что мышцы успевают восстановиться."
        }
        return out
    }
}
