package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.LoadKind
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunLevel
import forma.core.plan.ProgramGenerator
import forma.core.strength.SlotRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertTrue

/**
 * Denis, 23 November: «почему-то на каких-то неделях разминки нет (на разгрузочных)». The lighter week took the
 * warm-up away with the sets: the weight is a tenth lighter, the joints and the groove need the ramp all the same.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class WarmUpLighterWeekTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private val monday = LocalDate.of(2026, 11, 9)
    private val clock = monday.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "warm-up-lighter")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(
                goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 73.0, aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
        // This week is the block's lighter one.
        store.setProgram(ProgramGenerator.lighterWeekAt(store.programNow()!!, monday))
    }

    @After fun tearDown() = db.close()

    @Test fun theLighterWeekKeepsTheWarmUp() = runBlocking<Unit> {
        val program = repo.store.programNow()!!
        assertTrue(ProgramGenerator.isDeloadWeek(program, monday))
        val t = program.templates.first { tpl -> tpl.slots.any { it.role == SlotRole.MAIN && Catalog[it.exerciseId].load == LoadKind.EXTERNAL } }
        val light = repo.liftPlan(t.id, monday)
        assertTrue(light.deload)
        val usual = repo.liftPlan(t.id, monday.minusWeeks(1))
        val main = usual.items.first { it.slot.role == SlotRole.MAIN && it.warmups.isNotEmpty() }
        val same = light.items.first { it.position == main.position }
        assertTrue(same.warmups.isNotEmpty(), "the main lift is warmed up in the lighter week too")
        assertTrue(same.warmups.size >= main.warmups.size - 1, "about the same ramp: ${same.warmups} against ${main.warmups}")
    }
}
