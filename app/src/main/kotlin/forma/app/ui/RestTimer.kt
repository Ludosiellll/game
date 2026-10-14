package forma.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import forma.app.MainActivity
import forma.app.R
import forma.app.run.Feedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Rest between sets. It lives outside the workout screen and outside the process: the end time is saved, an ongoing
 * notification counts down (with "+15" and "Пропустить"), and an alarm beeps at the end with the screen off or after
 * the system closed the app. After the end the rest stays, counting how long it ran over, until the next set.
 */
@SuppressLint("StaticFieldLeak")
object RestTimer {
    /** [rang]: the end was signalled already (by the app or by the alarm, whichever came first). */
    data class Rest(
        val workoutId: Long, val endAt: Long, val totalMs: Long,
        val beep: Boolean = true, val vibrate: Boolean = true, val rang: Boolean = false,
    )

    /** How long a rest that ran over is still shown ("+0:40"): after that the set was forgotten, not rested for. */
    const val OVER_SHOWN_MS = 30 * 60_000L

    private const val PREFS = "rest_timer"
    private const val CHANNEL = "rest"
    private const val NOTIFICATION_ID = 7302
    /** "Отдых окончен" with the app not on the screen: a channel of its own that sounds, and a notification of its own. */
    internal const val END_CHANNEL = "rest_end"
    private const val END_ID = 7303
    /** How long that notification stays: the countdown's own one goes on saying how long the rest has run over. */
    private const val END_SHOWN_MS = 2 * 60_000L
    internal const val ACTION_END = "forma.rest.END"
    internal const val ACTION_PLUS = "forma.rest.PLUS"
    internal const val ACTION_SKIP = "forma.rest.SKIP"
    private const val EXTRA_END = "end"

    /** The clock; fixed in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** The app is on the screen: its own beep and buzz are heard then. Fixed in tests. */
    internal var onScreen: () -> Boolean = {
        val info = ActivityManager.RunningAppProcessInfo()
        runCatching { ActivityManager.getMyMemoryState(info) }
        info.importance in 1..ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private val scope = MainScope()
    private var job: Job? = null
    // The application context only (never an activity): it lives as long as the process anyway.
    private var app: Context? = null
    private var restored = false
    private val _rest = MutableStateFlow<Rest?>(null)
    val rest: StateFlow<Rest?> = _rest.asStateFlow()

    /** Brings back the rest saved before the system closed the app (its notification and alarm are still there). */
    fun restore(context: Context) {
        val c = context.applicationContext
        app = c
        if (restored) return
        restored = true
        if (_rest.value != null) return
        val r = read(c) ?: return
        if (clock() - r.endAt > OVER_SHOWN_MS) {
            clear(c)
            return
        }
        _rest.value = r
        follow(c, r)
    }

    /**
     * At the start of the app: the saved rest is cleared when its session is no longer going ([going] false: finished or
     * deleted while the app was closed), then [restore] brings back the one still on (and clears one ended long ago).
     */
    suspend fun restore(context: Context, going: suspend (workoutId: Long) -> Boolean) {
        val c = context.applicationContext
        val r = _rest.value ?: read(c)
        if (r != null && !going(r.workoutId) && (_rest.value ?: read(c))?.endAt == r.endAt) {
            app = c
            stop()
        }
        restore(c)
    }

    fun start(context: Context, workoutId: Long, ms: Long, beep: Boolean, vibrate: Boolean) =
        set(context, Rest(workoutId, clock() + ms, ms, beep, vibrate))

    /** ±15 s from the rest bar or the notification; more time also makes the ring longer. After the end, from now. */
    fun adjust(context: Context, deltaMs: Long, beep: Boolean, vibrate: Boolean) {
        val r = _rest.value ?: return
        val now = clock()
        if (r.endAt <= now && deltaMs <= 0) return
        val endAt = maxOf(r.endAt, now) + deltaMs
        set(context, r.copy(endAt = endAt, totalMs = maxOf(r.totalMs + deltaMs.coerceAtLeast(0), endAt - now), beep = beep, vibrate = vibrate, rang = false))
    }

    fun stop() {
        job?.cancel()
        _rest.value = null
        app?.let { clear(it) }
    }

    /** The session [workoutId] finished or deleted: its rest stops (a rest of another one goes on). */
    fun stopFor(context: Context, workoutId: Long) {
        restore(context)
        if (_rest.value?.workoutId == workoutId) stop()
    }

    /** The end set again: exact alarms allowed in the settings while the rest was on make it come on time. */
    fun reschedule(context: Context) {
        val r = _rest.value?.takeIf { !it.rang && it.endAt > clock() } ?: return
        schedule(context.applicationContext, r)
    }

    private fun set(context: Context, r: Rest) {
        val c = context.applicationContext
        app = c
        restored = true
        _rest.value = r
        save(c, r)
        schedule(c, r)
        // A new end: the signal of the one before is no longer true.
        runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(END_ID) }
        notify(c, r)
        follow(c, r)
    }

    /** In the app: the end signalled on time while the process lives; the rest shown as run over for a while after. */
    private fun follow(c: Context, r: Rest) {
        job?.cancel()
        job = scope.launch {
            if (!r.rang) {
                delay((r.endAt - clock()).coerceAtLeast(0))
                ring(c, r.endAt)
            }
            delay((r.endAt + OVER_SHOWN_MS - clock()).coerceAtLeast(0))
            if (_rest.value?.endAt == r.endAt) stop()
        }
    }

