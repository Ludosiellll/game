package forma.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.components.Chip
import forma.app.ui.components.FCard
import forma.app.ui.components.Hairline
import forma.app.ui.components.TopBar
import forma.app.ui.components.NumText
import forma.app.ui.theme.F
import forma.app.ui.theme.T
import forma.core.analytics.Points
import forma.core.model.Sex
import forma.core.run.HeartRate
import forma.core.run.PaceTable
import forma.core.run.Vdot
import forma.core.strength.OneRm
import forma.core.strength.Plates
import forma.core.strength.WarmUp
import forma.core.text.Ru
import kotlin.math.roundToInt

@Composable
fun CalculatorsScreen() {
    val nav = LocalNav.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Максимум", "Блины", "Разминка", "Темп", "Прогноз", "Пульс", "DOTS")
    ScrollScreen {
        TopBar("Калькуляторы", onBack = { nav.back() })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEachIndexed { i, t -> Chip(t, tab == i, { tab = i }) }
        }
        when (tab) {
            0 -> OneRmCalc()
            1 -> PlatesCalc()
            2 -> WarmUpCalc()
            3 -> PaceCalc()
            4 -> RaceCalc()
            5 -> HeartCalc()
            else -> DotsCalc()
        }
    }
}

@Composable
private fun ResultRows(rows: List<Pair<String, String>>) {
    FCard(gap = 0.dp) {
        rows.forEachIndexed { i, (a, b) ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(a, style = T.body(15.sp), modifier = Modifier.weight(1f))
                NumText(b, style = T.number(15.sp, FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun OneRmCalc() {
    var w by remember { mutableStateOf("80") }
    var r by remember { mutableStateOf("6") }
    var rir by remember { mutableStateOf("0") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(w, { w = it }, "Вес", "кг", Modifier.weight(1f))
        NumberField(r, { r = it }, "Повторы", modifier = Modifier.weight(1f), keyboard = KeyboardType.Number)
        NumberField(rir, { rir = it }, "В запасе", modifier = Modifier.weight(1f), keyboard = KeyboardType.Number)
    }
    val weight = parseNum(w) ?: return
    val reps = r.toIntOrNull()?.coerceIn(1, 30) ?: return
    val res = rir.toIntOrNull()?.coerceIn(0, 6) ?: 0
    val e = OneRm.estimate(weight, reps, res)
    FCard(gap = 4.dp) {
        Text("Вес, который ты поднимешь 1 раз", style = T.secondary(13.sp))
        NumText("${Ru.kg((e * 2).roundToInt() / 2.0)} кг", style = T.number(40.sp).copy(color = F.c.strength))
        Text("Посчитано по таблице RPE. Другие формулы: Эпли ${Ru.kg((OneRm.epley(weight, reps + res) * 2).roundToInt() / 2.0)}, Бжицки ${if (reps + res < 37) Ru.kg((OneRm.brzycki(weight, reps + res) * 2).roundToInt() / 2.0) else "—"}. Точнее всего при 3–8 повторах.", style = T.secondary(12.sp))
    }
    ResultRows(listOf(1, 2, 3, 5, 8, 10, 12, 15).map { n -> "${Ru.reps(n)} до отказа" to "${Ru.kg((OneRm.loadFor(e, n) * 2).roundToInt() / 2.0)} кг · ${(OneRm.fractionForRtf(n.toDouble()) * 100).roundToInt()}%" })
}

@Composable
private fun PlatesCalc() {
    var t by remember { mutableStateOf("82,5") }
    var bar by remember { mutableStateOf(20.0) }
    NumberField(t, { t = it }, "Вес на штанге", "кг")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(20.0, 15.0, 10.0).forEach { b -> Chip("гриф ${Ru.kg(b)}", bar == b, { bar = b }, Modifier.weight(1f)) }
    }
    val target = parseNum(t) ?: return
    val l = Plates.loadout(target, bar)
    FCard(gap = 10.dp) {
        Text("На каждую сторону", style = T.secondary(13.sp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            l.perSide.forEach { p ->
                val h = (30 + p * 2.4).dp
                Box(
                    Modifier.size(width = if (p >= 10) 16.dp else 10.dp, height = h).clip(RoundedCornerShape(3.dp)).background(
                        when (p) { 25.0 -> Color(0xFFD94A3F); 20.0 -> Color(0xFF3D7EDE); 15.0 -> Color(0xFFE2B33C); 10.0 -> Color(0xFF3FA165); else -> F.c.text2 },
                    ),
                )
            }
            Box(Modifier.size(width = 60.dp, height = 6.dp).background(F.c.text3))
        }
        NumText(if (l.perSide.isEmpty()) "Только гриф" else l.perSide.joinToString(" + ") { Ru.kg(it) }, style = T.number(24.sp))
        if (!l.isExact(target)) Text("Ровно ${Ru.kg(target)} не собрать — получится ${Ru.kg(l.total)} кг.", style = T.secondary(13.sp).copy(color = F.c.warn))
    }
}

@Composable
private fun WarmUpCalc() {
    var t by remember { mutableStateOf("100") }
    NumberField(t, { t = it }, "Рабочий вес", "кг")
    val w = parseNum(t) ?: return
    val sets = WarmUp.sets(w, 2.5, 20.0)
    ResultRows(sets.mapIndexed { i, s -> "Подход ${i + 1}" to "${Ru.kg(s.weight)} кг × ${s.reps}" } + ("Рабочий" to "${Ru.kg(w)} кг"))
    Text("Между разминочными подходами отдыхай 1–2 минуты, перед рабочим — от 2 минут.", style = T.secondary(13.sp))
}

/** "50,6 с", "1:28,4": a lap time with tenths (a whole second off the goal lap adds up over the laps of a race). */
fun lapTenths(sec: Double): String {
    val t = kotlin.math.round(sec * 10).toLong()
    val whole = t / 10
    return if (whole < 60) "${Ru.one(t / 10.0)} с" else "${whole / 60}:${"%02d".format(whole % 60)},${t % 10}"
}

/** Pace from a distance and a time: the lap of one's own stadium, 400 m, 1 km, 1500 m and longer at that pace. */
@Composable
private fun PaceCalc() {
    val repo = LocalRepo.current
    val profile by repo.store.profile.collectAsState(initial = null)
    var km by remember { mutableStateOf("5") }
    var time by remember { mutableStateOf("27:30") }
    var lap by remember(profile?.lapMeters) { mutableStateOf((profile?.lapMeters ?: 400).toString()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(km, { km = it }, "Дистанция", "км", Modifier.weight(1f))
        NumberField(time, { time = it }, "Время", "мм:сс", Modifier.weight(1f), keyboard = KeyboardType.Text)
    }
    NumberField(lap, { lap = it.filter(Char::isDigit) }, "Длина круга стадиона", "м", keyboard = KeyboardType.Number, placeholder = "400")
    val d = parseNum(km)?.takeIf { it > 0 }
        ?: return Text("Впиши дистанцию в километрах, например 1,5.", style = T.secondary(13.sp).copy(color = F.c.bad))
    val s = parseClock(time)?.takeIf { it > 0 }
        ?: return Text("Впиши время: минуты и секунды через двоеточие, например 5:30.", style = T.secondary(13.sp).copy(color = F.c.bad))
    val pace = s / d
    val lapM = lap.toIntOrNull()?.takeIf { it in 100..1000 }
    ResultRows(
        listOfNotNull(
            "Темп" to "${Ru.pace(pace)} /км",
            "Скорость" to "${Ru.one(d / (s / 3600))} км/ч",
            lapM?.takeIf { it != 400 }?.let { "Круг $it м" to lapTenths(pace * it / 1000) },
            "400 м" to lapTenths(pace * 0.4),
            "1 км" to Ru.clock(pace.roundToInt()),
            "1500 м этим темпом" to Ru.clock((pace * 1.5).roundToInt()),
            "5 км этим темпом" to Ru.clock((pace * 5).roundToInt()),
            "10 км этим темпом" to Ru.clock((pace * 10).roundToInt()),
        ),
    )
    if (lap.isNotBlank() && lapM == null) Text("Длина круга — от 100 до 1000 м.", style = T.secondary(13.sp).copy(color = F.c.bad))
}

/** Distances the race forecast lists, m. */
private val FORECAST = listOf(800, 1000, 1500, 3000, 5000, 10000, 21097, 42195)

@Composable
private fun RaceCalc() {
    var km by remember { mutableStateOf("5") }
    var time by remember { mutableStateOf("25:00") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(km, { km = it }, "Забег", "км", Modifier.weight(1f))
        NumberField(time, { time = it }, "Время", "мм:сс", Modifier.weight(1f), keyboard = KeyboardType.Text)
    }
    val d = parseNum(km)?.takeIf { it >= 0.4 }
        ?: return Text("Впиши дистанцию от 0,4 км. Например, 0,8 для 800 м или 1,5.", style = T.secondary(13.sp).copy(color = F.c.bad))
    val s = parseClock(time)?.takeIf { it >= 30 && it / d in 100.0..1200.0 }
        ?: return Text("Не похоже на время забега. Пиши минуты и секунды через двоеточие, например 5:30.", style = T.secondary(13.sp).copy(color = F.c.bad))
    val v = Vdot.fromRace(d * 1000, s)
    FCard(gap = 4.dp) {
        Text("Форма бега (VDOT)", style = T.secondary(13.sp))
        NumText(Ru.one(v), style = T.number(40.sp).copy(color = F.c.endurance))
        Text(
            "Чем выше число, тем ты быстрее. Прогноз по таблицам Дэниелса. " +
                (if (d < 1.5) "Если забег короче 1,5 км, прогноз на длинные дистанции менее точный. " else "") +
                "Марафон обычно выходит на 5–10 % медленнее, если бегаешь меньше 50 км в неделю.",
            style = T.secondary(12.sp),
        )
    }
    ResultRows(FORECAST.map { m -> goalDistance(m) to Ru.clock(Vdot.predictSeconds(v, m.toDouble()).roundToInt()) })
    val p = PaceTable.forVdot(v)
    ResultRows(
        listOf(
            "Лёгкий бег" to "${Ru.pace(p.easy.fast.toDouble())}–${Ru.pace(p.easy.slow.toDouble())}",
            "Марафонский" to Ru.pace(p.marathon.toDouble()),
            "Темповый: тяжело, но держишь" to Ru.pace(p.threshold.toDouble()),
            "Интервалы" to Ru.pace(p.interval.toDouble()),
            "Короткие быстрые отрезки" to Ru.pace(p.repetition.toDouble()),
        ),
    )
}

@Composable
private fun HeartCalc() {
    val repo = LocalRepo.current
    val profile by repo.store.profile.collectAsState(initial = null)
    var age by remember(profile) { mutableStateOf(profile?.let { (repo.today().year - it.birthYear).toString() } ?: "30") }
    var rest by remember(profile) { mutableStateOf(profile?.restingHr?.toString() ?: "") }
    var max by remember(profile) { mutableStateOf(profile?.maxHr?.toString() ?: "") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(age, { age = it }, "Возраст", modifier = Modifier.weight(1f), keyboard = KeyboardType.Number)
        NumberField(rest, { rest = it }, "Пульс в покое", modifier = Modifier.weight(1f), keyboard = KeyboardType.Number, placeholder = "—")
        NumberField(max, { max = it }, "Макс., если знаешь", modifier = Modifier.weight(1f), keyboard = KeyboardType.Number, placeholder = "—")
    }
    val a = age.toIntOrNull()?.coerceIn(10, 100) ?: return
    val typed = max.toIntOrNull()?.takeIf { it in 120..230 }
    val hrMax = typed ?: HeartRate.maxTanaka(a)
    val zones = HeartRate.zones(hrMax, rest.toIntOrNull())
    // The formula note whenever the formula is used — also when a typed maximum was not believable.
    Text("Максимальный пульс — $hrMax уд/мин${if (typed == null) ". Это расчёт по формуле Танаки: 208 − 0,7 × возраст, ошибка до ±11" else ""}.", style = T.secondary(13.sp))
    if (typed == null && max.isNotBlank()) Text("Такой максимум не похож на настоящий: обычно он от 120 до 230. Взят расчётный.", style = T.secondary(12.sp).copy(color = F.c.warn))
    ResultRows(zones.map { z -> "${z.number}. ${z.name}" to "${z.low}–${z.high}" })
}

@Composable
private fun DotsCalc() {
    val repo = LocalRepo.current
    val profile by repo.store.profile.collectAsState(initial = null)
    var total by remember { mutableStateOf("300") }
    var bw by remember(profile) { mutableStateOf(profile?.bodyWeightKg?.let { Ru.kg(it) } ?: "80") }
    var female by remember(profile) { mutableStateOf(profile?.sex == Sex.FEMALE) }
    Text("Сравнивает силу людей разного веса. Считается по сумме приседа, жима и тяги и весу тела.", style = T.secondary(13.sp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(total, { total = it }, "Присед + жим + тяга", "кг", Modifier.weight(1f))
        NumberField(bw, { bw = it }, "Вес тела", "кг", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Мужчина", !female, { female = false }, Modifier.weight(1f))
        Chip("Женщина", female, { female = true }, Modifier.weight(1f))
    }
    val t = parseNum(total) ?: return
    val b = parseNum(bw) ?: return
    FCard(gap = 4.dp) {
        Text("Очки DOTS", style = T.secondary(13.sp))
        NumText(Ru.one(Points.dots(t, b, if (female) Sex.FEMALE else Sex.MALE)), style = T.number(40.sp).copy(color = F.c.strength))
        Text("Формула из пауэрлифтинга. Чем больше очков, тем ты сильнее для своего веса.", style = T.secondary(12.sp))
    }
}
