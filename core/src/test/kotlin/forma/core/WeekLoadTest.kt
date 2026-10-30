package forma.core

import forma.core.plan.DayPlan
import forma.core.plan.Focus
import forma.core.plan.PlannedSession
import forma.core.plan.SessionKind
import forma.core.plan.Template
import forma.core.plan.WeekLoad
import forma.core.plan.WeekPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Denis (30.10), on taking someone's gym with his own running: «а что если нагрузка ебанутая будет — у человека бег был
 * рассчитан, чтобы прям все силы забирать, а тут он ещё и зал берёт». The week put together is checked and said plainly,
 * and can be made lighter.
 */
class WeekLoadTest {
    private val lower = Template("lower", "Низ", Focus.LOWER, emptyList())
    private val upper = Template("upper", "Верх", Focus.UPPER, emptyList())
    private val full = Template("full", "Всё тело", Focus.FULL, emptyList())
    private val templates = listOf(lower, upper, full)

    private fun lift(t: Template) = PlannedSession(SessionKind.LIFT, t.id)
    private val easy = PlannedSession(SessionKind.RUN_EASY)
    private val hard = PlannedSession(SessionKind.RUN_QUALITY)
    private val long = PlannedSession(SessionKind.RUN_LONG)
    private fun week(vararg days: Pair<Int, List<PlannedSession>>) = WeekPlan((1..7).map { d -> DayPlan(d, days.toMap()[d].orEmpty()) }, emptyList())

    /** His own week (3 gym days of the whole body, 3 runs, two of them easy) is fine. */
    @Test fun aBalancedWeekSaysNothing() {
        val w = week(1 to listOf(lift(full)), 2 to listOf(easy), 3 to listOf(lift(upper)), 4 to listOf(hard), 5 to listOf(lift(full), easy))
        val load = WeekLoad.of(w, templates)
        assertTrue(load.warnings.isEmpty(), "${load.warnings}")
        assertEquals(6, load.sessions)
        assertEquals(2, load.restDays)
    }

    /** Someone's 5-day split with his 4 runs: no day off, legs before intervals, hard days in a row. */
    @Test fun aHeavyMixIsSaidPlainly() {
        val w = week(
            1 to listOf(lift(lower)), 2 to listOf(lift(upper), hard), 3 to listOf(lift(lower), easy),
            4 to listOf(lift(upper), long), 5 to listOf(lift(full)), 6 to listOf(hard), 7 to listOf(easy),
        )
        val load = WeekLoad.of(w, templates)
        val text = load.warnings.joinToString(" | ")
        assertTrue(load.tooMuch)
        listOf("10 тренировок", "Нет ни одного дня отдыха", "Тяжёлых дней 6 из 7", "Ноги в пн, а в вт тяжёлый бег", "Ноги в пт, а в сб тяжёлый бег", "подряд")
            .forEach { assertTrue(it in text, "«$it» in: $text") }
    }

    @Test fun legsAndAHardRunOnOneDayIsSaid() {
        val w = week(2 to listOf(lift(lower), hard), 5 to listOf(lift(upper)))
        assertTrue(WeekLoad.of(w, templates).warnings.any { "и ноги, и тяжёлый бег" in it })
        // The upper body on a hard run's eve is fine.
        val ok = week(1 to listOf(lift(upper)), 2 to listOf(hard), 5 to listOf(lift(lower)))
        assertTrue(WeekLoad.of(ok, templates).warnings.isEmpty(), "${WeekLoad.of(ok, templates).warnings}")
    }

    /** Lighter: the hard run after the legs becomes easy; or the legs day before it goes. */
    @Test fun aWeekCanBeMadeLighter() {
        val w = week(1 to listOf(lift(lower)), 2 to listOf(hard), 4 to listOf(lift(upper)), 6 to listOf(long))
        val eased = assertNotNull(WeekLoad.easeRun(w, templates))
        assertEquals(listOf(easy), eased.on(2))
        assertEquals(listOf(long), eased.on(6))
        assertTrue(WeekLoad.of(eased, templates).warnings.isEmpty(), "${WeekLoad.of(eased, templates).warnings}")
        val dropped = assertNotNull(WeekLoad.dropLift(w, templates))
        assertTrue(dropped.on(1).isEmpty())
        assertEquals(listOf(lift(upper)), dropped.on(4))
        // Nothing to ease, or the gym's only day: no lighter week that way.
        assertNull(WeekLoad.easeRun(week(1 to listOf(easy)), templates))
        assertNull(WeekLoad.dropLift(week(1 to listOf(lift(full)), 3 to listOf(easy)), templates))
    }
}
