package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.LiftItem
import forma.app.data.LiftPlan
import forma.app.data.LiftSummary
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.strength.DoneSet
import forma.core.strength.HoldReason
import forma.core.strength.Outcome
import forma.core.strength.Progression
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Weight selection replayed on one real lifter's five gym sessions (13.10–27.10.2026): the sets as logged, his
 * "reps left", his steps, through the real repository. The replay reproduced every plan his phone stored on cac12ca
 * (the engine then); each finding of the review there is a probe here, fixed since. The sanity probes show where the
 * engine does what a coach would do.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class WeightsReviewTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    /** Monday 12 Oct 2026: the block's first week holds 13.10 and 15.10 (intro), week 2 holds 22–23.10, week 3 (peak) 27.10. */
    private val monday = LocalDate.of(2026, 10, 12)
    private var clock = 0L

    private fun at(d: LocalDate, hour: Int = 9) { clock = d.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli() }
    private fun at(d: LocalDate, t: String) { clock = d.atTime(LocalTime.parse(t)).toInstant(ZoneOffset.UTC).toEpochMilli() }

    private val denis = Profile(
        name = "Денис", birthYear = 2008, goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE,
        trainingDays = setOf(1, 2, 4, 6), bodyWeightKg = 71.0, lapMeters = 230, runGoal = RunGoal(1500, 330),
        aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
        doubleDays = true, gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
    )
    private val bw = 71.0

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "weights-review")
        store.clear()
        at(monday)
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(denis)
        repo.updateSettings {
            it.copy(
                swaps = mapOf(
                    "bench" to "chest_press", "db_incline" to "chest_press", "db_shoulder" to "db_bench", "ohp" to "machine_shoulder_press",
                    "row" to "pullup", "db_curl" to "db_overhead_ext", "pushdown" to "machine_lateral", "bulgarian" to "leg_ext",
                ),
                // His stored plans: the dumbbell press 2 × 6–10 with 180 s, the pulldown 2 sets (edits of the plan).
                sets = mapOf("db_shoulder" to 2, "lat_pulldown" to 2),
                reps = mapOf("db_shoulder" to listOf(6, 10)),
                rest = mapOf("db_shoulder" to 180),
            )
        }
        assertEquals(monday.toEpochDay(), repo.store.programNow()!!.startEpochDay)
    }

    @After fun tearDown() = db.close()

    // ---------- his sessions ----------

    /** One logged working set: position in the day, weight, reps, "reps left" (null = not rated), time (UTC). */
    private data class S(val pos: Int, val w: Double, val reps: Int, val rir: Int?, val t: String)

    private data class Session(val day: LocalDate, val template: String, val rpe: Int, val sets: List<S>)

    private val d13 = LocalDate.of(2026, 10, 13)
    private val d15 = LocalDate.of(2026, 10, 15)
    private val d22 = LocalDate.of(2026, 10, 22)
    private val d23 = LocalDate.of(2026, 10, 23)
    private val d27 = LocalDate.of(2026, 10, 27)
    /** The next sessions of the block's week 3 (no deload yet). */
    private val nextA = LocalDate.of(2026, 10, 29)
    private val nextB = LocalDate.of(2026, 10, 31)

    private val sessions = listOf(
        Session(d13, "upper_b", 7, listOf(
            S(2, 80.0, 10, 4, "10:58:35"), S(2, 90.0, 9, 3, "11:02:20"), S(2, 95.0, 8, 0, "11:06:16"),
            S(0, 60.0, 10, 2, "11:13:35"), S(0, 70.0, 6, 1, "11:17:43"), S(0, 70.0, 6, 1, "11:21:27"),
            S(1, 20.0, 10, 1, "11:30:31"), S(1, 30.0, 5, 0, "11:35:25"), S(1, 25.0, 6, 1, "11:42:29"),
            S(4, 12.5, 14, 5, "11:47:41"), S(4, 15.0, 9, 2, "11:49:58"),
            S(5, 25.0, 8, null, "11:53:53"), S(5, 22.5, 10, 1, "11:59:11"),
            S(3, 64.0, 10, 5, "12:05:25"), S(3, 76.0, 8, 2, "12:07:41"), S(3, 76.0, 7, 0, "12:11:22"),
        )),
        Session(d15, "upper_a", 6, listOf(
            S(0, 90.0, 5, 4, "12:18:32"), S(0, 90.0, 5, 5, "12:21:37"), S(0, 95.0, 6, 3, "12:25:30"),
            S(1, 25.0, 5, 4, "12:30:50"), S(1, 25.0, 5, 5, "12:36:58"), S(1, 30.0, 5, 3, "12:42:09"),
            S(2, 30.0, 8, 0, "12:49:23"), S(2, 30.0, 6, 3, "12:55:39"),
            S(3, 92.0, 7, 1, "13:01:02"), S(3, 84.0, 8, 1, "13:04:05"),
            S(4, 24.0, 10, 5, "13:08:43"), S(4, 27.5, 10, 3, "13:11:45"),
            S(5, 24.0, 10, 5, "13:15:07"), S(5, 24.0, 10, 2, "13:17:31"),
        )),
        Session(d22, "upper_a", 4, listOf(
            S(0, 100.0, 5, 4, "10:56:00"), S(0, 105.0, 5, 3, "10:59:56"), S(0, 105.0, 6, 3, "11:06:25"),
            S(1, 25.0, 7, 3, "11:15:45"), S(1, 25.0, 7, 2, "11:20:49"), S(1, 25.0, 8, 0, "11:26:11"),
            S(2, 30.0, 6, 2, "11:32:37"), S(2, 30.0, 6, 1, "11:37:37"),
            S(3, 85.0, 8, 5, "11:42:56"), S(3, 92.0, 8, 0, "11:46:42"),
            S(4, 30.0, 10, 4, "11:51:19"), S(4, 30.0, 10, 3, "12:01:41"),
            S(5, 24.0, 10, 2, "12:08:17"), S(5, 24.0, 10, 2, "12:14:54"),
        )),
        Session(d23, "lower_a", 5, listOf(
            S(0, 90.0, 5, 2, "12:20:26"), S(0, 90.0, 5, 3, "12:24:12"), S(0, 95.0, 5, 1, "12:29:51"),
            S(3, 70.0, 10, 5, "12:44:34"), S(3, 80.0, 8, 5, "12:44:47"), S(3, 90.0, 8, 2, "12:44:54"),
            S(2, 70.0, 10, 4, "12:47:19"), S(2, 80.0, 8, 3, "12:50:37"), S(2, 80.0, 8, 2, "12:58:00"),
        )),
        Session(d27, "upper_b", 7, listOf(
            S(0, 70.0, 9, 4, "11:23:45"), S(0, 75.0, 8, 2, "11:28:13"), S(0, 75.0, 6, 2, "11:31:34"),
            S(1, 25.0, 8, 3, "11:38:55"), S(1, 30.0, 7, 0, "11:43:11"), S(1, 30.0, 6, 0, "11:48:50"),
            S(2, 85.0, 8, 4, "11:58:09"), S(2, 90.0, 8, 2, "12:01:07"), S(2, 90.0, 8, 0, "12:05:43"),
            S(3, 64.0, 9, 5, "12:11:31"), S(3, 72.0, 8, 5, "12:14:00"), S(3, 76.0, 10, 4, "12:17:39"),
            S(5, 22.5, 10, 2, "12:26:15"), S(5, 22.5, 9, 0, "12:26:19"),
            S(4, 24.0, 11, 2, "12:32:07"), S(4, 24.0, 10, 0, "12:34:30"),
        )),
    )

    private val plans = mutableMapOf<LocalDate, LiftPlan>()
    private val summaries = mutableMapOf<LocalDate, LiftSummary>()

    private fun LiftPlan.at(pos: Int): LiftItem = items.first { it.position == pos }
    private fun LiftPlan.of(exerciseId: String): LiftItem = items.first { it.exerciseId == exerciseId }
    private fun Outcome?.short() = when (this) {
        is Outcome.Hold -> "Hold($reason, $weight × $reps)"
        null -> "none"
        else -> toString()
    }

    private suspend fun play(s: Session) {
        at(s.day, s.sets.first().t)
        clock -= 10 * 60 * 1000
        val id = repo.startLift(s.template, s.day)
        val plan = repo.planOf(db.workouts().get(id)!!)!!
        plans[s.day] = plan
        for (x in s.sets) {
            at(s.day, x.t)
            repo.logSet(id, plan.at(x.pos), x.w, x.reps, x.rir)
        }
        if (s.day == d27) {
            // His steps, set during that session: 4 kg stacks, 5 kg on the pull-up belt.
            repo.setStep("machine_lateral", 4.0, 2.5)
            repo.setStep("pullup", 5.0, 2.5)
            repo.setStep("cable_row", 4.0, 5.0)
            repo.setStep("lat_pulldown", 4.0, 5.0)
        }
        clock += 3 * 60 * 1000
        summaries[s.day] = repo.finishLift(id, s.rpe)
    }

    /** All five sessions in order; the machine lateral raise takes the dumbbell one's place before 27.10. */
    private suspend fun replay(upTo: LocalDate = d27) {
        for (s in sessions) {
            if (s.day > upTo) break
            if (s.day == d27) repo.updateSettings { it.copy(swaps = it.swaps + ("lateral_raise" to "machine_lateral")) }
            play(s)
        }
    }

    private fun outcome(day: LocalDate, exerciseId: String): Outcome? = summaries[day]?.reports?.firstOrNull { it.exerciseId == exerciseId }?.outcome

    private fun LiftItem.wr() = "${prescription.weight} × ${prescription.reps} (rir ${prescription.rir}${if (prescription.calibrate) ", calibrate" else ""})"

    private fun trace(): String = buildString {
        for (s in sessions) {
            val plan = plans[s.day] ?: continue
            appendLine("${s.day} ${s.template}:")
            for (item in plan.items.sortedBy { it.position }) {
                val done = s.sets.filter { it.pos == item.position }.joinToString(", ") { "${it.w}×${it.reps} rir ${it.rir ?: "-"}" }
                if (done.isEmpty()) continue
                val sl = item.slot
                appendLine("  [${item.position}] ${item.exerciseId} ${sl.role} ${item.prescription.sets}×${sl.repMin}-${sl.repMax} planned ${item.wr()} step ${item.step}; did $done -> ${outcome(s.day, item.exerciseId).short()}")
            }
        }
    }

    // ---------- findings of the review (fixed) ----------

    /**
     * Pull-ups: prescribed 22.5 kg on 15.10, 22.10 and 27.10; every one of his nine sets was at 25–30 kg
     * (15.10: 25×5 rir4, 25×5 rir5, 30×5 rir3; 22.10: 25×7 rir3, 25×7 rir2, 25×8 rir0; 27.10: 25×8 rir3, 30×7 rir0, 30×6 rir0).
     * The engine reads the reps as if done at the planned weight, so the weight never moves up.
     */
    @Test fun pullUpsDoneHeavierThanPlannedAreCredited() = runBlocking<Unit> {
        replay()
        val next = repo.liftPlan("upper_a", nextA).of("pullup")
        val st = db.state().slot("pullup@5-8")!!
        assertTrue(
            next.prescription.weight!! >= 25.0,
            "pull-ups: 22.5 × 5 → 22.5 × 7 → 22.5 × 7 while he did 25/25/30, 25/25/25, 25/30/30; " +
                "outcomes ${outcome(d15, "pullup").short()}, ${outcome(d22, "pullup").short()}, ${outcome(d27, "pullup").short()}; " +
                "next: ${next.wr()} (state ${st.weight} × ${st.reps}, stalls ${st.stalls}, steady ${st.steady})",
        )
    }

    /**
     * Main chest press (5–8): 22.10 planned 100 × 5 (rir 2); he did 100×5 rir4, 105×5 rir3, 105×6 rir3 — the "+" set a
     * step above the plan with 3 in reserve. The next plan must not be under 100; 105 is what the "+" set showed.
     */
    @Test fun mainChestPressDoneHeavierIsCredited() = runBlocking<Unit> {
        replay()
        val next = repo.liftPlan("upper_a", nextA).of("chest_press")
        val stat = db.state().stat("chest_press")!!
        val msg = "chest press 5–8: planned 100 × 5, did 100×5 rir4, 105×5 rir3, 105×6 rir3 → ${outcome(d22, "chest_press").short()}; " +
            "next ${next.wr()}; stat e1rm ${"%.1f".format(stat.e1rm)} best ${"%.1f".format(stat.best)}"
        println(msg)
        assertTrue(next.prescription.weight!! >= 100.0, msg)
    }

    /**
     * Shoulder press 27.10: planned 70 × 5 (rir 1); 70×9 rir4 → hint 75 → 75×8 rir2, 75×6 rir2. Two sets at 75 with reps
     * and reserve to spare, and the next plan goes back to 70.
     */
    @Test fun shoulderPressNextSessionStartsFromTheWeightHeShowed() = runBlocking<Unit> {
        replay()
        val next = repo.liftPlan("upper_b", nextB).of("machine_shoulder_press")
        assertTrue(
            next.prescription.weight!! >= 75.0,
            "shoulder press: planned 70 × 5 rir 1, did 70×9 rir4, 75×8 rir2, 75×6 rir2 → ${outcome(d27, "machine_shoulder_press").short()}; next ${next.wr()}",
        )
    }

    /**
     * In-session hint, shoulder press 27.10: after 70×9 rir4 the hint was 75 (the +10 % bound of the plan's 70). After
     * 75×8 rir2 — four reps of reserve beyond the plan at the hinted weight — the last set should go up again (80); the
     * bound is anchored to the plan's weight, so it stays at 75.
     */
    @Test fun inSessionHintMovesAgainWhenTheHintedWeightIsStillEasy() = runBlocking<Unit> {
        replay(upTo = d23)
        val item = repo.liftPlan("upper_b", d27).of("machine_shoulder_press")
        val ex = Catalog["machine_shoulder_press"]
        val first = Progression.nextSet(ex, item.prescription, listOf(DoneSet(70.0, 9, 4)), bw)!!
        assertEquals(75.0, first.weight, 1e-9, "after 70×9 rir4 (plan ${item.wr()})")
        val second = Progression.nextSet(ex, item.prescription, listOf(DoneSet(70.0, 9, 4), DoneSet(75.0, 8, 2)), bw)!!
        assertTrue(
            second.weight >= 80.0,
            "plan ${item.wr()}: after 70×9 rir4 → ${first.weight} × ${first.reps}; after 75×8 rir2 (reserve ${second.reserve} vs ${second.plannedReserve} planned) → ${second.weight} × ${second.reps}",
        )
    }

    /**
     * Cable row calibration 13.10: 64×10 rir5, 76×8 rir2, 76×7 rir0 → the working weight came out 65 × 8. The engine
     * trusts the set with the fewest reps to failure — the third, tired set at 76 — not the fresh 76×8 with 2 left,
     * which is the target itself (8 reps, 2 in reserve).
     */
    @Test fun calibrationTakesTheFreshSetNotTheTiredRepeat() = runBlocking<Unit> {
        replay(upTo = d23)
        val row = repo.liftPlan("upper_b", d27).of("cable_row")
        val o = outcome(d13, "cable_row")
        assertTrue(
            row.prescription.weight!! >= 75.0,
            "cable row after 64×10 rir5, 76×8 rir2, 76×7 rir0: $o → prescribed ${row.wr()}; chest press 8–10 after 80×10 rir4, 90×9 rir3, 95×8 rir0: ${outcome(d13, "chest_press")}",
        )
    }

    /**
     * Cable row 27.10: planned 65 × 8 — a weight his 4 kg stack does not have, so he loaded 64 (then 72×8 rir5, 76×10 rir4).
     * The 64 is read as "lighter than planned" → a miss → "stopped early" → the weight holds at 65 (64 on his new grid)
     * although the "+" set showed 10 reps with 4 to spare at 76.
     */
    @Test fun aSetOneKgUnderAnOffGridPlanIsNotAMiss() = runBlocking<Unit> {
        replay()
        val o = outcome(d27, "cable_row")
        val next = repo.liftPlan("upper_b", nextB).of("cable_row")
        val plan = plans[d27]!!.of("cable_row")
        assertTrue(
            next.prescription.weight!! >= 72.0,
            "cable row: planned ${plan.wr()}, did 64×9 rir5, 72×8 rir5, 76×10 rir4 → ${o.short()}; next ${next.wr()} on step ${next.step}",
        )
    }

    /**
     * Machine lateral raise: 24 kg in every set of three sessions (the 2.5 kg plan said 22.5, the stack goes by 4).
     * With the step set to 4 the stored 22.5 is floored to 20 — a notch below what he has been lifting all along.
     */
    @Test fun aChangedStepDoesNotFloorBelowWhatHeLifts() = runBlocking<Unit> {
        replay()
        val next = repo.liftPlan("upper_a", nextA).of("machine_lateral")
        val st = db.state().slot("machine_lateral@10-15")!!
        assertTrue(
            next.prescription.weight!! >= 24.0,
            "machine lateral: 24×10 rir5, 24×10 rir2 | 24×10 rir2 ×2 | 24×11 rir2, 24×10 rir0 (plans 22.5 × 10, 22.5 × 10, 22.5 × 11) → " +
                "${outcome(d27, "machine_lateral").short()}; stored ${st.weight} × ${st.reps}; step 4 → next ${next.wr()}",
        )
    }

    /** Every prescribed weight on the exercises he set a step for sits on that step's grid. */
    @Test fun prescriptionsSitOnHisGrid() = runBlocking<Unit> {
        replay()
        val a = repo.liftPlan("upper_a", nextA)
        val b = repo.liftPlan("upper_b", nextB)
        val checks = listOf(
            "pullup" to (a.of("pullup") to 5.0), "cable_row" to (b.of("cable_row") to 4.0),
            "lat_pulldown" to (a.of("lat_pulldown") to 4.0), "machine_lateral" to (b.of("machine_lateral") to 4.0),
        )
        val off = checks.filter { (_, p) -> val w = p.first.prescription.weight!!; abs(w / p.second - Math.rint(w / p.second)) > 1e-6 }
        assertTrue(off.isEmpty(), "off his grid: " + off.joinToString { (id, p) -> "$id ${p.first.wr()} (step ${p.second})" })
        // And the dumbbell exercises: his rack goes by 2.5 kg (22.5, 25, 27.5, 30), the catalogue's step is 2 kg.
        println("dumbbells next: hammer ${b.of("hammer_curl").wr()}, overhead ext ${a.of("db_overhead_ext").wr()}, db bench ${a.of("db_bench").wr()}")
    }

    // ---------- what works (expected to pass) ----------

    /**
     * Lat pulldown 22.10: 85×8 "5 left" then 92×8 "0 left" — the noisy high-reserve report does not pull the weight up
     * (the "+" set at 92 had nothing to spare); dumbbell bench 30×6 rir2, 30×6 rir1 holds; overhead extension
     * 30×10 rir4, 30×10 rir3 against a plan of 28 goes to 30.
     */
    @Test fun noisyHighReserveReportsAreNotChased() = runBlocking<Unit> {
        replay()
        val a = repo.liftPlan("upper_a", nextA)
        val pd = a.of("lat_pulldown")
        assertTrue(pd.prescription.weight!! in 84.0..88.0, "lat pulldown after 85×8 rir5, 92×8 rir0 → ${outcome(d22, "lat_pulldown").short()}; next ${pd.wr()}")
        val dbb = a.of("db_bench")
        assertEquals(30.0 to 6, dbb.prescription.weight to dbb.prescription.reps, "db bench after 30×6 rir2, 30×6 rir1 → ${outcome(d22, "db_bench").short()}")
        val ext = a.of("db_overhead_ext")
        assertTrue(ext.prescription.weight!! in 30.0..32.0, "overhead ext after 30×10 rir4, 30×10 rir3 (plan 28) → ${outcome(d22, "db_overhead_ext").short()}; next ${ext.wr()}")
    }

    /** No "reps left" correction kicks in on three pairs (needs four), so his reports are read as given. */
    @Test fun reserveBiasStaysOffWithFewPairs() = runBlocking<Unit> {
        replay()
        assertEquals(0, repo.rirBias())
    }

    /** The in-session hint on the pull-ups 27.10: 25×8 rir3 against 22.5 × 7 rir 1 → 30 (the +10 % of the total load). */
    @Test fun pullUpHintGoesUpWithinTenPercentOfTotalLoad() = runBlocking<Unit> {
        replay(upTo = d23)
        val item = repo.liftPlan("upper_b", d27).of("pullup")
        val planned = item.prescription.weight!!
        val ns = Progression.nextSet(Catalog["pullup"], item.prescription, listOf(DoneSet(25.0, 8, 3)), bw)!!
        val m = "plan ${item.wr()} → after 25×8 rir3: ${ns.weight} × ${ns.reps}"
        assertTrue(ns.weight > planned && ns.weight - planned <= 0.1 * (bw + planned) + 1e-9, m)
        val after = Progression.nextSet(Catalog["pullup"], item.prescription, listOf(DoneSet(25.0, 8, 3), DoneSet(ns.weight, 7, 0)), bw)!!
        assertEquals(ns.weight, after.weight, 1e-9, "$m; after ${ns.weight}×7 rir0: ${after.weight} × ${after.reps} (reserve ${after.reserve})")
    }

    /** The secondary chest press 27.10: 85×8 rir4 → hint 90; 90×8 rir2, 90×8 rir0 → the weight holds (nothing to spare at 90). */
    @Test fun secondaryChestPressHoldsAfterAGrindAtNinety() = runBlocking<Unit> {
        replay()
        val next = repo.liftPlan("upper_b", nextB).of("chest_press")
        val o = outcome(d27, "chest_press")
        assertTrue(o is Outcome.Hold && o.reason == HoldReason.CONSOLIDATE, "chest press 8–10: ${o.short()}; next ${next.wr()}")
        assertTrue(next.prescription.weight!! in 85.0..90.0, "next ${next.wr()}")
    }
}
