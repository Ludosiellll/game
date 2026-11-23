package forma.core

import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.plan.ProgramGenerator
import forma.core.plan.Scheduler
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * «Почему так» on the plan: the notes are stored with the week, so a plan laid out before the words were made plain (23
 * November: «Главное — …», «Сильнее ты становишься как раз во время отдыха») shows them in the new words, and the new
 * words stay as they are.
 */
class PlanNotesWordsTest {
    @Test fun oldNotesReadAsTheNewOnes() {
        val said = mapOf(
            "Зал на одни и те же мышцы не идёт два дня подряд: между ними есть день на восстановление." to
                "Зал на одни и те же мышцы не идёт два дня подряд.",
            "Накануне интервалов и длинной пробежки нет тренировки ног, так что бежать будет легче." to "Накануне тяжёлого бега нет тренировки ног.",
            "Длинная пробежка — в выходной, когда больше времени." to "Длинная пробежка в выходной, когда больше времени.",
            "Бег почти не мешает росту мышц и силы. Главное — разнести тяжёлые ноги и тяжёлый бег хотя бы на 6 часов, а лучше на сутки." to
                "Бег почти не мешает росту мышц и силы. Тренировку ног и тяжёлый бег разноси хотя бы на 6 часов, лучше на сутки.",
            "Тяжёлый бег — в дни верха тела: зал для рук и спины ему не мешает. Ноги — в день лёгкого бега." to
                "Тяжёлый бег идёт в дни верха тела: зал для рук и спины ему не мешает. Тренировка ног в день лёгкого бега.",
            "В день верха тела тяжёлый бег — первым, пока ноги свежие. Руки и спину бег почти не утомляет. В день ног — сначала зал, потом лёгкий бег." to
                "В день верха тела тяжёлый бег — первым, пока ноги свежие. В день ног сначала зал, потом лёгкий бег.",
            "В день верха тела тяжёлый бег — первым, пока ноги свежие. Руки и спину бег почти не утомляет. В день верха с лёгким бегом — сначала зал, потом бег." to
                "В день верха тела тяжёлый бег — первым, пока ноги свежие. В день верха с лёгким бегом сначала зал, потом бег.",
            "Дней полного отдыха: 2. Сильнее ты становишься как раз во время отдыха." to "Дней полного отдыха: 2.",
            // The gym first, then the run first in one note (23 November, second pass), in its words of now and of October.
            "В дни «зал + лёгкий бег» сначала зал. Если можешь разнести, бег утром, а зал через 6 часов и больше." to
                "В дни «зал + лёгкий бег» сначала зал, потом бег. Можно и разнести: бег утром, зал через 6\u00A0часов и больше.",
            "В дни «силовая + лёгкий бег» за один заход — сначала силовая. Если можешь разнести, бег утром, зал через 6+ часов." to
                "В дни «зал + лёгкий бег» сначала зал, потом бег. Можно и разнести: бег утром, зал через 6\u00A0часов и больше.",
        )
        for ((old, new) in said) assertEquals(new, Scheduler.reworded(old))
    }

    /** A week laid out before 23 November keeps its count of rest days: the plan does not show it, the week's rows do. */
    @Test fun theCountOfRestDaysIsNotShown() {
        val stored = listOf(
            "Зал на одни и те же мышцы не идёт два дня подряд: между ними есть день на восстановление.",
            "Дней полного отдыха: 3. Сильнее ты становишься как раз во время отдыха.",
            "В дни «зал + лёгкий бег» сначала зал. Если можешь разнести, бег утром, а зал через 6 часов и больше.",
            "Дней полного отдыха: 2.",
        )
        assertEquals(
            listOf(
                "Зал на одни и те же мышцы не идёт два дня подряд.",
                "В дни «зал + лёгкий бег» сначала зал, потом бег. Можно и разнести: бег утром, зал через 6\u00A0часов и больше.",
            ),
            Scheduler.shown(stored),
        )
    }

    @Test fun theNotesOfANewPlanAreLeftAsTheyAre() {
        val monday = LocalDate.of(2026, 11, 23)
        val days = listOf(setOf(1, 3, 5), setOf(1, 2, 4, 6), setOf(1, 2, 3, 4, 5), setOf(2, 4, 6, 7))
        for (d in days) for (run in RunFocus.entries) for (lift in listOf(LiftFocus.MUSCLE, LiftFocus.POWERBUILDING, LiftFocus.STRENGTH)) {
            val profile = Profile(
                birthYear = 2000, bodyWeightKg = 75.0, experience = Experience.INTERMEDIATE,
                runLevel = if (run == RunFocus.NONE) RunLevel.NONE else RunLevel.BASE, trainingDays = d, sessionMinutes = 90,
                aim = Aim(lift, run, if (run == RunFocus.NONE) Emphasis.GYM else Emphasis.BALANCED),
            )
            val notes = ProgramGenerator.generate(profile, monday).week.notes
            for (n in notes) assertEquals(n, Scheduler.reworded(n), "$d $run $lift")
            // The rest days are the week's own rows: no note counts them, and the plan shows every note a new week has.
            assertTrue(notes.none { it.startsWith("Дней полного отдыха") }, "$d $run $lift: $notes")
            assertEquals(notes, Scheduler.shown(notes), "$d $run $lift")
        }
    }
}
