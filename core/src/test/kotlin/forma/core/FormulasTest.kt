package forma.core

import forma.core.load.TrainingLoad
import forma.core.run.HeartRate
import forma.core.run.Riegel
import forma.core.run.Vdot
import forma.core.strength.OneRm
import forma.core.strength.Plates
import forma.core.strength.WarmUp
import forma.core.text.Ru
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormulasTest {
    private fun near(expected: Double, actual: Double, tol: Double) =
        assertTrue(abs(expected - actual) <= tol, "expected $expected ± $tol, got $actual")

    @Test fun epleyAndBrzyckiAgreeAtTenReps() {
        near(133.33, OneRm.epley(100.0, 10), 0.01)
        near(133.33, OneRm.brzycki(100.0, 10), 0.01)
        assertEquals(100.0, OneRm.epley(100.0, 1))
    }

    @Test fun e1rmFromRpeChart() {
        near(115.87, OneRm.estimate(100.0, 5), 0.01)
        // 3 reps with 2 in reserve is the same as 5 to failure.
        near(OneRm.estimate(100.0, 5), OneRm.estimate(100.0, 3, rir = 2), 1e-9)
        near(100.0, OneRm.loadFor(OneRm.estimate(100.0, 8, 2), 8, 2), 1e-9)
    }

    @Test fun rpeCurveIsContinuousAndFalling() {
        var prev = 1.0
        var r = 1.0
        while (r < 30) {
            val f = OneRm.fractionForRtf(r)
            assertTrue(f <= prev + 1e-12, "not falling at $r")
            prev = f
            r += 0.25
        }
        near(OneRm.fractionForRtf(11.999), OneRm.fractionForRtf(12.0), 1e-3)
    }

    @Test fun rtfInvertsTheCurve() {
        near(7.44, OneRm.rtfAt(100.0, 80.0), 0.01)
        near(5.0, OneRm.rtfAt(OneRm.estimate(60.0, 5), 60.0), 1e-6)
    }

    @Test fun plates() {
        assertEquals(listOf(25.0, 15.0), Plates.loadout(100.0).perSide)
        assertEquals(listOf(20.0, 1.25), Plates.loadout(62.5).perSide)
        assertEquals(emptyList(), Plates.loadout(20.0).perSide)
        val odd = Plates.loadout(61.0)
        assertEquals(60.0, odd.total)
    }

    @Test fun warmUpRampsBelowWorkWeight() {
        val sets = WarmUp.sets(100.0, 2.5)
        assertEquals(20.0, sets.first().weight)
        assertTrue(sets.all { it.weight < 100.0 })
        assertTrue(sets.zipWithNext().all { (a, b) -> b.weight > a.weight })
        assertEquals(emptyList(), WarmUp.sets(20.0, 2.5))
    }

    @Test fun vdotFromFiveKilometres() {
        near(49.8, Vdot.fromRace(5000.0, 20 * 60.0), 0.05)
    }

    @Test fun vdotPredictionsMatchDanielsTableForVdot50() {
        // Daniels' Running Formula, VDOT 50: 5K 19:57, 10K 41:21, half 1:31:35, marathon 3:10:49.
        near(19 * 60 + 57.0, Vdot.predictSeconds(50.0, 5000.0), 6.0)
        near(41 * 60 + 21.0, Vdot.predictSeconds(50.0, 10000.0), 12.0)
        near(91 * 60 + 35.0, Vdot.predictSeconds(50.0, 21097.5), 30.0)
        near(190 * 60 + 49.0, Vdot.predictSeconds(50.0, 42195.0), 90.0)
    }

    @Test fun thresholdPaceForVdot50() {
        // Daniels: T pace for VDOT 50 is 4:15 per km.
        near(255.0, Vdot.paceAtFraction(50.0, 0.88), 2.0)
    }

    @Test fun riegel() {
        near(41 * 60 + 41.0, Riegel.predictSeconds(5000.0, 20 * 60.0, 10000.0), 2.0)
    }

    @Test fun heartRate() {
        assertEquals(187, HeartRate.maxTanaka(30))
        val z = HeartRate.zones(190, 60)
        assertEquals(138, z[1].low)
        assertEquals(190, z[4].high)
    }

    @Test fun steadyTrainingIsNeutralAndARampShowsFatigue() {
        val start = LocalDate.of(2026, 1, 1)
        val steady = (0 until 28).associate { start.plusDays(it.toLong()) to 200.0 }
        val s1 = TrainingLoad.series(steady, start, start.plusDays(27))
        assertTrue(abs(s1.last().formPct) < 1.0, "steady form ${s1.last().formPct}")
        // A hard week on top: fatigue jumps above fitness.
        val ramp = steady + (28 until 35).associate { start.plusDays(it.toLong()) to 500.0 }
        val s2 = TrainingLoad.series(ramp, start, start.plusDays(34))
        assertTrue(s2.last().fatigue > s2.last().fitness)
        assertTrue(s2.last().formPct < -30)
        // A week off: fresh.
        val rest = TrainingLoad.series(steady, start, start.plusDays(34))
        assertTrue(rest.last().formPct > 10)
        assertEquals(420.0, TrainingLoad.sessionLoad(7, 60.0))
    }

    @Test fun russianPlurals() {
        assertEquals("1 минута", Ru.minutes(1))
        assertEquals("3 минуты", Ru.minutes(3))
        assertEquals("11 минут", Ru.minutes(11))
        assertEquals("21 минута", Ru.minutes(21))
        assertEquals("2 минуты 30 секунд", Ru.durationWords(150))
        assertEquals("62,5", Ru.kg(62.5))
        assertEquals("60", Ru.kg(60.0))
        assertEquals("1:05:09", Ru.clock(3909))
        assertEquals("4:15", Ru.pace(255.2))
    }
}
