package forma.core.run

import kotlin.math.ceil

/**
 * Lap times from a watch, typed after a run without the phone ("52 51 53"): read, and given to the reps as the lap button
 * would — each rep takes as many laps as it has (a rep of 6.5 laps starts with the half), with the same targets; laps
 * beyond the reps, or of a run without reps in laps, go in order outside them.
 */
object ManualLaps {
    /**
     * Seconds of each lap from what was typed: spaces (or ";") between laps, a decimal comma or point ("52,5"), minutes
     * with a colon for long laps ("1:05"). Null when something typed is not a time; empty when nothing was typed.
     */
    fun parse(text: String): List<Double>? {
        val tokens = text.replace(", ", " ").replace("; ", " ").trim().split(Regex("[\\s;/|]+")).filter { it.isNotEmpty() }
        val out = tokens.map { token ->
            val parts = token.replace(',', '.').split(':')
            val sec = when (parts.size) {
                1 -> parts[0].toDoubleOrNull()
                2 -> parts[0].toIntOrNull()?.let { m -> parts[1].toDoubleOrNull()?.takeIf { it < 60 }?.let { m * 60 + it } }
                else -> null
            }
            sec?.takeIf { it > 0 } ?: return null
        }
        return out
    }

    /** The laps typed, in order, as the lap button would have made them in [w]. */
    fun assign(w: RunWorkout, seconds: List<Double>): List<Lap> {
        val lapM = w.lapMeters
        val out = mutableListOf<Lap>()
        var k = 0
        var repNo = 0
        for (seg in w.segments) {
            if (seg.kind != SegmentKind.WORK) continue
            repNo++
            val laps = seg.laps ?: continue
            val of = ceil(laps).toInt()
            for (j in 0 until of) {
                if (k >= seconds.size) return out
                val part = if (j == 0 && laps % 1.0 != 0.0) laps % 1.0 else 1.0
                val target = seg.lapSec?.times(part)
                val range = seg.pace?.let { p -> lapM?.let { Track.lapSec(p.fast.toDouble(), it) * part to Track.lapSec(p.slow.toDouble(), it) * part } }
                // A time trial is run evenly at first; in its second half faster is what it is for.
                val free = w.testMeters != null && (j + 1) * 2 > of
                out += Lap(seconds[k++], if (free) null else range?.first ?: target, range?.second ?: target, j + 1, of, repNo, part)
            }
        }
        var n = 0
        while (k < seconds.size) out += Lap(seconds[k++], number = ++n)
        return out
    }

    /** The reps' pace from their laps, seconds a km (the half lap a rep may start with counts as a half); null without them. */
    fun repPace(laps: List<Lap>, lapM: Int): Double? {
        val reps = laps.filter { it.rep != null }
        if (reps.isEmpty()) return null
        return reps.sumOf { it.seconds } / (reps.sumOf { it.part } * lapM / 1000.0)
    }

    /** Reps whose every lap is there. */
    fun repsDone(laps: List<Lap>): Int = laps.count { it.closesRep }
}
