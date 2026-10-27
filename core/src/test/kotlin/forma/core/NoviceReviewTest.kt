package forma.core

import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.model.Sex
import forma.core.plan.PlanAdvisor
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.plan.Templates
import forma.core.strength.DoneSet
import forma.core.strength.OneRm
import forma.core.strength.Outcome
import forma.core.strength.Progression
import forma.core.strength.SlotRole
import forma.core.strength.SlotSpec
import forma.core.strength.SlotState
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The first weeks of a total beginner (a review of the path from installing the app to the first sessions): what the
 * templates hand a novice, the first weights of a light woman on barbell lifts, the first session's volume, and a week
 * laid out by hand. Each test failed on the code before its fix.
 */
class NoviceReviewTest {
    private val monday = LocalDate.of(2026, 10, 26)

    /** A novice who runs seriously: split squats on the one-leg slot; the Bulgarian (hard, balance to learn) comes with experience. */
    @Test fun aNoviceRunnerSplitSquatsBeforeTheBulgarian() {
        val aim = Aim(LiftFocus.STRENGTH, RunFocus.ENDURANCE, Emphasis.BALANCED)
        val novice = (Templates.forWeek(aim, Experience.NOVICE, lifts = 2) + Templates.forWeek(aim, Experience.NOVICE, lifts = 4))
            .flatMap { t -> t.slots.map { it.exerciseId } }
        assertTrue(novice.none { it in PlanAdvisor.HARD }, "a novice's templates: $novice")
        assertTrue("split_squat" in novice, "the one-leg slot keeps a one-leg squat: $novice")
        val seasoned = Templates.forWeek(aim, Experience.INTERMEDIATE, lifts = 2).flatMap { t -> t.slots.map { it.exerciseId } }
        assertTrue("bulgarian" in seasoned, "with experience the Bulgarian stays: $seasoned")
    }

    private fun woman(kg: Double, lift: LiftFocus = LiftFocus.GENERAL, exp: Experience = Experience.NOVICE) = Profile(
        sex = Sex.FEMALE, birthYear = 2001, bodyWeightKg = kg, experience = exp, runLevel = RunLevel.NONE,
        trainingDays = setOf(1, 3, 5), aim = Aim(lift, RunFocus.NONE, Emphasis.GYM),
    )

    /**
     * A light woman who never lifted: the empty bar (20 kg) is twice what she is guessed to press overhead (about 9 kg)
     * and the bar cannot go lighter. Her plan has the dumbbell press in its place, and the week says why.
     */
    @Test fun aLightBeginnerPressesDumbbellsWhereTheEmptyBarIsTooHeavy() {
        val p = ProgramGenerator.generate(woman(50.0), monday)
        val ids = p.templates.flatMap { t -> t.slots.map { it.exerciseId } }
        assertTrue("ohp" !in ids && "db_shoulder" in ids, "the overhead press for a 50-kg novice woman: $ids")
        assertTrue("bench" !in ids && "db_bench" in ids, "the bench (guess 13.5 kg against the 20-kg bar): $ids")
        // The squat and the Romanian deadlift are within reach of the bar (legs and hips are strong): they stay.
        assertTrue("squat" in ids && "rdl" in ids, "$ids")
        val why = p.week.notes.joinToString(" ")
        assertTrue("Жим гантелей сидя" in why && "гриф" in why, "the week says why: ${p.week.notes}")
        // A stronger beginner, one who wants strength, and one who knows her weights keep the barbell.
        val man = ProgramGenerator.generate(woman(80.0).copy(sex = Sex.MALE), monday).templates.flatMap { t -> t.slots.map { it.exerciseId } }
        assertTrue("ohp" in man && "bench" in man, "$man")
        val strength = ProgramGenerator.generate(woman(50.0, LiftFocus.STRENGTH), monday).templates.flatMap { t -> t.slots.map { it.exerciseId } }
        assertTrue("ohp" in strength && "bench" in strength, "a strength goal is the barbell lifts: $strength")
        val seasoned = ProgramGenerator.generate(woman(50.0, exp = Experience.INTERMEDIATE), monday).templates.flatMap { t -> t.slots.map { it.exerciseId } }
        assertTrue("ohp" in seasoned, "$seasoned")
    }

