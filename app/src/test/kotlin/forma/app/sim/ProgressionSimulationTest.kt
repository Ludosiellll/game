package forma.app.sim

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Catalog
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LoadKind
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.strength.Outcome
import forma.core.strength.SlotRole
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * Months of training simulated through the real repository with virtual athletes. Every scenario is compared with an
 * ideal coach (the same athlete, every set at the ideal weight for their true strength): the app should capture most
 * of the possible gain, keep weights close to ideal, rarely miss, and never collapse or run away.
 * Reports go to build/sim for reading.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ProgressionSimulationTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val start = LocalDate.of(2026, 1, 5)

    private fun profile(goal: Goal, exp: Experience, days: Set<Int>, bw: Double = 80.0) = Profile(
        goal = goal, experience = exp, runLevel = RunLevel.BASE, trainingDays = days, bodyWeightKg = bw, sessionMinutes = 90,
    )

    private val strength3 = profile(Goal.STRENGTH, Experience.NOVICE, setOf(1, 3, 5))
    private val hybrid = profile(Goal.HYBRID, Experience.NOVICE, setOf(1, 3, 5, 6))

    private fun simulate(
        name: String,
        profile: Profile,
        athlete: () -> VirtualAthlete,
        weeks: Int = 16,
        trains: (LocalDate) -> Boolean = { true },
        beforeDay: (Simulation, LocalDate) -> Unit = { _, _ -> },
        oracle: Boolean = false,
    ): Simulation = runBlocking {
        val sim = Simulation(ctx, name + if (oracle) "-ideal" else "", profile, athlete(), oracle = oracle)
        sim.setUp()
        sim.live(weeks * 7, trains = trains, beforeDay = { beforeDay(sim, it) })
        if (!oracle) {
            val text = sim.report(name) + (sim.mainLifts().take(2) + "db_lunge" + "db_incline").distinct().joinToString("") { "--- $it\n${sim.trace(it)}\n" }
            File(Simulation.outDir, "$name.txt").writeText(text)
        }
        sim.close()
        sim
    }

    /** Main and secondary lifts with a weight (not timed, not accessories). */
    private fun Simulation.keyLifts() = lifts.filter { it.role != SlotRole.ACCESSORY && Catalog[it.exerciseId].load != LoadKind.TIMED }
        .map { it.exerciseId }.distinct().filter { records(it).size >= 6 }

    /** e1RM a prescription implies: the load for its reps with the planned reserve. */
    private fun implied(r: LiftRecord, sim: Simulation): Double {
        val ex = Catalog[r.exerciseId]
        val load = forma.core.strength.Progression.effectiveLoad(ex, r.prescribed!!, sim.athlete.bodyWeight)
        return forma.core.strength.OneRm.estimate(load, r.reps, r.rir)
    }

    /** The weight the app planned for the next session after [o]. */
    private fun nextWeight(o: Outcome?): Double? = when (o) {
        is Outcome.Calibrated -> o.weight
        is Outcome.AddReps -> o.weight
        is Outcome.LowerReps -> o.weight
        is Outcome.AddWeight -> o.to
        is Outcome.Jump -> o.to
        is Outcome.Hold -> o.weight
        is Outcome.Reduce -> o.to
        is Outcome.Reset -> o.to
        else -> null
    }

    private fun median(x: List<Double>) = x.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }

    /** Median gain of key lifts relative to the ideal coach (1.0 = all that was possible). */
    private fun efficiency(app: Simulation, ideal: Simulation): Double =
        median(app.keyLifts().filter { it in ideal.keyLifts() }.map { app.gainPercent(it) / ideal.gainPercent(it) })

    private fun ratios(sim: Simulation) = sim.keyLifts().flatMap { sim.ratios(it) }

    private fun shortRate(sim: Simulation) = median(sim.keyLifts().map { sim.shortRate(it) })

    private fun check(name: String, condition: Boolean, detail: () -> String) =
        assertTrue(condition, "$name: ${detail()}")

    private fun compare(name: String, profile: Profile, athlete: () -> VirtualAthlete, minEfficiency: Double) {
        val app = simulate(name, profile, athlete)
        val ideal = simulate(name, profile, athlete, oracle = true)
        val eff = efficiency(app, ideal)
        val r = ratios(app).sorted()
        val p50 = r[r.size / 2]
        val p90 = r[(r.size - 1) * 9 / 10]
        val line = "%s efficiency=%.2f ratio p50=%.2f p90=%.2f short=%.0f%%".format(name, eff, p50, p90, shortRate(app) * 100)
        File(Simulation.outDir, "summary.txt").appendText(line + "\n")
        check(name, eff >= minEfficiency) { line }
        check(name, p50 in 0.88..1.04) { line }
        check(name, p90 <= 1.08) { "weights ran ahead of strength: $line" }
        check(name, shortRate(app) <= 0.15) { "too many missed targets: $line" }
    }

    @Test fun noviceGainsMostOfWhatIsPossible() {
        compare("novice-honest", strength3, { VirtualAthlete(Experience.NOVICE, 80.0, 2) }, 0.85)
        compare("novice-no-rir", strength3, { VirtualAthlete(Experience.NOVICE, 80.0, 2, reporting = Reporting.NONE) }, 0.85)
        compare("novice-hybrid-no-rir", hybrid, { VirtualAthlete(Experience.NOVICE, 80.0, 1, reporting = Reporting.NONE) }, 0.85)
        compare("novice-more-than-asked", strength3, { VirtualAthlete(Experience.NOVICE, 80.0, 3, effort = Effort.OVERSHOOT, reporting = Reporting.NONE) }, 0.85)
        compare("novice-optimistic-rir", strength3, { VirtualAthlete(Experience.NOVICE, 80.0, 4, reporting = Reporting.OPTIMISTIC) }, 0.8)
        compare("novice-high-endurance", strength3, { VirtualAthlete(Experience.NOVICE, 80.0, 5, endurance = 1.25) }, 0.8)
        compare("novice-low-endurance", strength3, { VirtualAthlete(Experience.NOVICE, 80.0, 6, endurance = 0.8) }, 0.8)
        compare("novice-ignores-hints", strength3, { VirtualAthlete(Experience.NOVICE, 80.0, 7, acceptsHints = false, reporting = Reporting.NONE) }, 0.8)
    }

    @Test fun trainedPeopleKeepProgressing() {
        // Planned lighter weeks cost some gain against a coach who never deloads.
        compare("intermediate-strength", profile(Goal.STRENGTH, Experience.INTERMEDIATE, setOf(1, 2, 4, 5)),
            { VirtualAthlete(Experience.INTERMEDIATE, 80.0, 8) }, 0.7)
        compare("intermediate-muscle-no-rir", profile(Goal.MUSCLE, Experience.INTERMEDIATE, setOf(1, 2, 4, 5)),
            { VirtualAthlete(Experience.INTERMEDIATE, 80.0, 9, reporting = Reporting.NONE) }, 0.7)
        compare("advanced-strength", profile(Goal.STRENGTH, Experience.ADVANCED, setOf(1, 2, 4, 5)),
            { VirtualAthlete(Experience.ADVANCED, 85.0, 10) }, 0.6)
    }

    @Test fun lessThanAskedNeverCollapses() {
        val sim = simulate("novice-less-than-asked", strength3, { VirtualAthlete(Experience.NOVICE, 80.0, 11, effort = Effort.UNDERSHOOT, reporting = Reporting.NONE) })
        for (id in sim.keyLifts()) {
            val calibrated = sim.records(id).first { !it.calibrate }.prescribed!!
            val last = sim.records(id).last().prescribed!!
            check(id, last >= calibrated * 0.6) { "fell from $calibrated to $last" }
        }
        val deloads = sim.records(sim.keyLifts().first()).count { it.deload }
        check("deloads", deloads <= 2) { "$deloads lighter weeks in 16" }
    }

    @Test fun aWeekOffCostsNothing() {
        val off = 42L until 49L
        val sim = simulate("novice-week-off", strength3, { VirtualAthlete(Experience.NOVICE, 80.0, 12) },
            trains = { d -> d.toEpochDay() - start.toEpochDay() !in off })
        for (id in sim.keyLifts()) {
            val after = sim.records(id).first { it.day.toEpochDay() - start.toEpochDay() >= off.last }
            val before = sim.records(id).last { it.day.toEpochDay() - start.toEpochDay() < off.first }
            check(id, (after.prescribed ?: 0.0) >= (before.prescribed ?: 0.0)) { "after a week off ${after.prescribed} < ${before.prescribed}" }
        }
    }

    @Test fun aLongBreakStartsLighterAndRecovers() {
        val off = 42L until 77L
        val sim = simulate("novice-five-weeks-off", strength3, { VirtualAthlete(Experience.NOVICE, 80.0, 13) },
            trains = { d -> d.toEpochDay() - start.toEpochDay() !in off })
        for (id in sim.keyLifts()) {
            val r = sim.records(id)
            val before = r.last { it.day.toEpochDay() - start.toEpochDay() < off.first }
            val planned = nextWeight(before.outcome) ?: before.prescribed!!
            val back = r.filter { it.day.toEpochDay() - start.toEpochDay() >= off.last }
            val first = back.first()
            // 5 weeks off: 10 % lighter in total load (step rounding allowed).
            check(id, first.prescribed!! < planned - 1e-9) { "no easier restart: ${first.prescribed} vs planned $planned" }
            check(id, back.take(3).none { it.outcome is Outcome.Reset }) { "reset right after the break" }
            // Back to the pre-break level: compare the strength a prescription implies (weight with its reps),
            // since a coarse step may come back as more reps rather than more weight.
            val recovered = back.takeLast(3).maxOf { implied(it, sim) }
            check(id, recovered >= implied(before, sim) * 0.97) {
                "did not get back: ${back.takeLast(3).map { "${it.prescribed}×${it.reps}" }} < ${before.prescribed}×${before.reps}"
            }
        }
    }

    @Test fun tenBadDaysDoNotDerailProgress() {
        val sim = simulate("novice-bad-ten-days", strength3, { VirtualAthlete(Experience.NOVICE, 80.0, 14) },
            beforeDay = { s, d -> if (d.toEpochDay() - start.toEpochDay() == 42L) { s.athlete.slumpDays = 10; s.athlete.slumpFactor = 0.88 } })
        for (id in sim.keyLifts()) {
            check(id, (sim.outcomes(id)["Reset"] ?: 0) <= 1) { "resets: ${sim.outcomes(id)}" }
            check(id, sim.gainPercent(id) > 12) { "gain ${sim.gainPercent(id)}" }
        }
    }

    @Test fun missedSessionsStillProgress() {
        val skip = Random(15)
        val sim = simulate("novice-misses-a-quarter", strength3, { VirtualAthlete(Experience.NOVICE, 80.0, 15) },
            trains = { skip.nextDouble() > 0.25 })
        check("ratio", median(ratios(sim)) >= 0.9) { "median ratio ${median(ratios(sim))}" }
        for (id in sim.keyLifts()) check(id, sim.gainPercent(id) > 10) { "gain ${sim.gainPercent(id)}" }
    }

    @Test fun losingWeightKeepsBodyWeightLiftsRight() {
        // Pull-ups carry body weight: losing 6 kg makes the same extra weight easier, and the app should notice.
        val sim = simulate("intermediate-losing-weight", profile(Goal.STRENGTH, Experience.INTERMEDIATE, setOf(1, 2, 4, 5), 92.0),
            { VirtualAthlete(Experience.INTERMEDIATE, 92.0, 16) },
            beforeDay = { s, d -> if (d.dayOfWeek == DayOfWeek.MONDAY && s.athlete.bodyWeight > 86.0) s.athlete.bodyWeight -= 0.4 })
        val pull = sim.records("pullup")
        check("pullup", pull.size >= 8) { "pull-ups not in the plan" }
        check("pullup", pull.last().prescribed!! > pull.first { !it.calibrate }.prescribed!!) { "no progress with extra weight" }
        check("pullup", sim.shortRate("pullup") <= 0.25) { "short ${sim.shortRate("pullup")}" }
    }

    @Test fun twentyRandomPeople() {
        val rng = Random(2026)
        val lines = mutableListOf<String>()
        repeat(20) { i ->
            val exp = listOf(Experience.NOVICE, Experience.NOVICE, Experience.INTERMEDIATE).random(rng)
            val goal = listOf(Goal.STRENGTH, Goal.MUSCLE, Goal.HYBRID, Goal.FITNESS).random(rng)
            val days = listOf(setOf(1, 3, 5), setOf(1, 2, 4, 5), setOf(1, 3, 5, 6), setOf(2, 4, 6)).random(rng)
            val bw = 55.0 + rng.nextDouble() * 45
            val effort = listOf(Effort.FOLLOW, Effort.FOLLOW, Effort.FOLLOW, Effort.OVERSHOOT).random(rng)
            val reporting = Reporting.entries.random(rng)
            val endurance = 0.8 + rng.nextDouble() * 0.45
            val talent = 0.7 + rng.nextDouble() * 0.6
            val seed = 100 + i
            val make = { VirtualAthlete(exp, bw, seed, effort, reporting, endurance, talent) }
            val p = profile(goal, exp, days, bw)
            val app = simulate("random-$i", p, make, weeks = 12)
            val ideal = simulate("random-$i", p, make, weeks = 12, oracle = true)
            val r = ratios(app).sorted()
            val eff = efficiency(app, ideal)
            val line = "random-%02d %s %s %s bw=%.0f %s %s end=%.2f eff=%.2f ratio p10=%.2f p50=%.2f p90=%.2f short=%.0f%%".format(
                i, exp, goal, days, bw, effort, reporting, endurance, eff, r[r.size / 10], r[r.size / 2], r[(r.size - 1) * 9 / 10],
                shortRate(app) * 100,
            )
            lines += line
            File(Simulation.outDir, "random.txt").writeText(lines.joinToString("\n"))
            check("random-$i", r[(r.size - 1) * 9 / 10] <= 1.10) { "ran ahead: $line" }
            check("random-$i", r[r.size / 10] >= 0.75) { "fell behind: $line" }
            check("random-$i", eff >= 0.65) { "low efficiency: $line" }
        }
    }
}