    /** The end of the rest that ends at [endAt]: once, whether the app or the alarm gets there first. */
    internal fun ring(context: Context, endAt: Long) {
        val c = context.applicationContext
        val r = _rest.value ?: read(c)?.also { _rest.value = it } ?: return
        if (r.endAt != endAt || r.rang) return
        val done = r.copy(rang = true)
        _rest.value = done
        save(c, done)
        // On the screen the app's own beep is enough. Not on it (the screen off, the phone in a pocket): a notification
        // that sounds by itself, as the system may freeze the app a moment later, and a buzz as an alarm's, which the
        // system lets through from the background.
        val front = onScreen()
        val sounded = !front && r.beep && endAlert(c)
        Feedback.restOver(c, r.beep && !sounded, r.vibrate, alarm = !front)
        notify(c, done)
    }

    /** "Отдых окончен" on a channel that sounds (the buzz is the app's own, by its setting); false when it cannot be shown. */
    private fun endAlert(c: Context): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return false
        if (!notificationsAllowed(c) || !nm.areNotificationsEnabled()) return false
        if (nm.getNotificationChannel(END_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(END_CHANNEL, "Конец отдыха", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Сигнал в конце отдыха, когда приложение не на экране"
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        if (nm.getNotificationChannel(END_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val b = NotificationCompat.Builder(c, END_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rest)
            .setContentTitle("Отдых окончен")
            .setContentText("Пора к следующему подходу")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(c))
            .setAutoCancel(true)
            .setTimeoutAfter(END_SHOWN_MS)
        return runCatching { nm.notify(END_ID, b.build()) }.isSuccess
    }

    // ---------- the alarm: the end on time with the screen off, or after the system closed the app ----------

    private fun endIntent(c: Context, endAt: Long?): PendingIntent = PendingIntent.getBroadcast(
        c, 0, Intent(c, RestReceiver::class.java).setAction(ACTION_END).apply { endAt?.let { putExtra(EXTRA_END, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Exact alarms are allowed (always before Android 12; after that the person may have turned them off). */
    fun exactAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    /** Notifications are allowed: the countdown on the lock screen and its buttons. */
    fun notificationsAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun schedule(c: Context, r: Rest) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = endIntent(c, r.endAt)
        // Without the exact-alarm permission the system may be a little late: the app itself beeps on time while it runs.
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        val ok = exact && runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.endAt, pi) }.isSuccess
        if (!ok) runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.endAt, pi) }
    }

    // ---------- the notification: the countdown, "+15" and "Пропустить" with the screen locked ----------

    private fun notify(c: Context, r: Rest) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Отдых между подходами", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Сколько осталось отдыхать; сигнал в конце даёт само приложение"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        if (!notificationsAllowed(c)) return
        val b = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rest)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(c))
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setWhen(r.endAt)
            // Gone by itself with the rest, even when the system closed the app before it could take it away.
            .setTimeoutAfter((r.endAt + OVER_SHOWN_MS - clock()).coerceAtLeast(1))
        if (r.rang || r.endAt <= clock()) {
            // Counting up from the end: how long the rest has run over.
            b.setContentTitle("Отдых окончен").setContentText("Пора к следующему подходу").setChronometerCountDown(false)
        } else {
            b.setContentTitle("Отдых").setContentText("Следующий подход — по сигналу").setChronometerCountDown(true)
                .addAction(0, "+15", action(c, ACTION_PLUS))
        }
        b.addAction(0, "Пропустить", action(c, ACTION_SKIP))
        runCatching { nm.notify(NOTIFICATION_ID, b.build()) }
    }

    private fun openApp(c: Context) = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(c: Context, a: String) = PendingIntent.getBroadcast(
        c, a.hashCode(), Intent(c, RestReceiver::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE,
    )

    // ---------- saved: the rest outlives the process ----------

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun save(c: Context, r: Rest) {
        prefs(c).edit {
            putLong("workout", r.workoutId).putLong("end", r.endAt).putLong("total", r.totalMs)
                .putBoolean("beep", r.beep).putBoolean("vibrate", r.vibrate).putBoolean("rang", r.rang)
        }
    }

    private fun read(c: Context): Rest? {
        val p = prefs(c)
        if (!p.contains("end")) return null
        return Rest(
            p.getLong("workout", 0), p.getLong("end", 0), p.getLong("total", 0),
            p.getBoolean("beep", true), p.getBoolean("vibrate", true), p.getBoolean("rang", false),
        )
    }

    private fun clear(c: Context) {
        prefs(c).edit { remove("workout").remove("end").remove("total").remove("beep").remove("vibrate").remove("rang") }
        runCatching { c.getSystemService(AlarmManager::class.java)?.cancel(endIntent(c, null)) }
        runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(END_ID) }
    }

    /** The person said no to the hint about the signal with the screen off: not shown again. */
    fun hintOff(context: Context): Boolean = prefs(context).getBoolean("hint_off", false)
    fun setHintOff(context: Context) = prefs(context).edit { putBoolean("hint_off", true) }

    /** Forgets the rest kept in memory, as when the system closes the app; the saved one stays (for tests). */
    internal fun forget() {
        job?.cancel()
        job = null
        _rest.value = null
        restored = false
    }

    internal fun handle(context: Context, intent: Intent) {
        restore(context)
        when (intent.action) {
            ACTION_END -> ring(context, intent.getLongExtra(EXTRA_END, -1))
            ACTION_PLUS -> _rest.value?.let { adjust(context, 15_000, it.beep, it.vibrate) }
            ACTION_SKIP -> stop()
        }
    }
}

/** The rest's alarm and notification buttons; works with the app closed (the rest is read back from its save). */
class RestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = RestTimer.handle(context, intent)
}
