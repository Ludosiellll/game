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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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

@Composable
fun RunSetupScreen(workout: RunWorkout, kind: SessionKind?) {
    val nav = LocalNav.current
    val settings = LocalSettings.current
    val context = LocalContext.current
    var gps by remember { mutableStateOf(settings.gps) }
    var voice by remember { mutableStateOf(settings.voice) }
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
        RunService.start(context, workout, gps && fine, voice, settings.beeps, settings.vibration)
        nav.replaceTop(Screen.RunActive)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { start() }
    ScrollScreen(bottomInset = true) {
        TopBar(if (workout.isOpenEnded) "Свободный бег" else workout.title, onBack = { nav.back() })
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
        } else if (workout.lapMeters != null) {
            Text("Секундомер и круги: время каждого круга — голосом и на экране.", style = T.secondary(15.sp))
        } else {
            Text("Секундомер, дистанция и темп по GPS. Отсечки каждый километр голосом.", style = T.secondary(15.sp))
        }
        val track = workout.lapMeters != null
        if (track) {
            Banner(
                "Стадион, круг ${workout.lapMeters} м. Жми «Круг», когда пересекаешь линию: приложение скажет время круга и в темпе ли ты. " +
                    "Писк — момент, когда ты должен быть на линии. Кнопка «Круг» есть и в уведомлении.",
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
        Banner(
            "Чтобы таймер не засыпал с выключенным экраном (особенно на Xiaomi), сними для «Формы» ограничения фоновой работы и экономии батареи. Нажми, чтобы открыть настройки приложения.",
            F.c.text2,
        ) {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        PrimaryButton("Старт", {
            val need = buildList {
                // Android 12+ ignores a request for precise location that does not ask for approximate too.
                if (gps && RunSession.tracker == null) {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val missing = need.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty() || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                missing.all { it == Manifest.permission.ACCESS_COARSE_LOCATION }
            ) start() else launcher.launch(need.toTypedArray())
        }, Modifier.fillMaxWidth(), color = F.c.endurance, textColor = F.c.onData, icon = Ic.play, height = 60.dp)
    }
}

/** Collapses the segment list: "Ускорения 5 × 3:00" instead of ten rows; on a stadium in laps. Third: the target. */
private fun groupSegments(w: RunWorkout): List<Triple<String, String, String?>> {
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
    var locked by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    val u = ui
    if (u == null) {
        LaunchedEffect(Unit) { delay(1500); if (RunSession.ui.value == null) nav.back() }
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
                val size = 112 * F.type.bigNumberScale
                // "25:04" or "1:05:09" must fit a narrow phone: the timer shrinks instead of being cut.
                NumText(
                    Ru.clock(big), style = T.number(size.sp).copy(lineHeight = (size * 1.07f).sp, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth(), maxLines = 1,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 40.sp, maxFontSize = size.sp, stepSize = 4.sp),
                )
                Text(
                    when {
                        w.isOpenEnded -> "с начала"
                        over -> "сверх плана +${Ru.clock(u.overSec)} · «Стоп», когда хватит"
                        race -> "время забега · финиш — последнее нажатие «Круг»"
                        else -> "осталось в отрезке"
                    },
                    style = T.secondary(13.sp), textAlign = TextAlign.Center,
                )
            }
            if (u.restored && u.state == RunTracker.State.PAUSED) {
                Banner("Телефон закрыл приложение во время пробежки. Время и дистанция до этого момента сохранены: ▶ — продолжить, «Стоп» — закончить.", F.c.warn)
            }

            val target = seg?.pace
            if (lapM != null) {
                // On a stadium: the lap to run and the last one pressed, judged against its target.
                val l = u.lastLap
                val repNo = if (seg?.kind == SegmentKind.WORK) w.segments.take(tl.index + 1).count { it.kind == SegmentKind.WORK } else null
                val lapsInRep = seg?.laps?.let { kotlin.math.ceil(it).toInt() }
                val lapNo = if (repNo != null && l?.rep == repNo && !l.closesRep) l.number + 1 else 1
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        "Цель круга",
                        target?.let { Ru.lapRange(Track.lapSec(it.fast.toDouble(), lapM), Track.lapSec(it.slow.toDouble(), lapM)) } ?: "—",
                        when {
                            w.isOpenEnded -> "свободный бег"
                            repNo != null && lapsInRep != null && lapsInRep > 1 -> "круг ${minOf(lapNo, lapsInRep)} из $lapsInRep"
                            target == null -> "сейчас без темпа"
                            seg.kind != SegmentKind.WORK -> "медленнее — нормально"
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
                        if (inRange) "Сейчас · в коридоре" else "Сейчас", pace?.let { Ru.pace(it) } ?: "–:––", "мин/км, за 30 с",
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
                else Metric(if (u.gps) Ru.km2(u.distanceM) else "—", "км", Modifier.weight(1f), Alignment.Start)
                Metric(Ru.clock(u.elapsedSec), if (w.isOpenEnded || over) "время" else "из ${Ru.clock(w.totalSeconds)}", Modifier.weight(1f), Alignment.CenterHorizontally)
                val clock = u.lapClock
                if (lapM != null && !u.gps && clock != null) Metric(Ru.clock(clock), "этот круг", Modifier.weight(1f), Alignment.End)
                else Metric(u.avgPace?.let { Ru.pace(it) } ?: "–:––", "средний темп", Modifier.weight(1f), Alignment.End)
            }
            Hairline()
            if (!w.isOpenEnded && seg != null) {
                val next = w.segments.getOrNull(tl.index + 1)
                Text(
                    if (next != null) "Далее: ${CoachText.segmentName(w, tl.index + 1).lowercase()}, ${next.laps?.let { Ru.laps(it) } ?: Ru.clock(next.seconds)}" else "Последний отрезок",
                    style = T.secondary(14.sp),
                )
            }
        }

        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (lapM != null && !finished) {
                val running = u.state == RunTracker.State.RUNNING
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                val on = running && !locked
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
                        Text("КРУГ", style = T.body(28.sp, FontWeight.ExtraBold).copy(color = if (on) F.c.onAccent else F.c.text3, letterSpacing = 3.sp))
                        Text(if (running) "жми на линии" else "на паузе", style = T.body(12.sp, FontWeight.SemiBold).copy(color = if (on) F.c.onAccent else F.c.text3))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (!w.isOpenEnded && seg != null) {
                        Box(
                            Modifier.size(width = 104.dp, height = 52.dp).clip(RoundedCornerShape(if (F.shape.pill) 26.dp else F.shape.radiusSmall))
                                .border(if (F.shape.border > 0.dp) F.shape.border else 1.dp, F.c.line, RoundedCornerShape(if (F.shape.pill) 26.dp else F.shape.radiusSmall))
                                .combinedClickable(enabled = !locked, role = Role.Button, onClickLabel = "Следующий отрезок", onClick = {
                                    RunService.send(context, RunService.ACTION_SKIP)
                                }),
                            contentAlignment = Alignment.Center,
                        ) { Text("Отрезок ›", style = T.body(13.sp, FontWeight.Bold).copy(color = if (locked) F.c.text3 else F.c.text2)) }
                    }
                }
                val paused = u.state == RunTracker.State.PAUSED
                Box(
                    Modifier.size(96.dp).clip(if (F.shape.pill) CircleShape else RoundedCornerShape(F.shape.radius))
                        .background(if (locked) F.c.surface2 else F.c.endurance)
                        .semantics { contentDescription = if (paused) "Продолжить" else "Пауза" }
                        .combinedClickable(enabled = !locked, role = Role.Button, onClick = {
                            RunService.send(context, if (paused) RunService.ACTION_RESUME else RunService.ACTION_PAUSE)
                        }),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (paused) Ic.play else Ic.pause, null, tint = if (locked) F.c.text3 else if (F.c.dark) F.c.bg else Color.White, modifier = Modifier.size(36.dp)) }
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
                        Text("Стоп\nудерживай", style = T.body(12.sp, FontWeight.Bold).copy(color = if (locked) F.c.text3 else F.c.bad, lineHeight = 14.sp), textAlign = TextAlign.Center)
                    }
                }
            }
            if (locked) Text("Экран заблокирован от случайных нажатий", style = T.secondary(12.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** A typed time-trial time ("5:52") over [meters], or null when it is not one: no colon, or a pace no one runs. */
private fun trialSeconds(text: String, meters: Int): Int? {
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
            withStyle(androidx.compose.ui.text.SpanStyle(color = colors[i])) { append(Ru.lap(l.seconds).removeSuffix(" с")) }
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
    // What the runner enters survives a turn of the phone.
    var rpe by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(tracker?.workout?.targetRpe ?: 5) }
    var saved by remember { mutableStateOf<Pair<Long, RunSummary>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var testKm by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    // Reps as the runner did them (more than planned counts), prefilled with what the timer saw.
    val repsKind = tracker?.workout?.type.let { it == RunType.INTERVALS || it == RunType.TEMPO || it == RunType.GOAL_PACE }
    var reps by androidx.compose.runtime.saveable.rememberSaveable {
        mutableIntStateOf(tracker?.repsDone(android.os.SystemClock.elapsedRealtime()) ?: 0)
    }
    // A time trial over a distance: its time, prefilled from the lap button.
    var testTime by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(tracker?.takeIf { it.workout.testMeters != null }?.result(android.os.SystemClock.elapsedRealtime(), 5)?.testSeconds?.let { Ru.clock(it) } ?: "")
    }
    // An easy or long run kept going past its plan: the extra counts unless the runner says it was not running.
    var countExtra by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }

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
                    StatTile("Темп", tracker.averagePace(nowMs)?.let { Ru.pace(it) } ?: "—", "средний", F.c.text, Modifier.weight(1f))
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
                        if (countExtra) "Засчитать: следующая пробежка станет длиннее" else "Не считать: например, телефон просто остался включённым",
                        countExtra,
                    ) { countExtra = it }
                }
            } else if (w.type == RunType.TEST && result.workDistanceM == null) {
                NumberField(testKm, { testKm = it }, "Сколько пробежал за 12 минут (если без GPS)", "км")
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
                        reps == w.reps -> "Сделал больше плана — нажми «+»: это учтётся."
                        else -> "Меньше плана — этот шаг повторится."
                    },
                    style = T.secondary(13.sp),
                )
            }
            LapsCard(tracker.laps, test = testM != null)
            SectionLabel("Насколько тяжело было?")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (1..5).forEach { v -> Chip("$v", rpe == v, { rpe = v }, Modifier.weight(1f), minWidth = 0.dp) } }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (6..10).forEach { v -> Chip("$v", rpe == v, { rpe = v }, Modifier.weight(1f), minWidth = 0.dp) } }
            Text(Texts.RPE.first { it.first == rpe }.second + " · по этой оценке приложение решит, усложнять ли следующий бег", style = T.secondary(14.sp))
            PrimaryButton(if (saving) "Сохраняю…" else "Сохранить", {
                if (saving) return@PrimaryButton
                saving = true
                scope.launch {
                    try {
                        var r = tracker.result(android.os.SystemClock.elapsedRealtime(), rpe)
                        parseNum(testKm)?.let { km -> if (r.workDistanceM == null) r = r.copy(workDistanceM = km * 1000) }
                        tracker.workout.testMeters?.let { m -> r = r.copy(testSeconds = trialSeconds(testTime, m)) }
                        if (repsKind) r = r.copy(reps = reps)
                        if (!countExtra && tracker.workout.continuable) {
                            val extra = ((tracker.timelineMs(android.os.SystemClock.elapsedRealtime()) / 1000).toInt() - tracker.workout.totalSeconds).coerceAtLeast(0)
                            r = r.copy(durationSec = (r.durationSec - extra).coerceAtLeast(1))
                        }
                        val points = RunSession.points.map { TrackPointEntity(0, it.timeMs, it.lat, it.lon, it.accuracy) }
                        // The day it was run: a run brought back after the app was closed may be saved days later.
                        saved = repo.saveRun(tracker.workout, r, RunSession.startedAtWall, points, repo.dayOf(RunSession.startedAtWall))
                        RunSession.clear()
                    } finally {
                        saving = false
                    }
                }
            }, Modifier.fillMaxWidth(), color = F.c.endurance, textColor = F.c.onData, enabled = !saving)
            Box(
                Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).clip(RoundedCornerShape(F.shape.radiusSmall))
                    .clickable(enabled = !saving, role = Role.Button, onClick = { confirmDiscard = true })
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Не сохранять", style = T.secondary(14.sp)) }
        } else if (s != null) {
            Text("Сохранено", style = T.screenTitle(), modifier = Modifier.padding(top = 12.dp))
            SectionLabel("Что дальше")
            FCard(gap = 0.dp) {
                s.second.outcomes.forEachIndexed { i, o ->
                    if (i > 0) Hairline()
                    Text(Texts.run(o), style = T.body(15.sp), modifier = Modifier.padding(vertical = 12.dp))
                }
                if (s.second.outcomes.isEmpty()) Text("Пробежка записана в историю.", style = T.body(15.sp), modifier = Modifier.padding(vertical = 12.dp))
            }
            LapsCard(s.second.laps, test = s.second.outcomes.any { it is forma.core.run.RunOutcome.TestTime })
            Text("Нагрузка: ${s.second.load.roundToInt()} (оценка × минуты).", style = T.secondary(13.sp))
            PrimaryButton("Готово", { nav.tab(Screen.Today) }, Modifier.fillMaxWidth())
        }
    }
}


