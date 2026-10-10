package forma.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WorkoutEntity::class, SetEntity::class, RunEntity::class, TrackPointEntity::class,
        SlotStateEntity::class, ExerciseStatEntity::class, BodyWeightEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class FormaDatabase : RoomDatabase() {
    abstract fun workouts(): WorkoutDao
    abstract fun sets(): SetDao
    abstract fun runs(): RunDao
    abstract fun state(): StateDao
    abstract fun backup(): BackupDao

    companion object {
        fun create(context: Context, inMemory: Boolean = false): FormaDatabase {
            val b = if (inMemory) Room.inMemoryDatabaseBuilder(context, FormaDatabase::class.java)
            else Room.databaseBuilder(context, FormaDatabase::class.java, "forma.db")
            return b.build()
        }
    }
}
