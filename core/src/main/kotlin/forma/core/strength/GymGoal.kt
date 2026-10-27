package forma.core.strength

import forma.core.analytics.Point
import forma.core.model.Experience
import forma.core.model.LiftGoal
import forma.core.text.Ru
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * The way to a goal in the gym ([LiftGoal]): how far the estimated max is from it, how fast it has been growing, when it
 * may come, and how the plan trains the lift for it.
 *
 * - Heavy sets grow the max more than light ones (Schoenfeld et al. 2017, JSCR: 1RM gains favour loads over 60 % of 1RM,
 *   growth of the muscle alike): the lift's main day goes to 3–5 reps.
 * - How often a week matters through the weekly sets it allows (Grgic et al. 2018, Sports Med: no effect once the volume
 *   is the same): a second, lighter day of the lift adds quality sets and practice of the movement.
 * - Before a test: a taper of up to two weeks, about half the volume, the weight kept (Travis et al. 2020 review; Pritchard
 *   et al. 2015; powerlifters most often 7–10 days, 41–50 % less).
 * - Usual growth: ACSM 2009 (+40 / +20 / +10 % in about 12 weeks untrained / trained / advanced), taken lower: those are
 *   supervised programs in studies.
 */
object GymGoal {
    /** The max the goal stands for: [LiftGoal.kg] for [LiftGoal.reps] as a one-rep max. */
    fun target(g: LiftGoal): Double = OneRm.estimate(g.kg, g.reps)

    /** Usual growth of the max a week, by experience. */
    fun usualRate(exp: Experience): Double = when (exp) {
        Experience.NOVICE -> 0.02
        Experience.INTERMEDIATE -> 0.01
        Experience.ADVANCED -> 0.005
    }

    /** As fast as it goes with everything right: half again the usual. */
    fun fastRate(exp: Experience): Double = usualRate(exp) * 1.6

    /** Sets, reps and rest of the lift in the plan for the goal. */
    data class Scheme(val sets: Int, val repMin: Int, val repMax: Int, val restSec: Int)

    /** The heavy day of the lift: the most sets at 3–5 (4–6 while the lift is being learnt), long rests. */
    fun heavy(exp: Experience): Scheme = if (exp == Experience.NOVICE) Scheme(3, 4, 6, 180) else Scheme(4, 3, 5, 180)

    /** The second day of the lift: quality volume at 6–10. */
    val VOLUME = Scheme(3, 6, 10, 150)

    /** Days before the goal's date that are lighter (the taper). */
    const val TAPER_DAYS = 9L

    /** Weeks ahead for a nearer goal when this one is far. */
    private const val NEAR_WEEKS = 8

    /** Sets of the lift in the taper: about half, two at least. */
    fun taperSets(sets: Int): Int = max(2, ceil(sets / 2.0).toInt())

    /** [day] is in the taper: the days just before [LiftGoal.date], the date itself not. */
    fun tapering(g: LiftGoal?, day: Long): Boolean = g?.date?.let { day in it - TAPER_DAYS until it } == true

    enum class Verdict { NO_DATA, DONE, ON_TRACK, AMBITIOUS, UNLIKELY }

    data class Path(
        val target: Double,
        /** The estimated max now: the best of the last three weeks (or the last session within eight); null without any. */
        val current: Double?,
        /** How much of the way from where it started is behind (0..1), when the start is known. */
        val share: Double?,
        /** Growth a week the estimate goes by, and whether it is the person's own (from their sessions). */
        val weekly: Double,
        val personal: Boolean,
        /** The person's own sessions of the last weeks show no growth. */
        val stalled: Boolean,
        /** Weeks to the goal at [weekly]; null when done or not known. */
        val weeks: Int?,
        /** Growth a week needed to make the date. */
        val needed: Double?,
        val verdict: Verdict,
        /** The max by the date at [weekly], when there is a date. */
        val byDate: Double?,
        /** The max [NEAR_WEEKS] weeks on at [weekly]: a nearer goal when this one is far. */
        val nearer: Double?,
    )

    /**
     * Where [g] stands on [today] (epoch day) from [series] — the lift's best estimated max of each session, oldest first —
     * for a person of [exp]. Their own growth counts once four sessions over three weeks or more show it.
     */
    fun path(g: LiftGoal, series: List<Point>, exp: Experience, today: Long): Path {
        val target = target(g)
        val recent = series.filter { it.epochDay in today - 21..today }
        val current = recent.maxOfOrNull { it.value } ?: series.lastOrNull()?.takeIf { today - it.epochDay <= 56 }?.value
        val window = series.filter { it.epochDay in today - 56..today }
        val own = if (window.size >= 4 && window.last().epochDay - window.first().epochDay >= 21) weeklyGrowth(window) else null
        val stalled = own != null && own <= 0.001
        // The person's own growth, up to twice the usual for the level: the first weeks on a new machine or a learnt
        // technique grow the estimate faster than strength, and would promise too much.
        val weekly = if (own != null && !stalled) own.coerceIn(0.001, 2 * usualRate(exp)) else usualRate(exp)
        val personal = own != null && !stalled
        val from = g.fromE1rm
        val share = if (current == null) null else when {
            from != null && from < target -> ((current - from) / (target - from)).coerceIn(0.0, 1.0)
            else -> (current / target).coerceIn(0.0, 1.0)
        }
        if (current == null) return Path(target, null, null, weekly, personal, stalled, null, null, Verdict.NO_DATA, null, null)
        if (current >= target) return Path(target, current, 1.0, weekly, personal, stalled, null, null, Verdict.DONE, null, null)
        val weeks = ceil(ln(target / current) / ln(1 + weekly)).toInt()
        val nearer = if (weeks > NEAR_WEEKS * 2) current * (1 + weekly).pow(NEAR_WEEKS) else null
        val date = g.date
        if (date == null || date <= today) {
            val verdict = if (weeks <= 52) Verdict.ON_TRACK else Verdict.AMBITIOUS
            return Path(target, current, share, weekly, personal, stalled, weeks, null, verdict, null, nearer)
        }
        val left = (date - today) / 7.0
        val needed = (target / current).pow(1 / left) - 1
        val verdict = when {
            needed <= weekly -> Verdict.ON_TRACK
            // As fast as it goes for the level, or a little past the person's own growth when that is faster.
            needed <= max(fastRate(exp), weekly * 1.2) -> Verdict.AMBITIOUS
            else -> Verdict.UNLIKELY
        }
        return Path(target, current, share, weekly, personal, stalled, weeks, needed, verdict, current * (1 + weekly).pow(left), nearer)
    }

