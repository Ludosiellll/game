package forma.core

import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Gear
import forma.core.model.LiftFocus
import forma.core.model.Pattern
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.plan.PlanAdvisor
import forma.core.plan.Picks
import forma.core.plan.Program
import forma.core.plan.ProgramGenerator
import forma.core.strength.SlotRole
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** A review of «Мои упражнения» and the home plans (each test failed on 1563b2a and is a finding fixed): the person's own choices kept, no double in a session, honest notes, home plans that keep their shape. */
class PicksReviewTest {
    private val monday = LocalDate.of(2026, 10, 26)

    private fun profile(
        lift: LiftFocus = LiftFocus.MUSCLE, exp: Experience = Experience.INTERMEDIATE, days: Set<Int> = setOf(1, 2, 4, 5),
        gear: Gear = Gear.GYM, bar: Boolean = false, run: RunFocus = RunFocus.NONE,
    ) = Profile(
        birthYear = 2000, bodyWeightKg = 75.0, experience = exp, runLevel = if (run == RunFocus.NONE) RunLevel.NONE else RunLevel.BASE,
        trainingDays = days, sessionMinutes = 90, aim = Aim(lift, run, if (run == RunFocus.NONE) Emphasis.GYM else Emphasis.BALANCED),
        gear = gear, pullupBar = bar,
    )

    private fun places(p: Program, swaps: Map<String, String> = emptyMap()) =
        p.templates.flatMap { t -> t.slots.map { Picks.Place(t.id, it.exerciseId, swaps[it.exerciseId] ?: it.exerciseId, it.role) } }

    private fun done(p: Program, r: Picks.Result, swaps: Map<String, String> = emptyMap()) =
        p.templates.associate { t -> t.title to t.slots.map { r.swaps[it.exerciseId] ?: swaps[it.exerciseId] ?: it.exerciseId } }

    /** One liked squat, two squat places in one session (Низ · А: squat + leg_press): the like lands on both. */
    @Test fun oneLikeForAMovementWithTwoPlacesInOneSessionIsPutTwice() {
        val p = ProgramGenerator.generate(profile(), monday)
        val lowerA = p.templates.first { it.id == "lower_a" }
        assertEquals(2, lowerA.slots.count { Catalog[it.exerciseId].pattern == Pattern.SQUAT }, lowerA.slots.map { it.exerciseId }.toString())
        val r = Picks.resolve(places(p), mapOf(Pattern.SQUAT to listOf("hack_squat")), emptySet(), Picks.Rules())
        val d = done(p, r)
        d.forEach { (title, ids) ->
            assertEquals(ids.distinct().size, ids.size, "$title has the same exercise twice in one session: $ids (swaps ${r.swaps}) — " +
                "one like for a movement with two places in a session is put in both, the fallback `?: fit[k % fit.size]` ignores `others`")
        }
    }

    /** Liking the plan's own exercise (squat for the squat movement) also doubles it: leg_press becomes a second squat. */
    @Test fun likingThePlansOwnExerciseDoublesItInTheSession() {
        val p = ProgramGenerator.generate(profile(), monday)
        val r = Picks.resolve(places(p), mapOf(Pattern.SQUAT to listOf("squat")), emptySet(), Picks.Rules())
        val d = done(p, r)
        assertEquals(null, r.swaps["leg_press"], "liking the plan's own squat turns leg_press into a second squat in Низ · А: ${d["Низ · А"]}")
    }

    /** A liked isolation exercise for a main/second place plus «не хочу» on the current one: the note says kept, the place is replaced. */
    @Test fun theKeptNoteLiesWhenTheCurrentIsAlsoDisliked() {
        val p = ProgramGenerator.generate(profile(), monday)
        val r = Picks.resolve(places(p), mapOf(Pattern.VERTICAL_PULL to listOf("straight_arm_pulldown")), setOf("lat_pulldown"), Picks.Rules())
        val kept = r.notes.filter { "оставил его" in it && "Тяга верхнего блока" in it }
        assertTrue(
            kept.isEmpty() || r.swaps["lat_pulldown"] == null,
            "note says «${kept.joinToString()}» (kept lat_pulldown) but the place is replaced: lat_pulldown → ${r.swaps["lat_pulldown"]}",
        )
    }

