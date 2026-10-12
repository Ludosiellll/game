package forma.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.sync.Cloud
import forma.core.model.Aim
import forma.core.model.Emphasis
import forma.core.model.Experience
import forma.core.model.LiftFocus
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.plan.ProgramGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The first start after an update that lays old plans out again: one repository and one database for the screens and
 * the cloud. Telling the cloud from inside the repository's own building used to build a second one, so what a sync
 * wrote never reached the open screens.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AppStartTest {
    private val denis = Profile(
        name = "Денис", aim = Aim(LiftFocus.MUSCLE, RunFocus.ENDURANCE, Emphasis.BALANCED), experience = Experience.ADVANCED,
        runLevel = RunLevel.BASE, trainingDays = setOf(2, 4, 6), bodyWeightKg = 80.0, birthYear = 2008, doubleDays = true,
        runGoal = RunGoal(1500, 330), lapMeters = 230,
    )

    @Test fun anOldPlanAtStartLeavesOneRepository() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<FormaApp>()
        val store = SettingsStore(app)
        store.clear()
        store.setProfile(denis)
        // As an older version left it: no planner version.
        store.setProgram(ProgramGenerator.generate(denis, LocalDate.now()).copy(planner = 0))
        // As MainActivity does: the repository first, then the cloud.
        val repo = app.repository
        val cloud = app.cloud
        val inCloud = Cloud::class.java.getDeclaredField("repo").apply { isAccessible = true }.get(cloud) as TrainingRepository
        assertSame(repo, inCloud)
        // And the plan was laid out again.
        assertEquals(ProgramGenerator.PLANNER, repo.store.programNow()!!.planner)
    }
}
