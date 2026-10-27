package forma.core.plan

import forma.core.model.Catalog
import forma.core.model.Equipment
import forma.core.model.Exercise
import forma.core.model.Experience
import forma.core.model.Gear
import forma.core.model.LiftFocus
import forma.core.model.LoadKind
import forma.core.model.Pattern
import forma.core.model.Profile
import forma.core.strength.SlotRole

/**
 * The plan made of what is there ([Gear]): an exercise that cannot be done here gets the closest one that can — the same
 * movement first, then the same main muscle; a main or second place keeps a compound exercise when there is one. With
 * nothing to stand in, the place goes, and the week says so.
 */
object GearFit {
    /** A home's hinge: the dumbbell Romanian deadlift, or with the body alone the glute bridge (the back raise is weak). */
    private val HOME = mapOf(Pattern.HINGE to listOf("db_rdl", "glute_bridge"))

    fun fit(templates: List<Template>, profile: Profile): Pair<List<Template>, String?> {
        val gear = profile.gear
        if (gear == Gear.GYM) return templates to null
        val novice = profile.experience == Experience.NOVICE
        val swapped = linkedMapOf<String, String>()
        val dropped = linkedSetOf<String>()
        val out = templates.map { t ->
            val taken = t.slots.map { it.exerciseId }.toMutableSet()
            t.copy(slots = t.slots.mapNotNull { slot ->
                val ex = Catalog[slot.exerciseId]
                if (gear.fits(ex, profile.pullupBar)) return@mapNotNull slot
                val to = Picks.standIn(ex, slot.role, novice, taken, HOME[ex.pattern].orEmpty()) { gear.fits(it, profile.pullupBar) }
                if (to == null) {
                    dropped += slot.exerciseId
                    null
                } else {
                    taken += to.id
                    swapped[slot.exerciseId] = to.id
                    slot.copy(exerciseId = to.id, increment = null)
                }
            })
        }.filter { it.slots.isNotEmpty() }
        if (swapped.isEmpty() && dropped.isEmpty()) return templates to null
        val said = swapped.entries.take(6).joinToString(", ") { (a, b) -> "«${Catalog[a].name}» → «${Catalog[b].name}»" } +
            if (swapped.size > 6) " и ещё ${swapped.size - 6}" else ""
        val text = buildString {
            append("${gear.ru}: план собран из того, что там есть")
            if (swapped.isNotEmpty()) append(" — $said")
            append(".")
            if (dropped.isNotEmpty()) append(" Нечем заменить, поэтому убрано: ${dropped.joinToString(", ") { "«${Catalog[it].name}»" }}.")
        }
        return out to text
    }
}

/**
 * «Мои упражнения»: per movement ([Pattern]) the exercises the person likes, and the ones they do not want. Resolved into
 * the plan's places as replacements for good (the plan's own id stays the key, so sets, reps, rest, a place taken out
 * and the history all stay where they are). Rules that keep the plan doing its job:
 * - a pick stands in only for its own movement (the squat place gets a squat, never a leg extension);
 * - a main or second place keeps a compound exercise;
 * - the gym goal's lift stays, and with strength as the aim the barbell main lifts stay;
 * - hard, unusual exercises ([PlanAdvisor.HARD]) wait for a beginner's first weeks;
 * - only what can be done where the person trains ([Gear]);
 * - a place the person took out of the plan stays out.
 * Each place a rule kept is said, with why.
 */
object Picks {
    /** One exercise place of the plan: [planId] in template [templateId], done now as [current] (a swap may stand). */
    data class Place(val templateId: String, val planId: String, val current: String, val role: SlotRole)

    data class Rules(
        val novice: Boolean = false,
        /** Strength (or strength and size) as the aim: the barbell main lifts are the point. */
        val strength: Boolean = false,
        val goalLift: String? = null,
        val gear: Gear = Gear.GYM,
        val bar: Boolean = false,
        /** Plan ids taken out of the plan. */
        val removed: Set<String> = emptySet(),
    ) {
        companion object {
            fun of(p: Profile, removed: Set<String>) = Rules(
                novice = p.experience == Experience.NOVICE,
                strength = p.aim.normalized().lift.let { it == LiftFocus.STRENGTH || it == LiftFocus.POWERBUILDING },
                goalLift = p.liftGoal?.exerciseId, gear = p.gear, bar = p.pullupBar, removed = removed,
            )
        }
    }

    /**
     * What resolving the picks does: plan id → the exercise in its place ([planId] itself: the plan's own back), for
     * every place that changes; and what was kept and why.
     */
    data class Result(val swaps: Map<String, String>, val notes: List<String>)

    /** The movements a strength aim keeps on the barbell for the main lifts. */
    private val BARBELL_MAINS = setOf(Pattern.SQUAT, Pattern.HINGE, Pattern.HORIZONTAL_PUSH, Pattern.VERTICAL_PUSH)

    /** Why [ex] cannot be liked for this person at all (shown on the option), or null. */
    fun unavailable(ex: Exercise, rules: Rules): String? = when {
        !rules.gear.fits(ex, rules.bar) -> "нечем делать: ${rules.gear.ru.lowercase()}"
        rules.novice && ex.id in PlanAdvisor.HARD -> "после первых недель: сначала освой движение попроще"
        else -> null
    }

    /** Why [ex] cannot stand in a [role] place, or null. */
    private fun unfit(ex: Exercise, role: SlotRole, rules: Rules): String? = unavailable(ex, rules) ?: when {
        role != SlotRole.ACCESSORY && !ex.compound -> "на одну мышцу, а это место — для многосуставного"
        else -> null
    }

