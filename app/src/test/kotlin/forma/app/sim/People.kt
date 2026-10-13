package forma.app.sim

import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.model.Sex
import forma.core.run.Vdot
import kotlin.math.roundToInt
import kotlin.random.Random

/** The random people of the population runs: the same id is always the same person. */
object People {
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

    fun person(id: Int): Person {
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
        val reporting = r.pick(Reporting.HONEST, Reporting.HONEST, Reporting.NONE, Reporting.OPTIMISTIC, Reporting.INFLATED)
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
}
