package forma.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.data.RunSummary
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.Texts
import forma.app.ui.components.Chip
import forma.app.ui.components.FCard
import forma.app.ui.components.Hairline
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.Stepper
import forma.app.ui.components.TopBar
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.core.run.ManualRun
import forma.core.run.RunPlan
import forma.core.run.RunType
import forma.core.run.RunWorkout
import forma.core.run.SegmentKind
import forma.core.run.Track
import forma.core.text.Ru
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A run with a watch (or a stopwatch) and no phone: what it showed goes in by hand, and the plan moves as after a run the
 * phone timed — the time and distance, the reps and their average lap, a trial's time, and the effort.
 */
@Composable
fun RunManualScreen(workout: RunWorkout) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val today = forma.app.ui.rememberToday(repo)
    val lapM = workout.lapMeters
    val testM = workout.testMeters
    val cooper = workout.type == RunType.TEST && testM == null
    val repsKind = (workout.type == RunType.INTERVALS || workout.type == RunType.TEMPO || workout.type == RunType.GOAL_PACE) && workout.reps > 0
    // An easy, long, walk-run or free run is judged by its time: without it there is nothing to go on.
    val timeNeeded = !repsKind && workout.type != RunType.TEST

    var daysAgo by rememberSaveable { mutableIntStateOf(0) }
    var time by rememberSaveable { mutableStateOf("") }
    var km by rememberSaveable { mutableStateOf("") }
    var reps by rememberSaveable { mutableIntStateOf(workout.reps) }
    var lap by rememberSaveable { mutableStateOf("") }
    var trial by rememberSaveable { mutableStateOf("") }
    var testKm by rememberSaveable { mutableStateOf("") }
    var rpe by rememberSaveable { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var saved by rememberSaveable(stateSaver = SavedRunSaver) { mutableStateOf<Pair<Long, RunSummary>?>(null) }

    // What is typed, read; null where it is not a number (an empty field is not a mistake).
    val timeSec = parseClock(time)?.roundToInt()?.takeIf { it > 0 }
    val dist = parseNum(km)?.takeIf { it > 0 }?.times(1000)
    val pace = if (timeSec != null && dist != null) timeSec / (dist / 1000) else null
    val repPace = if (lapM != null) {
        // "52" or "52,5" seconds a lap.
        parseNum(lap)?.takeIf { it > 0 }?.let { it / lapM * 1000 }
    } else {
        parseClock(lap)?.takeIf { it > 0 }
    }
    val trialSec = testM?.let { trialSeconds(trial, it) }
    val testDist = parseNum(testKm)?.takeIf { it > 0 }?.times(1000)

    val timeBad = time.isNotBlank() && (timeSec == null || timeSec > 6 * 3600)
    val kmBad = km.isNotBlank() && (dist == null || dist > 100_000)
    val paceBad = pace != null && pace !in RunPlan.PLAUSIBLE_PACE
    val lapBad = lap.isNotBlank() && (repPace == null || repPace !in RunPlan.PLAUSIBLE_PACE)
    val trialBad = trial.isNotBlank() && trialSec == null
    val testBad = testKm.isNotBlank() && (testDist == null || testDist !in 800.0..5_500.0)
    val ok = rpe > 0 && !timeBad && !kmBad && !paceBad && !lapBad && !trialBad && !testBad && (!timeNeeded || timeSec != null)

    // Once saved, back leads home: the screen before would offer to start this run again.
    val leave = { if (saved != null) nav.tab(Screen.Today) else nav.back() }
    androidx.activity.compose.BackHandler(enabled = saved != null) { nav.tab(Screen.Today) }
    ScrollScreen(bottomInset = true) {
        TopBar("Внести пробежку", if (workout.isOpenEnded) "Свободный бег" else workout.title, onBack = { leave() })
        val s = saved
        if (s != null) {
            Text("Сохранено", style = T.screenTitle(), modifier = Modifier.padding(top = 4.dp))
            SectionLabel("Что дальше")
            FCard(gap = 0.dp) {
                s.second.outcomes.forEachIndexed { i, o ->
                    if (i > 0) Hairline()
                    Text(Texts.run(o, manual = true), style = T.body(15.sp), modifier = Modifier.padding(vertical = 12.dp))
                }
                if (s.second.outcomes.isEmpty()) Text("Пробежка записана в историю.", style = T.body(15.sp), modifier = Modifier.padding(vertical = 12.dp))
            }
            NextToday { nav.tab(Screen.Today) }
            return@ScrollScreen
        }
        Text(
            "Бегал с часами или секундомером, без телефона? Впиши, что они показали: план сдвинется так же, как после пробежки с телефоном.",
            style = T.secondary(14.sp),
        )
        SectionLabel("Когда")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Сегодня", "Вчера", "Позавчера").forEachIndexed { i, label ->
                Chip(label, daysAgo == i, { daysAgo = i }, Modifier.weight(1f), minWidth = 0.dp)
            }
        }

        if (testM != null) {
            NumberField(
                trial, { trial = it },
                "Время забега, ${lapM?.let { "${Ru.laps(testM.toDouble() / it)} — " } ?: ""}$testM м",
                "мм:сс", keyboard = KeyboardType.Text, placeholder = "5:45",
            )
            Text(
                when {
                    trialBad -> "Не похоже на время забега — впиши минуты и секунды через двоеточие, например 5:52."
                    trial.isBlank() -> "Без времени забег не засчитается."
                    else -> "Время от старта до финиша, как на часах."
                },
                style = T.secondary(13.sp).copy(color = if (trialBad || trial.isBlank()) F.c.warn else F.c.text2),
            )
        }
        if (cooper) {
            NumberField(testKm, { testKm = it }, "Сколько пробежал за 12 минут", "км", placeholder = "2,6")
            if (testBad) Text("За 12 минут пробегают от 0,8 до 5,5 км — проверь число.", style = T.secondary(13.sp).copy(color = F.c.warn))
            else if (testKm.isBlank()) Text("Без дистанции тест не засчитается.", style = T.secondary(13.sp).copy(color = F.c.warn))
        }
        if (repsKind) {
            SectionLabel("Сколько отрезков сделал")
            Stepper(
                "$reps", "из ${workout.reps}", { reps = (reps - 1).coerceAtLeast(0) }, { reps = (reps + 1).coerceAtMost(workout.reps + 4) },
                Modifier.fillMaxWidth(), minusLabel = "Меньше отрезков", plusLabel = "Больше отрезков",
            )
            val target = workout.segments.firstOrNull { it.kind == SegmentKind.WORK }?.pace
            NumberField(
                lap, { lap = it },
                if (lapM != null) "Средний круг в отрезках" else "Средний темп отрезков",
                if (lapM != null) "с" else "мин/км",
                keyboard = if (lapM != null) KeyboardType.Decimal else KeyboardType.Text,
                placeholder = if (lapM != null) "52" else "4:30",
            )
            Text(
                when {
                    lapBad -> if (lapM != null) "Не похоже на время круга — впиши секунды, например 52." else "Не похоже на темп — впиши минуты и секунды на км, например 4:30."
                    target == null -> "Если знаешь — с ним видно, держится ли темп."
                    lapM != null -> "Цель — круг ${Ru.lapRange(Track.lapSec(target.fast.toDouble(), lapM), Track.lapSec(target.slow.toDouble(), lapM))}. " +
                        "Без среднего круга шаг решится по ощущениям."
                    else -> "Цель — ${Ru.pace(target.fast.toDouble())}–${Ru.pace(target.slow.toDouble())} на км. Без темпа шаг решится по ощущениям."
                },
                style = T.secondary(13.sp).copy(color = if (lapBad) F.c.warn else F.c.text2),
            )
        }
        NumberField(
            time, { time = it },
            if (timeNeeded) "Время пробежки" else "Время всей тренировки, с разминкой (если знаешь)",
            "мин или ч:мм:сс", keyboard = KeyboardType.Text, placeholder = if (timeNeeded) "45:00" else Ru.clock(workout.totalSeconds),
        )
        if (timeBad) Text("Не похоже на время — впиши минуты («45») или «45:30», «1:05:00».", style = T.secondary(13.sp).copy(color = F.c.warn))
        else if (timeNeeded && timeSec == null) Text("Время нужно: по нему план решит, удлинять ли следующую.", style = T.secondary(13.sp))
        else if (!timeNeeded && timeSec == null) Text("Не впишешь — посчитаю по плану: ${Ru.durationWords(workout.totalSeconds)}.", style = T.secondary(13.sp))
        NumberField(km, { km = it }, "Дистанция (если знаешь)", "км", placeholder = "6,2")
        when {
            kmBad -> Text("Не похоже на дистанцию — впиши километры, например 6,2.", style = T.secondary(13.sp).copy(color = F.c.warn))
            paceBad -> Text("Темп ${Ru.pace(pace!!)} на км — так не бегают: проверь время и дистанцию.", style = T.secondary(13.sp).copy(color = F.c.warn))
            pace != null -> Text("Средний темп ${Ru.pace(pace)} на км.", style = T.secondary(13.sp))
        }

        SectionLabel("Насколько тяжело было?")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (1..5).forEach { v -> Chip("$v", rpe == v, { rpe = v }, Modifier.weight(1f), minWidth = 0.dp) } }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (6..10).forEach { v -> Chip("$v", rpe == v, { rpe = v }, Modifier.weight(1f), minWidth = 0.dp) } }
        Text(
            Texts.RPE.firstOrNull { it.first == rpe }?.let { it.second + " · по этой оценке приложение решит, усложнять ли следующий бег" }
                ?: "Оцени, как тяжело было: от этого зависит следующий шаг.",
            style = T.secondary(14.sp).copy(color = if (rpe == 0) F.c.warn else F.c.text2),
        )
        PrimaryButton(
            when {
                saving -> "Сохраняю…"
                rpe == 0 -> "Сначала оценка"
                else -> "Сохранить"
            },
            {
                if (saving || !ok) return@PrimaryButton
                saving = true
                scope.launch {
                    try {
                        val date = today.minusDays(daysAgo.toLong())
                        val r = RunPlan.manual(
                            workout,
                            ManualRun(
                                durationSec = timeSec, distanceM = dist, rpe = rpe, reps = if (repsKind) reps else null,
                                workPace = repPace, testSeconds = trialSec, testDistanceM = testDist,
                            ),
                        )
                        // Filed at its end today, at midday on an earlier day (each entry its own moment: never merged).
                        val now = System.currentTimeMillis()
                        val startedAt = if (daysAgo == 0) now - r.durationSec * 1000L else repo.middayOf(date) + now % 60_000
                        saved = repo.saveRun(workout, r, startedAt, emptyList(), date, manual = true)
                    } finally {
                        saving = false
                    }
                }
            },
            Modifier.fillMaxWidth(), color = F.c.endurance, textColor = F.c.onData, enabled = ok && !saving,
        )
    }
}
