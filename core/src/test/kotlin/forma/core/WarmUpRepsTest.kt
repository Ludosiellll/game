package forma.core

import forma.core.strength.WarmUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Denis, 23 November: «разминка странная, когда очевидно что лучше сделать не на раз а на 2–3 чтобы лучше включить
 * мышцы» — his chest press of 120 kg was warmed up with 48 × 5, 72 × 3, 90 × 2 and 106 × 1. The work is sets of 5–8, not
 * a single: the ramp wakes the muscles with more reps on the light sets and ends on 2–3, never on one.
 */
class WarmUpRepsTest {
    @Test fun noWarmUpSetIsASingle() {
        for (work in listOf(25.0, 40.0, 60.0, 80.0, 90.0, 100.0, 120.0, 160.0, 220.0)) {
            for (bar in listOf(20.0, null)) {
                val sets = WarmUp.sets(work, 2.5, bar)
                assertTrue(sets.all { it.reps >= 2 }, "$work kg, bar $bar: $sets")
                assertTrue(sets.zipWithNext().all { (a, b) -> b.weight > a.weight && b.reps <= a.reps }, "$work kg: $sets")
                assertTrue(sets.all { it.weight < work }, "$work kg: $sets")
            }
        }
    }

    @Test fun hisChestPressRampsOnMoreRepsAndEndsOnTwo() {
        // A machine: no bar. 120 kg for 5.
        val sets = WarmUp.sets(120.0, 5.0, null)
        assertEquals(listOf(50.0 to 8, 70.0 to 5, 90.0 to 3, 105.0 to 2), sets.map { it.weight to it.reps })
    }

    @Test fun aMiddleWeightEndsOnThree() {
        // His squat of 90 kg: the bar, then three steps, the last for 3.
        val sets = WarmUp.sets(90.0, 2.5, 20.0)
        assertEquals(listOf(20.0 to 10, 40.0 to 8, 57.5 to 5, 77.5 to 3), sets.map { it.weight to it.reps })
    }
}
