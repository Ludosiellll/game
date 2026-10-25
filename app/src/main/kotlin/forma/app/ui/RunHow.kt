package forma.app.ui

import forma.core.run.PaceRange
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import forma.core.run.Track
import forma.core.text.Ru

/** How to run a session, in a line: its length, its reps, and the lap time or pace to keep. */
object RunHow {
    fun short(w: RunWorkout): String {
        val lapM = w.lapMeters
        fun range(p: PaceRange): String =
            if (lapM != null) "круг ${Ru.lapRange(Track.lapSec(p.fast.toDouble(), lapM), Track.lapSec(p.slow.toDouble(), lapM))}"
            else "${Ru.pace(p.fast.toDouble())}–${Ru.pace(p.slow.toDouble())} /км"
        // Only the fast limit is a limit for easy running: slower is fine.
        fun notFaster(p: PaceRange): String =
            if (lapM != null) "круг не быстрее ${Ru.lap(Track.lapSec(p.fast.toDouble(), lapM))}" else "не быстрее ${Ru.pace(p.fast.toDouble())} /км"
        val work = w.segments.filter { it.kind == SegmentKind.WORK }
        val rest = w.segments.filter { it.kind == SegmentKind.RECOVERY }
        return when (w.type) {
            RunType.FREE -> "Как захочется: время, дистанция и круги запишутся."
            RunType.EASY, RunType.LONG -> {
                val run = w.segments.firstOrNull { it.kind == SegmentKind.RUN }
                listOfNotNull("${w.workSeconds / 60} мин спокойно, чтобы можно было говорить", run?.pace?.let(::notFaster)).joinToString(" · ")
            }
            RunType.RUN_WALK -> {
                val run = w.segments.filter { it.kind == SegmentKind.RUN }
                val walk = w.segments.firstOrNull { it.kind == SegmentKind.WALK }
                val first = run.firstOrNull()
                if (first == null) "Бег с ходьбой"
                else "Бег ${Ru.clock(first.seconds)}" + (walk?.let { " / ходьба ${Ru.clock(it.seconds)}" } ?: "") + " × ${run.size}, бег спокойный"
            }
            RunType.GOAL_PACE, RunType.INTERVALS, RunType.TEMPO -> {
                val first = work.firstOrNull() ?: return w.note
                val same = work.all { it.laps == first.laps && it.seconds == first.seconds }
                val len = first.laps?.let { Ru.laps(it) } ?: Ru.clock(first.seconds)
                val reps = if (same) "${work.size} × $len" else "${work.size} ${Ru.plural(work.size, "отрезок", "отрезка", "отрезков")}"
                val target = first.pace?.let { " — ${range(it)}" } ?: ""
                val r = rest.firstOrNull()?.let { ", отдых ${Ru.clock(it.seconds)}" } ?: ""
                val warm = w.segments.firstOrNull { it.kind == SegmentKind.WARMUP }?.let { "Разминка ${Ru.clock(it.seconds)}, " } ?: ""
                "$warm$reps$target$r"
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
