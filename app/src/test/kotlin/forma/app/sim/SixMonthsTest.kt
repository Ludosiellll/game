package forma.app.sim

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.run.RunType
import forma.core.run.Vdot
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

/**
 * Half a year of Denis's plan (the gym Tue/Thu/Sat, running Mon/Tue/Thu/Sat, 1500 m in 5:30 on a 230 m lap) lived by
 * different virtual people through the real repository: one who gains steadily, one who gains little, one who misses
 * sessions, one who rates everything harder or easier than it was, one who starts reps too fast, one who falls ill, one
 * close to the goal and one far from it. Reports in build/sim/six-months for reading; long — run with FORMA_SIM=1.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SixMonthsTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val start = LocalDate.of(2026, 10, 12)
    private val weeks = 26
    private val goal = RunGoal(1500, 330)
    private val out = File(Simulation.outDir, "six-months").also { it.mkdirs() }

    private val denis = Profile(
        name = "Денис", aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
        runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6), bodyWeightKg = 80.0, birthYear = 2008, doubleDays = true,
        runGoal = goal, lapMeters = 230, gymDays = setOf(2, 4, 6), runDays = setOf(1, 2, 4, 6), sessionMinutes = 90,
    )

    private fun vdotFor(sec: Int) = Vdot.fromRace(1500.0, sec.toDouble())

    private class Case(
        val name: String,
        val about: String,
        val athlete: () -> VirtualAthlete,
        val runner: () -> VirtualRunner,
        val trains: (LocalDate) -> Boolean = { true },
        val before: (Simulation, LocalDate) -> Unit = { _, _ -> },
    )

    private fun cases(): List<Case> {
        val misses = Random(7)
        val sick = start.plusWeeks(10)
        return listOf(
            Case("1-steady", "тренируется по плану, растёт", { VirtualAthlete(Experience.ADVANCED, 80.0, 1) }, { VirtualRunner(vdotFor(365), 1, talent = 1.2) }),
            Case("2-responder", "быстро прибавляет и в зале, и в беге", { VirtualAthlete(Experience.INTERMEDIATE, 80.0, 2, talent = 1.3) }, { VirtualRunner(vdotFor(365), 2, talent = 1.6, ceilingGain = 0.22) }),
            Case("3-slow", "прибавляет мало", { VirtualAthlete(Experience.ADVANCED, 80.0, 3, talent = 0.5) }, { VirtualRunner(vdotFor(365), 3, talent = 0.5, ceilingGain = 0.08) }),
            Case("4-misses", "пропускает четверть дней", { VirtualAthlete(Experience.ADVANCED, 80.0, 4) }, { VirtualRunner(vdotFor(365), 4, talent = 1.2) }, trains = { misses.nextDouble() > 0.25 }),
            Case("5-rates-harder", "оценивает тяжесть на 2 выше, запас в зале не отмечает", { VirtualAthlete(Experience.ADVANCED, 80.0, 5, reporting = Reporting.NONE) }, { VirtualRunner(vdotFor(365), 5, talent = 1.2, rpeBias = 2) }),
            Case("6-rates-easier", "оценивает тяжесть на 2 ниже, запас в зале завышает", { VirtualAthlete(Experience.ADVANCED, 80.0, 6, reporting = Reporting.OPTIMISTIC) }, { VirtualRunner(vdotFor(365), 6, talent = 1.2, rpeBias = -2) }),
            Case("7-starts-fast", "первые отрезки слишком быстро", { VirtualAthlete(Experience.ADVANCED, 80.0, 7) }, { VirtualRunner(vdotFor(365), 7, talent = 1.2, startsFast = true) }),
            Case(
                "8-sick-3-weeks", "болеет три недели с 10-й", { VirtualAthlete(Experience.ADVANCED, 80.0, 8) }, { VirtualRunner(vdotFor(365), 8, talent = 1.2) },
                trains = { it < sick || it >= sick.plusWeeks(3) },
            ),
            Case("9-close", "уже бегает 1500 за 5:45", { VirtualAthlete(Experience.ADVANCED, 80.0, 9) }, { VirtualRunner(vdotFor(345), 9, talent = 1.2) }),
            Case("10-far", "бегает 1500 за 6:40", { VirtualAthlete(Experience.INTERMEDIATE, 80.0, 10) }, { VirtualRunner(vdotFor(400), 10, talent = 1.2) }),
        )
    }

    @Test fun halfAYear() {
        assumeTrue(System.getenv("FORMA_SIM") != null)
        val summary = StringBuilder()
        for (c in cases()) {
            val sim = runBlocking {
                val s = Simulation(ctx, "6m-${c.name}", denis, c.athlete(), start = start, runner = c.runner())
                s.setUp()
                s.live(weeks * 7, trains = c.trains, beforeDay = { c.before(s, it) })
                s
            }
            val text = report(c, sim)
            File(out, "${c.name}.txt").writeText(text)
            summary.appendLine(text.lineSequence().take(14).joinToString("\n")).appendLine()
            sim.close()
        }
        File(out, "summary.txt").writeText(summary.toString())
        println(summary)
    }

    private fun clock(sec: Double) = "%d:%04.1f".format((sec / 60).toInt(), sec % 60)

    private fun report(c: Case, sim: Simulation): String = buildString {
        val runs = sim.runLog
        val runner = sim.runner!!
        val first = runs.first()
        val trueStart = Vdot.predictSeconds(first.trueVdot!!, 1500.0)
        appendLine("## ${c.name} — ${c.about}")
        appendLine("1500 м (истинно): ${clock(trueStart)} → ${clock(runner.race1500())}; цель 5:30 ${if (runner.race1500() <= 330) "ДОСТИГНУТА" else "не достигнута"}")
        val trials = runs.filter { it.type == RunType.TEST }
        appendLine("контрольные: " + trials.joinToString(" ") { "${it.day}: ${it.result.testSeconds?.let { s -> clock(s * 1500.0 / 1495) }}" })
        val q = runs.filter { it.x != null }
        fun share(p: (Double) -> Boolean) = if (q.isEmpty()) 0 else q.count { p(it.x!!) } * 100 / q.size
        appendLine("тяжёлых пробежек ${q.size}: в темпе ${share { it <= 1.0 }}%, не удержал ${share { it > 1.0 }}%, слишком легко ${share { it < 0.8 }}%")
        val goalStages = runs.filter { it.type == RunType.GOAL_PACE }.map { it.stage + 1 }
        appendLine("лестница темпа цели (шаги): ${goalStages.joinToString(",")}")
        val ints = runs.filter { it.type == RunType.INTERVALS }.map { it.stage + 1 }
        val tempos = runs.filter { it.type == RunType.TEMPO }.map { it.stage + 1 }
        appendLine("интервалы: ${ints.joinToString(",")}; темповые: ${tempos.joinToString(",")}")
        appendLine("лёгкий бег, мин: ${first.easyMinutes} → ${runs.last().easyMinutes}; длинная: ${first.longMinutes} → ${runs.last().longMinutes}")
        val weekly = runs.groupBy { (it.day.toEpochDay() - start.toEpochDay()) / 7 }.mapValues { e -> e.value.sumOf { it.result.durationSec } / 60 }
        val jumps = weekly.keys.sorted().zipWithNext().map { (a, b) -> weekly.getValue(b).toDouble() / weekly.getValue(a) }
        appendLine("бег в неделю, мин: ${weekly.toSortedMap().values.joinToString(",")}; макс. рост за неделю ${"%.0f".format(((jumps.maxOrNull() ?: 1.0) - 1) * 100)}%")
        val err = runs.filter { it.appVdot != null && it.trueVdot != null }.map { it.appVdot!! - it.trueVdot!! }
        appendLine("VDOT приложения − истинный: в среднем ${"%.1f".format(err.average())}, крайние ${"%.1f".format(err.minOrNull() ?: 0.0)}…${"%.1f".format(err.maxOrNull() ?: 0.0)}")
        val reached = runs.firstOrNull { Vdot.predictSeconds(it.trueVdot!!, 1500.0) <= 330 }?.day
        appendLine("цель по истинной форме: ${reached ?: "—"}; засчитана приложением: ${runs.firstOrNull { "GoalReached" in it.outcomes.toString() }?.day ?: "—"}")
        appendLine("зал: тренировок ${sim.sessionsDone}")
        appendLine(sim.report("зал").lines().drop(2).filter { it.isNotBlank() }.take(4).joinToString("\n"))
        appendLine()
        appendLine("--- пробежки")
        for (r in runs) {
            appendLine("%s %-10s ст%-2d %-36s x=%-5s rpe=%-2d темп=%-6s VDOT прил=%-5s ист=%-5s лёгк=%d длин=%d %s".format(
                r.day, r.type, r.stage + 1, r.title.take(36), r.x?.let { "%.2f".format(it) } ?: "-", r.result.rpe,
                r.result.workPace?.let { "%.0f".format(it) } ?: "-", r.appVdot?.let { "%.1f".format(it) } ?: "-",
                r.trueVdot?.let { "%.1f".format(it) } ?: "-", r.easyMinutes, r.longMinutes, r.outcomes.joinToString(" ") { it.take(28) },
            ))
        }
    }
}
