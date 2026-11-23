package forma.app.ui

import forma.app.ui.screens.lastSetRule
import forma.app.ui.screens.prescriptionLine
import forma.core.model.Catalog
import forma.core.strength.Prescription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 23 November, a review of the squat screen as a lifter sees it between sets: «в последнем сколько сможешь» (read as «to
 * failure»), «Заканчивай подход, когда можешь ещё 3 раза» and «Остановись, когда сможешь ещё 2» on one screen. Now the
 * base sets have one rule in the line, and the last set one rule, in the panel right above that set (a second review the
 * same day: under the logged sets it was behind the panel while resting).
 */
class LastSetRuleTest {
    private val squat = Catalog["squat"]

    @Test fun oneRuleForTheSetsAndOneForTheLast() {
        val p = Prescription(82.5, 6, 3, 3, calibrate = false, top = true, topRir = 2)
        val line = prescriptionLine(squat, p)
        assertEquals("82,5 кг, 3 по 6. Стоп, когда можешь ещё 3.", line)
        assertTrue("сколько сможешь" !in line)
        val last = lastSetRule(p)
        assertTrue("после 6 продолжай" in last && "ещё 2" in last, last)
        assertTrue("ещё 1" in lastSetRule(p.copy(topRir = 1)))
        // Two left only on legs before a hard run: said why then, and only then.
        assertEquals("Последний подход: после 6 продолжай, стоп, когда можешь ещё 2. Скоро тяжёлый бег.", last)
        assertEquals("Последний подход: после 6 продолжай, стоп, когда можешь ещё 1.", lastSetRule(p.copy(topRir = 1)))
    }
}
