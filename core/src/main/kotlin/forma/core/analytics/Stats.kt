package forma.core.analytics

import forma.core.model.Catalog
import forma.core.model.Exercise
import forma.core.model.LoadKind
import forma.core.model.Muscle
import forma.core.strength.OneRm
import forma.core.strength.Progression
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.exp
import kotlin.math.ln

/** A logged working set with the day it was done. */
data class SetRow(
    val workoutId: Long,
    val epochDay: Long,
    val exerciseId: String,
    val weight: Double,
    val reps: Int,
    val rir: Int?,
    val targetReps: Int?,
)

data class Point(val epochDay: Long, val value: Double)

data class Records(val bestE1rm: Double, val heaviest: Double, val mostReps: Int, val sessions: Int)

object Stats {
    private fun rirOf(r: SetRow, plannedRir: Int = 2) = r.rir ?: if (r.targetReps != null && r.reps >= r.targetReps) plannedRir else 0

    /** Best e1RM of each session for one exercise. */
    fun e1rmSeries(rows: List<SetRow>, ex: Exercise, bodyWeight: (Long) -> Double): List<Point> =
        rows.filter { it.exerciseId == ex.id && it.reps > 0 && ex.load != LoadKind.TIMED }
            .groupBy { it.workoutId }
            .map { (_, sets) ->
                val day = sets.first().epochDay
                Point(day, sets.maxOf { OneRm.estimate(Progression.effectiveLoad(ex, it.weight, bodyWeight(day)), it.reps, rirOf(it)) })
            }
            .sortedBy { it.epochDay }

    fun records(rows: List<SetRow>, ex: Exercise, bodyWeight: (Long) -> Double): Records? {
        val mine = rows.filter { it.exerciseId == ex.id && it.reps > 0 }
        if (mine.isEmpty()) return null
        val series = e1rmSeries(mine, ex, bodyWeight)
        return Records(
            bestE1rm = series.maxOfOrNull { it.value } ?: 0.0,
            heaviest = mine.maxOf { it.weight },
            mostReps = mine.maxOf { it.reps },
            sessions = mine.map { it.workoutId }.distinct().size,
        )
    }

    fun monday(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Hard sets per muscle for each of the last [weeks] weeks (oldest first). A set counts fully for primary
     * muscles and half for secondary ones — the usual way to count weekly volume.
     */
    fun weeklySets(rows: List<SetRow>, today: LocalDate, weeks: Int): Map<Muscle, List<Double>> {
        val start = monday(today).minusWeeks((weeks - 1).toLong())
        val out = Muscle.entries.associateWith { DoubleArray(weeks) }
        for (r in rows) {
            val ex = Catalog.find(r.exerciseId) ?: continue
            val w = ((r.epochDay - start.toEpochDay()) / 7).toInt()
            if (w !in 0 until weeks || r.epochDay < start.toEpochDay()) continue
            ex.primary.forEach { out.getValue(it)[w] += 1.0 }
            ex.secondary.forEach { out.getValue(it)[w] += 0.5 }
        }
        return out.mapValues { it.value.toList() }
    }

    /** Kilograms moved per week (weight × reps; both dumbbells; body weight for body-weight lifts). */
    fun weeklyTonnage(rows: List<SetRow>, today: LocalDate, weeks: Int, bodyWeight: (Long) -> Double): List<Double> {
        val start = monday(today).minusWeeks((weeks - 1).toLong())
        val out = DoubleArray(weeks)
        for (r in rows) {
            val ex = Catalog.find(r.exerciseId) ?: continue
            if (ex.load == LoadKind.TIMED || r.epochDay < start.toEpochDay()) continue
            val w = ((r.epochDay - start.toEpochDay()) / 7).toInt()
            if (w !in 0 until weeks) continue
            val load = Progression.effectiveLoad(ex, r.weight, bodyWeight(r.epochDay)) * if (ex.perHand) 2 else 1
            out[w] += load * r.reps
        }
        return out.toList()
    }

    /**
     * Strength index: 100 at the start, then the geometric mean of (current e1RM / first e1RM) over every
     * exercise with history, sampled weekly. "+12 %" means lifts are on average 12 % stronger.
     */
    fun strengthIndex(rows: List<SetRow>, bodyWeight: (Long) -> Double): List<Point> {
        val byEx = rows.groupBy { it.exerciseId }.mapNotNull { (id, r) ->
            val ex = Catalog.find(id) ?: return@mapNotNull null
            val s = e1rmSeries(r, ex, bodyWeight)
            if (s.isEmpty()) null else s
        }
        if (byEx.isEmpty()) return emptyList()
        val first = byEx.minOf { it.first().epochDay }
        val last = byEx.maxOf { it.last().epochDay }
        val out = mutableListOf<Point>()
        var day = monday(LocalDate.ofEpochDay(first)).plusDays(6).toEpochDay()
        while (true) {
            val ratios = byEx.mapNotNull { s ->
                val upTo = s.filter { it.epochDay <= day }
                if (upTo.isEmpty()) null else upTo.takeLast(2).maxOf { it.value } / s.first().value
            }
            if (ratios.isNotEmpty()) out += Point(minOf(day, last), 100 * exp(ratios.sumOf { ln(it) } / ratios.size))
            if (day >= last) break
            day += 7
        }
        return out
    }

    /** Change of the last value against the first, in percent. */
    fun changePct(series: List<Point>): Double? =
        if (series.size < 2 || series.first().value == 0.0) null else (series.last().value / series.first().value - 1) * 100
}