    /** Growth a week of the max over the [points]: the straight line through the logarithm of each session's best. */
    private fun weeklyGrowth(points: List<Point>): Double {
        val xs = points.map { it.epochDay.toDouble() }
        val ys = points.map { ln(it.value) }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx <= 0) return 0.0
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        return exp(slope * 7) - 1
    }

    /** Down to the [step] of the lift. */
    private fun down(x: Double, step: Double) = floor(x / step + 1e-9) * step

    /** The test of the max: warm-up sets (weight, reps) and the attempts at one rep, lightest first. */
    data class TestDay(val warmups: List<Pair<Double, Int>>, val attempts: List<Double>)

    /**
     * The test of the max on the goal's day, from the estimate [e1rm]: warm-up sets of 5, 3, 2 and 1 at half, 70, 80 and
     * 90 %, an opener a little under the estimate, then the goal ([target]) — or the estimate and a little, when the goal
     * is far past it (a test that ends in a miss teaches nothing). Weights down to the [step].
     */
    fun testDay(e1rm: Double, target: Double, step: Double): TestDay {
        val opener = down(minOf(e1rm * 0.95, target), step)
        val warm = listOf(0.5 to 5, 0.7 to 3, 0.8 to 2, 0.9 to 1).map { (f, r) -> down(e1rm * f, step) to r }.filter { it.first < opener }
        // The goal itself when it is within reach, up to the next step (a goal of 126 on a 2,5 kg bar is 127,5).
        val goalOnStep = ceil(target / step - 1e-9) * step
        val last = if (goalOnStep <= e1rm * 1.025 + 1e-9) goalOnStep else down(minOf(target, e1rm * 1.02), step)
        return TestDay(warm, listOf(opener) + if (last > opener) listOf(last) else emptyList())
    }

    /** "≈ 132,5 кг": a max said to the lift's step. */
    private fun kg(x: Double, step: Double) = Ru.kg(down(x, step))

    /**
     * The way to the goal in words, for the screens and the coach: where the max is, how fast it grows, when the goal may
     * come, and what to do when it is far or stuck. [name] — the lift, [step] — its weight step, [each] — " (каждая
     * гантель)" for dumbbells.
     */
    fun words(g: LiftGoal, p: Path, name: String, step: Double, each: String = ""): String {
        val goal = "$name ${Ru.kg(g.kg)} кг$each" + if (g.reps > 1) " на ${g.reps} ${Ru.plural(g.reps, "раз", "раза", "раз")}" else ""
        val oneRep = if (g.reps > 1) " (это ≈ ${kg(p.target, step)} кг на один раз)" else ""
        val current = p.current ?: return "Цель — $goal$oneRep. Путь покажу после первой тренировки с этим упражнением."
        if (p.verdict == Verdict.DONE) return "Цель $goal взята: по последним подходам максимум ≈ ${kg(current, step)} кг. Можно проверить на деле или поставить следующую."
        val pct = String.format(java.util.Locale.ROOT, "%.1f", p.weekly * 100).replace('.', ',')
        val out = mutableListOf(
            "Цель — $goal$oneRep, сейчас максимум ≈ ${kg(current, step)} кг.",
            if (p.personal) "По твоим тренировкам он растёт ≈ на $pct % в неделю." else "Обычно на твоём уровне максимум растёт ≈ на $pct % в неделю.",
        )
        p.weeks?.let { w -> out += "При таком темпе — примерно через $w ${Ru.plural(w, "неделю", "недели", "недель")}." }
        val date = g.date?.let { LocalDate.ofEpochDay(it) }
        val needed = p.needed?.let { String.format(java.util.Locale.ROOT, "%.1f", it * 100).replace('.', ',') }
        when (p.verdict) {
            Verdict.ON_TRACK -> if (date != null) out += "К ${Ru.date(date)} успеваешь."
            Verdict.AMBITIOUS -> out += if (date != null) "К ${Ru.date(date)} — только если всё пойдёт отлично: нужно ≈ +$needed % в неделю." else "Это надолго — держи ближнюю цель."
            Verdict.UNLIKELY -> out += "К ${Ru.date(date!!)} вряд ли: нужно ≈ +$needed % в неделю, а реально к этому дню ≈ ${kg(p.byDate ?: current, step)} кг. Лучше сдвинуть дату или взять цель ближе."
            else -> {}
        }
        if (p.stalled) out += "Последние недели без роста: проверь сон, еду и запас в подходах — или возьми лёгкую неделю."
        p.nearer?.let { out += "Ближняя цель — ≈ ${kg(it, step)} кг через $NEAR_WEEKS недель." }
        return out.joinToString(" ")
    }
}
