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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.run.RunSession
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.LocalSettings
import forma.app.ui.Navigator
import forma.app.ui.Screen
import forma.app.ui.screens.CalculatorsScreen
import forma.app.ui.screens.LiftDoneScreen
import forma.app.ui.screens.OnboardingScreen
import forma.app.ui.screens.PlanScreen
import forma.app.ui.screens.ProgressScreen
import forma.app.ui.screens.RunActiveScreen
import forma.app.ui.screens.RunSetupScreen
import forma.app.ui.screens.TodayScreen
import forma.app.ui.screens.WorkoutScreen
import forma.app.ui.screens.ExerciseScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import forma.core.plan.SessionKind
import forma.core.run.Fix
import forma.core.run.RunPlan
import forma.core.run.RunResult
import forma.core.run.RunState
import forma.core.run.RunTracker
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
import kotlin.math.roundToInt

/** Seeds eight weeks of realistic training through the real engine and photographs every main screen in every look. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreensShotTest {
    @get:Rule val compose = createComposeRule()

    private var clock = System.currentTimeMillis()
    private val zone = ZoneId.systemDefault()
    private fun at(d: LocalDate, hour: Int) = d.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun seed(): Pair<TrainingRepository, Long> = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx)
        store.clear()
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now = { clock })
        val today = repo.today()
        val start = today.minusWeeks(8).with(java.time.DayOfWeek.MONDAY)
        clock = at(start, 9)
        repo.completeOnboarding(
            Profile(
                name = "Денис", goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE,
                trainingDays = setOf(1, 3, 5, 6), bodyWeightKg = 80.0, birthYear = 1996, sessionMinutes = 75,
                raceMeters = 5000.0, raceSeconds = 24 * 60 + 30.0, restingHr = 58,
            ),
        )
        val program = store.programNow()!!
        store.setProgram(program.copy(startEpochDay = start.toEpochDay()))
        // Realistic first weights for the calibration session, by exercise.
        val firstWeight = mapOf(
            "squat" to 70.0, "bench" to 57.5, "row" to 50.0, "deadlift" to 90.0, "ohp" to 37.5, "pullup" to 0.0,
            "rdl" to 70.0, "bulgarian" to 14.0, "db_curl" to 12.0, "pushdown" to 25.0, "lateral_raise" to 8.0,
            "lat_pulldown" to 55.0, "db_shoulder" to 18.0, "cable_row" to 55.0, "plank" to 0.0,
        )
        var d = start
        var week = 0
        while (d.isBefore(today)) {
            val sessions = store.programNow()!!.week.on(d.dayOfWeek.value)
            for (s in sessions) {
                clock = at(d, 18)
                if (s.kind == SessionKind.LIFT) {
                    val id = repo.startLift(s.templateId!!, d)
                    val plan = repo.planOf(repo.db.workouts().get(id)!!)!!
                    for (item in plan.items) {
                        val p = item.prescription
                        val w = p.weight ?: firstWeight[item.exerciseId] ?: 20.0
                        repeat(p.sets) { i ->
                            // Mostly on target; now and then one rep more or a rep short on the last set.
                            val extra = if ((week + i + item.position) % 5 == 0) 1 else if (i == p.sets - 1 && (week + item.position) % 7 == 3) -1 else 0
                            clock += 150_000
                            repo.logSet(id, item, w, (p.reps + extra).coerceAtLeast(1), null)
                        }
                    }
                    clock = at(d, 18) + 58 * 60_000
                    repo.finishLift(id, if (week % 4 == 3) 8 else 7)
                } else {
                    val w = repo.runFor(s.kind, d)
                    val st = store.runStateNow()
                    val pace = RunPlan.paces(st)
                    val easy = pace?.easy?.let { (it.fast + it.slow) / 2.0 } ?: 380.0
                    val workPace = w.segments.firstOrNull { it.kind == SegmentKind.WORK }?.pace?.let { (it.fast + it.slow) / 2.0 - 3 }
                    val dist = w.segments.sumOf { seg ->
                        val p = if (seg.kind == SegmentKind.WORK && workPace != null) workPace else easy
                        seg.seconds / p * 1000
                    }
                    val workDist = w.segments.filter { it.kind == SegmentKind.WORK }.sumOf { it.seconds / (workPace ?: easy) * 1000 }
                    repo.saveRun(
                        w, RunResult(1.0, w.targetRpe, w.totalSeconds, distanceM = dist, workPace = workPace, workDistanceM = workDist.takeIf { it > 0 }),
                        at(d, 7), emptyList(), d,
                    )
                }
            }
            if (d.dayOfWeek == java.time.DayOfWeek.MONDAY) {
                clock = at(d, 8)
                repo.db.state().upsertBodyWeight(forma.app.data.BodyWeightEntity(d.toEpochDay(), 80.0 - week * 0.2))
            }
            d = d.plusDays(1)
            if (d.dayOfWeek == java.time.DayOfWeek.MONDAY) week++
        }
        // An unfinished lifting session today with two sets done.
        clock = System.currentTimeMillis() - 24 * 60_000
        val active = repo.startLift(store.programNow()!!.templates.first().id, today)
        val plan = repo.planOf(repo.db.workouts().get(active)!!)!!
        val first = plan.items.first()
        repeat(2) { repo.logSet(active, first, first.prescription.weight ?: 60.0, first.prescription.reps, if (it == 0) 2 else 1) }
        clock = System.currentTimeMillis()
        repo to active
    }

    private fun settle() {
        repeat(6) {
            Thread.sleep(150)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    @Test fun screens() {
        val (repo, active) = seed()
        val doneLift = runBlocking { repo.db.workouts().allDone().last { it.kind == "LIFT" }.id }
        val intervals = runBlocking { RunPlan.intervals(repo.store.runStateNow().copy(intervalStage = 2)) }
        // A live interval run 21:36 in, third rep.
        val tracker = RunTracker(intervals)
        tracker.start(0)
        var dist = 0.0
        for (s in 0..1296) {
            val seg = tracker.timeline(s * 1000L).segment
            val speed = if (seg?.kind == SegmentKind.WORK) 1000.0 / 272 else 1000.0 / 352
            dist += speed
            tracker.onFix(Fix(s * 1000L, 53.9 + dist / 111_195.0, 27.56, 4f), s * 1000L)
        }
        RunSession.tracker = tracker
        RunSession.gpsEnabled = true
        RunSession.publish(1_296_000)

        var look by mutableStateOf("carbon")
        var screen by mutableStateOf<@Composable () -> Unit>({ TodayScreen() })
        val settings = Settings()
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides settings.copy(look = look)) {
                FormaTheme(Looks.byId(look)) {
                    Box(Modifier.fillMaxSize().background(F.c.bg)) { screen() }
                }
            }
        }
        val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
            "today" to { TodayScreen() },
            "workout" to { WorkoutScreen(active) },
            "run" to { RunActiveScreen() },
            "progress" to { ProgressScreen() },
            "plan" to { PlanScreen() },
            "liftdone" to { LiftDoneScreen(doneLift) },
            "runsetup" to { RunSetupScreen(intervals, SessionKind.RUN_QUALITY) },
            "exercise" to { ExerciseScreen("squat") },
            "calc" to { CalculatorsScreen() },
            "onboarding" to { OnboardingScreen() },
            "more" to { forma.app.ui.screens.MoreScreen() },
            "cloud" to {
                forma.app.ui.screens.CloudContent(
                    forma.app.sync.CloudState(configured = true, ready = true, email = "denis@example.com", lastSyncAt = System.currentTimeMillis() - 2 * 3_600_000),
                    null, {}, {}, {}, {},
                )
            },
            "cloudoff" to { forma.app.ui.screens.CloudContent(forma.app.sync.CloudState(configured = true, ready = true), null, {}, {}, {}, {}) },
            "cloudin" to {
                forma.app.ui.screens.CloudSignInContent(
                    restore = false, configured = true, mode = forma.app.ui.screens.SignInMode.SIGN_UP, email = "denis@example.com",
                    password = "secret-pass", busy = false, error = null, progress = null, result = null,
                    onEmail = {}, onPassword = {}, onMode = {}, onSubmit = {}, onBack = {},
                )
            },
        )
        for (l in Looks.all) {
            look = l.id
            for ((name, s) in screens) {
                if (l.id != "carbon" && name in setOf("runsetup", "exercise", "calc", "onboarding", "liftdone", "more", "cloudoff")) continue
                screen = s
                settle()
                compose.onRoot().captureRoboImage("build/shots/${l.id}-$name.png")
            }
        }
    }

    /** The very first session of a new user: every working weight is being found. */
    @Test fun firstSession() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "shots-first")
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now = { clock })
        val id = runBlocking {
            store.clear()
            repo.completeOnboarding(
                Profile(
                    name = "Денис", goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE,
                    trainingDays = setOf(1, 3, 5), bodyWeightKg = 80.0, birthYear = 1996,
                ),
            )
            repo.startLift(store.programNow()!!.templates.first().id)
        }
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) {
                    Box(Modifier.fillMaxSize().background(F.c.bg)) { WorkoutScreen(id) }
                }
            }
        }
        settle()
        compose.onRoot().captureRoboImage("build/shots/carbon-workoutfirst.png")
    }

    /** Daily life on a fixed calendar: a missed session on a free day, a bad day, a vacation, body estimates. */
    @Test fun dailyLife() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx, "shots-life")
        var now by mutableStateOf(at(LocalDate.of(2026, 10, 5), 9))
        val repo = TrainingRepository(FormaDatabase.create(ctx, inMemory = true), store, now = { now })
        val monday = LocalDate.of(2026, 10, 5)
        runBlocking {
            store.clear()
            repo.completeOnboarding(
                Profile(
                    name = "Денис", goal = Goal.HYBRID, experience = Experience.INTERMEDIATE, runLevel = RunLevel.BASE,
                    trainingDays = setOf(1, 3, 5, 6), bodyWeightKg = 80.0, heightCm = 182, birthYear = 1996,
                ),
            )
            // Monday skipped; Wednesday's hard run done.
            val wed = monday.plusDays(2)
            now = at(wed, 7)
            val q = repo.runFor(SessionKind.RUN_QUALITY, wed)
            repo.saveRun(q, RunResult(1.0, 7, q.totalSeconds, distanceM = 7000.0), now, emptyList(), wed)
        }
        var screen by mutableStateOf<@Composable () -> Unit>({})
        compose.setContent {
            CompositionLocalProvider(LocalRepo provides repo, LocalNav provides Navigator(Screen.Today), LocalSettings provides Settings()) {
                FormaTheme(Looks.byId("carbon")) {
                    Box(Modifier.fillMaxSize().background(F.c.bg)) { screen() }
                }
            }
        }
        fun shot(name: String, day: LocalDate, content: @Composable () -> Unit) {
            now = at(day, 9)
            screen = {}
            settle()
            screen = content
            settle()
            compose.onRoot().captureRoboImage("build/shots/carbon-$name.png")
        }
        // Thursday is free: the missed session that fits is offered.
        shot("today-catchup", monday.plusDays(3)) { TodayScreen() }
        // Friday: "so-so" makes the session lighter.
        shot("today-light", monday.plusDays(4)) { TodayScreen() }
        compose.onNode(androidx.compose.ui.test.hasText("Так себе")).performClick()
        settle()
        compose.onRoot().captureRoboImage("build/shots/carbon-today-light.png")
        // Two weeks away from Friday.
        runBlocking { repo.pause(monday.plusDays(4), monday.plusDays(17)) }
        shot("today-pause", monday.plusDays(6)) { TodayScreen() }
        shot("body", monday.plusDays(6)) { forma.app.ui.screens.BodyScreen() }
    }
}
