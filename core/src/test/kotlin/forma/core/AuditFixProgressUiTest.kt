package forma.core

import forma.core.analytics.Point
import forma.core.analytics.SetRow
import forma.core.analytics.Stats
import forma.core.model.Catalog
import forma.core.strength.DoneSet
import forma.core.strength.OneRm
import forma.core.strength.Prescription
import forma.core.strength.Progression
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Audit fixes of group I (progress screens): the one-rep number the charts and rows read. */
class AuditFixProgressUiTest {
    private val day0 = LocalDate.of(2026, 11, 17).toEpochDay()
    private val bench = Catalog["bench"]

    /**
     * progress-1rm-two-estimators: a session with the engine's own estimate is read at it. Bench 97,5 × 8, 8, 10 with the
     * last set «8+» and nothing marked: the engine stops the "+" set with one in reserve (11 reps to failure, 137,9 kg); the
     * series guessed two (12, 143,4 kg) and «Прогресс» said 11 kg more than the table, the coach and the export.
     */
    @Test fun aSessionIsReadAtTheEnginesOwnEstimate() {
        val p = Prescription(97.5, 8, 3, 2, calibrate = false, top = true)
        val done = listOf(DoneSet(97.5, 8), DoneSet(97.5, 8), DoneSet(97.5, 10))
        val engine = Progression.e1rmOf(bench, done, p, 80.0)!!
        assertEquals(97.5 / OneRm.fractionForRtf(11.0), engine, 1e-9)
        val rows = done.map { SetRow(1, day0, bench.id, it.weight, it.reps, null, 8, e1rm = engine) }
        assertEquals(engine, Stats.e1rmSeries(rows, bench) { 80.0 }.single().value, 1e-9)
        // The same session in the strength index and a row: the engine's number there too.
        assertEquals(engine, Stats.liftOn(Stats.e1rmSeries(rows, bench) { 80.0 }, day0)!!, 1e-9)
    }

    /**
     * progress-1rm-two-estimators: «на 1 раз» is one number wherever it is said — the better of the last two sessions, as
     * «Прогресс» has it; a lift not done in four weeks keeps the engine's working estimate, else its last session.
     */
    @Test fun theOneRepNumberIsTheOneProgressShows() {
        val series = listOf(Point(day0, 130.0), Point(day0 + 7, 136.0), Point(day0 + 14, 134.0))
        assertEquals(136.0, Stats.oneRmNow(series, day0 + 15, stored = 131.0))
        assertEquals(131.0, Stats.oneRmNow(series, day0 + 60, stored = 131.0))
        assertEquals(134.0, Stats.oneRmNow(series, day0 + 60, stored = null))
        assertNull(Stats.oneRmNow(emptyList(), day0, stored = null))
    }
}
