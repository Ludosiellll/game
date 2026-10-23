package forma.app.ui

import forma.app.ui.screens.parseLapTime
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A lap's time typed by hand in history: seconds or as on a watch. */
class LapTimeParseTest {
    @Test fun secondsOrMinutesAndSeconds() {
        assertEquals(83.0, parseLapTime("83"))
        assertEquals(83.4, parseLapTime("83,4")!!, 1e-9)
        assertEquals(83.4, parseLapTime(" 83.4 ")!!, 1e-9)
        assertEquals(83.0, parseLapTime("1:23"))
        assertEquals(83.4, parseLapTime("1:23,4")!!, 1e-9)
        assertEquals(50.6, parseLapTime("0:50,6")!!, 1e-9)
    }

    @Test fun typosAreNotTimes() {
        assertNull(parseLapTime(""))
        assertNull(parseLapTime("1:5"))
        assertNull(parseLapTime("1:75"))
        assertNull(parseLapTime("-5"))
        assertNull(parseLapTime("0"))
        assertNull(parseLapTime("1:2:3"))
        assertNull(parseLapTime("abc"))
    }
}
