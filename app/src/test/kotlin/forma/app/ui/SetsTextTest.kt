package forma.app.ui

import forma.app.ui.screens.setsText
import kotlin.test.Test
import kotlin.test.assertEquals

/** 23 November, the weekly muscle list read «Спина 6,0 … Икры 0,0»: sets are counted in whole and half sets. */
class SetsTextTest {
    @Test fun wholeSetsHaveNoDecimals() {
        assertEquals("6", setsText(6.0))
        assertEquals("0", setsText(0.0))
        assertEquals("2,5", setsText(2.5))
    }
}
