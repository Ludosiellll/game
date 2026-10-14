package forma.app.run

import forma.core.run.Cue
import forma.core.run.Lap
import forma.core.run.RunEvent
import forma.core.run.RunPlan
import forma.core.run.RunTimeline
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.Segment
import forma.core.run.SegmentKind
import forma.core.run.Track
import forma.core.text.Ru
import kotlin.math.roundToInt

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
                when {
                    w.type == RunType.TEST && w.testMeters != null -> "Контрольный забег"
                    w.type == RunType.TEST -> "Контрольный отрезок"
                    w.type == RunType.GOAL_PACE -> if (works.size > 1) "Отрезок $n из ${works.size}" else "Отрезок"
                    w.type == RunType.TEMPO -> if (works.size > 1) "Темповый отрезок $n из ${works.size}" else "Темповый отрезок"
                    works.size > 1 -> "Ускорение $n из ${works.size}"
                    else -> "Ускорение"
                }
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
        // On a track the laps speak; GPS kilometres on a small loop are noise.
        is RunEvent.Kilometre -> if (w.lapMeters != null) null else "Километр ${e.km}. Темп ${paceWords(e.splitSec)}."
        is RunEvent.Timeline -> when (val c = e.cue) {
            is Cue.SegmentStart -> {
                val s = c.segment
                val laps = s.laps
                val lapSec = s.lapSec
                val lapM = w.lapMeters
                val pace = s.pace
                when {
                    laps != null && lapSec != null -> "${segmentName(w, c.index)}: ${Ru.laps(laps)}, круг за ${lapSec.roundToInt()}. ${hint(s)}"
                    // An easy run on a stadium: the lap time it should not be faster than.
                    s.kind == SegmentKind.RUN && lapM != null && pace != null ->
                        "${segmentName(w, c.index)}, ${Ru.durationWords(s.seconds)}. ${hint(s)} Круг — не быстрее ${Track.lapSec(pace.fast.toDouble(), lapM).roundToInt()} секунд."
                    else -> "${segmentName(w, c.index)}, ${Ru.durationWords(s.seconds)}. ${hint(s)}"
                }
            }
            is Cue.Halfway -> "Половина отрезка."
            is Cue.MinuteLeft ->
                if (RunTimeline.startOnLine(w) == c.index + 1) "Через минуту ${startWhat(w)}. Закончи круг и подходи к старту." else "Осталась минута."
            is Cue.ToLine -> "Разминка закончена. ${startWhere(w, c.index)} и нажми «Круг», когда побежишь: ${startWhat(w)} начнётся с нажатия."
            is Cue.Strides -> "Последние три минуты разминки: сделай 3–4 ускорения по 15 секунд, между ними трусца."
            is Cue.Countdown, is Cue.LapMark -> null
            is Cue.Finished ->
                if (w.continuable) "План выполнен. Можно закончить или бежать дальше — время идёт, лишнее засчитается."
                else "Тренировка завершена. Можно остановиться."
        }
    }

    /**
     * After the lap button: the lap's time and whether it is on target ("52. В темпе."). The press that ends a time trial
     * says its result; [repSec] is the rep's time so far.
     */
    fun lap(w: RunWorkout, l: Lap, repSec: Double?): String {
        if (w.testMeters != null && l.closesRep && repSec != null) return "Финиш. ${Ru.durationWords(repSec.roundToInt())}."
        val t = l.seconds.roundToInt()
        val time = if (t < 100) "$t" else Ru.durationWords(t)
        // The second half of a time trial: no "too fast" there.
        val push = w.testMeters != null && l.fast == null && l.slow != null
        return when (l.verdict) {
            0 -> if (push) "$time. Хорошо, можно прибавить." else "$time. В темпе."
            -1 -> if (l.closesRep) "$time. Быстрее цели." else "$time. Быстро, спокойнее."
            1 -> if (l.closesRep) "$time. Медленнее цели." else if (push) "$time. Прибавь." else "$time. Медленно, чуть прибавь."
            else -> "$time."
        }
    }

    /** What waits on the line: the time trial or the reps. */
    private fun startWhat(w: RunWorkout): String = if (w.testMeters != null) "забег" else "отрезки"

    /** Where the rep [index] starts: on the line, or part of a lap before it ("Встань на старт с середины круга"). */
    fun startWhere(w: RunWorkout, index: Int): String {
        val frac = w.segments.getOrNull(index)?.laps?.let { it % 1.0 }?.takeIf { it > 0.01 }
        return if (frac != null) "Встань на старт ${RunPlan.startWords(frac)}" else "Встань на линию"
    }

    /** A lap GPS counted because no press came: its rough time, and against the target when there is one. */
    fun autoLap(l: Lap): String = "Круг по GPS: ${l.seconds.roundToInt()}." + when (l.verdict) {
        0 -> " В темпе."
        -1 -> " Быстро."
        1 -> " Медленно."
        else -> ""
    }

    /** Off the pace on the road for a while: in a rep, on an easy run (only too fast matters there) or a free run's target. */
    fun pace(alert: forma.core.run.PaceAlarm.Alert, easy: Boolean): String = when (alert) {
        forma.core.run.PaceAlarm.Alert.FAST -> if (easy) "Быстро для лёгкого бега — сбавь." else "Быстрее цели — держи темп."
        forma.core.run.PaceAlarm.Alert.SLOW -> "Медленнее цели — прибавь."
    }

    /**
     * After the last rep of a session: the pressed laps on target, and the one thing to change if there is one. Null when
     * nothing was judged (no laps pressed, no target).
     */
    fun lapsSummary(w: RunWorkout, laps: List<Lap>): String? {
        if (w.type != RunType.GOAL_PACE && w.type != RunType.INTERVALS && w.type != RunType.TEMPO) return null
        val judged = laps.filter { it.rep != null && !it.auto && (it.fast != null || it.slow != null) }
        if (judged.size < 2) return null
        val on = judged.count { it.verdict == 0 }
        val fast = judged.count { it.verdict == -1 }
        val slow = judged.count { it.verdict == 1 }
        return "Кругов в темпе: $on из ${judged.size}." + when {
            fast * 10 >= judged.size * 3 -> " Много быстрых — в следующий раз ровнее, по писку."
            slow * 10 >= judged.size * 3 -> " Много медленных — темп пока тяжёл, это нормально."
            on == judged.size -> " Ровно, как надо."
            else -> ""
        } + " Подробный разбор — после сохранения."
    }

    /**
     * GPS gone on the road ([forma.core.run.GpsWatch]): lost for a while, or none at all since the system brought the run
     * back. The steps go on with the distance only when they give it ([bySteps]: the stride known, running cadence).
     */
    fun gps(say: forma.core.run.GpsWatch.Say, bySteps: Boolean): String = when (say) {
        forma.core.run.GpsWatch.Say.LOST -> if (bySteps) "GPS потерян, считаю по шагам." else "GPS потерян."
        forma.core.run.GpsWatch.Say.NONE -> if (bySteps) "GPS не отвечает, считаю по шагам." else "GPS не отвечает, дистанция пока не считается."
        forma.core.run.GpsWatch.Say.BACK -> "GPS снова есть."
    }

    /** Said every minute while the plan waits for the start press. */
    fun waitingReminder(w: RunWorkout): String = "Жду старта: нажми «Круг», когда побежишь."

    /** "5 минут 12 секунд" for a pace of 312 s/km. */
    fun paceWords(secPerKm: Int): String = Ru.durationWords(secPerKm)
}
