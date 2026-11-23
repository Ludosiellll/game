package forma.app.ui

import forma.core.run.PaceRange
import forma.core.run.RunExplain
import forma.core.run.RunReview
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import forma.core.run.Track
import forma.core.text.Ru

/**
 * How to run a session, in a line: the lap time or pace to keep, the warm-up and the rest. It always stands under the
 * session's title, so it leaves out what the title says (23 November: «Темп цели · 3 × 3 круга» over «Разминка 12:00,
 * 3 × 3 круга, …»); empty when nothing is left to say. Minutes as minutes, never as a clock («Разминка 12:00» read as a
 * warm-up at noon).
 */
object RunHow {
    /** A number and its unit stay on one line: «48–49 с» never leaves «с» alone at the start of the next. */
    private val UNIT = Regex("(\\d) (с|мин|км|м|/км)(?![а-яё])")

    fun short(w: RunWorkout): String = line(w).replace(UNIT, "$1\u00A0$2")

    /**
     * Under the line before the start: what it leaves out — the cool-down, the warm-up where the line has none — the
     * whole time and the effort meant: «Заминка 8 мин · всего 31 мин · тяжесть 7 из 10». An easy or long run is its
     * running alone and its title has the minutes: «Тяжесть до 5 из 10».
     */
    fun totals(w: RunWorkout): String {
        // The line says the warm-up of a session of reps; a trial's or run/walk's is said here.
        val lineHasWarmUp = (w.type == RunType.GOAL_PACE || w.type == RunType.INTERVALS || w.type == RunType.TEMPO) &&
            w.segments.any { it.kind == SegmentKind.WORK }
        val warm = w.segments.firstOrNull { it.kind == SegmentKind.WARMUP }?.takeIf { !lineHasWarmUp }
        val cool = w.segments.lastOrNull { it.kind == SegmentKind.COOLDOWN }
        val ends = listOfNotNull(
            warm?.let { "разминка ${RunExplain.timeWords(it.seconds)}" },
            cool?.let { "заминка ${RunExplain.timeWords(it.seconds)}" },
        ).joinToString(", ")
        val whole = if (w.segments.any { it.kind != SegmentKind.RUN }) "всего ${w.totalSeconds / 60} мин" else null
        // As the debrief reads it after the run: easy, long and run/walk up to their limit, the others at their mark.
        val effort = RunReview.ratingLimit(w.type)?.let { "тяжесть до $it\u00A0из\u00A010" } ?: "тяжесть ${w.targetRpe}\u00A0из\u00A010"
        return listOfNotNull(ends.ifEmpty { null }, whole, effort).joinToString(" · ")
            .replaceFirstChar { it.uppercase() }.replace(UNIT, "$1\u00A0$2")
    }

    private fun line(w: RunWorkout): String {
        val lapM = w.lapMeters
        val work = w.segments.filter { it.kind == SegmentKind.WORK }
        val rest = w.segments.filter { it.kind == SegmentKind.RECOVERY }
        // The reps' lap time on a stadium; on the road their pace, named so it is not read as the warm-up's.
        fun range(p: PaceRange): String =
            if (lapM != null) "круг ${Ru.lapRange(Track.lapSec(p.fast.toDouble(), lapM), Track.lapSec(p.slow.toDouble(), lapM))}"
            else "${if (work.size == 1) "отрезок" else "отрезки"} ${Ru.pace(p.fast.toDouble())}–${Ru.pace(p.slow.toDouble())} /км"
        // Only the fast limit is a limit for easy running: slower is fine.
        fun notFaster(p: PaceRange): String =
            if (lapM != null) "круг не быстрее ${Ru.lap(Track.lapSec(p.fast.toDouble(), lapM))}" else "не быстрее ${Ru.pace(p.fast.toDouble())} /км"
        return when (w.type) {
            RunType.FREE -> "Как захочется: время, дистанция и круги запишутся."
            // The minutes are in the title («Лёгкий бег · 28 мин»): the limit alone.
            RunType.EASY, RunType.LONG ->
                w.segments.firstOrNull { it.kind == SegmentKind.RUN }?.pace?.let(::notFaster)?.replaceFirstChar { it.uppercase() } ?: ""
            RunType.RUN_WALK -> {
                val run = w.segments.filter { it.kind == SegmentKind.RUN }
                val walk = w.segments.firstOrNull { it.kind == SegmentKind.WALK }
                val first = run.firstOrNull()
                if (first == null) "Бег с ходьбой"
                else "Бег ${Ru.clock(first.seconds)}" + (walk?.let { " / ходьба ${Ru.clock(it.seconds)}" } ?: "") + " × ${run.size}, бег спокойный"
            }
            // The reps are in the title («Темп цели · 3 × 3 круга»): the lap time or pace to keep, the warm-up and the rest.
            RunType.GOAL_PACE, RunType.INTERVALS, RunType.TEMPO -> {
                val first = work.firstOrNull() ?: return w.note
                listOfNotNull(
                    w.segments.firstOrNull { it.kind == SegmentKind.WARMUP }?.let { "разминка ${RunExplain.timeWords(it.seconds)}" },
                    first.pace?.let(::range),
                    rest.firstOrNull()?.let { "отдых ${RunExplain.timeWords(it.seconds)}" },
                ).joinToString(", ").replaceFirstChar { it.uppercase() }
            }
            RunType.TEST -> {
                val first = work.firstOrNull()
                when {
                    w.testMeters == null -> "12 минут как можно дальше, ровно, без рывка в начале"
                    first?.lapSec != null -> "В полную силу, но ровно: первые круги по ${Ru.lap(first.lapSec!!)}, со второй половины — быстрее, если есть силы"
                    else -> "В полную силу, но ровно: первая половина по ${first?.pace?.let { Ru.pace((it.fast + it.slow) / 2.0) } ?: "–"} на км"
                }
            }
        }
    }
}
