package forma.core.plan

import forma.core.body.Body
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.model.Sex
import forma.core.run.RunCaps
import forma.core.run.RunState
import forma.core.run.Vdot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.max

object ProgramGenerator {
    /** The planner's version: a program laid out by an older one is laid out again (progress kept). */
    const val PLANNER = 2

    fun generate(profile: Profile, today: LocalDate): Program {
        val aim = profile.aim.normalized()
        val days = profile.trainingDays.filter { it in 1..7 }.toSet().ifEmpty { setOf(1, 3, 5) }
        val middleGoal = profile.runGoal?.middleDistance == true && aim.run != RunFocus.NONE
        val plan = planAim(aim, middleGoal)
        // The week as the person laid it out day by day, or laid out by the app over their days.
        val gym = profile.gymDays?.filter { it in 1..7 }?.toSet()
        val run = profile.runDays?.filter { it in 1..7 }?.toSet()
        val own = gym != null && run != null && (gym + run).isNotEmpty()
        val mix = if (own) Scheduler.mixFixed(plan, gym!!, run!!, profile.runLevel, middleGoal)
        else Scheduler.mix(plan, days.size, profile.experience, profile.runLevel, profile.doubleDays, middleGoal = middleGoal)
        val shared = if (own) (gym!! intersect run!!).isNotEmpty() else profile.doubleDays
        // Upper / lower / upper only when there are hard runs to put after the upper body; with easy runs alone the
        // full-body sessions (legs three times a week) stay.
        val (templates, lighter) = lightStart(
            Templates.forWeek(plan, profile.experience, mix.lifts, shared = shared && mix.quality + mix.long > 0)
                .map { Templates.fit(it, profile.sessionMinutes) },
            profile,
        )
        val laid = if (own) Scheduler.scheduleFixed(gym!!, run!!, mix, templates, plan) else Scheduler.schedule(days, mix, templates, plan)
        val week = laid.copy(notes = laid.notes + listOfNotNull(lighter, manyLifts(profile, mix.lifts, own)))
        val block = when (profile.experience) {
            Experience.NOVICE -> 0
            Experience.INTERMEDIATE -> 5
            Experience.ADVANCED -> 4
        }
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val program = Program(aim.nearestGoal(), profile.experience, templates, week, block, caps(plan), monday.toEpochDay(), aim, today.toEpochDay(), planner = PLANNER)
        return program.copy(runCaps = runCaps(program, middleGoal))
    }

    /** The barbell lifts a beginner may start with dumbbells instead (the same movement, the same muscles). */
    private val DUMBBELL_START = mapOf(
        "ohp" to "db_shoulder", "bench" to "db_bench", "squat" to "goblet_squat", "rdl" to "db_rdl", "front_squat" to "goblet_squat",
    )

    /**
     * A beginner for whom the empty bar is far too heavy (a light woman's overhead press: the bar is 20 kg, she is guessed
     * to press about 9): the dumbbell version in its place, from a pair of a few kilos, and a line saying why. The bar
     * cannot be lighter, and a start above her strength is a target missed every session. Not for a strength goal (that
     * is the barbell lifts), not for someone who knows their weights, never for the lift of a gym goal.
     */
    fun lightStart(templates: List<Template>, profile: Profile): Pair<List<Template>, String?> {
        val a = profile.aim.normalized()
        if (profile.experience != Experience.NOVICE || a.lift == LiftFocus.STRENGTH || a.lift == LiftFocus.POWERBUILDING) return templates to null
        val swapped = linkedMapOf<String, String>()
        val out = templates.map { t ->
            t.copy(slots = t.slots.map { slot ->
                val to = DUMBBELL_START[slot.exerciseId]
                val ex = Catalog.find(slot.exerciseId)
                val bar = ex?.bar
                val guess = ex?.let { it.startRatio * profile.bodyWeightKg * if (profile.sex == Sex.FEMALE) 0.6 else 1.0 }
                val light = to != null && bar != null && guess != null && guess < 0.75 * bar
                if (light && profile.liftGoal?.exerciseId != slot.exerciseId && t.slots.none { it.exerciseId == to }) {
                    swapped[slot.exerciseId] = to!!
                    slot.copy(exerciseId = to)
                } else slot
            })
        }
        if (swapped.isEmpty()) return templates to null
        val bar = swapped.keys.mapNotNull { Catalog.find(it)?.bar }.max()
        val pairs = swapped.entries.joinToString(", ") { (from, to) -> "вместо «${Catalog[from].name}» — «${Catalog[to].name}»" }
        return out to (pairs.replaceFirstChar { it.uppercase() } + ": пустой гриф весит ${forma.core.text.Ru.kg(bar)} кг, для начала это тяжело, " +
            "а гантели начинаются с пары килограммов. Окрепнешь — штангу можно вернуть заменой упражнения.")
    }

    /**
     * A beginner's own week with more than three gym days: kept (the days are theirs), and said plainly that three are
     * enough to start with ([Scheduler.mix] gives a beginner three).
     */
    private fun manyLifts(profile: Profile, lifts: Int, own: Boolean): String? =
        if (own && profile.experience == Experience.NOVICE && lifts > 3) {
            "Новичку для роста хватает 3 тренировок в зале в неделю: на $lifts устанешь сильнее, а расти будешь почти так же. " +
                "Если тяжело — убери день-другой зала."
        } else null