    /**
     * Calibrated at the empty bar with fewer reps in her than the range asks: the target is the reps she can do there
     * (the bar cannot be lighter), built up from there — not 8 a session that she cannot reach.
     */
    @Test fun calibratedAtTheBarTheTargetIsTheRepsSheHasThere() {
        val ohp = Catalog["ohp"]
        val bw = 50.0
        val spec = SlotSpec("ohp", 3, 8, 12, 2, 120, SlotRole.MAIN)
        val hint = Progression.startHint(ohp, bw, female = true, trained = 1.0)!!
        val p = Progression.prescribe(ohp, spec, SlotState.fresh(spec), bodyWeight = bw, hint = hint)
        val ev = Progression.evaluate(ohp, spec, SlotState.fresh(spec), p, listOf(DoneSet(20.0, 4), DoneSet(20.0, 3), DoneSet(20.0, 3)), bw, novice = true)
        val out = assertIs<Outcome.Calibrated>(ev.outcome)
        assertEquals(20.0, out.weight)
        val rtf = OneRm.rtfAt(ev.e1rm!!, out.weight)
        assertTrue(out.reps + spec.rir <= rtf + 0.5, "target ${out.reps} at the bar with ${spec.rir} in reserve; reps to failure there ${"%.1f".format(rtf)}")
        assertEquals(out.reps, ev.state.reps)
        // Someone strong enough for the range keeps the range's bottom as before.
        val ev2 = Progression.evaluate(ohp, spec, SlotState.fresh(spec), p, listOf(DoneSet(30.0, 10)), 80.0)
        assertEquals(8, assertIs<Outcome.Calibrated>(ev2.outcome).reps)
    }

    /**
     * A novice's first time on an exercise: two sets, three reps short of failure — a beginner cannot yet tell "two left",
     * and the first exposure is the one that makes muscles sore. Later sessions are the plan's.
     */
    @Test fun aNovicesFirstTimeOnAnExerciseIsTwoEasySets() {
        val squat = Catalog["squat"]
        val spec = SlotSpec("squat", 3, 6, 10, 2, 150, SlotRole.MAIN)
        val first = Progression.prescribe(squat, spec, SlotState.fresh(spec), bodyWeight = 60.0, hint = 30.0, novice = true)
        assertTrue(first.calibrate)
        assertEquals(2, first.sets)
        assertEquals(3, first.rir)
        // The calibration reads an unrated set as stopped where told (three left).
        assertEquals(3, Progression.assumedRir(DoneSet(30.0, 10), 0, first))
        val seasoned = Progression.prescribe(squat, spec, SlotState.fresh(spec), bodyWeight = 60.0, hint = 30.0)
        assertEquals(3, seasoned.sets)
        assertEquals(2, seasoned.rir)
        // Once the weight is known, the plan's sets and reserve.
        val known = Progression.prescribe(squat, spec, SlotState(32.5, 6, 0, 0, 1, true), bodyWeight = 60.0, novice = true)
        assertEquals(3, known.sets)
        assertEquals(2, known.rir)
    }

    /**
     * A beginner who lays out five gym days by hand gets them (the choice is theirs), and the week says plainly that three
     * are enough at the start: more is more tiredness, hardly more growth.
     */
    @Test fun aBeginnersFiveGymDaysAreKeptAndTheWeekSaysThreeAreEnough() {
        val aim = Aim(LiftFocus.MUSCLE, RunFocus.NONE, Emphasis.GYM)
        val profile = Profile(
            birthYear = 2009, bodyWeightKg = 60.0, experience = Experience.NOVICE, runLevel = RunLevel.NONE, aim = aim,
            trainingDays = setOf(1, 2, 3, 4, 5), gymDays = setOf(1, 2, 3, 4, 5), runDays = emptySet(),
        )
        val program = ProgramGenerator.generate(profile, monday)
        assertEquals(5, program.week.days.sumOf { d -> d.sessions.count { it.kind == SessionKind.LIFT } })
        assertTrue(program.week.notes.any { "3 тренировок" in it }, "${program.week.notes}")
        // Three days: nothing to say.
        val three = ProgramGenerator.generate(profile.copy(gymDays = setOf(1, 3, 5), trainingDays = setOf(1, 3, 5)), monday)
        assertTrue(three.week.notes.none { "3 тренировок" in it }, "${three.week.notes}")
    }
}
