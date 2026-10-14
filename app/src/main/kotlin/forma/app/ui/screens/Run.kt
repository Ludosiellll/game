package forma.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import forma.app.data.AppJson
import forma.app.data.RunSummary
import forma.app.data.TrackPointEntity
import forma.app.run.CoachText
import forma.app.run.RunService
import forma.app.run.RunSession
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.LocalSettings
import forma.app.ui.Screen
import forma.app.ui.Texts
import forma.app.ui.components.Banner
import forma.app.ui.components.Chip
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.RoundIcon
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.StatTile
import forma.app.ui.components.Stepper
import forma.app.ui.components.TopBar
import forma.app.ui.components.NumText
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import forma.app.ui.theme.labelText
import forma.core.plan.SessionKind
import forma.core.run.Lap
import forma.core.run.RunTracker
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import forma.core.run.Track
import forma.core.text.Ru
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun segmentColor(kind: SegmentKind, work: Color, rest: Color, base: Color): Color = when (kind) {
    SegmentKind.WORK, SegmentKind.RUN -> work
    SegmentKind.RECOVERY, SegmentKind.WALK -> rest
    else -> base
}

/** Whole workout as proportional segments; the done part is solid, the rest faded. */
@Composable
fun SegmentBar(w: RunWorkout, elapsed: Int, modifier: Modifier = Modifier) {
    val work = F.c.endurance
    val rest = F.c.text3
    val base = F.c.line
    val r = if (F.shape.pill) 3.dp else 0.dp
    Canvas(modifier.fillMaxWidth().height(14.dp).semantics { contentDescription = "Ход тренировки" }) {
        val total = w.totalSeconds.coerceAtLeast(1)
        val gap = 2.dp.toPx()
        var x = 0f
        var t = 0
        for (s in w.segments) {
            val sw = (size.width - gap * (w.segments.size - 1)) * s.seconds / total
            val c = segmentColor(s.kind, work, rest, base)
            drawRoundRect(c.copy(alpha = 0.28f), Offset(x, 0f), Size(sw, size.height), CornerRadius(r.toPx()))
            val doneSec = (elapsed - t).coerceIn(0, s.seconds)
            if (doneSec > 0) drawRoundRect(c, Offset(x, 0f), Size(sw * doneSec / s.seconds, size.height), CornerRadius(r.toPx()))
            x += sw + gap
            t += s.seconds
        }
    }
}

/**
 * Before the start. A run the plan put on the stadium can be run on the road this time: no laps, the distance and pace
 * by GPS, auto-pause on easy and free runs.
 */
@Composable
fun RunSetupScreen(workout: RunWorkout, kind: SessionKind?) {
    val repo = LocalRepo.current
    val today = forma.app.ui.rememberToday(repo)
    var road by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val roadRun by androidx.compose.runtime.produceState<RunWorkout?>(null, road) {
        value = when {
            !road -> null
            kind == null -> repo.freeRun(null)
            else -> repo.runFor(kind, today, light = workout.light, road = true)
        }
    }
    val lap = workout.lapMeters
    RunSetupBody(
        if (road) roadRun ?: workout else workout,
        ready = !road || roadRun != null,
        where = lap?.let {
            {
                forma.app.ui.components.Segmented(listOf("Стадион · $it м", "Улица"), if (road) 1 else 0, { i -> road = i == 1 })
            }
        },
    )
}

