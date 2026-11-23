package forma.app.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.screens.Hints
import forma.app.ui.screens.ProgressScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Muscle
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * «Мышцы за 7 дней» against the plan for the same days (a review on 23 November): someone who did every set the plan
 * asked for read «Меньше плана почти везде» on every gym day before the session and all through a lighter week, as the
 * seven days were measured against a whole template week at full sets. Any weekday: the history is built up to today.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h4200dp-xhdpi")
class MusclesWeekUiTest {
    @get:Rule val compose = createComposeRule()

    private var clock = System.currentTimeMillis()
    private val zone = ZoneId.systemDefault()
    private fun at(d: LocalDate, hour: Int) = d.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun settle(rounds: Int = 40) {
        repeat(rounds) {
            Thread.sleep(100)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    /** Eight weeks of the gym as planned, every prescribed set logged as prescribed; the runs are not the point here. */
    private fun asPlanned(name: String): TrainingRepository = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, name)
        store.clear()
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now = { clock })
        val today = repo.today()
        val start = today.minusWeeks(8).with(DayOfWeek.MONDAY)
        clock = at(start, 9)
        repo.completeOnboarding(
            Profile(
                name = "Денис", goal = Goal.HYBRID, experience = Experience.ADVANCED, runLevel = RunLevel.BASE,
                trainingDays = setOf(1, 2, 4, 6), bodyWeightKg = 73.0, birthYear = 2008, sessionMinutes = 75,
                runGoal = RunGoal(1500, 330), lapMeters = 205,
                aim = Aim(LiftFocus.POWERBUILDING, RunFocus.ENDURANCE, Emphasis.BALANCED),
            ),
        )
        store.setProgram(store.programNow()!!.copy(startEpochDay = start.toEpochDay()))
        store.editSettings { it.copy(hintsSeen = it.hintsSeen + listOf(Hints.PROGRESS)) }
        var d = start
        while (d.isBefore(today)) {
            for (s in store.programNow()!!.week.on(d.dayOfWeek.value)) {
                if (s.kind != SessionKind.LIFT) continue
                clock = at(d, 18)
                val id = repo.startLift(s.templateId!!, d)
                for (item in repo.planOf(repo.db.workouts().get(id)!!)!!.items) {
                    val p = item.prescription
                    repeat(p.sets) {
                        clock += 240_000
                        repo.logSet(id, item, p.weight ?: 40.0, p.reps, p.rir)
                    }
                }
                clock = at(d, 18) + 80 * 60_000
                repo.finishLift(id, 7)
            }
            d = d.plusDays(1)
        }
        clock = System.currentTimeMillis()
        repo
    }

    @Test fun aWeekDoneAsPlannedReadsSoOnAnyDay() {
        val repo = asPlanned("muscles-as-planned")
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Progress), LocalSettings provides Settings().copy(look = Looks.minimalDark.id)) {
                FormaTheme(Looks.minimalDark) { Box(Modifier.fillMaxSize().background(F.c.bg)) { ProgressScreen() } }
            }
        }
        // The plan of the week's days comes from the database, a query at a time: waited for, not a fixed pause.
        compose.waitUntil(120_000) { settle(1); compose.onAllNodesWithText("Мышцы за 7 дней").fetchSemanticsNodes().isNotEmpty() }
        settle(5)
        compose.onNodeWithText("Мышцы за 7 дней").performScrollTo()
        val wrong = listOf("Меньше плана почти везде", "Меньше плана:", "Мало:").filter {
            compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(wrong.isEmpty(), "every planned set was done, yet the week reads ${wrong.joinToString()} (${repo.today().dayOfWeek})")
        compose.onNodeWithText("Всё по плану").assertExists()
    }

    @Test fun theLighterWeekPlansHalfTheSets() = runBlocking {
        val repo = asPlanned("muscles-lighter-week")
        val program = repo.store.programNow()!!
        // A lifting day of a block's last (lighter) week and the same weekday the week after it.
        val liftDay = (1..7).first { day -> program.week.on(day).any { it.kind == SessionKind.LIFT } }
        val monday = LocalDate.ofEpochDay(program.startEpochDay).plusWeeks(program.blockWeeks.toLong() - 1)
        val lighter = monday.plusDays(liftDay - 1L)
        val usual = lighter.plusWeeks(1)
        assertTrue(forma.core.plan.ProgramGenerator.isDeloadWeek(program, lighter) && !forma.core.plan.ProgramGenerator.isDeloadWeek(program, usual))
        val half = repo.plannedSets(lighter, lighter)
        val full = repo.plannedSets(usual, usual)
        assertTrue(full.values.sum() > 0)
        for (m in Muscle.entries) {
            val f = full.getValue(m)
            assertTrue(half.getValue(m) < f || f == 0.0 || half.getValue(m) <= 1.0, "$m: lighter ${half[m]} against ${full[m]}")
        }
        // A paused day asks for nothing.
        repo.store.editSettings { it.copy(pauseFrom = usual.toEpochDay(), pauseUntil = usual.toEpochDay()) }
        assertEquals(0.0, repo.plannedSets(usual, usual).values.sum())
    }
}
