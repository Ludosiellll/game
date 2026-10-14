package forma.core

import forma.core.analytics.Point
import forma.core.analytics.SetRow
import forma.core.analytics.Stats
import forma.core.model.Catalog
import forma.core.model.Muscle
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsTest {
    private val day0 = LocalDate.of(2026, 9, 7).toEpochDay() // Monday
    private fun row(w: Long, d: Long, ex: String, kg: Double, reps: Int) = SetRow(w, day0 + d, ex, kg, reps, null, reps)

    @Test fun e1rmSeriesTakesTheBestSetPerSession() {
        val rows = listOf(row(1, 0, "squat", 60.0, 8), row(1, 0, "squat", 60.0, 6), row(2, 3, "squat", 62.5, 8))
        val s = Stats.e1rmSeries(rows, Catalog["squat"]) { 80.0 }
        assertEquals(2, s.size)
        assertTrue(s[1].value > s[0].value)
    }

    @Test fun weeklySetsCountPrimaryAndHalfSecondary() {
        val today = LocalDate.ofEpochDay(day0 + 10)
        val rows = List(3) { row(1, 7, "bench", 60.0, 8) }
        val v = Stats.weeklySets(rows, today, 2)
        assertEquals(listOf(0.0, 3.0), v[Muscle.CHEST])
        assertEquals(listOf(0.0, 1.5), v[Muscle.TRICEPS])
    }

    @Test fun tonnageCountsBothDumbbells() {
        val today = LocalDate.ofEpochDay(day0 + 2)
        val t = Stats.weeklyTonnage(listOf(row(1, 1, "db_bench", 20.0, 10)), today, 1) { 80.0 }
        assertEquals(listOf(400.0), t)
    }

    @Test fun sessionsWithNoLoadDoNotBreakTheNumbers() {
        // A dumbbell exercise done without dumbbells (0 kg) has no estimate: no NaN, no division by zero.
        val rows = (0 until 3).flatMap { i -> listOf(row(i.toLong(), i * 7L, "bulgarian", 0.0, 10), row(i.toLong(), i * 7L, "squat", 60.0 + 2.5 * i, 8)) }
        assertTrue(Stats.e1rmSeries(rows, Catalog["bulgarian"]) { 80.0 }.isEmpty())
        val idx = Stats.strengthIndex(rows) { 80.0 }
        assertTrue(idx.isNotEmpty() && idx.all { it.value.isFinite() })
        assertTrue(Stats.changePct(idx)!!.isFinite())
        assertTrue(Stats.strengthIndex(rows.filter { it.exerciseId == "bulgarian" }) { 80.0 }.isEmpty())
        assertNull(Stats.changePct(listOf(Point(0, 0.0), Point(7, 10.0))))
    }

    /** Back after two months off at lower weights: the drop shows, and the old weights regained bring it back — not "+25 %". */
    @Test fun aReturnAfterABreakIsSetAgainstTheLastValueBeforeIt() {
        val before = (0 until 4).map { i -> row(i.toLong(), i * 7L, "squat", 100.0, 5) }
        val after = listOf(80.0, 85.0, 90.0, 95.0, 100.0).mapIndexed { i, kg -> row(10L + i, 84L + i * 7, "squat", kg, 5) }
        val idx = Stats.strengthIndex(before + after) { 80.0 }
        assertTrue(idx.minOf { it.value } < 85, idx.toString())
        assertEquals(100.0, idx.last().value, 1e-6)
        assertEquals(0.0, Stats.changePct(idx)!!, 1e-6)
    }

    @Test fun strengthIndexStartsAtHundredAndGrows() {
        val rows = (0 until 6).flatMap { i -> listOf(row(i.toLong(), i * 7L, "squat", 60.0 + 2.5 * i, 8), row(i.toLong(), i * 7L, "bench", 50.0 + 1.25 * i, 8)) }
        val idx = Stats.strengthIndex(rows) { 80.0 }
        assertEquals(100.0, idx.first().value, 1e-9)
        assertTrue(idx.last().value > 110)
        assertTrue(Stats.changePct(idx)!! > 10)
    }
}