@Composable
private fun RunSetupBody(workout: RunWorkout, ready: Boolean, where: (@Composable () -> Unit)?) {
    val nav = LocalNav.current
    val repo = LocalRepo.current
    val settings = LocalSettings.current
    val context = LocalContext.current
    // A gym session still open (the gym, then the stadium the same day): the run's time must not end up inside it.
    val open by remember { repo.db.workouts().observeActive() }.collectAsState(initial = null)
    val openLift = open?.takeIf { it.kind == forma.app.data.Kind.LIFT }
    var askLift by remember { mutableStateOf(false) }
    // What will not work without a permission refused, said before the start (the run starts all the same).
    var denied by remember { mutableStateOf<String?>(null) }
    var gps by remember { mutableStateOf(settings.gps) }
    var voice by remember { mutableStateOf(settings.voice) }
    // A free run may have the runner's own target: a lap time on a stadium, a pace on the road.
    var targetText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val target = if (workout.isOpenEnded) freeTarget(targetText, workout.lapMeters) else null
    val targetBad = workout.isOpenEnded && targetText.isNotBlank() && target == null
    val chosen = if (target != null) workout.copy(targetPace = target) else workout
    fun start() {
        // One run at a time: an unfinished or unsaved one is shown instead of starting another over it.
        val current = RunSession.tracker
        if (current != null) {
            nav.replaceTop(if (current.state == RunTracker.State.FINISHED) Screen.RunDone(0) else Screen.RunActive)
            return
        }
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (gps && !fine && granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            android.widget.Toast.makeText(context, "С примерной геопозицией дистанцию не посчитать — бег по времени. Нужна точная.", android.widget.Toast.LENGTH_LONG).show()
        }
        val stride = forma.core.run.Stride.fit(settings.strideSamples.mapNotNull { if (it.size == 2) it[0] to it[1] else null })
        RunService.start(context, chosen, gps && fine, voice, settings.beeps, settings.vibration, settings.trackLine, settings.autoPause, stride)
        nav.replaceTop(Screen.RunActive)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        val warn = deniedWarning(
            gps, granted(Manifest.permission.ACCESS_FINE_LOCATION),
            Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
        )
        if (warn != null && RunSession.tracker == null) denied = warn else start()
    }
    fun go() {
        val need = buildList {
            // Android 12+ ignores a request for precise location that does not ask for approximate too.
            if (gps && RunSession.tracker == null) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            // The step counter: cadence, and the distance where GPS is lost.
            if (Build.VERSION.SDK_INT >= 29 && RunSession.tracker == null) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        val missing = need.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty() || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            missing.all { it == Manifest.permission.ACCESS_COARSE_LOCATION }
        ) start() else launcher.launch(need.toTypedArray())
    }
    if (askLift && openLift != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askLift = false },
            title = { Text("Силовая ещё открыта — закончить её сейчас?") },
            text = { Text("Иначе время пробежки попадёт в силовую: она закончится, когда ты её закроешь.") },
            confirmButton = {
                androidx.compose.material3.TextButton({ askLift = false; finishOnOpen = openLift.id; nav.push(Screen.Workout(openLift.id)) }) { Text("Закончить", color = F.c.accent) }
            },
            dismissButton = { androidx.compose.material3.TextButton({ askLift = false; go() }) { Text("Позже") } },
            containerColor = F.c.surface, titleContentColor = F.c.text, textContentColor = F.c.text2,
        )
    }
    denied?.let { warn ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { denied = null },
            title = { Text("Не всё будет работать") },
            text = { Text(warn) },
            confirmButton = { androidx.compose.material3.TextButton({ denied = null; start() }) { Text("Начать", color = F.c.accent) } },
            dismissButton = { androidx.compose.material3.TextButton({ denied = null; openAppSettings(context) }) { Text("Настройки") } },
            containerColor = F.c.surface, titleContentColor = F.c.text, textContentColor = F.c.text2,
        )
    }
    // Asked again each time the screen comes back (from the system's settings): the banner goes once it is done.
    var batteryFree by remember { mutableStateOf(batteryUnrestricted(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        batteryFree = batteryUnrestricted(context)
        onPauseOrDispose {}
    }
    // No Russian voice in the phone: said here, not discovered on the track by the silence.
    var speaks by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(voice) { if (voice) forma.app.run.VoiceCoach.checkRussian(context) { speaks = it } }
    ScrollScreen(bottomInset = true) {
        TopBar(if (workout.isOpenEnded) "Свободный бег" else workout.title, onBack = { nav.back() })
        where?.invoke()
        if (voice && speaks == false) {
            Banner("В телефоне нет русского голоса: подсказок голосом не будет, только писки. Нажми — откроются настройки синтеза речи, там скачай русский.", F.c.warn) {
                val open = listOf(android.content.Intent("com.android.settings.TTS_SETTINGS"), android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                open.firstOrNull { runCatching { context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
            }
        }
        if (workout.note.isNotEmpty()) Text(workout.note, style = T.body(16.sp))
        if (!workout.isOpenEnded) {
            FCard {
                Row {
                    Text("Всего ${Ru.durationWords(workout.totalSeconds)}", style = T.body(15.sp, FontWeight.Bold), modifier = Modifier.weight(1f))
                    Text("цель: ${Texts.RPE.first { it.first == workout.targetRpe }.second.lowercase()}", style = T.secondary(13.sp))
                }
                SegmentBar(workout, 0)
                Column {
                    groupSegments(workout).forEach { (title, detail, target) ->
                        Hairline()
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(title, style = T.body(15.sp, FontWeight.SemiBold))
                                if (target != null) NumText(target, style = T.secondary(13.sp))
                            }
                            NumText(detail, style = T.number(14.sp, FontWeight.Bold).copy(color = F.c.text2))
                        }
                    }
                }
            }
        } else {
            Text(
                if (workout.lapMeters != null) "Секундомер и круги: время каждого круга — голосом и на экране."
                else "Секундомер, дистанция и темп по GPS. Отсечки каждый километр голосом.",
                style = T.secondary(15.sp),
            )
            val lapM = workout.lapMeters
            NumberField(
                targetText, { targetText = it },
                if (lapM != null) "Цель круга (если хочешь)" else "Цель темпа (если хочешь)",
                if (lapM != null) "с" else "мин/км",
                keyboard = if (lapM != null) KeyboardType.Decimal else KeyboardType.Text,
                placeholder = if (lapM != null) "75" else "5:30",
            )
            Text(
                when {
                    targetBad -> if (lapM != null) "Не похоже на время круга — впиши секунды, например 75." else "Не похоже на темп — впиши минуты и секунды на км, например 5:30."
                    lapM != null -> "С целью после каждого круга скажу, быстро или медленно. Без неё — только время круга."
                    else -> "С целью скажу, если убежишь из темпа. Без неё — только отсечки по километрам."
                },
                style = T.secondary(13.sp).copy(color = if (targetBad) F.c.warn else F.c.text2),
            )
        }
        val track = workout.lapMeters != null
        if (track) {
            Banner(
                "Стадион, круг ${workout.lapMeters} м. Жми «Круг», когда пересекаешь линию: приложение скажет время круга и в темпе ли ты. " +
                    // The pacer beeps only in reps of laps (RunTimeline: Cue.LapMark).
                    (if (workout.reps > 0) "Писк — момент, когда ты должен быть на линии. " else "") +
                    "Кнопка «Круг» есть и в уведомлении." +
                    when {
                        !gps -> ""
                        // The line is learnt from presses on it: until one, GPS has nothing to count laps from.
                        workout.reps == 0 && settings.trackLine != null -> " С GPS круг засчитается сам, даже без нажатия: примерно, ±2–3 с. Круги считаются от линии: начал не на ней — первый раз на линии станет стартом."
                        workout.reps == 0 -> " Нажми «Круг» на линии хотя бы раз — с GPS дальше круги засчитаются сами, примерно (±2–3 с)."
                        else -> " С GPS забытое в отрезке нажатие обычно не пропадёт: когда линия уже известна (её запоминают твои нажатия на ней), круг засчитается сам, примерно (±2–3 с)."
                    },
                F.c.endurance, icon = Ic.info,
            )
        }
        FCard(gap = 4.dp) {
            ToggleRow(
                "GPS: дистанция и темп",
                if (track) "На стадионе круги считает кнопка — GPS можно выключить" else "На беговой дорожке выключи", gps,
            ) { gps = it }
            Hairline()
            ToggleRow("Голосовые подсказки", if (track) "Начало отрезков, время каждого круга, финиш" else "Начало отрезков, километры, финиш", voice) { voice = it }
        }
        // On Xiaomi the autostart is apart from the battery: without it the system does not bring a closed run back.
        var autostartHidden by remember { mutableStateOf(autostartDone(context)) }
        if (!batteryFree) BatteryCard()
        else if (xiaomi() && !autostartHidden) AutostartRow { autostartHidden = true; hideAutostart(context) }
        PrimaryButton("Старт", {
            if (!ready || targetBad) return@PrimaryButton
            if (openLift != null && RunSession.tracker == null) askLift = true else go()
        }, Modifier.fillMaxWidth(), color = F.c.endurance, textColor = F.c.onData, icon = Ic.play, height = 60.dp)
        // Run with a watch and no phone: the numbers go in by hand and the plan moves as after a timed run.
        GhostButton("Бегал без телефона — внести", { if (ready) nav.push(Screen.RunManual(workout)) }, Modifier.fillMaxWidth())
    }
}

/**
 * What will not work with a permission refused, in a sentence or two; null when nothing is missing. [gpsWanted]: GPS was
 * left on for this run.
 */
internal fun deniedWarning(gpsWanted: Boolean, location: Boolean, notifications: Boolean): String? {
    val parts = buildList {
        if (gpsWanted && !location) add("Без доступа к геопозиции не будет дистанции и темпа по GPS — только время, круги и шаги.")
        if (!notifications) add("Без уведомлений не будет таймера и кнопки «Круг» на экране блокировки.")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

private fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** The phone leaves the app out of its battery saving: the run's timer is not put to sleep with the screen off. */
internal fun batteryUnrestricted(context: android.content.Context): Boolean =
    context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

private val XIAOMI = setOf("xiaomi", "redmi", "poco")

/** A Xiaomi, Redmi or POCO phone (MIUI, HyperOS). */
private fun xiaomi() = Build.MANUFACTURER.lowercase() in XIAOMI || Build.BRAND.lowercase() in XIAOMI

/** The first of [intents] the phone opens, else the app's settings page. */
private fun openFirst(context: android.content.Context, vararg intents: android.content.Intent) {
    val all = intents.toList() + android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null),
    )
    all.firstOrNull { runCatching { context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
}

private fun autostartIntent() = android.content.Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")

/** The runner said autostart is on (the phone does not tell): the reminder is hidden on this phone for good. */
private fun autostartDone(context: android.content.Context) =
    context.getSharedPreferences(RUN_PREFS, android.content.Context.MODE_PRIVATE).getBoolean("autostartDone", false)

private fun hideAutostart(context: android.content.Context) {
    context.getSharedPreferences(RUN_PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean("autostartDone", true).apply()
}

private const val RUN_PREFS = "forma_run"

/**
 * Battery saving still on for the app: the system's own request to switch it off, and on Xiaomi (MIUI, HyperOS) the
 * autostart list, which kills a run with the screen off just as surely. Each falls back to the app's settings page.
 */
@android.annotation.SuppressLint("BatteryLife")
@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    fun open(vararg intents: android.content.Intent) = openFirst(context, *intents)
    val xiaomi = xiaomi()
    FCard(gap = 8.dp) {
        Text(
            "Телефон может усыпить таймер с выключенным экраном" + (if (xiaomi) " — на Xiaomi особенно" else "") +
                ". Разреши «Форме» работать без ограничений батареи" + (if (xiaomi) " и включи ей автозапуск." else "."),
            style = T.body(14.sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Без ограничений", {
                open(
                    android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, ("package:" + context.packageName).toUri()),
                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }, Modifier.weight(1f))
            GhostButton(if (xiaomi) "Автозапуск" else "Настройки", { open(autostartIntent()) }, Modifier.weight(1f))
        }
    }
}

/**
 * Xiaomi with the battery saving off already: the autostart is a list of its own, and it is what lets the system bring a
 * run back after it closed the app. A line and two small buttons; "already on" hides it.
 */
@Composable
private fun AutostartRow(onDone: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Включи «Форме» автозапуск: без него Xiaomi не вернёт пробежку, если закроет приложение.", style = T.secondary(13.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Автозапуск", { openFirst(context, autostartIntent()) }, Modifier.weight(1f), height = 40.dp)
            GhostButton("Уже включён", onDone, Modifier.weight(1f), color = F.c.text2, height = 40.dp)
        }
    }
}

