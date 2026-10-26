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
    /** The cloud account (user id) the data belonged to when the file was made; null when the phone had none. */
    val account: String? = null,
)

class BackupManager(private val repo: TrainingRepository) {
    /**
     * The cloud account the phone's data belongs to: the one it synced with (or, restored from a file and not synced
     * since, the one before), else [signedIn] — the account signed in now, if any.
     */
    suspend fun account(signedIn: String? = null): String? = forma.app.sync.SyncEngine.owner(repo.db.sync()) ?: signedIn

    /**
     * All tables read in one transaction: a sync or a saved run in between cannot leave sets without their workout.
     * [signedIn]: the account signed in now, written into the file when the phone has not synced yet.
     */
    /** [points]: with the runs' GPS points (a backup); the coach's summary goes without them — there can be many. */
    suspend fun snapshot(signedIn: String? = null, points: Boolean = true): Backup {
        val b = repo.db.backup()
        val st = repo.store
        return repo.db.withTransaction {
            Backup(
                createdAt = System.currentTimeMillis(), profile = st.profileNow(), program = st.programNow(),
                runState = st.runStateNow(), settings = st.settingsNow(), liftState = st.liftStateNow(),
                workouts = b.workouts(), sets = b.sets(), runs = b.runs(), points = if (points) b.points() else emptyList(), slots = b.slots(), stats = b.stats(),
                bodyWeights = b.bodyWeights(), customExercises = st.customNow(), account = account(signedIn),
            )
        }
    }

    suspend fun export(context: Context, uri: Uri, signedIn: String? = null) {
        val json = AppJson.encodeToString(Backup.serializer(), snapshot(signedIn))
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            ?: error("Не удалось открыть файл")
    }

    /** The file's content, read to be confirmed before anything on the phone changes. */
    fun read(context: Context, uri: Uri): Backup {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Не удалось прочитать файл")
        return AppJson.decodeFromString(Backup.serializer(), text)
    }

    /** A workout in the gym or a run is going on: a restore would wipe it, so it waits. */
    suspend fun sessionGoing(): Boolean = repo.db.workouts().active() != null || forma.app.run.RunSession.tracker != null

    /**
     * Replaces the data on the phone with the file's content. Returns the number of finished workouts restored.
     * With the cloud on, the next sync first brings in what the cloud has (newer progress wins, workouts deleted since
     * the file was made stay deleted), then uploads what only the file had: an old file rolls nothing back.
     * Whose the data is stays known: the account of the phone (or [signedIn]), else the file's — the restored data goes
     * only to that account. The caller has asked about a file of another account ([Backup.account]).
     */
    suspend fun restore(b: Backup, signedIn: String? = null): Int {
        if (sessionGoing()) error("Сначала закончи начатую тренировку или пробежку")
        DataLock.mutex.withLock {
            val owner = account(signedIn) ?: b.account
            repo.db.backup().replaceAll(
                b.workouts, b.sets, b.runs, b.points, b.slots, b.stats, b.bodyWeights,
                listOfNotNull(owner?.let { SyncCursorEntity(forma.app.sync.SyncEngine.OWNER, it) }),
            )
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
        // Finished ones, as the question before the restore counts them: one left unfinished in the file is not a workout yet.
        return b.workouts.count { it.status == Status.DONE }
    }
}
