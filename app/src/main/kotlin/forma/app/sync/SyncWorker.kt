package forma.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import forma.app.FormaApp

/** Background sync: runs when the phone is online; retries with back-off on network trouble. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cloud = (applicationContext as FormaApp).cloud
        val result = cloud.syncNow()
        return when {
            result.isSuccess -> Result.success()
            result.exceptionOrNull() is NotSignedInException -> Result.success()
            runAttemptCount < 5 -> Result.retry()
            else -> Result.failure()
        }
    }
}