/**
 * A free run's own target from what was typed: seconds a lap on a stadium ("75"), minutes and seconds a km on the road
 * ("5:30"); a second a lap or five seconds a km either way. Null when empty or not a pace anyone runs.
 */
internal fun freeTarget(text: String, lapM: Int?): forma.core.run.PaceRange? {
    if (text.isBlank()) return null
    val perKm = if (lapM != null) parseNum(text)?.takeIf { it > 0 }?.let { it / lapM * 1000 } else parseClock(text)?.takeIf { it > 0 }
    if (perKm == null || perKm !in forma.core.run.RunPlan.PLAUSIBLE_PACE) return null
    val spread = if (lapM != null) 1000.0 / lapM else 5.0
    return forma.core.run.PaceRange((perKm - spread).roundToInt(), (perKm + spread).roundToInt())
}

/** Collapses the segment list: "Ускорения 5 × 3:00" instead of ten rows; on a stadium in laps. Third: the target. */
internal fun groupSegments(w: RunWorkout): List<Triple<String, String, String?>> {
    val out = mutableListOf<Triple<String, String, String?>>()
    val lapM = w.lapMeters
    fun target(s: forma.core.run.Segment): String? {
        val p = s.pace ?: return null
        return if (lapM != null) "круг ${Ru.lapRange(Track.lapSec(p.fast.toDouble(), lapM), Track.lapSec(p.slow.toDouble(), lapM))}"
        else "${Ru.pace(p.fast.toDouble())}–${Ru.pace(p.slow.toDouble())} мин/км"
    }
    fun len(s: forma.core.run.Segment) = s.laps?.let { Ru.laps(it) } ?: Ru.clock(s.seconds)
    val works = w.segments.filter { it.kind == SegmentKind.WORK || it.kind == SegmentKind.RUN }
    val rests = w.segments.filter { it.kind == SegmentKind.RECOVERY || it.kind == SegmentKind.WALK }
    w.segments.firstOrNull { it.kind == SegmentKind.WARMUP }?.let { out += Triple(CoachText.segmentName(w, 0), Ru.clock(it.seconds), null) }
    if (works.isNotEmpty()) {
        val same = works.all { len(it) == len(works.first()) }
        val name = when (w.type) {
            RunType.INTERVALS -> "Ускорения"
            RunType.TEMPO -> "Темповые отрезки"
            RunType.GOAL_PACE -> "В темпе цели"
            RunType.RUN_WALK -> "Бег"
            RunType.TEST -> if (w.testMeters != null) "Забег" else "Контрольный отрезок"
            else -> "Бег"
        }
        val detail = if (same && works.size > 1) "${works.size} × ${len(works.first())}" else works.joinToString(" + ") { len(it) }
        out += Triple(name, detail, target(works.first()))
    }
    if (rests.isNotEmpty()) {
        val name = when (w.type) {
            RunType.RUN_WALK -> "Ходьба между"
            RunType.GOAL_PACE -> "Отдых шагом или трусцой"
            else -> "Отдых трусцой"
        }
        out += Triple(name, if (rests.all { it.seconds == rests.first().seconds }) "${rests.size} × ${Ru.clock(rests.first().seconds)}" else "${rests.size} отрезка", null)
    }
    w.segments.lastOrNull { it.kind == SegmentKind.COOLDOWN }?.let { out += Triple(CoachText.segmentName(w, w.segments.lastIndex), Ru.clock(it.seconds), null) }
    return out
}

