package forma.app.sim

import android.content.Context
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.core.model.Catalog
import forma.core.model.LoadKind
import forma.core.model.Profile
import forma.core.plan.SessionKind
import forma.core.run.RunResult
import forma.core.strength.DoneSet
import forma.core.strength.Outcome
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

/** One exercise in one simulated session. */
data class LiftRecord(
    val day: LocalDate,
    val exerciseId: String,
    val role: SlotRole,
    val calibrate: Boolean,
    val deload: Boolean,
    val prescribed: Double?,
    val reps: Int,
    val sets: Int,
    val rir: Int,
    /** Weight that would give exactly the target reps with the planned reserve for the person's true strength. */
    val ideal: Double,
    val trueOneRm: Double,
    val done: List<Pair<Double, VirtualAthlete.SetResult>>,
    val outcome: Outcome?,
) {
    /** Prescribed load relative to the ideal one (body weight included for body-weight lifts). */
    fun ratio(bodyWeight: Double): Double? {
        val p = prescribed ?: return null
        val ex = Catalog[exerciseId]
        val bw = if (ex.load == LoadKind.BODYWEIGHT_PLUS) ex.bodyShare * bodyWeight else 0.0
        return (p + bw) / (ideal + bw)
    }

    val firstSetShort: Boolean get() = done.firstOrNull()?.let { it.second.reps < reps } ?: false
}

/**
 * Drives the real [TrainingRepository] day by day with a [VirtualAthlete]: plans from the app, sets performed by the
 * athlete, in-session hints taken like a user tapping them, runs completed as planned.
 */
