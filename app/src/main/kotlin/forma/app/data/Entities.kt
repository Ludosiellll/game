package forma.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

object Kind {
    const val LIFT = "LIFT"
    const val RUN = "RUN"
}

object Status {
    const val ACTIVE = "ACTIVE"
    const val DONE = "DONE"
}

/** Global id for rows that other devices must recognise (the local Long ids differ per phone). */
fun newUid(): String = java.util.UUID.randomUUID().toString()

@Entity(tableName = "workout", indices = [Index("epochDay"), Index("status"), Index("uid", unique = true)])
@Serializable
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val kind: String,
    val templateId: String?,
    val title: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: String,
    val rpe: Int? = null,
    val durationSec: Int? = null,
    val load: Double? = null,
    val deload: Boolean = false,
    /** Prescriptions shown at the start (LiftPlan JSON) — evaluation compares against them. */
    val planJson: String? = null,
    /** What changed after the session (JSON list of SlotReport / RunOutcome). */
    val summaryJson: String? = null,
    val note: String? = null,
    val uid: String = newUid(),
)

@Entity(
    tableName = "set_entry",
    indices = [Index("workoutId"), Index("exerciseId"), Index("uid", unique = true)],
    foreignKeys = [ForeignKey(WorkoutEntity::class, ["id"], ["workoutId"], onDelete = ForeignKey.CASCADE)],
)
@Serializable
data class SetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: String,
    val slotKey: String,
    /** Order of the exercise inside the workout. */
    val position: Int,
    val weight: Double,
    val reps: Int,
    val rir: Int?,
    val targetWeight: Double?,
    val targetReps: Int?,
    val doneAt: Long,
    val uid: String = newUid(),
)

@Entity(
    tableName = "run_entry",
    foreignKeys = [ForeignKey(WorkoutEntity::class, ["id"], ["workoutId"], onDelete = ForeignKey.CASCADE)],
)
@Serializable
data class RunEntity(
    @PrimaryKey val workoutId: Long,
    val type: String,
    val planJson: String,
    val completed: Double,
    val movingSec: Int,
    val distanceM: Double?,
    val avgPace: Double?,
    val workDistanceM: Double?,
    val workPace: Double?,
    val vdotAfter: Double?,
)

@Entity(
    tableName = "track_point",
    primaryKeys = ["workoutId", "t"],
    foreignKeys = [ForeignKey(WorkoutEntity::class, ["id"], ["workoutId"], onDelete = ForeignKey.CASCADE)],
)
@Serializable
data class TrackPointEntity(val workoutId: Long, val t: Long, val lat: Double, val lon: Double, val accuracy: Float)

@Entity(tableName = "slot_state")
@Serializable
data class SlotStateEntity(
    @PrimaryKey val slotKey: String,
    val exerciseId: String,
    val weight: Double?,
    val reps: Int,
    val stalls: Int,
    val misses: Int,
    val sessions: Int,
    val updatedAt: Long,
)

@Entity(tableName = "exercise_stat")
@Serializable
data class ExerciseStatEntity(
    @PrimaryKey val exerciseId: String,
    /** Smoothed working estimate used for new prescriptions. */
    val e1rm: Double,
    /** Best single-session estimate ever. */
    val best: Double,
    val updatedAt: Long,
)

@Entity(tableName = "body_weight")
@Serializable
data class BodyWeightEntity(@PrimaryKey val epochDay: Long, val kg: Double)

/** Fingerprint of a row as the cloud last saw it (after an upload or a download). */
@Entity(tableName = "sync_row", primaryKeys = ["tbl", "key"])
data class SyncRowEntity(val tbl: String, val key: String, val hash: String)

/** Server time of the newest change downloaded per cloud table. */
@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(@PrimaryKey val tbl: String, val cursor: String)
