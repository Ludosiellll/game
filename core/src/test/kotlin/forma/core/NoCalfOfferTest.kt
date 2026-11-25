package forma.core

import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.RunFocus
import forma.core.plan.PlanAdvisor
import forma.core.plan.PlanAdvisor.Day
import forma.core.plan.PlanAdvisor.Item
import forma.core.plan.Templates
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Denis took the calf raise out of his plan and said twice not to bring calf work back (24 November: «нет возвращать не
 * надо»): «Идеальный план» still offered the seated calf raise, another exercise for the same muscle. Whoever took calf
 * work out of the plan, or marked it «не хочу», is not offered calf work again.
 */
class NoCalfOfferTest {
    private val hybrid = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED)

    /** His plan: the template week with the calf raise taken out. */
    private fun days(): List<Day> = Templates.forWeek(hybrid, Experience.ADVANCED, 3, shared = true).map { t ->
        Day(t.id, t.title, t.slots.filter { it.exerciseId != "calf_raise" }.map { s -> Item(s.exerciseId, s.exerciseId, s.sets, s.repMin, s.repMax, s.restSec, s.role) })
    }

    private fun input(habits: PlanAdvisor.Habits) =
        PlanAdvisor.Input(days(), hybrid, Experience.ADVANCED, runs = 3, sessionMinutes = 75, habits = habits, find = Catalog::find)

    @Test fun calfWorkTakenOutOfThePlanIsNotOfferedAgain() {
        val a = PlanAdvisor.advise(input(PlanAdvisor.Habits(avoided = mapOf("calf_raise" to "ты убрал из плана"))))
        val calf = a.changes.filter { c -> c.options.any { Catalog.find(it)?.pattern == forma.core.model.Pattern.CALF } || "calf" in c.id }
        assertTrue(calf.isEmpty(), "no calf work offered: ${calf.map { it.id }}")
        // Nor asked for in words.
        assertTrue(a.changes.none { "носки" in it.why }, a.changes.map { it.why }.toString())
    }

    @Test fun markedNotWantedIsTheSame() {
        val a = PlanAdvisor.advise(input(PlanAdvisor.Habits(avoided = mapOf("seated_calf" to "ты отметил «не хочу»"))))
        assertTrue(a.changes.none { c -> c.options.any { Catalog.find(it)?.pattern == forma.core.model.Pattern.CALF } }, a.changes.map { it.id }.toString())
    }

    /** With no sign against it the offer stays as it was. */
    @Test fun withNothingSaidAgainstItTheSoleusIsStillOffered() {
        val a = PlanAdvisor.advise(input(PlanAdvisor.Habits()))
        assertTrue(a.changes.any { it.id == "add:seated_calf" }, a.changes.map { it.id }.toString())
    }
}
