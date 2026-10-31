package forma.core

import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Muscle
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.model.Split
import forma.core.plan.Accents
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.plan.Templates
import forma.core.strength.SlotRole
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The program wizard (Denis, 31.10, after an app he liked): how the week is divided and up to two groups worked more. Every
 * choice makes a whole plan; «Подберём сами» stays what it was.
 */
class SplitAccentsTest {
    private val aims = listOf(
        Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), Aim(LiftFocus.MUSCLE, RunFocus.NONE, Emphasis.GYM),
        Aim(LiftFocus.STRENGTH, RunFocus.SPEED, Emphasis.BALANCED),
    )

    @Test fun everySplitMakesWholeDays() {
        for (split in Split.entries) for (aim in aims) for (exp in Experience.entries) for (lifts in 1..6) {
            val t = Templates.forWeek(aim, exp, lifts, split = split)
            val what = "$split $aim $exp $lifts"
            assertTrue(t.isNotEmpty(), what)
            assertEquals(t.size, t.map { it.id }.toSet().size, "unique days: $what")
            t.forEach { day ->
                assertTrue(day.slots.any { it.role == SlotRole.MAIN }, "a main lift on ${day.id}: $what")
                day.slots.forEach { assertTrue(Catalog.find(it.exerciseId) != null, "${it.exerciseId}: $what") }
            }
        }
    }

    @Test fun theAppsOwnChoiceIsAsBefore() {
        for (aim in aims) for (lifts in 1..5) {
            assertEquals(Templates.forWeek(aim, Experience.INTERMEDIATE, lifts), Templates.forWeek(aim, Experience.INTERMEDIATE, lifts, split = Split.AUTO))
        }
    }

    @Test fun pushPullLegsOnThreeDays() {
        val t = Templates.forWeek(aims[1], Experience.INTERMEDIATE, 3, split = Split.PPL)
        assertEquals(listOf("Толкай", "Тяни", "Ноги"), t.map { it.title })
        // Legs once, on their own day.
        assertEquals(listOf(false, false, true), t.map { it.legs })
    }

    @Test fun accentsAddWorkWhereAskedAndNowhereElse() {
        val aim = aims[1]
        val base = Templates.forWeek(aim, Experience.INTERMEDIATE, 4, split = Split.UPPER_LOWER)
        val more = Accents.apply(base, setOf(Muscle.GLUTES, Muscle.BACK), aim, Experience.INTERMEDIATE)
        val before = Accents.weekly(base)
        val after = Accents.weekly(more)
        assertTrue(after[Muscle.GLUTES]!! > before[Muscle.GLUTES]!!, "$before → $after")
        assertTrue(after[Muscle.BACK]!! > before[Muscle.BACK]!!, "$before → $after")
        // The main lifts are as they were, no exercise past five sets, other groups no less.
        assertEquals(base.flatMap { t -> t.slots.filter { it.role == SlotRole.MAIN } }, more.flatMap { t -> t.slots.filter { it.role == SlotRole.MAIN } })
        assertTrue(more.all { t -> t.slots.all { it.sets <= Accents.MAX_SETS } })
        Muscle.entries.forEach { assertTrue(after[it]!! >= before[it]!!, "$it: $before → $after") }
        // Two at most: a third is evenly again.
        assertEquals(more, Accents.apply(base, linkedSetOf(Muscle.GLUTES, Muscle.BACK, Muscle.CHEST), aim, Experience.INTERMEDIATE))
        assertEquals(base, Accents.apply(base, emptySet(), aim, Experience.INTERMEDIATE))
    }

    @Test fun theWizardsChoicesMakeAPlan() {
        val monday = LocalDate.of(2026, 11, 2)
        for (split in Split.entries) for (days in listOf(setOf(1, 3, 5), setOf(1, 2, 4, 5), setOf(1, 2, 3, 4, 5, 6))) {
            val profile = Profile(
                birthYear = 1996, bodyWeightKg = 80.0, experience = Experience.INTERMEDIATE, runLevel = RunLevel.NONE,
                trainingDays = days, sessionMinutes = 90, aim = Aim(LiftFocus.MUSCLE, RunFocus.NONE, Emphasis.GYM),
                split = split, accents = setOf(Muscle.SHOULDERS),
            )
            val p = ProgramGenerator.generate(profile, monday)
            val lifts = p.week.days.flatMap { d -> d.sessions.filter { it.kind == SessionKind.LIFT } }
            assertTrue(lifts.isNotEmpty(), "$split $days")
            lifts.forEach { s -> assertTrue(p.templates.any { it.id == s.templateId }, "$split $days: ${s.templateId}") }
            assertTrue(Accents.weekly(p)[Muscle.SHOULDERS]!! > 0)
        }
    }
}
