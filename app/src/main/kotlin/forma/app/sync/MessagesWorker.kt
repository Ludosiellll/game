package forma.app.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import forma.app.FormaApp
import forma.app.MainActivity
import forma.app.R
import forma.core.text.Ru

/**
 * New messages while the app is not open: checked every 15 minutes (the least WorkManager allows; instant ones would
 * need Google's push service) and told once per new message.
 */
class MessagesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cloud = (applicationContext as FormaApp).cloud
        if (!cloud.signedIn) return Result.success()
        val api = cloud.friends ?: return Result.success()
        // Offline or the server busy: the next check tells it.
        val unread = runCatching { api.unread() }.getOrElse { return Result.success() }
        MessageNotice.show(applicationContext, unread)
        return Result.success()
    }
}

/** The notification about new messages: once per message (the newest told is remembered), off when they are read. */
object MessageNotice {
    private const val CHANNEL = "messages"
    private const val ID = 4201
    private const val PREFS = "forma_messages"
    private const val KEY_LAST = "last_told"

    /** What the notification says about [unread]: «Денис: Как прошла тренировка?», or how many from how many people. */
    fun text(unread: Unread): Pair<String, String>? {
        if (unread.count <= 0 || unread.from.isEmpty()) return null
        val first = unread.from.first()
        return if (unread.from.size == 1) {
            (if (first.count == 1) first.name else "${first.name}: ${first.count} ${Ru.plural(first.count, "сообщение", "сообщения", "сообщений")}") to (first.last ?: "")
        } else {
            "${unread.count} ${Ru.plural(unread.count, "новое сообщение", "новых сообщения", "новых сообщений")}" to unread.from.joinToString(", ") { it.name }
        }
    }

    /** Tells [unread] when it holds a message newer than the last one told; true when a notification went up. */
    fun show(c: Context, unread: Unread): Boolean {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (unread.count <= 0) {
            c.getSystemService(NotificationManager::class.java)?.cancel(ID)
            return false
        }
        if (unread.lastId <= prefs.getLong(KEY_LAST, 0)) return false
        prefs.edit().putLong(KEY_LAST, unread.lastId).apply()
        val (title, body) = text(unread) ?: return false
        val nm = c.getSystemService(NotificationManager::class.java) ?: return false
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed || !nm.areNotificationsEnabled()) return false
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Сообщения", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Новые сообщения от людей в приложении"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        }
        val open = PendingIntent.getActivity(
            c, ID, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_OPEN, OPEN_MESSAGES),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return runCatching { nm.notify(ID, n) }.isSuccess
    }

    /** The messages are read in the app: the notification about them goes. */
    fun clear(c: Context) {
        c.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    const val EXTRA_OPEN = "forma.open"
    const val OPEN_MESSAGES = "messages"
}
