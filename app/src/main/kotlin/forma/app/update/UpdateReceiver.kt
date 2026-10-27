package forma.app.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import forma.app.MainActivity
import forma.app.R
import forma.app.ui.RestTimer

/**
 * How an update's install went ([ACTION_RESULT] from the system installer): the confirmation Android wants shown, or a
 * failure said on the screen. And once the new version is in ([Intent.ACTION_MY_PACKAGE_REPLACED] — the old process is
 * gone by then): a notification to open it, since the app does not start again by itself.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESULT -> result(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> updated(context)
        }
    }

    private fun result(context: Context, intent: Intent) {
        val u = when (val s = Updater.state.value) {
            is Updater.State.Installing -> s.update
            is Updater.State.Downloading -> s.update
            is Updater.State.Failed -> s.update
            else -> null
        }
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The first update (or Android before 12): the system's own «Установить?», shown over the app.
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> if (u != null) Updater.state.value = Updater.State.Failed(u, why(status))
        }
    }

    private fun updated(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!RestTimer.notificationsAllowed(context) || !nm.areNotificationsEnabled()) return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Обновления", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "«Форма» обновилась — открыть"
            })
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIFICATION, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_run)
                .setContentTitle("«Форма» обновлена")
                .setContentText("Нажми, чтобы открыть — расскажу, что нового")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STATUS)
                .build(),
        )
    }

    companion object {
        const val ACTION_RESULT = "forma.app.update.RESULT"
        private const val CHANNEL = "updates"
        private const val NOTIFICATION = 7301

        /** A failed install in words. */
        fun why(status: Int): String = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Установка отменена. Нажми «Обновить», когда будешь готов."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Не хватает места на телефоне для обновления."
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "Телефон не принял обновление: оно не подходит к установленной версии."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Телефон заблокировал установку. Разреши «Форме» ставить приложения и попробуй ещё раз."
            else -> "Не получилось установить обновление. Попробуй ещё раз."
        }
    }
}
