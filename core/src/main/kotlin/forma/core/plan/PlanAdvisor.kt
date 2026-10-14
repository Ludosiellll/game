package forma.core.plan

import forma.core.model.Aim
import forma.core.model.Exercise
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.LoadKind
import forma.core.model.Muscle
import forma.core.model.Pattern
import forma.core.model.RunFocus
import forma.core.strength.SlotRole
import forma.core.text.Ru
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * «Идеальный план»: the gym plan as it is now (the person's swaps, sets and added exercises included) weighed against
 * everything that matters for this person, and the changes that would make it better — each with its reasons and its
 * options, each taken or left by the person. Nothing here changes the plan: it only says what could be.
 *
 * What it weighs:
 * - hard sets a week per muscle (fully for a muscle an exercise works first, half for one that helps) against the range
 *   for the focus and experience (more sets, more growth, up to about 20 a week — Schoenfeld et al. 2017; 6–15 is enough
 *   for strength); the legs lower beside running (strength and running compete for the legs' recovery);
 * - a muscle short of sets and another with sets to spare: one exercise swapped for the other, the session no longer;
 * - what a runner's legs need: one-leg strength (running is a series of one-leg landings), the back of the thigh trained
 *   lengthening (Nordic curls roughly halved hamstring injuries in footballers — van der Horst 2015, Petersen 2011), the
 *   soleus (calf raises seated);
 * - the hinge (the back of the body) in a week that trains the legs; pushing against pulling;
 * - a lift without a new best for weeks: the same movement in another variation for a few weeks;
 * - a session longer than the time there is; being worn down often; a race coming (nothing new for the legs before it).
 */
object PlanAdvisor {
    /**
     * One exercise of a day as it is done now. [planId]: the plan's exercise in this place (before a swap; for one added
     * for good, its own id); [added]: added to the day for good.
     */
    data class Item(
        val exerciseId: String,
        val planId: String,
        val sets: Int,
        val repMin: Int,
        val repMax: Int,
        val restSec: Int,
        val role: SlotRole,
        val added: Boolean = false,
    )

    /** A lifting day of the week, done [times] a week. */
    data class Day(val templateId: String, val title: String, val items: List<Item>, val times: Int = 1)

    data class Input(
        val days: List<Day>,
        val aim: Aim,
        val experience: Experience,
        /** Runs a week. */
        val runs: Int = 0,
        /** The session length the person has, minutes; null — not said. */
        val sessionMinutes: Int? = null,
        /** The person's sessions against the plan's estimate ([SessionTime.ratio]). */
        val timeRatio: Double = 1.0,
        /** Exercise → weeks since its last new best (only exercises done often enough to tell). */
        val stalled: Map<String, Int> = emptyMap(),
        /** Below their own rest line, or "so-so", often lately. */
        val tiredOften: Boolean = false,
        /** Days to the goal race, null without one. */
        val daysToRace: Long? = null,
        val find: (String) -> Exercise?,
    )

    enum class Kind { SWAP, ADD, SETS }

    /**
     * A change. [Kind.SWAP]: one of [options] in the place of [planId] (the plan's own id brings it back; [added]: an
     * exercise added for good is replaced); [Kind.ADD]: one of [options] added to the day for good; [Kind.SETS]: [setsTo]
     * sets for the exercise in [planId]'s place. [setsTo], [repsTo], [restSec] with an addition: the numbers it needs.
     * A swap or sets of a plan exercise hold for it on every day it is on (as the plan keeps them); [priority]: 1 matters most.
     */
    data class Change(
        val id: String,
        val kind: Kind,
        val templateId: String,
        val dayTitle: String,
        val planId: String?,
        val from: String?,
        val options: List<String>,
        val setsFrom: Int?,
        val setsTo: Int?,
        val repsTo: Pair<Int, Int>?,
        val restSec: Int?,
        val why: String,
        val priority: Int,
        val added: Boolean = false,
    ) {
        /** The exercise put in or added: the first option unless another is picked. */
        val to: String? get() = options.firstOrNull()

        /** The change with option [id] picked. */
        fun pick(id: String): Change = if (id in options) copy(options = listOf(id) + (options - id)) else this
    }

    /** Weekly sets for [muscle] now and with the changes taken, against the range [low]–[high] for this person. */
    data class Volume(val muscle: Muscle, val now: Double, val after: Double, val low: Int, val high: Int)

    /**
     * [days]: the plan as it is; [changes]: what could be better, the most important first; [volume]: weekly sets now and
     * with every change taken; [fine]: what is right already (a plan judged is more than its faults); [ranges]: weekly sets
     * wanted per muscle.
     */
    data class Advice(
        val days: List<Day>,
        val changes: List<Change>,
        val volume: List<Volume>,
        val fine: List<String>,
        val ranges: Map<Muscle, Pair<Int, Int>>,
        val summary: String,
        /** Something the plan cannot fix, said first (many lifts stuck at once: recovery, not exercises). */
        val warning: String? = null,
        /** What is not proposed now, and why (a race coming, no time for more). */
        val held: List<String> = emptyList(),
    )

    /** The muscles the week is weighed by (the core works in everything heavy). */
    val MUSCLES = listOf(
        Muscle.CHEST, Muscle.BACK, Muscle.SHOULDERS, Muscle.BICEPS, Muscle.TRICEPS,
        Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES, Muscle.CALVES,
    )
    private val LEGS = setOf(Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES, Muscle.CALVES)
    private val PUSH = setOf(Pattern.HORIZONTAL_PUSH, Pattern.INCLINE_PUSH, Pattern.VERTICAL_PUSH, Pattern.DIP)
    private val PULL = setOf(Pattern.HORIZONTAL_PULL, Pattern.VERTICAL_PULL, Pattern.REAR_DELT)

    /** No new leg exercise this close to the race: the first sessions of one leave the legs sore for days. */
    const val RACE_QUIET_DAYS = 28L

    /** Weeks without a new best before another variation is worth it. */
    const val STALL_WEEKS = 5

    /** This many main lifts stuck at once is taken for recovery, not for the exercises. */
    const val MANY_STUCK = 3

    /** At most this many sets of one exercise the advisor proposes (more is better spread over another exercise). */
    const val MAX_SETS_ADVISED = 4

    fun runner(input: Input) = input.aim.normalized().run != RunFocus.NONE && input.runs >= 2

    /** Weekly sets a muscle wants: by focus and experience; the legs lower beside running; the arms work in presses and pulls too. */
    fun range(input: Input, m: Muscle): Pair<Int, Int> {
        val (lo, hi) = when (input.aim.normalized().lift) {
            LiftFocus.MUSCLE -> 10 to 20
            LiftFocus.POWERBUILDING -> 8 to 18
            LiftFocus.STRENGTH -> 6 to 15
            else -> 6 to 12
        }
        val low = if (input.experience == Experience.NOVICE) lo - 2 else lo
        return when {
            m == Muscle.CALVES -> if (runner(input)) 4 to 12 else 0 to hi
            runner(input) && m in LEGS -> 4 to minOf(hi, 12)
            m == Muscle.BICEPS || m == Muscle.TRICEPS -> (low - 2).coerceAtLeast(2) to hi
            else -> low to hi
        }
    }

    /** Weekly sets per muscle of [days]: the first muscles fully, the helping ones half. */
    fun volume(days: List<Day>, find: (String) -> Exercise?): Map<Muscle, Double> {
        val out = mutableMapOf<Muscle, Double>()
        for (d in days) for (it in d.items) {
            val ex = find(it.exerciseId) ?: continue
            val sets = it.sets * d.times.toDouble()
            ex.primary.forEach { m -> out[m] = (out[m] ?: 0.0) + sets }
            ex.secondary.forEach { m -> out[m] = (out[m] ?: 0.0) + sets / 2 }
        }
        return out
    }

    /** Minutes of [d]: work and rest per set and a warm-up ([SessionTime.estimate]), at the person's own pace. */
    fun minutes(d: Day, ratio: Double): Int =
        ((10 + d.items.sumOf { it.sets * (it.restSec + 45) } / 60.0) * ratio).roundToInt()

    /** [days] with [changes] taken (each with its first option). */
    fun apply(days: List<Day>, changes: List<Change>): List<Day> = days.map { d ->
        var items = d.items
        // A plan exercise's swap and sets hold on every day it is on; an added one's on its own day.
        for (c in changes.filter { it.templateId == d.templateId || it.kind != Kind.ADD && !it.added }) {
            val to = c.to ?: continue
            fun mine(i: Item) = i.planId == c.planId && i.added == c.added
            items = when (c.kind) {
                Kind.SWAP -> items.map {
                    if (mine(it)) it.copy(
                        exerciseId = to, sets = c.setsTo ?: it.sets, repMin = c.repsTo?.first ?: it.repMin,
                        repMax = c.repsTo?.second ?: it.repMax, restSec = c.restSec ?: it.restSec,
                    ) else it
                }
                Kind.ADD -> if (items.any { it.exerciseId == to }) items else items + Item(
                    to, to, c.setsTo ?: 3, c.repsTo?.first ?: 10, c.repsTo?.second ?: 15, c.restSec ?: 75, SlotRole.ACCESSORY, added = true,
                )
                Kind.SETS -> items.map { if (mine(it) && c.setsTo != null) it.copy(sets = c.setsTo) else it }
            }
        }
        d.copy(items = items)
    }

    /** The weekly sets table now and for the week [after] (the plan with the chosen changes taken). */
    fun table(advice: Advice, after: List<Day>, find: (String) -> Exercise?): List<Volume> {
        val now = volume(advice.days, find)
        val then = volume(after, find)
        return MUSCLES.map { m -> advice.ranges.getValue(m).let { (lo, hi) -> Volume(m, now[m] ?: 0.0, then[m] ?: 0.0, lo, hi) } }
    }

    fun advise(input: Input): Advice {
        val find = input.find
        val days = input.days.filter { it.items.isNotEmpty() }
        val ranges = MUSCLES.associateWith { range(input, it) }
        if (days.isEmpty()) return Advice(emptyList(), emptyList(), emptyList(), emptyList(), ranges, "В неделе нет зала — разбирать нечего.")
        val changes = mutableListOf<Change>()
        val fine = mutableListOf<String>()
        val held = mutableListOf<String>()
        fun name(id: String) = find(id)?.name ?: id
        fun cur() = apply(days, changes)
        fun has(pred: (Exercise) -> Boolean) = cur().any { d -> d.items.any { i -> find(i.exerciseId)?.let(pred) == true } }
        fun legSets(d: Day) = d.items.sumOf { i -> if (find(i.exerciseId)?.lowerBody == true) i.sets else 0 }
        fun legDay() = cur().filter { legSets(it) > 0 }.maxByOrNull(::legSets)
        // An item some change already concerns (one proposed to be added too): no second change on it.
        fun touched(d: Day, i: Item) = changes.any { c ->
            if (c.kind == Kind.ADD) i.added && c.templateId == d.templateId && c.to == i.exerciseId
            else c.planId == i.planId && c.added == i.added && (!i.added || c.templateId == d.templateId)
        }
        val runner = runner(input)
        val raceSoon = input.daysToRace?.let { it in 0..RACE_QUIET_DAYS } == true
        val usable = { id: String -> find(id)?.archived == false }

        // 1. Lifts without a new best for weeks: the same movement in another variation. Many stuck at once is recovery
        // (sleep, food, a lighter week), not the exercises: then only the longest stuck gets another variation.
        val stuck = days.flatMap { d -> d.items.filter { i -> i.role != SlotRole.ACCESSORY && (input.stalled[i.exerciseId] ?: 0) >= STALL_WEEKS }.map { d to it } }
        val manyStuck = stuck.size >= MANY_STUCK
        val warning = if (manyStuck) "Застой сразу в ${stuck.size} основных упражнениях. Так бывает, когда не хватает восстановления, " +
            "а не упражнений: сначала сон, еда и лёгкая неделя. Другой вариант предлагаю только для самого долгого застоя." else null
        val stallFor = if (manyStuck) stuck.sortedByDescending { (_, i) -> input.stalled[i.exerciseId] ?: 0 }.take(1) else stuck
        for ((d, i) in stallFor) {
            val weeks = input.stalled[i.exerciseId] ?: continue
            if (touched(d, i)) continue
            val ex = find(i.exerciseId) ?: continue
            // Before the race the legs get nothing new.
            if (raceSoon && ex.lowerBody) continue
            // Another variation, not one done in the week already (stuck there too, or the same work twice).
            val present = days.flatMap { d2 -> d2.items.map { it.exerciseId } }.toSet()
            val alts = VARIATIONS[ex.id].orEmpty().filter(usable).filter { it !in present && (input.stalled[it] ?: 0) < STALL_WEEKS }
            if (alts.isEmpty()) continue
            changes += Change(
                "stall:${d.templateId}:${i.planId}", Kind.SWAP, d.templateId, d.title, i.planId, i.exerciseId, alts, null, null, null, null,
                "«${ex.name}» — ${Ru.weeks(weeks)} без нового максимума. Тот же рисунок движения в другом варианте на 4–6 недель " +
                    "(другой угол, хват или пауза) нагружает мышцы иначе и обычно сдвигает застой; потом можно вернуть прежнее — сила перенесётся.",
                1, added = i.added,
            )
        }

        // 2. What a runner's legs need.
        if (runner) {
            val oneLeg = has { it.pattern == Pattern.LUNGE && it.compound } || has { it.id == "single_leg_rdl" || it.id == "pistol" }
            if (!oneLeg && !raceSoon) {
                // A one-leg lift the person swapped away: offered back first, with the easier ones.
                val swapped = cur().firstNotNullOfOrNull { d ->
                    d.items.firstOrNull { i -> !i.added && i.planId != i.exerciseId && find(i.planId)?.pattern == Pattern.LUNGE }?.let { d to it }
                }
                if (swapped != null) {
                    val (d, i) = swapped
                    val own = find(i.planId)!!
                    changes += Change(
                        "oneleg:${i.planId}", Kind.SWAP, d.templateId, d.title, i.planId, i.exerciseId,
                        (listOf(own.id) + ONE_LEG).distinct().filter(usable), null, null, null, null,
                        "Бег — череда приземлений на одну ногу: упражнение на одной ноге тренирует силу и устойчивость таза и колена так, " +
                            "как бегу нужно. «${name(i.exerciseId)}» на это не работает. Если «${own.name}» неудобны — сплит-приседания " +
                            "проще по равновесию, зашагивания ближе всего к бегу.",
                        2,
                    )
                } else legDay()?.let { d ->
                    changes += Change(
                        "oneleg:add", Kind.ADD, d.templateId, d.title, null, null, ONE_LEG.filter(usable), null, 3, 8 to 12, 120,
                        "Ни одного упражнения на одной ноге: бег — череда приземлений на одну ногу, сила и устойчивость таза и колена " +
                            "тренируются именно так.",
                        2,
                    )
                }
            }
            if (!raceSoon && !has { it.id == "nordic_curl" || it.id == "glute_ham_raise" }) legDay()?.let { d ->
                changes += Change(
                    "add:nordic", Kind.ADD, d.templateId, d.title, null, null, listOf("nordic_curl", "glute_ham_raise").filter(usable), null, 2, 4 to 8, 90,
                    "Задняя поверхность бедра в беге работает на растяжение — в быстрых отрезках особенно, там она и травмируется. " +
                        "Нордические сгибания тренируют её именно так: в исследованиях они снижали такие травмы примерно вдвое. " +
                        "Первые две недели — по 1 подходу: с непривычки сильная крепатура.",
                    2,
                )
            }
            if (!raceSoon && !has { it.id == "seated_calf" }) legDay()?.let { d ->
                changes += Change(
                    "add:seated_calf", Kind.ADD, d.templateId, d.title, null, null, listOf("seated_calf").filter(usable), null, 3, 12 to 20, 60,
                    "Камбаловидная мышца несёт на каждом шаге бега больше, чем икроножная, а стоя работает в основном икроножная. " +
                        "Подъёмы сидя (колено согнуто) нагружают именно камбаловидную — это и защита ахилла и голени.",
                    2,
                )
            }
            if (raceSoon) held += "До забега ${Ru.days(input.daysToRace!!.toInt())}: новое для ног предложу после него — " +
                "первые тренировки непривычного дают крепатуру на несколько дней."
        }

        // 3. The hinge: the back of the body, in a week that trains the legs.
        if (!raceSoon && has { it.lowerBody && it.compound } && !has { it.pattern == Pattern.HINGE && it.compound }) legDay()?.let { d ->
            changes += Change(
                "add:hinge", Kind.ADD, d.templateId, d.title, null, null, listOf("rdl", "db_rdl", "single_leg_rdl").filter(usable), null, 3, 6 to 10, 120,
                "Ноги в плане есть, а наклона нет: задняя поверхность бедра и ягодицы почти без работы в разгибании таза — " +
                    "а это сила толчка и защита спины. Румынская тяга — самый простой вариант.",
                2,
            )
        }

        // One gym session a week short for many muscles: a second day helps, cramming them into one does not.
        val sessions = days.sumOf { it.times }
        val shortNow = volume(cur(), find).let { v -> MUSCLES.filter { m -> ranges.getValue(m).first.let { lo -> lo > 0 && (v[m] ?: 0.0) < lo - 1 } } }
        val oneDayShort = sessions == 1 && shortNow.size >= 3
        if (oneDayShort) held += "Одна тренировка в неделю, а подходов не хватает сразу на ${shortNow.size} " +
            "${Ru.plural(shortNow.size, "мышцу", "мышцы", "мышц")} (${shortNow.joinToString(", ") { it.ru.lowercase() }}). В одну тренировку " +
            "всё не уместить — лучше второй день в зале (План → Поменять дни), тогда предложу, что в него поставить."

        // 4. Weekly sets per muscle. Short, with another muscle to spare: one exercise swapped for the other. Short alone:
        // more sets where it works already, else an exercise for it. Far over: a set less on its single-muscle work.
        for (m in MUSCLES) {
            if (raceSoon && m in LEGS) continue
            val (low, high) = ranges.getValue(m)
            // Decided with the changes so far (one already proposed may cover it), said in the plan's own numbers.
            val vol = volume(cur(), find)
            val vol0 = volume(days, find)
            val v = vol[m] ?: 0.0
            val v0 = vol0[m] ?: 0.0
            if (low > 0 && v < low - 1) {
                val iso = isolations(m, runner).filter(usable)
                    .filter { id -> (days + cur()).none { d -> d.items.any { it.exerciseId == id } } }
                // A single-muscle exercise for a muscle with sets to spare (in the upper half of its range, still in it
                // without these sets), on a day of the same half of the body.
                // A swap of a plan exercise holds on every day it is on: its sets all count. Not the protective work the
                // advisor itself stands for (Nordic curls, the soleus, the rear delts).
                fun weekSets(i: Item) = cur().sumOf { d2 -> d2.items.filter { !it.added && it.planId == i.planId }.sumOf { it.sets * d2.times } }
                val spare = cur().flatMap { d -> d.items.map { d to it } }.firstOrNull { (d, i) ->
                    val ex = find(i.exerciseId)
                    val sets = if (i.added) i.sets * d.times else weekSets(i)
                    ex != null && i.role == SlotRole.ACCESSORY && !ex.compound && ex.load != LoadKind.TIMED && ex.primary.isNotEmpty() &&
                        ex.id !in PROTECTIVE && ex.pattern != Pattern.REAR_DELT &&
                        m !in ex.primary && (m in LEGS) == ex.lowerBody && !touched(d, i) &&
                        ex.primary.all { o ->
                            val (ol, oh) = ranges[o] ?: (0 to 99)
                            val ov = vol[o] ?: 0.0
                            ov >= (ol + oh) / 2.0 && ov - sets >= ol + 1
                        }
                }
                if (spare != null && iso.isNotEmpty()) {
                    val (d, i) = spare
                    val o = find(i.exerciseId)!!.primary.first()
                    changes += Change(
                        "rebalance:${m.name}:${i.planId}", Kind.SWAP, d.templateId, d.title, i.planId, i.exerciseId, iso, null, null, null, null,
                        "${m.ru}: ${setsText(v0)} в неделю при норме $low–$high, а ${o.ru.lowercase()} — ${setsText(vol0[o] ?: 0.0)}, с запасом. " +
                            "Замена перекладывает подходы туда, где их не хватает, а тренировка не удлиняется.",
                        2, added = i.added,
                    )
                    continue
                }
                // More sets where it is worked first already: single-muscle and secondary lifts, not the main ones.
                var need = ceil(low - v).toInt()
                val targets = cur().flatMap { d -> d.items.map { d to it } }
                    .filter { (d, i) -> find(i.exerciseId)?.primary?.contains(m) == true && i.sets < MAX_SETS_ADVISED && i.role != SlotRole.MAIN && !touched(d, i) }
                    .sortedWith(compareBy({ -it.second.role.ordinal }, { minutes(it.first, input.timeRatio) }))
                for ((d, i) in targets) {
                    if (need <= 1) break
                    val to = minOf(MAX_SETS_ADVISED, i.sets + need)
                    changes += Change(
                        "vol:${m.name}:${i.planId}", Kind.SETS, d.templateId, d.title, i.planId, i.exerciseId, listOf(i.exerciseId), i.sets, to, null, null,
                        "${m.ru}: ${setsText(v0)} в неделю — меньше нормы для твоей цели ($low–$high). Ещё ${Ru.sets(to - i.sets)} — дешевле " +
                            "всего в упражнении, которое эту мышцу уже нагружает: без нового упражнения и почти без лишнего времени.",
                        2, added = i.added,
                    )
                    need -= (to - i.sets) * d.times
                }
                // A new exercise only for what the ones there do not do already: no second lateral raise beside the first.
                val there = targets.mapNotNull { (_, i) -> find(i.exerciseId)?.pattern }.toSet()
                val iso2 = iso.filter { id -> find(id)?.pattern !in there }
                if (need > 1 && iso2.isNotEmpty() && !oneDayShort) {
                    val iso = iso2
                    val day = if (m in LEGS) legDay() else upperDayFor(cur(), find, input.timeRatio)
                    val add = day?.let {
                        Change(
                            "add:${m.name}:${iso.first()}", Kind.ADD, it.templateId, it.title, null, null, iso, null, 3, if (m in LEGS) 8 to 12 else 10 to 15, 75,
                            "${m.ru}: ${setsText(v0)} в неделю — меньше нормы для твоей цели ($low–$high), и прямой работы на эту мышцу почти нет.",
                            2,
                        )
                    }
                    // Only what fits the time with everything proposed before it; the rest is said, not proposed.
                    val sm0 = input.sessionMinutes
                    val longer = add?.let { c -> minutes(apply(cur(), listOf(c)).first { it.templateId == c.templateId }, input.timeRatio) }
                    if (add != null && (sm0 == null || longer!! <= sm0 + 10)) changes += add
                    else if (add != null) held += "${m.ru}: ${setsText(v0)} в неделю при норме $low–$high, но с предложенным выше «${add.dayTitle}» " +
                        "уже около ${longer} мин при твоих $sm0 — ещё одно упражнение не влезет. Больше времени или ещё день в зале — и предложу."
                }
            } else if (v0 > high + 2 && v > high + 2) {
                val target = cur().flatMap { d -> d.items.map { d to it } }
                    .filter { (d, i) -> i.role == SlotRole.ACCESSORY && find(i.exerciseId)?.primary?.contains(m) == true && i.sets > 2 && !touched(d, i) }
                    .maxByOrNull { (_, i) -> i.sets }
                if (target != null) {
                    val (d, i) = target
                    changes += Change(
                        "less:${m.name}:${i.planId}", Kind.SETS, d.templateId, d.title, i.planId, i.exerciseId, listOf(i.exerciseId), i.sets, i.sets - 1, null, null,
                        "${m.ru}: ${setsText(v0)} в неделю — больше нормы ($low–$high)" + (if (runner && m in LEGS) ", а ногам ещё бегать" else "") +
                            ": лишние подходы уже не растят, а утомляют.",
                        3, added = i.added,
                    )
                }
            }
        }

        // 5. Pushing and pulling.
        val now = cur()
        val push = now.sumOf { d -> d.items.sumOf { i -> if (find(i.exerciseId)?.pattern in PUSH) i.sets * d.times else 0 } }
        val pull = now.sumOf { d -> d.items.sumOf { i -> if (find(i.exerciseId)?.pattern in PULL) i.sets * d.times else 0 } }
        // As the plan is, and with the pulls proposed above if there are any.
        val push0 = days.sumOf { d -> d.items.sumOf { i -> if (find(i.exerciseId)?.pattern in PUSH) i.sets * d.times else 0 } }
        val pulls0 = days.sumOf { d -> d.items.sumOf { i -> if (find(i.exerciseId)?.pattern in PULL) i.sets * d.times else 0 } }
        if (push > 0 && pull < push * 0.75) {
            val pulls = if (pulls0 == pull) "$pull" else "$pulls0 (с предложенными выше — $pull)"
            if (!has { it.pattern == Pattern.REAR_DELT }) upperDayFor(now, find, input.timeRatio)?.let { d ->
                changes += Change(
                    "add:pull", Kind.ADD, d.templateId, d.title, null, null, listOf("face_pull", "reverse_fly", "reverse_pec_deck").filter(usable), null, 3, 12 to 20, 60,
                    "Жимов в неделе ${Ru.sets(push0)}, тяг — $pulls: плечи тянет вперёд. Тяга к лицу — противовес жимам для задней дельты " +
                        "и лопаток, плечевой сустав скажет спасибо.",
                    3,
                )
            }
        } else if (push0 > 0 && pulls0 >= push0 * 0.75) {
            fine += "Жимы и тяги в балансе: $push0 и $pulls0 ${Ru.plural(pulls0, "подход", "подхода", "подходов")} в неделю."
        }

        // 6. Time and recovery.
        val sm = input.sessionMinutes
        for (d0 in days) {
            // The plan as it is: a day over the time only by what is proposed here says so on that proposal instead.
            val min = minutes(d0, input.timeRatio)
            if (sm == null || min <= sm + 10) continue
            val d = cur().first { it.templateId == d0.templateId }
            // As the plan fits a session into the time itself: an accessory set first, then one of a secondary lift.
            val free = d.items.filter { it.sets > 2 && !touched(d, it) && find(it.exerciseId)?.load != LoadKind.TIMED }
            val last = free.filter { it.role == SlotRole.ACCESSORY }.maxByOrNull { it.sets }
                ?: free.lastOrNull { it.role == SlotRole.SECONDARY } ?: continue
            val saves = ((last.restSec + 45) / 60.0 * input.timeRatio).roundToInt()
            changes += Change(
                "time:${d.templateId}:${last.planId}", Kind.SETS, d.templateId, d.title, last.planId, last.exerciseId, listOf(last.exerciseId), last.sets, last.sets - 1, null, null,
                "«${d.title}» выходит примерно на $min мин при твоих $sm. Подход ${if (last.role == SlotRole.ACCESSORY) "подсобного" else "второго по важности упражнения"} " +
                    "экономит около $saves мин и почти ничего не стоит: главное остаётся.",
                3, added = last.added,
            )
        }
        if (input.tiredOften) {
            val worst = cur().flatMap { d -> d.items.map { d to it } }
                .filter { (d, i) -> i.role == SlotRole.ACCESSORY && i.sets >= 3 && !touched(d, i) && find(i.exerciseId)?.load != LoadKind.TIMED }
                .sortedByDescending { (_, i) -> i.sets }.take(2)
            for ((d, i) in worst) changes += Change(
                "rest:${d.templateId}:${i.planId}", Kind.SETS, d.templateId, d.title, i.planId, i.exerciseId, listOf(i.exerciseId), i.sets, i.sets - 1, null, null,
                "Последние недели ты часто уставший: меньше подсобного — больше сил на главное" + (if (runner) " и на бег." else "."),
                3, added = i.added,
            )
        }

        val final = changes.filter { it.options.isNotEmpty() }.distinctBy { it.id }.sortedBy { it.priority }.map { c ->
            // An addition that makes its day longer than the time there is: said on it.
            if (c.kind != Kind.ADD || sm == null) return@map c
            val before = days.first { it.templateId == c.templateId }
            val with = minutes(apply(listOf(before), listOf(c)).first(), input.timeRatio)
            if (with > sm + 10) c.copy(why = c.why + " С ним «${before.title}» — около $with мин при твоих $sm.") else c
        }
        val a = volume(days, find)
        val b = volume(apply(days, final), find)
        val table = MUSCLES.map { m -> ranges.getValue(m).let { (lo, hi) -> Volume(m, a[m] ?: 0.0, b[m] ?: 0.0, lo, hi) } }
        // What is right already.
        val inRange = table.filter { it.low > 0 && it.now >= it.low - 1 && it.now <= it.high + 2 }.map { it.muscle.ru.lowercase() }
        if (inRange.isNotEmpty()) fine.add(0, "Подходов в неделю в норме: ${inRange.joinToString(", ")}.")
        if (input.stalled.isNotEmpty() && stuck.isEmpty()) fine += "Застоя нет: основные упражнения растут."
        if (sm != null) days.maxByOrNull { minutes(it, input.timeRatio) }?.let { d ->
            val min = minutes(d, input.timeRatio)
            if (min <= sm + 10) fine += "По времени укладываешься: самая длинная — «${d.title}», около $min мин при твоих $sm."
        }
        val n = final.size
        val summary = when {
            n == 0 -> "План уже хорошо собран под твою цель — менять нечего."
            final.any { it.priority == 1 } -> "$n ${Ru.plural(n, "предложение", "предложения", "предложений")}; главное — застой в основных " +
                "упражнениях. Всё на выбор: план меняется только тем, что ты отметишь."
            else -> "$n ${Ru.plural(n, "предложение", "предложения", "предложений")} — на выбор. План и так рабочий, это доводка: " +
                "меняется только то, что ты отметишь."
        }
        return Advice(days, final, table, fine, ranges, summary, warning, held)
    }

    /** What the advisor itself proposes to protect (a runner's hamstrings and calves): never the one swapped away for sets elsewhere. */
    private val PROTECTIVE = setOf("nordic_curl", "glute_ham_raise", "seated_calf", "face_pull")

    /** One-leg lifts, in order of preference for a runner. */
    private val ONE_LEG = listOf("bulgarian", "split_squat", "step_up", "reverse_lunge")

    /** The day for upper-body work: one without heavy legs, the shortest. */
    private fun upperDayFor(days: List<Day>, find: (String) -> Exercise?, ratio: Double): Day? =
        days.filter { d -> d.items.none { i -> find(i.exerciseId)?.let { it.lowerBody && it.compound } == true } }.minByOrNull { minutes(it, ratio) }
            ?: days.minByOrNull { minutes(it, ratio) }

    /** Exercises that work [m] first, to add or swap in (built in, so there in most gyms), the best first. */
    private fun isolations(m: Muscle, runner: Boolean): List<String> = when (m) {
        Muscle.CHEST -> listOf("cable_fly", "pec_deck", "db_fly")
        Muscle.BACK -> listOf("cable_row", "chest_supported_row", "lat_pulldown")
        Muscle.SHOULDERS -> listOf("lateral_raise", "cable_lateral", "machine_lateral")
        Muscle.BICEPS -> listOf("db_curl", "hammer_curl", "cable_curl")
        Muscle.TRICEPS -> listOf("rope_pushdown", "pushdown", "cable_overhead_ext")
        Muscle.QUADS -> if (runner) listOf("split_squat", "step_up", "leg_ext") else listOf("leg_ext", "leg_press", "split_squat")
        Muscle.HAMSTRINGS -> if (runner) listOf("nordic_curl", "seated_leg_curl", "leg_curl") else listOf("seated_leg_curl", "leg_curl", "nordic_curl")
        Muscle.GLUTES -> listOf("hip_thrust", "glute_bridge", "cable_kickback")
        Muscle.CALVES -> if (runner) listOf("seated_calf", "calf_raise") else listOf("calf_raise", "seated_calf")
        else -> emptyList()
    }

    /** Variations people switch to when a lift stalls (Catalog ids, the closest first). */
    private val VARIATIONS = mapOf(
        "squat" to listOf("pause_squat", "front_squat", "box_squat"),
        "front_squat" to listOf("squat", "pause_squat"),
        "bench" to listOf("pause_bench", "close_grip_bench", "db_bench"),
        "db_bench" to listOf("bench", "db_incline"),
        "ohp" to listOf("seated_ohp", "db_shoulder", "push_press"),
        "db_shoulder" to listOf("ohp", "arnold_press", "machine_shoulder_press"),
        "deadlift" to listOf("trap_bar_deadlift", "deficit_deadlift", "rack_pull"),
        "rdl" to listOf("db_rdl", "sldl", "good_morning"),
        "row" to listOf("pendlay_row", "chest_supported_row", "db_row"),
        "pullup" to listOf("chinup", "neutral_pullup", "lat_pulldown"),
        "lat_pulldown" to listOf("close_pulldown", "pullup", "neutral_pullup"),
        "db_incline" to listOf("incline_bench", "db_bench"),
        "incline_bench" to listOf("db_incline", "incline_machine_press"),
        "leg_press" to listOf("hack_squat", "pendulum_squat", "bulgarian"),
        "bulgarian" to listOf("split_squat", "step_up", "reverse_lunge"),
        "cable_row" to listOf("machine_row", "db_row", "chest_supported_row"),
        "hip_thrust" to listOf("machine_hip_thrust", "glute_bridge"),
        "dip" to listOf("close_grip_bench", "machine_dip"),
    )

    /** "6 подходов", "6,5 подхода". */
    fun setsText(v: Double): String =
        if (v % 1.0 == 0.0) Ru.sets(v.roundToInt()) else "${Ru.one(v)} подхода"
}
