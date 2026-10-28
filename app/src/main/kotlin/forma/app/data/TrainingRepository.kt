package forma.app.data

import forma.core.analytics.SetRow
import forma.core.load.TrainingLoad
import forma.core.model.Catalog
import forma.core.model.Equipment
import forma.core.model.Exercise
import forma.core.model.Experience
import forma.core.model.LiftGoal
import forma.core.model.LoadKind
import forma.core.model.Profile
import forma.core.model.Sex
import forma.core.plan.CatchUp
import forma.core.plan.Picks
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
import forma.core.strength.GymGoal
import forma.core.strength.Outcome
import forma.core.strength.Phase
import forma.core.strength.WarmUpSet
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
import kotlin.math.min
import kotlin.math.roundToInt

/** Template id of sessions copied from a friend (not part of the own plan). */
const val COPIED_TEMPLATE = "copy"

/** "Верх А · копия: Денис" — the friend's name after a colon, so it never has to be declined. */
fun copiedTitle(title: String, from: String): String = "${oneLine(title, 60)} · копия: ${oneLine(from, 40)}"

/**
 * A friend's words (a session's title, an exercise's name) as one plain line: they reach the AI coach as data, so no
 * line breaks, headings or brackets that would read as the app's own text.
 */
fun oneLine(s: String, max: Int = 60): String =
    s.replace(Regex("[\\r\\n\\t#\\[\\]<>`]"), " ").replace(Regex("\\s{2,}"), " ").trim().take(max)

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

    /** The repository's clock, ms. */
    fun nowMs(): Long = now()

    /** Something worth copying to the cloud was saved past the repository's own functions (the coach's edits). */
    internal fun changed() = onChange()

    /**
     * The stadium's lap length (metres) as measured by GPS on a run: the plan reads it when a run is planned, so the
     * program stays as it is.
     */
    suspend fun setLapMeters(meters: Int) = sessionLock.withLock {
        val p = store.profileNow() ?: return@withLock
        if (p.lapMeters == meters) return@withLock
        store.setProfile(p.copy(lapMeters = meters))
        // The loop GPS measured is set (or another length chosen): nothing more to ask.
        store.editSettings { it.copy(loopSeen = null) }
        // The last run on a stadium, while nothing has been built on it: decided again on the loop as it is — its trial's
        // distance, its reps' pace — as a rating put right decides it again.
        val last = store.lastRunNow()?.takeIf { it.after == store.runStateNow() && it.workout.lapMeters != null && it.workout.lapMeters != meters }
        if (last != null) {
            val (w, r) = forma.core.run.RunPlan.onLoop(last.workout, last.result, meters)
            redecideLast(last, w, r)
        }
        onChange()
    }

    /**
     * The last run decided again from [workout] and [result] put right (the lap length, the laps): the progress, the stored
     * plan, distance and pace, the outcomes. Only while nothing has been built on it ([LastRun.after] is the state now).
     */
    private suspend fun redecideLast(last: LastRun, workout: RunWorkout, result: forma.core.run.RunResult) {
        val (next, decided) = decideRun(last.before, workout, result, LocalDate.ofEpochDay(last.day))
        store.setRunState(next)
        store.setLastRun(last.copy(after = next, workout = workout, result = result))
        val (ws, rs) = forma.core.run.RunPlan.onMeasuredLoop(workout, result) ?: (workout to result)
        db.runs().get(last.workoutId)?.let { run ->
            db.runs().insert(
                run.copy(
                    planJson = AppJson.encodeToString(RunWorkout.serializer(), ws), distanceM = rs.distanceM,
                    avgPace = rs.distanceM?.takeIf { it > 50 }?.let { rs.durationSec / (it / 1000.0) }, workPace = rs.workPace, vdotAfter = next.vdot,
                ),
            )
        }
        val w = db.workouts().get(last.workoutId) ?: return
        val summary = w.summaryJson?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() } ?: return
        if (decided.any { it is RunOutcome.Graduated } && summary.outcomes.none { it is RunOutcome.Graduated }) graduate()
        db.workouts().update(w.copy(summaryJson = AppJson.encodeToString(RunSummary.serializer(), summary.copy(outcomes = decided, laps = result.laps ?: summary.laps))))
        store.clearCoachReview(last.workoutId)
    }

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
    /**
     * [next] with the aim of both sides kept when one is dropped («Только зал»): what that side was for and which side
     * came first come back with it. Cleared once both are in the week again.
     */
    private fun keptAimOf(old: Profile?, next: Profile): Profile = when {
        next.aim.lifts && next.aim.runs -> if (next.keptAim != null) next.copy(keptAim = null) else next
        old != null && old.aim.lifts && old.aim.runs -> next.copy(keptAim = old.aim)
        else -> next.copy(keptAim = next.keptAim ?: old?.keptAim)
    }

    suspend fun updateProfile(input: Profile) {
        val old = store.profileNow()
        // Saved on a friend's program without a new aim or days (the screen showed the copy's): one's own program comes
        // back as it was, its days too; with new ones, a plan is built from them.
        val own = store.settingsNow().ownPlan?.takeIf { old != null && store.programNow()?.copiedFrom != null && sameShape(old, input) }
        val profile = keptAimOf(old, own?.let { withShapeOf(input, it.profile) } ?: input)
        if (old != null && (old.gear != profile.gear || old.pullupBar != profile.pullupBar)) moveKit(old, profile)
        store.setProfile(profile)
        store.setProgram(own?.let { ownProgram(profile, it) } ?: keepCycle(ProgramGenerator.generate(profile, today()), store.programNow()))
        reapplyPicks()
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
        // Leaving a friend's program for an own one: the own edits put aside when copying belong to it again.
        store.settingsNow().ownPlan?.let { own -> store.setSettings(own.editsInto(store.settingsNow())) }
        onChange()
    }

    /**
     * Back from a friend's program to one's own as it was before copying: the aim and days of the profile, the program
     * and its edits (swaps, sets, rest...). Copied before the own plan was kept: the own one is built from the profile.
     */
    suspend fun restoreOwnProgram() {
        val profile = store.profileNow() ?: return
        val own = store.settingsNow().ownPlan ?: return updateProfile(profile)
        val restored = withShapeOf(profile, own.profile)
        store.setProfile(restored)
        store.setProgram(ownProgram(restored, own))
        store.setSettings(own.editsInto(store.settingsNow()))
        onChange()
    }

    /** Level up from the progress screen: the program stays (a friend's too) with the level's blocks; progress is kept. */
    suspend fun setExperience(experience: Experience) {
        val profile = store.profileNow() ?: return
        val program = store.programNow()
        if (program?.copiedFrom == null) return updateProfile(profile.copy(experience = experience))
        val next = profile.copy(experience = experience)
        store.setProfile(next)
        store.setProgram(program.copy(experience = experience, blockWeeks = ProgramGenerator.generate(next, today()).blockWeeks))
        onChange()
    }

    /** The same aim, days and session length: what the plan is laid out by. */
    private fun sameShape(a: Profile, b: Profile) = a.aim == b.aim && a.trainingDays == b.trainingDays && a.gymDays == b.gymDays &&
        a.runDays == b.runDays && a.doubleDays == b.doubleDays && a.sessionMinutes == b.sessionMinutes

    /** [profile] with the aim, days and session length of [p]. */
    private fun withShapeOf(profile: Profile, p: Profile) = profile.copy(
        aim = p.aim, goal = p.goal, trainingDays = p.trainingDays, gymDays = p.gymDays, runDays = p.runDays,
        doubleDays = p.doubleDays, sessionMinutes = p.sessionMinutes,
    )

    /** The own program put aside for [profile]: built anew for another experience level (the edits apply all the same). */
    private fun ownProgram(profile: Profile, own: OwnPlan): Program {
        val program = if (own.program.experience == profile.experience) own.program
        else keepCycle(ProgramGenerator.generate(profile, today()), own.program)
        // What this week held under the friend's program was never asked of the own one: not "missed".
        return program.copy(createdEpochDay = today().toEpochDay())
    }

    /** A beginner who runs 30 minutes now: the profile's level and the week of a runner; the run state stays as it is. */
    private suspend fun graduate() {
        val profile = store.profileNow() ?: return
        if (profile.runLevel != forma.core.model.RunLevel.NONE) return
        val next = profile.copy(runLevel = forma.core.model.RunLevel.BASE)
        val fresh = runCatching { ProgramGenerator.generate(next, today()) }.getOrNull() ?: return
        store.setProfile(next)
        if (store.programNow()?.copiedFrom == null) {
            store.setProgram(keepCycle(fresh, store.programNow()))
            reapplyPicks()
        }
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
        reapplyPicks()
        if (notify) onChange()
    }

    /**
     * Weights older versions left, reviewed once ([WEIGHTS_REVIEW]): the latest session of every rep range evaluated
     * again by the current rules. Only upward — sets done heavier than planned, which versions before 27.10 did not
     * count (Denis: pull-ups 22.5 × 7 planned after 25 × 8 with 3 left, 30 × 7, 30 × 6), help on pull-ups not needed.
     * A weight changed since that session (by the coach, by a later session) is not that session's and stays.
     */
    suspend fun reviewWeights(notify: Boolean = true) {
        if (store.settingsNow().weightsReviewed >= WEIGHTS_REVIEW) return
        val profile = store.profileNow()
        if (profile != null) {
            val settings = store.settingsNow()
            // The latest session of each rep range that has sets of it.
            val latest = mutableMapOf<String, Pair<WorkoutEntity, LiftItem>>()
            for (w in db.workouts().allDone().filter { it.kind == Kind.LIFT }.sortedBy { it.startedAt }) {
                val plan = planOf(w) ?: continue
                val done = db.sets().forWorkout(w.id).map { it.position }.toSet()
                for (item in plan.items) if (item.position in done) latest[item.slot.key] = w to item
            }
            var changed = false
            for ((key, pair) in latest) {
                val (w, item) = pair
                val row = db.state().slot(key) ?: continue
                val p = item.prescription
                if (p.phase != Phase.NORMAL) continue
                // What that session left; anything else means the weight was changed since.
                val reports = liftSummaryOf(w)?.reports.orEmpty().filter { it.exerciseId == item.exerciseId }
                val left = when (val o = reports.singleOrNull()?.outcome) {
                    is Outcome.Calibrated -> o.weight
                    is Outcome.AddReps -> o.weight
                    is Outcome.LowerReps -> o.weight
                    is Outcome.Hold -> o.weight
                    is Outcome.AddWeight -> o.to
                    is Outcome.Jump -> o.to
                    is Outcome.Reduce -> o.to
                    is Outcome.Reset -> o.to
                    else -> null
                } ?: continue
                val now = row.weight ?: continue
                if (kotlin.math.abs(now - left) > 1e-9) continue
                val ex = exercise(item.exerciseId, settings)
                val bias = rirBias(before = w.id)
                val sets = db.sets().forWorkout(w.id).filter { it.position == item.position }
                    .map { DoneSet(it.weight, it.reps, forma.core.strength.RirScale.correct(it.rir, bias)) }
                // The state before that session, as far as it is known: its plan.
                val before = if (p.calibrate || p.weight == null) SlotState.fresh(item.slot).copy(linear = row.linear)
                else SlotState(p.weight, p.reps, sessions = max(0, row.sessions - 1), linear = row.linear, cutFrom = row.cutFrom, cutReps = row.cutReps)
                val ev = Progression.evaluate(
                    ex, item.slot, before, p, sets, profile.bodyWeightKg,
                    novice = profile.experience == Experience.NOVICE, recentBest = recentBest(ex.id, w.epochDay, w.id),
                )
                val to = ev.state.weight ?: continue
                if (to <= now + 1e-9) continue
                db.state().upsertSlot(
                    row.copy(
                        weight = to, reps = ev.state.reps, stalls = ev.state.stalls, misses = ev.state.misses, steady = ev.state.steady,
                        linear = ev.state.linear, cutFrom = ev.state.cutFrom, cutReps = ev.state.cutReps,
                        // A moment later than before: the newer change wins on the other phone, the days since it stay.
                        updatedAt = row.updatedAt + 1,
                    ),
                )
                changed = true
            }
            if (changed && notify) onChange()
        }
        store.editSettings { it.copy(weightsReviewed = WEIGHTS_REVIEW) }
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
    internal suspend fun recentBest(exerciseId: String, day: Long, exclude: Long): Double? =
        db.workouts().allDone().asSequence()
            .filter { it.kind == Kind.LIFT && it.id != exclude && it.epochDay in (day - 56)..day }
            .mapNotNull { w -> w.summaryJson?.let { runCatching { AppJson.decodeFromString(LiftSummary.serializer(), it) }.getOrNull() } }
            .flatMap { it.reports.asSequence() }
            .filter { it.exerciseId == exerciseId }
            .mapNotNull { it.e1rm }
            .maxOrNull()

    /**
     * The estimate from the latest session of [exerciseId] (8 weeks before [day]) when every working set of it was done
     * without help — the body alone or more; null when it had help or there is none.
     */
    internal suspend fun ownBest(exerciseId: String, day: Long): Double? {
        val sets = db.sets().history(exerciseId).filter { it.reps > 0 }
        val ids = sets.map { it.workoutId }.toSet()
        val last = db.workouts().allDone().filter { it.kind == Kind.LIFT && it.id in ids && it.epochDay in (day - 56)..day }
            .maxWithOrNull(compareBy<WorkoutEntity> { it.epochDay }.thenBy { it.startedAt }) ?: return null
        if (sets.any { it.workoutId == last.id && it.weight < 0 }) return null
        return liftSummaryOf(last)?.reports?.firstOrNull { it.exerciseId == exerciseId }?.e1rm?.takeIf { it > 0 }
    }

    // ---------- lifting ----------

    companion object {
        /** The review of weights older versions left ([reviewWeights]); a new one when the rules change how sets count. */
        const val WEIGHTS_REVIEW = 1

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
                    if (p.calibrate) continue
                    val mine = by[w.id].orEmpty().filter { it.position == item.position }.sortedBy { it.doneAt }
                    forma.core.strength.RirScale.pairOf(mine.map { DoneSet(it.weight, it.reps, it.rir) }, p.reps)?.let { pairs += it }
                }
            }
            return forma.core.strength.RirScale.bias(pairs)
        }

        /** What a finished workout stands for in the weekly plan. */
        fun sessionOf(w: WorkoutEntity): PlannedSession = when {
            w.kind == Kind.LIFT -> PlannedSession(SessionKind.LIFT, w.templateId)
            w.templateId == RunType.LONG.name -> PlannedSession(SessionKind.RUN_LONG)
            w.templateId in setOf(RunType.INTERVALS.name, RunType.TEMPO.name, RunType.TEST.name, RunType.GOAL_PACE.name) -> PlannedSession(SessionKind.RUN_QUALITY)
            else -> PlannedSession(SessionKind.RUN_EASY)
        }

        /**
         * Whether a lift worked the legs when its template cannot say: a friend's copied workout, by its exercises (the
         * thighs or glutes as main muscles). Null: as its template says (and for runs).
         */
        fun legsOf(w: WorkoutEntity): Boolean? {
            if (w.kind != Kind.LIFT || w.templateId != COPIED_TEMPLATE) return null
            val plan = w.planJson?.let { runCatching { AppJson.decodeFromString(LiftPlan.serializer(), it) }.getOrNull() } ?: return null
            val legs = setOf(forma.core.model.Muscle.QUADS, forma.core.model.Muscle.HAMSTRINGS, forma.core.model.Muscle.GLUTES)
            return plan.items.any { item -> Catalog.find(item.exerciseId)?.primary?.any { it in legs } == true }
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

        /** The longest easy run on a day with a leg session, minutes. */
        const val AFTER_LEGS_EASY = 45

        /** A gap after the last set longer than this means the session was left open, not still going. */
        const val OPEN_GAP_MS = 30 * 60_000L
        /** Time put after the last set of a session left open: the last rest, putting things away. */
        const val AFTER_LAST_SET_MS = 5 * 60_000L

        /**
         * When a lifting session ended: now, unless the last set ([lastSetAt]) was more than [OPEN_GAP_MS] before — then
         * a few minutes after it. Its minutes go into the load: a session finished in the evening must not count the day.
         */
        fun liftEnd(startedAt: Long, lastSetAt: Long?, now: Long): Long = when {
            lastSetAt != null && now - lastSetAt > OPEN_GAP_MS -> maxOf(startedAt, lastSetAt + AFTER_LAST_SET_MS)
            else -> now
        }

        /**
         * Minutes of a lifting session started at [startedAt] with sets done at [setTimes]: to its [liftEnd], each gap
         * longer than [OPEN_GAP_MS] counted as [AFTER_LAST_SET_MS] — a start pressed at home long before the first set,
         * a session left open and carried on another day.
         */
        fun liftMinutes(startedAt: Long, setTimes: List<Long>, now: Long): Int {
            val end = liftEnd(startedAt, setTimes.maxOrNull(), now)
            val ms = (listOf(startedAt) + setTimes.sorted() + end).zipWithNext { a, b ->
                val gap = (b - a).coerceAtLeast(0)
                if (gap > OPEN_GAP_MS) AFTER_LAST_SET_MS else gap
            }.sum()
            return max(1, (ms / 60_000.0).roundToInt())
        }

        /** Days after being sick that still count ([sickEnd]). */
        const val SICK_WINDOW = 21
        /** Easy runs after being sick before reps and tempo come back. */
        const val SICK_EASY_RUNS = 2
        /** No trial within this many days after being sick. */
        const val SICK_NO_TRIAL_DAYS = 7
        /** Why the first gym session after being sick is lighter, said in it. */
        const val SICK_LIFT = "После болезни тренировка полегче: меньше подходов и вес легче, до отказа не доходи. Прогресс и план от этого не собьются."

        /** Every pause as [from, until]: the ones before and the last one. */
        private fun pausesOf(s: Settings): List<List<Long>> =
            s.pastPauses.filter { it.size == 2 } + listOfNotNull(s.pauseUntil?.let { listOf(s.pauseFrom ?: it, it) })

        /**
         * [s] with the last sickness up to [day] taken back: its «Болею» marks and the pauses begun on them (or with them in)
         * are gone. The last sickness is the last mark with its pause and the marks and pauses right before it, one free day
         * between at most; an earlier one stays.
         */
        fun withoutSickness(s: Settings, day: LocalDate): Settings {
            val pauses = pausesOf(s)
            var from = Long.MAX_VALUE
            val sick = s.feels.filterValues { it == Feel.SICK.name }.keys.filter { it <= day.toEpochDay() }.sortedDescending()
            for (m in sick) {
                val with = pauses.filter { m in it[0]..it[1] }
                if (from != Long.MAX_VALUE && maxOf(m, with.maxOfOrNull { it[1] } ?: m) < from - 2) break
                from = minOf(from, m, with.minOfOrNull { it[0] } ?: m)
            }
            val marks = sick.filter { it >= from }.toSet()
            val gone = pauses.filter { p -> marks.any { it in p[0]..p[1] } }.toSet()
            val last = s.pauseUntil?.let { listOf(s.pauseFrom ?: it, it) }
            return s.copy(
                feels = s.feels - marks,
                pastPauses = s.pastPauses.filter { it !in gone },
                pauseFrom = if (last in gone) null else s.pauseFrom,
                pauseUntil = if (last in gone) null else s.pauseUntil,
            )
        }

        /** The most sets of one exercise the plan can be set to. */
        const val MAX_SETS = 8
        /** Rest between sets the plan can be set to, seconds. */
        const val MIN_REST = 30
        const val MAX_REST = 300

        /** Where the sets, reps and rest of an exercise added to day [templateId] for good are kept. */
        fun addedKey(templateId: String, exerciseId: String) = "$templateId:$exerciseId"

        /** [s] with [exerciseId] added to template [templateId] for good; null when it is there already. */
        internal fun withAdded(s: Settings, templateId: String, exerciseId: String): Settings? {
            val list = s.added[templateId].orEmpty()
            if (exerciseId in list) return null
            return s.copy(added = s.added + (templateId to list + exerciseId))
        }

        /** [s] with [newId] in the place of [oldId] among the exercises added to [templateId]; its numbers go with the place. */
        internal fun withReplacedAdded(s: Settings, templateId: String, oldId: String, newId: String): Settings {
            val list = s.added[templateId].orEmpty()
            if (oldId !in list || newId == oldId) return s
            val next = if (newId in list) list - oldId else list.map { if (it == oldId) newId else it }
            // The sets, reps and rest chosen for this place go to the exercise put in it.
            val from = addedKey(templateId, oldId)
            val to = addedKey(templateId, newId)
            fun <V> Map<String, V>.move(): Map<String, V> = this[from]?.let { (this - from) + (to to it) } ?: this
            return s.copy(added = s.added + (templateId to next), sets = s.sets.move(), reps = s.reps.move(), rest = s.rest.move())
        }

        /** [s] with sets, reps and rest of [planId] for good; values equal to the plan's own ([planned]) clear the override. */
        internal fun withTune(s: Settings, planId: String, planned: SlotSpec, sets: Int, reps: Pair<Int, Int>, restSec: Int): Settings {
            val n = sets.coerceIn(1, MAX_SETS)
            val r = restSec.coerceIn(MIN_REST, MAX_REST)
            val range = reps.first.coerceIn(1, 100).let { lo -> lo to reps.second.coerceIn(lo, 100) }
            return s.copy(
                sets = if (n == planned.sets) s.sets - planId else s.sets + (planId to n),
                reps = if (range == planned.repMin to planned.repMax) s.reps - planId else s.reps + (planId to listOf(range.first, range.second)),
                rest = if (r == planned.restSec) s.rest - planId else s.rest + (planId to r),
            )
        }

        /**
         * [s] with [newId] in the place of plan exercise [planId] ([slot]: its place in the plan); the same id or null brings
         * the plan's own back. Sets, reps and rest chosen for a heavy lift do not carry over to a single-joint exercise in
         * its place (and back).
         */
        internal fun withSwap(s: Settings, slot: SlotSpec?, planId: String, newId: String?): Settings {
            if ((s.swaps[planId] ?: planId) == (newId ?: planId)) return s
            val swaps = if (newId == null || newId == planId) s.swaps - planId else s.swaps + (planId to newId)
            val wasDemoted = planId in s.demoted
            val nowDemoted = slot != null && swaps[planId]?.let { demotes(slot, it) } == true
            val demoted = if (nowDemoted) s.demoted + planId else s.demoted - planId
            return if (wasDemoted == nowDemoted) s.copy(swaps = swaps, demoted = demoted)
            else s.copy(swaps = swaps, demoted = demoted, sets = s.sets - planId, reps = s.reps - planId, rest = s.rest - planId)
        }

        /** [s] with [sets] of [planId] for good; null or the plan's own number ([planned]) clears the override. */
        internal fun withSets(s: Settings, planId: String, sets: Int?, planned: Int): Settings {
            val n = sets?.coerceIn(1, MAX_SETS)
            return s.copy(sets = if (n == null || n == planned) s.sets - planId else s.sets + (planId to n))
        }

        /**
         * [settings] with the «Идеальный план» changes [chosen] taken, each with the option picked — the same edits as the
         * plan screen makes. Sets of a plan exercise on several days with different planned numbers stay set on all of them.
         */
        fun advised(settings: Settings, program: Program, chosen: List<forma.core.plan.PlanAdvisor.Change>): Settings {
            var s = settings
            val specs = program.templates.flatMap { it.slots }
            for (c in chosen) {
                val to = c.to ?: continue
                val planId = c.planId
                s = when (c.kind) {
                    forma.core.plan.PlanAdvisor.Kind.SWAP -> when {
                        planId == null -> s
                        c.added -> withReplacedAdded(s, c.templateId, planId, to)
                        // Accepted by the person: theirs from now on, not undone when a pick is cleared.
                        else -> withSwap(s, specs.firstOrNull { it.exerciseId == planId }, planId, to).let { it.copy(pickSwaps = it.pickSwaps - planId) }
                    }
                    forma.core.plan.PlanAdvisor.Kind.ADD -> {
                        val added = withAdded(s, c.templateId, to)
                        val planned = addedSpec(to)
                        when {
                            added == null -> s
                            c.setsTo == null && c.repsTo == null && c.restSec == null -> added
                            else -> withTune(
                                added, addedKey(c.templateId, to), planned, c.setsTo ?: planned.sets,
                                c.repsTo ?: (planned.repMin to planned.repMax), c.restSec ?: planned.restSec,
                            )
                        }
                    }
                    forma.core.plan.PlanAdvisor.Kind.SETS -> when {
                        planId == null || c.setsTo == null -> s
                        c.added -> withSets(s, addedKey(c.templateId, planId), c.setsTo, addedSpec(planId).sets)
                        else -> {
                            val planned = specs.filter { it.exerciseId == planId }.map { plannedFor(it, s).sets }.distinct()
                            withSets(s, planId, c.setsTo, planned.singleOrNull() ?: -1)
                        }
                    }
                }
            }
            return s
        }

        /** Sets, reps and rest of an exercise added to a session: 3 sets in a range that suits it. */
        internal fun addedSpec(exerciseId: String): SlotSpec {
            val ex = Catalog[exerciseId]
            return when {
                ex.load == LoadKind.TIMED -> SlotSpec(ex.id, 3, 20, 60, 1, 60, SlotRole.ACCESSORY)
                ex.compound -> SlotSpec(ex.id, 3, 6, 10, 2, 120, SlotRole.SECONDARY)
                else -> SlotSpec(ex.id, 3, 10, 15, 2, 75, SlotRole.ACCESSORY)
            }
        }

        /**
         * The plan's slot as the user set it for good: its sets, reps and rest, the exercise put in its place. In the place
         * of a heavy lift a single-joint exercise starts from an added one's numbers; the user's own come on top.
         */
        internal fun tuned(spec: SlotSpec, settings: Settings): SlotSpec {
            val id = swapOf(spec, settings) ?: return overrides(spec, settings)
            return if (demotes(spec, id) && spec.exerciseId in settings.demoted) overrides(addedSpec(id), settings, spec.exerciseId)
            else overrides(spec, settings).copy(exerciseId = id, increment = null)
        }

        /** What the plan sets for its slot [spec] with the user's swap in it, before the user's own sets, reps and rest. */
        fun plannedFor(spec: SlotSpec, settings: Settings): SlotSpec =
            swapOf(spec, settings)?.takeIf { demotes(spec, it) && spec.exerciseId in settings.demoted }?.let { addedSpec(it) } ?: spec

        /**
         * A single-joint exercise put in the place of a multi-joint main or secondary lift: heavy sets of 5–8 with long
         * rests do not suit it, it is done like an added one (3 × 10–15).
         */
        fun demotes(spec: SlotSpec, newId: String): Boolean {
            if (spec.role == SlotRole.ACCESSORY || newId == spec.exerciseId) return false
            val was = Catalog.find(spec.exerciseId) ?: return false
            val now = Catalog.find(newId) ?: return false
            return was.compound && !now.compound && now.load != LoadKind.TIMED
        }

        /** [spec] with [newId] in its place: the same numbers, or an added one's when the swap [demotes] it. */
        fun swapSpec(spec: SlotSpec, newId: String): SlotSpec = when {
            newId == spec.exerciseId -> spec
            demotes(spec, newId) -> addedSpec(newId)
            else -> spec.copy(exerciseId = newId, increment = null)
        }

        /** The exercise the user put in the place of [spec] for good, if any (and it can still be done). */
        private fun swapOf(spec: SlotSpec, settings: Settings): String? =
            settings.swaps[spec.exerciseId]?.let { Catalog.moved[it] ?: it }?.takeIf { Catalog.find(it)?.archived == false }

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
        internal fun swapped(spec: SlotSpec, settings: Settings): SlotSpec = swapOf(spec, settings)?.let {
            if (spec.exerciseId in settings.demoted) swapSpec(spec, it) else if (it == spec.exerciseId) spec else spec.copy(exerciseId = it, increment = null)
        } ?: spec

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
        // The goal in the gym: its lift first among the main ones while fresh, and in the days before its date half the
        // sets at the same weight (the taper).
        val goal = store.profileNow()?.liftGoal
        val taper = GymGoal.tapering(goal, date.toEpochDay())
        // The legs three days before the goal race or nearer: a lighter session, the race wants fresh legs.
        val deload = deloadOn(date, forceDeload) || t.legs && daysToRace(date)?.let { it in 0..3 } == true
        // Not feeling well («Так себе») or back after being sick: lighter for the goal's lift too, and no max test.
        val unwell = forceDeload || liftAfterSick(date)
        // The goal's lift in its taper: half the sets at the working weight, no "+" set — a lighter week of the block
        // does not make it lighter still (the taper is its own lighter plan).
        // Its heavy sets are the day's main lift even where the plan had it second: first, with a full warm-up ramp.
        suspend fun item(position: Int, planned: SlotSpec, restKey: String): LiftItem {
            val heavyGoal = planned.exerciseId == goal?.exerciseId && planned.repMax <= 6 && planned.role == SlotRole.SECONDARY
            val spec = if (heavyGoal) planned.copy(role = SlotRole.MAIN) else planned
            val tapered = taper && spec.exerciseId == goal?.exerciseId
            return when {
                tapered && unwell -> buildItem(position, spec.copy(sets = GymGoal.taperSets(spec.sets)), date, true, restKey)
                tapered -> buildItem(position, spec.copy(sets = GymGoal.taperSets(spec.sets)), date, false, restKey, Phase.TAPER)
                else -> buildItem(position, spec, date, deload, restKey)
            }
        }
        // Exercises taken out for good stay out — never all of them. Positions stay the plan's (swaps look them up).
        val added = addedTo(t.id, settings)
        val kept = t.slots.withIndex().filter { it.value.exerciseId !in settings.removed }
            .ifEmpty { if (added.isEmpty()) t.slots.withIndex().toList() else emptyList() }
        val own = kept.map { (i, spec) -> item(i, tuned(spec, settings), spec.exerciseId).copy(planId = spec.exerciseId) }
        // Added for good: after the plan's own, as chosen (no swap applies to them: they were picked by hand).
        val extra = added.mapIndexed { j, id ->
            val key = addedKey(t.id, id)
            item(t.slots.size + j, overrides(addedSpec(id), settings, key), key).copy(planId = id, addedForGood = true)
        }
        val why = if (deload && !forceDeload && liftAfterSick(date) && !ProgramGenerator.isDeloadWeek(program, date)) SICK_LIFT else null
        val items = warmUps(inOrder(own + extra, goal?.exerciseId), deload)
        val test = goal?.date == date.toEpochDay() && !unwell
        return LiftPlan(t.id, t.title, if (test) testOn(items, goal!!) else items, deload, why)
    }

    /**
     * The goal's day: its lift (the first place of it) is the test of the max — the warm-ups and the single attempts of
     * [GymGoals.testDay] instead of its sets; the progress of the plan stays as it was ([Phase.TEST]).
     */
    private suspend fun testOn(items: List<LiftItem>, goal: LiftGoal): List<LiftItem> {
        val at = items.indexOfFirst { it.exerciseId == goal.exerciseId }.takeIf { it >= 0 } ?: return items
        val test = GymGoals.testDay(this, goal) ?: return items
        val it0 = items[at]
        val p = it0.prescription.copy(
            weight = test.attempts.first(), reps = 1, sets = test.attempts.size, rir = 0, calibrate = false, startHint = null,
            phase = Phase.TEST, top = false, intro = false, attempts = test.attempts,
        )
        val tested = it0.copy(prescription = p, warmups = test.warmups.map { (w, r) -> WarmUpSet(w, r) }, noWarmUp = null)
        // First of the day, while fresh.
        return listOf(tested) + items.filterIndexed { i, _ -> i != at }
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
            // The working weight, or the first time the weight the search starts from.
            val work = p.weight ?: p.startHint?.takeIf { p.calibrate && it > 0 }
            val heavy = work != null && ex.load == LoadKind.EXTERNAL && ex.compound && !deload
            val single = if (heavy && fresh) WarmUp.single(work!!, ex.step, bar) else emptyList()
            val out = when {
                item.slot.role == SlotRole.MAIN && item.warmups.isNotEmpty() -> item.copy(warmups = if (fresh) item.warmups else item.warmups.takeLast(2))
                single.isNotEmpty() -> item.copy(warmups = single)
                ex.load == LoadKind.TIMED || deload -> item
                // Nothing lighter than the work itself: the empty bar or the lightest load.
                heavy && fresh -> item.copy(
                    warmups = emptyList(),
                    noWarmUp = "Разминка — первый подход: вес и так самый лёгкий. Сделай его медленно, следи за техникой.",
                )
                !ex.compound -> item.copy(warmups = emptyList(), noWarmUp = "Разминка не нужна: упражнение на одну мышцу, первый подход сам её разогреет.")
                !fresh -> item.copy(warmups = emptyList(), noWarmUp = "Разминка не нужна: ${ex.primary.joinToString(", ") { it.ru.lowercase() }} уже в работе после прошлых упражнений.")
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
    private fun inOrder(items: List<LiftItem>, goalLift: String? = null): List<LiftItem> = items.withIndex().sortedWith(
        compareBy<IndexedValue<LiftItem>> { it.value.slot.role.ordinal }
            .thenBy { if (it.value.exerciseId == goalLift) 0 else 1 }
            .thenBy { if (Catalog[it.value.exerciseId].compound) 0 else 1 }
            .thenBy { it.index },
    ).map { it.value }

    /** Exercises added to template [templateId] for good that can still be done (an own exercise may be archived). */
    fun addedTo(templateId: String, settings: Settings): List<String> =
        settings.added[templateId].orEmpty().map { Catalog.moved[it] ?: it }.filter { Catalog.find(it)?.archived == false }



    private suspend fun deloadOn(date: LocalDate, force: Boolean): Boolean {
        val program = store.programNow() ?: return force
        val reactive = store.liftStateNow().reactiveDeloadStart
        return force || ProgramGenerator.isDeloadWeek(program, date) || (reactive != null && date.toEpochDay() in reactive until reactive + 7) ||
            liftAfterSick(date)
    }

    /**
     * The last day of being sick before [date], within [SICK_WINDOW] days: the last day marked «Болею», or the end of a
     * pause begun on one (or with one in it). Null — not sick lately. Read from the marks, so a mistap set back is no sickness.
     */
    fun sickEnd(settings: Settings, date: LocalDate): Long? {
        val d = date.toEpochDay()
        val sick = settings.feels.filterValues { it == Feel.SICK.name }.keys
        val ends = sick.filter { it < d } + pausesOf(settings).filter { p -> sick.any { it in p[0]..p[1] } }.map { minOf(it[1], d - 1) }
        return ends.filter { it >= d - SICK_WINDOW }.maxOrNull()
    }

    /** Whether [day] is in a pause begun on a «Болею» day (or with one in it): the sick card's pause. */
    fun sickPauseOn(settings: Settings, day: LocalDate): Boolean {
        val d = day.toEpochDay()
        val sick = settings.feels.filterValues { it == Feel.SICK.name }.keys
        return pausesOf(settings).any { p -> d in p[0]..p[1] && sick.any { it in p[0]..p[1] } }
    }

    /**
     * «Болею» taken back — it was nothing after all ([withoutSickness]): the days of it are ordinary skipped days, missed as
     * any, and the runs and the gym go by the plan with no easy return.
     */
    suspend fun notSick(day: LocalDate = today()) {
        val before = store.settingsNow()
        if (sickEnd(before, day.plusDays(1)) == null) return
        store.editSettings { withoutSickness(it, day) }
        // A pause of a week or more moved the block to the week of return: back to this week, as at an early return.
        val movedAhead = (store.programNow()?.startEpochDay ?: Long.MIN_VALUE) > mondayOf(day).toEpochDay()
        if (sickPauseOn(before, day) && movedAhead) restartBlock(day)
        onChange()
    }

    /** Runs still to go easy after being sick ([SICK_EASY_RUNS] minus those done since); 0 — none. */
    suspend fun easyAfterSick(date: LocalDate): Int {
        val end = sickEnd(store.settingsNow(), date) ?: return 0
        val done = db.workouts().allDone().count { it.kind == Kind.RUN && it.epochDay > end && it.epochDay < date.toEpochDay() }
        return (SICK_EASY_RUNS - done).coerceAtLeast(0)
    }

    /** The first gym session after being sick: lighter (fewer sets, lighter weights); the plan does not move. */
    suspend fun liftAfterSick(date: LocalDate): Boolean {
        val end = sickEnd(store.settingsNow(), date) ?: return false
        return db.workouts().allDone().none { it.kind == Kind.LIFT && it.epochDay > end && it.epochDay < date.toEpochDay() }
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

    /**
     * One exercise of a session: today's prescription from its own progress, the stored estimate or a start hint.
     * [restKey]: where the user's own rest for this place is kept — the plan's exercise id (before a swap), [addedKey]
     * for one added to a day for good, null for one added today only.
     */
    private suspend fun buildItem(position: Int, spec: SlotSpec, date: LocalDate, deload: Boolean, restKey: String? = null, phase: Phase? = null): LiftItem {
        val program = store.programNow() ?: error("no program")
        val profile = store.profileNow() ?: error("no profile")
        val settings = store.settingsNow()
        // The days before a lighter week the app set itself are not the block's peak: the reserve as planned.
        val lighterNext = store.liftStateNow().reactiveDeloadStart?.let { date.toEpochDay() in it - 7 until it } == true
        val rirOffset = if (deload) 0 else ProgramGenerator.rirOffset(program, date).let { if (lighterNext) max(0, it) else it }
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
            ex, spec, st, phase ?: if (deload) Phase.DELOAD else Phase.NORMAL, rirOffset,
            stat?.e1rm, profile.bodyWeightKg, hint,
            daysSinceLast = lastDay?.let { date.toEpochDay() - it },
            // Legs a day or two before a hard run: the "+" set stops with 2 in reserve, the run gets fresher legs.
            topRir = if (ex.lowerBody && hardRunSoon(program, date)) 2 else 1,
            // A novice's gentle first time, unless the person set this exercise's sets themselves.
            novice = profile.experience == Experience.NOVICE && (restKey == null || restKey !in settings.sets),
            // The latest session without help: help left from before goes for whoever did five and more with the body alone.
            ownBest = if (ex.assisted && spec.key !in settings.chosenHelp) ownBest(ex.id, date.toEpochDay()) else null,
        )
        // The ramp leads to the working weight, or the first time to the weight to start the search from.
        val work = p.weight ?: p.startHint?.takeIf { p.calibrate }
        val warm = if (spec.role == SlotRole.MAIN && work != null && work > 0 && ex.load == LoadKind.EXTERNAL && !deload) {
            WarmUp.sets(work, ex.step, if (ex.equipment == Equipment.BARBELL) ex.bar else null)
        } else emptyList()
        // How this person recovers on this exercise: reps lost from set to set after the rest they took.
        // Own rest belongs to the place in the plan: it stays after a swap, and one set for a day stays on that day.
        val ownRest = restKey != null && restKey in settings.rest
        val shift = if (ex.load == LoadKind.TIMED || ownRest) 0 else forma.core.strength.RestAdvice.personal(
            db.sets().history(ex.id).groupBy { it.workoutId }.values.flatMap { l ->
                val target = l.firstOrNull()?.targetReps ?: spec.repMin
                forma.core.strength.RestAdvice.pairsOf(l.sortedBy { it.doneAt }.map { DoneSet(it.weight, it.reps, it.rir) to it.doneAt }, target) {
                    Progression.effectiveLoad(ex, it, profile.bodyWeightKg)
                }
            },
            spec.restSec,
        )
        return LiftItem(
            position, spec, ex.id, ex.name, p, warm, ex.step, restShift = shift, restOwn = ownRest,
            restWhy = when {
                shift > 0 -> "Отдых здесь у тебя на ${shift} с длиннее. В прошлых тренировках после обычного отдыха ты терял " +
                    "в следующем подходе ${if (shift >= 60) "3 повтора и больше" else "2 повтора и больше"}."
                shift < 0 -> "Отдых здесь у тебя на ${-shift} с короче. В прошлых тренировках ты почти не терял повторов от подхода к подходу."
                else -> null
            },
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
        // The exercise already in place picked again: nothing changes, today or for good.
        if (newId == old.exerciseId) return true
        if (db.sets().forWorkout(workoutId).any { it.position == position }) return false
        val settings = store.settingsNow()
        val own = ownSlot(plan.templateId, old)
        val original = originalOf(plan, old, own, settings)
        val base = original ?: old.slot
        val spec = swapSpec(base, newId)
        val forGood = forever && original != null && old.addedForGood
        // The user's own rest stays with the place, unless the swap made it another kind of exercise.
        val restKey = when {
            demotes(base, newId) -> null
            old.addedForGood && old.planId != null -> addedKey(plan.templateId, old.planId)
            else -> own?.exerciseId
        }
        val item = buildItem(position, spec, LocalDate.ofEpochDay(w.epochDay), plan.deload, restKey)
            .copy(planId = if (forGood) newId else old.planId ?: own?.exerciseId, addedForGood = old.addedForGood)
        replacePlan(workoutId, plan.copy(items = plan.items.map { if (it.position == position) item else it }))
        if (forever && original != null) {
            if (old.addedForGood) replaceAdded(plan.templateId, original.exerciseId, newId) else setSwap(original.exerciseId, newId)
        }
        return true
    }

    /** The plan's exercise at [old]'s place with its sets, reps and rest for good (a swap is today's choice, those stay). */
    private fun originalOf(plan: LiftPlan, old: LiftItem, own: SlotSpec?, settings: Settings): SlotSpec? = when {
        old.addedForGood && old.planId != null -> overrides(addedSpec(old.planId), settings, addedKey(plan.templateId, old.planId))
        // A slot swapped for a single-joint exercise keeps that one's sets, reps and rest under its id: not the lift's.
        own != null -> if (own.exerciseId in settings.demoted) own else overrides(own, settings)
        else -> null
    }

    /**
     * What a swap of the exercise at [position] for [newId] would change: the numbers before and after, when a
     * single-joint exercise takes the place of a heavy lift ([demotes]); null when they stay.
     */
    suspend fun swapChange(workoutId: Long, position: Int, newId: String): Pair<SlotSpec, SlotSpec>? {
        val plan = db.workouts().get(workoutId)?.let { planOf(it) } ?: return null
        val old = plan.items.firstOrNull { it.position == position }?.takeIf { it.exerciseId != newId } ?: return null
        val base = originalOf(plan, old, ownSlot(plan.templateId, old), store.settingsNow()) ?: old.slot
        return if (demotes(base, newId)) base to swapSpec(base, newId) else null
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
        val item = buildItem(position, spec, LocalDate.ofEpochDay(w.epochDay), plan.deload, restKey = if (forever) addedKey(plan.templateId, exerciseId) else null)
            .let { if (forever) it.copy(planId = exerciseId, addedForGood = true) else it }
        replacePlan(workoutId, plan.copy(items = plan.items + item))
        if (forever) addToPlan(plan.templateId, exerciseId)
        return true
    }

    /** Adds [exerciseId] to every session of template [templateId] from now on, after the plan's own exercises. */
    suspend fun addToPlan(templateId: String, exerciseId: String): Boolean {
        // In one edit of the settings: a change made meanwhile (a run saved, the coach) is not written over.
        var added = false
        store.editSettings { s -> withAdded(s, templateId, exerciseId)?.also { added = true } ?: s }
        if (!added) return false
        onChange()
        return true
    }

    /**
     * The goal in the gym set to [goal] (null — none). The plan changes made for the goal before go back first (where
     * still as it left them); with [adjust], the plan trains the new goal's lift for it ([GymGoals.propose]). The start
     * of the way (the estimated max, the day) is kept while the lift stays the same; it is the max the way itself
     * measures ([GymGoals.path]), so a goal just set is at the start of its way. Without [adjust], the plan made for a
     * goal on the same lift stays, unless [keepPlan] is off («Вернуть» to a goal that had none).
     */
    suspend fun setLiftGoal(goal: LiftGoal?, adjust: Boolean, keepPlan: Boolean = true) {
        val profile = store.profileNow() ?: return
        val old = profile.liftGoal
        val stamped = goal?.let { g ->
            val same = old?.exerciseId == g.exerciseId
            // A goal put back as it was («Вернуть») keeps its own start.
            g.copy(
                fromE1rm = g.fromE1rm ?: if (same && old?.fromE1rm != null) old.fromE1rm
                    else GymGoals.path(this, g)?.current ?: db.state().stat(g.exerciseId)?.e1rm,
                setDay = g.setDay ?: if (same) old?.setDay ?: today().toEpochDay() else today().toEpochDay(),
            )
        }
        store.setProfile(profile.copy(liftGoal = stamped))
        val program = store.programNow()
        val proposal = if (stamped != null && adjust && program != null) {
            GymGoals.propose(CoachActions.places(this), program, profile.experience, stamped.exerciseId)
        } else null
        store.editSettings { s ->
            val keep = s.goalPlan?.takeIf { stamped != null && !adjust && keepPlan && it.exerciseId == stamped.exerciseId }
            val back = if (keep != null) s else GymGoals.revert(s)
            proposal?.edit?.invoke(back) ?: back
        }
        onChange()
    }

    /** Takes an exercise added for good out of template [templateId], with the numbers set for it there. */
    suspend fun removeAdded(templateId: String, exerciseId: String) {
        val key = addedKey(templateId, exerciseId)
        store.editSettings { s ->
            val list = s.added[templateId].orEmpty() - exerciseId
            s.copy(
                added = if (list.isEmpty()) s.added - templateId else s.added + (templateId to list),
                sets = s.sets - key, reps = s.reps - key, rest = s.rest - key,
            )
        }
        onChange()
    }

    /** Puts [newId] in place of [oldId] among the exercises added for good to template [templateId]. */
    suspend fun replaceAdded(templateId: String, oldId: String, newId: String) {
        var changed = false
        store.editSettings { s -> withReplacedAdded(s, templateId, oldId, newId).also { changed = it != s } }
        if (!changed) return
        onChange()
    }

    /**
     * Sets, rep range and rest kept under [planId] (the plan's exercise id, or [addedKey] for one added to a day) in
     * every session from now on; values equal to the plan's own ([planned]) clear the override, so a later change of the
     * plan comes through.
     */
    suspend fun tunePlan(planId: String, planned: SlotSpec, sets: Int, reps: Pair<Int, Int>, restSec: Int) {
        store.editSettings { withTune(it, planId, planned, sets, reps, restSec) }
        onChange()
    }

    /** The exercise added for good [exerciseId] as the plan starts it (before the user's own sets, reps and rest). */
    fun addedPlanned(exerciseId: String): SlotSpec = addedSpec(exerciseId)

    /** Replaces [planId] by [newId] in every session from now on; the same id (or null) brings the original back. */
    /** Settings changed on their screen; they travel to other phones with the next sync. */
    suspend fun updateSettings(f: (Settings) -> Settings) {
        store.editSettings(f)
        onChange()
    }

    suspend fun setSwap(planId: String, newId: String?) {
        // The same exercise picked again changes nothing (a swap made before the rule below keeps its numbers), but picked
        // by hand it is the person's own now, not the picks'.
        if ((store.settingsNow().swaps[planId] ?: planId) == (newId ?: planId)) {
            if (planId in store.settingsNow().pickSwaps) store.editSettings { it.copy(pickSwaps = it.pickSwaps - planId) }
            return
        }
        val slot = store.programNow()?.templates?.flatMap { it.slots }?.firstOrNull { it.exerciseId == planId }
        // Made by hand: the person's own from now on, not undone when a pick is cleared.
        store.editSettings { s -> withSwap(s, slot, planId, newId).let { it.copy(pickSwaps = it.pickSwaps - planId) } }
        onChange()
    }

    // ---------- «Мои упражнения» ----------

    /** The places of the plan as done now (replacements in), for [Picks.resolve]. */
    private fun pickPlaces(program: Program, s: Settings): List<Picks.Place> = program.templates.flatMap { t ->
        t.slots.map { Picks.Place(t.id, it.exerciseId, swapOf(it, s) ?: it.exerciseId, it.role) }
    }

    /** The picks of [s] put into the plan: replacements for good where they say, the earlier ones of picks undone. */
    private fun withPicks(s: Settings, program: Program, profile: Profile, fresh: Set<forma.core.model.Pattern> = emptySet()): Pair<Settings, Picks.Result> {
        val likes = likesOf(s)
        val r = Picks.resolve(pickPlaces(program, s), likes, s.dislikes, Picks.Rules.of(profile, s.removed), s.pickSwaps, fresh)
        val slots = program.templates.flatMap { it.slots }
        val out = r.swaps.entries.fold(s) { acc, (planId, to) ->
            withSwap(acc, slots.firstOrNull { it.exerciseId == planId }, planId, to)
                .let { it.copy(pickSwaps = if (to == planId) it.pickSwaps - planId else it.pickSwaps + planId) }
        }
        return out to r
    }

    private fun likesOf(s: Settings) = s.likes.mapNotNull { (k, v) -> runCatching { forma.core.model.Pattern.valueOf(k) }.getOrNull()?.let { it to v } }.toMap()

    /** The movements whose likes differ between [was] and [now]: there the new likes win over a replacement made by hand. */
    private fun changed(was: Settings, now: Map<forma.core.model.Pattern, List<String>>): Set<forma.core.model.Pattern> {
        val old = likesOf(was)
        return (old.keys + now.keys).filter { old[it].orEmpty() != now[it].orEmpty() }.toSet()
    }

    /**
     * «Мои упражнения» saved: [likes] per movement and [dislikes] kept and put into the plan. What changed for each place
     * (plan id → exercise in its place) and what was kept and why.
     */
    suspend fun setPicks(likes: Map<forma.core.model.Pattern, List<String>>, dislikes: Set<String>): Picks.Result {
        val program = store.programNow() ?: return Picks.Result(emptyMap(), emptyList())
        val profile = store.profileNow() ?: return Picks.Result(emptyMap(), emptyList())
        var result = Picks.Result(emptyMap(), emptyList())
        store.editSettings { s ->
            val kept = s.copy(likes = likes.filterValues { it.isNotEmpty() }.mapKeys { it.key.name }, dislikes = dislikes)
            withPicks(kept, program, profile, changed(s, likes)).also { result = it.second }.first
        }
        onChange()
        return result
    }

    /** What resolving the picks would do now, without saving (the screen's preview). */
    suspend fun previewPicks(likes: Map<forma.core.model.Pattern, List<String>>, dislikes: Set<String>): Picks.Result {
        val program = store.programNow() ?: return Picks.Result(emptyMap(), emptyList())
        val profile = store.profileNow() ?: return Picks.Result(emptyMap(), emptyList())
        val was = store.settingsNow()
        val s = was.copy(likes = likes.filterValues { it.isNotEmpty() }.mapKeys { it.key.name }, dislikes = dislikes)
        return withPicks(s, program, profile, changed(was, likes)).second
    }

    /** A movement of the plan for «Мои упражнения»: its places now as (session title, exercise done there). */
    data class PickGroup(val pattern: forma.core.model.Pattern, val now: List<Pair<String, String>>)

    /** The plan's movements in plan order (places taken out of the plan left out) and the rules picks follow. */
    suspend fun pickGroups(): Pair<List<PickGroup>, Picks.Rules>? {
        val program = store.programNow() ?: return null
        val profile = store.profileNow() ?: return null
        val s = store.settingsNow()
        val groups = linkedMapOf<forma.core.model.Pattern, MutableList<Pair<String, String>>>()
        for (t in program.templates) for (slot in t.slots) {
            if (slot.exerciseId in s.removed) continue
            val pattern = Catalog.find(slot.exerciseId)?.pattern ?: continue
            groups.getOrPut(pattern) { mutableListOf() } += t.title to (swapOf(slot, s) ?: slot.exerciseId)
        }
        return groups.map { (k, v) -> PickGroup(k, v) } to Picks.Rules.of(profile, s.removed)
    }

    /**
     * The kit changed (gym → home and back): the person's own choices kept under plan ids follow the place to the exercise
     * that stands in it now — taken out stays out (Denis's calves), own sets, reps and rest stay; a replacement whose
     * exercise cannot be done there goes.
     */
    private suspend fun moveKit(old: Profile, now: Profile) {
        val moves = runCatching { ProgramGenerator.kitMoves(old, now) }.getOrNull() ?: return
        fun to(k: String): Set<String> = moves[k] ?: setOf(k)
        fun <V> map(m: Map<String, V>): Map<String, V> = m.flatMap { (k, v) -> to(k).map { it to v } }.toMap()
        store.editSettings { s ->
            s.copy(
                removed = s.removed.flatMap(::to).toSet(),
                sets = map(s.sets), reps = map(s.reps), rest = map(s.rest),
                swaps = map(s.swaps).filterValues { Catalog.find(it)?.let { ex -> now.gear.fits(ex, now.pullupBar) } == true },
                pickSwaps = s.pickSwaps.flatMap(::to).toSet(),
                demoted = s.demoted.flatMap(::to).toSet(),
            )
        }
    }

    /** The plan laid out again (new places, other ids): the picks put in again, without saying anything. */
    private suspend fun reapplyPicks() {
        val s = store.settingsNow()
        if (s.likes.isEmpty() && s.dislikes.isEmpty()) return
        val program = store.programNow() ?: return
        val profile = store.profileNow() ?: return
        store.editSettings { withPicks(it, program, profile).first }
    }

    /** Sets of [planId] in every session from now on; null (or the plan's own number) brings the plan's back. */
    suspend fun setPlanSets(planId: String, sets: Int?, planned: Int) {
        store.editSettings { withSets(it, planId, sets, planned) }
        onChange()
    }

    /** The weight step of [exerciseId] in this gym from now on; its own step ([default]) clears the override. */
    suspend fun setStep(exerciseId: String, step: Double, default: Double) {
        store.editSettings { s -> s.copy(steps = if (step == default) s.steps - exerciseId else s.steps + (exerciseId to step)) }
        restepActive(exerciseId)
        onChange()
    }

    /**
     * The step of [exerciseId] changed during a session: its places there without sets yet start on the new step too
     * (the weight down to it), as the next sessions will — not only from the next one.
     */
    private suspend fun restepActive(exerciseId: String) {
        val w = db.workouts().active()?.takeIf { it.kind == Kind.LIFT } ?: return
        val plan = planOf(w) ?: return
        val started = db.sets().forWorkout(w.id).map { it.position }.toSet()
        val ex = exercise(exerciseId, store.settingsNow())
        val items = plan.items.map { i ->
            if (i.exerciseId != exerciseId || i.position in started || i.prescription.phase == Phase.TEST) i
            else i.copy(step = ex.step, prescription = i.prescription.weight?.let { wt ->
                val st = ex.stepAt(wt)
                i.prescription.copy(weight = (kotlin.math.floor(wt / st + 1e-9) * st).coerceAtLeast(Progression.minWeight(ex, store.profileNow()?.bodyWeightKg ?: 75.0)))
            } ?: i.prescription)
        }
        if (items != plan.items) replacePlan(w.id, plan.copy(items = items))
    }

    /** Takes [planId] out of every session from now on, or brings it back. */
    suspend fun setRemoved(planId: String, removed: Boolean) {
        store.editSettings { s -> s.copy(removed = if (removed) s.removed + planId else s.removed - planId) }
        // A place out or back: the picks of now, not those of when it went (a cleared pick left in it goes).
        reapplyPicks()
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
        store.editSettings { s ->
            // The plan's exercise comes back with its own numbers, not those set for the archived one in its place.
            val back = s.swaps.filterValues { it == id }.keys intersect s.demoted
            s.copy(swaps = s.swaps.filterValues { it != id }, demoted = s.demoted - back, sets = s.sets - back, reps = s.reps - back, rest = s.rest - back)
        }
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
        store.clearCoachReview(id)
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
        // Help the person chose is over once the progress reaches the body alone.
        val chosen = store.settingsNow().chosenHelp.filter { (db.state().slot(it)?.weight ?: -1.0) >= 0 }
        if (chosen.isNotEmpty()) store.editSettings { it.copy(chosenHelp = it.chosenHelp - chosen.toSet()) }
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
        // A session left open (finished in the evening, or after a run at the stadium) ends with its last set.
        val end = liftEnd(w.startedAt, sets.maxOfOrNull { it.doneAt }, now())
        val minutes = liftMinutes(w.startedAt, sets.map { it.doneAt }, now())
        val load = TrainingLoad.sessionLoad(rpe, minutes.toDouble())
        val summary = LiftSummary(reports, volume, sets.size, minutes, load)
        db.workouts().update(
            w.copy(
                status = Status.DONE, finishedAt = end, rpe = rpe, durationSec = minutes * 60, load = load,
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
        // A lighter week just behind (last week) is recovery enough. A longer window blocked it in every week of a
        // 4–5-week block: the reactive deload never came for intermediate and advanced lifters.
        val recent = state.reactiveDeloadStart?.let { day.toEpochDay() < it + 42 } == true ||
            ProgramGenerator.isDeloadWeek(program, day.minusWeeks(1))
        // Run down by this person's own measure: below their rest line for four days or more (the dip still on).
        val worn = storyOf(db.workouts().allDone(), day)?.let { st ->
            st.history && st.dips.lastOrNull()?.let { it.recovered == null && day.toEpochDay() - it.from.toEpochDay() >= 3 } == true
        } == true
        if ((setbacks.size >= 2 || worn && setbacks.isNotEmpty()) && !plannedSoon && !recent) {
            store.setLiftState(state.copy(reactiveDeloadStart = nextMonday.toEpochDay()))
            // The lighter week becomes the block's last one and a new block follows it: the planned one does not come a
            // week or two later, the weeks keep their count (no week 1 three times), the runs are lighter that week too.
            // Without blocks (novices) the count of lighter running weeks stays as it is.
            if (program.blockWeeks > 0) store.setProgram(ProgramGenerator.lighterWeekAt(program, nextMonday))
        }
    }

    // ---------- from friends ----------

    /** A friend's own exercises that this phone does not know yet, so their workouts and programs can be copied. */
    suspend fun importExercises(list: List<Exercise>) {
        val known = store.customNow().map { it.id }.toSet()
        val missing = list.filter { it.custom && it.id !in known && Catalog.find(it.id) == null }
        if (missing.isEmpty()) return
        store.setCustom(store.customNow() + missing.map { it.copy(archived = false, name = oneLine(it.name).ifBlank { "Упражнение" }) })
        refreshCustom()
        onChange()
    }

    /** Most exercises a copied session takes. */
    private val copiedMax = 12

    /** The exercises of a friend's session that this phone can do, in order, at most [copiedMax]. */
    private fun copiable(plan: LiftPlan): List<SlotSpec> =
        plan.items.map { it.slot }.filter { Catalog.find(it.exerciseId)?.archived == false }.take(copiedMax)

    /**
     * What a copy of a friend's session leaves out, by name: exercises this phone does not know and those beyond
     * [copiedMax]. Asked after [startCopiedLift] (the friend's own exercises are known by then).
     */
    fun copiedLeftOut(plan: LiftPlan): List<String> {
        val taken = copiable(plan).map { it.exerciseId }.toMutableList()
        return plan.items.filter { item -> !taken.remove(item.exerciseId) }.map { it.name }
    }

    /**
     * Starts a session with a friend's exercises, sets and rep ranges; the weights are this user's own (progress,
     * estimate or the first-time search). Exercises this phone cannot know are left out ([copiedLeftOut] names them).
     * In this user's lighter week — or when the friend's session was a lighter one — it is done as a lighter session:
     * fewer sets, lighter weights. Of the friend's own exercises only those in the session are taken over. Null while
     * another session runs.
     */
    suspend fun startCopiedLift(plan: LiftPlan, from: String, exercises: List<Exercise> = emptyList(), date: LocalDate = today()): Long? = sessionLock.withLock {
        if (db.workouts().active() != null) return@withLock null
        val used = plan.items.map { it.exerciseId }.toSet()
        importExercises(exercises.filter { it.id in used })
        val specs = copiable(plan)
            .map { it.copy(sets = it.sets.coerceIn(1, 10), repMin = it.repMin.coerceIn(1, 100), repMax = it.repMax.coerceIn(it.repMin.coerceIn(1, 100), 100), rir = it.rir.coerceIn(0, 5), restSec = it.restSec.coerceIn(0, 600)) }
        if (specs.isEmpty()) return@withLock null
        val deload = plan.deload || deloadOn(date, false)
        val items = specs.mapIndexed { i, spec -> buildItem(i, spec.copy(increment = null), date, deload) }
        val copy = LiftPlan(COPIED_TEMPLATE, copiedTitle(plan.title, from), items, deload = deload)
        db.workouts().insert(
            WorkoutEntity(
                epochDay = date.toEpochDay(), kind = Kind.LIFT, templateId = COPIED_TEMPLATE, title = copy.title,
                startedAt = now(), status = Status.ACTIVE, deload = deload, planJson = AppJson.encodeToString(LiftPlan.serializer(), copy),
            ),
        )
    }

    /**
     * Takes a friend's program as one's own: the same days, sessions and exercises; this user's weights, block length
     * and progress per exercise. The profile follows (aim and days). The own program before it is put aside with its
     * edits: [restoreOwnProgram] brings it back. Of the friend's own exercises only those in the program are taken over.
     */
    suspend fun copyProgram(program: Program, from: String, exercises: List<Exercise> = emptyList(), part: CopyPart = CopyPart.ALL): Boolean {
        val profile = store.profileNow() ?: return false
        // As the friend does it (their swaps, sets... put in on their phone) when their phone sent that; whatever it stored,
        // only a program that works here is taken.
        val whole = program.copy(templates = program.shared ?: program.templates, shared = null)
        // Only the gym or only running: the other side is gone from the week and from the aim — not one's own kept.
        val asDone = when (part) {
            CopyPart.ALL -> whole
            CopyPart.GYM -> whole.copy(
                week = whole.week.copy(days = whole.week.days.map { d -> d.copy(sessions = d.sessions.filter { it.kind == SessionKind.LIFT }) }),
                aim = forma.core.model.Aim(whole.aim.lift.takeIf { it != forma.core.model.LiftFocus.NONE } ?: forma.core.model.LiftFocus.GENERAL, forma.core.model.RunFocus.NONE, forma.core.model.Emphasis.GYM),
            )
            CopyPart.RUN -> whole.copy(
                week = whole.week.copy(days = whole.week.days.map { d -> d.copy(sessions = d.sessions.filter { it.isRun }) }),
                aim = forma.core.model.Aim(forma.core.model.LiftFocus.NONE, whole.aim.run.takeIf { it != forma.core.model.RunFocus.NONE } ?: forma.core.model.RunFocus.HEALTH, forma.core.model.Emphasis.RUN),
                templates = emptyList(),
            )
        }.let { if (part == CopyPart.ALL) it else it.copy(goal = it.aim.nearestGoal()) }
        if (asDone.week.days.none { it.sessions.isNotEmpty() }) return false
        val used = asDone.templates.flatMap { t -> t.slots.map { it.exerciseId } }.toSet()
        importExercises(exercises.filter { it.id in used })
        val safe = asDone.checked { id -> Catalog.find(id)?.archived == false }
            ?.let { p -> p.copy(templates = p.templates.map { t -> t.copy(title = oneLine(t.title).ifBlank { "Тренировка" }) }) } ?: return false
        val days = safe.week.days.filter { it.sessions.isNotEmpty() }.map { it.day }.toSet().ifEmpty { profile.trainingDays }
        // The friend's week is the week now: the own days chosen before are not (the plan line and the grid show these).
        val updated = profile.copy(
            aim = safe.aim, goal = safe.aim.nearestGoal(), trainingDays = days, gymDays = null, runDays = null,
            doubleDays = profile.doubleDays && part == CopyPart.ALL,
        )
        val own = ProgramGenerator.generate(updated, today())
        val before = store.programNow()
        val settings = store.settingsNow()
        // One's own program is put aside once: copying from a second friend keeps the own one, not the first friend's.
        val keep = settings.ownPlan ?: before?.takeIf { it.copiedFrom == null }?.let { OwnPlan.of(profile, it, settings) }
        store.setProfile(updated)
        store.setProgram(
            safe.copy(
                experience = profile.experience, blockWeeks = own.blockWeeks, startEpochDay = own.startEpochDay,
                createdEpochDay = today().toEpochDay(), copiedFrom = from.take(40), runCaps = own.runCaps,
                // The friend's notes are about their whole week: with one side taken, the notes of a week like this one.
                week = if (part == CopyPart.ALL) safe.week else safe.week.copy(notes = own.week.notes),
            ),
        )
        // The own edits of the plan before belong to that plan (kept with it): the friend's exercises are taken as they are.
        updateSettings { it.copy(swaps = emptyMap(), removed = emptySet(), added = emptyMap(), sets = emptyMap(), reps = emptyMap(), rest = emptyMap(), demoted = emptySet(), pickSwaps = emptySet(), ownPlan = keep) }
        onChange()
        return true
    }

    // ---------- daily life: pause, missed sessions, energy ----------

    /** Pause windows stay in the settings: their days are never counted as missed. */
    fun paused(s: Settings, day: LocalDate): Boolean = s.pausedOn(day)

    private fun mondayOf(day: LocalDate): LocalDate = day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

    /**
     * Vacation or illness from [from] to [until]: no sessions are expected. A week or more away is rest enough, so the
     * lifting block starts anew in the week of return instead of putting a lighter week right after it. The first
     * sessions back are lighter by themselves (layoff and comeback rules).
     */
    suspend fun pause(from: LocalDate, until: LocalDate) {
        store.editSettings { it.withPause(from.toEpochDay(), until.toEpochDay()) }
        if (until.toEpochDay() - from.toEpochDay() + 1 >= 7) {
            restartBlock(until.plusDays(1))
            // A week or more away is rest enough: a lighter week the app set before it is not needed any more.
            val lift = store.liftStateNow()
            if (lift.reactiveDeloadStart?.let { it + 7 > from.toEpochDay() } == true) store.setLiftState(lift.copy(reactiveDeloadStart = null))
        }
        onChange()
    }

    /** Back earlier than planned: the pause ends yesterday. */
    suspend fun resume(day: LocalDate = today()) {
        val s = store.settingsNow()
        val from = s.pauseFrom ?: return
        if (!paused(s, day)) return
        store.editSettings { it.copy(pauseUntil = day.toEpochDay() - 1) }
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
    private fun asSession(w: WorkoutEntity): PlannedSession = sessionOf(w)

    /** The catch-up's week of [monday]: planned (none on pause days) and done, a copied workout's legs by its exercises. */
    private fun catchUpWeek(program: Program?, settings: Settings, monday: LocalDate, done: List<WorkoutEntity>): List<CatchUp.Day> =
        (1..7).map { d ->
            val date = monday.plusDays(d - 1L)
            val pause = paused(settings, date)
            val planned = if (program == null || pause) emptyList() else program.week.on(d)
            val mine = done.filter { it.epochDay == date.toEpochDay() }.sortedBy { it.startedAt }
            CatchUp.Day(d, planned, mine.map(::asSession), mine.map(::legsOf), paused = pause)
        }

    /** The first day of [monday]'s week the plan held (catch-up ignores what was planned before it). */
    private fun firstDay(program: Program?, monday: LocalDate): Int =
        program?.createdEpochDay?.let { (it - monday.toEpochDay() + 1).toInt().coerceIn(1, 8) } ?: 1

    /** A session missed earlier this week that fits [day] — never while paused, never from before the plan existed. */
    suspend fun catchUp(day: LocalDate = today()): CatchUp.Suggestion? {
        val program = store.programNow() ?: return null
        val settings = store.settingsNow()
        if (paused(settings, day)) return null
        val monday = mondayOf(day)
        val done = db.workouts().allDone().filter { it.epochDay in monday.toEpochDay()..monday.toEpochDay() + 6 }
        return CatchUp.suggest(program, day.dayOfWeek.value, catchUpWeek(program, settings, monday, done), firstDay(program, monday))
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
    suspend fun runFor(kind: SessionKind, date: LocalDate = today(), light: Boolean = false, road: Boolean = false, trial: Boolean? = null): RunWorkout {
        // A due trial goes on its own day ([trialDay]): not on a hard day too soon after the legs.
        @Suppress("NAME_SHADOWING") val trial = trial ?: (kind != SessionKind.RUN_QUALITY || trialDay(date) == date)
        val saved = store.runStateNow()
        val program = store.programNow()
        // The caps of the plan as it is now: after a switch to less running, runs are no longer than it allows.
        val caps = capsOn(date)
        val st = RunPlan.withBreak(saved, date.toEpochDay()).let {
            if (caps == null) it else it.copy(easyMinutes = minOf(it.easyMinutes, caps.easyMax), longMinutes = minOf(it.longMinutes, caps.longMax))
        }.let { s -> RunPlan.weekCap(saved, date.toEpochDay())?.let { cap -> s.copy(easyMinutes = minOf(s.easyMinutes, cap), longMinutes = minOf(s.longMinutes, cap)) } ?: s }
        val profile = store.profileNow()
        val goal = profile?.runGoal
        val lap = profile?.lapMeters?.takeIf { !road }
        val cutback = program != null && ProgramGenerator.isRunCutbackWeek(program, date)
        // Before the first test of a race goal, easy and long runs get a pace limit from the goal (approximate).
        val guess = if (st.vdot == null && goal != null) st.copy(vdot = RunPlan.provisionalVdot(goal)) else null
        // The goal race ahead: two weeks lighter, then a short race-pace session, then easy running; the day is the race.
        val toRace = daysToRace(date)
        if (toRace == 0L && goal != null && st.canRunContinuously) return RunPlan.race(st, goal)
        val taper = toRace != null && toRace in 1..14
        val lighter = cutback || taper
        val settings = store.settingsNow()
        val coachLong = settings.coachLong?.on(date) == true
        val longEasy = kind == SessionKind.RUN_EASY && program != null && longEasyDay(program, date, goal?.middleDistance == true, coachLong)
        // Back from being sick: the first runs easy, no reps and no strides, whatever the day holds.
        val sickLeft = easyAfterSick(date)
        val sickNote = "После болезни начинаем мягко: ещё $sickLeft ${Ru.plural(sickLeft, "лёгкая пробежка", "лёгкие пробежки", "лёгких пробежек")}, " +
            "без отрезков и ускорений. Беги так, чтобы мог говорить. Станет хуже — остановись и отметь «Болею»: здоровье важнее плана. "
        val w = if (!st.canRunContinuously) RunPlan.runWalk(st) else when {
            toRace != null && toRace in 1..2 -> RunPlan.easy(st.copy(easyMinutes = minOf(st.easyMinutes, 20)), strides = 4).let {
                it.copy(note = "До забега ${toRace} ${Ru.plural(toRace.toInt(), "день", "дня", "дней")}: только размяться, без усталости. " + it.note)
            }
            toRace != null && toRace in 3..5 && kind == SessionKind.RUN_QUALITY -> (
                if (goal?.middleDistance == true) RunPlan.goalPace(st, goal, lap, cutback = true) else RunPlan.quality(st, cutback = true, goal = goal, lapMeters = lap)
            ).let { it.copy(note = "Последняя тяжёлая перед забегом. Она короткая: освежить темп и не устать. " + it.note) }
            toRace != null && toRace in 3..7 && kind != SessionKind.RUN_QUALITY ->
                RunPlan.easy(st.copy(easyMinutes = (minOf(st.easyMinutes, st.longMinutes) * 0.7).toInt().coerceAtLeast(20)), strides = 4)
            sickLeft > 0 -> RunPlan.easy(guess ?: st, lighter, date.toEpochDay())
                .let { if (guess != null) RunPlan.provisional(it) else it }.let { it.copy(note = sickNote + it.note) }
            kind == SessionKind.RUN_LONG || longEasy ->
                if (guess != null) RunPlan.provisional(RunPlan.long(guess, lighter, date.toEpochDay())) else RunPlan.long(st, lighter, date.toEpochDay())
            // The first run after more than a week without running: no hard session cold, nor a due trial — easy today, the
            // hard ones from the next hard day (the trial there, [trialDay]). Whether a trial was due or not: two people
            // back from the same break got an easy run and race-pace reps.
            kind == SessionKind.RUN_QUALITY && RunPlan.breakDays(saved, date.toEpochDay()) != null ->
                RunPlan.easy(guess ?: st, lighter, date.toEpochDay()).let { if (guess != null) RunPlan.provisional(it) else it }.let {
                    it.copy(
                        note = (
                            if (needsTest(st, date, goal)) "После перерыва не начинаем с контрольного. Сегодня лёгкий бег, чтобы втянуться, а контрольный — в следующий тяжёлый день. "
                            else "После перерыва не начинаем с тяжёлой пробежки. Сегодня лёгкий бег, чтобы втянуться, а тяжёлые — со следующего тяжёлого дня. "
                            ) + it.note,
                    )
                }
            kind == SessionKind.RUN_QUALITY -> when {
                !trial || !needsTest(st, date, goal) -> RunPlan.quality(st, lighter, goal, lap, goalLater = goalLater(program, date))
                goal?.middleDistance == true -> RunPlan.goalTest(st, goal, lap)
                else -> RunPlan.test(st)
            }
            else -> {
                // On a lifting day the easy run comes after the gym: short, unless the plan was built for shared days.
                val liftDay = program?.week?.on(date.dayOfWeek.value)?.any { it.kind == SessionKind.LIFT } == true
                val short = liftDay && profile?.doubleDays != true
                val base = guess ?: st
                // The coach's count, where the plan has strides (no more than 4 in a lighter week).
                val own = settings.coachStrides?.takeIf { it.on(date) }?.value?.let { if (lighter) minOf(it, 4) else it }
                val strides = if (program != null && stridesOn(program, date, goal?.middleDistance == true, coachLong) && st.returnEasy == 0) own ?: RunPlan.strides(st, lighter) else 0
                RunPlan.easy(if (short) base.copy(easyMinutes = minOf(base.easyMinutes, 30)) else base, lighter, date.toEpochDay(), strides)
                    .let { if (guess != null) RunPlan.provisional(it) else it }
            }
        }
        fun track(x: RunWorkout) = if (lap != null) RunPlan.onTrack(x, lap) else x
        val planned = track(if (light) RunPlan.lighter(st, track(w)) else w)
        val comeback = RunPlan.comebackNote(saved, date.toEpochDay()) ?: return planned
        return planned.copy(note = "$comeback ${planned.note}")
    }

    /**
     * «Идеальный план» ([forma.core.plan.PlanAdvisor]): the gym plan as it is done now (swaps, sets, exercises taken out
     * and added, all as on the plan screen) weighed with the person's focus, experience, running, session time and own
     * pace, lifts without a new best, how tired they have been, a race coming. Null without a program.
     */
    suspend fun planAdvice(): forma.core.plan.PlanAdvisor.Advice? =
        // Many reads of the sessions in a row: off the main thread, which then waits for none of them.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { adviceNow() }

    private suspend fun adviceNow(): forma.core.plan.PlanAdvisor.Advice? {
        val program = store.programNow() ?: return null
        val profile = store.profileNow() ?: return null
        val settings = store.settingsNow()
        val today = today()
        val sessions = program.week.days.flatMap { it.sessions }
        val days = adviceDays(program, settings)
        // Tired often: "so-so" on 3 days of the last three weeks, below the rest line now, or a lighter week the app set lately.
        val lows = (1..21).count { settings.feelOn(today.minusDays(it.toLong())) == Feel.LOW }
        val zone = loadStoryNow(today)?.takeIf { it.history }?.zone
        val lighter = store.liftStateNow().reactiveDeloadStart?.let { today.toEpochDay() - it in 0..28 } == true
        return forma.core.plan.PlanAdvisor.advise(
            forma.core.plan.PlanAdvisor.Input(
                days, program.aim, program.experience,
                runs = sessions.count { it.isRun }, sessionMinutes = profile.sessionMinutes, timeRatio = timeRatio(),
                stalled = weeksWithoutBest(today), tiredOften = lows >= 3 || zone == forma.core.load.LoadStory.Zone.REST || lighter,
                daysToRace = daysToRace(today), habits = habitsOf(program, settings, today), find = Catalog::find,
            ),
        )
    }

    /**
     * What the person does ([forma.core.plan.PlanAdvisor.Habits]), from the plan edits and the sessions of the last six
     * weeks. An exercise counts from the first session it was in its place as it is now: a session without it there
     * (taken out or swapped that day) or with no set of it is skipped; sets are compared with the session's own number
     * (a lighter day or week asks fewer). Skipped in half the sessions or more (2+ of 3+) — avoided; done with fewer sets
     * than planned in most full sessions (3+) — the sets usually done. A hard unusual exercise done in 3+ sessions — liked.
     */
    private suspend fun habitsOf(program: Program, settings: Settings, today: LocalDate): forma.core.plan.PlanAdvisor.Habits {
        val avoided = mutableMapOf<String, String>()
        settings.removed.forEach { avoided[it] = "ты убрал из плана" }
        settings.dislikes.forEach { avoided.putIfAbsent(it, "ты отметил «не хочу»") }
        // Only what can be done where the person trains (at home: no machines, no barbell).
        store.profileNow()?.takeIf { it.gear != forma.core.model.Gear.GYM }?.let { p ->
            Catalog.all.filter { !p.gear.fits(it, p.pullupBar) }.forEach { avoided.putIfAbsent(it.id, "нечем делать: ${p.gear.ru.lowercase()}") }
        }
        val ws = db.workouts().allDone().filter { it.kind == Kind.LIFT && it.epochDay in today.toEpochDay() - 42..today.toEpochDay() }
        val setsOf = mutableMapOf<Long, List<SetEntity>>()
        val skipped = mutableMapOf<String, Pair<Int, Int>>()
        val doneSets = mutableMapOf<String, Int>()
        var likesHard = false
        for (t in program.templates) {
            val sessions = ws.filter { it.templateId == t.id }.mapNotNull { w -> planOf(w)?.let { w to it } }
            for (i in adviceItems(t, settings)) {
                var seen = false
                var n = 0
                var skip = 0
                val done = mutableListOf<Int>()
                for ((w, plan) in sessions) {
                    val at = plan.items.firstOrNull { (it.planId ?: it.exerciseId) == i.planId && it.addedForGood == i.added }
                    val same = at != null && at.exerciseId == i.exerciseId
                    if (!seen && !same) continue
                    seen = true
                    n++
                    if (at == null || !same) { skip++; continue }
                    val sets = setsOf.getOrPut(w.id) { db.sets().forWorkout(w.id) }.count { it.position == at.position }
                    if (sets == 0) skip++ else if (at.prescription.sets == i.sets) done += sets
                }
                val k = forma.core.plan.PlanAdvisor.key(t.id, i)
                if (n >= 3 && skip >= 2 && skip * 2 >= n) {
                    skipped[k] = skip to n
                    avoided[i.exerciseId] = "ты пропускаешь в $skip из $n тренировок"
                } else if (done.size >= 3) {
                    val usual = done.sorted()[done.size / 2]
                    if (usual < i.sets) doneSets[k] = usual
                }
                if (i.exerciseId in forma.core.plan.PlanAdvisor.HARD && n - skip >= 3) likesHard = true
            }
        }
        // Nordic curls said not to propose: a sign against hard unusual exercises too.
        if ("add:nordic" in settings.adviceHidden) avoided.putIfAbsent("nordic_curl", "ты просил не предлагать")
        val name = { id: String -> Catalog.find(id)?.name ?: id }
        val avoidsHard = avoided.entries.firstOrNull { it.key in forma.core.plan.PlanAdvisor.HARD }?.let { "«${name(it.key)}» ${it.value}" }
        return forma.core.plan.PlanAdvisor.Habits(
            avoided = avoided, doneSets = doneSets, skipped = skipped, avoidsHard = avoidsHard,
            likesHard = likesHard && avoidsHard == null, hidden = settings.adviceHidden,
        )
    }

    /** «Не предлагать» on a «Идеальный план» change: it is not proposed again (until shown again). */
    suspend fun hideAdvice(id: String) {
        store.editSettings { it.copy(adviceHidden = it.adviceHidden + id) }
        onChange()
    }

    /** The changes said not to propose come back. */
    suspend fun showHiddenAdvice() {
        store.editSettings { it.copy(adviceHidden = emptySet()) }
        onChange()
    }

    /** The lifting days of the week as [settings] make them, each with how many times a week it comes. */
    private fun adviceDays(program: Program, settings: Settings): List<forma.core.plan.PlanAdvisor.Day> {
        val times = program.week.days.flatMap { it.sessions }.filter { it.kind == SessionKind.LIFT }.groupingBy { it.templateId }.eachCount()
        return program.templates.filter { (times[it.id] ?: 0) > 0 }.map { t -> forma.core.plan.PlanAdvisor.Day(t.id, t.title, adviceItems(t, settings), times.getValue(t.id)) }
    }

    /** The exercises of [t] as every session has them now, with the numbers set for good (no lighter week, no progress). */
    private fun adviceItems(t: forma.core.plan.Template, settings: Settings): List<forma.core.plan.PlanAdvisor.Item> {
        val added = addedTo(t.id, settings)
        val kept = t.slots.filter { it.exerciseId !in settings.removed }.ifEmpty { if (added.isEmpty()) t.slots else emptyList() }
        val own = kept.map { spec ->
            val s = tuned(spec, settings)
            forma.core.plan.PlanAdvisor.Item(s.exerciseId, spec.exerciseId, s.sets, s.repMin, s.repMax, s.restSec, s.role)
        }
        val extra = added.map { id ->
            val s = overrides(addedSpec(id), settings, addedKey(t.id, id))
            forma.core.plan.PlanAdvisor.Item(id, id, s.sets, s.repMin, s.repMax, s.restSec, s.role, added = true)
        }
        return own + extra
    }

    /**
     * Exercise → weeks from its last new best to its last session, for exercises with weights done 5+ times over 5+ weeks
     * of the last 12 and done in the last two (a lift left long ago or just begun says nothing yet). The best is of the
     * median of three sessions in a row: one session rated too light or too heavy neither makes nor hides a stall; first
     * sessions (the weight found) do not count; body-weight lifts are left out (the body's own weight moves their estimate).
     */
    private suspend fun weeksWithoutBest(today: LocalDate): Map<String, Int> {
        val from = today.toEpochDay() - 84
        val by = mutableMapOf<String, MutableList<Pair<Long, Double>>>()
        for (w in db.workouts().allDone().filter { it.kind == Kind.LIFT && it.epochDay >= from }) {
            val s = liftSummaryOf(w) ?: continue
            for (r in s.reports) {
                if (r.outcome is Outcome.Calibrated || Catalog.find(r.exerciseId)?.load != LoadKind.EXTERNAL) continue
                r.e1rm?.let { by.getOrPut(r.exerciseId) { mutableListOf() } += w.epochDay to it }
            }
        }
        return by.mapNotNull { (id, xs) ->
            val sorted = xs.sortedBy { it.first }
            val last = sorted.last().first
            if (sorted.size < 5 || last - sorted.first().first < 35 || today.toEpochDay() - last > 14) return@mapNotNull null
            var best = 0.0
            var bestDay = sorted[2].first
            for (k in 2 until sorted.size) {
                val m = listOf(sorted[k - 2].second, sorted[k - 1].second, sorted[k].second).sorted()[1]
                if (m > best * 1.005) { best = m; bestDay = sorted[k].first }
            }
            id to ((last - bestDay) / 7).toInt()
        }.toMap()
    }

    /**
     * Takes the changes of the «Идеальный план» the person ticked, each with the option they picked, through the same
     * edits as the plan screen (so each one is seen and undone there); nothing else changes.
     */
    suspend fun applyPlanAdvice(chosen: List<forma.core.plan.PlanAdvisor.Change>) {
        val program = store.programNow() ?: return
        if (chosen.isEmpty()) return
        store.editSettings { advised(it, program, chosen) }
        onChange()
    }

    /**
     * The gym week as it would be with [chosen] taken — built by the same edits and the same reading of the plan as
     * [applyPlanAdvice] and [planAdvice], so what «С выбранным» shows is what taking them does.
     */
    suspend fun planPreview(chosen: List<forma.core.plan.PlanAdvisor.Change>): List<forma.core.plan.PlanAdvisor.Day> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val program = store.programNow() ?: return@withContext emptyList()
            adviceDays(program, advised(store.settingsNow(), program, chosen))
        }

    /**
     * A day with the gym and a run: which goes first and why ([forma.core.plan.SessionOrder]) — from the run as it stands
     * that day, what the gym session does to the legs exercise by exercise (swaps and all), the person's freshness and
     * feel today, the legs of the last two days, a lighter gym session. Null on a day without both (or a pause day).
     */
    suspend fun dayOrder(date: LocalDate, light: Boolean = false): DayOrder? {
        val program = store.programNow() ?: return null
        val settings = store.settingsNow()
        if (paused(settings, date)) return null
        val sessions = program.week.on(date.dayOfWeek.value)
        val run = sessions.firstOrNull { it.isRun } ?: return null
        val lift = sessions.firstOrNull { it.kind == SessionKind.LIFT && it.templateId != null } ?: return null
        val templateId = lift.templateId ?: return null
        val feelLow = settings.feelOn(date) == Feel.LOW
        val w = runFor(run.kind, date, light || feelLow)
        val kind = when (w.type) {
            RunType.TEST -> if (daysToRace(date) == 0L) forma.core.plan.SessionOrder.Run.RACE else forma.core.plan.SessionOrder.Run.TRIAL
            RunType.GOAL_PACE, RunType.INTERVALS, RunType.TEMPO -> forma.core.plan.SessionOrder.Run.HARD
            RunType.LONG -> forma.core.plan.SessionOrder.Run.LONG
            else -> forma.core.plan.SessionOrder.Run.EASY
        }
        val plan = liftPlan(templateId, date)
        val legMuscles = setOf(forma.core.model.Muscle.QUADS, forma.core.model.Muscle.HAMSTRINGS, forma.core.model.Muscle.GLUTES, forma.core.model.Muscle.CALVES)
        val legItems = plan.items.filter { item -> exercise(item.exerciseId, settings).let { ex -> ex.lowerBody || ex.primary.any { it in legMuscles } } }
        val heavy = legItems.filter { it.slot.role != SlotRole.ACCESSORY && exercise(it.exerciseId, settings).compound }
        val legs = when {
            heavy.isNotEmpty() -> forma.core.plan.SessionOrder.Legs.HEAVY
            legItems.isNotEmpty() -> forma.core.plan.SessionOrder.Legs.LIGHT
            else -> forma.core.plan.SessionOrder.Legs.NONE
        }
        // Now: today's freshness against the person's own rest line, and the feel they said.
        val fatigue = if (date == today()) {
            val zone = loadStoryNow(date)?.takeIf { it.history }?.zone
            when {
                feelLow || zone == forma.core.load.LoadStory.Zone.REST -> forma.core.plan.SessionOrder.Fatigue.TIRED
                zone == forma.core.load.LoadStory.Zone.FRESH -> forma.core.plan.SessionOrder.Fatigue.FRESH
                else -> forma.core.plan.SessionOrder.Fatigue.NORMAL
            }
        } else forma.core.plan.SessionOrder.Fatigue.NORMAL
        // The legs worked in the last two days, as done (a friend's copied workout by its exercises).
        val done = db.workouts().allDone().filter { it.kind == Kind.LIFT && it.epochDay in date.toEpochDay() - 2 until date.toEpochDay() }
        val legsFrom = done.filter { w0 -> legsOf(w0) ?: program.templates.firstOrNull { it.id == w0.templateId }?.legs == true }
            .maxOfOrNull { it.epochDay }?.let { if (it == date.toEpochDay() - 1) "вчера" else "позавчера" }
        val day = forma.core.plan.SessionOrder.Day(
            kind, legs, program.aim.normalized().emphasis, fatigue,
            legNames = heavy.ifEmpty { legItems }.map { it.name.replaceFirstChar { c -> c.lowercase() } },
            legsHeavyFrom = legsFrom, gymLight = plan.deload || light || feelLow,
        )
        return DayOrder(run, lift, day, forma.core.plan.SessionOrder.advise(day))
    }

    /** Days from [date] to the goal race (0 on its day), null without a race date or after it. */
    private suspend fun daysToRace(date: LocalDate): Long? =
        store.profileNow()?.runGoal?.date?.let { it - date.toEpochDay() }?.takeIf { it >= 0 }

    /** Easy days of the plan's week, in order. */
    private fun easyDays(program: Program) = program.week.days.sortedBy { it.day }.filter { d -> d.sessions.any { it.kind == SessionKind.RUN_EASY } }.map { it.day }

    /**
     * A middle-distance week with two easy runs or more and no long run: its last easy run (not on a leg day when another
     * one is free of it) is a long easy run of up to an hour — the aerobic base the reps stand on. [coach]: the coach set a
     * long easy run ([Settings.coachLong]) — then any week with an easy run and no long one has it, on the same day.
     */
    private fun longEasyDay(program: Program, date: LocalDate, middle: Boolean, coach: Boolean = false): Boolean {
        if (!middle && !coach || program.week.days.any { d -> d.sessions.any { it.kind == SessionKind.RUN_LONG } }) return false
        val easy = easyDays(program)
        if (easy.isEmpty() || easy.size < 2 && !coach) return false
        val monday = date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val pick = easy.lastOrNull { !legsOn(program, monday.plusDays(it - 1L)) } ?: easy.last()
        return date.dayOfWeek.value == pick
    }

    /** Strides go with the first two easy runs of a week that has hard runs (not the long easy one). */
    private fun stridesOn(program: Program, date: LocalDate, middle: Boolean, coachLong: Boolean = false): Boolean {
        if (program.week.days.none { d -> d.sessions.any { it.kind == SessionKind.RUN_QUALITY } }) return false
        return date.dayOfWeek.value in easyDays(program).filter { !longEasyDay(program, date.with(java.time.DayOfWeek.of(it)), middle, coachLong) }.take(2)
    }

    /**
     * The caps of runs on [date] ([ProgramGenerator.runCaps]); on a day with a leg session an easy run is at most
     * [AFTER_LEGS_EASY] minutes: the one easy run of a middle-distance week grows to an hour, but not after squats two days
     * before a hard run.
     */
    private suspend fun capsOn(date: LocalDate): forma.core.run.RunCaps? {
        val program = store.programNow() ?: return null
        val middle = store.profileNow()?.runGoal?.middleDistance == true
        val caps = ProgramGenerator.runCaps(program, middle)
        val legs = legsOn(program, date)
        // The long easy run of a middle-distance week: up to an hour (after the legs, as an easy run after them).
        val coach = store.settingsNow().coachLong?.on(date) == true
        // The coach's long run on a day with the gym (any): no longer than an easy run after the legs.
        val gym = coach && program.week.on(date.dayOfWeek.value).any { it.kind == SessionKind.LIFT }
        val long = if (longEasyDay(program, date, middle, coach)) min(caps.longMax, if (legs || gym) AFTER_LEGS_EASY else 60) else caps.longMax
        return caps.copy(easyMax = if (legs) min(caps.easyMax, AFTER_LEGS_EASY) else caps.easyMax, longMax = long)
    }

    /** Whether the plan has a leg session on [date]. */
    private fun legsOn(program: Program, date: LocalDate) = program.week.on(date.dayOfWeek.value).any { s ->
        s.kind == SessionKind.LIFT && program.templates.firstOrNull { it.id == s.templateId }?.legs == true
    }

    /** A hard run planned on [date] or one or two days after it. */
    private fun hardRunSoon(program: Program, date: LocalDate) = (0L..2L).any { k ->
        program.week.on(date.plusDays(k).dayOfWeek.value).any { it.kind == SessionKind.RUN_QUALITY }
    }

    /** Legs worked one or two days before [date]: running economy is down for a day or two after strength work. */
    private fun legsBefore(program: Program, date: LocalDate) = (1L..2L).any { legsOn(program, date.minusDays(it)) }

    /**
     * Race pace is better on a fresher hard day: when the legs worked a day or two before [date] and another hard day of
     * the next four is fresh — and not the trial's — race pace due today goes there (the cycle's next session today).
     */
    private suspend fun goalLater(program: Program?, date: LocalDate): Boolean {
        if (program == null || !legsBefore(program, date)) return false
        // Owed for two weeks (the fresh days were missed): race pace now, tired legs or not.
        if (store.runStateNow().goalDeferredOn?.let { date.toEpochDay() - it >= 14 } == true) return false
        val settings = store.settingsNow()
        val fresh = (1L..4L).map { date.plusDays(it) }.firstOrNull { d ->
            program.week.on(d.dayOfWeek.value).any { it.kind == SessionKind.RUN_QUALITY } && !legsBefore(program, d) && !paused(settings, d)
        } ?: return false
        return trialDay(date) != fresh
    }

    /**
     * A test calibrates paces when they are unknown, and again every 8 weeks — every 5 with a race goal, and right after
     * the race-pace ladder is done (not while coming back from a break).
     */
    private fun needsTest(st: RunState, date: LocalDate, goal: forma.core.model.RunGoal?): Boolean {
        if (st.runs < 2 || st.returnEasy > 0) return false
        if (st.lastTrialTryDay?.let { date.toEpochDay() - it < 14 } == true) return false
        // The goal race within three weeks is the test: no trial before it.
        if (goal?.date?.let { it - date.toEpochDay() in 0..21 } == true) return false
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
        // Only a trial that is due: not before two runs, after a break, or within weeks of the last one.
        val st = store.runStateNow()
        if (!st.canRunContinuously) return null
        val goal = store.profileNow()?.runGoal
        val hard = (0 until 14).map { from.plusDays(it.toLong()) }.filter { d ->
            if (paused(settings, d) || !needsTest(st, d, goal)) return@filter false
            // Not within a week of being sick, nor while the runs after it are still easy.
            if (sickEnd(settings, d)?.let { d.toEpochDay() - it <= SICK_NO_TRIAL_DAYS } == true || easyAfterSick(d) > 0) return@filter false
            // Not the first run after more than a week without running: a trial is not run cold.
            if (RunPlan.breakDays(st, d.toEpochDay()) != null && (from.toEpochDay() until d.toEpochDay()).none { e ->
                    val day = LocalDate.ofEpochDay(e)
                    !paused(settings, day) && program.week.on(day.dayOfWeek.value).any { it.isRun }
                }
            ) return@filter false
            val planned = program.week.on(d.dayOfWeek.value)
            val item = DayItem(d, planned, done.filter { it.epochDay == d.toEpochDay() })
            planned.any { it.kind == SessionKind.RUN_QUALITY && !item.isDone(it) }
        }
        // A trial wants fresh legs: at least three days after a leg session (strength work on the legs costs running
        // economy for one to two days), when the next week holds such a hard day; otherwise the first hard day.
        return hard.firstOrNull { it.isBefore(hard.first().plusDays(7)) && !legsBefore(program, it) } ?: hard.firstOrNull()
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
        val st = store.runStateNow()
        val seen = store.settingsNow().loopSeen
        // No loop of its own (no GPS, a run entered by hand): the one GPS measured before, on the same lap length set.
        @Suppress("NAME_SHADOWING") val result = if (result.loopM == null && seen != null && seen.set == workout.lapMeters) result.copy(loopM = seen.meters.toDouble()) else result
        val (next, outcomes) = decideRun(st, workout, result, date)
        // Stored as run: on the loop GPS measured when the setting is clearly off (the history's distance and pace).
        val (stored, measured) = forma.core.run.RunPlan.onMeasuredLoop(workout, result) ?: (workout to result)
        val minutes = max(1, (result.durationSec / 60.0).roundToInt())
        val load = TrainingLoad.sessionLoad(result.rpe, result.durationSec / 60.0)
        val summary = RunSummary(outcomes, minutes, load, result.laps.orEmpty(), result.cadence, manual, result.kmSplits.orEmpty())
        val avgPace = measured.distanceM?.takeIf { it > 50 }?.let { result.durationSec / (it / 1000.0) }
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
                    id, workout.type.name, AppJson.encodeToString(RunWorkout.serializer(), stored), result.completed,
                    result.durationSec, measured.distanceM, avgPace, result.workDistanceM, measured.workPace, next.vdot,
                ),
            )
            // One point per moment: a doubled GPS callback must not fail the save.
            if (points.isNotEmpty()) db.runs().insertPoints(points.distinctBy { it.t }.map { it.copy(workoutId = id) })
            id
        }
        // The progress moves only once the run is safely stored.
        store.setRunState(next)
        store.setLastRun(LastRun(id, date.toEpochDay(), st, next, workout, result))
        // A loop measured clearly shorter than set: remembered, and «Сегодня» asks to set it; one that agrees clears it.
        val set = workout.lapMeters
        val loop = result.loopM
        if (set != null && loop != null && result.loopM != seen?.meters?.toDouble()) {
            val off = outcomes.any { it is RunOutcome.LoopShorter }
            store.editSettings { it.copy(loopSeen = if (off) LoopSeen(loop.roundToInt(), set) else null) }
        }
        // 30 minutes of running reached: the week of a beginner (run/walk only) becomes a runner's week — hard sessions,
        // a trial — with the progress kept. Seen in half-year simulations: without it a graduate had easy runs only, and
        // no trial, for months.
        if (outcomes.any { it is RunOutcome.Graduated }) graduate()
        onChange()
        id to summary
    }

    /** The running progress after [result] of [workout] run on [date] from [st], and the plan's decisions on it. */
    private suspend fun decideRun(st: RunState, workout: RunWorkout, result: RunResult, date: LocalDate): Pair<RunState, List<RunOutcome>> {
        val caps = capsOn(date) ?: forma.core.run.RunCaps(45, 90)
        val after = RunPlan.after(st, workout, result, caps, date.toEpochDay(), goal = store.profileNow()?.runGoal)
        var next = after.state
        if (workout.type == RunType.TEST && after.outcomes.any { it is RunOutcome.VdotChanged }) next = next.copy(lastTestDay = date.toEpochDay())
        // A test run but not counted (no time, a doubtful one): the next waits two weeks, not until the next hard day.
        else if (workout.type == RunType.TEST) next = next.copy(lastTrialTryDay = date.toEpochDay())
        return next to after.outcomes
    }

    /**
     * The rating of a finished workout put right ("how hard it was"): its load — and so freshness — follows. The last
     * run saved, while nothing has been built on it (no run since, the progress as it left it), is decided again as if
     * rated so: easy runs longer or shorter, a ladder's step. An older run keeps its decisions: the runs after it were
     * planned from them. True when the plan decided again.
     */
    suspend fun setRating(workoutId: Long, rpe: Int): Boolean = sessionLock.withLock {
        val w = db.workouts().get(workoutId)?.takeIf { it.status == Status.DONE } ?: return@withLock false
        val r = rpe.coerceIn(1, 10)
        if (w.rpe == r) return@withLock false
        // The coach's review spoke of the rating and what it decided: asked again when shown next.
        store.clearCoachReview(workoutId)
        val load = TrainingLoad.sessionLoad(r, (w.durationSec ?: 0) / 60.0)
        if (w.kind == Kind.LIFT) {
            val summary = w.summaryJson?.let { runCatching { AppJson.decodeFromString(LiftSummary.serializer(), it) }.getOrNull() }
            db.workouts().update(w.copy(rpe = r, load = load, summaryJson = summary?.let { AppJson.encodeToString(LiftSummary.serializer(), it.copy(load = load)) } ?: w.summaryJson))
            onChange()
            return@withLock false
        }
        val summary = w.summaryJson?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() }
        val last = store.lastRunNow()?.takeIf { it.workoutId == workoutId && it.after == store.runStateNow() }
        var outcomes = summary?.outcomes
        if (last != null) {
            val result = last.result.copy(rpe = r)
            val (next, decided) = decideRun(last.before, last.workout, result, LocalDate.ofEpochDay(last.day))
            store.setRunState(next)
            store.setLastRun(last.copy(after = next, result = result))
            db.runs().get(workoutId)?.let { db.runs().insert(it.copy(vdotAfter = next.vdot)) }
            if (decided.any { it is RunOutcome.Graduated } && outcomes?.any { it is RunOutcome.Graduated } != true) graduate()
            outcomes = decided
        }
        val fixed = summary?.copy(load = load, outcomes = outcomes ?: summary.outcomes)
        db.workouts().update(w.copy(rpe = r, load = load, summaryJson = fixed?.let { AppJson.encodeToString(RunSummary.serializer(), it) } ?: w.summaryJson))
        onChange()
        last != null
    }

    /**
     * The laps of a saved run put right ([forma.core.run.LapEdit]): a press forgotten or doubled. On a stadium where the
     * distance was the laps, it follows them; the plan's decisions on the run stay as they were.
     */
    suspend fun editRunLaps(workoutId: Long, laps: List<forma.core.run.Lap>) = sessionLock.withLock {
        editRunLapsLocked(workoutId, laps)
        // The laps of reps put right (a stray touch ended one early) on the last run, nothing built on it yet: decided
        // again on them — the reps' pace, a trial's time.
        val last = store.lastRunNow()?.takeIf { it.workoutId == workoutId && it.after == store.runStateNow() }
        val lapM = last?.workout?.lapMeters
        if (last != null && lapM != null && last.result.laps?.filter { it.rep != null } != laps.filter { it.rep != null }) {
            val pace = forma.core.run.ManualLaps.repPace(laps, lapM)
            val trial = last.workout.testMeters?.let { laps.filter { it.rep == 1 }.sumOf { l -> l.seconds }.takeIf { s -> s > 0 }?.roundToInt() }
            redecideLast(last, last.workout, last.result.copy(laps = laps, workPace = pace ?: last.result.workPace, testSeconds = trial ?: last.result.testSeconds))
            onChange()
        }
    }

    private suspend fun editRunLapsLocked(workoutId: Long, laps: List<forma.core.run.Lap>) {
        val w = db.workouts().get(workoutId) ?: return
        val summary = w.summaryJson?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() } ?: return
        db.workouts().update(w.copy(summaryJson = AppJson.encodeToString(RunSummary.serializer(), summary.copy(laps = laps))))
        // A review of the laps before they were put right (a double press read as a fast lap): asked again when shown next.
        store.clearCoachReview(workoutId)
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

    /**
     * The week of [monday] as planned and as done: no sessions on pause days; a session done on a later day for a missed
     * one closes it, as the catch-up counts it (a lift of any template, a friend's copied workout too).
     */
    fun weekFlow(monday: LocalDate): Flow<List<DayItem>> =
        combine(store.program, store.settings, db.workouts().observeDone(monday.toEpochDay(), monday.toEpochDay() + 6)) { program, settings, done ->
            val week = catchUpWeek(program, settings, monday, done)
            val byDay = week.associate { d -> d.day to done.filter { it.epochDay == monday.plusDays(d.day - 1L).toEpochDay() }.sortedBy { it.startedAt } }
            val t = today()
            val todayIndex = when {
                t.isBefore(monday) -> 0
                t.isAfter(monday.plusDays(6)) -> 8
                else -> t.dayOfWeek.value
            }
            val closes = CatchUp.closes(week, firstDay(program, monday), todayIndex)
            val later = closes.filter { it.doneDay != it.day }
            week.map { d ->
                val date = monday.plusDays(d.day - 1L)
                DayItem(
                    date, d.planned, byDay[d.day].orEmpty(),
                    doneLater = later.filter { it.day == d.day }.associate { it.index to byDay.getValue(it.doneDay)[it.doneIndex] },
                    catchUps = later.filter { it.doneDay == d.day }.associate { byDay.getValue(d.day)[it.doneIndex].id to monday.plusDays(it.day - 1L) },
                    paused = paused(settings, date),
                    onDay = closes.filter { it.day == d.day && it.doneDay == d.day }.associate { it.index to byDay.getValue(d.day)[it.doneIndex] },
                )
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
