package forma.core

import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.plan.ProgramGenerator
import java.time.LocalDate
import forma.core.model.LiftFocus
import forma.core.model.RunFocus
import forma.core.plan.Focus
import forma.core.plan.Template
import forma.core.plan.Templates
import forma.core.strength.RestAdvice
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Denis (31.10): «в последних исследованиях отдых между многосуставными упражнениями лучше делать 3-5 минут, а между
 * изоляцией 1-3». The main lifts start at 3 min (heavy sets and sets to failure get more, up to 5), isolation at 1:30 and
 * never under a minute; a plan made before moves once, keeping what is not an old default.
 */
class RestDefaultsTest {
    private fun spec(id: String, role: SlotRole, rest: Int) = SlotSpec(id, 3, 6, 10, 2, rest, role)

    @Test fun aPlanMadeBeforeMovesToTheNewRests() {
        val profile = Profile(
            birthYear = 1996, bodyWeightKg = 80.0, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE,
            trainingDays = setOf(1, 3, 5), sessionMinutes = 90, aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
        )
        val old = ProgramGenerator.generate(profile, LocalDate.of(2026, 10, 26)).let { p ->
            p.copy(
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
                templates = listOf(
                    Template("a", "Всё тело · А", Focus.FULL, listOf(
                        spec("squat", SlotRole.MAIN, 150), spec("rdl", SlotRole.SECONDARY, 120),
                        spec("lateral_raise", SlotRole.ACCESSORY, 75), spec("plank", SlotRole.ACCESSORY, 60),
                        // Not an old default: the advisor or a friend's copy set it — it stays.
                        spec("bench", SlotRole.MAIN, 200),
                    )),
                ),
            )
        }
        val moved = Templates.restMoved(old).templates.single().slots.associate { it.exerciseId to it.restSec }
        assertEquals(mapOf("squat" to 180, "rdl" to 120, "lateral_raise" to 90, "plank" to 60, "bench" to 200), moved)
        // Twice is once.
        assertEquals(moved, Templates.restMoved(Templates.restMoved(old)).templates.single().slots.associate { it.exerciseId to it.restSec })
    }

    @Test fun isolationNeverUnderAMinute() {
        // Many easy reps with reserve, a deload: before, down to 45 s.
        val r = RestAdvice.after(75, reps = 15, rir = 4, plannedRir = 1, compound = false, deload = true, personal = -15)
        assertEquals(60, r.sec)
        // A main lift's heavy set to failure goes past 3 min, never past 5.
        assertEquals(240, RestAdvice.after(180, reps = 5, rir = 0, plannedRir = 2, compound = true, deload = false, personal = 0).sec)
        assertEquals(300, RestAdvice.after(180, reps = 3, rir = 0, plannedRir = 2, compound = true, deload = false, personal = 60).sec)
    }
}
