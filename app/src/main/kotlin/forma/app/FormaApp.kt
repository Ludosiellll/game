package forma.app

import android.app.Application
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository

/** Manual dependency container: one database and one repository per process. */
class FormaApp : Application() {
    val repository: TrainingRepository by lazy {
        TrainingRepository(FormaDatabase.create(this), SettingsStore(this))
    }
}
