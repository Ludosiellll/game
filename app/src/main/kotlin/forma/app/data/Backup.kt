package forma.app.data

import android.content.Context
import android.net.Uri
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
)

class BackupManager(private val repo: TrainingRepository) {
    suspend fun snapshot(): Backup {
        val b = repo.db.backup()
        val st = repo.store
        return Backup(
            createdAt = System.currentTimeMillis(), profile = st.profileNow(), program = st.programNow(),
            runState = st.runState.let { st.runStateNow() }, settings = st.settingsNow(), liftState = st.liftStateNow(),
            workouts = b.workouts(), sets = b.sets(), runs = b.runs(), points = b.points(), slots = b.slots(), stats = b.stats(),
            bodyWeights = b.bodyWeights(),
        )
    }

    suspend fun export(context: Context, uri: Uri) {
        val json = AppJson.encodeToString(Backup.serializer(), snapshot())
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            ?: error("Не удалось открыть файл")
    }

    /** Replaces all data with the file's content. Returns the number of workouts restored. */
    suspend fun import(context: Context, uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Не удалось прочитать файл")
        val b = AppJson.decodeFromString(Backup.serializer(), text)
        repo.db.backup().replaceAll(b.workouts, b.sets, b.runs, b.points, b.slots, b.stats, b.bodyWeights)
        val st = repo.store
        b.profile?.let { st.setProfile(it) }
        b.program?.let { st.setProgram(it) }
        b.runState?.let { st.setRunState(it) }
        st.setSettings(b.settings)
        st.setLiftState(b.liftState)
        return b.workouts.size
    }
}
