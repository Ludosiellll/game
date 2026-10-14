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

    @Test fun manyPeople() {
        val n = System.getenv("FORMA_SIM_N")?.toIntOrNull()
        assumeTrue(n != null)
        val from = System.getenv("FORMA_SIM_FROM")?.toIntOrNull() ?: 0
        val rows = File(out, "people-$from.tsv").printWriter()
        val issues = File(out, "issues-$from.txt").printWriter()
        rows.println("id\tissues\t" + People.person(0).tags.keys.joinToString("\t") + "\tv0\tv1\tappV\tfailQ\tgoalTop\teasy0\teasy1\tgymP50\tgymShort\tgymGain\tzone\trestLine\tlearned\tdips\tspikes\trirBias\ttimeRatio\teasyMoves\trested\toutgrew\treportKb\terror")
        for (id in from until from + n!!) {
            val p = People.person(id)
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
                line = "\t".repeat(21) + "\t" + (e.message ?: "").take(80)
            }
            rows.println("$id\t${found.joinToString(";") { it.substringBefore(':') }}\t" + p.tags.values.joinToString("\t") + "\t" + line)
            if (found.isNotEmpty()) issues.println("#$id ${p.tags}\n  " + found.joinToString("\n  "))
            rows.flush(); issues.flush()
        }
        rows.close(); issues.close()
    }

    /** What a good plan must never do; the numbers for the table. */
    private fun check(p: People.Person, sim: Simulation, found: MutableList<String>): String {
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
                val top = when (t) { RunType.GOAL_PACE -> forma.core.run.RunPlan.goalLadderSize; RunType.INTERVALS -> forma.core.run.RunPlan.intervalLadderSize; else -> forma.core.run.RunPlan.tempoLadderSize } - 1
                // The top rung held is the ladder done, not a rung stuck on.
                var same = 1
                var worst = 1
                for (i in 1 until st.size) {
                    same = if (st[i] == st[i - 1]) same + 1 else 1
                    if (st[i] != top) worst = maxOf(worst, same)
                }
                if (worst >= 6) found += "STUCK: $t on one rung $worst sessions in a row"
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
        return (cols + g + personal(p, sim, found)).joinToString("\t") + "\t"
    }

    /**
     * The rules that learn from the person and what the person sees about it: the load story and the rest line, the
     * exported file, the reserve scale, the session time, longer rest, paces from easy runs, outgrowing novice rules.
     */
    private fun personal(p: People.Person, sim: Simulation, found: MutableList<String>): List<String> = runBlocking {
        val repo = sim.repo
        val end = start.plusWeeks(weeks.toLong()).minusDays(1)
        val done = repo.db.workouts().allDone()
        val story = repo.loadStoryNow(end)
        if (done.any { (it.load ?: 0.0) > 0 } && story == null) found += "LOAD_STORY_MISSING"
        story?.let { st ->
            if (st.personal.restBelow !in -45.0..-10.0) found += "REST_LINE_OUT: ${st.personal.restBelow}"
            if (st.personal.learned && st.personal.restBelow > -20) found += "REST_LINE_TOO_HIGH: ${st.personal}"
            if (st.history && st.zone == null) found += "ZONE_MISSING"
            listOf(forma.core.load.LoadExplain.fitness(st), forma.core.load.LoadExplain.fatigue(st), forma.core.load.LoadExplain.freshness(st),
                forma.core.load.LoadExplain.personal(st.personal), forma.core.load.LoadExplain.zoneText(st)).forEach { t ->
                if (listOf("NaN", "Infinity", "null").any { it in t }) found += "LOAD_TEXT_BAD: ${t.take(80)}"
            }
        }
        // The exported file: every finished session in it, nothing broken in it.
        val text = forma.app.data.TrainingReport.build(forma.app.data.BackupManager(repo).snapshot(), end, "sim")
        val entries = Regex("^### \\d+ [а-я]+ \\d{4}, ", RegexOption.MULTILINE).findAll(text).count()
        if (entries != done.size) found += "REPORT_MISSES: ${done.size} sessions, $entries in the file"
        listOf("NaN", "Infinity", "null").forEach { if (it in text) found += "REPORT_BAD: «$it» at ${text.indexOf(it).let { i -> text.substring(maxOf(0, i - 60), i + 10).replace('\n', ' ') }}" }
        File(out, "reports").mkdirs()
        if (p.id % 100 == 0) File(out, "reports/report-${p.id}.md").writeText(text)
        // The reserve scale: a steady over-rater found out, an honest one left alone.
        val bias = repo.rirBias()
        val rep = p.athlete.reporting
        if (rep == Reporting.HONEST && bias > 0 || rep == Reporting.NONE && bias != 0) found += "RIR_SCALE_WRONG: $rep read as +$bias"
        val ratio = repo.timeRatio()
        if (sim.lifts.isNotEmpty() && ratio !in 0.7..1.6) found += "TIME_RATIO_ODD: ${"%.2f".format(ratio)}"
        val easyMoves = sim.runLog.count { r -> r.outcomes.any { "EasyPaces" in it } }
        val program = repo.store.programNow()
        val rested = program?.templates?.sumOf { t -> runCatching { repo.liftPlan(t.id, end).items.count { it.restWhy != null } }.getOrDefault(0) } ?: 0
        val outgrew = repo.outgrewNovice()
        listOf(
            st(story) { it.zone?.name ?: "" }, st(story) { "%.0f".format(it.personal.restBelow) }, st(story) { it.personal.learned.toString() },
            st(story) { it.dips.size.toString() }, st(story) { it.weeks.count { w -> w.level == forma.core.load.LoadStory.Level.SPIKE }.toString() },
            "$bias", "%.2f".format(ratio), "$easyMoves", "$rested", "$outgrew", "${text.length / 1024}",
        )
    }

    private fun st(s: forma.core.load.LoadStory.Story?, f: (forma.core.load.LoadStory.Story) -> String) = s?.let(f) ?: ""
}