class Simulation(
    context: Context,
    name: String,
    private val profile: Profile,
    val athlete: VirtualAthlete,
    val start: LocalDate = LocalDate.of(2026, 1, 5),
    /** Reference run: every set at the ideal weight for the person's true strength (what a perfect coach would give). */
    private val oracle: Boolean = false,
) {
    private val zone = ZoneId.of("Europe/Moscow")
    private var clock = start.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
    val db: FormaDatabase = FormaDatabase.create(context, inMemory = true)
    val store = SettingsStore(context, "sim-$name")
    val repo = TrainingRepository(db, store, now = { clock }, zone = zone)
    val lifts = mutableListOf<LiftRecord>()
    var sessionsDone = 0
        private set
    var runsDone = 0
        private set

    suspend fun setUp() {
        store.clear()
        repo.completeOnboarding(profile)
    }

    private fun at(d: LocalDate, hour: Int, minute: Int = 0) = d.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /**
     * Lives [days] days from [from]. [trains] says whether the person shows up that day;
     * [beforeDay] can change the athlete (slump, body weight) before it starts.
     */
    suspend fun live(
        days: Int,
        from: LocalDate = start,
        trains: (LocalDate) -> Boolean = { true },
        beforeDay: (LocalDate) -> Unit = {},
    ) {
        for (i in 0 until days) {
            val d = from.plusDays(i.toLong())
            beforeDay(d)
            athlete.newDay(d.toEpochDay())
            if (!trains(d)) continue
            val program = store.programNow() ?: error("no program")
            for (s in program.week.on(d.dayOfWeek.value)) {
                clock = at(d, 18)
                if (s.kind == SessionKind.LIFT) lift(s.templateId!!, d) else run(s.kind, d)
            }
        }
    }

    private suspend fun lift(templateId: String, d: LocalDate) {
        val id = repo.startLift(templateId, d)
        val plan = repo.planOf(db.workouts().get(id)!!)!!
        val records = mutableListOf<LiftRecord>()
        for (item in plan.items) {
            val ex = Catalog[item.exerciseId]
            val p = item.prescription
            var weight = p.weight ?: p.startHint ?: if (ex.load == LoadKind.EXTERNAL) (ex.bar ?: item.step * 4) else 0.0
            var reps = p.reps
            val done = mutableListOf<Pair<Double, VirtualAthlete.SetResult>>()
            val idealReps = if (p.calibrate) item.slot.repMin else p.reps
            val ideal = if (ex.load == LoadKind.TIMED) 0.0 else athlete.idealWeight(ex, idealReps, p.rir)
            if (oracle && ex.load != LoadKind.TIMED) {
                weight = max(0.0, forma.core.floorTo(ideal, ex.stepAt(ideal)))
                reps = idealReps
            }
            for (k in 0 until p.sets) {
                val kind = when {
                    p.calibrate -> VirtualAthlete.SetKind.CALIBRATE
                    p.top && k == p.sets - 1 -> VirtualAthlete.SetKind.TOP
                    else -> VirtualAthlete.SetKind.REGULAR
                }
                val r = athlete.perform(ex, weight, reps, k, if (oracle) VirtualAthlete.SetKind.REGULAR else kind)
                clock += (item.slot.restSec + 40) * 1000L
                repo.logSet(id, item, weight, r.reps, r.reportedRir)
                done += weight to r
                if (!oracle && athlete.acceptsHints && r.reps > 0) {
                    val adj = Progression.adjustAfterSet(ex, p, DoneSet(weight, r.reps, r.reportedRir), athlete.bodyWeight, index = k)
                    if (adj != null) { weight = adj.weight; reps = adj.reps }
                }
            }
            athlete.adapt(ex, done, d.toEpochDay())
            records += LiftRecord(
                d, ex.id, item.slot.role, p.calibrate, plan.deload, p.weight, p.reps, p.sets, p.rir, ideal,
                athlete.trueOneRm(ex.id), done, null,
            )
        }
        val avgReserve = records.flatMap { r -> r.done.map { it.second.actualRir } }.average().takeIf { !it.isNaN() } ?: 2.0
        val rpe = (10 - avgReserve).toInt().coerceIn(5, 10)
        val summary = repo.finishLift(id, rpe)
        val byEx = summary.reports.associateBy { it.exerciseId }
        lifts += records.map { it.copy(outcome = byEx[it.exerciseId]?.outcome) }
        sessionsDone++
    }

    private suspend fun run(kind: SessionKind, d: LocalDate) {
        val w = repo.runFor(kind, d)
        repo.saveRun(w, RunResult(1.0, w.targetRpe, w.totalSeconds), at(d, 7), emptyList(), d)
        runsDone++
    }

    // ---------- metrics ----------

    fun records(id: String): List<LiftRecord> = lifts.filter { it.exerciseId == id }

    /** Growth of true strength from the first to the last session, %. */
    fun gainPercent(id: String): Double {
        val r = records(id)
        return (r.last().trueOneRm / r.first().trueOneRm - 1) * 100
    }

    /** Ratios prescribed/ideal after calibration, outside deload weeks. */
    fun ratios(id: String, skipFirst: Int = 2): List<Double> =
        records(id).drop(skipFirst).filter { !it.calibrate && !it.deload }.mapNotNull { it.ratio(athlete.bodyWeight) }

    fun shortRate(id: String, skipFirst: Int = 2): Double {
        val r = records(id).drop(skipFirst).filter { !it.calibrate && !it.deload }
        return if (r.isEmpty()) 0.0 else r.count { it.firstSetShort }.toDouble() / r.size
    }

    fun outcomes(id: String): Map<String, Int> =
        records(id).mapNotNull { it.outcome?.let { o -> o::class.simpleName } }.groupingBy { it }.eachCount()

    fun mainLifts(): List<String> = lifts.filter { it.role == SlotRole.MAIN }.map { it.exerciseId }.distinct()

    /** Text table for the report. */
    fun report(title: String): String = buildString {
        appendLine("### $title")
        appendLine("sessions $sessionsDone, runs $runsDone")
        for (id in lifts.map { it.exerciseId }.distinct()) {
            val r = records(id)
            if (Catalog[id].load == LoadKind.TIMED || r.size < 3) continue
            val ratios = ratios(id).sorted()
            fun q(p: Double) = if (ratios.isEmpty()) Double.NaN else ratios[((ratios.size - 1) * p).toInt()]
            append("%-14s n=%2d gain=%5.1f%% ratio p10=%.2f p50=%.2f p90=%.2f short=%3.0f%% ".format(
                id, r.size, gainPercent(id), q(0.1), q(0.5), q(0.9), shortRate(id) * 100,
            ))
            appendLine(outcomes(id).entries.sortedBy { it.key }.joinToString(" ") { "${it.key}:${it.value}" })
        }
    }

    /** Session-by-session trace of one exercise: prescribed vs ideal. */
    fun trace(id: String): String = records(id).joinToString("\n") { r ->
        val sets = r.done.joinToString(" ") { (w, s) -> "${fmt(w)}×${s.reps}${s.reportedRir?.let { "@$it" } ?: ""}" }
        "%s %-4s p=%-6s ×%d ideal=%6.1f 1RM=%6.1f | %s | %s".format(
            r.day, if (r.deload) "DL" else if (r.calibrate) "CAL" else "", r.prescribed?.let(::fmt) ?: "-", r.reps,
            r.ideal, r.trueOneRm, sets, r.outcome?.toString()?.take(60) ?: "",
        )
    }

    private fun fmt(w: Double) = if (abs(w - w.toLong()) < 1e-9) w.toLong().toString() else "%.1f".format(w)

    fun close() = db.close()

    companion object {
        /** Where reports are written for reading after a run. */
        val outDir = File("build/sim").also { it.mkdirs() }
    }
}
