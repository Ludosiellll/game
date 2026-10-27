package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.Texts
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Muscle
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.PlanAdvisor
import forma.core.plan.SessionKind
import forma.core.plan.Templates
import forma.core.strength.NextSet
import forma.core.strength.RestAdvice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rest and session-structure probes on Denis's real-like plan (POWERBUILDING + 1500 m, ADVANCED, gym Tue/Thu/Sat, runs
 * Mon/Thu/Sat, his swaps): the week's layout against his runs, the weekly sets the plan gives a runner's calves against
 * the advisor's own floor, the rest learned from his chest-press sets replayed by their timestamps, and the words after
 * a set heavier than planned. Each failing assertion is one finding.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RestReviewTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    /** Monday 12 Oct 2026: his five sessions fall in block weeks 1–3. */
    private val monday = LocalDate.of(2026, 10, 12)
    private var clock = 0L

    private fun at(d: LocalDate, t: LocalTime) { clock = d.atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    private fun at(d: LocalDate, hour: Int = 9) = at(d, LocalTime.of(hour, 0))

    private val denis = Profile(
        name = "Денис", goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
        bodyWeightKg = 71.0, lapMeters = 230, runGoal = RunGoal(1500, 330), aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
        doubleDays = true, gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
    )

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "rest-review")
        store.clear()
        at(monday)
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(denis)
        repo.updateSettings {
            it.copy(
                swaps = mapOf(
                    "bench" to "chest_press", "db_incline" to "chest_press", "db_shoulder" to "db_bench", "ohp" to "machine_shoulder_press",
                    "row" to "pullup", "lateral_raise" to "machine_lateral", "db_curl" to "db_overhead_ext", "pushdown" to "machine_lateral",
                    "bulgarian" to "leg_ext",
                ),
                removed = setOf("plank"),
            )
        }
        assertEquals(monday.toEpochDay(), repo.store.programNow()!!.startEpochDay)
    }

    @After fun tearDown() = db.close()

    private suspend fun week(): String {
        val p = repo.store.programNow()!!
        return (1..7).joinToString(" | ") { d -> "$d: " + p.week.on(d).joinToString("+") { it.templateId ?: it.kind.name } }
    }

    /** One chest-press session of [template] on [day]: sets (weight, reps, rir) logged at the clock times given. */
    private suspend fun chest(template: String, day: LocalDate, vararg sets: Triple<Triple<Double, Int, Int?>, String, Unit?>) {
        at(day, LocalTime.parse(sets.first().second).minusMinutes(15))
        val id = repo.startLift(template, day)
        val item = repo.planOf(db.workouts().get(id)!!)!!.items.first { it.exerciseId == "chest_press" }
        for ((s, t) in sets) {
            at(day, LocalTime.parse(t))
            repo.logSet(id, item, s.first, s.second, s.third)
        }
        at(day, LocalTime.parse(sets.last().second).plusMinutes(20))
        repo.finishLift(id, 7)
    }

    private fun s(w: Double, reps: Int, rir: Int?, at: String) = Triple(Triple(w, reps, rir), at, null)

    /** His four chest-press sessions as logged (times UTC, taken as local: only the gaps matter). */
    private suspend fun hisChestPressSessions() {
        chest("upper_b", LocalDate.of(2026, 10, 13), s(80.0, 10, 4, "10:58:35"), s(90.0, 9, 3, "11:02:20"), s(95.0, 8, 0, "11:06:16"))
        chest("upper_a", LocalDate.of(2026, 10, 15), s(90.0, 5, 4, "12:18:32"), s(90.0, 5, 5, "12:21:37"), s(95.0, 6, 3, "12:25:30"))
        chest("upper_a", LocalDate.of(2026, 10, 22), s(100.0, 5, 4, "10:56:00"), s(105.0, 5, 3, "10:59:56"), s(105.0, 6, 3, "11:06:25"))
        chest("upper_b", LocalDate.of(2026, 10, 27), s(85.0, 8, 4, "11:58:09"), s(90.0, 8, 2, "12:01:07"), s(90.0, 8, 0, "12:05:43"))
    }

    private suspend fun pairs(): List<RestAdvice.Pair> =
        db.sets().history("chest_press").groupBy { it.workoutId }.values.flatMap { l ->
            RestAdvice.pairsOf(l.sortedBy { it.doneAt }.map { forma.core.strength.DoneSet(it.weight, it.reps, it.rir) to it.doneAt }, l.first().targetReps ?: 5)
        }

    // ---------- what works ----------

    /** The hard runs stay off the leg day and the day after it; the legs' "+" set keeps 2 in reserve before a hard run. */
    @Test fun theWeekKeepsHardRunsOffTheLegsAndSparesThemBeforeOne() = runBlocking<Unit> {
        val p = repo.store.programNow()!!
        val legDay = (1..7).first { d -> p.week.on(d).any { it.templateId == "lower_a" } }
        val hard = (1..7).filter { d -> p.week.on(d).any { it.kind == SessionKind.RUN_QUALITY || it.kind == SessionKind.RUN_LONG } }
        assertTrue(legDay !in hard && (legDay % 7 + 1) !in hard, "legs on $legDay, hard runs on $hard: ${week()}")
        val squat = repo.liftPlan("lower_a", monday.plusDays(legDay - 1L)).items.first { it.exerciseId == "squat" }
        val soon = (0..2).any { k -> (legDay - 1 + k) % 7 + 1 in hard }
        println("WEEK ${week()} | legs on $legDay, hard runs $hard, hard run within 2 days: $soon, squat topRir ${squat.prescription.topRir}")
        for (t in p.templates) println("PLAN ${t.id}: " + repo.liftPlan(t.id, monday.plusDays(1)).items.joinToString(" ") { "${it.exerciseId}[${it.slot.role.name[0]} ${it.prescription.sets}×${it.slot.repMin}-${it.slot.repMax} r${it.slot.restSec}]" })
        assertEquals(if (soon) 2 else 1, squat.prescription.topRir, "hard run within two days of the legs: $soon; ${week()}")
    }

    /** His own chest-press sets, replayed by their timestamps, leave the rest where the plan has it. */
    @Test fun hisRealChestPressSessionsLeaveTheRestAsPlanned() = runBlocking<Unit> {
        hisChestPressSessions()
        val ps = pairs()
        // Sets in a row up to a tenth apart pair too (he moves the weight between sets): the learning has his data.
        assertTrue(ps.size >= forma.core.strength.RestAdvice.MIN_PAIRS, "pairs from four sessions: $ps")
        val item = repo.liftPlan("upper_b", LocalDate.of(2026, 10, 29)).items.first { it.exerciseId == "chest_press" }
        assertEquals(0, item.restShift, "pairs $ps")
        assertEquals(null, item.restWhy)
    }

    // ---------- findings ----------

    /**
     * Three more sessions in the shape of his 27.10 (85 × 8 with 4 left, 90 × 8 with 2 left after 3 min, 90 × 8 to
     * failure after 4:36 on the clock) bring the chest press to six pairs; four of them lose 2 reps after a rest twice
     * the planned 2:00. The app then tells him he recovers slowly and makes every chest-press rest 30 s longer.
     */
    @Test fun threeMoreSessionsWithLongRestsMakeTheAppLengthenHisRests() = runBlocking<Unit> {
        hisChestPressSessions()
        for (d in listOf(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 3))) {
            chest("upper_b", d, s(85.0, 8, 4, "11:58:09"), s(90.0, 8, 2, "12:01:07"), s(90.0, 8, 0, "12:05:43"))
        }
        val ps = pairs()
        val item = repo.liftPlan("upper_b", LocalDate.of(2026, 11, 10)).items.first { it.exerciseId == "chest_press" }
        assertEquals(
            0, item.restShift,
            "restShift = +${item.restShift} s on the chest press after pairs $ps (planned rest ${item.slot.restSec} s; " +
                "the lost reps came after ${ps.filter { it.before - it.after >= 2 }.map { it.restSec }} s of rest). Screen: «${item.restWhy}»",
        )
    }

    /**
     * The one accessory a 1500 m runner should keep — calves — is the one the runner rule cuts: a serious runner's
     * accessories drop to 2 sets, so the week has 2 calf sets against the advisor's own floor of 4 for a runner
     * ([PlanAdvisor.range]), and the soleus (seated calf) is not in any template at all.
     */
    @Test fun aRunnersWeekKeepsItsCalvesAboveTheAdvisorsOwnFloor() = runBlocking<Unit> {
        val p = repo.store.programNow()!!
        val days = p.templates.map { t ->
            val items = repo.liftPlan(t.id, monday.plusDays(1)).items.map {
                PlanAdvisor.Item(it.exerciseId, it.planId ?: it.exerciseId, it.prescription.sets, it.slot.repMin, it.slot.repMax, it.slot.restSec, it.slot.role)
            }
            PlanAdvisor.Day(t.id, t.title, items)
        }
        val input = PlanAdvisor.Input(days, p.aim, p.experience, runs = 3, find = Catalog::find)
        val volume = PlanAdvisor.volume(days, Catalog::find)
        val table = Muscle.entries.joinToString(", ") { m -> "${m.name} ${volume[m] ?: 0.0} (floor ${PlanAdvisor.range(input, m).first})" }
        val layout = days.joinToString(" | ") { d -> d.templateId + ": " + d.items.joinToString(" ") { "${it.exerciseId}×${it.sets}" } }
        val calves = volume[Muscle.CALVES] ?: 0.0
        assertTrue(
            calves >= PlanAdvisor.range(input, Muscle.CALVES).first,
            "calves $calves sets a week < floor ${PlanAdvisor.range(input, Muscle.CALVES).first} for a runner. Week: $table. Plan: $layout. " +
                "Templates.minutes: ${p.templates.joinToString { "${it.id} ${Templates.minutes(it)} min" }}",
        )
    }

    /**
     * After 90 × 8 with 2 left where the plan said 85 × 8 with 1 left (his 27.10 chest press), the hint says «Запас 2 —
     * чуть легче плана» — about the reserve, but to someone who just loaded 5 kg more than the plan it reads as "lighter
     * than planned".
     */
    @Test fun aSetHeavierThanPlannedIsNotCalledLighterThanThePlan() {
        val ex = Catalog["chest_press"]
        val text = Texts.nextSet(ex, NextSet(90.0, 8, 0, reserve = 2, plannedReserve = 1, plus = false, calibrate = false))
        assertTrue(!text.contains("легче плана"), "plan 85 × 8 (1 left), done 90 × 8 (2 left): «$text»")
    }
}
