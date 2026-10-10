package forma.app

import android.app.Application
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.sync.Cloud
import kotlinx.coroutines.runBlocking

/** Manual dependency container: one database, one repository and one cloud link per process. */
class FormaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // A run the system closed together with the app comes back paused at its last save.
        forma.app.run.RunSession.init(forma.app.run.RunStore(filesDir), android.os.SystemClock.elapsedRealtime())
    }

    val repository: TrainingRepository by lazy {
        TrainingRepository(FormaDatabase.create(this), SettingsStore(this), onChange = { cloud.syncSoon() })
            // Own exercises must be in the catalog before any screen shows a workout with them (a small local read).
            .also { runBlocking { it.refreshCustom() } }
    }

    val cloud: Cloud by lazy { Cloud(this, repository) }
}