@Composable
private fun ToggleRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.body(15.sp, FontWeight.SemiBold))
            Text(subtitle, style = T.secondary(13.sp))
        }
        Switch(
            value, onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = F.c.accent, checkedThumbColor = F.c.onAccent, uncheckedTrackColor = F.c.surface2, uncheckedBorderColor = F.c.line),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RunActiveScreen() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val ui by RunSession.ui.collectAsState()
    val settings = LocalSettings.current
    // Kept through a turn of the phone: a run in the pocket must not come back unlocked.
    var locked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    // Over the lock screen while the run screen is open: the clock and "lap" without unlocking.
    DisposableEffect(Unit) {
        val activity = context as? forma.app.MainActivity
        activity?.showOverLock(true)
        onDispose { activity?.showOverLock(false) }
    }
    val u = ui
    val lapsByKey = u != null && u.workout.lapMeters != null && u.state == RunTracker.State.RUNNING
    // The volume-down key presses "lap" (and leaves the volume alone) while a stadium run goes on.
    DisposableEffect(lapsByKey) {
        forma.app.run.LapKey.onLap = if (lapsByKey) ({ RunService.send(context, RunService.ACTION_LAP) }) else null
        onDispose { forma.app.run.LapKey.onLap = null }
    }
    // The run was on this screen and is gone (cleared from elsewhere: the cloud replaced the data): back, as before.
    val had = remember { booleanArrayOf(false) }
    if (u != null) had[0] = true
    if (u == null && had[0]) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    if (u == null) {
        val error by RunSession.startError.collectAsState()
        var late by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { delay(1500); late = true }
        // Not started: said, not a silent step back to the screen before.
        if (error != null || late) {
            ScrollScreen(bottomInset = true) {
                TopBar("Пробежка", onBack = { RunSession.clearStartError(); nav.back() })
                Text(error ?: "Пробежка не запустилась. Попробуй ещё раз.", style = T.body(16.sp))
                PrimaryButton("Назад", { RunSession.clearStartError(); nav.back() }, Modifier.fillMaxWidth())
            }
        }
        return
    }
    val w = u.workout
    val tl = u.timeline
    val seg = tl.segment
    val finished = u.state == RunTracker.State.FINISHED
    LaunchedEffect(finished) { if (finished) nav.replaceTop(Screen.RunDone(0)) }

    val lapM = w.lapMeters
    // An easy or long run kept going past its plan: the clock goes on, the extra counts.
    val over = w.continuable && tl.finished
    val waiting = u.waiting
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        // The numbers scroll on a short phone; the buttons stay at the bottom.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (w.isOpenEnded) "Свободный бег" else w.title, style = T.body(16.sp, FontWeight.ExtraBold))
                    val acc = u.gpsAccuracy
                    Text(
                        when {
                            !u.gps && lapM != null -> "Стадион · круг $lapM м"
                            !u.gps && u.distanceM > 0 -> "Без GPS · дистанция по шагам"
                            !u.gps -> "Без GPS · только время"
                            acc == null -> "GPS · ищем спутники…"
                            acc <= 15 -> "GPS · точность ${acc.roundToInt()} м"
                            else -> "GPS · слабый сигнал (${acc.roundToInt()} м)"
                        },
                        style = T.secondary(12.sp).copy(color = if (u.gps && (acc ?: 99f) <= 15) F.c.good else F.c.text2),
                    )
                }
                RoundIcon(Ic.lock, if (locked) "Разблокировать экран" else "Заблокировать экран", { locked = !locked }, fill = if (locked) F.c.accent else null, tint = if (locked) F.c.onAccent else F.c.text)
            }

            Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    labelText(
                        when {
                            w.isOpenEnded -> "Время"
                            over -> "План выполнен"
                            waiting -> CoachText.segmentName(w, tl.index + 1)
                            seg != null -> CoachText.segmentName(w, tl.index)
                            else -> "Готово"
                        },
                    ),
                    style = T.body(14.sp, FontWeight.ExtraBold).copy(color = F.c.endurance, letterSpacing = if (F.type.capsLabels) 2.sp else 0.sp),
                )
                // A time trial shows the race clock: the finish is the last lap press, not a planned time.
                val race = w.testMeters != null && seg?.kind == SegmentKind.WORK
                val big = when {
                    w.isOpenEnded || over -> u.elapsedSec
                    race -> tl.segmentElapsed
                    else -> tl.segmentRemaining
                }
                // Already huge: a large system font does not make it bigger, it would only push the rest off the screen.
                val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                val size = 112 * F.type.bigNumberScale / fontScale
                if (waiting) {
                    // After the warm-up the plan waits for the runner on the line: the rep starts with the press.
                    BasicText(
                        "На старт", Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        style = T.body((56 / fontScale).sp, FontWeight.ExtraBold).copy(color = F.c.text, textAlign = TextAlign.Center),
                        maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = (56 / fontScale).sp, stepSize = 2.sp),
                    )
                } else {
                    // "25:04" or "1:05:09" must fit a narrow phone: the timer shrinks instead of being cut.
                    NumText(
                        Ru.clock(big), style = T.number(size.sp).copy(lineHeight = (size * 1.07f).sp, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth(), maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 32.sp, maxFontSize = size.sp, stepSize = 4.sp),
                    )
                }
                Text(
                    when {
                        w.isOpenEnded -> "с начала"
                        over -> "сверх плана +${Ru.clock(u.overSec)} · «Стоп», когда хватит"
                        waiting -> CoachText.startWhere(w, tl.index + 1) + " и жми «Круг», когда побежишь: " +
                            (if (w.testMeters != null) "время забега" else "отрезок") + " пойдёт с нажатия"
                        race -> "время забега · финиш — последнее нажатие «Круг»"
                        else -> "осталось в отрезке"
                    },
                    style = T.secondary(13.sp), textAlign = TextAlign.Center,
                )
            }
            if (u.autoPaused) {
                Banner("Автопауза: стоишь — время встало. Побежишь — продолжится само.", F.c.endurance)
            }
            if (u.restored && u.state == RunTracker.State.PAUSED) {
                Banner("Телефон закрыл приложение во время пробежки. Время и дистанция до этого момента сохранены: ▶ — продолжить, «Стоп» — закончить.", F.c.warn)
            }

            // Waiting on the line, the numbers are those of the rep about to start.
            val shownIndex = if (waiting) tl.index + 1 else tl.index
            val shown = if (waiting) w.segments.getOrNull(shownIndex) else seg
            // A free run's own target, if the runner set one.
            val target = shown?.pace ?: w.targetPace?.takeIf { w.isOpenEnded }
            if (lapM != null) {
                // On a stadium: the lap to run and the last one pressed, judged against its target.
                val l = u.lastLap
                val repNo = if (shown?.kind == SegmentKind.WORK) w.segments.take(shownIndex + 1).count { it.kind == SegmentKind.WORK } else null
                val lapsInRep = shown?.laps?.let { kotlin.math.ceil(it).toInt() }
                val lapNo = if (repNo != null && l?.rep == repNo && !l.closesRep) l.number + 1 else 1
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        "Цель круга",
                        target?.let { Ru.lapRange(Track.lapSec(it.fast.toDouble(), lapM), Track.lapSec(it.slow.toDouble(), lapM)) } ?: "—",
                        when {
                            w.isOpenEnded -> if (target != null) "твоя цель" else "свободный бег"
                            waiting -> "отсчёт — с нажатия"
                            repNo != null && lapsInRep != null && lapsInRep > 1 -> "круг ${minOf(lapNo, lapsInRep)} из $lapsInRep"
                            target == null -> "сейчас без темпа"
                            shown?.kind != SegmentKind.WORK -> "медленнее — нормально"
                            else -> "писк — ты на линии"
                        },
                        F.c.text, Modifier.weight(1f),
                    )
                    StatTile(
                        "Последний круг", l?.let { Ru.lap(it.seconds) } ?: "—",
                        when {
                            l == null -> "жми «Круг» на линии"
                            l.verdict != null -> lapVerdict(l)
                            l.rep == null && w.reps > 0 -> "между отрезками"
                            else -> "круг ${u.laps}"
                        },
                        l?.let { lapColor(it) } ?: F.c.text, Modifier.weight(1f),
                    )
                }
            } else if (u.gps && (target != null || u.pace != null)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (target != null) StatTile("Цель", "${Ru.pace(target.fast.toDouble())}–${Ru.pace(target.slow.toDouble())}", "мин/км", F.c.text, Modifier.weight(1f))
                    val pace = u.pace
                    val inRange = pace != null && target != null && pace >= target.fast - 3 && pace <= target.slow + 3
                    StatTile(
                        if (inRange) "Сейчас · в коридоре" else "Сейчас", pace?.let { Ru.pace(it) } ?: "–:––",
                        u.cadence?.let { "мин/км · $it шаг/мин" } ?: "мин/км",
                        if (target == null) F.c.text else if (inRange) F.c.good else F.c.warn, Modifier.weight(1f),
                    )
                }
            }

            if (!w.isOpenEnded) {
                SegmentBar(w, tl.totalElapsed)
            }
            Hairline()
            Row {
                if (lapM != null && !u.gps) Metric("${u.laps}", Ru.plural(u.laps, "круг", "круга", "кругов"), Modifier.weight(1f), Alignment.Start)
                // Without GPS the steps may give the distance (the stride learnt on earlier runs): roughly, but not "—".
                else Metric(if (u.gps || u.distanceM > 0) Ru.km2(u.distanceM) else "—", if (u.gps || u.distanceM <= 0) "км" else "км по шагам", Modifier.weight(1f), Alignment.Start)
                Metric(Ru.clock(u.elapsedSec), if (w.isOpenEnded || over) "время" else "из ${Ru.clock(w.totalSeconds)}", Modifier.weight(1f), Alignment.CenterHorizontally)
                val clock = u.lapClock
                // Waiting on the line no lap is running: the warm-up's twelve minutes are not "this lap".
                if (lapM != null && clock != null) Metric(if (waiting) "—" else Ru.clock(clock), "этот круг", Modifier.weight(1f), Alignment.End)
                else Metric(u.avgPace?.let { Ru.pace(it) } ?: "–:––", "средний темп", Modifier.weight(1f), Alignment.End)
            }
            Hairline()
            if (!w.isOpenEnded && seg != null) {
                // Waiting on the line, the rep about to start is on top already: "next" is what follows it.
                val nextIndex = shownIndex + 1
                val next = w.segments.getOrNull(nextIndex)
                Text(
                    if (next != null) "Далее: ${CoachText.segmentName(w, nextIndex).lowercase()}, ${next.laps?.let { Ru.laps(it) } ?: Ru.clock(next.seconds)}" else "Последний отрезок",
                    style = T.secondary(14.sp),
                )
            }
            // The key reaches the app only while its screen is on (see LapKey): said, not found out on the line.
            if (lapM != null && !finished) Text("Круг — кнопкой громкости вниз, пока экран включён.", style = T.secondary(13.sp))
        }

        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (lapM != null && !finished) {
                val running = u.state == RunTracker.State.RUNNING
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                // The lock guards everything but this button: it is what the runner needs on the line.
                val on = running
                Box(
                    Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(if (F.shape.pill) 42.dp else F.shape.radius))
                        .background(if (on) F.c.accent else F.c.surface2)
                        .semantics { contentDescription = "Круг" }
                        .combinedClickable(enabled = on, role = Role.Button, onClickLabel = "Отметить круг на линии", onClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            RunService.send(context, RunService.ACTION_LAP)
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (waiting) "СТАРТ" else "КРУГ", style = T.body(28.sp, FontWeight.ExtraBold).copy(color = if (on) F.c.onAccent else F.c.text3, letterSpacing = 3.sp))
                        Text(
                            when {
                                !running -> "на паузе"
                                waiting -> "жми, когда побежишь"
                                else -> "жми на линии"
                            },
                            style = T.body(12.sp, FontWeight.SemiBold).copy(color = if (on) F.c.onAccent else F.c.text3),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (!w.isOpenEnded && seg != null) {
                        // Inside a rep (or waiting for one) a stray tap must not throw it away: hold to skip.
                        val hold = waiting || seg.kind == SegmentKind.WORK
                        val skip = { RunService.send(context, RunService.ACTION_SKIP) }
                        Box(
                            Modifier.size(width = 104.dp, height = 52.dp).clip(RoundedCornerShape(if (F.shape.pill) 26.dp else F.shape.radiusSmall))
                                .border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, F.c.line, RoundedCornerShape(if (F.shape.pill) 26.dp else F.shape.radiusSmall))
                                .combinedClickable(
                                    enabled = !locked, role = Role.Button,
                                    onClickLabel = if (hold) "Удерживай, чтобы пропустить отрезок" else "Следующий отрезок", onClick = { if (!hold) skip() },
                                    onLongClickLabel = "Пропустить отрезок", onLongClick = skip,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                if (hold) "Пропустить\nудерживай" else "Дальше ›", Modifier.padding(horizontal = 8.dp),
                                style = T.body(if (hold) 12.sp else 13.sp, FontWeight.Bold).copy(color = if (locked) F.c.text3 else F.c.text2, lineHeight = 1.15.em, textAlign = TextAlign.Center),
                                maxLines = 2, autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = if (hold) 12.sp else 13.sp, stepSize = 0.5.sp),
                            )
                        }
                    }
                }
                val paused = u.state == RunTracker.State.PAUSED
                // On a stadium the pause sits right under the lap button: a slip of the finger must not stop the plan.
                val holdPause = lapM != null && !paused
                val toggle = { RunService.send(context, if (paused) RunService.ACTION_RESUME else RunService.ACTION_PAUSE) }
                val tint = if (locked) F.c.text3 else if (F.c.dark) F.c.bg else Color.White
                Box(
                    Modifier.size(96.dp).clip(if (F.shape.pill) CircleShape else RoundedCornerShape(F.shape.radius))
                        .background(if (locked) F.c.surface2 else F.c.endurance)
                        .semantics { contentDescription = if (paused) "Продолжить" else "Пауза" }
                        .combinedClickable(
                            enabled = !locked, role = Role.Button,
                            onClickLabel = if (holdPause) "Удерживай, чтобы поставить на паузу" else null, onClick = { if (!holdPause) toggle() },
                            onLongClickLabel = if (holdPause) "Пауза" else null, onLongClick = if (holdPause) toggle else null,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (paused) Ic.play else Ic.pause, null, tint = tint, modifier = Modifier.size(if (holdPause) 30.dp else 36.dp))
                        if (holdPause) Text("удерживай", style = T.body(10.sp, FontWeight.Bold).copy(color = tint))
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Box(
                        Modifier.size(width = 104.dp, height = 52.dp).clip(RoundedCornerShape(if (F.shape.pill) 26.dp else F.shape.radiusSmall))
                            .border(if (F.shape.border > 0.dp) F.shape.border else 1.5.dp, if (locked) F.c.line else F.c.bad, RoundedCornerShape(if (F.shape.pill) 26.dp else F.shape.radiusSmall))
                            .semantics { contentDescription = "Стоп" }
                            .combinedClickable(
                                enabled = !locked, role = Role.Button, onClickLabel = "Удерживай, чтобы закончить", onClick = {},
                                onLongClickLabel = "Закончить пробежку", onLongClick = { RunService.send(context, RunService.ACTION_STOP) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "Стоп\nудерживай", Modifier.padding(horizontal = 8.dp),
                            style = T.body(12.sp, FontWeight.Bold).copy(color = if (locked) F.c.text3 else F.c.bad, lineHeight = 1.15.em, textAlign = TextAlign.Center),
                            maxLines = 2, autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
                        )
                    }
                }
            }
            if (locked) {
                Text(
                    if (lapM != null) "Заблокировано всё, кроме «Круг»" else "Экран заблокирован от случайных нажатий",
                    style = T.secondary(12.sp), modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

/** The saved run and its summary through a turn of the phone: the "what next" must not turn into "already saved". */
internal val SavedRunSaver = androidx.compose.runtime.saveable.Saver<Pair<Long, RunSummary>?, String>(
    save = { v -> v?.let { (id, sum) -> "$id|" + forma.app.data.AppJson.encodeToString(RunSummary.serializer(), sum) } },
    restore = { t ->
        runCatching { t.substringBefore('|').toLong() to forma.app.data.AppJson.decodeFromString(RunSummary.serializer(), t.substringAfter('|')) }.getOrNull()
    },
)

internal const val SAVE_FAILED = "Не сохранилось — попробуй ещё раз"

/** A treadmill display's distance typed in km, metres; null when empty or not a distance of a run [sec] long. */
internal fun boardDistance(text: String, sec: Int): Double? {
    val m = parseNum(text)?.takeIf { it > 0 && it <= 100 }?.times(1000) ?: return null
    return m.takeIf { sec <= 0 || sec / (it / 1000) in forma.core.run.RunPlan.PLAUSIBLE_PACE }
}

/** A typed time-trial time ("5:52") over [meters], or null when it is not one: no colon, or a pace no one runs. */
internal fun trialSeconds(text: String, meters: Int): Int? {
    if (':' !in text) return null
    val t = parseClock(text)?.roundToInt() ?: return null
    return t.takeIf { it > 0 && it / (meters / 1000.0) in forma.core.run.RunPlan.PLAUSIBLE_PACE }
}

/** "в темпе", "быстро", "медленно" for a lap with a target; empty without one. */
private fun lapVerdict(l: Lap): String = when (l.verdict) {
    0 -> "в темпе"
    -1 -> "быстро"
    1 -> "медленно"
    else -> ""
}

@Composable
private fun lapColor(l: Lap): Color = when (l.verdict) {
    0 -> F.c.good
    -1 -> F.c.warn
    1 -> F.c.bad
    else -> F.c.text
}

/** Lap times from the lap button, rep by rep, each judged against its target. [test]: the reps are the time trial. */
@Composable
fun LapsCard(laps: List<Lap>, test: Boolean) {
    if (laps.isEmpty()) return
    SectionLabel("Круги")
    // The laps as bars (a half lap as a whole one's pace): an uneven run and a forgotten press are seen at a glance.
    if (laps.size >= 3) {
        forma.app.ui.components.BarChart(
            laps.map { it.seconds / it.part }, F.c.endurance.copy(alpha = 0.55f), height = 90.dp,
            floor = laps.minOf { it.seconds / it.part } * 0.85,
            xLabels = laps.indices.map { if (it == 0 || it == laps.lastIndex) "${it + 1}" else "" },
            description = "Время кругов по порядку: выше столбик — медленнее круг",
        )
    }
    FCard(gap = 0.dp) {
        val reps = laps.filter { it.rep != null }.groupBy { it.rep!! }
        reps.entries.forEachIndexed { i, (rep, ls) ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (test) "Забег" else "Отрезок $rep", style = T.body(15.sp, FontWeight.SemiBold))
                    if (ls.size > 1) LapLine(ls)
                    else ls.first().takeIf { it.verdict != null }?.let { Text(lapVerdict(it), style = T.secondary(13.sp).copy(color = lapColor(it))) }
                }
                val total = ls.sumOf { it.seconds }
                NumText(
                    if (ls.size > 1) Ru.clock(total.roundToInt()) else Ru.lap(total),
                    style = T.number(18.sp).copy(color = if (ls.size == 1) lapColor(ls.first()) else F.c.text),
                )
            }
        }
        val free = laps.filter { it.rep == null }
        if (free.isNotEmpty()) {
            if (reps.isNotEmpty()) Hairline()
            Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (reps.isEmpty()) "Круги по порядку" else "Круги между отрезками", style = T.body(15.sp, FontWeight.SemiBold))
                LapLine(free)
            }
        }
        val race = laps.filter { it.rep != null }
        if (test && race.isNotEmpty()) {
            // Evenness is the skill of the distance: the spread of the race's whole laps (not the half one at the start).
            val median = race.map { it.seconds }.sorted()[race.size / 2]
            val full = race.filter { it.seconds >= median * 0.75 }.map { it.seconds }
            Hairline()
            Text(
                (if (full.size >= 2) "Круги от ${Ru.lap(full.min())} до ${Ru.lap(full.max())}. " else "") +
                    "Начало — по плану (оранжевый — слишком быстро), вторая половина — быстрее, если есть силы.",
                style = T.secondary(12.sp), modifier = Modifier.padding(top = 10.dp),
            )
        } else if (laps.any { it.verdict != null }) {
            Hairline()
            Text("Зелёный — в темпе цели, оранжевый — быстрее, красный — медленнее.", style = T.secondary(12.sp), modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** "52 · 51 · 53" with each lap in the colour of its verdict. */
@Composable
private fun LapLine(laps: List<Lap>) {
    val colors = laps.map { lapColor(it) }
    val text = androidx.compose.ui.text.buildAnnotatedString {
        laps.forEachIndexed { i, l ->
            if (i > 0) append(" · ")
            // A lap GPS counted (no press) is roughly timed: "≈53".
            withStyle(androidx.compose.ui.text.SpanStyle(color = colors[i])) { append((if (l.auto) "≈" else "") + Ru.lap(l.seconds).removeSuffix(" с")) }
        }
    }
    Text(text, style = T.number(14.sp, FontWeight.SemiBold))
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier, align: Alignment.Horizontal) {
    Column(modifier, horizontalAlignment = align) {
        NumText(value, style = T.number(24.sp))
        Text(label, style = T.secondary(11.sp))
    }
}

/** After the run: rate it, save it, see what changes. [workoutId] 0 means "not saved yet". */
@Composable
fun RunDoneScreen(workoutId: Long) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val tracker = remember { RunSession.tracker }
    // What the runner enters survives a turn of the phone. No effort is chosen for them: the next step depends on it,
    // and a value left as it was would count every run as "as planned".
    var rpe by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var saved by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = SavedRunSaver) { mutableStateOf<Pair<Long, RunSummary>?>(null) }
    var saving by remember { mutableStateOf(false) }
    // The save failed (the database, the disk): the run is kept, the runner tries again.
    var failed by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var testKm by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    // On a treadmill (no GPS distance): the distance from its display.
    var boardKm by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    // Reps as the runner did them (more than planned counts), prefilled with what the timer saw.
    val repsKind = tracker?.workout?.type.let { it == RunType.INTERVALS || it == RunType.TEMPO || it == RunType.GOAL_PACE }
    var reps by androidx.compose.runtime.saveable.rememberSaveable {
        mutableIntStateOf(tracker?.repsDone(android.os.SystemClock.elapsedRealtime()) ?: 0)
    }
    // A time trial over a distance: its time, prefilled from the lap button.
    var testTime by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(tracker?.takeIf { it.workout.testMeters != null }?.result(android.os.SystemClock.elapsedRealtime(), 5)?.testSeconds?.let { Ru.clock(it) } ?: "")
    }
    // An easy or long run kept going past its plan: the extra counts unless the runner says it was not running — but not
    // after the hour-long auto-stop, which is a phone forgotten in a pocket far more often than a run.
    var countExtra by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(tracker?.let { (it.timelineMs(android.os.SystemClock.elapsedRealtime()) / 1000).toInt() - it.workout.totalSeconds < 3600 - 60 } ?: true)
    }

    if (workoutId != 0L) {
        // Opened from history: a saved run.
        HistoryItemScreen(workoutId)
        return
    }
    if (tracker == null && saved == null) {
        // Saved or thrown away already (another screen, another try).
        ScrollScreen(bottomInset = true) {
            TopBar("Пробежка", onBack = { nav.tab(Screen.Today) })
            Text("Эта пробежка уже сохранена или удалена — она в истории, если была сохранена.", style = T.body(15.sp))
            PrimaryButton("На главную", { nav.tab(Screen.Today) }, Modifier.fillMaxWidth())
        }
        return
    }
    // Back keeps the run: it waits on the main screen until it is saved or thrown away.
    androidx.activity.compose.BackHandler(enabled = saved == null) { nav.tab(Screen.Today) }
    if (confirmDiscard) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Не сохранять пробежку?") },
            text = { Text("Время, дистанция и маршрут пропадут, план бега не изменится.") },
            confirmButton = {
                androidx.compose.material3.TextButton({ confirmDiscard = false; RunSession.clear(); nav.tab(Screen.Today) }) {
                    Text("Удалить", color = F.c.bad)
                }
            },
            dismissButton = { androidx.compose.material3.TextButton({ confirmDiscard = false }) { Text("Отмена") } },
            containerColor = F.c.surface, titleContentColor = F.c.text, textContentColor = F.c.text2,
        )
    }
    val nowMs = android.os.SystemClock.elapsedRealtime()
    ScrollScreen(bottomInset = true) {
        val s = saved
        if (s == null && tracker != null) {
            val result = tracker.result(nowMs, rpe)
            Text("Пробежка закончена", style = T.screenTitle(), modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Время", Ru.clock(result.durationSec), if (tracker.workout.isOpenEnded) "" else "${(result.completed * 100).roundToInt()}% плана", F.c.text, Modifier.weight(1f))
                val lapM = tracker.workout.lapMeters
                if (lapM != null && result.distanceM == null) {
                    // A stadium without GPS: the laps tell the story.
                    val n = tracker.laps.size
                    StatTile("Круги", "$n", Ru.plural(n, "круг", "круга", "кругов") + " нажато", F.c.endurance, Modifier.weight(1f))
                    StatTile("Отрезки", result.workPace?.let { Ru.lap(Track.lapSec(it, lapM)) } ?: "—", "средний круг", F.c.text, Modifier.weight(1f))
                } else {
                    StatTile("Дистанция", result.distanceM?.let { Ru.distance(it) } ?: "—", "", F.c.endurance, Modifier.weight(1f))
                    StatTile("Темп", tracker.averagePace(nowMs)?.let { Ru.pace(it) } ?: "—", result.cadence?.let { "средний · $it шаг/мин" } ?: "средний", F.c.text, Modifier.weight(1f))
                }
            }
            val w = tracker.workout
            val testM = w.testMeters
            if (testM != null) {
                val lapM = w.lapMeters
                NumberField(
                    testTime, { testTime = it },
                    "Время забега, ${lapM?.let { "${Ru.laps(testM.toDouble() / it)} — " } ?: ""}$testM м",
                    "мм:сс", keyboard = KeyboardType.Text, placeholder = "5:45",
                )
                val typed = trialSeconds(testTime, testM)
                Text(
                    when {
                        testTime.isNotBlank() && typed == null -> "Не похоже на время забега — впиши минуты и секунды через двоеточие, например 5:52."
                        result.testSeconds != null && tracker.laps.any { it.auto } -> "По кнопке «Круг» и GPS: круг без нажатия засчитан примерно (±2–3 с). Если засекал сам — поправь."
                        result.testSeconds != null -> "По кнопке «Круг». Если засекал сам — поправь."
                        else -> "Впиши время с секундомера: без него забег не засчитается."
                    },
                    style = T.secondary(13.sp).copy(color = if (testTime.isNotBlank() && typed == null) F.c.warn else F.c.text2),
                )
            }
            // Past the plan of an easy or long run: the extra minutes grow the next one, so they must be real.
            val over = if (w.continuable) ((tracker.timelineMs(nowMs) / 1000).toInt() - w.totalSeconds).coerceAtLeast(0) else 0
            if (over >= 60) {
                FCard(gap = 4.dp) {
                    Toggle(
                        "Сверх плана +${Ru.clock(over)}",
                        if (countExtra) "Засчитать: если было легко, следующая станет длиннее" else "Не считать: например, телефон просто остался включённым",
                        countExtra,
                    ) { countExtra = it }
                }
            } else if (w.type == RunType.TEST && w.testMeters == null && result.workDistanceM == null) {
                NumberField(testKm, { testKm = it }, "Сколько пробежал за 12 минут (если без GPS)", "км")
            }
            // A treadmill (or GPS off): the distance shown on its display, instead of none or the steps' rough one.
            val board = w.lapMeters == null && w.type != RunType.TEST && (result.distanceM == null || !RunSession.gpsEnabled)
            if (board) {
                NumberField(boardKm, { boardKm = it }, "Дистанция с табло", "км", placeholder = result.distanceM?.let { Ru.one(it / 1000) } ?: "5,0")
                val boardBad = boardKm.isNotBlank() && boardDistance(boardKm, result.durationSec) == null
                Text(
                    when {
                        boardBad -> "Не похоже на дистанцию за это время — впиши километры, например 5,2."
                        result.distanceM != null -> "Без GPS дистанция посчитана по шагам, примерно. Бежал на дорожке — впиши с табло."
                        else -> "Бежал на дорожке — впиши дистанцию с табло: будет темп."
                    },
                    style = T.secondary(13.sp).copy(color = if (boardBad) F.c.warn else F.c.text2),
                )
            }
            if (repsKind && w.reps > 0) {
                SectionLabel("Сколько отрезков сделал")
                Stepper(
                    "$reps", "из ${w.reps}", { reps = (reps - 1).coerceAtLeast(0) }, { reps = (reps + 1).coerceAtMost(w.reps + 4) },
                    Modifier.fillMaxWidth(), minusLabel = "Меньше отрезков", plusLabel = "Больше отрезков",
                )
                Text(
                    when {
                        reps > w.reps -> "Больше плана при той же тяжести — следующий шаг будет крупнее."
                        reps == w.reps -> "Ровно по плану. Сделал больше — нажми «+»: это учтётся."
                        else -> "Меньше плана — шаг повторится, а не получится второй раз подряд — шаг назад."
                    },
                    style = T.secondary(13.sp),
                )
            }
            LapsCard(tracker.laps, test = testM != null)
            SectionLabel("Насколько тяжело было?")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (1..5).forEach { v -> Chip("$v", rpe == v, { rpe = v }, Modifier.weight(1f), minWidth = 0.dp) } }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (6..10).forEach { v -> Chip("$v", rpe == v, { rpe = v }, Modifier.weight(1f), minWidth = 0.dp) } }
            Text(
                Texts.RPE.firstOrNull { it.first == rpe }?.let { it.second + " · по этой оценке приложение решит, усложнять ли следующий бег" }
                    ?: "Оцени, как тяжело было: от этого зависит следующий шаг.",
                style = T.secondary(14.sp).copy(color = if (rpe == 0) F.c.warn else F.c.text2),
            )
            PrimaryButton(if (saving) "Сохраняю…" else if (rpe == 0) "Сначала оценка" else "Сохранить", {
                if (saving || rpe == 0) return@PrimaryButton
                saving = true
                failed = false
                scope.launch {
                    try {
                        var r = tracker.result(android.os.SystemClock.elapsedRealtime(), rpe)
                        parseNum(testKm)?.let { km -> if (r.workDistanceM == null) r = r.copy(workDistanceM = km * 1000) }
                        boardDistance(boardKm, r.durationSec)?.let { m -> r = r.copy(distanceM = m) }
                        tracker.workout.testMeters?.let { m -> r = r.copy(testSeconds = trialSeconds(testTime, m)) }
                        if (repsKind) r = r.copy(reps = reps)
                        if (!countExtra && tracker.workout.continuable) {
                            val extra = ((tracker.timelineMs(android.os.SystemClock.elapsedRealtime()) / 1000).toInt() - tracker.workout.totalSeconds).coerceAtLeast(0)
                            val kept = (r.durationSec - extra).coerceAtLeast(1)
                            // The distance of the time kept, at the run's own pace: else the pace in history is a fiction.
                            val share = kept.toDouble() / r.durationSec.coerceAtLeast(1)
                            r = r.copy(durationSec = kept, distanceM = r.distanceM?.let { it * share })
                        }
                        val points = RunSession.points.map { TrackPointEntity(0, it.timeMs, it.lat, it.lon, it.accuracy) }
                        // The day it was run: a run brought back after the app was closed may be saved days later. A save
                        // that fails keeps the run (on its screen and on disk) for another try.
                        val done = try {
                            repo.saveRun(tracker.workout, r, RunSession.startedAtWall, points, repo.dayOf(RunSession.startedAtWall))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed = true
                            null
                        }
                        if (done != null) {
                            saved = done
                            // The stadium's line as the presses placed it, and the strides measured where GPS was good: next
                            // time GPS laps count from the first one, and steps give the distance where GPS is lost. The
                            // run is saved already: a failure here must not offer to save it twice.
                            val line = tracker.line
                            val strides = tracker.strideSamples()
                            if (line != null || strides.isNotEmpty()) {
                                runCatching {
                                    repo.updateSettings { st ->
                                        st.copy(
                                            trackLine = line?.let { listOf(it.first, it.second) } ?: st.trackLine,
                                            strideSamples = (st.strideSamples + strides.map { listOf(it.first, it.second) }).takeLast(150),
                                        )
                                    }
                                }
                            }
                            RunSession.clear()
                        }
                    } finally {
                        saving = false
                    }
                }
            }, Modifier.fillMaxWidth(), color = F.c.endurance, textColor = F.c.onData, enabled = !saving && rpe > 0)
            if (failed) Text(SAVE_FAILED, style = T.body(14.sp, FontWeight.SemiBold).copy(color = F.c.warn))
            Box(
                Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).clip(RoundedCornerShape(F.shape.radiusSmall))
                    .clickable(enabled = !saving, role = Role.Button, onClick = { confirmDiscard = true })
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Не сохранять", style = T.secondary(14.sp)) }
        } else if (s != null) {
            Text("Сохранено", style = T.screenTitle(), modifier = Modifier.padding(top = 12.dp))
            // How it went and why, from what was saved.
            val w by remember(s.first) { repo.db.workouts().observe(s.first) }.collectAsState(initial = null)
            val run by androidx.compose.runtime.produceState<forma.app.data.RunEntity?>(null, s.first) { value = repo.db.runs().get(s.first) }
            val savedW = w
            val savedRun = run
            if (savedW != null && savedRun != null) RunDebrief(savedW, savedRun, s.second)
            // The plan the run was run to: a shorter run than usual says so instead of "the length stays".
            val savedPlan = remember(savedRun) {
                savedRun?.let { runCatching { forma.app.data.AppJson.decodeFromString(RunWorkout.serializer(), it.planJson) }.getOrNull() }
            }
            SectionLabel("Что дальше в плане")
            FCard(gap = 0.dp) {
                s.second.outcomes.forEachIndexed { i, o ->
                    if (i > 0) Hairline()
                    Text(Texts.run(o, plan = savedPlan), style = T.body(15.sp), modifier = Modifier.padding(vertical = 12.dp))
                }
                if (s.second.outcomes.isEmpty()) Text("Пробежка записана в историю.", style = T.body(15.sp), modifier = Modifier.padding(vertical = 12.dp))
            }
            LapsCard(s.second.laps, test = s.second.outcomes.any { it is forma.core.run.RunOutcome.TestTime })
            Text("Нагрузка: ${s.second.load.roundToInt()} (оценка × минуты).", style = T.secondary(13.sp))
            NextPlannedCard(forma.app.ui.rememberToday(repo))
            NextToday { nav.tab(Screen.Today) }
        }
    }
}


