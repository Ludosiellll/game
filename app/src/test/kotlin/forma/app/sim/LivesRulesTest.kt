package forma.app.sim

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.AppJson
import forma.app.data.Kind
import forma.app.data.LiftSummary
import forma.app.data.RunSummary
import forma.app.ui.Texts
import forma.app.ui.screens.coachContext
import forma.core.model.Catalog
import forma.core.model.LoadKind
import forma.core.run.RunType
import forma.core.strength.Progression
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import kotlin.math.max
import kotlin.random.Random

/**
 * Random people living weeks of the app through the real repository (the plan of the week, the gym and the runs saved,
 * missed days, illness), and rules that must hold for anybody along the way: nothing breaks; what was done in the gym
 * is never lost; trials come at least two weeks apart; the gym plan's exercises never change without the person; the
 * coach's brief builds and fits what the coach takes; no text says «NaN» or «null».
 *
 * In the usual test run: [PEOPLE] people of a fixed seed, [WEEKS] weeks each. In the background run
 * (`FORMA_LIVES_SECONDS`, `FORMA_FUZZ_SEED` new every time): new people until the time is out. A broken rule fails
 * the test with the person (their id: `People.person(id)`), the day and the rule; written to `build/fuzz/lives.txt`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LivesRulesTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val start = LocalDate.of(2026, 10, 12)

    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    @Test fun peopleLiveByTheRules() {
        val seed = env("FORMA_FUZZ_SEED")?.toLong() ?: 20261028L
        val seconds = env("FORMA_LIVES_SECONDS")?.toLong() ?: 0
        val only = env("FORMA_LIVES_ID")?.toInt()
        val until = System.currentTimeMillis() + seconds * 1000
        val first = only ?: (Math.floorMod(seed, 1_000_000L).toInt() + 5000)
        val out = File("build/fuzz").also { it.mkdirs() }
        var n = 0
        while (true) {
            val id = first + n
            val weeks = if (seconds > 0) 16 else WEEKS
            val broken = live(id, weeks, seed)
            n++
            if (broken != null) {
                val text = "Правило жизни нарушено.\nПовторить: FORMA_LIVES_ID=$id (People.person($id), ${weeks} недель)\n\n$broken\n\nЧеловек: ${People.person(id).tags}"
                File(out, "lives.txt").writeText(text)
                throw AssertionError(text)
            }
            if (only != null) break
            if (seconds > 0) { if (System.currentTimeMillis() >= until) break } else if (n >= PEOPLE) break
        }
        File(out, "counts.txt").appendText("lives\t$n\tseed=$seed\n")
    }

    /** One person's weeks; what is broken, or null. */
    private fun live(id: Int, weeks: Int, seed: Long): String? {
        val p = People.person(id)
        val rnd = Random(id * 7 + 3)
        val sim = Simulation(ctx, "lives-$id-$seed", p.profile, p.athlete, start = start, runner = p.runner)
        try {
            runBlocking { sim.setUp() }
            val exercises = { runBlocking { sim.store.programNow() }?.templates?.associate { it.id to it.slots.map { s -> s.exerciseId } } }
            val before = exercises()
            runBlocking { sim.live(weeks * 7, trains = { rnd.nextDouble() >= p.miss }) }
            // The gym plan: the same exercises unless the person changed them (this one never does).
            val after = exercises()
            if (before != null && after != null) for ((t, xs) in before) {
                val now = after[t] ?: continue
                if (now.toSet() != xs.toSet()) return "план зала изменился без человека: «$t» было $xs, стало $now"
            }
            gymNeverLost(sim)?.let { return it }
            trialsApart(sim)?.let { return it }
            fitnessNearTheTruth(sim)?.let { return it }
            textsClean(sim)?.let { return it }
            // The coach's brief: builds, and fits what the coach takes (60 000 characters).
            val brief = runBlocking { coachContext(sim.repo, start.plusWeeks(weeks.toLong())) }
            if (brief.length > 60_000) return "сводка для тренера ${brief.length} знаков — тренер берёт 60 000"
            if (listOf("NaN", "Infinity").any { it in brief }) return "в сводке для тренера «NaN»"
            return null
        } catch (e: Throwable) {
            return "сломалось: ${e::class.simpleName}: ${e.message?.take(300)}\n" + e.stackTrace.take(10).joinToString("\n") { "    at $it" }
        } finally {
            sim.close()
        }
    }

    /**
     * Every planned set done at the plan's weight or heavier and at its reps: the next time of that exercise (within two
     * weeks, not in a lighter week, the plan not first time) asks no lighter than the lightest of them.
     */
    private fun gymNeverLost(sim: Simulation): String? {
        // By the progress the plan keeps (the exercise in its rep range): rows × 8 on one day and × 6 on another are two —
        // compared across them, «47.5 × 8 done, 42.5 × 6 next» was the other one's plan.
        for ((_, recs) in sim.lifts.groupBy { it.slot }) {
            val ex = recs.first().exerciseId
            val e = Catalog[ex]
            if (e.load == LoadKind.TIMED) continue
            for ((a, b) in recs.zipWithNext()) {
                val pw = a.prescribed ?: continue
                if (a.calibrate || a.deload || b.deload || b.calibrate || b.prescribed == null) continue
                if (b.day.toEpochDay() - a.day.toEpochDay() > 14 || b.reps != a.reps && b.reps < a.reps - 3) continue
                val planned = a.done.filter { it.second.reps > 0 }.take(a.sets)
                if (planned.size < a.sets || planned.any { it.first < pw - 1e-9 || it.second.reps < a.reps }) continue
                val reached = max(pw, forma.core.floorTo(planned.minOf { it.first }, e.stepAt(pw)))
                if (b.prescribed!! < reached - 1e-6 && Progression.effectiveLoad(e, b.prescribed!!, sim.athlete.bodyWeight) < Progression.effectiveLoad(e, reached, sim.athlete.bodyWeight) * 0.999) {
                    return "${a.day} $ex: все подходы ${planned.joinToString { "${it.first}×${it.second.reps}" }} при плане $pw×${a.reps} — а ${b.day} план ${b.prescribed}×${b.reps}"
                }
            }
        }
        return null
    }

    private fun trialsApart(sim: Simulation): String? {
        val trials = sim.runLog.filter { it.type == RunType.TEST }
        for ((a, b) in trials.zipWithNext()) {
            if (b.day.toEpochDay() - a.day.toEpochDay() < 14) return "контрольные ${a.day} и ${b.day}: меньше двух недель"
        }
        return null
    }

    /**
     * The fitness the app holds against the runner's true one (the simulation knows it, the app never does): after a
     * trial counted within 3 (a trial run a day's form and a fast start off, 1,5 % of the time, is about 1 of fitness),
     * never further than 5 at any run after the first trial — the ladders and the easy runs move it by one at a time,
     * and the next trial puts it right. Seen without it: nothing in the lives said whether the paces were the runner's.
     */
    private fun fitnessNearTheTruth(sim: Simulation): String? {
        var trialSeen = false
        for (r in sim.runLog) {
            val app = r.appVdot ?: continue
            val truth = r.trueVdot ?: continue
            val counted = r.type == RunType.TEST && r.outcomes.any { it.startsWith("VdotChanged") }
            if (counted) trialSeen = true
            if (!trialSeen) continue
            val gap = kotlin.math.abs(app - truth)
            if (counted && gap > 3.0) return "${r.day} ${r.title}: после контрольного форма $app, а бегун на ${"%.1f".format(truth)} (${r.outcomes})"
            if (gap > 5.0) return "${r.day} ${r.title}: форма $app ушла от бегуна (${"%.1f".format(truth)})"
        }
        return null
    }

    private fun textsClean(sim: Simulation): String? = runBlocking {
        for (w in sim.db.workouts().allDone()) {
            val json = w.summaryJson ?: continue
            val texts = if (w.kind == Kind.RUN) {
                AppJson.decodeFromString(RunSummary.serializer(), json).outcomes.map { Texts.run(it) }
            } else {
                AppJson.decodeFromString(LiftSummary.serializer(), json).reports.map { Texts.outcome(it.outcome, Catalog[it.exerciseId]) }
            }
            texts.firstOrNull { t -> listOf("NaN", "Infinity", "null").any { it in t } }?.let { return@runBlocking "${LocalDate.ofEpochDay(w.epochDay)}: в тексте «$it»" }
        }
        null
    }

    companion object {
        const val PEOPLE = 2
        const val WEEKS = 6
    }
}
