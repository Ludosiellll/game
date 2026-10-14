package forma.app.run

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import forma.app.MainActivity
import forma.app.R

/**
 * The loud notice that a run stopped behind the runner's back: the system closed the app mid-run and the run could not
 * carry on by itself (Android does not always let an app in the background go back to the foreground). Its own channel
 * of high importance: the run's ongoing notification is silent and often folded away, this one must be seen.
 */
object RunAlerts {
    private const val CHANNEL = "run_alert"
    const val INTERRUPTED_ID = 43
    const val TITLE = "Пробежка прервалась — нажми, чтобы продолжить"

    /** How long after the process starts the run service has to pick a running run up before the runner is told. */
    const val CHECK_AFTER_MS = 4_000L

    fun interrupted(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Пробежка прервалась", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Если телефон остановил пробежку"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(TITLE)
            .setContentText("Телефон закрыл приложение. Время и дистанция до этого момента сохранены.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            // Posted again over itself (the service, then the check after the start): one sound, not two.
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(INTERRUPTED_ID, n) }
    }

    fun cancel(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(INTERRUPTED_ID) }
    }

    /**
     * At the start of the process: a run that was going when the app was closed, and a few seconds later still not
     * carried on by the service (it was not brought back, or could not go to the foreground) — the runner is told, unless
     * the app is on the screen already (it shows the run itself) or the service told them already.
     */
    fun checkAfterStart(ctx: Context) {
        if (!RunSession.interrupted()) return
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ checkNow(app) }, CHECK_AFTER_MS)
    }

    internal fun checkNow(ctx: Context) {
        if (RunSession.interrupted() && !onScreen() && !showing(ctx)) interrupted(ctx)
    }

    /** The notice is up already: the service told the runner as it gave up. */
    private fun showing(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.activeNotifications?.any { it.id == INTERRUPTED_ID } }.getOrNull() == true

    private fun onScreen(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        runCatching { ActivityManager.getMyMemoryState(info) }
        return info.importance in 1..ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
