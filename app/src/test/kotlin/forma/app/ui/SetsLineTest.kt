package forma.app.ui

import forma.app.ui.screens.prescriptionLine
import forma.app.ui.screens.setsLine
import forma.core.model.Catalog
import forma.core.strength.Prescription
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 23 November, a second review of the gym screens: the same sets were written three ways («100 кг × 6, 6» in the summary,
 * «82,5×6  82,5×6  82,5×6» with no unit on the exercise page, «14 кг ×2, 3 по 10» over rows saying «по 14 кг × 10»), and
 * «Прошлый раз» put every rep after the heaviest weight («85 кг × 8, 6, 5» for 80 × 8 then 85 × 6, 5). Now one way: the
 * weight once for the sets done at it, the number kept on one line with its unit.
 */
class SetsLineTest {
    private fun plain(s: String) = s.replace('\u00A0', ' ')

    @Test fun eachWeightWithTheSetsDoneAtIt() {
        val squat = Catalog["squat"]
        assertEquals("80 кг × 8 · 85 кг × 6, 5", plain(setsLine(squat, listOf(80.0 to 8, 85.0 to 6, 85.0 to 5))))
        assertEquals("82,5 кг × 6, 6, 6", plain(setsLine(squat, listOf(82.5 to 6, 82.5 to 6, 82.5 to 6))))
        assertEquals("по 14 кг × 10, 8", plain(setsLine(Catalog["db_curl"], listOf(14.0 to 10, 14.0 to 8))))
        assertEquals("помощь 15 кг × 8, 7", plain(setsLine(Catalog["pullup"], listOf(-15.0 to 8, -15.0 to 7))))
        assertEquals("30 с, 25 с", plain(setsLine(Catalog["plank"], listOf(0.0 to 30, 0.0 to 25))))
        // A line that wraps never leaves «кг» alone at the start of the next.
        assertEquals("72,5\u00A0кг × 6, 6", setsLine(squat, listOf(72.5 to 6, 72.5 to 6)))
    }

    @Test fun dumbbellsInThePlanLineAreSaidPerHand() {
        assertEquals(
            "По 14 кг, 3 по 10. Стоп, когда можешь ещё 2.",
            prescriptionLine(Catalog["db_curl"], Prescription(14.0, 10, 3, 2, calibrate = false)),
        )
    }
}
