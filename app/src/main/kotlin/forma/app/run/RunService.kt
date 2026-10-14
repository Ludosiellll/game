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
import android.os.Bundle
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
import forma.core.run.Press
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
 * a partial wake lock for the timer, GPS fixes and the voice coach. The run is written to disk every few seconds. A
 * run the system kills together with the app is brought back by the system ([START_STICKY]): it goes on where it was
 * saved, the gap filled from the step counter, and the voice says so; when the foreground is refused, a loud
 * notification says the run stopped ([RunAlerts]). Opened by hand, the app shows it paused (see [RunSession.init]).
 */
class RunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private var coach: VoiceCoach? = null
    private var wake: PowerManager.WakeLock? = null
    private var locationManager: LocationManager? = null
    private var lastNotifiedIndex = -1
    private var lastNotifyAt = 0L
    private var lastSavedAt = 0L

    // An object, not a lambda: before Android 11 the provider callbacks have no default bodies.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Brought back by the system after it closed the app mid-run.
            restart()
            return START_STICKY
        }
        when (intent.action) {
            ACTION_START -> start(intent)
            // By hand: the auto-pause steps aside until the runner runs again.
            ACTION_PAUSE -> control { autoPause.reset(); RunSession.autoPaused = false; it.pause(now()) }
            ACTION_RESUME -> if (attach()) control { autoPause.reset(); RunSession.autoPaused = false; it.resume(now()) }
            ACTION_SKIP -> control { it.skipSegment(now()) }
            ACTION_LAP -> lap()
            ACTION_STOP -> stopRun()
        }
        // A control sent to a run that is not going in this service (a restored, paused one) needs no service.
        if (ticker == null) stopSelf(startId)
        return START_STICKY
    }

    /** The gap left by a kill, filled once the step counter is read again: its wall-clock length and the run's record. */
    private var pendingGap: Pair<Long, RunGap>? = null

    /**
     * The process was killed mid-run and the system started the service again: the run saved on disk (loaded by
     * [RunSession.init]) goes on — from now, and the time missed as far as the steps cover it ([RunTracker.fillGap]).
     * Without the foreground (refused in the background on newer Android) nothing can run: the runner is told loudly.
     */
    private fun restart() {
        if (ticker != null) return
        val t = RunSession.tracker
        val gap = RunSession.gap
        if (t == null || t.state == RunTracker.State.FINISHED) {
            stopSelf()
            return
        }
        if (!attach()) {
            RunAlerts.interrupted(this)
            stopSelf()
            return
        }
        if (gap?.running == true) {
            t.resume(now())
            if (gap.savedAtWall > 0) pendingGap = (System.currentTimeMillis() - gap.savedAtWall).coerceIn(0, MAX_GAP_MS) to gap
        }
        coach?.speak(if (t.state == RunTracker.State.RUNNING) "Пробежка восстановлена, продолжаем." else "Пробежка восстановлена, она на паузе.")
        RunSession.publish(now())
        RunSession.save(now())
        notifyNow()
    }

    private fun now() = SystemClock.elapsedRealtime()

    /** Five-minute marks past the plan already announced. */
    private var overMark = 0

    /** When the coach last asked to press "lap" at the start while the plan waits on the line. */
    private var waitSaidAt = 0L

    /** The reps' laps said, once (see [afterReps]). */
    private var lapsSaid = false

    /** Stops the clock at a standstill on the road (easy, long and free runs) and goes on with running. */
    private val autoPause = forma.core.run.AutoPause()
    private val paceAlarm = forma.core.run.PaceAlarm()

    private fun control(action: (RunTracker) -> Unit) {
        val t = RunSession.tracker ?: return
        action(t)
        RunSession.publish(now())
        RunSession.save(now())
        if (ticker != null) notifyNow()
    }

    /** The runner crossed the line: the lap's time is said and judged against the target; on the start line — "Старт". */
    private fun lap() {
        val t = RunSession.tracker ?: return
        when (val p = t.press(now())) {
            Press.Ignored -> return
            Press.Start -> coach?.speak("Старт.", now = true)
            is Press.Done -> {
                val l = p.lap
                coach?.speak(CoachText.lap(t.workout, l, l.rep?.let { t.repTimes().getOrNull(it - 1) }), now = true)
            }
        }
        RunSession.publish(now())
        RunSession.save(now())
        if (ticker != null) notifyNow()
    }

    private fun start(intent: Intent) {
        val fresh = RunSession.tracker == null
        val gps = hasLocation() && if (fresh) intent.getBooleanExtra(EXTRA_GPS, true) else RunSession.gpsEnabled
        // Started with startForegroundService(): in the foreground first, whatever happens next, or the system
        // kills the app a few seconds later.
        if (!foreground(gps)) {
            if (fresh) RunSession.failStart(START_REFUSED)
            return
        }
        if (ticker != null) return
        if (fresh) {
            val workout = intent.getStringExtra(EXTRA_WORKOUT)
                ?.let { runCatching { AppJson.decodeFromString(RunWorkout.serializer(), it) }.getOrNull() }
            if (workout == null) RunSession.failStart(UNREADABLE)
            if (workout != null) {
                val tracker = RunTracker(workout)
                // The stadium's line from the last session: laps GPS sees count from the first one.
                intent.getDoubleArrayExtra(EXTRA_LINE)?.takeIf { it.size == 2 && workout.lapMeters != null }?.let { tracker.setLine(it[0], it[1]) }
                intent.getDoubleArrayExtra(EXTRA_STRIDE)?.takeIf { it.size == 2 }?.let { tracker.setStride(it[0], it[1]) }
                RunSession.begin(
                    tracker, gps, intent.getBooleanExtra(EXTRA_VOICE, true),
                    intent.getBooleanExtra(EXTRA_BEEPS, true), intent.getBooleanExtra(EXTRA_VIBRATION, true),
                    intent.getBooleanExtra(EXTRA_AUTOPAUSE, true),
                )
                tracker.start(now())
            }
        }
        // A finished run waits to be saved on its screen; a new one cannot start over it.
        if (RunSession.tracker?.state == RunTracker.State.FINISHED || !attach()) leave()
    }

    /** GPS lost and back on the road, for the voice. */
    private val gpsWatch = forma.core.run.GpsWatch()

    /** Puts the current run in the foreground: notification, wake lock, GPS, voice and the ticker. */
    private fun attach(): Boolean {
        val t = RunSession.tracker ?: return false
        if (ticker != null) return true
        if (t.state == RunTracker.State.FINISHED) return false
        val gps = RunSession.gpsEnabled && hasLocation()
        if (!foreground(gps)) return false
        RunSession.attached()
        RunAlerts.cancel(this)
        // Marks past the plan already behind (a run brought back after the app was closed) are not said again.
        overMark = ((t.timelineMs(now()) / 1000).toInt() - t.workout.totalSeconds).coerceAtLeast(0) / 300
        lapsSaid = afterReps(t)
        coach = VoiceCoach(this, RunSession.voice, RunSession.beeps, RunSession.vibration)
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forma:run").apply { acquire(6 * 60 * 60 * 1000L) }
        if (gps) startGps()
        startSteps()
        RunSession.publish(now())
        RunSession.save(now())
        ticker = scope.launch {
            while (isActive) {
                // The run was cleared from elsewhere (the cloud replaced the data): nothing to keep in the foreground.
                val tr = RunSession.tracker ?: run {
                    leave()
                    break
                }
                val events = tr.tick(now())
                handle(events)
                // The reps are over: how their laps went, in a sentence — the full debrief comes with the saved run.
                if (!lapsSaid && afterReps(tr)) {
                    lapsSaid = true
                    CoachText.lapsSummary(tr.workout, tr.laps)?.let { coach?.speak(it) }
                }
                // A lap nobody pressed, seen by GPS crossing the line: in a rep, or any lap of a run without reps, it is
                // said (roughly timed, and judged when there is a target).
                tr.autoLap(now())?.let { l ->
                    coach?.speak(CoachText.autoLap(l), now = true)
                    RunSession.save(now())
                }
                checkPace(tr)
                checkGps(tr)
                if (events.any { (it as? RunEvent.Timeline)?.cue is Cue.ToLine }) waitSaidAt = now()
                // Waiting on the line: a reminder every minute, in case the first one was missed.
                if (tr.state == RunTracker.State.RUNNING && tr.waiting(now()) && now() - waitSaidAt > 60_000) {
                    waitSaidAt = now()
                    coach?.speak(CoachText.waitingReminder(tr.workout))
                }
                RunSession.publish(now())
                val idx = tr.timeline(now()).index
                if (idx != lastNotifiedIndex || now() - lastNotifyAt > 10_000) notifyNow()
                if (now() - lastSavedAt > 5_000) {
                    lastSavedAt = now()
                    RunSession.save(now())
                }
                // Easy and long runs go on past the plan if the runner wants to (more counts): every five minutes the
                // coach says so, and an hour past the plan the run ends (a phone forgotten in a pocket).
                if (tr.state == RunTracker.State.RUNNING && tr.workout.continuable && tr.timeline(now()).finished) {
                    val over = (tr.timelineMs(now()) / 1000).toInt() - tr.workout.totalSeconds
                    val mark = over / 300
                    if (mark > overMark) {
                        overMark = mark
                        coach?.speak("Сверх плана ${Ru.minutes(mark * 5)}. «Стоп», когда хватит.")
                    }
                }
                val overLimit = tr.workout.continuable && (tr.timelineMs(now()) / 1000).toInt() - tr.workout.totalSeconds >= 3600
                if (tr.state == RunTracker.State.RUNNING && tr.timeline(now()).finished && !tr.workout.isOpenEnded && (!tr.workout.continuable || overLimit)) {
                    tr.finish(now())
                    RunSession.publish(now())
                    RunSession.save(now())
                    notifyNow()
                    // Let the coach finish the last sentence, then leave the foreground.
                    delay(4000)
                    leave()
                    break
                }
                delay(250)
            }
        }
        return true
    }

    /** startForeground with location when GPS is used; falls back to the health type alone if that is refused. */
    private fun foreground(gps: Boolean): Boolean {
        createChannel()
        val base = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
        val type = if (gps && Build.VERSION.SDK_INT >= 29) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else base
        val ok = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type) }.isSuccess ||
            (type != base && runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), base) }.isSuccess)
        if (!ok) stopSelf()
        return ok
    }

    /**
     * On the road, the pace against the target of the moment: a rep's range, an easy run's fast limit, a free run's own
     * target. Off it for a while — said ("Медленнее цели — прибавь"). Not on a stadium: there each lap is judged.
     */
    private fun checkPace(tr: RunTracker) {
        val w = tr.workout
        if (w.lapMeters != null || !RunSession.gpsEnabled) return
        if (tr.state != RunTracker.State.RUNNING || tr.waiting(now())) {
            // A stop (a traffic light, a pause) starts the watch over: no "slower" the moment the run goes on.
            paceAlarm.update(now(), null, null, null, 0)
            return
        }
        val tl = tr.timeline(now())
        val seg = tl.segment
        val (fast, slow, easy) = when {
            w.isOpenEnded -> w.targetPace?.let { Triple(it.fast.toDouble(), it.slow.toDouble(), false) }
            seg?.kind == forma.core.run.SegmentKind.WORK -> seg.pace?.let { Triple(it.fast.toDouble(), it.slow.toDouble(), false) }
            seg?.kind == forma.core.run.SegmentKind.RUN -> seg.pace?.let { Triple(it.fast.toDouble(), null, true) }
            else -> null
        } ?: Triple(null, null, false)
        val into = if (w.isOpenEnded) tr.activeMs(now()) else tl.segmentElapsed * 1000L
        // No fresh pace (GPS gone a while) is no alarm: the watch starts over when it comes back.
        paceAlarm.update(now(), tr.currentPace(now()), fast, slow, into)?.let { coach?.speak(CoachText.pace(it, easy)) }
    }

    /**
     * On the road, GPS lost for a while and back, said once each way: the distance goes on by steps meanwhile (when the
     * stride is known), or stands. Not on a stadium, where the laps are the button's.
     */
    private fun checkGps(tr: RunTracker) {
        if (tr.workout.lapMeters != null || !RunSession.gpsEnabled) return
        when (gpsWatch.tick(now(), tr.state == RunTracker.State.RUNNING)) {
            forma.core.run.GpsWatch.Say.LOST -> coach?.speak(if (tr.cadence() != null) "GPS потерян, считаю по шагам." else "GPS потерян.")
            forma.core.run.GpsWatch.Say.BACK -> coach?.speak("GPS снова есть.")
            null -> Unit
        }
    }

    /**
     * A quarter of a minute into what follows the last rep: the reps' laps are all in (a last press a little late still
     * counts), and the cool-down has minutes left — said at the finish, the sentence would be cut off as the run ends.
     */
    private fun afterReps(tr: RunTracker): Boolean {
        val lastWork = tr.workout.segments.indexOfLast { it.kind == forma.core.run.SegmentKind.WORK }
        if (lastWork < 0) return false
        val tl = tr.timeline(now())
        return tl.index > lastWork + 1 || (tl.index == lastWork + 1 && tl.segmentElapsed >= 15)
    }

    private fun handle(events: List<RunEvent>) {
        val c = coach ?: return
        val w = RunSession.tracker?.workout ?: return
        for (e in events) {
            if (e is RunEvent.Timeline) {
                when (val cue = e.cue) {
                    is Cue.Countdown -> c.countdown(cue.left)
                    is Cue.LapMark -> c.lapMark()
                    is Cue.SegmentStart -> if (cue.index > 0) c.segmentChange()
                    is Cue.ToLine, is Cue.Finished -> c.segmentChange()
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
        val fix = Fix(loc.time, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 99f, if (loc.hasSpeed()) loc.speed else null)
        gpsWatch.fix(now(), fix.accuracy <= 25f)
        applyAutoPause(t, autoPauseOn(t) { autoPause.update(fix.speed, now(), t.state == RunTracker.State.RUNNING, fix.accuracy) })
        val events = t.onFix(fix, now())
        if (t.state == RunTracker.State.RUNNING && fix.accuracy <= 25f) RunSession.points += fix
        handle(events)
    }

    /**
     * The auto-pause: only on the road (not on a stadium, where standing at the line between reps is part of the plan)
     * and only for runs without a timed plan to keep — easy, long and free. [judge] runs only then.
     */
    private fun autoPauseOn(t: RunTracker, judge: () -> forma.core.run.AutoPause.Act?): forma.core.run.AutoPause.Act? {
        val w = t.workout
        if (!RunSession.autoPause || w.lapMeters != null || !(w.continuable || w.isOpenEnded)) return null
        if (t.state == RunTracker.State.FINISHED) return null
        return judge()
    }

    private fun applyAutoPause(t: RunTracker, act: forma.core.run.AutoPause.Act?) {
        when (act) {
            is forma.core.run.AutoPause.Act.Pause -> {
                t.pause(act.atMs)
                RunSession.autoPaused = true
                coach?.speak("Автопауза.")
            }
            forma.core.run.AutoPause.Act.Resume -> {
                if (t.state == RunTracker.State.PAUSED) t.resume(now())
                RunSession.autoPaused = false
                coach?.speak("Продолжаем.")
            }
            null -> return
        }
        RunSession.publish(now())
        RunSession.save(now())
        notifyNow()
    }

    /** "Stop" held on the run screen: the run is finished and waits on its screen to be saved. */
    private fun stopRun() {
        RunSession.tracker?.let {
            it.finish(now())
            RunSession.publish(now())
            RunSession.save(now())
        }
        leave()
    }

    /** The step counter (when allowed and the phone has one): cadence, and the distance where GPS is lost. */
    private val stepListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val t = RunSession.tracker ?: return
            val total = e.values[0].toLong()
            pendingGap?.let { (ms, gap) ->
                // The first reading after a restart: the steps taken while the app was gone (none known after a reboot,
                // when the counter starts over).
                pendingGap = null
                val steps = gap.stepTotal?.let { total - it }?.takeIf { it >= 0 }
                if (t.fillGap(ms, steps, now()) > 0) {
                    RunSession.publish(now())
                    RunSession.save(now())
                }
            }
            // Read while paused too: steps again are what ends an auto-pause, GPS or not.
            applyAutoPause(t, autoPauseOn(t) { autoPause.steps(total, now()) })
            handle(t.onSteps(total, now()))
        }

        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    private fun startSteps() {
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) return
        val sm = getSystemService(android.hardware.SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER) ?: return
        runCatching { sm.registerListener(stepListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL) }
    }

    private fun stopSteps() {
        runCatching { getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(stepListener) }
    }

    /** Out of the foreground and gone; the run itself stays in [RunSession]. */
    private fun leave() {
        ticker?.cancel()
        ticker = null
        stopSteps()
        locationManager?.removeUpdates(listener)
        locationManager = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        coach?.release()
        coach = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        RunSession.tracker?.let { RunSession.save(now()) }
        stopSteps()
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
        val waiting = t.waiting(nowMs)
        val title = when {
            t.workout.isOpenEnded -> t.workout.title
            tl.finished -> "Готово"
            waiting -> "Жду старта: жми «Круг» на линии"
            else -> CoachText.segmentName(t.workout, tl.index)
        }
        val parts = mutableListOf<String>()
        if (t.distanceM > 0) parts += Ru.distance(t.distanceM)
        t.currentPace()?.let { parts += "${Ru.pace(it)} /км" }
        if (!t.workout.isOpenEnded) parts += "всего ${Ru.clock(t.activeSec(nowMs))}, план ${Ru.clock(t.workout.totalSeconds)}"
        b.setContentTitle(title).setContentText(parts.joinToString(" · "))
        if (t.state == RunTracker.State.RUNNING) {
            if (waiting) {
                b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - t.activeMs(nowMs))
            } else if (!t.workout.isOpenEnded && !tl.finished) {
                b.setUsesChronometer(true).setChronometerCountDown(true)
                    .setWhen(System.currentTimeMillis() + tl.segmentRemaining * 1000L)
            } else {
                b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - t.activeMs(nowMs))
            }
            // On a stadium the lap button is on the lock screen too, alone: "pause" next to it gets pressed by mistake.
            if (t.workout.lapMeters != null) b.addAction(0, "Круг", action(ACTION_LAP))
            else b.addAction(0, "Пауза", action(ACTION_PAUSE))
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
        const val ACTION_SKIP = "forma.run.SKIP"
        const val ACTION_LAP = "forma.run.LAP"
        const val EXTRA_WORKOUT = "workout"
        const val EXTRA_GPS = "gps"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_BEEPS = "beeps"
        const val EXTRA_VIBRATION = "vibration"
        const val EXTRA_LINE = "line"
        const val EXTRA_AUTOPAUSE = "autopause"
        const val EXTRA_STRIDE = "stride"
        private const val CHANNEL = "run"
        private const val NOTIFICATION_ID = 42

        /** A gap longer than this after a kill is not filled: the run most likely ended out there. */
        private const val MAX_GAP_MS = 30 * 60_000L

        const val START_REFUSED = "Телефон не дал запустить пробежку. Попробуй ещё раз; если повторится — сними для «Формы» ограничения батареи."
        const val UNREADABLE = "Не получилось прочитать тренировку. Открой её заново и нажми «Старт»."

        fun start(
            ctx: Context, workout: RunWorkout, gps: Boolean, voice: Boolean, beeps: Boolean, vibration: Boolean,
            line: List<Double>? = null, autoPause: Boolean = true, stride: Pair<Double, Double>? = null,
        ) {
            val i = Intent(ctx, RunService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_WORKOUT, AppJson.encodeToString(RunWorkout.serializer(), workout))
                .putExtra(EXTRA_GPS, gps).putExtra(EXTRA_VOICE, voice).putExtra(EXTRA_BEEPS, beeps).putExtra(EXTRA_VIBRATION, vibration)
                .putExtra(EXTRA_AUTOPAUSE, autoPause)
            if (stride != null) i.putExtra(EXTRA_STRIDE, doubleArrayOf(stride.first, stride.second))
            if (line != null && line.size == 2) i.putExtra(EXTRA_LINE, line.toDoubleArray())
            RunSession.clearStartError()
            // Refused outright (a vendor's limits on starting in the background): said on the run screen.
            runCatching { ContextCompat.startForegroundService(ctx, i) }.onFailure { RunSession.failStart(START_REFUSED) }
        }

        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, RunService::class.java).setAction(action))
        }
    }
}
