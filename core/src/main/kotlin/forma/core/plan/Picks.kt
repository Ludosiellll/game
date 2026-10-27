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
    /**
     * What a home prefers over the closest catalog match: the dumbbell Romanian deadlift for a hinge; with the body alone
     * the glute bridge (the back raise is weak), chin-ups for the biceps (with a bar), dips off a bench or close push-ups
     * for the triceps — rather than no arm work at all.
     */
    private fun prefer(gear: Gear, pattern: Pattern): List<String> = when (gear) {
        Gear.GYM -> emptyList()
        Gear.DUMBBELLS -> if (pattern == Pattern.HINGE) listOf("db_rdl") else emptyList()
        Gear.BODY -> when (pattern) {
            Pattern.HINGE -> listOf("glute_bridge")
            Pattern.CURL -> listOf("chinup")
            Pattern.TRICEPS_EXT -> listOf("bench_dip", "diamond_pushup")
            else -> emptyList()
        }
    }

    /** [templates] made of what is there, each (gym exercise → stand-in) pair, the gym exercises dropped, and the week's line. */
    data class Fit(val templates: List<Template>, val pairs: Set<Pair<String, String>>, val dropped: Set<String>, val note: String?)

    fun fit(templates: List<Template>, profile: Profile): Pair<List<Template>, String?> = of(templates, profile).let { it.templates to it.note }

    fun of(templates: List<Template>, profile: Profile): Fit {
        val gear = profile.gear
        if (gear == Gear.GYM) return Fit(templates, emptySet(), emptySet(), null)
        val pairs = linkedSetOf<Pair<String, String>>()
        val dropped = linkedSetOf<String>()
        // The same gym exercise gets the same stand-in in every session where it is free there.
        val chosen = mutableMapOf<String, String>()
        val out = templates.map { t ->
            val taken = t.slots.map { it.exerciseId }.toMutableSet()
            t.copy(slots = t.slots.mapNotNull { slot ->
                val ex = Catalog[slot.exerciseId]
                if (gear.fits(ex, profile.pullupBar)) return@mapNotNull slot
                // Never a hard, unusual one in a gym exercise's place at home (Nordic curls need the heels held, sissy
                // squats knees that know them): the movement that is there instead, or the place goes.
                val to = Picks.standIn(ex, slot.role, novice = true, taken, listOfNotNull(chosen[ex.id]) + prefer(gear, ex.pattern)) { gear.fits(it, profile.pullupBar) }
                    ?.also { chosen.putIfAbsent(ex.id, it.id) }
                if (to == null) {
                    dropped += slot.exerciseId
                    null
                } else {
                    taken += to.id
                    pairs += slot.exerciseId to to.id
                    slot.copy(exerciseId = to.id, increment = null)
                }
            })
        }.filter { it.slots.isNotEmpty() }
        if (pairs.isEmpty() && dropped.isEmpty()) return Fit(templates, pairs, dropped, null)
        val said = pairs.take(6).joinToString(", ") { (a, b) -> "«${Catalog[a].name}» → «${Catalog[b].name}»" } +
            if (pairs.size > 6) " и ещё ${pairs.size - 6}" else ""
        val text = buildString {
            append("${gear.ru}: план собран из того, что там есть")
            if (pairs.isNotEmpty()) append(" — $said")
            append(".")
            if (dropped.isNotEmpty()) append(" Нечем заменить, поэтому убрано: ${dropped.joinToString(", ") { "«${Catalog[it].name}»" }}.")
            // The gym goal's lift cannot be done here: said, with what to do about it.
            profile.liftGoal?.exerciseId?.takeIf { g -> pairs.any { it.first == g } || g in dropped }?.let { g ->
                val instead = pairs.filter { it.first == g }.map { Catalog[it.second].name }.distinct()
                append(" Твоя цель в зале — «${Catalog[g].name}», а здесь его не сделать")
                append(if (instead.isNotEmpty()) ": в плане «${instead.joinToString("», «")}»." else ".")
                append(" Цель можно оставить и делать это упражнение в зале или поменять цель на то, что есть дома.")
            }
        }
        return Fit(out, pairs, dropped, text)
    }

    /**
     * For the person's own numbers and choices kept under plan ids (taken out, sets, reps, rest, replacements): the ids of
     * a plan made with [from]'s kit → the ids the same places get with [to]'s ([gym] ids: the templates before any kit).
     * A place with nothing to stand in has none.
     */
    fun moved(gym: List<Template>, from: Profile, to: Profile): Map<String, Set<String>> {
        fun ids(p: Profile): Map<String, Set<String>> {
            val f = of(gym, p)
            return gym.flatMap { t -> t.slots.map { it.exerciseId } }.distinct().associateWith { g ->
                when {
                    p.gear.fits(Catalog[g], p.pullupBar) -> setOf(g)
                    else -> f.pairs.filter { it.first == g }.map { it.second }.toSet()
                }
            }
        }
        val before = ids(from)
        val after = ids(to)
        val out = mutableMapOf<String, MutableSet<String>>()
        for ((g, olds) in before) for (o in olds) out.getOrPut(o) { mutableSetOf() } += after[g].orEmpty()
        return out
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
    fun resolve(
        places: List<Place>, likes: Map<Pattern, List<String>>, dislikes: Set<String>, rules: Rules, made: Set<String> = emptySet(),
        fresh: Set<Pattern> = emptySet(),
    ): Result {
        val swaps = linkedMapOf<String, String>()
        val notes = mutableListOf<String>()
        // A plan id may stand in several sessions: one replacement for all of them, the strictest place decides.
        val byPlan = places.groupBy { it.planId }
        // What each session holds as resolved so far, so no exercise lands twice in one.
        val holds = places.groupBy { it.templateId }.mapValues { (_, l) -> l.map { it.current }.toMutableSet() }
        val turn = mutableMapOf<Pattern, Int>()
        for ((planId, at) in byPlan) {
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
            // A replacement earlier picks made, with no like of its movement behind it any more.
            val stale = planId in made && current != planId && planId !in dislikes && liked.isEmpty()
            // A place taken out of the plan stays out; a pick left in it goes, so it comes back as the plan's own.
            if (planId in rules.removed) {
                if (stale) put(planId)
                continue
            }
            when {
                planId == rules.goalLift || current == rules.goalLift -> {
                    if (liked.isNotEmpty() || current in dislikes) notes += "«${plan.name}» — упражнение твоей цели в зале, остаётся."
                    continue
                }
                rules.strength && role == SlotRole.MAIN && pattern in BARBELL_MAINS && plan.equipment == Equipment.BARBELL -> {
                    when {
                        // Not wanted there: the barbell lift itself, the point of the aim.
                        current in dislikes && planId !in dislikes -> put(planId)
                        // A replacement earlier picks made goes; one the person made by hand stays theirs.
                        current != planId && planId in made && planId !in dislikes -> put(planId)
                    }
                    if (liked.any { it.id != planId } || current in dislikes) {
                        val now = swaps[planId] ?: current
                        notes += if (now == planId) {
                            "Цель — сила: главным на «${pattern.ru.lowercase()}» остаётся «${plan.name}». Остальные места этого движения — по твоему выбору."
                        } else {
                            "Цель — сила: главное место на «${pattern.ru.lowercase()}» по выбору не меняю — там «${Catalog[now].name}», ты поставил сам."
                        }
                    }
                    continue
                }
                // Put in by hand, not «не хочу», and the likes of its movement not changed now: the person's own stays.
                current != planId && planId !in made && current !in dislikes && pattern !in fresh -> {
                    if (liked.any { it.id != current }) notes += "«${Catalog[current].name}» ты поставил сам — оставил."
                    continue
                }
            }
            val fit = liked.filter { unfit(it, role, rules) == null }
            if (fit.isNotEmpty()) {
                val k = turn.getOrDefault(pattern, 0)
                // Never twice in one session: with every liked one there already, the place keeps what it has.
                val pick = fit.indices.map { fit[(k + it) % fit.size] }.firstOrNull { others(it.id) }
                if (pick != null) {
                    turn[pattern] = k + 1
                    put(pick.id)
                    continue
                }
            }
            when {
                current in dislikes -> {
                    val taken = at.flatMap { holds[it.templateId].orEmpty() }.toSet() - current
                    val to = (listOf(plan).filter { it.id !in dislikes && it.id != current && unfit(it, role, rules) == null && it.id !in taken } +
                        listOfNotNull(standIn(Catalog[current], role, rules.novice, taken + dislikes) { it.pattern == pattern && unavailable(it, rules) == null && unfit(it, role, rules) == null }))
                        .firstOrNull()
                    if (to != null) put(to.id) else notes += "Для «${Catalog[current].name}» не нашлось замены, которая подходит: оставил."
                }
                stale -> put(planId)
            }
            // A like that cannot stand here, said only where the place kept what it had.
            if (planId !in swaps) liked.firstOrNull { unfit(it, role, rules) != null }?.let { l ->
                notes += "«${l.name}» — ${unfit(l, role, rules)}: на месте «${Catalog[current].name}» оставил его."
            }
        }
        return Result(swaps, notes.distinct())
    }
}