    /** Strength aim, the squat place swapped by hand to box_squat, then box_squat marked «не хочу»: it stays, and the note says the squat is the main. */
    @Test fun strengthMainWithAHandMadeSwapKeepsTheDislikedExerciseAndSaysOtherwise() {
        val p = ProgramGenerator.generate(profile(lift = LiftFocus.STRENGTH), monday)
        val hand = mapOf("squat" to "box_squat")
        val r = Picks.resolve(places(p, hand), emptyMap(), setOf("box_squat"), Picks.Rules(strength = true))
        val d = done(p, r, hand)
        assertTrue(
            d.values.none { "box_squat" in it },
            "box_squat is «не хочу» but stays in the squat place: ${d["Низ · А"]}; notes: ${r.notes} (the note says «остаётся «Приседания со штангой»» while the place holds box_squat)",
        )
    }

    /** Same, with a like for another squat: a hand-made swap on a strength main is kept, but the note names the plan's lift as what stays. */
    @Test fun strengthNoteNamesThePlansLiftWhileAHandMadeSwapIsThere() {
        val p = ProgramGenerator.generate(profile(lift = LiftFocus.STRENGTH), monday)
        val hand = mapOf("squat" to "box_squat")
        val r = Picks.resolve(places(p, hand), mapOf(Pattern.SQUAT to listOf("hack_squat")), emptySet(), Picks.Rules(strength = true))
        val note = r.notes.firstOrNull { "Цель — сила" in it && "присед" in it.lowercase() }
        assertTrue(note == null || "Приседания на ящик" in note, "the note says the main stays «Приседания со штангой», but the place holds box_squat (hand-made): $note; ${done(p, r, hand)["Низ · А"]}")
    }

    // ---------- GearFit: the real home plans ----------

    private data class Case(val gear: Gear, val bar: Boolean, val days: Int, val exp: Experience, val lift: LiftFocus, val run: RunFocus)

    private val cases = buildList {
        for (gear in listOf(Gear.DUMBBELLS, Gear.BODY)) for (bar in listOf(false, true)) for (days in 2..5)
            for (exp in listOf(Experience.NOVICE, Experience.INTERMEDIATE))
                for (lift in listOf(LiftFocus.STRENGTH, LiftFocus.POWERBUILDING, LiftFocus.MUSCLE, LiftFocus.GENERAL))
                    for (run in listOf(RunFocus.NONE, RunFocus.ENDURANCE)) add(Case(gear, bar, days, exp, lift, run))
    }

    private fun daysOf(n: Int, run: RunFocus) = when (n) {
        2 -> setOf(1, 4)
        3 -> setOf(1, 3, 5)
        4 -> setOf(1, 2, 4, 5)
        else -> setOf(1, 2, 3, 5, 6)
    }.let { if (run == RunFocus.NONE) it else it + setOf(7) }

