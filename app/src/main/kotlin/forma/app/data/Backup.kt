package forma.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import forma.core.model.Profile
import forma.core.plan.Program
import forma.core.run.RunState
import kotlinx.serialization.Serializable

/** Everything the app knows, in one JSON file. */
@Serializable
data class Backup(
    val version: Int = 1,
    val createdAt: Long,
    val profile: Profile?,
    val program: Program?,
    val runState: RunState?,
    val settings: Settings,
    val liftState: LiftState,
    val workouts: List<WorkoutEntity>,
    val sets: List<SetEntity>,
    val runs: List<RunEntity>,
    val points: List<TrackPointEntity>,
    val slots: List<SlotStateEntity>,
    val stats: List<ExerciseStatEntity>,
    val bodyWeights: List<BodyWeightEntity>,
    val customExercises: List<forma.core.model.Exercise> = emptyList(),
)

class BackupManager(private val repo: TrainingRepository) {
    /** All tables read in one transaction: a sync or a saved run in between cannot leave sets without their workout. */
    suspend fun snapshot(): Backup {
        val b = repo.db.backup()
        val st = repo.store
        return repo.db.withTransaction {
            Backup(
                createdAt = System.currentTimeMillis(), profile = st.profileNow(), program = st.programNow(),
                runState = st.runStateNow(), settings = st.settingsNow(), liftState = st.liftStateNow(),
                workouts = b.workouts(), sets = b.sets(), runs = b.runs(), points = b.points(), slots = b.slots(), stats = b.stats(),
                bodyWeights = b.bodyWeights(), customExercises = st.customNow(),
            )
        }
    }

    suspend fun export(context: Context, uri: Uri) {
        val json = AppJson.encodeToString(Backup.serializer(), snapshot())
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            ?: error("Не удалось открыть файл")
    }

    /**
     * Replaces the data on the phone with the file's content. Returns the number of workouts restored.
     * With the cloud on, the next sync first brings in what the cloud has (newer progress wins, workouts deleted since
     * the file was made stay deleted), then uploads what only the file had: an old file rolls nothing back.
     */
    suspend fun import(context: Context, uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Не удалось прочитать файл")
        return restore(AppJson.decodeFromString(Backup.serializer(), text))
    }

    suspend fun restore(b: Backup): Int {
        DataLock.mutex.withLock {
            repo.db.backup().replaceAll(b.workouts, b.sets, b.runs, b.points, b.slots, b.stats, b.bodyWeights)
            val st = repo.store
            b.profile?.let { st.setProfile(it) }
            b.program?.let { st.setProgram(it) }
            b.runState?.let { st.setRunState(it) }
            st.setSettings(b.settings)
            st.setLiftState(b.liftState)
            st.setCustom(b.customExercises)
            repo.refreshCustom()
        }
        repo.notifyChanged()
        return b.workouts.size
    }
}
