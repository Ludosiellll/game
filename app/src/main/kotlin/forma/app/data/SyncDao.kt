package forma.app.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert

/** A finished set together with the global id of its workout. */
data class SetWithWorkout(@Embedded val set: SetEntity, val workoutUid: String)

/** A finished run with its workout's global id and a summary of the GPS track (points never change after saving). */
data class RunWithWorkout(
    @Embedded val run: RunEntity,
    val workoutUid: String,
    val points: Int,
    val firstT: Long?,
    val lastT: Long?,
)

/** Everything the cloud sync reads and writes on the phone. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_row WHERE tbl = :tbl") suspend fun rows(tbl: String): List<SyncRowEntity>
    @Upsert suspend fun putRows(rows: List<SyncRowEntity>)
    @Query("DELETE FROM sync_row WHERE tbl = :tbl AND `key` IN (:keys)") suspend fun dropRows(tbl: String, keys: List<String>)
    @Query("SELECT cursor FROM sync_cursor WHERE tbl = :tbl") suspend fun cursor(tbl: String): String?
    @Upsert suspend fun putCursor(c: SyncCursorEntity)
    @Query("DELETE FROM sync_row") suspend fun clearRows()
    @Query("DELETE FROM sync_cursor") suspend fun clearCursors()

    /** Forget what the cloud has seen: the next sync uploads every row and downloads everything. */
    @Transaction
    suspend fun reset() {
        clearRows()
        clearCursors()
    }

    // ---- workouts (only finished ones leave the phone) ----

    @Query("SELECT * FROM workout WHERE status = 'DONE'") suspend fun doneWorkouts(): List<WorkoutEntity>

    /** Finished workouts the cloud has not got yet: new since the last upload (friends do not see them yet). */
    @Query("SELECT COUNT(*) FROM workout WHERE status = 'DONE' AND uid NOT IN (SELECT `key` FROM sync_row WHERE tbl = 'workouts')")
    fun observeNotUploaded(): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT COUNT(*) FROM workout WHERE status = 'DONE' AND uid NOT IN (SELECT `key` FROM sync_row WHERE tbl = 'workouts')")
    suspend fun notUploaded(): Int
    @Query("SELECT * FROM workout WHERE uid = :uid") suspend fun workoutByUid(uid: String): WorkoutEntity?
    @Insert suspend fun insertWorkout(w: WorkoutEntity): Long
    @Update suspend fun updateWorkout(w: WorkoutEntity)
    @Query("DELETE FROM workout WHERE uid = :uid") suspend fun deleteWorkout(uid: String)

    // ---- sets ----

    @Query("SELECT s.*, w.uid AS workoutUid FROM set_entry s JOIN workout w ON w.id = s.workoutId WHERE w.status = 'DONE'")
    suspend fun doneSets(): List<SetWithWorkout>
    @Query("SELECT * FROM set_entry WHERE uid = :uid") suspend fun setByUid(uid: String): SetEntity?
    @Insert suspend fun insertSet(s: SetEntity): Long
    @Update suspend fun updateSet(s: SetEntity)
    @Query("DELETE FROM set_entry WHERE uid = :uid") suspend fun deleteSet(uid: String)

    // ---- runs ----

    @Query(
        "SELECT r.*, w.uid AS workoutUid, " +
            "(SELECT COUNT(*) FROM track_point p WHERE p.workoutId = r.workoutId) AS points, " +
            "(SELECT MIN(p.t) FROM track_point p WHERE p.workoutId = r.workoutId) AS firstT, " +
            "(SELECT MAX(p.t) FROM track_point p WHERE p.workoutId = r.workoutId) AS lastT " +
            "FROM run_entry r JOIN workout w ON w.id = r.workoutId WHERE w.status = 'DONE'",
    )
    suspend fun doneRuns(): List<RunWithWorkout>
    @Query("SELECT * FROM track_point WHERE workoutId = :workoutId ORDER BY t") suspend fun points(workoutId: Long): List<TrackPointEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRun(r: RunEntity)
    @Query("DELETE FROM run_entry WHERE workoutId = :workoutId") suspend fun deleteRun(workoutId: Long)
    @Query("DELETE FROM track_point WHERE workoutId = :workoutId") suspend fun deletePoints(workoutId: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPoints(p: List<TrackPointEntity>)

    @Transaction
    suspend fun replaceRun(r: RunEntity, points: List<TrackPointEntity>) {
        putRun(r)
        deletePoints(r.workoutId)
        if (points.isNotEmpty()) insertPoints(points)
    }

    // ---- per-exercise state and body weight (natural keys, same on every phone) ----

    @Query("SELECT * FROM slot_state") suspend fun slots(): List<SlotStateEntity>
    @Upsert suspend fun putSlot(s: SlotStateEntity)
    @Query("DELETE FROM slot_state WHERE slotKey = :key") suspend fun deleteSlot(key: String)
    @Query("SELECT * FROM slot_state WHERE slotKey = :key") suspend fun slot(key: String): SlotStateEntity?
    @Query("SELECT * FROM exercise_stat WHERE exerciseId = :id") suspend fun stat(id: String): ExerciseStatEntity?
    @Query("SELECT * FROM exercise_stat") suspend fun stats(): List<ExerciseStatEntity>
    @Upsert suspend fun putStat(s: ExerciseStatEntity)
    @Query("DELETE FROM exercise_stat WHERE exerciseId = :id") suspend fun deleteStat(id: String)
    @Query("SELECT * FROM body_weight") suspend fun bodyWeights(): List<BodyWeightEntity>
    @Upsert suspend fun putBodyWeight(b: BodyWeightEntity)
    @Query("DELETE FROM body_weight WHERE epochDay = :day") suspend fun deleteBodyWeight(day: Long)
}
