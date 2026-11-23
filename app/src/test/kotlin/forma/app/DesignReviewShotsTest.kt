package forma.app

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.LocalSettings
import forma.app.ui.Navigator
import forma.app.ui.Screen
import forma.app.ui.screens.Hints
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.SegmentKind
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Whole screens, top to bottom, for a design review (23 November: «проверить каждый аспект приложения … от лица человека
 * настоящего»): a person like Denis — gym and a 1500 m goal on a 205 m loop, eight weeks of training through the real
 * engine — in the default look, first-run hints already read. The window is tall so a screen fits in one picture.
 * Recorded only with -Proborazzi.test.record=true (build/shots/review-*.png); otherwise it just renders them.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h2600dp-xhdpi")
class DesignReviewShotsTest {
    @get:Rule val compose = createComposeRule()

    private var clock = System.currentTimeMillis()
    private val zone = ZoneId.systemDefault()
    private fun at(d: LocalDate, hour: Int) = d.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun settle() {
        repeat(8) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun seed(): Pair<TrainingRepository, Long> = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "design-review")
        store.clear()
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now = { clock })
        val today = repo.today()
        val start = today.minusWeeks(8).with(java.time.DayOfWeek.MONDAY)
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
        store.editSettings { it.copy(hintsSeen = it.hintsSeen + listOf(Hints.TODAY, Hints.PLAN, Hints.PROGRESS, Hints.WORKOUT, Hints.RUN)) }
        val firstWeight = mapOf(
            "squat" to 80.0, "bench" to 70.0, "chest_press" to 90.0, "row" to 60.0, "deadlift" to 110.0, "ohp" to 40.0, "pullup" to 0.0,
            "rdl" to 80.0, "sldl" to 80.0, "bulgarian" to 16.0, "db_curl" to 14.0, "pushdown" to 30.0, "lateral_raise" to 10.0,
            "lat_pulldown" to 70.0, "db_shoulder" to 22.0, "cable_row" to 60.0, "leg_press" to 140.0, "leg_curl" to 50.0, "leg_ext" to 50.0,
        )
        var d = start
        var week = 0
        while (d.isBefore(today)) {
            // He misses a session now and then, as people do.
            val skip = (d.toEpochDay() % 9L) == 0L
            for (s in store.programNow()!!.week.on(d.dayOfWeek.value)) {
                if (skip) continue
                clock = at(d, 18)
                if (s.kind == SessionKind.LIFT) {
                    val id = repo.startLift(s.templateId!!, d)
                    val plan = repo.planOf(repo.db.workouts().get(id)!!)!!
                    for (item in plan.items) {
                        val p = item.prescription
                        val w = p.weight ?: firstWeight[item.exerciseId] ?: 20.0
                        repeat(p.sets) { i ->
                            val extra = if ((week + i + item.position) % 5 == 0) 1 else if (i == p.sets - 1 && (week + item.position) % 7 == 3) -1 else 0
                            clock += 240_000
                            repo.logSet(id, item, w, (p.reps + extra).coerceAtLeast(1), if (i == p.sets - 1) 1 else 2)
                        }
                    }
                    clock = at(d, 18) + 90 * 60_000
                    repo.finishLift(id, if (week % 4 == 3) 8 else 7)
                } else {
                    val w = RunPlan.onTrack(repo.runFor(s.kind, d), 205)
                    val st = store.runStateNow()
                    val easy = RunPlan.paces(RunPlan.estimated(st, RunGoal(1500, 330)))?.easy?.let { (it.fast + it.slow) / 2.0 } ?: 390.0
                    val workPace = w.segments.firstOrNull { it.kind == SegmentKind.WORK }?.pace?.let { (it.fast + it.slow) / 2.0 - 2 }
                    val dist = w.segments.sumOf { seg -> seg.seconds / (if (seg.kind == SegmentKind.WORK && workPace != null) workPace else easy) * 1000 }
                    val workDist = w.segments.filter { it.kind == SegmentKind.WORK }.sumOf { it.seconds / (workPace ?: easy) * 1000 }
                    repo.saveRun(
                        w, RunResult(1.0, w.targetRpe, w.totalSeconds, distanceM = dist, workPace = workPace, workDistanceM = workDist.takeIf { it > 0 }, reps = w.reps),
                        at(d, 7), emptyList(), d,
                    )
                }
            }
            if (d.dayOfWeek == java.time.DayOfWeek.MONDAY || d.dayOfWeek == java.time.DayOfWeek.THURSDAY) {
                clock = at(d, 8)
                repo.db.state().upsertBodyWeight(forma.app.data.BodyWeightEntity(d.toEpochDay(), 73.0 + week * 0.15 + (if (d.dayOfWeek.value == 4) 0.4 else 0.0)))
            }
            d = d.plusDays(1)
            if (d.dayOfWeek == java.time.DayOfWeek.MONDAY) week++
        }
        // Today's gym session under way, two sets done.
        clock = System.currentTimeMillis() - 30 * 60_000
        val lift = store.programNow()!!.templates.first().id
        val active = repo.startLift(lift, today)
        val plan = repo.planOf(repo.db.workouts().get(active)!!)!!
        val first = plan.items.first()
        repeat(2) { repo.logSet(active, first, first.prescription.weight ?: 80.0, first.prescription.reps, 2) }
        clock = System.currentTimeMillis()
        repo to active
    }

    @Test fun wholeScreens() {
        val (repo, active) = seed()
        val doneLift = runBlocking { repo.db.workouts().allDone().last { it.kind == "LIFT" }.id }
        val doneRun = runBlocking { repo.db.workouts().allDone().last { it.kind == "RUN" }.id }
        val quality = runBlocking { RunPlan.onTrack(repo.runFor(SessionKind.RUN_QUALITY, repo.today()), 205) }
        var screen by mutableStateOf<@Composable () -> Unit>({ forma.app.ui.screens.TodayScreen() })
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings().copy(look = Looks.minimalDark.id)) {
                FormaTheme(Looks.minimalDark) { Box(Modifier.fillMaxSize().background(F.c.bg)) { screen() } }
            }
        }
        val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
            "today" to { forma.app.ui.screens.TodayScreen() },
            "workout" to { forma.app.ui.screens.WorkoutScreen(active) },
            "liftdone" to { forma.app.ui.screens.LiftDoneScreen(doneLift) },
            "rundone" to { forma.app.ui.screens.RunDoneScreen(doneRun) },
            "plan" to { forma.app.ui.screens.PlanScreen() },
            "progress-strength" to { forma.app.ui.screens.ProgressScreen(0) },
            "progress-run" to { forma.app.ui.screens.ProgressScreen(1) },
            "progress-load" to { forma.app.ui.screens.ProgressScreen(2) },
            "progress-body" to { forma.app.ui.screens.ProgressScreen(3) },
            "exercise-squat" to { forma.app.ui.screens.ExerciseScreen("squat") },
            "runsetup" to { forma.app.ui.screens.RunSetupScreen(quality, SessionKind.RUN_QUALITY) },
            "bodyweight" to { forma.app.ui.screens.BodyScreen() },
            "history" to { forma.app.ui.screens.HistoryScreen() },
            "more" to { forma.app.ui.screens.MoreScreen() },
            "coach" to { forma.app.ui.screens.CoachScreen() },
        )
        for ((name, s) in screens) {
            screen = s
            settle()
            compose.onRoot().captureRoboImage("build/shots/review-$name.png")
        }
    }
}
