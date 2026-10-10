package forma.core.plan

import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.run.RunCaps
import forma.core.run.RunState
import forma.core.run.Vdot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

object ProgramGenerator {
    fun generate(profile: Profile, today: LocalDate): Program {
        val days = profile.trainingDays.filter { it in 1..7 }.toSet().ifEmpty { setOf(1, 3, 5) }
        val mix = Scheduler.mix(profile.goal, days.size, profile.experience, profile.runLevel)
        val templates = Templates.forWeek(profile.goal, profile.experience, mix.lifts)
            .map { Templates.fit(it, profile.sessionMinutes) }
        val week = Scheduler.schedule(days, mix, templates, profile.goal)
        val block = when (profile.experience) {
            Experience.NOVICE -> 0
            Experience.INTERMEDIATE -> 5
            Experience.ADVANCED -> 4
        }
        val caps = when (profile.goal) {
            Goal.ENDURANCE -> RunCaps(easyMax = 60, longMax = 120)
            Goal.HYBRID -> RunCaps(easyMax = 45, longMax = 90)
            Goal.FITNESS -> RunCaps(easyMax = 45, longMax = 60)
            Goal.STRENGTH, Goal.MUSCLE -> RunCaps(easyMax = 35, longMax = 50)
        }
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return Program(profile.goal, profile.experience, templates, week, block, caps, monday.toEpochDay())
    }

    /** Starting point of the running ladders from what the person told about their running. */
    fun initialRunState(profile: Profile): RunState {
        val vdot = if (profile.raceMeters != null && profile.raceSeconds != null && profile.raceMeters > 0 && profile.raceSeconds > 0) {
            (Vdot.fromRace(profile.raceMeters, profile.raceSeconds) * 2).toInt() / 2.0
        } else null
        return when (profile.runLevel) {
            RunLevel.NONE -> RunState(vdot = vdot, walkStage = 0)
            RunLevel.BASE -> RunState(vdot = vdot, walkStage = -1, easyMinutes = 25, longMinutes = 40)
            RunLevel.TRAINED -> RunState(vdot = vdot, walkStage = -1, easyMinutes = 35, longMinutes = 60, intervalStage = 2, tempoStage = 2)
        }
    }

    /** 1-based week of the program for [date]. */
    fun weekIndex(program: Program, date: LocalDate): Int =
        ((date.toEpochDay() - program.startEpochDay).coerceAtLeast(0) / 7).toInt() + 1

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
