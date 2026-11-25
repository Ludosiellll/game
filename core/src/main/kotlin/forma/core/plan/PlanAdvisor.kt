package forma.core.plan

import forma.core.model.Aim
import forma.core.model.Equipment
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
 * - a session longer than the time there is; being worn down often; a race coming (nothing new for the legs before it);
 * - what the person does ([Habits]): their own swaps stay, what they swapped away, took out, skip or hid is never offered,
 *   hard unusual exercises (Nordic curls, Bulgarian split squats…) never for one who avoids them and last for others, the
 *   plan set to the sets they really do, machines first for one who goes for machines.
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
        /** What the person does: their choices and their sessions. */
        val habits: Habits = Habits(),
        /** The groups the person asked to work more (the wizard's «акценты»): their own band in [range]. */
        val accents: Set<Muscle> = emptySet(),
        /** The week as laid out (which day has which run and session, in order); null — not known. */
        val week: WeekPlan? = null,
        val find: (String) -> Exercise?,
    )

    /**
     * What the person does, from their plan edits and sessions. Keys of [doneSets] and [skipped]: the plan's exercise id,
     * or "templateId:exerciseId" for one added for good ([key]).
     */
    data class Habits(
        /**
         * Exercise → why it is not offered, said after its name: "ты убрал из плана", "ты пропускаешь в 3 из 4 тренировок".
         * The person's swaps in the plan are seen from the plan itself ([Item.planId] ≠ [Item.exerciseId]).
         */
        val avoided: Map<String, String> = emptyMap(),
        /** Exercise place → sets usually done, when fewer than planned. */
        val doneSets: Map<String, Int> = emptyMap(),
        /** Exercise place → (sessions skipped, sessions), when skipped often. */
        val skipped: Map<String, Pair<Int, Int>> = emptyMap(),
        /** Why the person seems to avoid hard unusual exercises; null — no sign of it. */
        val avoidsHard: String? = null,
        /** Does hard unusual exercises regularly: they may come first. */
        val likesHard: Boolean = false,
        /** Swaps toward machines and cables: those first among options. */
        val machinesFirst: Boolean = false,
        /** Change ids the person said not to propose. */
        val hidden: Set<String> = emptySet(),
    )

    /** The key of an exercise's place for [Habits]: the plan's id, or the day and the exercise for one added for good. */
    fun key(templateId: String, i: Item) = if (i.added) "$templateId:${i.exerciseId}" else i.planId

    /**
     * Hard unusual exercises: hard on the body or on technique, with soreness or balance to learn. Not offered to one who
     * avoids them, last among options for others.
     */
    val HARD = setOf(
        "nordic_curl", "glute_ham_raise", "bulgarian", "pistol", "sissy_squat", "zercher_squat", "deficit_deadlift",
        "good_morning", "power_clean", "handstand_pushup", "single_leg_rdl",
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
        /** What is not proposed now, and why (a race coming, no time for more, the person's own choice). */
        val held: List<String> = emptyList(),
        /** How many changes the person said not to propose (they can bring them back). */
        val hidden: Int = 0,
        /**
         * One line for the plan screen's card when an offer should be seen without opening this screen (a runner who wants
         * bigger legs below the band); null otherwise.
         */
        val nudge: String? = null,
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

    /** Running that asks things of the legs (endurance or speed, twice a week or more): not a jog for health. */
    fun runner(aim: Aim, runs: Int) = aim.normalized().run.let { it == RunFocus.ENDURANCE || it == RunFocus.SPEED } && runs >= 2

    fun runner(input: Input) = runner(input.aim, input.runs)

    /** The leg muscles a size aim grows; not the calves (no calf work is added for size). */
    private val THIGHS = setOf(Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES)

    /**
     * Below this many hard sets a week a leg muscle still grows, but little: about 4 is the smallest dose with growth that
     * shows, 10 and more is where growth is clear (Pelland 2026, Currier 2026) — 8 is the least a runner's legs grow on.
     */
    const val LEG_GROWTH = 8

    /** A runner's legs: the least that works, and the most before more sets cost the running more than they add. */
    const val RUNNER_LEGS_MIN = 4
    const val RUNNER_LEGS_MAX = 12

    /** A runner's thigh muscle the person wants bigger: a size focus («Мышцы», «Сила и масса») or an accent on it. */
    fun growsLegs(aim: Aim, runs: Int, accents: Set<Muscle>, m: Muscle): Boolean =
        runner(aim, runs) && m in THIGHS && (aim.normalized().lift.let { it == LiftFocus.MUSCLE || it == LiftFocus.POWERBUILDING } || m in accents)

    /** Weekly sets a muscle wants for [input]: the table of [range] (the screens read it too, [ranges]). */
    fun range(input: Input, m: Muscle): Pair<Int, Int> = range(input.aim, input.experience, input.runs, input.accents, m)

    /** [range] for every muscle: one table for «Идеальный план», the program wizard and «Мышцы за 7 дней». */
    fun ranges(aim: Aim, experience: Experience, runs: Int, accents: Set<Muscle>): Map<Muscle, Pair<Int, Int>> =
        Muscle.entries.associateWith { range(aim, experience, runs, accents, it) }

    /**
     * Weekly sets a muscle wants: by focus and experience; a runner's legs by the aim; the arms work in presses and pulls
     * too; an accented group up to the accents' own weekly ceiling ([Accents.MAX_WEEK]), not «too many» below it.
     */
    fun range(aim: Aim, experience: Experience, runs: Int, accents: Set<Muscle>, m: Muscle): Pair<Int, Int> {
        val (lo, hi) = when (aim.normalized().lift) {
            LiftFocus.MUSCLE -> 10 to 20
            LiftFocus.POWERBUILDING -> 8 to 18
            LiftFocus.STRENGTH -> 6 to 15
            else -> 6 to 12
        }
        val low = if (experience == Experience.NOVICE) lo - 2 else lo
        val runner = runner(aim, runs)
        val band = when {
            m == Muscle.CALVES -> if (runner) 4 to 12 else 0 to hi
            // Research legs-norm-for-runners+D3, the owner's decision of 25 Nov: running does not stop leg growth on average
            // (Schumann 2022), so a runner who wants bigger legs has the size floor — 10 for «Мышцы», 8 for «Сила и масса»
            // or an accent — not the 4 that only works; 12 stays the ceiling (12, 18 and 24 sets grew thighs alike, Aube
            // 2022, and the legs still run). The floor is the growth floor, so a novice's is not lower.
            runner && growsLegs(aim, runs, accents, m) -> maxOf(lo, LEG_GROWTH) to minOf(hi, RUNNER_LEGS_MAX)
            runner && m in THIGHS -> RUNNER_LEGS_MIN to minOf(hi, RUNNER_LEGS_MAX)
            m == Muscle.BICEPS || m == Muscle.TRICEPS -> (low - 2).coerceAtLeast(2) to hi
            else -> low to hi
        }
        // The accent itself adds up to this much: more is what the person asked for, not sets that only tire.
        return if (m in accents) band.first to maxOf(band.second, Accents.MAX_WEEK.toInt()) else band
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
        // The person's own swaps, seen in the plan itself: what they swapped away is theirs to bring back, not the advisor's.
        val ownSwaps = days.flatMap { it.items }.filter { !it.added && it.planId != it.exerciseId }
        val h = input.habits.let { h0 ->
            val swappedAway = ownSwaps.associate { it.planId to "ты сам заменил на «${name(it.exerciseId)}»" }
            val toMachines = ownSwaps.count { find(it.planId)?.equipment !in MACHINES && find(it.exerciseId)?.equipment in MACHINES }
            val fromMachines = ownSwaps.count { find(it.planId)?.equipment in MACHINES && find(it.exerciseId)?.equipment !in MACHINES }
            h0.copy(
                avoided = swappedAway + h0.avoided,
                avoidsHard = h0.avoidsHard ?: ownSwaps.firstOrNull { it.planId in HARD }?.let { "«${name(it.planId)}» ты сам заменил на «${name(it.exerciseId)}»" },
                machinesFirst = h0.machinesFirst || toMachines > fromMachines,
            )
        }
        // Never what the person turned away from; hard unusual exercises never for one who avoids them.
        val usable = { id: String -> find(id)?.archived == false && id !in h.avoided && !(h.avoidsHard != null && id in HARD) }
        // Calf work taken out of the plan or marked «не хочу» is turned down as a whole, not one exercise of it: another
        // calf exercise is not offered in its place, nor the calves counted as short (Denis took the calf raise out and said
        // twice not to bring calf work back, 24 November — and was still offered the seated raise).
        val noCalves = input.habits.avoided.keys.any { find(it)?.pattern == Pattern.CALF }
        // Options in the person's order: machines first for one who goes for them, hard unusual ones last unless liked.
        fun prefer(ids: List<String>): List<String> {
            val ok = ids.filter(usable)
            val byKind = if (h.machinesFirst) ok.sortedBy { if (find(it)?.equipment in MACHINES) 0 else 1 } else ok
            return if (h.likesHard) byKind else byKind.sortedBy { if (it in HARD) 1 else 0 }
        }
        fun key(d: Day, i: Item) = key(d.templateId, i)
        // Not done as planned anyway: no more sets put on it, the first to lose one.
        fun cutShort(d: Day, i: Item) = key(d, i) in h.doneSets || key(d, i) in h.skipped

        // 0. The plan as the person does it: an exercise skipped often — the same muscle another way; sets usually cut
        // short — the plan set to what is done.
        for (d in days) for (i in d.items) {
            val ex = find(i.exerciseId) ?: continue
            val k = key(d, i)
            val skip = h.skipped[k]
            if (skip != null) {
                val (n, of) = skip
                val m = ex.primary.firstOrNull()
                val present = days.flatMap { d2 -> d2.items.map { it.exerciseId } }.toSet()
                val alts = prefer((VARIATIONS[ex.id].orEmpty() + (m?.let { isolations(it, runner) } ?: emptyList())).distinct())
                    .filter { it != ex.id && it !in present && find(it)?.let { a -> a.compound == ex.compound || i.role == SlotRole.ACCESSORY } == true }
                if (alts.isNotEmpty()) changes += Change(
                    "skip:$k", Kind.SWAP, d.templateId, d.title, i.planId, i.exerciseId, alts, null, null, null, null,
                    "«${ex.name}» ты пропускаешь в $n из $of последних тренировок. Похоже, неудобно или не нравится. " +
                        "Вот другие упражнения на ту же мышцу${m?.let { " (${it.ru.lowercase()})" } ?: ""}: пусть в плане будет то, что ты правда делаешь.",
                    2, added = i.added,
                ) else held += "«${ex.name}» ты пропускаешь в $n из $of тренировок. Замены на ту же мышцу я не нашёл. Убрать упражнение можно в «Плане»."
                continue
            }
            val usual = h.doneSets[k] ?: continue
            if (usual in 1 until i.sets) changes += Change(
                "real:$k", Kind.SETS, d.templateId, d.title, i.planId, i.exerciseId, listOf(i.exerciseId), i.sets, usual, null, null,
                "«${ex.name}»: обычно ты делаешь ${Ru.sets(usual)} из ${i.sets}. Поставь в план $usual — так план совпадёт с тем, что ты " +
                    "делаешь, и время тренировки посчитается верно.",
                3, added = i.added,
            )
        }

        // 1. Lifts without a new best for weeks: the same movement in another variation. Many stuck at once is recovery
        // (sleep, food, a lighter week), not the exercises: then only the longest stuck gets another variation.
        val stuck = days.flatMap { d -> d.items.filter { i -> i.role != SlotRole.ACCESSORY && (input.stalled[i.exerciseId] ?: 0) >= STALL_WEEKS }.map { d to it } }
        val manyStuck = stuck.size >= MANY_STUCK
        val warning = if (manyStuck) "Сразу в ${stuck.size} основных упражнениях веса стоят на месте. Обычно это значит, что телу не хватает отдыха. " +
            "Сначала сон, еда и лёгкая неделя. Замену предлагаю только там, где веса стоят дольше всего." else null
        val stallFor = if (manyStuck) stuck.sortedByDescending { (_, i) -> input.stalled[i.exerciseId] ?: 0 }.take(1) else stuck
        for ((d, i) in stallFor) {
            val weeks = input.stalled[i.exerciseId] ?: continue
            if (touched(d, i) || key(d, i) in h.skipped) continue
            val ex = find(i.exerciseId) ?: continue
            // Before the race the legs get nothing new.
            if (raceSoon && ex.lowerBody) continue
            // Another variation, not one done in the week already (stuck there too, or the same work twice).
            val present = days.flatMap { d2 -> d2.items.map { it.exerciseId } }.toSet()
            val alts = VARIATIONS[ex.id].orEmpty().filter(usable).filter { it !in present && (input.stalled[it] ?: 0) < STALL_WEEKS }
            if (alts.isEmpty()) continue
            changes += Change(
                "stall:${d.templateId}:${i.planId}", Kind.SWAP, d.templateId, d.title, i.planId, i.exerciseId, alts, null, null, null, null,
                "«${ex.name}»: ${Ru.weeks(weeks)} без нового рекорда. Попробуй на 4–6 недель другой вариант того же движения: " +
                    "другой угол, хват или пауза. Мышцы получат новую нагрузку, и вес обычно снова растёт. Потом можно вернуть прежнее — сила сохранится.",
                1, added = i.added,
            )
        }

        // 2. What a runner's legs need.
        if (runner) {
            val oneLeg = has { it.pattern == Pattern.LUNGE && it.compound } || has { it.id == "single_leg_rdl" || it.id == "pistol" }
            if (!oneLeg && !raceSoon) {
                // A one-leg lift the person swapped away or took out is their choice: not undone, only said.
                val turned = h.avoided.entries.firstOrNull { (id, _) -> find(id)?.let { it.pattern == Pattern.LUNGE && it.compound } == true }
                val easiest = listOf("step_up", "reverse_lunge", "split_squat").firstOrNull(usable)
                if (turned != null) {
                    held += "Упражнения на одной ноге полезны для бега: на каждом шаге ты приземляешься на одну ногу. Но «${name(turned.key)}» " +
                        "${turned.value} — это твой выбор, обратно не предлагаю." +
                        (easiest?.let { " Захочешь попробовать — проще всего «${name(it)}» без веса или с лёгкими гантелями." } ?: "")
                } else legDay()?.let { d ->
                    changes += Change(
                        "oneleg:add", Kind.ADD, d.templateId, d.title, null, null, prefer(ONE_LEG), null, 3, 8 to 12, 120,
                        "В плане нет упражнений на одной ноге. А в беге ты на каждом шаге приземляешься на одну ногу. " +
                            "Так тренируются сила и устойчивость таза и колена.",
                        2,
                    )
                }
            }
            if (!raceSoon && !has { it.id == "nordic_curl" || it.id == "glute_ham_raise" }) legDay()?.let { d ->
                val nordic = prefer(listOf("nordic_curl", "glute_ham_raise"))
                val curl = cur().flatMap { it.items }.firstOrNull { find(it.exerciseId)?.pattern == Pattern.KNEE_FLEXION }
                val why = "В беге задняя поверхность бедра работает, растягиваясь. Особенно в быстрых отрезках — там она чаще всего и травмируется."
                when {
                    nordic.isNotEmpty() -> changes += Change(
                        "add:nordic", Kind.ADD, d.templateId, d.title, null, null, nordic, null, 2, 4 to 8, 90,
                        "$why Нордические сгибания тренируют её именно так. В исследованиях они снижали такие травмы примерно вдвое. " +
                            "Первые две недели делай по 1 подходу: с непривычки мышцы сильно болят." +
                            (if (h.likesHard) "" else " Упражнение тяжёлое и непривычное. Не хочешь — нажми «Не предлагать»."),
                        2,
                    )
                    // Not for one who avoids hard unusual exercises: the curl they do, done slower, gives part of it.
                    curl != null -> held += "Нордические сгибания не предлагаю: " +
                        (h.avoided["nordic_curl"]?.let { "их $it" } ?: h.avoidsHard?.let { "$it — похоже, тяжёлые непривычные упражнения тебе не нравятся" } ?: "ты их не делаешь") +
                        ". Часть той же пользы даст «${name(curl.exerciseId)}», если опускать вес медленно, за 3–4 секунды. $why"
                    else -> prefer(listOf("seated_leg_curl", "leg_curl")).takeIf { it.isNotEmpty() }?.let { opts ->
                        changes += Change(
                            "add:hamstrings", Kind.ADD, d.templateId, d.title, null, null, opts, null, 2, 10 to 15, 75,
                            "$why Сгибание ног тренирует её так же, если опускать вес медленно, за 3–4 секунды. Ничего непривычного.",
                            2,
                        )
                    }
                }
            }
            if (!raceSoon && !noCalves && !has { it.id == "seated_calf" }) legDay()?.let { d ->
                changes += Change(
                    "add:seated_calf", Kind.ADD, d.templateId, d.title, null, null, listOf("seated_calf").filter(usable), null, 3, 12 to 20, 60,
                    "На каждом шаге бега больше всего работает камбаловидная мышца, она лежит под икроножной. Подъёмы на носки стоя " +
                        "качают в основном икроножную. А сидя, с согнутым коленом, работает как раз камбаловидная. Это защищает ахилл и голень.",
                    2,
                )
            }
            if (raceSoon) held += "До забега ${Ru.days(input.daysToRace!!.toInt())}. Новое для ног предложу после него: " +
                "от непривычных упражнений ноги болят несколько дней."
        }

        // 3. The hinge: the back of the body, in a week that trains the legs.
        if (!raceSoon && has { it.lowerBody && it.compound } && !has { it.pattern == Pattern.HINGE && it.compound }) legDay()?.let { d ->
            changes += Change(
                "add:hinge", Kind.ADD, d.templateId, d.title, null, null, prefer(listOf("rdl", "db_rdl", "single_leg_rdl")), null, 3, 6 to 10, 120,
                "Ноги в плане есть, а упражнений с наклоном вперёд нет. Без них задняя поверхность бедра и ягодицы недорабатывают. " +
                    "А от них зависят сила толчка и здоровая спина. Проще всего начать с румынской тяги.",
                2,
            )
        }

        // Short of sets in the steps below: under the band less one. A runner's legs only under the 4 that works — one who
        // wants bigger legs gets them a step at a time (3b), not every set up to the band at once.
        fun floorOf(m: Muscle) = ranges.getValue(m).first.let { if (runner && m in THIGHS) minOf(it, RUNNER_LEGS_MIN) else it }

        // One gym session a week short for many muscles: a second day helps, cramming them into one does not.
        val sessions = days.sumOf { it.times }
        val shortNow = volume(cur(), find).let { v -> MUSCLES.filter { m -> floorOf(m).let { lo -> lo > 0 && (v[m] ?: 0.0) < lo - 1 } } }
        val oneDayShort = sessions == 1 && shortNow.size >= 3
        if (oneDayShort) held += "Зал раз в неделю, а подходов не хватает сразу на ${shortNow.size} " +
            "${Ru.plural(shortNow.size, "мышцу", "мышцы", "мышц")} (${shortNow.joinToString(", ") { it.ru.lowercase() }}). В одну тренировку " +
            "всё не влезет. Лучше добавь второй день в зале: «План» → «Изменить цель, дни и данные». Тогда предложу, что в него поставить."

        // 3b. A runner who wants bigger legs, below the band (research legs-norm-for-runners+D3 and part (a) of one-leg-day,
        // the owner's decision of 25 Nov: his gym plan changes only by what he takes, Egor's copy decides for itself). A
        // step at a time, the back of the thigh first — squats barely grow it (Kubo 2019): a set more of the leg curl, then
        // of the leg extension, on the leg day (added there when the week has none); then two light sets of one of them at
        // the end of the upper session after the week's second hard run, the run first that day — legs twice a week, so a
        // missed leg day still leaves some leg work (Schoenfeld 2019: at equal weekly sets, how often barely matters). That
        // session stays an upper one ([Template.legs]): the week's layout and the checks around hard runs stay as they are.
        // No calf or shin work: the calves are not among these muscles.
        // Over the time there is with [c] and everything proposed before it: the minutes it would take, else null.
        fun over(c: Change): Int? = input.sessionMinutes?.let { sm ->
            minutes(apply(cur(), listOf(c)).first { it.templateId == c.templateId }, input.timeRatio).takeIf { it > sm + 10 }
        }
        fun nb(v: Double) = Ru.nobreak(setsText(v))
        val legsShort = listOf(Muscle.HAMSTRINGS, Muscle.QUADS).filter { m ->
            growsLegs(input.aim, input.runs, input.accents, m) && (volume(cur(), find)[m] ?: 0.0) < ranges.getValue(m).first
        }
        if (legsShort.isNotEmpty() && !raceSoon) {
            val v0 = volume(days, find)
            if (input.tiredOften) {
                val m = legsShort.first()
                val (low, high) = ranges.getValue(m)
                held += "${nb(v0[m] ?: 0.0)} в неделю на ${m.ru.lowercase()} при норме $low–$high, но пока ты часто устаёшь, больше подходов не предлагаю."
            } else {
                for (m in legsShort) {
                    val (low, high) = ranges.getValue(m)
                    val flexion = m == Muscle.HAMSTRINGS
                    val ids = if (flexion) listOf("leg_curl", "seated_leg_curl") else listOf("leg_ext")
                    val pattern = if (flexion) Pattern.KNEE_FLEXION else Pattern.KNEE_EXTENSION
                    val why = "${nb(v0[m] ?: 0.0)} в неделю на ${m.ru.lowercase()} при норме $low–$high, " +
                        if (flexion) "а приседания его почти не качают." else "а разгибание ног бегу почти не мешает."
                    val leg = legDay()
                    // The same movement there already, on the leg day first: a set more of it, as it is done. Another exercise
                    // of it (a Nordic curl, proposed or done) is left as it is; none in the week — the exercise is added.
                    val there = cur().flatMap { d -> d.items.map { d to it } }.filter { (_, i) -> find(i.exerciseId)?.pattern == pattern }
                        .sortedBy { (d, _) -> if (d.templateId == leg?.templateId) 0 else 1 }
                    val more = when {
                        there.isNotEmpty() -> there.firstOrNull { (d, i) ->
                            i.exerciseId in ids && usable(i.exerciseId) && i.sets < MAX_SETS_ADVISED && !touched(d, i) && !cutShort(d, i)
                        }?.let { (d, i) ->
                            Change(
                                "legs:${m.name}", Kind.SETS, d.templateId, d.title, i.planId, i.exerciseId, listOf(i.exerciseId), i.sets,
                                i.sets + 1, null, null, why, 2, added = i.added,
                            )
                        }
                        oneDayShort -> null
                        else -> leg?.let { d ->
                            prefer(ids).takeIf { it.isNotEmpty() }?.let { opts ->
                                Change("legs:${m.name}", Kind.ADD, d.templateId, d.title, null, null, opts, null, 2, 10 to 15, 90, why, 2)
                            }
                        }
                    }
                    val c = more ?: continue
                    val longer = over(c)
                    if (longer == null) changes += c
                    else held += "${nb(v0[m] ?: 0.0)} в неделю на ${m.ru.lowercase()} при норме $low–$high, но «${c.dayTitle}» с этим займёт около " +
                        "$longer\u00A0мин из твоих ${input.sessionMinutes}."
                }
                // Two light sets after the week's second hard run: the curl while the hamstrings are short, else the extension.
                val after = input.week?.let { afterHardRun(it, days, find) }
                if (after != null && !oneDayShort) {
                    val (weekday, d) = after
                    val vNow = volume(cur(), find)
                    val stillShort = legsShort.filter { m -> (vNow[m] ?: 0.0) < ranges.getValue(m).first }
                    val opts = prefer(if (Muscle.QUADS in stillShort && Muscle.HAMSTRINGS !in stillShort) listOf("leg_ext", "leg_curl") else listOf("leg_curl", "leg_ext"))
                    val legTitle = legDay()?.title
                    if (opts.isNotEmpty()) {
                        val c = Change(
                            "legs:after-run", Kind.ADD, d.templateId, d.title, null, null, opts, null, 2, 10 to 15, 90,
                            "2\u00A0подхода ${ON_DAY[weekday - 1]} в конце тренировки после бега, с запасом 2–3\u00A0повтора и без «+»: так ноги " +
                                "работают дважды в неделю" + (legTitle?.let { ", и пропуск тренировки «$it» не оставит неделю без них" } ?: "") + ".",
                            2,
                        )
                        val longer = over(c)
                        if (longer == null) changes += c
                        else held += "2\u00A0подхода на ноги ${ON_DAY[weekday - 1]} не влезут, «${d.title}» с ними займёт около " +
                            "$longer\u00A0мин из твоих ${input.sessionMinutes}."
                    }
                }
            }
        }

        // 4. Weekly sets per muscle. Short, with another muscle to spare: one exercise swapped for the other. Short alone:
        // more sets where it works already, else an exercise for it. Far over: a set less on its single-muscle work.
        for (m in MUSCLES) {
            if (raceSoon && m in LEGS) continue
            if (noCalves && m == Muscle.CALVES) continue
            val (low, high) = ranges.getValue(m)
            val floor = floorOf(m)
            // Decided with the changes so far (one already proposed may cover it), said in the plan's own numbers.
            val vol = volume(cur(), find)
            val vol0 = volume(days, find)
            val v = vol[m] ?: 0.0
            val v0 = vol0[m] ?: 0.0
            if (floor > 0 && v < floor - 1) {
                val iso = prefer(isolations(m, runner))
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
                        ex.id !in PROTECTIVE && ex.pattern != Pattern.REAR_DELT && !i.added &&
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
                        "${m.ru}: ${setsText(v0)} в неделю, а норма — $low–$high. Зато ${o.ru.lowercase()} — ${setsText(vol0[o] ?: 0.0)}, это с запасом. " +
                            "Замена перенесёт подходы туда, где их мало, и тренировка не станет длиннее.",
                        2, added = i.added,
                    )
                    continue
                }
                // More sets where it is worked first already: single-muscle and secondary lifts, not the main ones.
                var need = ceil(floor - v).toInt()
                val targets = cur().flatMap { d -> d.items.map { d to it } }
                    .filter { (d, i) -> find(i.exerciseId)?.primary?.contains(m) == true && i.sets < MAX_SETS_ADVISED && i.role != SlotRole.MAIN && !touched(d, i) && !cutShort(d, i) }
                    .sortedWith(compareBy({ -it.second.role.ordinal }, { minutes(it.first, input.timeRatio) }))
                for ((d, i) in targets) {
                    if (need <= 1) break
                    val to = minOf(MAX_SETS_ADVISED, i.sets + need)
                    changes += Change(
                        "vol:${m.name}:${i.planId}", Kind.SETS, d.templateId, d.title, i.planId, i.exerciseId, listOf(i.exerciseId), i.sets, to, null, null,
                        "${m.ru}: ${setsText(v0)} в неделю, а норма для твоей цели — $low–$high. Проще всего добавить ${Ru.sets(to - i.sets)} " +
                            "в упражнение, которое уже качает эту мышцу. Новое не нужно, и времени почти не прибавится.",
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
                            "${m.ru}: ${setsText(v0)} в неделю, а норма для твоей цели — $low–$high. Упражнений именно на эту мышцу в плане почти нет.",
                            2,
                        )
                    }
                    // Only what fits the time with everything proposed before it; the rest is said, not proposed.
                    val sm0 = input.sessionMinutes
                    val longer = add?.let { c -> minutes(apply(cur(), listOf(c)).first { it.templateId == c.templateId }, input.timeRatio) }
                    if (add != null && (sm0 == null || longer!! <= sm0 + 10)) changes += add
                    else if (add != null) held += "${m.ru}: ${setsText(v0)} в неделю, а норма — $low–$high. Но с тем, что предложено выше, «${add.dayTitle}» " +
                        "уже около ${longer} мин, а у тебя $sm0. Ещё одно упражнение не влезет. Будет больше времени или ещё день в зале — предложу."
                }
            } else if (v0 > high + 2 && v > high + 2) {
                val target = cur().flatMap { d -> d.items.map { d to it } }
                    .filter { (d, i) -> i.role == SlotRole.ACCESSORY && find(i.exerciseId)?.primary?.contains(m) == true && i.sets > 2 && !touched(d, i) }
                    .sortedWith(compareBy({ if (cutShort(it.first, it.second)) 0 else 1 }, { if (it.second.added) 1 else 0 }, { -it.second.sets }))
                    .firstOrNull()
                if (target != null) {
                    val (d, i) = target
                    // Research legs-norm-for-runners+D3 (owner, 25 Nov): more sets still grow a muscle, only less and less
                    // (Pelland 2026) — not «они уже не растут».
                    changes += Change(
                        "less:${m.name}:${i.planId}", Kind.SETS, d.templateId, d.title, i.planId, i.exerciseId, listOf(i.exerciseId), i.sets, i.sets - 1, null, null,
                        "${m.ru}: ${Ru.nobreak(setsText(v0))} в неделю при норме $low–$high, от лишних подходов мышцы растут уже мало, а усталость копится" +
                            (if (runner && m in LEGS) " и мешает бегу." else "."),
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
                    "add:pull", Kind.ADD, d.templateId, d.title, null, null, prefer(listOf("face_pull", "reverse_fly", "reverse_pec_deck")), null, 3, 12 to 20, 60,
                    "Жимов в неделе ${Ru.sets(push0)}, а тяг — $pulls. Так плечи заваливаются вперёд. Тяга к лицу уравновешивает жимы: " +
                        "работают задняя часть плеча и лопатки. Плечам так здоровее.",
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
            val order = compareBy<Item>({ if (cutShort(d, it)) 0 else 1 }, { if (it.added) 1 else 0 }, { -it.sets })
            val last = free.filter { it.role == SlotRole.ACCESSORY }.sortedWith(order).firstOrNull()
                ?: free.lastOrNull { it.role == SlotRole.SECONDARY } ?: continue
            val saves = ((last.restSec + 45) / 60.0 * input.timeRatio).roundToInt()
            changes += Change(
                "time:${d.templateId}:${last.planId}", Kind.SETS, d.templateId, d.title, last.planId, last.exerciseId, listOf(last.exerciseId), last.sets, last.sets - 1, null, null,
                "«${d.title}» идёт около $min мин, а у тебя $sm. Если убрать подход ${if (last.role == SlotRole.ACCESSORY) "в добивающем упражнении" else "во вспомогательном упражнении"}, " +
                    "сэкономишь около $saves мин. Главное останется.",
                3, added = last.added,
            )
        }
        if (input.tiredOften) {
            val worst = cur().flatMap { d -> d.items.map { d to it } }
                // A runner's calves are not the extra work to drop when tired: they keep the legs whole for running.
                .filter { (d, i) -> i.role == SlotRole.ACCESSORY && i.sets >= 3 && !touched(d, i) && find(i.exerciseId)?.load != LoadKind.TIMED &&
                    !(runner && find(i.exerciseId)?.pattern == Pattern.CALF) }
                .sortedWith(compareBy({ if (cutShort(it.first, it.second)) 0 else 1 }, { if (it.second.added) 1 else 0 }, { -it.second.sets })).take(2)
            for ((d, i) in worst) changes += Change(
                "rest:${d.templateId}:${i.planId}", Kind.SETS, d.templateId, d.title, i.planId, i.exerciseId, listOf(i.exerciseId), i.sets, i.sets - 1, null, null,
                "Последние недели ты часто устаёшь. Меньше добивающих подходов — больше сил на главное" + (if (runner) " и на бег." else "."),
                3, added = i.added,
            )
        }

        val shown = changes.filter { it.options.isNotEmpty() }.distinctBy { it.id }
        val final = shown.filter { it.id !in h.hidden }.sortedBy { it.priority }.map { c ->
            // An addition that makes its day longer than the time there is: said on it.
            if (c.kind != Kind.ADD || sm == null) return@map c
            val before = days.first { it.templateId == c.templateId }
            val with = minutes(apply(listOf(before), listOf(c)).first(), input.timeRatio)
            if (with > sm + 10) c.copy(why = c.why + " С ним «${before.title}» займёт около $with мин, а у тебя $sm.") else c
        }
        val a = volume(days, find)
        val b = volume(apply(days, final), find)
        val table = MUSCLES.map { m -> ranges.getValue(m).let { (lo, hi) -> Volume(m, a[m] ?: 0.0, b[m] ?: 0.0, lo, hi) } }
        // What is right already. A runner's legs only from the growth floor (research legs-norm-for-runners+D3, owner 25 Nov):
        // 4–7 sets work but grow little — said so, never «в норме».
        val inRange = table.filter {
            it.low > 0 && it.now >= (if (runner && it.muscle in THIGHS) maxOf(it.low, LEG_GROWTH).toDouble() else it.low - 1.0) && it.now <= it.high + 2
        }.map { it.muscle.ru.lowercase() }
        if (inRange.isNotEmpty()) fine.add(0, "Подходов в неделю в норме: ${inRange.joinToString(", ")}.")
        val little = table.filter { runner && it.muscle in THIGHS && it.low < LEG_GROWTH && it.now >= it.low - 1 && it.now < LEG_GROWTH }
        if (little.isNotEmpty()) {
            val lo = little.minOf { it.now }
            val hi = little.maxOf { it.now }
            val names = little.map { it.muscle.ru.lowercase() }.let { l -> if (l.size == 1) l[0] else l.dropLast(1).joinToString(", ") + " и " + l.last() }
            held += (if (lo == hi) "" else "${num(lo)}–") + Ru.nobreak(setsText(hi)) + " в неделю на $names работают, но для роста мышц это мало."
        }
        // An offer for the legs is seen on the plan's card too, without opening this screen.
        val legOffers = final.count { it.id.startsWith("legs:") }
        val nudge = if (legOffers == 0) null else table.filter { it.muscle in THIGHS && it.now < it.low }.minByOrNull { it.now - it.low }?.let { v ->
            "$legOffers\u00A0${Ru.plural(legOffers, "предложение", "предложения", "предложений")} для ног: ${Ru.nobreak(setsText(v.now))} в неделю " +
                "на ${v.muscle.ru.lowercase()} при норме ${v.low}–${v.high}."
        }
        if (input.stalled.isNotEmpty() && stuck.isEmpty()) fine += "Веса в основных упражнениях растут."
        if (sm != null) days.maxByOrNull { minutes(it, input.timeRatio) }?.let { d ->
            val min = minutes(d, input.timeRatio)
            if (min <= sm + 10) fine += "По времени укладываешься: самая длинная тренировка — «${d.title}», около $min мин из твоих $sm."
        }
        val n = final.size
        val summary = when {
            n == 0 -> "План уже хорошо собран под твою цель — менять нечего."
            final.any { it.priority == 1 } -> "$n ${Ru.plural(n, "предложение", "предложения", "предложений")}. Главное — веса стоят в основных " +
                "упражнениях. Выбирай сам: план поменяется только там, где ты отметишь."
            else -> "$n ${Ru.plural(n, "предложение", "предложения", "предложений")} на выбор. План и так рабочий, это мелкие улучшения. " +
                "Поменяется только то, что ты отметишь."
        }
        return Advice(days, final, table, fine, ranges, summary, warning, held, hidden = h.hidden.size, nudge = nudge)
    }

    /** "в понедельник", "во вторник": a day of the week after «в», by its ISO number less one. */
    private val ON_DAY = listOf("в понедельник", "во вторник", "в среду", "в четверг", "в пятницу", "в субботу", "в воскресенье")

    /**
     * The upper-body day done after the week's second hard run, the same day and after it (research one-leg-day (a), owner
     * 25 Nov: Denis's Thursday): the day for a little leg work. Not when a hard run follows within two days (legs and a hard
     * run about 48 h apart), nor on a day with leg work already. With its weekday (ISO); null when the week has none.
     */
    private fun afterHardRun(week: WeekPlan, days: List<Day>, find: (String) -> Exercise?): Pair<Int, Day>? {
        val hard = (1..7).flatMap { d -> week.on(d).withIndex().filter { it.value.isHardRun }.map { d to it.index } }
        val (day, at) = hard.getOrNull(1) ?: return null
        val id = week.on(day).drop(at + 1).firstOrNull { it.kind == SessionKind.LIFT }?.templateId ?: return null
        if ((1..2).any { k -> week.on((day - 1 + k) % 7 + 1).any { it.isHardRun } }) return null
        val d = days.firstOrNull { it.templateId == id } ?: return null
        if (d.items.any { find(it.exerciseId)?.lowerBody == true }) return null
        return day to d
    }

    /**
     * Where a week's leg sets stand against their band ([ranges], the advisor's own table): one line for «Мышцы за 7 дней»
     * (research legs-norm-for-runners+D3, owner 25 Nov: against the band, not only against the plan). The muscle furthest
     * below its band first; [span]: the days counted ("за 7 дней"). Null without a band for the legs.
     */
    fun legsLine(sets: Map<Muscle, Double>, ranges: Map<Muscle, Pair<Int, Int>>, span: String): String? {
        val legs = listOf(Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES).mapNotNull { m -> ranges[m]?.let { Triple(m, sets[m] ?: 0.0, it) } }
        if (legs.isEmpty()) return null
        fun n(v: Double) = Ru.nobreak(setsText(v))
        fun band(r: Pair<Int, Int>) = "${r.first}–${r.second}"
        if (legs.all { it.second <= 0.0 }) return "0\u00A0подходов на ноги $span при норме ${band(legs.first().third)}."
        legs.filter { (_, v, r) -> v < r.first }.minByOrNull { (_, v, r) -> v - r.first }?.let { (m, v, r) ->
            return "${n(v)} на ${m.ru.lowercase()} $span при норме ${band(r)}."
        }
        legs.filter { (_, v, _) -> v < LEG_GROWTH }.minByOrNull { it.second }?.let { (m, v, _) ->
            return "${n(v)} на ${m.ru.lowercase()} $span работают, но для роста мышц это мало."
        }
        legs.filter { (_, v, r) -> v > r.second }.maxByOrNull { (_, v, r) -> v - r.second }?.let { (m, v, r) ->
            return "${n(v)} на ${m.ru.lowercase()} $span, это выше нормы ${band(r)}."
        }
        val lo = legs.minOf { it.second }
        val hi = legs.maxOf { it.second }
        return (if (lo == hi) "" else "${num(lo)}–") + n(hi) + " на ноги $span при норме ${band(legs.first().third)}."
    }

    /** What the advisor itself proposes to protect (a runner's hamstrings and calves): never the one swapped away for sets elsewhere. */
    private val PROTECTIVE = setOf("nordic_curl", "glute_ham_raise", "seated_calf", "face_pull")

    private val MACHINES = setOf(Equipment.MACHINE, Equipment.CABLE)

    /** One-leg lifts, in order of preference for a runner (the hard one goes last unless liked: [prefer]). */
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

    /** "6", "6,5": the number of sets alone (the first of a range, "5–6,5 подхода"). */
    private fun num(v: Double): String = if (v % 1.0 == 0.0) v.roundToInt().toString() else Ru.one(v)
}
