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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

class TrainingRepository(
    val db: FormaDatabase,
    val store: SettingsStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Called after anything worth copying to the cloud has been saved. */
    private val onChange: () -> Unit = {},
) {
    fun today(): LocalDate = LocalDate.now(zone)

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
        return settings.steps[id]?.let { ex.copy(step = it) } ?: ex
    }

    private fun SlotStateEntity.toState() = SlotState(weight, reps, stalls, misses, sessions)

    private fun SlotState.toEntity(spec: SlotSpec) =
        SlotStateEntity(spec.key, spec.exerciseId, weight, reps, stalls, misses, sessions, now())

    // ---------- lifting ----------

    suspend fun liftPlan(templateId: String, date: LocalDate, forceDeload: Boolean = false): LiftPlan {
        val program = store.programNow() ?: error("no program")
        val profile = store.profileNow() ?: error("no profile")
        val settings = store.settingsNow()
        val t = program.template(templateId)
        val reactive = store.liftStateNow().reactiveDeloadStart
        val deload = forceDeload || ProgramGenerator.isDeloadWeek(program, date) ||
            (reactive != null && date.toEpochDay() in reactive until reactive + 7)
        val rirOffset = if (deload) 0 else ProgramGenerator.rirOffset(program, date)
        val slots = db.state().slots().associateBy { it.slotKey }
        val stats = db.state().stats().associateBy { it.exerciseId }
        val trained = when (profile.experience) {
            Experience.NOVICE -> 1.0
            Experience.INTERMEDIATE -> 1.35
            Experience.ADVANCED -> 1.7
        }
        val items = t.slots.mapIndexed { i, spec ->
            val ex = exercise(spec.exerciseId, settings)
            val slotRow = slots[spec.key]
            val st = slotRow?.toState() ?: SlotState.fresh(spec)
            val hint = Progression.startHint(ex, profile.bodyWeightKg, profile.sex == Sex.FEMALE, trained)
            val lastDay = slotRow?.updatedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }
            val p = Progression.prescribe(
                ex, spec, st, if (deload) Phase.DELOAD else Phase.NORMAL, rirOffset,
                stats[ex.id]?.e1rm, profile.bodyWeightKg, hint,
                daysSinceLast = lastDay?.let { date.toEpochDay() - it },
            )
            val warm = if (spec.role == SlotRole.MAIN && p.weight != null && ex.load == LoadKind.EXTERNAL && !deload) {
                WarmUp.sets(p.weight!!, ex.step, if (ex.equipment == Equipment.BARBELL) ex.bar else null)
            } else emptyList()
            LiftItem(i, spec, ex.id, ex.name, p, warm, ex.step)
        }
        return LiftPlan(t.id, t.title, items, deload)
    }

    /** Starts (or returns the already running) lifting session. */
    suspend fun startLift(templateId: String, date: LocalDate = today(), forceDeload: Boolean = false): Long {
        db.workouts().active()?.let { return it.id }
        val plan = liftPlan(templateId, date, forceDeload)
        return db.workouts().insert(
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

    suspend fun finishLift(workoutId: Long, rpe: Int): LiftSummary {
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
            val ev = Progression.evaluate(ex, item.slot, st, item.prescription, done, bw)
            if (ev.outcome !is Outcome.NotDone) db.state().upsertSlot(ev.state.toEntity(item.slot))
            var record = false
            ev.e1rm?.let { obs ->
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
        checkReactiveDeload(LocalDate.ofEpochDay(w.epochDay))
        onChange()
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
        val active = state.reactiveDeloadStart?.let { day.toEpochDay() < it + 7 } == true
        if (setbacks.size >= 2 && !plannedSoon && !active) {
            store.setLiftState(state.copy(reactiveDeloadStart = nextMonday.toEpochDay()))
        }
    }

    // ---------- running ----------

    suspend fun runFor(kind: SessionKind, date: LocalDate = today()): RunWorkout {
        val st = RunPlan.withBreak(store.runStateNow(), date.toEpochDay())
        val program = store.programNow()
        val cutback = program != null && ProgramGenerator.isRunCutbackWeek(program, date)
        if (!st.canRunContinuously) return RunPlan.runWalk(st)
        return when (kind) {
            SessionKind.RUN_LONG -> RunPlan.long(st, cutback, date.toEpochDay())
            SessionKind.RUN_QUALITY -> if (needsTest(st, date)) RunPlan.test(st) else RunPlan.quality(st, cutback)
            else -> {
                // On a lifting day the easy run comes after the gym: keep it short.
                val liftDay = program?.week?.on(date.dayOfWeek.value)?.any { it.kind == SessionKind.LIFT } == true
                RunPlan.easy(if (liftDay) st.copy(easyMinutes = minOf(st.easyMinutes, 30)) else st, cutback)
            }
        }
    }

    /** A 12-minute test calibrates paces when they are unknown, and again every 8 weeks. */
    private fun needsTest(st: RunState, date: LocalDate): Boolean {
        if (st.runs < 2) return false
        val last = st.lastTestDay ?: return st.vdot == null
        return date.toEpochDay() - last >= 56
    }

    fun freeRun(): RunWorkout = RunWorkout(RunType.FREE, "Свободный бег", emptyList(), "Секундомер, дистанция и темп.", targetRpe = 5)

    suspend fun saveRun(
        workout: RunWorkout, result: RunResult, startedAt: Long, points: List<TrackPointEntity>, date: LocalDate = today(),
    ): Pair<Long, RunSummary> {
        val program = store.programNow()
        val caps = program?.runCaps ?: forma.core.run.RunCaps(45, 90)
        val st = store.runStateNow()
        val after = RunPlan.after(st, workout, result, caps, date.toEpochDay())
        var next = after.state
        if (workout.type == RunType.TEST && after.outcomes.any { it is RunOutcome.VdotChanged }) next = next.copy(lastTestDay = date.toEpochDay())
        store.setRunState(next)
        val minutes = max(1, (result.durationSec / 60.0).roundToInt())
        val load = TrainingLoad.sessionLoad(result.rpe, result.durationSec / 60.0)
        val summary = RunSummary(after.outcomes, minutes, load)
        val id = db.workouts().insert(
            WorkoutEntity(
                epochDay = date.toEpochDay(), kind = Kind.RUN, templateId = workout.type.name, title = workout.title,
                startedAt = startedAt, finishedAt = now(), status = Status.DONE, rpe = result.rpe,
                durationSec = result.durationSec, load = load,
                summaryJson = AppJson.encodeToString(RunSummary.serializer(), summary),
            ),
        )
        val avgPace = result.distanceM?.takeIf { it > 50 }?.let { result.durationSec / (it / 1000.0) }
        db.runs().insert(
            RunEntity(
                id, workout.type.name, AppJson.encodeToString(RunWorkout.serializer(), workout), result.completed,
                result.durationSec, result.distanceM, avgPace, result.workDistanceM, result.workPace, next.vdot,
            ),
        )
        if (points.isNotEmpty()) db.runs().insertPoints(points.map { it.copy(workoutId = id) })
        onChange()
        return id to summary
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

    /** Data changed outside the usual paths (a restored backup file). */
    fun notifyChanged() = onChange()

    suspend fun hasData(): Boolean = db.workouts().allDone().isNotEmpty()

    suspend fun isOnboarded(): Boolean = store.profile.first() != null && store.program.first() != null
}
