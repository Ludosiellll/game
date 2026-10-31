package forma.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.Settings
import forma.app.ui.theme.Looks
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Denis (31.10): «давай по умолчанию темную тему». Every install moves to the dark «Минимализм» once: a new one, an old
 * one on any look, and the white «Минимализм» the move of 30.10 gave. A look picked by hand after that move stays, and
 * nothing moves twice (a look picked after this move stays too).
 */
@RunWith(AndroidJUnit4::class)
class DesignMoveTest {
    @Test fun everyoneMovesToTheDarkLookOnce() {
        assertEquals("minimal_dark", Looks.moved(Settings()).look)
        assertEquals("minimal_dark", Looks.moved(Settings(look = "poster")).look)
        assertEquals("minimal_dark", Looks.moved(Settings(look = "minimal", designVersion = 1)).look)
        assertEquals(Looks.DESIGN_VERSION, Looks.moved(Settings()).designVersion)
    }

    @Test fun aLookPickedByHandStays() {
        // Picked after the move of 30.10.
        assertEquals("carbon", Looks.moved(Settings(look = "carbon", designVersion = 1)).look)
        // Picked after this move: the white one again, by choice.
        val chosen = Settings(look = "minimal", designVersion = Looks.DESIGN_VERSION)
        assertEquals(chosen, Looks.moved(chosen))
    }
}
