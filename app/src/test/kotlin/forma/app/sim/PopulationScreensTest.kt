package forma.app.sim

import android.content.Context
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import forma.app.data.Kind
import forma.app.data.Settings
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.LocalSettings
import forma.app.ui.Navigator
import forma.app.ui.Screen
import forma.app.ui.screens.DayScreen
import forma.app.ui.screens.HistoryItemScreen
import forma.app.ui.screens.HistoryScreen
import forma.app.ui.screens.PlanScreen
import forma.app.ui.screens.ProgressScreen
import forma.app.ui.screens.SettingsScreen
import forma.app.ui.screens.TodayScreen
import forma.app.ui.screens.WorkoutScreen
import forma.app.ui.theme.F
import forma.app.ui.theme.FormaTheme
import forma.app.ui.theme.Looks
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

/**
 * Every main screen after half a year of training, for a sample of the population's people: no crash, nothing broken
 * on it (NaN, null, Infinity, a negative count), and its text dumped so it can be read person by person; a few as
 * pictures. FORMA_SIM_SCREENS=… people (long; skipped without it). Reports in build/sim/screens.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xxhdpi")
class PopulationScreensTest {
    @get:Rule val compose = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val start = LocalDate.of(2026, 10, 12)
    private val weeks = 26
    private val out = File(Simulation.outDir, "screens").also { it.mkdirs() }

    private fun settle() {
        repeat(5) {
            Thread.sleep(120)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    /** Every text on the screen, in order. */
    private fun texts(): List<String> = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any") { true }, useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private val broken = Regex("""NaN|Infinity|\bnull\b|−?-\d+ (подход|повтор|мин|км|раз)""")

    @Test fun everyScreenForManyPeople() {
        val n = System.getenv("FORMA_SIM_SCREENS")?.toIntOrNull()
        assumeTrue(n != null)
        val ids = (0 until n!!).map { (it * 37 + 11) % 1000 }
        val issues = File(out, "issues.txt").printWriter()
        var screen by mutableStateOf<Pair<String, @Composable () -> Unit>?>(null)
        var current: forma.app.data.TrainingRepository? = null
        var nav = Navigator(Screen.Today)
        compose.setContent {
            val repo = current
            val s = screen
            if (repo != null && s != null) {
                CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav, LocalSettings provides Settings()) {
                    FormaTheme(Looks.byId("carbon")) { Box(Modifier.fillMaxSize().background(F.c.bg)) { androidx.compose.runtime.key(s.first) { s.second() } } }
                }
            }
        }
        for (id in ids) {
            val p = People.person(id)
            val dump = StringBuilder("# #$id ${p.tags}\n")
            val sim = runBlocking {
                val s = Simulation(ctx, "scr-$id", p.profile, p.athlete, start = start, runner = p.runner)
                s.setUp()
                val rnd = Random(id * 7 + 3)
                s.live(weeks * 7, trains = { rnd.nextDouble() >= p.miss })
                s
            }
            val repo = sim.repo
            val end = start.plusWeeks(weeks.toLong()).minusDays(1)
            val done = runBlocking { repo.db.workouts().allDone() }
            val lastLift = done.lastOrNull { it.kind == Kind.LIFT }?.id
            val lastRun = done.lastOrNull { it.kind == Kind.RUN }?.id
            val program = runBlocking { repo.store.programNow() }
            val liftDay = (0L..6L).map { end.plusDays(it) }.firstOrNull { d -> program?.week?.on(d.dayOfWeek.value)?.any { it.kind == forma.core.plan.SessionKind.LIFT } == true }
            val screens = buildList<Pair<String, @Composable () -> Unit>> {
                add("today" to { TodayScreen() })
                (0..3).forEach { t -> add("progress-$t" to { ProgressScreen(t) }) }
                add("history" to { HistoryScreen() })
                lastLift?.let { add("lift-done" to { HistoryItemScreen(it) }) }
                lastRun?.let { add("run-done" to { HistoryItemScreen(it) }) }
                add("plan" to { PlanScreen() })
                add("day" to { DayScreen(end) })
                add("settings" to { SettingsScreen() })
            }
            current = repo
            for ((name, content) in screens) {
                try {
                    nav = Navigator(Screen.Today)
                    screen = name to content
                    settle()
                    val t = texts()
                    dump.append("## $name\n").append(t.joinToString("\n")).append("\n\n")
                    t.filter { broken.containsMatchIn(it) }.forEach { issues.println("#$id $name BROKEN: $it") }
                    if (t.isEmpty()) issues.println("#$id $name EMPTY")
                    if (id == ids.first() || id % 5 == 0 && ids.indexOf(id) < 12) compose.onRoot().captureRoboImage("${out.path}/$id-$name.png")
                } catch (e: Throwable) {
                    issues.println("#$id $name CRASH ${e::class.simpleName}: ${e.message?.take(200)} at ${e.stackTrace.take(5).joinToString(" < ")}")
                }
            }
            // A gym session started on the next lifting day: the workout screen as it opens.
            if (liftDay != null) try {
                val t0 = program!!.week.on(liftDay.dayOfWeek.value).first { it.kind == forma.core.plan.SessionKind.LIFT }.templateId!!
                val wid = runBlocking { repo.startLift(t0, liftDay) }
                screen = "workout" to { WorkoutScreen(wid) }
                settle()
                val t = texts()
                dump.append("## workout\n").append(t.joinToString("\n")).append("\n\n")
                t.filter { broken.containsMatchIn(it) }.forEach { issues.println("#$id workout BROKEN: $it") }
                runBlocking { repo.cancelWorkout(wid) }
            } catch (e: Throwable) {
                issues.println("#$id workout CRASH ${e::class.simpleName}: ${e.message?.take(200)} at ${e.stackTrace.take(5).joinToString(" < ")}")
            }
            screen = null
            settle()
            File(out, "text-$id.md").writeText(dump.toString())
            issues.flush()
            sim.close()
        }
        issues.close()
    }
}
