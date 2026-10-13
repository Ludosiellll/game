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
import forma.core.text.Ru
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

    /** Midday of [date] in this phone's time zone (when a run entered for an earlier day is filed). */
    fun middayOf(date: LocalDate): Long = date.atTime(12, 0).atZone(zone()).toInstant().toEpochMilli()

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

    /**
     * A novice who has outgrown novice rules: two thirds of the main lifts or more are past the linear phase (a weight
     * a session no longer works) after eight weeks or more in the gym. Then planned light weeks and intermediate
     * choices fit better; the person decides.
     */
    suspend fun outgrewNovice(): Boolean {
        val profile = store.profileNow() ?: return false
        if (profile.experience != Experience.NOVICE) return false
        val lifts = db.workouts().allDone().filter { it.kind == Kind.LIFT }
        val first = lifts.minOfOrNull { it.epochDay } ?: return false
        if (today().toEpochDay() - first < 56) return false
        val mains = store.programNow()?.templates.orEmpty().flatMap { it.slots }.filter { it.role == SlotRole.MAIN }.map { it.key }.distinct()
        val states = mains.mapNotNull { db.state().slot(it) }
        return states.size >= 2 && states.count { !it.linear } * 3 >= mains.size * 2
    }

    /** Saves the profile and rebuilds the plan. Lifting progress is kept per exercise and rep range. */
    suspend fun updateProfile(profile: Profile) {
        val old = store.profileNow()
        store.setProfile(profile)
        store.setProgram(keepCycle(ProgramGenerator.generate(profile, today()), store.programNow()))
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

    /** A beginner who runs 30 minutes now: the profile's level and the week of a runner; the run state stays as it is. */
    private suspend fun graduate() {
        val profile = store.profileNow() ?: return
        if (profile.runLevel != forma.core.model.RunLevel.NONE) return
        val next = profile.copy(runLevel = forma.core.model.RunLevel.BASE)
        val fresh = runCatching { ProgramGenerator.generate(next, today()) }.getOrNull() ?: return
        store.setProfile(next)
        if (store.programNow()?.copiedFrom == null) store.setProgram(keepCycle(fresh, store.programNow()))
        // Not all at once: a runner's week has more runs than three run/walk sessions (seen in simulations: +60–90 % of
        // minutes in one week). The first week stays within 10 % of the last run/walk week — the easy runs are as long
        // as that allows — and the hard sessions come back after three easy runs.
        val st = store.runStateNow()
        val runs = store.programNow()!!.week.days.sumOf { d -> d.sessions.count { it.isRun } }.coerceAtLeast(1)
        val day = today().toEpochDay()
        val lastWeek = st.recent.filter { it.day > day - 7 }.sumOf { it.minutes }
        val easy = if (lastWeek > 0) (lastWeek * 1.1 / runs).toInt().coerceIn(15, 30) else 20
        store.setRunState(st.copy(easyMinutes = easy, longMinutes = minOf(st.longMinutes, easy + 10), returnEasy = maxOf(st.returnEasy, 3)))
    }

    /**
     * The block goes on: a new week layout does not move where the lifter is in the cycle (a lighter week stays where it
     * was due, a vacation shift stays). Another experience level has another block and starts over. A week laid out
     * differently counts from today: what it would have had earlier this week was never shown, so it is not "missed".
     */
    private fun keepCycle(fresh: Program, prev: Program?): Program = when {
        prev == null || prev.experience != fresh.experience -> fresh
        prev.week.days == fresh.week.days -> fresh.copy(startEpochDay = prev.startEpochDay, createdEpochDay = prev.createdEpochDay)
        else -> fresh.copy(startEpochDay = prev.startEpochDay)
    }

    /**
     * A week laid out by an older planner is laid out again by the current one, so a fix in the planner reaches plans
     * made before it; progress and the cycle stay. A program copied from a friend is theirs and stays as it is.
     * [notify]: tell the cloud. Not while the app is still building this repository: the cloud link is built on it, and
     * reaching for it from inside would build a second repository and database (the app syncs at start anyway).
     */
    suspend fun refreshPlan(notify: Boolean = true) {
        val program = store.programNow() ?: return
        val profile = store.profileNow() ?: return
        if (program.planner >= ProgramGenerator.PLANNER || program.copiedFrom != null) return
        // Runs at every start: a profile the planner cannot lay out keeps its plan rather than crash the app each time.
        val fresh = runCatching { ProgramGenerator.generate(profile, today()) }.getOrNull() ?: return
        store.setProgram(keepCycle(fresh, program))
        if (notify) onChange()
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
        /** The plan's estimate for a session as planned, minutes. */
        fun estimateOf(plan: LiftPlan): Double = forma.core.plan.SessionTime.estimate(plan.items.map { it.slot to it.prescription.sets })

        fun timeRatioOf(ws: List<WorkoutEntity>): Double = forma.core.plan.SessionTime.ratio(
            ws.filter { it.kind == Kind.LIFT && it.status == Status.DONE }.sortedBy { it.startedAt }.mapNotNull { w ->
                val plan = w.planJson?.let { runCatching { AppJson.decodeFromString(LiftPlan.serializer(), it) }.getOrNull() } ?: return@mapNotNull null
                val sec = w.durationSec ?: return@mapNotNull null
                estimateOf(plan) to sec / 60.0
            },
        )

        /** [RirScale][forma.core.strength.RirScale] bias from finished lifting sessions and their sets. */
        fun rirBiasOf(ws: List<WorkoutEntity>, sets: List<SetEntity>): Int {
            val by = sets.groupBy { it.workoutId }
            val pairs = mutableListOf<forma.core.strength.RirScale.Pair>()
            for (w in ws.filter { it.kind == Kind.LIFT }.sortedBy { it.startedAt }.takeLast(40)) {
                val plan = w.planJson?.let { runCatching { AppJson.decodeFromString(LiftPlan.serializer(), it) }.getOrNull() } ?: continue
                for (item in plan.items) {
                    val p = item.prescription
                    if (!p.top || p.calibrate) continue
                    val mine = by[w.id].orEmpty().filter { it.position == item.position }.sortedBy { it.doneAt }
                    if (mine.size < 2) continue
                    val plus = mine.last()
                    val rated = mine[mine.size - 2]
                    val rir = rated.rir ?: continue
                    if (kotlin.math.abs(rated.weight - plus.weight) > 1e-6) continue
                    pairs += forma.core.strength.RirScale.Pair(rated.reps, rir, plus.reps, plus.rir)
                }
            }
            return forma.core.strength.RirScale.bias(pairs)
        }

        /** The load story from finished workouts; null without any load. */
        fun storyOf(ws: List<WorkoutEntity>, today: LocalDate): forma.core.load.LoadStory.Story? {
            val loads = ws.filter { (it.load ?: 0.0) > 0 }.groupBy { LocalDate.ofEpochDay(it.epochDay) }.mapValues { (_, l) -> l.sumOf { it.load ?: 0.0 } }
            if (loads.isEmpty()) return null
            val verdicts = ws.mapNotNull { w -> verdict(w)?.let { LocalDate.ofEpochDay(w.epochDay) to it } }
            return forma.core.load.LoadStory.of(
                loads, today,
                bad = verdicts.filter { !it.second }.map { it.first }.toSet(),
                good = verdicts.filter { it.second }.map { it.first }.toSet(),
            )
        }

        /**
         * Whether a session went well (true) or badly (false), from what it changed: in the gym, half the exercises or
         * more short of their reps (held, lowered, reset); on a run, a step repeated or back, a shorter next run, reps
         * too slow. Null: nothing to judge (a deload, a trial, a session without a summary).
         */
        fun verdict(w: WorkoutEntity): Boolean? {
            val json = w.summaryJson ?: return null
            if (w.deload) return null
            return when (w.kind) {
                Kind.LIFT -> {
                    val reports = runCatching { AppJson.decodeFromString(LiftSummary.serializer(), json) }.getOrNull()?.reports ?: return null
                    val judged = reports.filter { it.outcome !is Outcome.Calibrated && it.outcome != Outcome.NotDone && it.outcome != Outcome.Deload }
                    if (judged.isEmpty()) return null
                    val short = judged.count { r ->
                        val o = r.outcome
                        o is Outcome.Reduce || o is Outcome.Reset || o is Outcome.LowerReps ||
                            (o is Outcome.Hold && o.reason in setOf(forma.core.strength.HoldReason.NEAR_MISS, forma.core.strength.HoldReason.FIRST_MISS, forma.core.strength.HoldReason.FEWER_SETS))
                    }
                    short * 2 < judged.size
                }
                else -> {
                    val outcomes = runCatching { AppJson.decodeFromString(RunSummary.serializer(), json) }.getOrNull()?.outcomes ?: return null
                    if (outcomes.isEmpty() || outcomes.any { it is RunOutcome.TestTime || it == RunOutcome.TestNotCounted || it is RunOutcome.TestDoubtful || it is RunOutcome.LighterWeek }) return null
                    outcomes.none { it is RunOutcome.StageRepeat || it is RunOutcome.StageDown || it is RunOutcome.Shorter || it == RunOutcome.TooSlow }
                }
            }
        }

        /** The most sets of one exercise the plan can be set to. */
        const val MAX_SETS = 8
        /** Rest between sets the plan can be set to, seconds. */
        const val MIN_REST = 30
        const val MAX_REST = 300

        /** Where the sets, reps and rest of an exercise added to day [templateId] for good are kept. */
        fun addedKey(templateId: String, exerciseId: String) = "$templateId:$exerciseId"

        /** Sets, reps and rest of an exercise added to a session: 3 sets in a range that suits it. */
        internal fun addedSpec(exerciseId: String): SlotSpec {
            val ex = Catalog[exerciseId]
            return when {
                ex.load == LoadKind.TIMED -> SlotSpec(ex.id, 3, 20, 60, 1, 60, SlotRole.ACCESSORY)
                ex.compound -> SlotSpec(ex.id, 3, 6, 10, 2, 120, SlotRole.SECONDARY)
                else -> SlotSpec(ex.id, 3, 10, 15, 2, 75, SlotRole.ACCESSORY)
            }
        }

        /** The plan's slot as the user set it for good: its sets, reps and rest, the exercise put in its place. */
        internal fun tuned(spec: SlotSpec, settings: Settings): SlotSpec = swapped(overrides(spec, settings), settings)

        /**
         * Sets, rep range and rest the user set for good under [id]: the plan's exercise id, or [addedKey] for one added to a
         * day (the same exercise added to two days, or also in another day's plan, keeps its own numbers on each).
         */
        internal fun overrides(spec: SlotSpec, settings: Settings, id: String = spec.exerciseId): SlotSpec {
            val sets = settings.sets[id]?.coerceIn(1, MAX_SETS) ?: spec.sets
            val reps = settings.reps[id]?.takeIf { it.size == 2 && it[0] >= 1 && it[0] <= it[1] && it[1] <= 100 }
            val rest = settings.rest[id]?.coerceIn(MIN_REST, MAX_REST) ?: spec.restSec
            return spec.copy(sets = sets, repMin = reps?.get(0) ?: spec.repMin, repMax = reps?.get(1) ?: spec.repMax, restSec = rest)
        }

        /** The plan's slot with the exercise the user put in its place for good, if any. */
        internal fun swapped(spec: SlotSpec, settings: Settings): SlotSpec {
            val id = settings.swaps[spec.exerciseId]?.let { Catalog.moved[it] ?: it }?.takeIf { Catalog.find(it)?.archived == false } ?: return spec
            return spec.copy(exerciseId = id, increment = null)
        }

        /** Exercises added to template [templateId] for good that can still be done (an own exercise may be archived). */
        internal fun addedFor(templateId: String, settings: Settings): List<String> =
            settings.added[templateId].orEmpty().map { Catalog.moved[it] ?: it }.filter { Catalog.find(it)?.archived == false }

        /**
         * [program] with [Program.shared]: its templates as done with [settings] — what a friend copying it gets. The
         * same rules as a session's plan: taken out for good stay out (never all), swaps and own numbers in, added ones after.
         */
        fun shareable(program: Program, settings: Settings): Program = program.copy(
            shared = program.templates.map { t ->
                val added = addedFor(t.id, settings)
                val kept = t.slots.filter { it.exerciseId !in settings.removed }.ifEmpty { if (added.isEmpty()) t.slots else emptyList() }
                t.copy(slots = kept.map { tuned(it, settings) } + added.map { overrides(addedSpec(it), settings, addedKey(t.id, it)) })
            },
        )
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
        return LiftPlan(t.id, t.title, warmUps(inOrder(own + extra), deload), deload)
    }

    /**
     * Warm-ups where they matter, in the order of the day: a ramp before a main lift (shorter when its muscles are warm
     * already), one set before a heavy secondary exercise whose muscles have not worked yet; none before work on warm
     * muscles or a single muscle — said why.
     */
    private fun warmUps(items: List<LiftItem>, deload: Boolean): List<LiftItem> {
        val warm = mutableSetOf<forma.core.model.Muscle>()
        return items.map { item ->
            val ex = Catalog[item.exerciseId]
            val p = item.prescription
            val fresh = ex.primary.none { it in warm }
            val bar = if (ex.equipment == Equipment.BARBELL) ex.bar else null
            val heavy = p.weight != null && ex.load == LoadKind.EXTERNAL && ex.compound && !deload
            val out = when {
                item.slot.role == SlotRole.MAIN && item.warmups.isNotEmpty() -> item.copy(warmups = if (fresh) item.warmups else item.warmups.takeLast(2))
                heavy && fresh -> item.copy(warmups = WarmUp.single(p.weight!!, ex.step, bar))
                ex.load == LoadKind.TIMED || deload -> item
                !ex.compound -> item.copy(warmups = emptyList(), noWarmUp = "Разминка не нужна: упражнение на одну мышцу — первый подход сам разогреет.")
                !fresh -> item.copy(warmups = emptyList(), noWarmUp = "Разминка не нужна: ${ex.primary.joinToString(", ") { it.ru.lowercase() }} уже в работе после предыдущих упражнений.")
                else -> item
            }
            warm += ex.primary + ex.secondary
            out
        }
    }

    /**
     * The order of a day: main lifts first while fresh, then the secondary ones, single-muscle work last; within a role,
     * multi-joint before isolation; otherwise the plan's own order. An exercise added by hand takes its place by what it
     * is, not the end of the list. Positions stay as they are (they key swaps and sets).
     */
    private fun inOrder(items: List<LiftItem>): List<LiftItem> = items.withIndex().sortedWith(
        compareBy<IndexedValue<LiftItem>> { it.value.slot.role.ordinal }
            .thenBy { if (Catalog[it.value.exerciseId].compound) 0 else 1 }
            .thenBy { it.index },
    ).map { it.value }

    /** Exercises added to template [templateId] for good that can still be done (an own exercise may be archived). */
    fun addedTo(templateId: String, settings: Settings): List<String> =
        settings.added[templateId].orEmpty().map { Catalog.moved[it] ?: it }.filter { Catalog.find(it)?.archived == false }



    private suspend fun deloadOn(date: LocalDate, force: Boolean): Boolean {
        val program = store.programNow() ?: return force
        val reactive = store.liftStateNow().reactiveDeloadStart
        return force || ProgramGenerator.isDeloadWeek(program, date) || (reactive != null && date.toEpochDay() in reactive until reactive + 7)
    }







    /**
     * How far the start-weight guesses were off for this person: the weight found in each first session against the
     * guess as made from body weight, sex and experience (both as a one-rep estimate, the reps may differ). The median
     * of at least two, between 0.4 and 2.5; 1 before that.
     */
    suspend fun startFactor(profile: Profile, trained: Double): Double {
        val ratios = mutableListOf<Double>()
        for (w in db.workouts().allDone().filter { it.kind == Kind.LIFT }.takeLast(30)) {
            val plan = planOf(w) ?: continue
            val reports = liftSummaryOf(w)?.reports ?: continue
            for (item in plan.items) {
                if (!item.prescription.calibrate) continue
                val o = reports.firstOrNull { it.exerciseId == item.exerciseId }?.outcome as? Outcome.Calibrated ?: continue
                val ex = Catalog.find(item.exerciseId) ?: continue
                val guess = Progression.startHint(ex, profile.bodyWeightKg, profile.sex == Sex.FEMALE, trained) ?: continue
                if (guess <= 0 || o.weight <= 0) continue
                ratios += forma.core.strength.OneRm.estimate(o.weight, o.reps, 2) / forma.core.strength.OneRm.estimate(guess, item.prescription.reps, 2)
            }
        }
        return Progression.ownStart(ratios)
    }

    /** How much longer (or shorter) this person's gym sessions are than the plan estimates ([forma.core.plan.SessionTime]). */
    suspend fun timeRatio(): Double = timeRatioOf(db.workouts().allDone())

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
        val stat = db.state().stat(ex.id)
        // A first time: the start weight is guessed, and the guess learns from how far off it was for this person.
        val own = if (stat == null && slotRow == null && ex.load == LoadKind.EXTERNAL) startFactor(profile, trained) else 1.0
        val hint = Progression.startHint(ex, profile.bodyWeightKg, profile.sex == Sex.FEMALE, trained * own)
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
        // Reps falling off set after set, session after session: this person needs longer rest here (unless they set it).
        val ownRest = ex.load != LoadKind.TIMED && !deload && settings.rest.keys.none { it == spec.exerciseId || it.endsWith(":${spec.exerciseId}") } &&
            forma.core.strength.RestAdvice.longer(
                db.sets().history(ex.id).groupBy { it.workoutId }.values.map { l -> l.sortedBy { it.doneAt }.map { DoneSet(it.weight, it.reps, it.rir) } },
            )
        val rested = if (ownRest) spec.copy(restSec = (spec.restSec + forma.core.strength.RestAdvice.MORE_SEC).coerceAtMost(MAX_REST)) else spec
        return LiftItem(
            position, rested, ex.id, ex.name, p, warm, ex.step,
            restWhy = if (ownRest) "Отдых ${Ru.clock(rested.restSec)} вместо ${Ru.clock(spec.restSec)}: в последних тренировках к последнему подходу " +
                "у тебя повторы падали на 3 и больше. Дольше отдых — больше повторов в поздних подходах." else null,
        )
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

    /**
     * How far this person's "reps left" run off, from their "+" sets ([forma.core.strength.RirScale]); 0 until there are
     * enough. [before]: only sessions before this one.
     */
    suspend fun rirBias(before: Long? = null): Int {
        val ws = db.workouts().allDone().filter { it.kind == Kind.LIFT && (before == null || it.id != before) }.takeLast(40)
        return rirBiasOf(ws, ws.flatMap { db.sets().forWorkout(it.id) })
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
        // Reserves read on this person's own scale (learned before this session).
        val bias = rirBias(before = workoutId)
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
            val done = mine.map { DoneSet(it.weight, it.reps, forma.core.strength.RirScale.correct(it.rir, bias)) }
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
     * Two or more main lifts stepping back within two weeks means fatigue is winning — or one, when the person has been
     * below their own rest line for four days: the next week becomes a lighter one (half the sets, −10 %, more reps in
     * reserve).
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
        // Run down by this person's own measure: below their rest line for four days or more (the dip still on).
        val worn = storyOf(db.workouts().allDone(), day)?.let { st ->
            st.history && st.dips.lastOrNull()?.let { it.recovered == null && day.toEpochDay() - it.from.toEpochDay() >= 3 } == true
        } == true
        if ((setbacks.size >= 2 || worn && setbacks.isNotEmpty()) && !plannedSoon && !recent) {
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
        // As the friend does it (their swaps, sets... put in on their phone) when their phone sent that; whatever it stored,
        // only a program that works here is taken.
        val asDone = program.copy(templates = program.shared ?: program.templates, shared = null)
        val safe = asDone.checked { id -> Catalog.find(id)?.archived == false } ?: return false
        val days = safe.week.days.filter { it.sessions.isNotEmpty() }.map { it.day }.toSet().ifEmpty { profile.trainingDays }
        // The friend's week is the week now: the own days chosen before are not (the plan line and the grid show these).
        val updated = profile.copy(aim = safe.aim, goal = safe.aim.nearestGoal(), trainingDays = days, gymDays = null, runDays = null)
        val own = ProgramGenerator.generate(updated, today())
        store.setProfile(updated)
        store.setProgram(
            safe.copy(
                experience = profile.experience, blockWeeks = own.blockWeeks, startEpochDay = own.startEpochDay,
                createdEpochDay = today().toEpochDay(), copiedFrom = from.take(40), runCaps = own.runCaps,
            ),
        )
        // The own edits of the plan before belong to that plan: the friend's exercises are taken as they are.
        updateSettings { it.copy(swaps = emptyMap(), removed = emptySet(), added = emptyMap(), sets = emptyMap(), reps = emptyMap(), rest = emptyMap()) }
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
    /**
     * The run of [kind] on [date]. [trial]: a due trial may be this one — false for a hard day after the one the trial
     * falls on ([trialDay]), which shows what comes after it instead.
     */
    suspend fun runFor(kind: SessionKind, date: LocalDate = today(), light: Boolean = false, road: Boolean = false, trial: Boolean = true): RunWorkout {
        val saved = store.runStateNow()
        val program = store.programNow()
        // The caps of the plan as it is now: after a switch to less running, runs are no longer than it allows.
        val caps = program?.runCaps
        val st = RunPlan.withBreak(saved, date.toEpochDay()).let {
            if (caps == null) it else it.copy(easyMinutes = minOf(it.easyMinutes, caps.easyMax), longMinutes = minOf(it.longMinutes, caps.longMax))
        }.let { s -> RunPlan.weekCap(saved, date.toEpochDay())?.let { cap -> s.copy(easyMinutes = minOf(s.easyMinutes, cap), longMinutes = minOf(s.longMinutes, cap)) } ?: s }
        val profile = store.profileNow()
        val goal = profile?.runGoal
        val lap = profile?.lapMeters?.takeIf { !road }
        val cutback = program != null && ProgramGenerator.isRunCutbackWeek(program, date)
        // Before the first test of a race goal, easy and long runs get a pace limit from the goal (approximate).
        val guess = if (st.vdot == null && goal != null) st.copy(vdot = RunPlan.provisionalVdot(goal)) else null
        val w = if (!st.canRunContinuously) RunPlan.runWalk(st) else when (kind) {
            SessionKind.RUN_LONG -> if (guess != null) RunPlan.provisional(RunPlan.long(guess, cutback, date.toEpochDay())) else RunPlan.long(st, cutback, date.toEpochDay())
            SessionKind.RUN_QUALITY -> when {
                !trial || !needsTest(st, date, goal) -> RunPlan.quality(st, cutback, goal, lap)
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

    /**
     * The day a due trial falls on: the first hard day from [from] whose run is not done yet (a run of another kind done
     * that day — a free run — takes the day; the trial goes on to the next hard day). The week shows the trial once, the
     * hard days after it as what comes after it.
     */
    suspend fun trialDay(from: LocalDate = today()): LocalDate? {
        val program = store.programNow() ?: return null
        val settings = store.settingsNow()
        val done = db.workouts().observeDone(from.toEpochDay(), from.toEpochDay() + 13).first()
        for (i in 0 until 14) {
            val d = from.plusDays(i.toLong())
            if (paused(settings, d)) continue
            val planned = program.week.on(d.dayOfWeek.value)
            val item = DayItem(d, planned, done.filter { it.epochDay == d.toEpochDay() })
            if (planned.any { it.kind == SessionKind.RUN_QUALITY && !item.isDone(it) }) return d
        }
        return null
    }

    /**
     * The workout a saved run was run to, as planned that day — not what the plan holds now: once a run is saved the plan
     * moves on (the next hard session is another one), and a day already done must show what was done.
     */
    suspend fun runPlanOf(workoutId: Long): RunWorkout? =
        db.runs().get(workoutId)?.let { runCatching { AppJson.decodeFromString(RunWorkout.serializer(), it.planJson) }.getOrNull() }

    /** A stopwatch run; on a stadium of [lapMeters] with the lap button. */
    fun freeRun(lapMeters: Int? = null): RunWorkout =
        RunWorkout(RunType.FREE, "Свободный бег", emptyList(), "Секундомер, дистанция и темп.", targetRpe = 5, lapMeters = lapMeters)

    /**
     * Saves a finished run: the workout, its numbers and track in one transaction, then the running progress.
     * A run saved already (same start; a second tap, a retry) is returned as it is, not saved twice.
     */
    suspend fun saveRun(
        workout: RunWorkout, result: RunResult, startedAt: Long, points: List<TrackPointEntity>, date: LocalDate = today(),
        manual: Boolean = false,
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
        val summary = RunSummary(after.outcomes, minutes, load, result.laps.orEmpty(), result.cadence, manual, result.kmSplits.orEmpty())
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
        // 30 minutes of running reached: the week of a beginner (run/walk only) becomes a runner's week — hard sessions,
        // a trial — with the progress kept. Seen in half-year simulations: without it a graduate had easy runs only, and
        // no trial, for months.
        if (after.outcomes.any { it is RunOutcome.Graduated }) graduate()
        onChange()
        id to summary
    }

    /**
     * The laps of a saved run put right ([forma.core.run.LapEdit]): a press forgotten or doubled. On a stadium where the
     * distance was the laps, it follows them; the plan's decisions on the run stay as they were.
     */
    suspend fun editRunLaps(workoutId: Long, laps: List<forma.core.run.Lap>) {
        val w = db.workouts().get(workoutId) ?: return
        val summary = w.summaryJson?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() } ?: return
        db.workouts().update(w.copy(summaryJson = AppJson.encodeToString(RunSummary.serializer(), summary.copy(laps = laps))))
        val r = db.runs().get(workoutId)
        val lapM = r?.let { runCatching { AppJson.decodeFromString(RunWorkout.serializer(), it.planJson).lapMeters }.getOrNull() }
        if (r != null && lapM != null && summary.laps.isNotEmpty()) {
            val before = summary.laps.sumOf { it.part } * lapM
            val after = laps.sumOf { it.part } * lapM
            val d = r.distanceM
            val next = when {
                // The distance was the laps: it is the laps again.
                d == null || kotlin.math.abs(d - before) < 1.0 -> after
                // GPS saw more than the laps (the laps only in the reps): the laps or GPS, whichever is more.
                d > before -> maxOf(d, after)
                // Less than the laps: only part of the run was counted (the time past the plan left out) — the same part.
                else -> d * after / before
            }
            if (kotlin.math.abs(next - (d ?: 0.0)) >= 0.5) {
                db.runs().insert(r.copy(distanceM = next, avgPace = if (next > 50) r.movingSec / (next / 1000) else r.avgPace))
            }
        }
        onChange()
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

    /** The load story on [today]: the person's rest line is learned from how their own sessions went. */
    fun loadStory(today: LocalDate): Flow<forma.core.load.LoadStory.Story?> = db.workouts().observeHistory().map { ws -> storyOf(ws, today) }

    suspend fun loadStoryNow(today: LocalDate): forma.core.load.LoadStory.Story? = storyOf(db.workouts().allDone(), today)

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