    /**
     * The aim the running is planned by. A race of up to 3 km decides it whatever the focus is called: for 1500 m
     * endurance is built by race pace, intervals and easy running (the speed plan), not by long runs for a half
     * marathon, and running "for health" has no hard runs at all.
     */
    fun planAim(aim: Aim, middleGoal: Boolean): Aim =
        if (middleGoal && aim.run != RunFocus.SPEED && aim.run != RunFocus.NONE) aim.copy(run = RunFocus.SPEED) else aim

    /**
     * The caps of [program] as runs use them. A middle-distance goal with no long run and one easy run a week: that easy
     * run is the whole aerobic base, so it may grow to an hour (as a long easy run would), kept easy. Applied when runs
     * are planned, so a plan made before keeps its sessions as they are.
     */
    fun runCaps(program: Program, middleGoal: Boolean): RunCaps {
        val runs = program.week.days.flatMap { it.sessions }
        val baseRun = middleGoal && runs.none { it.kind == SessionKind.RUN_LONG } && runs.count { it.kind == SessionKind.RUN_EASY } == 1
        return if (baseRun) program.runCaps.copy(easyMax = max(program.runCaps.easyMax, 60)) else program.runCaps
    }

    /** How long easy and long runs may grow: by what running is for and how much of the week it gets. */
    fun caps(aim: Aim): RunCaps {
        val a = aim.normalized()
        return when (a.run) {
            RunFocus.NONE, RunFocus.HEALTH -> if (a.emphasis == Emphasis.RUN) RunCaps(45, 75) else RunCaps(40, 60)
            RunFocus.ENDURANCE -> when (a.emphasis) {
                Emphasis.RUN -> RunCaps(60, 150)
                Emphasis.BALANCED -> RunCaps(50, 110)
                Emphasis.GYM -> RunCaps(40, 90)
            }
            RunFocus.SPEED -> when (a.emphasis) {
                Emphasis.RUN -> RunCaps(55, 100)
                Emphasis.BALANCED -> RunCaps(45, 90)
                Emphasis.GYM -> RunCaps(35, 70)
            }
        }
    }

    /** Starting point of the running ladders from what the person told about their running. */
    fun initialRunState(profile: Profile): RunState {
        // Only a believable race (2:10–15:00 a km) gives paces; the fitness stays within what people run.
        val m = profile.raceMeters
        val sec = profile.raceSeconds
        val vdot = if (m != null && sec != null && m > 0 && sec > 0 && sec / (m / 1000) in forma.core.run.RunPlan.PLAUSIBLE_PACE) {
            ((Vdot.fromRace(m, sec) * 2).toInt() / 2.0).coerceIn(20.0, 85.0)
        } else null
        // Novice runners with BMI over 30 get injured more (Nielsen et al. 2013) and fewer when they start with less
        // running (Bertelsen et al.): they start shorter and grow by the same rules.
        val heavy = profile.heightCm?.let { Body.bmi(profile.bodyWeightKg, it) >= 30 } == true
        return when (profile.runLevel) {
            RunLevel.NONE -> RunState(vdot = vdot, walkStage = 0)
            RunLevel.BASE -> if (heavy) RunState(vdot = vdot, walkStage = -1, easyMinutes = 20, longMinutes = 30)
            else RunState(vdot = vdot, walkStage = -1, easyMinutes = 25, longMinutes = 40)
            RunLevel.TRAINED -> RunState(vdot = vdot, walkStage = -1, easyMinutes = 35, longMinutes = 60, intervalStage = 2, tempoStage = 2)
        }
    }

    /** 1-based week of the lifting block's count for [date] (the block may start again: a pause, a lighter week). */
    fun weekIndex(program: Program, date: LocalDate): Int =
        ((date.toEpochDay() - program.startEpochDay).coerceAtLeast(0) / 7).toInt() + 1

    /** 1-based week since the program was made, for "Неделя N": a block started again does not start it over. */
    fun programWeek(program: Program, date: LocalDate): Int {
        val made = program.createdEpochDay?.let { LocalDate.ofEpochDay(it).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay() }
        return ((date.toEpochDay() - (made ?: program.startEpochDay)).coerceAtLeast(0) / 7).toInt() + 1
    }

    /**
     * The program with its block counted so that the week of [monday] is the block's lighter week (the last one) and a
     * new block follows it; without blocks, the 4-week count of lighter running weeks the same way.
     */
    fun lighterWeekAt(program: Program, monday: LocalDate): Program {
        val weeks = if (program.blockWeeks > 0) program.blockWeeks else 4
        return program.copy(startEpochDay = monday.minusWeeks(weeks - 1L).toEpochDay())
    }

    /** Lighter week: last week of each lifting block, and every 4th week for running. */
    fun isDeloadWeek(program: Program, date: LocalDate): Boolean {
        val w = weekIndex(program, date)
        return program.blockWeeks > 0 && w % program.blockWeeks == 0
    }

    fun isRunCutbackWeek(program: Program, date: LocalDate): Boolean {
        val w = weekIndex(program, date)
        return if (program.blockWeeks > 0) w % program.blockWeeks == 0 else w % 4 == 0
    }

    /** RIR shift inside a block: easier first week, harder before the deload. */
    fun rirOffset(program: Program, date: LocalDate): Int {
        if (program.blockWeeks <= 0) return 0
        val pos = (weekIndex(program, date) - 1) % program.blockWeeks + 1
        return when {
            pos == 1 -> 1
            pos == program.blockWeeks - 1 -> -1
            else -> 0
        }
    }
}
