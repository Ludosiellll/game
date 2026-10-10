package forma.core

import forma.core.analytics.Calories
import forma.core.analytics.Points
import forma.core.model.Sex
import forma.core.run.PaceTable
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtrasTest {
    @Test fun dotsMatchesOpenPowerliftingVectors() {
        assertEquals(387.96, Points.dots(600.0, 90.0, Sex.MALE), 0.01)
        assertEquals(387.99, Points.dots(350.0, 60.0, Sex.FEMALE), 0.01)
    }

    @Test fun runningMetsInterpolate() {
        assertEquals(10.5, Calories.runMet(10.8), 1e-9)
        assertEquals(9.15, Calories.runMet(9.35), 1e-9)
        // 1 hour at 10.8 km/h for 80 kg ≈ 840 kcal.
        assertEquals(840.0, Calories.run(10.8, 80.0, 60.0), 1e-9)
    }

    @Test fun pacesMatchDanielsTablesAtVdot30And40() {
        val p40 = PaceTable.forVdot(40.0)
        assertTrue(abs(p40.threshold - 306) <= 2, "T40 ${p40.threshold}")
        assertTrue(abs(p40.interval - 281) <= 2, "I40 ${p40.interval}")
        val p30 = PaceTable.forVdot(30.0)
        assertTrue(abs(p30.threshold - 384) <= 3, "T30 ${p30.threshold}")
        assertTrue(abs(p30.interval - 354) <= 3, "I30 ${p30.interval}")
    }
}
