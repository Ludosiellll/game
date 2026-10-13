package forma.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.core.model.Experience
import forma.core.model.Profile
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.run.RunResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TrainingReportTest {
    private lateinit var db: FormaDatabase
    private lateinit var repo: TrainingRepository
    private val monday = LocalDate.of(2026, 10, 5)
    private var clock = monday.atTime(18, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, "report-test")
        store.clear()
        repo = TrainingRepository(db, store, now = { clock })
        repo.completeOnboarding(
            Profile(name = "Денис", birthYear = 2008, experience = Experience.ADVANCED, runLevel = RunLevel.BASE, trainingDays = setOf(1, 2, 4, 6),
                bodyWeightKg = 72.0, runGoal = RunGoal(1500, 330), lapMeters = 230),
        )
    }

    @After fun tearDown() { db.close() }

    /** Everything that happened is in the file, in words: each set with its reserve, each run with its laps, what the app decided. */
    @Test fun theReportTellsEverySessionInWords() = runBlocking {
        val program = repo.store.programNow()!!
        val t = program.templates.first()
        val id = repo.startLift(t.id, monday)
        val plan = repo.planOf(db.workouts().get(id)!!)!!
        val item = plan.items.first()
        repeat(3) { repo.logSet(id, item, 80.0, 8, 2) }
        clock += 3_600_000
        repo.finishLift(id, 7)
        clock += 86_400_000
        repo.saveRun(repo.freeRun(230), RunResult(1.0, 4, 1800, distanceM = 5000.0), clock, emptyList(), monday.plusDays(1))
        val text = TrainingReport.build(BackupManager(repo).snapshot(), monday.plusDays(2), "test")
        listOf(
            "# Форма — все тренировки и прогресс", "Беговая цель: 1500 м за 5:30", "круг 230 м", "## План", "## Где сейчас",
            "80 кг × 8 (запас 2)", "Итог:", "тяжесть 7/10", "свободный", "5,00 км", "Нагрузка и восстановление", "## Обозначения",
        ).forEach { assertTrue(text.contains(it), "no «$it» in:\n$text") }
        // The plan as done here: the first exercise of the template is in it by name.
        assertTrue(text.contains(item.name), text)
    }
}
