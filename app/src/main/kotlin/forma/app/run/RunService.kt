package forma.app.run

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import forma.app.MainActivity
import forma.app.R
import forma.app.data.AppJson
import forma.core.run.Cue
import forma.core.run.Fix
import forma.core.run.RunEvent
import forma.core.run.RunTracker
import forma.core.run.RunWorkout
import forma.core.text.Ru
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps a run going with the screen off: a foreground service (health type, plus location when GPS is allowed),
 * a partial wake lock for the timer, GPS fixes and the voice coach.
 */
class RunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private var coach: VoiceCoach? = null
    private var wake: PowerManager.WakeLock? = null
    private var locationManager: LocationManager? = null
    private var lastNotifiedIndex = -1
    private var lastNotifyAt = 0L

    private val listener = LocationListener { loc: Location -> onLocation(loc) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_PAUSE -> RunSession.tracker?.let { it.pause(now()); RunSession.publish(now()); notifyNow() }
            ACTION_RESUME -> RunSession.tracker?.let { it.resume(now()); RunSession.publish(now()); notifyNow() }
            ACTION_STOP -> stopRun()
        }
        return START_NOT_STICKY
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun start(intent: Intent) {
        if (RunSession.tracker != null) return
        val json = intent.getStringExtra(EXTRA_WORKOUT) ?: return
        val workout = AppJson.decodeFromString(RunWorkout.serializer(), json)
        val wantGps = intent.getBooleanExtra(EXTRA_GPS, true) && hasLocation()
        coach = VoiceCoach(
            this, intent.getBooleanExtra(EXTRA_VOICE, true), intent.getBooleanExtra(EXTRA_BEEPS, true),
            intent.getBooleanExtra(EXTRA_VIBRATION, true),
        )
        createChannel()
        var type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
        if (wantGps && Build.VERSION.SDK_INT >= 29) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val tracker = RunTracker(workout)
        RunSession.tracker = tracker
        RunSession.gpsEnabled = wantGps
        RunSession.startedAtWall = System.currentTimeMillis()
        RunSession.points.clear()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forma:run").apply { acquire(6 * 60 * 60 * 1000L) }
        if (wantGps) startGps()
        tracker.start(now())
        RunSession.publish(now())
        ticker = scope.launch {
            while (isActive) {
                val t = RunSession.tracker ?: break
                val events = t.tick(now())
                handle(events)
                RunSession.publish(now())
                val idx = t.timeline(now()).index
                if (idx != lastNotifiedIndex || now() - lastNotifyAt > 10_000) notifyNow()
                if (t.timeline(now()).finished && !t.workout.isOpenEnded) {
                    t.finish(now())
                    RunSession.publish(now())
                    notifyNow()
                    break
                }
                delay(250)
            }
        }
    }

    private fun handle(events: List<RunEvent>) {
        val c = coach ?: return
        val w = RunSession.tracker?.workout ?: return
        for (e in events) {
            if (e is RunEvent.Timeline) {
                when (val cue = e.cue) {
                    is Cue.Countdown -> c.countdown(cue.left)
                    is Cue.SegmentStart -> if (cue.index > 0) c.segmentChange()
                    is Cue.Finished -> c.segmentChange()
                    else -> Unit
                }
            }
            CoachText.say(w, e)?.let { c.speak(it) }
        }
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startGps() {
        val lm = getSystemService(LocationManager::class.java) ?: return
        locationManager = lm
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()) }
    }

    private fun onLocation(loc: Location) {
        val t = RunSession.tracker ?: return
        val fix = Fix(loc.time, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 99f)
        val events = t.onFix(fix, now())
        if (t.state == RunTracker.State.RUNNING && fix.accuracy <= 25f) RunSession.points += fix
        handle(events)
    }

    private fun stopRun() {
        RunSession.tracker?.finish(now())
        RunSession.publish(now())
        ticker?.cancel()
        locationManager?.removeUpdates(listener)
        wake?.let { if (it.isHeld) it.release() }
        coach?.release()
        coach = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        locationManager?.removeUpdates(listener)
        wake?.let { if (it.isHeld) it.release() }
        coach?.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Пробежка", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Таймер и дистанция во время бега"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }

    private fun notifyNow() {
        lastNotifyAt = now()
        RunSession.tracker?.let { lastNotifiedIndex = it.timeline(now()).index }
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val t = RunSession.tracker
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
        if (t == null) return b.setContentTitle("Пробежка").build()
        val nowMs = now()
        val tl = t.timeline(nowMs)
        val title = if (t.workout.isOpenEnded) t.workout.title else if (tl.finished) "Готово" else CoachText.segmentName(t.workout, tl.index)
        val parts = mutableListOf<String>()
        if (t.distanceM > 0) parts += Ru.distance(t.distanceM)
        t.currentPace()?.let { parts += "${Ru.pace(it)} /км" }
        if (!t.workout.isOpenEnded) parts += "всего ${Ru.clock(t.activeSec(nowMs))} из ${Ru.clock(t.workout.totalSeconds)}"
        b.setContentTitle(title).setContentText(parts.joinToString(" · "))
        if (t.state == RunTracker.State.RUNNING) {
            if (!t.workout.isOpenEnded && !tl.finished) {
                b.setUsesChronometer(true).setChronometerCountDown(true)
                    .setWhen(System.currentTimeMillis() + tl.segmentRemaining * 1000L)
            } else {
                b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - t.activeMs(nowMs))
            }
            b.addAction(0, "Пауза", action(ACTION_PAUSE))
        } else if (t.state == RunTracker.State.PAUSED) {
            b.addAction(0, "Продолжить", action(ACTION_RESUME))
        }
        return b.build()
    }

    private fun action(a: String) = PendingIntent.getService(
        this, a.hashCode(), Intent(this, RunService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_START = "forma.run.START"
        const val ACTION_PAUSE = "forma.run.PAUSE"
        const val ACTION_RESUME = "forma.run.RESUME"
        const val ACTION_STOP = "forma.run.STOP"
        const val EXTRA_WORKOUT = "workout"
        const val EXTRA_GPS = "gps"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_BEEPS = "beeps"
        const val EXTRA_VIBRATION = "vibration"
        private const val CHANNEL = "run"
        private const val NOTIFICATION_ID = 42

        fun start(ctx: Context, workout: RunWorkout, gps: Boolean, voice: Boolean, beeps: Boolean, vibration: Boolean) {
            val i = Intent(ctx, RunService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_WORKOUT, AppJson.encodeToString(RunWorkout.serializer(), workout))
                .putExtra(EXTRA_GPS, gps).putExtra(EXTRA_VOICE, voice).putExtra(EXTRA_BEEPS, beeps).putExtra(EXTRA_VIBRATION, vibration)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, RunService::class.java).setAction(action))
        }
    }
}
