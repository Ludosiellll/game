package forma.app.sim

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.LoadKind
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.model.Sex
import forma.core.run.RunType
import forma.core.run.Vdot
import forma.core.strength.SlotRole
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Hundreds of random people, half a year each, through the real repository: every gym and running focus, emphasis,
 * experience, running level (run/walk included), 2–6 days, the gym and a run in one go or not, an own week, race goals
 * from 800 m to 10 km or none, a stadium of 200–400 m or the road, a known race or not; and as people: talent and
 * ceiling, honest or biased effort, reps in reserve reported or not, more or fewer reps than asked, hints taken or not,
 * missed days, illness, a fast start. Each run is checked against what a good plan must never do, and the findings are
 * grouped by what the people had in common. FORMA_SIM_N=… (long; skipped without it). Reports in build/sim/population.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PopulationTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val start = LocalDate.of(2026, 10, 12)
    private val weeks = 26
    private val out = File(Simulation.outDir, "population").also { it.mkdirs() }

    data class Person(
        val id: Int,
        val profile: Profile,
        val runner: VirtualRunner?,
        val athlete: VirtualAthlete,
        val miss: Double,
        val sickFrom: Int?,
        val sickWeeks: Int,
        val tags: Map<String, String>,
    )

    private fun <T> Random.pick(vararg xs: T): T = xs[nextInt(xs.size)]
    private fun Random.range(a: Double, b: Double) = a + nextDouble() * (b - a)

    private fun person(id: Int): Person {
        val r = Random(1000 + id)
        var lift = r.pick(*LiftFocus.values())
        var run = r.pick(*RunFocus.values())
        if (lift == LiftFocus.NONE && run == RunFocus.NONE) run = RunFocus.ENDURANCE
        val emphasis = r.pick(*Emphasis.values())
        val exp = r.pick(*Experience.values())
        val runLevel = if (run == RunFocus.NONE) RunLevel.BASE else r.pick(RunLevel.NONE, RunLevel.BASE, RunLevel.BASE, RunLevel.TRAINED)
        val nDays = r.nextInt(2, 7)
        val days = (1..7).shuffled(r).take(nDays).toSet()
        val double = r.nextBoolean()
        val own = r.nextDouble() < 0.3 && lift != LiftFocus.NONE && run != RunFocus.NONE
        val gymDays = if (own) (1..7).shuffled(r).take(r.nextInt(2, 5)).toSet() else null
        val runDays = if (own) (1..7).shuffled(r).take(r.nextInt(2, 6)).toSet() else null
        val bw = r.range(55.0, 100.0)
        val sex = if (r.nextDouble() < 0.25) Sex.FEMALE else Sex.MALE
        // The runner's true form: 1500 m from 5:00 to 8:00 (run/walk beginners slower).
        val start1500 = if (runLevel == RunLevel.NONE) r.range(480.0, 600.0) else r.range(300.0, 480.0)
        val v0 = Vdot.fromRace(1500.0, start1500)
        val goalMeters = r.pick(800, 1500, 1500, 3000, 5000, 10000)
        val goal = if (run != RunFocus.NONE && r.nextDouble() < 0.55) {
            val now = Vdot.predictSeconds(v0, goalMeters.toDouble())
            RunGoal(goalMeters, (now * r.range(0.82, 0.99)).roundToInt())
        } else null
        val lap = if (r.nextDouble() < 0.4) null else r.pick(200, 230, 250, 400)
        val known = run != RunFocus.NONE && runLevel != RunLevel.NONE && r.nextDouble() < 0.2
        val profile = Profile(
            sex = sex, bodyWeightKg = bw, birthYear = r.nextInt(1975, 2009), experience = exp, runLevel = runLevel,
            trainingDays = days, sessionMinutes = r.pick(45, 60, 75, 90), aim = Aim(lift, run, emphasis), runGoal = goal,
            lapMeters = lap, doubleDays = double, gymDays = gymDays, runDays = runDays,
            raceMeters = if (known) 5000.0 else null, raceSeconds = if (known) Vdot.predictSeconds(v0, 5000.0) * r.range(0.97, 1.05) else null,
        )
        val bias = if (r.nextDouble() < 0.6) 0 else r.pick(-2, -1, 1, 2)
        val fast = r.nextDouble() < 0.15
        val runner = if (run != RunFocus.NONE) VirtualRunner(v0, id, talent = r.range(0.4, 1.8), ceilingGain = r.range(0.05, 0.25), rpeBias = bias, startsFast = fast) else null
        val athleteExp = if (r.nextDouble() < 0.7) exp else r.pick(*Experience.values())
        val effort = if (r.nextDouble() < 0.7) Effort.FOLLOW else r.pick(Effort.OVERSHOOT, Effort.UNDERSHOOT)
        val reporting = r.pick(Reporting.HONEST, Reporting.HONEST, Reporting.NONE, Reporting.OPTIMISTIC)
        val athlete = VirtualAthlete(athleteExp, bw, id, effort, reporting, endurance = r.range(0.8, 1.25), talent = r.range(0.5, 1.5), acceptsHints = r.nextDouble() < 0.85)
        val miss = if (r.nextBoolean()) 0.0 else r.range(0.05, 0.4)
        val sick = r.nextDouble() < 0.2
        val tags = mapOf(
            "lift" to lift.name, "run" to run.name, "emph" to emphasis.name, "exp" to exp.name, "level" to runLevel.name,
            "days" to nDays.toString(), "double" to double.toString(), "own" to own.toString(),
            "goal" to (goal?.let { "${it.meters}" } ?: "none"), "lap" to (lap?.toString() ?: "road"), "known" to known.toString(),
            "bias" to bias.toString(), "fast" to fast.toString(), "effort" to effort.name, "rir" to reporting.name,
            "hints" to athlete.acceptsHints.toString(), "miss" to (if (miss == 0.0) "0" else if (miss < 0.2) "<20%" else "20-40%"),
            "sick" to sick.toString(), "athleteExp" to athleteExp.name, "sex" to sex.name,
        )
        return Person(id, profile, runner, athlete, miss, if (sick) r.nextInt(3, 20) else null, r.nextInt(1, 6), tags)
    }

    @Test fun manyPeople() {
        val n = System.getenv("FORMA_SIM_N")?.toIntOrNull()
        assumeTrue(n != null)
        val from = System.getenv("FORMA_SIM_FROM")?.toIntOrNull() ?: 0
        val rows = File(out, "people-$from.tsv").printWriter()
        val issues = File(out, "issues-$from.txt").printWriter()
        rows.println("id\tissues\t" + person(0).tags.keys.joinToString("\t") + "\tv0\tv1\tappV\tfailQ\tgoalTop\teasy0\teasy1\tgymP50\tgymShort\tgymGain\terror")
        for (id in from until from + n!!) {
            val p = person(id)
            val found = mutableListOf<String>()
            var line = ""
            try {
                val rnd = Random(id * 7 + 3)
                val sickStart = p.sickFrom?.let { start.plusWeeks(it.toLong()) }
                val sim = runBlocking {
                    val s = Simulation(ctx, "pop-$id", p.profile, p.athlete, start = start, runner = p.runner)
                    s.setUp()
                    s.live(
                        weeks * 7,
                        trains = { d ->
                            val ill = sickStart != null && d >= sickStart && d < sickStart.plusWeeks(p.sickWeeks.toLong())
                            !ill && rnd.nextDouble() >= p.miss
                        },
                    )
                    s
                }
                line = check(p, sim, found)
                if (System.getenv("FORMA_SIM_TRACE") != null) File(out, "trace-$id.txt").writeText(sim.runLog.joinToString("\n") { r ->
                    "${r.day} ${r.type} st${r.stage + 1} ${r.title} rpe=${r.result.rpe} done=${r.result.completed} appV=${r.appVdot} trueV=${"%.1f".format(r.trueVdot)} ${r.outcomes}"
                })
                sim.close()
            } catch (e: Throwable) {
                found += "CRASH ${e::class.simpleName}: ${e.message?.take(200)} at ${e.stackTrace.take(4).joinToString(" < ")}"
                line = "\t".repeat(10) + "\t" + (e.message ?: "").take(80)
            }
            rows.println("$id\t${found.joinToString(";") { it.substringBefore(':') }}\t" + p.tags.values.joinToString("\t") + "\t" + line)
            if (found.isNotEmpty()) issues.println("#$id ${p.tags}\n  " + found.joinToString("\n  "))
            rows.flush(); issues.flush()
        }
        rows.close(); issues.close()
    }

    /** What a good plan must never do; the numbers for the table. */
    private fun check(p: Person, sim: Simulation, found: MutableList<String>): String {
        val runs = sim.runLog
        val runner = sim.runner
        var cols = listOf("", "", "", "", "", "", "")
        if (runner != null && runs.isNotEmpty()) {
            val v0 = runs.first().trueVdot!!
            // Paces from a fitness above the true one: the plan asks for more than the body has.
            val over = runs.filter { it.appVdot != null && it.trueVdot != null }.maxOfOrNull { it.appVdot!! - it.trueVdot!! } ?: 0.0
            if (over > 1.5) found += "PACES_TOO_FAST: app VDOT ${"%.1f".format(over)} above the true"
            val q = runs.filter { it.x != null }
            val fail = if (q.size >= 6) q.count { it.x!! > 1.0 }.toDouble() / q.size else 0.0
            if (q.size >= 6 && fail > 0.4) found += "MANY_FAILED: ${(fail * 100).roundToInt()} % of ${q.size} hard sessions not held"
            // A ladder on one rung, session after session.
            for (t in listOf(RunType.GOAL_PACE, RunType.INTERVALS, RunType.TEMPO)) {
                val st = runs.filter { it.type == t }.map { it.stage }
                var same = 1
                var worst = 1
                for (i in 1 until st.size) { same = if (st[i] == st[i - 1]) same + 1 else 1; worst = maxOf(worst, same) }
                val top = when (t) { RunType.GOAL_PACE -> forma.core.run.RunPlan.goalLadderSize; RunType.INTERVALS -> forma.core.run.RunPlan.intervalLadderSize; else -> forma.core.run.RunPlan.tempoLadderSize } - 1
                if (worst >= 6 && st.lastOrNull() != top) found += "STUCK: $t on one rung $worst sessions in a row"
            }
            // Easy volume that shrank over half a year while the form grew (and the person trained).
            val e0 = runs.first().easyMinutes
            val e1 = runs.last().easyMinutes
            if (runner.vdot > v0 + 0.5 && e1 < e0 && p.miss < 0.2 && p.sickFrom == null) found += "VOLUME_SHRANK: easy $e0 → $e1 min"
            // A week far above the weeks before it without anything missed.
            if (p.miss == 0.0 && p.sickFrom == null) {
                val weekly = runs.groupBy { (it.day.toEpochDay() - start.toEpochDay()) / 7 }.toSortedMap().values.map { w -> w.sumOf { it.result.durationSec } / 60.0 }
                for (i in 3 until weekly.size) {
                    val before = weekly.subList(i - 3, i).maxOrNull()!!
                    if (before > 30 && weekly[i] > before * 1.3) { found += "SPIKE: week ${i + 1} ${weekly[i].roundToInt()} min after ≤ ${before.roundToInt()}"; break }
                }
            }
            val trials = runs.filter { it.type == RunType.TEST }
            val notCounted = trials.count { r -> r.outcomes.any { "TestNotCounted" in it || "TestDoubtful" in it } }
            if (notCounted > 0) found += "TRIAL_NOT_COUNTED: $notCounted of ${trials.size}"
            if (p.profile.runLevel == RunLevel.NONE && runs.all { it.type == RunType.RUN_WALK } && runs.size > 40) found += "NEVER_GRADUATED: run/walk all half-year"
            val goal = p.profile.runGoal
            if (goal != null) {
                val reachedTrue = runs.firstOrNull { Vdot.predictSeconds(it.trueVdot!!, goal.meters.toDouble()) <= goal.seconds }?.day
                val reachedApp = runs.any { r -> r.outcomes.any { "GoalReached" in it } }
                if (reachedTrue != null && !reachedApp && reachedTrue < start.plusWeeks(weeks - 9L)) found += "GOAL_NOT_SEEN: reached $reachedTrue, the app never counted it"
            }
            val goalTop = runs.filter { it.type == RunType.GOAL_PACE }.maxOfOrNull { it.stage + 1 }
            cols = listOf("%.1f".format(v0), "%.1f".format(runner.vdot), runs.last().appVdot?.let { "%.1f".format(it) } ?: "", "%.2f".format(fail), goalTop?.toString() ?: "", "$e0", "$e1")
        }
        var g = listOf("", "", "")
        val key = sim.lifts.filter { it.role != SlotRole.ACCESSORY && Catalog[it.exerciseId].load != LoadKind.TIMED }.map { it.exerciseId }.distinct().filter { sim.records(it).size >= 6 }
        if (key.isNotEmpty()) {
            val ratios = key.flatMap { sim.ratios(it) }.sorted()
            val p50 = ratios.getOrNull(ratios.size / 2) ?: 1.0
            val p90 = ratios.getOrNull((ratios.size * 0.9).toInt()) ?: 1.0
            val short = key.map { sim.shortRate(it) }.average()
            val gain = key.map { sim.gainPercent(it) }.average()
            if (p50 < 0.85 || p50 > 1.05) found += "GYM_OFF: prescribed/ideal median ${"%.2f".format(p50)}"
            if (p90 > 1.1) found += "GYM_TOO_HEAVY: p90 ${"%.2f".format(p90)}"
            if (short > 0.2) found += "GYM_SHORT: first set short in ${(short * 100).roundToInt()} %"
            g = listOf("%.2f".format(p50), "%.2f".format(short), "%.1f".format(gain))
        }
        return (cols + g).joinToString("\t") + "\t"
    }
}
