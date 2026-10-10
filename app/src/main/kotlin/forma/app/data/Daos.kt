package forma.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    @Insert suspend fun insert(w: WorkoutEntity): Long
    @Update suspend fun update(w: WorkoutEntity)
    @Query("SELECT * FROM workout WHERE id = :id") suspend fun get(id: Long): WorkoutEntity?
    @Query("SELECT * FROM workout WHERE id = :id") fun observe(id: Long): Flow<WorkoutEntity?>
    @Query("SELECT * FROM workout WHERE status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1") fun observeActive(): Flow<WorkoutEntity?>
    @Query("SELECT * FROM workout WHERE status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1") suspend fun active(): WorkoutEntity?
    @Query("SELECT * FROM workout WHERE status = 'DONE' AND epochDay BETWEEN :from AND :to ORDER BY startedAt")
    fun observeDone(from: Long, to: Long): Flow<List<WorkoutEntity>>
    @Query("SELECT * FROM workout WHERE status = 'DONE' ORDER BY startedAt DESC") fun observeHistory(): Flow<List<WorkoutEntity>>
    @Query("SELECT * FROM workout WHERE status = 'DONE' ORDER BY startedAt") suspend fun allDone(): List<WorkoutEntity>
    @Query("DELETE FROM workout WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT COUNT(*) FROM workout WHERE status = 'DONE'") fun observeDoneCount(): Flow<Int>
    @Query("SELECT * FROM workout WHERE kind = 'RUN' AND startedAt = :startedAt LIMIT 1") suspend fun runStartedAt(startedAt: Long): WorkoutEntity?
}

@Dao
interface SetDao {
    @Insert suspend fun insert(s: SetEntity): Long
    @Update suspend fun update(s: SetEntity)
    @Query("DELETE FROM set_entry WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM set_entry WHERE workoutId = :workoutId ORDER BY position, doneAt") fun observeFor(workoutId: Long): Flow<List<SetEntity>>
    @Query("SELECT * FROM set_entry WHERE workoutId = :workoutId ORDER BY position, doneAt") suspend fun forWorkout(workoutId: Long): List<SetEntity>
    @Query(
        "SELECT s.* FROM set_entry s JOIN workout w ON w.id = s.workoutId " +
            "WHERE w.status = 'DONE' AND s.exerciseId = :exerciseId ORDER BY w.startedAt, s.doneAt",
    )
    suspend fun history(exerciseId: String): List<SetEntity>
    @Query(
        "SELECT s.* FROM set_entry s JOIN workout w ON w.id = s.workoutId WHERE w.status = 'DONE' ORDER BY w.startedAt, s.doneAt",
    )
    fun observeAllDone(): Flow<List<SetEntity>>
    @Query(
        "SELECT s.* FROM set_entry s JOIN workout w ON w.id = s.workoutId " +
            "WHERE w.status = 'DONE' AND s.exerciseId = :exerciseId ORDER BY w.startedAt DESC, s.doneAt LIMIT 40",
    )
    suspend fun recent(exerciseId: String): List<SetEntity>
}

@Dao
interface RunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(r: RunEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPoints(p: List<TrackPointEntity>)
    @Query("SELECT * FROM run_entry WHERE workoutId = :id") suspend fun get(id: Long): RunEntity?
    @Query("SELECT * FROM track_point WHERE workoutId = :id ORDER BY t") suspend fun points(id: Long): List<TrackPointEntity>
    @Query("SELECT r.* FROM run_entry r JOIN workout w ON w.id = r.workoutId WHERE w.status = 'DONE' ORDER BY w.startedAt")
    fun observeAll(): Flow<List<RunEntity>>
}

@Dao
interface StateDao {
    @Upsert suspend fun upsertSlot(s: SlotStateEntity)
    @Query("SELECT * FROM slot_state") suspend fun slots(): List<SlotStateEntity>
    @Query("SELECT * FROM slot_state WHERE slotKey = :key") suspend fun slot(key: String): SlotStateEntity?
    @Upsert suspend fun upsertStat(s: ExerciseStatEntity)
    @Query("SELECT * FROM exercise_stat") suspend fun stats(): List<ExerciseStatEntity>
    @Query("SELECT * FROM exercise_stat") fun observeStats(): Flow<List<ExerciseStatEntity>>
    @Query("SELECT * FROM exercise_stat WHERE exerciseId = :id") suspend fun stat(id: String): ExerciseStatEntity?
    @Upsert suspend fun upsertBodyWeight(b: BodyWeightEntity)
    @Query("SELECT * FROM body_weight ORDER BY epochDay") fun observeBodyWeight(): Flow<List<BodyWeightEntity>>
    @Query("SELECT * FROM body_weight ORDER BY epochDay DESC LIMIT 1") suspend fun lastBodyWeight(): BodyWeightEntity?
    @Query("SELECT * FROM body_weight ORDER BY epochDay") suspend fun bodyWeights(): List<BodyWeightEntity>
    @Query("SELECT * FROM slot_state") fun observeSlots(): Flow<List<SlotStateEntity>>
}

@Dao
interface BackupDao {
    @Query("SELECT * FROM workout") suspend fun workouts(): List<WorkoutEntity>
    @Query("SELECT * FROM set_entry") suspend fun sets(): List<SetEntity>
    @Query("SELECT * FROM run_entry") suspend fun runs(): List<RunEntity>
    @Query("SELECT * FROM track_point") suspend fun points(): List<TrackPointEntity>
    @Query("SELECT * FROM slot_state") suspend fun slots(): List<SlotStateEntity>
    @Query("SELECT * FROM exercise_stat") suspend fun stats(): List<ExerciseStatEntity>
    @Query("SELECT * FROM body_weight") suspend fun bodyWeights(): List<BodyWeightEntity>

    @Query("DELETE FROM workout") suspend fun clearWorkouts()
    @Query("DELETE FROM slot_state") suspend fun clearSlots()
    @Query("DELETE FROM exercise_stat") suspend fun clearStats()
    @Query("DELETE FROM body_weight") suspend fun clearBodyWeights()
    @Query("DELETE FROM sync_row") suspend fun clearSyncRows()
    @Query("DELETE FROM sync_cursor") suspend fun clearSyncCursors()

    @Insert suspend fun insertWorkouts(x: List<WorkoutEntity>)
    @Insert suspend fun insertSets(x: List<SetEntity>)
    @Insert suspend fun insertRuns(x: List<RunEntity>)
    @Insert suspend fun insertPoints(x: List<TrackPointEntity>)
    @Insert suspend fun insertSlots(x: List<SlotStateEntity>)
    @Insert suspend fun insertStats(x: List<ExerciseStatEntity>)
    @Insert suspend fun insertBodyWeights(x: List<BodyWeightEntity>)

    /**
     * The file's rows instead of the phone's, in one transaction. What the cloud has seen is forgotten in the same
     * transaction: the next sync merges the cloud copy in first and never takes the file's gaps for deletions.
     */
    @Transaction
    suspend fun replaceAll(
        workouts: List<WorkoutEntity>, sets: List<SetEntity>, runs: List<RunEntity>, points: List<TrackPointEntity>,
        slots: List<SlotStateEntity>, stats: List<ExerciseStatEntity>, bodyWeights: List<BodyWeightEntity>,
    ) {
        clearWorkouts(); clearSlots(); clearStats(); clearBodyWeights(); clearSyncRows(); clearSyncCursors()
        insertWorkouts(workouts); insertSets(sets); insertRuns(runs); insertPoints(points)
        insertSlots(slots); insertStats(stats); insertBodyWeights(bodyWeights)
    }
}