    /**
     * The closest stand-in for [ex] in a [role] place, of the same kind of load (a hold for a hold): [prefer] first, then
     * for a main or second place a compound one of the same movement, of the same main muscle, any of the same movement;
     * for a small (accessory) place the same movement or a single-joint one for the same muscle — never a heavy press in
     * place of a raise. Not one already in the session ([taken]), never a hard one for a beginner.
     */
    fun standIn(ex: Exercise, role: SlotRole, novice: Boolean, taken: Set<String>, prefer: List<String> = emptyList(), ok: (Exercise) -> Boolean): Exercise? {
        fun fine(o: Exercise) = !o.custom && !o.archived && o.id != ex.id && o.id !in taken && ok(o) &&
            !(novice && o.id in PlanAdvisor.HARD) && (o.load == LoadKind.TIMED) == (ex.load == LoadKind.TIMED)
        prefer.firstNotNullOfOrNull { id -> Catalog.find(id)?.takeIf(::fine) }?.let { return it }
        val c = Catalog.alternatives(ex).filter(::fine)
        val same = c.filter { it.pattern == ex.pattern }
        return if (role != SlotRole.ACCESSORY) same.firstOrNull { it.compound } ?: c.firstOrNull { it.compound } ?: same.firstOrNull()
        else same.firstOrNull() ?: c.firstOrNull { !it.compound }
    }

    /**
     * The places of the plan with [likes] (movement → liked exercise ids, in the order picked) and [dislikes]: each place
     * of a movement with likes gets one of them (several places — in turn, never twice in one session); a place done now
     * with an exercise not wanted, and no like for it, gets the closest one that is fine. [made]: plan ids whose current
     * replacement came from earlier picks — with no pick for them any more, the plan's own comes back.
     */
    fun resolve(places: List<Place>, likes: Map<Pattern, List<String>>, dislikes: Set<String>, rules: Rules, made: Set<String> = emptySet()): Result {
        val swaps = linkedMapOf<String, String>()
        val notes = mutableListOf<String>()
        // A plan id may stand in several sessions: one replacement for all of them, the strictest place decides.
        val byPlan = places.groupBy { it.planId }
        // What each session holds as resolved so far, so no exercise lands twice in one.
        val holds = places.groupBy { it.templateId }.mapValues { (_, l) -> l.map { it.current }.toMutableSet() }
        val turn = mutableMapOf<Pattern, Int>()
        for ((planId, at) in byPlan) {
            if (planId in rules.removed) continue
            val plan = Catalog.find(planId) ?: continue
            val current = at.first().current
            val role = at.minBy { it.role.ordinal }.role
            val pattern = plan.pattern
            val liked = likes[pattern].orEmpty().mapNotNull { Catalog.find(it) }.filter { !it.archived && it.id !in dislikes }
            fun others(id: String) = at.all { p -> holds[p.templateId].orEmpty().let { h -> id !in h || id == p.current } }
            fun put(id: String) {
                if (id == current) return
                swaps[planId] = id
                at.forEach { p -> holds[p.templateId]?.apply { remove(p.current); add(id) } }
            }
            when {
                planId == rules.goalLift || current == rules.goalLift -> {
                    if (liked.isNotEmpty() || current in dislikes) notes += "«${plan.name}» — упражнение твоей цели в зале, остаётся."
                    continue
                }
                rules.strength && role == SlotRole.MAIN && pattern in BARBELL_MAINS && plan.equipment == Equipment.BARBELL -> {
                    if (liked.any { it.id != planId } || current in dislikes) {
                        notes += "Цель — сила: главным на «${pattern.ru.lowercase()}» остаётся «${plan.name}». Остальные места этого движения — по твоему выбору."
                    }
                    // A replacement earlier picks made goes; one the person made by hand stays theirs.
                    if (current != planId && planId in made && planId !in dislikes) put(planId)
                    continue
                }
            }
            val fit = liked.filter { unfit(it, role, rules) == null }
            if (fit.isNotEmpty()) {
                val k = turn.getOrDefault(pattern, 0)
                val pick = (0 until fit.size).map { fit[(k + it) % fit.size] }.firstOrNull { others(it.id) } ?: fit[k % fit.size]
                turn[pattern] = k + 1
                put(pick.id)
                continue
            }
            liked.firstOrNull()?.let { l ->
                unfit(l, role, rules)?.let { why -> notes += "«${l.name}» — $why: на месте «${Catalog[current].name}» оставил его." }
            }
            when {
                current in dislikes -> {
                    val taken = at.flatMap { holds[it.templateId].orEmpty() }.toSet() - current
                    val to = (listOf(plan).filter { it.id !in dislikes && it.id != current && unfit(it, role, rules) == null && it.id !in taken } +
                        listOfNotNull(standIn(Catalog[current], role, rules.novice, taken + dislikes) { it.pattern == pattern && unavailable(it, rules) == null && unfit(it, role, rules) == null }))
                        .firstOrNull()
                    if (to != null) put(to.id) else notes += "Для «${Catalog[current].name}» не нашлось замены, которая подходит: оставил."
                }
                // A replacement made by earlier picks with no pick behind it any more: the plan's own comes back.
                planId in made && current != planId && planId !in dislikes -> put(planId)
            }
        }
        return Result(swaps, notes.distinct())
    }
}
