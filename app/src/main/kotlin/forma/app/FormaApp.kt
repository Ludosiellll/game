package forma.app

import android.app.Application
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.sync.Cloud

/** Manual dependency container: one database, one repository and one cloud link per process. */
class FormaApp : Application() {
    val repository: TrainingRepository by lazy {
        TrainingRepository(FormaDatabase.create(this), SettingsStore(this), onChange = { cloud.syncSoon() })
    }

    val cloud: Cloud by lazy { Cloud(this, repository) }
}
