package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.LiftItem
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.Focus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Research legs-norm-for-runners+D3 and part (a) of one-leg-day (the owner's decision of 25 Nov) on Denis's plan through
 * the repository: «Идеальный план» offers the leg curl and the leg extension a set more on «Низ» and two sets at the end
 * of the upper session after Thursday's hard run; looking changes nothing; taken, they change those sets and add that one
 * exercise «насовсем» with its numbers — the leg day's other exercises, the upper sessions' own, the week and the
 * templates stay as they were — and the added leg sets on the upper day come without a «+» set, 2–3 in reserve.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ResearchLegsRepoTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    /** Monday 12 Oct 2026, the plan's first week. */
    private val monday = LocalDate.of(2026, 10, 12)
    private var clock = monday.plusDays(2).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun at(d: LocalDate, hour: Int) { clock = d.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }

    private val denis = Profile(
        name = "Денис", aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
        runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6), runGoal = RunGoal(1500, 330), lapMeters = 230, doubleDays = true,
        gymDays = setOf(2, 4, 6), runDays = setOf(1, 4, 6),
    )

    /** Denis as he is: his own swap of the Bulgarian split squat for the leg extension (as in IdealPlanTest). */
    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "research-legs")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(denis)
        store.editSettings { s -> s.copy(swaps = s.swaps + ("bulgarian" to "leg_ext")) }
    }

    @After fun tearDown() = db.close()

    private fun row(i: LiftItem) = "${i.exerciseId} ${i.slot.sets}×${i.slot.repMin}-${i.slot.repMax} r${i.slot.restSec}"

    @Test fun theLegOffersReachHisPlanOnlyWhenTakenAndChangeNothingElse() = runBlocking<Unit> {
        val program = repo.store.programNow()!!
        val week = (1..7).joinToString(" | ") { d -> "$d: " + program.week.on(d).joinToString("+") { it.templateId ?: it.kind.name } }
        val before = repo.store.settingsNow()
        val a = repo.planAdvice()!!
        assertEquals(before, repo.store.settingsNow(), "looking changes nothing")
        val legs = a.changes.filter { it.id.startsWith("legs:") }
        assertEquals(listOf("legs:HAMSTRINGS", "legs:QUADS", "legs:after-run"), legs.map { it.id }, "$week; ${a.changes.map { it.id }}")
        val (curl, ext, extra) = legs
        assertEquals("leg_curl", curl.planId)
        assertEquals("bulgarian" to "leg_ext", ext.planId to ext.from)
        // The upper session done after Thursday's hard run, the run first that day: «Верх · А», an upper template.
        val thursday = program.week.on(4)
        assertTrue(thursday.first().isHardRun && thursday.last().templateId == extra.templateId, "$week; add-on on ${extra.templateId}")
        assertEquals(Focus.UPPER, program.template(extra.templateId).focus)
        assertNotNull(a.nudge, "the plan screen's card says it")

        val saturday = monday.plusDays(5)
        val tuesday = monday.plusDays(1)
        val thu = monday.plusDays(3)
        // The other upper session of the week (Tuesday's «Верх · Б»).
        val otherUpper = program.templates.first { it.focus == Focus.UPPER && it.id != extra.templateId }.id
        val lower0 = repo.liftPlan("lower_a", saturday).items.map(::row)
        val upper0 = repo.liftPlan(extra.templateId, thu).items.map(::row)
        val upperB0 = repo.liftPlan(otherUpper, tuesday).items.map(::row)

        repo.applyPlanAdvice(listOf(curl, ext, extra))
        val s = repo.store.settingsNow()
        val key = TrainingRepository.addedKey(extra.templateId, extra.to!!)
        assertEquals(before.sets + mapOf("leg_curl" to 3, "bulgarian" to 4, key to 2), s.sets)
        assertEquals(listOf(extra.to!!), s.added[extra.templateId])
        // Nothing else of his settings, his plan's week or its templates (an upper template stays an upper one).
        assertEquals(before.copy(sets = s.sets, added = s.added), s)
        assertEquals(program, repo.store.programNow())

        assertEquals(
            lower0.map { it.replace("leg_curl 2×", "leg_curl 3×").replace("leg_ext 3×", "leg_ext 4×") },
            repo.liftPlan("lower_a", saturday).items.map(::row),
        )
        val upper = repo.liftPlan(extra.templateId, thu).items
        assertEquals(upper0, upper.filter { !it.addedForGood }.map(::row))
        assertEquals("${extra.to} 2×10-15 r90", row(upper.last()), "at the end of the session")
        assertTrue(upper.last().addedForGood)
        assertEquals(upperB0, repo.liftPlan(otherUpper, tuesday).items.map(::row))

        // Taken: not offered again.
        val again = repo.planAdvice()!!.changes.map { it.id }
        assertTrue(again.none { it.startsWith("legs:") }, again.toString())
    }

    /** The leg sets on the upper day: no «+» set, 2–3 in reserve, once the weight is known — the upper day's own lifts keep theirs. */
    @Test fun theLegSetsOnTheUpperDayHaveNoPlusSet() = runBlocking<Unit> {
        val extra = repo.planAdvice()!!.changes.first { it.id == "legs:after-run" }
        repo.applyPlanAdvice(listOf(extra))
        // A first session of it: every set as prescribed, so the weights are known the week after.
        val thu = monday.plusDays(3)
        at(thu, 18)
        val id = repo.startLift(extra.templateId, thu)
        for (item in repo.planOf(db.workouts().get(id)!!)!!.items) {
            val p = item.prescription
            repeat(p.sets) {
                clock += 180_000
                repo.logSet(id, item, p.weight ?: p.startHint ?: 40.0, p.reps, 2)
            }
        }
        clock += 600_000
        repo.finishLift(id, 7)
        val next = repo.liftPlan(extra.templateId, thu.plusWeeks(1)).items
        val legs = next.single { it.addedForGood }
        assertFalse(legs.prescription.calibrate, "the weight is known by now")
        assertFalse(legs.prescription.top, "no «+» set on the legs of an upper day")
        assertTrue(legs.prescription.rir in 2..3, "reserve ${legs.prescription.rir}")
        val main = next.first { !it.addedForGood }
        assertTrue(main.prescription.top, "${main.exerciseId} keeps its «+» set")
    }
}