    @Test fun homePlansKeepTheirShape() {
        val bad = mutableListOf<String>()
        val shown = StringBuilder()
        for (c in cases) {
            val prof = profile(c.lift, c.exp, daysOf(c.days, c.run), c.gear, c.bar, c.run)
            val p = runCatching { ProgramGenerator.generate(prof, monday) }.getOrElse { bad += "$c: crashed ${it.message}"; continue }
            val tag = "${c.gear.name}${if (c.bar) "+bar" else ""} d${c.days} ${c.exp.name.take(3)} ${c.lift.name.take(4)} ${c.run.name.take(3)}"
            shown.append(tag).append('\n')
            p.templates.forEach { t -> shown.append("  ${t.title}: ${t.slots.joinToString { "${it.exerciseId}/${it.role.name.take(1)}" }}\n") }
            p.week.notes.filter { "план собран" in it }.forEach { shown.append("  note: $it\n") }
            for (t in p.templates) {
                val ids = t.slots.map { it.exerciseId }
                ids.filterNot { c.gear.fits(Catalog[it], c.bar) }.forEach { bad += "$tag ${t.title}: $it cannot be done here" }
                t.slots.filter { it.role == SlotRole.MAIN && !Catalog[it.exerciseId].compound }.forEach { bad += "$tag ${t.title}: isolation ${it.exerciseId} in a MAIN place" }
                if (c.exp == Experience.NOVICE) ids.filter { it in PlanAdvisor.HARD }.forEach { bad += "$tag ${t.title}: hard $it for a novice" }
                if (ids.distinct().size != ids.size) bad += "$tag ${t.title}: duplicate $ids"
                if (t.slots.none { it.role == SlotRole.MAIN }) bad += "$tag ${t.title}: no main place left: $ids"
            }
            // The note's pairs versus what the plan holds: «A» → «B» said, but A became something else somewhere.
            val gym = ProgramGenerator.generate(prof.copy(gear = Gear.GYM, pullupBar = false), monday)
            val note = p.week.notes.firstOrNull { "план собран" in it }
            if (note != null) {
                val said = Regex("«([^»]+)» → «([^»]+)»").findAll(note).map { it.groupValues[1] to it.groupValues[2] }.toList()
                val actual = mutableSetOf<Pair<String, String>>()
                for (gt in gym.templates) {
                    val ht = p.templates.firstOrNull { it.id == gt.id } ?: continue
                    val keep = gt.slots.map { it.exerciseId }.filter { c.gear.fits(Catalog[it], c.bar) }
                    // Walk the home slots: a kept id matches itself; anything else is a replacement of the next unfit gym slot.
                    var hi = 0
                    for (gs in gt.slots) {
                        if (gs.exerciseId in keep) { if (ht.slots.getOrNull(hi)?.exerciseId == gs.exerciseId) hi++; continue }
                        val hs = ht.slots.getOrNull(hi) ?: continue
                        if (hs.exerciseId !in keep) { actual += Catalog[gs.exerciseId].name to Catalog[hs.exerciseId].name; hi++ }
                    }
                }
                said.forEach { (a, b) ->
                    val really = actual.filter { it.first == a }.map { it.second }.toSet()
                    if (really.isNotEmpty() && b !in really) bad += "$tag note says «$a» → «$b», plan has «$a» → $really"
                    if (really.size > 1) bad += "$tag note says «$a» → «$b» but it became $really in different sessions"
                }
            }
        }
        println(shown)
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    /** At home with the body alone, a pull-up bar there: a biceps place should not be dropped when chin-ups are on the bar. */
    @Test fun aBarAtHomeGivesTheBicepsPlaceChinUpsNotNothing() {
        val p = ProgramGenerator.generate(profile(gear = Gear.BODY, bar = true), monday)
        val ids = p.templates.flatMap { t -> t.slots.map { it.exerciseId } }
        val note = p.week.notes.firstOrNull { "план собран" in it }
        assertTrue(ids.any { Catalog[it].pattern == Pattern.CURL || it == "chinup" }, "no biceps work at all though a bar is there: $ids; note: $note")
    }

    /** At home with the body alone: a triceps place has bench dips / diamond push-ups; it should not be «убрано». */
    @Test fun theBodyAloneKeepsATricepsPlace() {
        val p = ProgramGenerator.generate(profile(gear = Gear.BODY), monday)
        val note = p.week.notes.firstOrNull { "план собран" in it }
        assertTrue(note == null || "Разгибание рук на блоке" !in note.substringAfter("убрано", ""), "triceps dropped though bench_dip / diamond_pushup exist: $note")
    }

    /** A gym goal on the bench, then «дома: гантели»: the goal's lift leaves the plan and nothing says what happens to the goal. */
    @Test fun aHomePlanSaysWhatBecomesOfTheGymGoal() {
        val prof = profile(gear = Gear.DUMBBELLS).copy(liftGoal = forma.core.model.LiftGoal("bench", 100.0))
        val p = ProgramGenerator.generate(prof, monday)
        val ids = p.templates.flatMap { t -> t.slots.map { it.exerciseId } }
        val notes = p.week.notes
        assertTrue(
            "bench" in ids || notes.any { "цел" in it.lowercase() },
            "the goal «пожать 100» is on the bench; at home the bench is gone (${ids.filter { Catalog[it].pattern == Pattern.HORIZONTAL_PUSH }}) and no note mentions the goal: $notes",
        )
    }

    /** A liked exercise that the plan already holds elsewhere in the same session must not land twice via «не хочу» either. */
    @Test fun aDislikeNextToALikeDoesNotDoubleTheLike() {
        val p = ProgramGenerator.generate(profile(), monday)
        val r = Picks.resolve(places(p), mapOf(Pattern.SQUAT to listOf("hack_squat")), setOf("leg_press"), Picks.Rules())
        val d = done(p, r)
        d.forEach { (title, ids) -> if (ids.distinct().size != ids.size) fail("$title: $ids (swaps ${r.swaps})") }
    }
}
