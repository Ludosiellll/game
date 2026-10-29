package forma.core

import forma.core.model.Catalog
import forma.core.strength.DoneSet
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import kotlin.test.Test
import kotlin.test.assertTrue

/** Help on pull-ups (a gravitron, a band) comes off as fast as the load moved allows, not a step a session. */
class AssistedWeaningTest {
    @Test fun helpComesOffByTheLoadMovedNotByTheHelpItself() {
        // 80 kg, 20 kg of help planned × 8; the hints took him to 15 and 10 kg of help within the session, the «+» set
        // 10 «ещё 1» with 10. The cap of a session's jump (a tenth of the load) was taken of the help, −20 × 1.1, and the
        // help came off 2.5 kg a session: four sessions to where the first one had shown him.
        val ex = Catalog["pullup"]
        val spec = SlotSpec(ex.id, 3, 6, 10, 1, 120, SlotRole.MAIN)
        val st = SlotState(-20.0, 8, sessions = 6, linear = false)
        val p = Progression.prescribe(ex, spec, st, bodyWeight = 80.0)
        val e = Progression.evaluate(ex, spec, st, p, listOf(DoneSet(-20.0, 8, 5), DoneSet(-15.0, 8, 5), DoneSet(-10.0, 10, 1)), 80.0)
        assertTrue(e.state.weight!! >= -15.0, "${e.outcome}")
        // Still a tenth of the load at most: 60 kg moved, 66 next.
        assertTrue(e.state.weight!! <= -14.0, "${e.outcome}")
    }
}
