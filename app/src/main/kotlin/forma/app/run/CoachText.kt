package forma.app.run

import forma.core.run.Cue
import forma.core.run.RunEvent
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.Segment
import forma.core.run.SegmentKind
import forma.core.text.Ru

/** What the coach says. Short sentences: they are heard while running. */
object CoachText {
    fun segmentName(w: RunWorkout, index: Int): String {
        val s = w.segments.getOrNull(index) ?: return ""
        return when (s.kind) {
            SegmentKind.WARMUP -> if (w.type == RunType.RUN_WALK) "Разминка шагом" else "Разминка"
            SegmentKind.COOLDOWN -> if (w.type == RunType.RUN_WALK) "Заминка шагом" else "Заминка"
            SegmentKind.WALK -> "Шаг"
            SegmentKind.RECOVERY -> "Отдых трусцой"
            SegmentKind.RUN -> when (w.type) {
                RunType.LONG -> "Длинная пробежка"
                RunType.EASY -> "Лёгкий бег"
                else -> "Бег"
            }
            SegmentKind.WORK -> {
                val works = w.segments.withIndex().filter { it.value.kind == SegmentKind.WORK }
                val n = works.indexOfFirst { it.index == index } + 1
                if (w.type == RunType.TEST) "Контрольный отрезок" else if (works.size > 1) "Ускорение $n из ${works.size}" else "Темповый отрезок"
            }
        }
    }

    private fun hint(s: Segment): String = when (s.kind) {
        SegmentKind.WARMUP, SegmentKind.COOLDOWN, SegmentKind.RECOVERY -> "Спокойно."
        SegmentKind.WALK -> "Быстрым шагом."
        SegmentKind.RUN -> "Разговорный темп."
        SegmentKind.WORK -> "Ровно и сильно."
    }

    fun say(w: RunWorkout, e: RunEvent): String? = when (e) {
        is RunEvent.Kilometre -> "Километр ${e.km}. Темп ${paceWords(e.splitSec)}."
        is RunEvent.Timeline -> when (val c = e.cue) {
            is Cue.SegmentStart -> "${segmentName(w, c.index)}, ${Ru.durationWords(c.segment.seconds)}. ${hint(c.segment)}"
            is Cue.Halfway -> "Половина отрезка."
            is Cue.MinuteLeft -> "Осталась минута."
            is Cue.Countdown -> null
            is Cue.Finished -> "Тренировка завершена. Можно остановиться."
        }
    }

    /** "5 минут 12 секунд" for a pace of 312 s/km. */
    fun paceWords(secPerKm: Int): String = Ru.durationWords(secPerKm)
}
