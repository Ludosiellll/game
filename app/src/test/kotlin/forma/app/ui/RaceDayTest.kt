package forma.app.ui

import forma.app.ui.screens.parseRaceDay
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RaceDayTest {
    private val today = LocalDate.of(2026, 10, 14)

    @Test fun aRaceDayIsReadAsPeopleTypeIt() {
        assertEquals(LocalDate.of(2026, 11, 7), parseRaceDay("07.11", today))
        assertEquals(LocalDate.of(2026, 11, 7), parseRaceDay("7.11.26", today))
        assertEquals(LocalDate.of(2027, 6, 15), parseRaceDay("15/06/2027", today))
        // Without a year and already past this year: next year's.
        assertEquals(LocalDate.of(2027, 3, 1), parseRaceDay("01.03", today))
        assertEquals(null, parseRaceDay("32.01", today))
        assertEquals(null, parseRaceDay("01.10.2026", today))
        assertEquals(null, parseRaceDay("завтра", today))
    }
}
