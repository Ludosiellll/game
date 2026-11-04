package forma.core

import forma.core.model.Catalog
import forma.core.strength.Progression
import forma.core.strength.WarmUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Denis, 4 November: «в махах в тренажере шаг по 5, но начиная с 9 кг ... нужно 9 14 19 24 29». A step that goes from a
 * weight: every weight the app gives lands on the stack.
 */
class StepFromTest {
    private val machine = Catalog.all.first { it.pattern == forma.core.model.Pattern.LATERAL_RAISE && it.equipment == forma.core.model.Equipment.MACHINE }
        .copy(step = 5.0, fineStep = null, stepFrom = 9.0)
    private val stack = (0..10).map { 9.0 + 5 * it }

    @Test fun roundingLandsOnTheStack() {
        assertEquals(14.0, floorTo(17.0, 5.0, 9.0))
        assertEquals(19.0, floorTo(19.0, 5.0, 9.0))
        assertEquals(9.0, floorTo(6.0, 5.0, 9.0), "never below the stack's first weight")
        assertEquals(19.0, roundTo(17.0, 5.0, 9.0))
        assertEquals(15.0, floorTo(17.0, 5.0), "from 0 as before")
        assertEquals(14.0, machine.upFrom(9.0))
        assertEquals(14.0, machine.upFrom(10.0))
        assertEquals(9.0, machine.downFrom(14.0))
        assertEquals(9.0, machine.downFrom(9.0))
        assertEquals(9.0, Progression.minWeight(machine, 75.0))
    }

    @Test fun warmUpsAreOnTheStack() {
        WarmUp.sets(29.0, 5.0, null, 9.0).forEach { assertTrue(it.weight in stack, "warm-up ${it.weight}") }
        WarmUp.single(29.0, 5.0, null, 9.0).forEach { assertTrue(it.weight in stack, "warm-up ${it.weight}") }
    }

    @Test fun progressionStaysOnTheStack() {
        // Any working weight the engine gives, from any start, is one of the stack's.
        for (start in listOf(9.0, 14.0, 15.0, 19.0, 22.0, 29.0)) {
            val hint = Progression.startHint(machine, 75.0, false, start / (machine.startRatio * 75.0))!!
            assertTrue(hint in stack, "start hint $hint from $start")
        }
    }
}
