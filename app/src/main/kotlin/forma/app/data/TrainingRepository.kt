package forma.app.data

import forma.core.analytics.SetRow
import forma.core.load.TrainingLoad
import forma.core.model.Catalog
import forma.core.model.Equipment
import forma.core.model.Exercise
import forma.core.model.Experience
import forma.core.model.LoadKind
import forma.core.model.Profile
import forma.core.model.Sex
import forma.core.plan.CatchUp
import forma.core.plan.PlannedSession
import forma.core.plan.Program
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.run.RunOutcome
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.strength.DoneSet
import forma.core.strength.Outcome
import forma.core.strength.Phase
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import forma.core.strength.WarmUp
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

/** Template id of sessions copied from a friend (not part of the own plan). */
const val COPIED_TEMPLATE = "copy"

class TrainingRepository(
    val db: FormaDatabase,
    val store: SettingsStore,
    private val now: () -> Long = System::currentTimeMillis,
    /** Fixed in tests and simulations; otherwise the phone's current zone, read each time (people travel). */
    private val zone: ZoneId? = null,
    /** Called after anything worth copying to the cloud has been saved. */
    private val onChange: () -> Unit = {},
) {
    private fun zone(): ZoneId = zone ?: ZoneId.systemDefault()

    /** Starting, finishing and saving sessions one at a time: a double tap must not make two of anything. */
    private val sessionLock = Mutex()

    /** Today by the repository's clock (tests and simulations move it). */
    fun today(): LocalDate = dayOf(now())

    /** The local day of a moment ([ms] since the epoch). */
    fun dayOf(ms: Long): LocalDate = java.time.Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate()

    // ---------- profile and program ----------

    suspend fun completeOnboarding(profile: Profile) {
        store.setProfile(profile)
        store.setProgram(ProgramGenerator.generate(profile, today()))
        store.setRunState(typedRace(ProgramGenerator.initialRunState(profile), profile))
        db.state().upsertBodyWeight(BodyWeightEntity(today().toEpochDay(), profile.bodyWeightKg))
        onChange()
    }

    /**
     * A race typed in the profile is a test as of today: the next one comes on the usual cadence. With a race of up to
     * 3 km as the goal the first test is that race itself, after the first two runs.
     */
    private fun typedRace(st: forma.core.run.RunState, profile: Profile): forma.core.run.RunState =
        if (st.vdot != null && profile.runGoal?.middleDistance != true) st.copy(lastTestDay = today().toEpochDay()) else st

    /** Saves the profile and rebuilds the plan. Lifting progress is kept per exercise and rep range. */
    suspend fun updateProfile(profile: Profile) {
        val old = store.profileNow()
        store.setProfile(profile)
        val fresh = ProgramGenerator.generate(profile, today())
        val prev = store.programNow()
        // The block goes on: a saved profile changes the week, not where the lifter is in the cycle (a lighter week
        // stays where it was due, a vacation shift stays). Another experience level has another block and starts over.
        store.setProgram(
            if (prev != null && prev.experience == fresh.experience) fresh.copy(startEpochDay = prev.startEpochDay, createdEpochDay = prev.createdEpochDay)
            else fresh,
        )
        val raceChanged = old == null || old.raceMeters != profile.raceMeters || old.raceSeconds != profile.raceSeconds
        if (raceChanged || old.runLevel != profile.runLevel) {
            val init = ProgramGenerator.initialRunState(profile)
            val cur = store.runStateNow()
            // A test run since says more than a race typed long ago; a race typed now says more than the old fitness.
            val vdot = if (raceChanged) init.vdot ?: cur.vdot else cur.vdot ?: init.vdot
            val next = if (old?.runLevel != profile.runLevel) {
                // Another level: its starting lengths and steps, but what running has shown stays.
                init.copy(
                    vdot = vdot, runs = cur.runs, recent = cur.recent, lastRunDay = cur.lastRunDay, lastTestDay = cur.lastTestDay,
                    lastVdotUpDay = cur.lastVdotUpDay, goalStage = cur.goalStage, qualityCount = cur.qualityCount,
                )
            } else cur.copy(vdot = vdot)
            store.setRunState(if (raceChanged && init.vdot != null) typedRace(next, profile) else next)
        }
        // A new race goal starts its race-pace ladder from the first step; a time a few seconds off (5:35 → 5:30) is the
        // same goal, and the steps done stay.
        val og = old?.runGoal
        val ng = profile.runGoal
        val sameGoal = og == ng || og != null && ng != null && og.meters == ng.meters && kotlin.math.abs(ng.seconds - og.seconds) <= og.seconds * 0.05
        if (old != null && !sameGoal) {
            store.setRunState(store.runStateNow().copy(goalStage = 0, goalFails = 0, testSoon = false))
        }
        if (old?.bodyWeightKg != profile.bodyWeightKg) logBodyWeight(profile.bodyWeightKg)
        onChange()
    }

    suspend fun logBodyWeight(kg: Double) {
        db.state().upsertBodyWeight(BodyWeightEntity(today().toEpochDay(), kg))
        store.profileNow()?.let { if (it.bodyWeightKg != kg) store.setProfile(it.copy(bodyWeightKg = kg)) }
        onChange()
    }

    private fun exercise(id: String, settings: Settings): Exercise {
        val ex = Catalog[id]
        return settings.steps[id]?.let { ex.copy(step = it, fineStep = null) } ?: ex
    }

    private fun SlotStateEntity.toState() = SlotState(weight, reps, stalls, misses, sessions, linear, steady, cutFrom, cutReps)

    private fun SlotState.toEntity(spec: SlotSpec) =
        SlotStateEntity(spec.key, spec.exerciseId, weight, reps, stalls, misses, sessions, now(), linear, steady, cutFrom, cutReps)

    /** Best session estimate of [exerciseId] over the 8 weeks before [day] (the floor for reductions). */
    private suspend fun recentBest(exerciseId: String, day: Long, exclude: Long): Double? =
        db.workouts().allDone().asSequence()
            .filter { it.kind == Kind.LIFT && it.id != exclude && it.epochDay in (day - 56)..day }
            .mapNotNull { w -> w.summaryJson?.let { runCatching { AppJson.decodeFromString(LiftSummary.serializer(), it) }.getOrNull() } }
            .flatMap { it.reports.asSequence() }
            .filter { it.exerciseId == exerciseId }
            .mapNotNull { it.e1rm }
            .maxOrNull()

    // ---------- lifting ----------

    companion object {
        /** The most sets of one exercise the plan can be set to. */
        const val MAX_SETS = 8
        /** Rest between sets the plan can be set to, seconds. */
        const val MIN_REST = 30
        const val MAX_REST = 300

        /** Where the sets, reps and rest of an exercise added to day [templateId] for good are kept. */
        fun addedKey(templateId: String, exerciseId: String) = "$templateId:$exerciseId"
    }

    suspend fun liftPlan(templateId: String, date: LocalDate, forceDeload: Boolean = false): LiftPlan {
        val program = store.programNow() ?: error("no program")
        val settings = store.settingsNow()
        val t = program.template(templateId)
        val deload = deloadOn(date, forceDeload)
        // Exercises taken out for good stay out — never all of them. Positions stay the plan's (swaps look them up).
        val added = addedTo(t.id, settings)
        val kept = t.slots.withIndex().filter { it.value.exerciseId !in settings.removed }
            .ifEmpty { if (added.isEmpty()) t.slots.withIndex().toList() else emptyList() }
        val own = kept.map { (i, spec) -> buildItem(i, tuned(spec, settings), date, deload).copy(planId = spec.exerciseId) }
        // Added for good: after the plan's own, as chosen (no swap applies to them: they were picked by hand).
        val extra = added.mapIndexed { j, id ->
            buildItem(t.slots.size + j, overrides(addedSpec(id), settings, addedKey(t.id, id)), date, deload).copy(planId = id, addedForGood = true)
        }
        return LiftPlan(t.id, t.title, own + extra, deload)
    }

    /** Exercises added to template [templateId] for good that can still be done (an own exercise may be archived). */
    fun addedTo(templateId: String, settings: Settings): List<String> =
        settings.added[templateId].orEmpty().map { Catalog.moved[it] ?: it }.filter { Catalog.find(it)?.archived == false }

    /** Sets, reps and rest of an exercise added to a session: 3 sets in a range that suits it. */
    private fun addedSpec(exerciseId: String): SlotSpec {
        val ex = Catalog[exerciseId]
        return when {
            ex.load == LoadKind.TIMED -> SlotSpec(ex.id, 3, 20, 60, 1, 60, SlotRole.ACCESSORY)
            ex.compound -> SlotSpec(ex.id, 3, 6, 10, 2, 120, SlotRole.SECONDARY)
            else -> SlotSpec(ex.id, 3, 10, 15, 2, 75, SlotRole.ACCESSORY)
        }
    }

    private suspend fun deloadOn(date: LocalDate, force: Boolean): Boolean {
        val program = store.programNow() ?: return force
        val reactive = store.liftStateNow().reactiveDeloadStart
        return force || ProgramGenerator.isDeloadWeek(program, date) || (reactive != null && date.toEpochDay() in reactive until reactive + 7)
    }

    /** The plan's slot as the user set it for good: its sets, reps and rest, the exercise put in its place. */
    private fun tuned(spec: SlotSpec, settings: Settings): SlotSpec = swapped(overrides(spec, settings), settings)

    /**
     * Sets, rep range and rest the user set for good under [id]: the plan's exercise id, or [addedKey] for one added to a
     * day (the same exercise added to two days, or also in another day's plan, keeps its own numbers on each).
     */
    private fun overrides(spec: SlotSpec, settings: Settings, id: String = spec.exerciseId): SlotSpec {
        val sets = settings.sets[id]?.coerceIn(1, MAX_SETS) ?: spec.sets
        val reps = settings.reps[id]?.takeIf { it.size == 2 && it[0] >= 1 && it[0] <= it[1] && it[1] <= 100 }
        val rest = settings.rest[id]?.coerceIn(MIN_REST, MAX_REST) ?: spec.restSec
        return spec.copy(sets = sets, repMin = reps?.get(0) ?: spec.repMin, repMax = reps?.get(1) ?: spec.repMax, restSec = rest)
    }

    /** The plan's slot with the exercise the user put in its place for good, if any. */
    private fun swapped(spec: SlotSpec, settings: Settings): SlotSpec {
        val id = settings.swaps[spec.exerciseId]?.let { Catalog.moved[it] ?: it }?.takeIf { Catalog.find(it)?.archived == false } ?: return spec
        return spec.copy(exerciseId = id, increment = null)
    }

    /** One exercise of a session: today's prescription from its own progress, the stored estimate or a start hint. */
    private suspend fun buildItem(position: Int, spec: SlotSpec, date: LocalDate, deload: Boolean): LiftItem {
        val program = store.programNow() ?: error("no program")
        val profile = store.profileNow() ?: error("no profile")
        val settings = store.settingsNow()
        val rirOffset = if (deload) 0 else ProgramGenerator.rirOffset(program, date)
        val trained = when (profile.experience) {
            Experience.NOVICE -> 1.0
            Experience.INTERMEDIATE -> 1.35
            Experience.ADVANCED -> 1.7
        }
        val ex = exercise(spec.exerciseId, settings)
        val slotRow = db.state().slot(spec.key)
        val st = slotRow?.toState() ?: SlotState.fresh(spec)
        val hint = Progression.startHint(ex, profile.bodyWeightKg, profile.sex == Sex.FEMALE, trained)
        val stat = db.state().stat(ex.id)
        // The last time the exercise was done in any rep range (a new range starts from the estimate).
        val lastMs = listOfNotNull(slotRow?.updatedAt, stat?.updatedAt).maxOrNull()
        val lastDay = lastMs?.let { java.time.Instant.ofEpochMilli(it).atZone(zone()).toLocalDate().toEpochDay() }
        val p = Progression.prescribe(
            ex, spec, st, if (deload) Phase.DELOAD else Phase.NORMAL, rirOffset,
            stat?.e1rm, profile.bodyWeightKg, hint,
            daysSinceLast = lastDay?.let { date.toEpochDay() - it },
        )
        val warm = if (spec.role == SlotRole.MAIN && p.weight != null && ex.load == LoadKind.EXTERNAL && !deload) {
            WarmUp.sets(p.weight!!, ex.step, if (ex.equipment == Equipment.BARBELL) ex.bar else null)
        } else emptyList()
        return LiftItem(position, spec, ex.id, ex.name, p, warm, ex.step)
    }

    // ---------- exercise swaps and own exercises ----------

    /** What can replace the exercise at [position] of a session: same movement first; timed holds only for holds. */
    fun alternativesFor(exerciseId: String): List<Exercise> {
        val ex = Catalog[exerciseId]
        return Catalog.alternatives(ex).filter { (it.load == LoadKind.TIMED) == (ex.load == LoadKind.TIMED) }
    }

    /**
     * Puts [newId] in place of the exercise at [position] of a running session (only before its first set).
     * [forever] also replaces it in every future session of the plan, until changed back.
     */
    suspend fun swapExercise(workoutId: Long, position: Int, newId: String, forever: Boolean): Boolean {
        val w = db.workouts().get(workoutId) ?: return false
        val plan = planOf(w) ?: return false
        val old = plan.items.firstOrNull { it.position == position } ?: return false
        if (db.sets().forWorkout(workoutId).any { it.position == position }) return false
        val settings = store.settingsNow()
        val own = ownSlot(plan.templateId, old)
        // The plan's exercise here with its sets, reps and rest for good (the swap is today's choice, those stay).
        val original = when {
            old.addedForGood && old.planId != null -> overrides(addedSpec(old.planId), settings, addedKey(plan.templateId, old.planId))
            own != null -> overrides(own, settings)
            else -> null
        }
        val base = original ?: old.slot
        val spec = if (newId == base.exerciseId) base else base.copy(exerciseId = newId, increment = null)
        val forGood = forever && original != null && old.addedForGood
        val item = buildItem(position, spec, LocalDate.ofEpochDay(w.epochDay), plan.deload)
            .copy(planId = if (forGood) newId else old.planId ?: own?.exerciseId, addedForGood = old.addedForGood)
        replacePlan(workoutId, plan.copy(items = plan.items.map { if (it.position == position) item else it }))
        if (forever && original != null) {
            if (old.addedForGood) replaceAdded(plan.templateId, original.exerciseId, newId) else setSwap(original.exerciseId, newId)
        }
        return true
    }

    /** The generated plan's own slot of [item] in template [templateId]; null for an exercise added to the plan or today. */
    private suspend fun ownSlot(templateId: String, item: LiftItem): SlotSpec? {
        if (item.addedForGood) return null
        val slots = store.programNow()?.templates?.firstOrNull { it.id == templateId }?.slots ?: return null
        val id = item.planId
        // Sessions started before the origin was stored: the position is the plan's index (added ones come after). With
        // the origin stored, only that exercise: after the plan was rebuilt mid-session the position may hold another.
        return if (id == null) slots.getOrNull(item.position)
        else slots.getOrNull(item.position)?.takeIf { it.exerciseId == id } ?: slots.firstOrNull { it.exerciseId == id }
    }

    /** The exercise at [position] of the session belongs to the plan (its own or added for good), not to today only. */
    suspend fun inPlan(workoutId: Long, position: Int): Boolean {
        val plan = db.workouts().get(workoutId)?.let { planOf(it) } ?: return false
        val item = plan.items.firstOrNull { it.position == position } ?: return false
        return item.addedForGood || ownSlot(plan.templateId, item) != null
    }

    /**
     * Adds [exerciseId] at the end of a running session (3 sets in a range that suits it). [forever] also adds it to
     * every session of this plan from now on, after the plan's own exercises.
     */
    suspend fun addToWorkout(workoutId: Long, exerciseId: String, forever: Boolean = false): Boolean {
        val w = db.workouts().get(workoutId) ?: return false
        val plan = planOf(w) ?: return false
        val settings = store.settingsNow()
        // Past every position of the plan: an added exercise must never pass for a plan slot (a plan exercise taken out
        // today leaves its position free, and "for good" acts on the plan's exercise at a position).
        val planSize = (store.programNow()?.templates?.firstOrNull { it.id == plan.templateId }?.slots?.size ?: 0) +
            settings.added[plan.templateId].orEmpty().size
        val position = maxOf(planSize, (plan.items.maxOfOrNull { it.position } ?: -1) + 1)
        val spec = if (forever) overrides(addedSpec(exerciseId), settings, addedKey(plan.templateId, exerciseId)) else addedSpec(exerciseId)
        val item = buildItem(position, spec, LocalDate.ofEpochDay(w.epochDay), plan.deload)
            .let { if (forever) it.copy(planId = exerciseId, addedForGood = true) else it }
        replacePlan(workoutId, plan.copy(items = plan.items + item))
        if (forever) addToPlan(plan.templateId, exerciseId)
        return true
    }

    /** Adds [exerciseId] to every session of template [templateId] from now on, after the plan's own exercises. */
    suspend fun addToPlan(templateId: String, exerciseId: String): Boolean {
        val s = store.settingsNow()
        val list = s.added[templateId].orEmpty()
        if (exerciseId in list) return false
        store.setSettings(s.copy(added = s.added + (templateId to list + exerciseId)))
        onChange()
        return true
    }

    /** Takes an exercise added for good out of template [templateId], with the numbers set for it there. */
    suspend fun removeAdded(templateId: String, exerciseId: String) {
        val s = store.settingsNow()
        val list = s.added[templateId].orEmpty() - exerciseId
        val key = addedKey(templateId, exerciseId)
        store.setSettings(
            s.copy(
                added = if (list.isEmpty()) s.added - templateId else s.added + (templateId to list),
                sets = s.sets - key, reps = s.reps - key, rest = s.rest - key,
            ),
        )
        onChange()
    }

    /** Puts [newId] in place of [oldId] among the exercises added for good to template [templateId]. */
    suspend fun replaceAdded(templateId: String, oldId: String, newId: String) {
        val s = store.settingsNow()
        val list = s.added[templateId].orEmpty()
        if (oldId !in list || newId == oldId) return
        val next = if (newId in list) list - oldId else list.map { if (it == oldId) newId else it }
        // The sets, reps and rest chosen for this place go to the exercise put in it.
        val from = addedKey(templateId, oldId)
        val to = addedKey(templateId, newId)
        fun <V> Map<String, V>.move(): Map<String, V> = this[from]?.let { (this - from) + (to to it) } ?: this
        store.setSettings(s.copy(added = s.added + (templateId to next), sets = s.sets.move(), reps = s.reps.move(), rest = s.rest.move()))
        onChange()
    }

    /**
     * Sets, rep range and rest kept under [planId] (the plan's exercise id, or [addedKey] for one added to a day) in
     * every session from now on; values equal to the plan's own ([planned]) clear the override, so a later change of the
     * plan comes through.
     */
    suspend fun tunePlan(planId: String, planned: SlotSpec, sets: Int, reps: Pair<Int, Int>, restSec: Int) {
        val s = store.settingsNow()
        val n = sets.coerceIn(1, MAX_SETS)
        val r = restSec.coerceIn(MIN_REST, MAX_REST)
        val range = reps.first.coerceIn(1, 100).let { lo -> lo to reps.second.coerceIn(lo, 100) }
        store.setSettings(
            s.copy(
                sets = if (n == planned.sets) s.sets - planId else s.sets + (planId to n),
                reps = if (range == planned.repMin to planned.repMax) s.reps - planId else s.reps + (planId to listOf(range.first, range.second)),
                rest = if (r == planned.restSec) s.rest - planId else s.rest + (planId to r),
            ),
        )
        onChange()
    }

    /** The exercise added for good [exerciseId] as the plan starts it (before the user's own sets, reps and rest). */
    fun addedPlanned(exerciseId: String): SlotSpec = addedSpec(exerciseId)

    /** Replaces [planId] by [newId] in every session from now on; the same id (or null) brings the original back. */
    /** Settings changed on their screen; they travel to other phones with the next sync. */
    suspend fun updateSettings(f: (Settings) -> Settings) {
        store.setSettings(f(store.settingsNow()))
        onChange()
    }

    suspend fun setSwap(planId: String, newId: String?) {
        val s = store.settingsNow()
        val swaps = if (newId == null || newId == planId) s.swaps - planId else s.swaps + (planId to newId)
        store.setSettings(s.copy(swaps = swaps))
        onChange()
    }

    /** Sets of [planId] in every session from now on; null (or the plan's own number) brings the plan's back. */
    suspend fun setPlanSets(planId: String, sets: Int?, planned: Int) {
        val s = store.settingsNow()
        val n = sets?.coerceIn(1, MAX_SETS)
        store.setSettings(s.copy(sets = if (n == null || n == planned) s.sets - planId else s.sets + (planId to n)))
        onChange()
    }

    /** The weight step of [exerciseId] in this gym from now on; its own step ([default]) clears the override. */
    suspend fun setStep(exerciseId: String, step: Double, default: Double) {
        val s = store.settingsNow()
        store.setSettings(s.copy(steps = if (step == default) s.steps - exerciseId else s.steps + (exerciseId to step)))
        onChange()
    }

    /** Takes [planId] out of every session from now on, or brings it back. */
    suspend fun setRemoved(planId: String, removed: Boolean) {
        val s = store.settingsNow()
        store.setSettings(s.copy(removed = if (removed) s.removed + planId else s.removed - planId))
        onChange()
    }

    /**
     * Takes the exercise at [position] out of a running session (only before its first set, never the last one left).
     * [forever] takes it out of the plan too, when it comes from the plan.
     */
    suspend fun removeFromWorkout(workoutId: Long, position: Int, forever: Boolean): Boolean {
        val w = db.workouts().get(workoutId) ?: return false
        val plan = planOf(w) ?: return false
        if (plan.items.size <= 1 || plan.items.none { it.position == position }) return false
        if (db.sets().forWorkout(workoutId).any { it.position == position }) return false
        val item = plan.items.first { it.position == position }
        replacePlan(workoutId, plan.copy(items = plan.items.filter { it.position != position }))
        if (forever) {
            if (item.addedForGood && item.planId != null) removeAdded(plan.templateId, item.planId)
            else ownSlot(plan.templateId, item)?.let { setRemoved(it.exerciseId, true) }
        }
        return true
    }

    /** The plan's own slot at [position] of template [templateId], if the position comes from the plan. */
    suspend fun planSlot(templateId: String, position: Int): SlotSpec? =
        store.programNow()?.templates?.firstOrNull { it.id == templateId }?.slots?.getOrNull(position)

    /** Loads the user's own exercises into the catalog (start, after sync, after edits). */
    suspend fun refreshCustom() = Catalog.setCustom(store.customNow())

    suspend fun addCustom(ex: Exercise): Exercise {
        val e = ex.copy(custom = true, archived = false)
        store.setCustom(store.customNow().filter { it.id != e.id } + e)
        refreshCustom()
        onChange()
        return e
    }

    /** Removes an own exercise from the lists; its history keeps its name. */
    suspend fun archiveCustom(id: String) {
        store.setCustom(store.customNow().map { if (it.id == id) it.copy(archived = true) else it })
        val s = store.settingsNow()
        store.setSettings(s.copy(swaps = s.swaps.filterValues { it != id }))
        refreshCustom()
        onChange()
    }

    /** Starts (or returns the already running) lifting session. */
    suspend fun startLift(templateId: String, date: LocalDate = today(), forceDeload: Boolean = false): Long = sessionLock.withLock {
        db.workouts().active()?.let { return@withLock it.id }
        val plan = liftPlan(templateId, date, forceDeload)
        db.workouts().insert(
            WorkoutEntity(
                epochDay = date.toEpochDay(), kind = Kind.LIFT, templateId = templateId, title = plan.title,
                startedAt = now(), status = Status.ACTIVE, deload = plan.deload,
                planJson = AppJson.encodeToString(LiftPlan.serializer(), plan),
            ),
        )
    }

    fun planOf(w: WorkoutEntity): LiftPlan? = w.planJson?.let { runCatching { AppJson.decodeFromString(LiftPlan.serializer(), it) }.getOrNull() }

    suspend fun replacePlan(workoutId: Long, plan: LiftPlan) {
        val w = db.workouts().get(workoutId) ?: return
        db.workouts().update(w.copy(planJson = AppJson.encodeToString(LiftPlan.serializer(), plan)))
    }

    suspend fun logSet(workoutId: Long, item: LiftItem, weight: Double, reps: Int, rir: Int?): Long =
        db.sets().insert(
            SetEntity(
                workoutId = workoutId, exerciseId = item.exerciseId, slotKey = item.slot.key, position = item.position,
                weight = weight, reps = reps, rir = rir, targetWeight = item.prescription.weight,
                targetReps = item.prescription.reps, doneAt = now(),
            ),
        )

    suspend fun updateSet(s: SetEntity) = db.sets().update(s)
    suspend fun deleteSet(id: Long) = db.sets().delete(id)

    suspend fun cancelWorkout(id: Long) = db.workouts().delete(id)

    /** Deletes a finished workout; the cloud copy (and friends' feeds) lose it on the next sync. */
    suspend fun deleteWorkout(id: Long) {
        db.workouts().delete(id)
        onChange()
    }

    private fun liftSummaryOf(w: WorkoutEntity): LiftSummary? =
        w.summaryJson?.let { runCatching { AppJson.decodeFromString(LiftSummary.serializer(), it) }.getOrNull() }

    /**
     * Evaluates every exercise and closes the session, all in one database transaction. A session finished already
     * (a second tap, a retry after the screen was closed half way) returns its result without evaluating again.
     */
    suspend fun finishLift(workoutId: Long, rpe: Int): LiftSummary = sessionLock.withLock {
        val first = db.workouts().get(workoutId) ?: error("no workout")
        if (first.status == Status.DONE) return@withLock liftSummaryOf(first) ?: LiftSummary(emptyList(), 0.0, 0, 0, 0.0)
        val summary = db.withTransaction { evaluateLift(workoutId, rpe) }
        checkReactiveDeload(LocalDate.ofEpochDay(first.epochDay))
        onChange()
        summary
    }

    private suspend fun evaluateLift(workoutId: Long, rpe: Int): LiftSummary {
        val w = db.workouts().get(workoutId) ?: error("no workout")
        val plan = planOf(w) ?: error("no plan")
        val profile = store.profileNow() ?: error("no profile")
        val settings = store.settingsNow()
        val sets = db.sets().forWorkout(workoutId)
        val bw = profile.bodyWeightKg
        val reports = mutableListOf<SlotReport>()
        var volume = 0.0
        // One exercise twice in a session (added again with "+", or swapped to one already there) has one progress:
        // the first one decides it, the extra sets are volume — tired back-off sets must not cut the working weight.
        val evaluated = mutableSetOf<String>()
        for (item in plan.items) {
            val ex = exercise(item.exerciseId, settings)
            val mine = sets.filter { it.position == item.position }
            for (s in mine) if (ex.load != LoadKind.TIMED) {
                volume += Progression.effectiveLoad(ex, s.weight, bw) * s.reps * (if (ex.perHand) 2 else 1)
            }
            if (mine.isNotEmpty() && !evaluated.add(item.slot.key)) continue
            val done = mine.map { DoneSet(it.weight, it.reps, it.rir) }
            val st = db.state().slot(item.slot.key)?.toState() ?: SlotState.fresh(item.slot)
            val ev = Progression.evaluate(
                ex, item.slot, st, item.prescription, done, bw,
                novice = profile.experience == Experience.NOVICE, recentBest = recentBest(ex.id, w.epochDay, workoutId),
            )
            if (ev.outcome !is Outcome.NotDone) db.state().upsertSlot(ev.state.toEntity(item.slot))
            var record = false
            // No estimate from sets without load (a weighted exercise done at 0 kg).
            ev.e1rm?.takeIf { it > 0 }?.let { obs ->
                val old = db.state().stat(ex.id)
                record = old != null && obs > old.best + 0.05
                db.state().upsertStat(
                    ExerciseStatEntity(
                        ex.id, Progression.smoothE1rm(old?.e1rm, obs, profile.experience == Experience.NOVICE),
                        max(old?.best ?: 0.0, obs), now(),
                    ),
                )
            }
            if (ev.outcome !is Outcome.NotDone) reports += SlotReport(ex.id, ex.name, ev.outcome, ev.e1rm, record)
        }
        val minutes = max(1, ((now() - w.startedAt) / 60_000.0).roundToInt())
        val load = TrainingLoad.sessionLoad(rpe, minutes.toDouble())
        val summary = LiftSummary(reports, volume, sets.size, minutes, load)
        db.workouts().update(
            w.copy(
                status = Status.DONE, finishedAt = now(), rpe = rpe, durationSec = minutes * 60, load = load,
                summaryJson = AppJson.encodeToString(LiftSummary.serializer(), summary),
            ),
        )
        return summary
    }

    /**
     * Two or more main lifts stepping back within two weeks means fatigue is winning:
     * the next week becomes a lighter one (half the sets, −10 %, more reps in reserve).
     */
    private suspend fun checkReactiveDeload(day: LocalDate) {
        val program = store.programNow() ?: return
        val state = store.liftStateNow()
        val from = day.toEpochDay() - 13
        val setbacks = db.workouts().allDone().filter { it.kind == Kind.LIFT && it.epochDay in from..day.toEpochDay() && !it.deload }
            .flatMap { w ->
                val plan = planOf(w)
                val summary = w.summaryJson?.let { runCatching { AppJson.decodeFromString(LiftSummary.serializer(), it) }.getOrNull() }
                summary?.reports.orEmpty().filter { r ->
                    (r.outcome is Outcome.Reduce || r.outcome is Outcome.Reset) &&
                        plan?.items?.firstOrNull { it.exerciseId == r.exerciseId }?.slot?.role == SlotRole.MAIN
                }.map { it.exerciseId }
            }.distinct()
        val nextMonday = day.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
        val plannedSoon = ProgramGenerator.isDeloadWeek(program, nextMonday) || ProgramGenerator.isDeloadWeek(program, day)
        // At most one lighter week in six: a run of bad days is not a reason to deload again and again.
        val recent = state.reactiveDeloadStart?.let { day.toEpochDay() < it + 42 } == true ||
            (1L..3L).any { ProgramGenerator.isDeloadWeek(program, day.minusWeeks(it)) }
        if (setbacks.size >= 2 && !plannedSoon && !recent) {
            store.setLiftState(state.copy(reactiveDeloadStart = nextMonday.toEpochDay()))
        }
    }

    // ---------- from friends ----------

    /** A friend's own exercises that this phone does not know yet, so their workouts and programs can be copied. */
    suspend fun importExercises(list: List<Exercise>) {
        val known = store.customNow().map { it.id }.toSet()
        val missing = list.filter { it.custom && it.id !in known && Catalog.find(it.id) == null }
        if (missing.isEmpty()) return
        store.setCustom(store.customNow() + missing.map { it.copy(archived = false) })
        refreshCustom()
        onChange()
    }

    /**
     * Starts a session with a friend's exercises, sets and rep ranges; the weights are this user's own (progress,
     * estimate or the first-time search). Exercises this phone cannot know are left out. Null while another session runs.
     */
    suspend fun startCopiedLift(plan: LiftPlan, from: String, exercises: List<Exercise> = emptyList(), date: LocalDate = today()): Long? = sessionLock.withLock {
        if (db.workouts().active() != null) return@withLock null
        importExercises(exercises)
        val specs = plan.items.map { it.slot }.filter { Catalog.find(it.exerciseId)?.archived == false }
            .map { it.copy(sets = it.sets.coerceIn(1, 10), repMin = it.repMin.coerceIn(1, 100), repMax = it.repMax.coerceIn(it.repMin.coerceIn(1, 100), 100), rir = it.rir.coerceIn(0, 5), restSec = it.restSec.coerceIn(0, 600)) }
            .take(12)
        if (specs.isEmpty()) return@withLock null
        val items = specs.mapIndexed { i, spec -> buildItem(i, spec.copy(increment = null), date, deload = false) }
        val copy = LiftPlan(COPIED_TEMPLATE, "${plan.title.take(60)} · как у $from", items, deload = false)
        db.workouts().insert(
            WorkoutEntity(
                epochDay = date.toEpochDay(), kind = Kind.LIFT, templateId = COPIED_TEMPLATE, title = copy.title,
                startedAt = now(), status = Status.ACTIVE, deload = false, planJson = AppJson.encodeToString(LiftPlan.serializer(), copy),
            ),
        )
    }

    /**
     * Takes a friend's program as one's own: the same days, sessions and exercises; this user's weights, block length
     * and progress per exercise. The profile follows (aim and days); changing the profile later builds one's own again.
     */
    suspend fun copyProgram(program: Program, from: String, exercises: List<Exercise> = emptyList()): Boolean {
        importExercises(exercises)
        val profile = store.profileNow() ?: return false
        // Whatever the friend's phone stored, only a program that works here is taken.
        val safe = program.checked { id -> Catalog.find(id)?.archived == false } ?: return false
        val days = safe.week.days.filter { it.sessions.isNotEmpty() }.map { it.day }.toSet().ifEmpty { profile.trainingDays }
        val updated = profile.copy(aim = safe.aim, goal = safe.aim.nearestGoal(), trainingDays = days)
        val own = ProgramGenerator.generate(updated, today())
        store.setProfile(updated)
        store.setProgram(
            safe.copy(
                experience = profile.experience, blockWeeks = own.blockWeeks, startEpochDay = own.startEpochDay,
                createdEpochDay = today().toEpochDay(), copiedFrom = from.take(40), runCaps = own.runCaps,
            ),
        )
        onChange()
        return true
    }

    // ---------- daily life: pause, missed sessions, energy ----------

    /** The last pause window stays in the settings: its days are never counted as missed. */
    fun paused(s: Settings, day: LocalDate): Boolean {
        val until = s.pauseUntil ?: return false
        return day.toEpochDay() in (s.pauseFrom ?: until)..until
    }

    private fun mondayOf(day: LocalDate): LocalDate = day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

    /**
     * Vacation or illness from [from] to [until]: no sessions are expected. A week or more away is rest enough, so the
     * lifting block starts anew in the week of return instead of putting a lighter week right after it. The first
     * sessions back are lighter by themselves (layoff and comeback rules).
     */
    suspend fun pause(from: LocalDate, until: LocalDate) {
        store.setSettings(store.settingsNow().copy(pauseFrom = from.toEpochDay(), pauseUntil = until.toEpochDay()))
        if (until.toEpochDay() - from.toEpochDay() + 1 >= 7) restartBlock(until.plusDays(1))
        onChange()
    }

    /** Back earlier than planned: the pause ends yesterday. */
    suspend fun resume(day: LocalDate = today()) {
        val s = store.settingsNow()
        val from = s.pauseFrom ?: return
        if (!paused(s, day)) return
        store.setSettings(s.copy(pauseUntil = day.toEpochDay() - 1))
        // A long pause moved the block to the planned week of return: bring it to the actual one.
        val movedAhead = (store.programNow()?.startEpochDay ?: Long.MIN_VALUE) > mondayOf(day).toEpochDay()
        if (day.toEpochDay() - from >= 7 || movedAhead) restartBlock(day)
        onChange()
    }

    private suspend fun restartBlock(back: LocalDate) {
        val p = store.programNow() ?: return
        store.setProgram(p.copy(startEpochDay = mondayOf(back).toEpochDay()))
    }

    /** What a finished workout stands for in the weekly plan. */
    private fun asSession(w: WorkoutEntity): PlannedSession = when {
        w.kind == Kind.LIFT -> PlannedSession(SessionKind.LIFT, w.templateId)
        w.templateId == RunType.LONG.name -> PlannedSession(SessionKind.RUN_LONG)
        w.templateId in setOf(RunType.INTERVALS.name, RunType.TEMPO.name, RunType.TEST.name, RunType.GOAL_PACE.name) -> PlannedSession(SessionKind.RUN_QUALITY)
        else -> PlannedSession(SessionKind.RUN_EASY)
    }

    /** A session missed earlier this week that fits [day] — never while paused, never from before the plan existed. */
    suspend fun catchUp(day: LocalDate = today()): CatchUp.Suggestion? {
        val program = store.programNow() ?: return null
        val settings = store.settingsNow()
        if (paused(settings, day)) return null
        val monday = mondayOf(day)
        val done = db.workouts().allDone().filter { it.epochDay in monday.toEpochDay()..monday.toEpochDay() + 6 }
        val week = (1..7).map { d ->
            val date = monday.plusDays(d - 1L)
            val planned = if (paused(settings, date)) emptyList() else program.week.on(d)
            CatchUp.Day(d, planned, done.filter { it.epochDay == date.toEpochDay() }.map(::asSession))
        }
        val first = program.createdEpochDay?.let { (it - monday.toEpochDay() + 1).toInt().coerceIn(1, 8) } ?: 1
        return CatchUp.suggest(program, day.dayOfWeek.value, week, first)
    }

    /** Average training energy per week over the last four weeks (or since the first session), kcal. */
    suspend fun trainingKcalPerWeek(kg: Double, day: LocalDate = today()): Double {
        val all = db.workouts().allDone()
        val first = all.minOfOrNull { it.epochDay } ?: return 0.0
        val from = maxOf(day.toEpochDay() - 28, first)
        val weeks = ((day.toEpochDay() - from) / 7.0).coerceIn(1.0, 4.0)
        val ws = all.filter { it.epochDay in from until day.toEpochDay() }
        val kcal = ws.sumOf { w ->
            val minutes = (w.durationSec ?: 0) / 60.0
            if (w.kind == Kind.LIFT) forma.core.body.Body.liftKcal(kg, minutes)
            else db.runs().get(w.id)?.distanceM?.takeIf { it > 100 }?.let { forma.core.body.Body.runKcal(kg, it / 1000) }
                ?: forma.core.body.Body.runKcalByTime(kg, minutes)
        }
        return kcal / weeks
    }

    // ---------- running ----------

    /**
     * The run for [kind] on [date]: the ladders' next step, a test when due, lighter on a bad day; on a stadium in laps.
     * With a race goal of up to 3 km the test is the race distance itself.
     */
    /** The run of [kind] on [date]; [road]: not on the stadium this time (laps and lap times off, auto-pause on). */
    suspend fun runFor(kind: SessionKind, date: LocalDate = today(), light: Boolean = false, road: Boolean = false): RunWorkout {
        val saved = store.runStateNow()
        val program = store.programNow()
        // The caps of the plan as it is now: after a switch to less running, runs are no longer than it allows.
        val caps = program?.runCaps
        val st = RunPlan.withBreak(saved, date.toEpochDay()).let {
            if (caps == null) it else it.copy(easyMinutes = minOf(it.easyMinutes, caps.easyMax), longMinutes = minOf(it.longMinutes, caps.longMax))
        }
        val profile = store.profileNow()
        val goal = profile?.runGoal
        val lap = profile?.lapMeters?.takeIf { !road }
        val cutback = program != null && ProgramGenerator.isRunCutbackWeek(program, date)
        // Before the first test of a race goal, easy and long runs get a pace limit from the goal (approximate).
        val guess = if (st.vdot == null && goal != null) st.copy(vdot = RunPlan.provisionalVdot(goal)) else null
        val w = if (!st.canRunContinuously) RunPlan.runWalk(st) else when (kind) {
            SessionKind.RUN_LONG -> if (guess != null) RunPlan.provisional(RunPlan.long(guess, cutback, date.toEpochDay())) else RunPlan.long(st, cutback, date.toEpochDay())
            SessionKind.RUN_QUALITY -> when {
                !needsTest(st, date, goal) -> RunPlan.quality(st, cutback, goal, lap)
                goal?.middleDistance == true -> RunPlan.goalTest(st, goal, lap)
                else -> RunPlan.test(st)
            }
            else -> {
                // On a lifting day the easy run comes after the gym: short, unless the plan was built for shared days.
                val liftDay = program?.week?.on(date.dayOfWeek.value)?.any { it.kind == SessionKind.LIFT } == true
                val short = liftDay && profile?.doubleDays != true
                val base = guess ?: st
                RunPlan.easy(if (short) base.copy(easyMinutes = minOf(base.easyMinutes, 30)) else base, cutback, date.toEpochDay())
                    .let { if (guess != null) RunPlan.provisional(it) else it }
            }
        }
        fun track(x: RunWorkout) = if (lap != null) RunPlan.onTrack(x, lap) else x
        val planned = track(if (light) RunPlan.lighter(st, track(w)) else w)
        val comeback = RunPlan.comebackNote(saved, date.toEpochDay()) ?: return planned
        return planned.copy(note = "$comeback ${planned.note}")
    }

    /**
     * A test calibrates paces when they are unknown, and again every 8 weeks — every 5 with a race goal, and right after
     * the race-pace ladder is done (not while coming back from a break).
     */
    private fun needsTest(st: RunState, date: LocalDate, goal: forma.core.model.RunGoal?): Boolean {
        if (st.runs < 2 || st.returnEasy > 0) return false
        if (st.lastTrialTryDay?.let { date.toEpochDay() - it < 14 } == true) return false
        if (st.testSoon) return true
        // With a race of up to 3 km as the goal the first test is that race, whatever fitness was typed in.
        val last = st.lastTestDay ?: return st.vdot == null || goal?.middleDistance == true
        return date.toEpochDay() - last >= if (goal?.middleDistance == true) 35 else 56
    }

    /** A stopwatch run; on a stadium of [lapMeters] with the lap button. */
    fun freeRun(lapMeters: Int? = null): RunWorkout =
        RunWorkout(RunType.FREE, "Свободный бег", emptyList(), "Секундомер, дистанция и темп.", targetRpe = 5, lapMeters = lapMeters)

    /**
     * Saves a finished run: the workout, its numbers and track in one transaction, then the running progress.
     * A run saved already (same start; a second tap, a retry) is returned as it is, not saved twice.
     */
    suspend fun saveRun(
        workout: RunWorkout, result: RunResult, startedAt: Long, points: List<TrackPointEntity>, date: LocalDate = today(),
    ): Pair<Long, RunSummary> = sessionLock.withLock {
        db.workouts().runStartedAt(startedAt)?.let { w ->
            val s = w.summaryJson?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() }
            return@withLock w.id to (s ?: RunSummary(emptyList(), max(1, (w.durationSec ?: 60) / 60), w.load ?: 0.0))
        }
        val program = store.programNow()
        val caps = program?.runCaps ?: forma.core.run.RunCaps(45, 90)
        val st = store.runStateNow()
        val after = RunPlan.after(st, workout, result, caps, date.toEpochDay(), goal = store.profileNow()?.runGoal)
        var next = after.state
        if (workout.type == RunType.TEST && after.outcomes.any { it is RunOutcome.VdotChanged }) next = next.copy(lastTestDay = date.toEpochDay())
        // A test run but not counted (no time, a doubtful one): the next waits two weeks, not until the next hard day.
        else if (workout.type == RunType.TEST) next = next.copy(lastTrialTryDay = date.toEpochDay())
        val minutes = max(1, (result.durationSec / 60.0).roundToInt())
        val load = TrainingLoad.sessionLoad(result.rpe, result.durationSec / 60.0)
        val summary = RunSummary(after.outcomes, minutes, load, result.laps.orEmpty(), result.cadence)
        val avgPace = result.distanceM?.takeIf { it > 50 }?.let { result.durationSec / (it / 1000.0) }
        val id = db.withTransaction {
            val id = db.workouts().insert(
                WorkoutEntity(
                    epochDay = date.toEpochDay(), kind = Kind.RUN, templateId = workout.type.name, title = workout.title,
                    startedAt = startedAt, finishedAt = now(), status = Status.DONE, rpe = result.rpe,
                    durationSec = result.durationSec, load = load,
                    summaryJson = AppJson.encodeToString(RunSummary.serializer(), summary),
                ),
            )
            db.runs().insert(
                RunEntity(
                    id, workout.type.name, AppJson.encodeToString(RunWorkout.serializer(), workout), result.completed,
                    result.durationSec, result.distanceM, avgPace, result.workDistanceM, result.workPace, next.vdot,
                ),
            )
            // One point per moment: a doubled GPS callback must not fail the save.
            if (points.isNotEmpty()) db.runs().insertPoints(points.distinctBy { it.t }.map { it.copy(workoutId = id) })
            id
        }
        // The progress moves only once the run is safely stored.
        store.setRunState(next)
        onChange()
        id to summary
    }

    // ---------- views ----------

    fun weekFlow(monday: LocalDate): Flow<List<DayItem>> =
        combine(store.program, db.workouts().observeDone(monday.toEpochDay(), monday.toEpochDay() + 6)) { program, done ->
            (0 until 7).map { i ->
                val d = monday.plusDays(i.toLong())
                DayItem(d, program?.week?.on(d.dayOfWeek.value).orEmpty(), done.filter { it.epochDay == d.toEpochDay() })
            }
        }

    fun setRows(): Flow<List<SetRow>> = combine(db.sets().observeAllDone(), db.workouts().observeHistory()) { sets, ws ->
        val day = ws.associate { it.id to it.epochDay }
        sets.mapNotNull { s -> day[s.workoutId]?.let { SetRow(s.workoutId, it, s.exerciseId, s.weight, s.reps, s.rir, s.targetReps) } }
    }

    /** Body weight on a given day: the last logged value up to that day. */
    fun bodyWeightLookup(): Flow<(Long) -> Double> = combine(db.state().observeBodyWeight(), store.profile) { list, profile ->
        val fallback = profile?.bodyWeightKg ?: 75.0
        val sorted = list.sortedBy { it.epochDay }
        val f: (Long) -> Double = { day -> sorted.lastOrNull { it.epochDay <= day }?.kg ?: sorted.firstOrNull()?.kg ?: fallback }
        f
    }

    fun dailyLoads(): Flow<Map<LocalDate, Double>> = db.workouts().observeHistory().map { ws ->
        ws.groupBy { LocalDate.ofEpochDay(it.epochDay) }.mapValues { (_, l) -> l.sumOf { it.load ?: 0.0 } }
    }

    suspend fun lastSetsFor(exerciseId: String): List<SetEntity> {
        val recent = db.sets().recent(exerciseId)
        val lastWorkout = recent.firstOrNull()?.workoutId ?: return emptyList()
        return recent.filter { it.workoutId == lastWorkout }.sortedBy { it.doneAt }
    }

    /** The last finished session's report for this exercise: what was done and what changed. */
    suspend fun lastReport(exerciseId: String): Pair<List<SetEntity>, SlotReport?>? {
        val sets = lastSetsFor(exerciseId)
        if (sets.isEmpty()) return null
        val w = db.workouts().get(sets.first().workoutId)
        val report = w?.summaryJson?.let { runCatching { AppJson.decodeFromString(LiftSummary.serializer(), it) }.getOrNull() }
            ?.reports?.firstOrNull { it.exerciseId == exerciseId }
        return sets to report
    }

    /**
     * Everything off the phone (before it takes another account's data): workouts, progress, the plan, the profile
     * and settings. The caller holds [DataLock].
     */
    suspend fun clearPhone() {
        db.withTransaction {
            val b = db.backup()
            b.clearWorkouts(); b.clearSlots(); b.clearStats(); b.clearBodyWeights(); b.clearSyncRows(); b.clearSyncCursors()
        }
        store.clear()
        refreshCustom()
    }

    /** Data changed outside the usual paths (a restored backup file). */
    fun notifyChanged() = onChange()

    suspend fun hasData(): Boolean = db.workouts().allDone().isNotEmpty()

    suspend fun isOnboarded(): Boolean = store.profile.first() != null && store.program.first() != null
}
