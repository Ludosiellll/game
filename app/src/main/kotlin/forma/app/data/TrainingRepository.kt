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
    fun today(): LocalDate = java.time.Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()

    // ---------- profile and program ----------

    suspend fun completeOnboarding(profile: Profile) {
        store.setProfile(profile)
        store.setProgram(ProgramGenerator.generate(profile, today()))
        store.setRunState(ProgramGenerator.initialRunState(profile))
        db.state().upsertBodyWeight(BodyWeightEntity(today().toEpochDay(), profile.bodyWeightKg))
        onChange()
    }

    /** Saves the profile and rebuilds the plan. Lifting progress is kept per exercise and rep range. */
    suspend fun updateProfile(profile: Profile) {
        val old = store.profileNow()
        store.setProfile(profile)
        store.setProgram(ProgramGenerator.generate(profile, today()))
        val runChanged = old == null || old.runLevel != profile.runLevel ||
            old.raceMeters != profile.raceMeters || old.raceSeconds != profile.raceSeconds
        if (runChanged) {
            val init = ProgramGenerator.initialRunState(profile)
            val cur = store.runStateNow()
            store.setRunState(
                if (old?.runLevel != profile.runLevel) init.copy(vdot = init.vdot ?: cur.vdot)
                else cur.copy(vdot = init.vdot ?: cur.vdot),
            )
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

    suspend fun liftPlan(templateId: String, date: LocalDate, forceDeload: Boolean = false): LiftPlan {
        val program = store.programNow() ?: error("no program")
        val settings = store.settingsNow()
        val t = program.template(templateId)
        val deload = deloadOn(date, forceDeload)
        val items = t.slots.mapIndexed { i, spec -> buildItem(i, swapped(spec, settings), date, deload) }
        return LiftPlan(t.id, t.title, items, deload)
    }

    private suspend fun deloadOn(date: LocalDate, force: Boolean): Boolean {
        val program = store.programNow() ?: return force
        val reactive = store.liftStateNow().reactiveDeloadStart
        return force || ProgramGenerator.isDeloadWeek(program, date) || (reactive != null && date.toEpochDay() in reactive until reactive + 7)
    }

    /** The plan's slot with the exercise the user put in its place for good, if any. */
    private fun swapped(spec: SlotSpec, settings: Settings): SlotSpec {
        val id = settings.swaps[spec.exerciseId]?.takeIf { Catalog.find(it)?.archived == false } ?: return spec
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
        val original = store.programNow()?.templates?.firstOrNull { it.id == plan.templateId }?.slots?.getOrNull(position)
        val base = original ?: old.slot
        val spec = if (newId == base.exerciseId) base else base.copy(exerciseId = newId, increment = null)
        val item = buildItem(position, spec, LocalDate.ofEpochDay(w.epochDay), plan.deload)
        replacePlan(workoutId, plan.copy(items = plan.items.map { if (it.position == position) item else it }))
        if (forever && original != null) setSwap(original.exerciseId, newId)
        return true
    }

    /** Adds [exerciseId] at the end of a running session, for today only (3 sets in a range that suits it). */
    suspend fun addToWorkout(workoutId: Long, exerciseId: String): Boolean {
        val w = db.workouts().get(workoutId) ?: return false
        val plan = planOf(w) ?: return false
        val ex = Catalog[exerciseId]
        val spec = when {
            ex.load == LoadKind.TIMED -> SlotSpec(ex.id, 3, 20, 60, 1, 60, SlotRole.ACCESSORY)
            ex.compound -> SlotSpec(ex.id, 3, 6, 10, 2, 120, SlotRole.SECONDARY)
            else -> SlotSpec(ex.id, 3, 10, 15, 2, 75, SlotRole.ACCESSORY)
        }
        val position = (plan.items.maxOfOrNull { it.position } ?: -1) + 1
        val item = buildItem(position, spec, LocalDate.ofEpochDay(w.epochDay), plan.deload)
        replacePlan(workoutId, plan.copy(items = plan.items + item))
        return true
    }

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
        for (item in plan.items) {
            val ex = exercise(item.exerciseId, settings)
            val mine = sets.filter { it.position == item.position }
            for (s in mine) if (ex.load != LoadKind.TIMED) {
                volume += Progression.effectiveLoad(ex, s.weight, bw) * s.reps * (if (ex.perHand) 2 else 1)
            }
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
        w.templateId in setOf(RunType.INTERVALS.name, RunType.TEMPO.name, RunType.TEST.name) -> PlannedSession(SessionKind.RUN_QUALITY)
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

    suspend fun runFor(kind: SessionKind, date: LocalDate = today(), light: Boolean = false): RunWorkout {
        val saved = store.runStateNow()
        val st = RunPlan.withBreak(saved, date.toEpochDay())
        val program = store.programNow()
        val cutback = program != null && ProgramGenerator.isRunCutbackWeek(program, date)
        val w = if (!st.canRunContinuously) RunPlan.runWalk(st) else when (kind) {
            SessionKind.RUN_LONG -> RunPlan.long(st, cutback, date.toEpochDay())
            SessionKind.RUN_QUALITY -> if (needsTest(st, date)) RunPlan.test(st) else RunPlan.quality(st, cutback)
            else -> {
                // On a lifting day the easy run comes after the gym: keep it short.
                val liftDay = program?.week?.on(date.dayOfWeek.value)?.any { it.kind == SessionKind.LIFT } == true
                RunPlan.easy(if (liftDay) st.copy(easyMinutes = minOf(st.easyMinutes, 30)) else st, cutback, date.toEpochDay())
            }
        }
        val planned = if (light) RunPlan.lighter(st, w) else w
        val comeback = RunPlan.comebackNote(saved, date.toEpochDay()) ?: return planned
        return planned.copy(note = "$comeback ${planned.note}")
    }

    /** A 12-minute test calibrates paces when they are unknown, and again every 8 weeks (not while coming back). */
    private fun needsTest(st: RunState, date: LocalDate): Boolean {
        if (st.runs < 2 || st.returnEasy > 0) return false
        val last = st.lastTestDay ?: return st.vdot == null
        return date.toEpochDay() - last >= 56
    }

    fun freeRun(): RunWorkout = RunWorkout(RunType.FREE, "Свободный бег", emptyList(), "Секундомер, дистанция и темп.", targetRpe = 5)

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
        val after = RunPlan.after(st, workout, result, caps, date.toEpochDay())
        var next = after.state
        if (workout.type == RunType.TEST && after.outcomes.any { it is RunOutcome.VdotChanged }) next = next.copy(lastTestDay = date.toEpochDay())
        val minutes = max(1, (result.durationSec / 60.0).roundToInt())
        val load = TrainingLoad.sessionLoad(result.rpe, result.durationSec / 60.0)
        val summary = RunSummary(after.outcomes, minutes, load)
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
