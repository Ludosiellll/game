package forma.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import forma.app.ui.LocalNav
import forma.app.ui.LocalRepo
import forma.app.ui.Screen
import forma.app.ui.components.FCard
import forma.app.ui.components.GhostButton
import forma.app.ui.components.Hairline
import forma.app.ui.components.PrimaryButton
import forma.app.ui.components.SectionLabel
import forma.app.ui.components.TopBar
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T
import kotlinx.coroutines.launch

/** One page of the first look round the app: an icon, a title and a few short lines. */
private data class TourPage(val icon: ImageVector, val run: Boolean, val title: String, val lines: List<String>)

private val TOUR = listOf(
    TourPage(
        Ic.home, false, "«Сегодня» — что делать сегодня",
        listOf(
            "Тут силовая, пробежка или отдых на сегодня. Нажми «Начать» — приложение поведёт по тренировке.",
            "Сверху — твоя неделя: галочка — сделано, красный кружок — пропущено.",
            "Перед тренировкой отметь самочувствие: «Так себе» — нагрузка будет меньше, «Болею» — день отдыха.",
        ),
    ),
    TourPage(
        Ic.dumbbell, false, "В зале",
        listOf(
            "Вес и повторы приложение подставит само. Первый раз с упражнением вес подбирается: начни с подсказанного.",
            "После подхода отметь, сколько повторов ещё оставалось — это «запас». По нему приложение решает, когда прибавить вес.",
            "Таймер отдыха запускается сам. Упражнение можно заменить, пока по нему нет подходов.",
        ),
    ),
    TourPage(
        Ic.run, true, "Бег",
        listOf(
            "Темпы считаются по твоей форме. Лёгкий бег — так, чтобы можно было говорить.",
            "На стадионе жми «Круг» на старте и на каждой линии: круг — это нажатие. Приложение пискнет, когда круг должен закончиться, и подскажет голосом.",
            "После пробежки оцени, насколько было тяжело: от оценки зависит следующая.",
        ),
    ),
    TourPage(
        Ic.calendar, false, "План",
        listOf(
            "Вся неделя по дням. Нажми на день — увидишь, что там, зачем и почему в этот день.",
            "Не получается в свой день — сделай в другой, раньше или позже: засчитается.",
            "Там же — замены упражнений, подходы и «Идеальный план» с советами.",
        ),
    ),
    TourPage(
        Ic.info, false, "Если что-то пошло не так",
        listOf(
            "Болеешь — «Болею» и пауза: после неё приложение вернёт тебя мягко. Отметили по ошибке — «Снять отметку «Болею»».",
            "Ошибся с оценкой тренировки или с кругом — поправь в «Ещё → История».",
            "Уезжаешь — «План → Пауза»: пропуски не будут висеть.",
        ),
    ),
    TourPage(
        Ic.chat, false, "Тренер",
        listOf(
            "Вкладка «Тренер» — чат с ИИ, который видит твои тренировки. Спроси, почему тренировка такая или как идёт прогресс.",
            "Попроси поменять: «сегодня плохо — давай полегче», «гантели в зале через 2,5 кг». Мелкое он поменяет сам, и это можно вернуть.",
            "Упражнения, подходы, цель и стадион меняются только после твоего «Принять». После тренировки тренер её разберёт.",
        ),
    ),
    TourPage(
        Ic.chart, false, "Прогресс и всё остальное",
        listOf(
            "В «Прогрессе» видно, как растут веса и бег.",
            "В «Ещё» — история, друзья, калькуляторы, облако и «Как пользоваться». Там это знакомство и словарь слов вроде «запас» и «VDOT».",
        ),
    ),
)

/** The first look round the app, after the questionnaire or again from «Ещё → Как пользоваться». */
@Composable
fun TourScreen(first: Boolean) {
    val nav = LocalNav.current
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableIntStateOf(0) }
    fun done() {
        scope.launch { repo.updateSettings { it.copy(tourSeen = true) } }
        if (first) nav.replaceAll(Screen.Today) else nav.back()
    }
    val p = TOUR[page]
    val color = if (p.run) F.c.endurance else F.c.accent
    ScrollScreen(gap = 18.dp, bottomInset = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            TOUR.indices.forEach { i ->
                Box(Modifier.weight(1f).height(4.dp).background(if (i <= page) F.c.accent else F.c.line))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Как всё устроено", style = T.secondary(14.sp), modifier = Modifier.weight(1f))
            Text(
                "Пропустить", style = T.body(14.sp, FontWeight.Bold).copy(color = F.c.text2),
                modifier = Modifier.clickable(onClickLabel = "Пропустить знакомство") { done() }.padding(8.dp),
            )
        }
        Box(Modifier.size(72.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(p.icon, null, tint = color, modifier = Modifier.size(34.dp))
        }
        Text(p.title, style = T.title(26.sp))
        p.lines.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(color))
                Text(line, style = T.body(16.sp), modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (page > 0) GhostButton("Назад", { page-- }, Modifier.weight(1f), height = 54.dp)
            PrimaryButton(if (page < TOUR.lastIndex) "Дальше" else "Начать", { if (page < TOUR.lastIndex) page++ else done() }, Modifier.weight(2f))
        }
    }
}

/** Words of the app that are not everyday words, said plainly. */
val GLOSSARY = listOf(
    "Запас" to "Сколько повторов ещё оставалось в подходе. 0 — до отказа, 2 — оставалось ещё два. По запасу приложение решает, прибавлять ли вес.",
    "«8+» в последнем подходе" to "Сделай 8 и продолжай, пока получается чисто. Лишние повторы — повод прибавить вес.",
    "Подбор веса" to "Первая тренировка с упражнением: начни с подсказанного веса, после подхода приложение подскажет рабочий.",
    "Лёгкая неделя" to "Раз в несколько недель: в зале вдвое меньше подходов и вес на 10 % легче, пробежки короче. Потом прежние веса.",
    "Цикл" to "Несколько недель плана, последняя из них лёгкая. «Неделя 3 из 5» — где ты в цикле.",
    "Свежесть" to "Сколько сил после последних тренировок. Плюс — силы есть, минус — накопилась усталость.",
    "Сила (%)" to "Насколько вырос вес на 1 раз за последние 8 недель, в среднем по упражнениям.",
    "Максимум (1ПМ)" to "Вес, который можно поднять один раз. Приложение считает его по обычным подходам, поднимать тяжёлое на один раз не нужно.",
    "Форма бега (VDOT)" to "Число, которое описывает, как быстро ты бегаешь сейчас. Чем выше — тем быстрее темпы. По нему считаются все темпы и прогнозы.",
    "Контрольный забег" to "Забег на дистанцию в полную силу. По его времени приложение уточнит твою форму и пересчитает темпы.",
    "Темп цели" to "Темп, которым нужно бежать, чтобы выбежать свою цель. Отрезки в этом темпе приучают к нему.",
    "Интервалы" to "Быстрые отрезки с отдыхом между ними: развивают скорость и выносливость.",
    "Темповый бег" to "Ровный бег «тяжело, но держишь», 15–25 минут. Учит дольше держать быстрый темп.",
    "Лёгкий бег" to "Темп, в котором можно говорить предложениями. Медленнее — нормально.",
    "Ускорения" to "4–6 коротких разгонов по 15–20 с после лёгкого бега. Они для лёгкости и техники, до усталости не доводи.",
    "Нагрузка" to "Насколько тяжёлой была тренировка × сколько она длилась. Из неё считается «Свежесть».",
)

/** «Как пользоваться»: the look round again, what each screen is for, the words, the hints on the screens again. */
@Composable
fun HelpScreen() {
    val nav = LocalNav.current
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var news by rememberSaveable { mutableStateOf(false) }
    if (news) WhatsNewDialog(listOf(RELEASES.last())) { news = false }
    ScrollScreen {
        TopBar("Как пользоваться", onBack = { nav.back() })
        PrimaryButton("Знакомство с приложением", { nav.push(Screen.Tour(first = false)) }, Modifier.fillMaxWidth())
        GhostButton("Что нового в последнем обновлении", { news = true }, Modifier.fillMaxWidth())
        GhostButton("Спросить тренера (ИИ)", { nav.tab(Screen.Coach()) }, Modifier.fillMaxWidth())
        SectionLabel("Экраны")
        FCard(gap = 10.dp) {
            SCREENS.forEachIndexed { i, (name, text) ->
                if (i > 0) Hairline()
                Text(name, style = T.body(16.sp, FontWeight.Bold))
                Text(text, style = T.secondary(14.sp))
            }
        }
        SectionLabel("Словарь")
        FCard(gap = 10.dp) {
            GLOSSARY.forEachIndexed { i, (word, text) ->
                if (i > 0) Hairline()
                Text(word, style = T.body(16.sp, FontWeight.Bold))
                Text(text, style = T.secondary(14.sp))
            }
        }
        GhostButton(
            if (shown) "Подсказки снова на экранах" else "Показать подсказки на экранах снова",
            { scope.launch { repo.updateSettings { it.copy(hintsSeen = emptySet()) }; shown = true } }, Modifier.fillMaxWidth(),
        )
        // The notices the MIT licence asks to keep with the app: the muscle map's shapes (BodyShapes.kt).
        var notices by rememberSaveable { mutableStateOf(false) }
        Text(
            if (notices) "Лицензии ▴" else "Лицензии ▾", style = T.secondary(13.sp),
            modifier = Modifier.clickable(onClickLabel = if (notices) "Свернуть лицензии" else "Показать лицензии") { notices = !notices }.padding(vertical = 8.dp),
        )
        if (notices) Text(LICENSES, style = T.secondary(12.sp))
    }
}

/** Third-party notices shipped with the app. */
const val LICENSES = """Карта мышц: react-native-body-highlighter, © 2022 ELABBASSI Hicham; контур тела: react-muscle-highlighter, © 2024 My Muscle Contributors. Обе — лицензия MIT:

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""

/** What each screen is for, the same words as its hint. */
val SCREENS = listOf(
    "Сегодня" to "Что делать сегодня. «Начать» — и приложение ведёт по тренировке. Сверху — неделя. Самочувствие меняет нагрузку дня.",
    "План" to "Неделя по дням: нажми на день — что, зачем и почему в этот день. Ниже — замены упражнений, подходы, пауза и «Идеальный план».",
    "Тренировка в зале" to "Вес и повторы подставлены. После подхода отметь «запас» и нажми «Подход сделан». Упражнения переключаются сверху.",
    "Пробежка" to "На стадионе — «Круг» на линии, на улице — темп по GPS. После — оценка, насколько было тяжело.",
    "Прогресс" to "Как растут веса, бег, нагрузка и вес тела. Нажми на упражнение — его история.",
    "История" to "Все тренировки и пробежки. Можно исправить оценку, поправить круги или удалить ошибочную.",
    "Друзья" to "Тренировки друзей и их программы: можно взять целиком, только зал или только бег.",
    "Тренер" to "Вкладка внизу: чат с ИИ. Объясняет, разбирает тренировки, меняет план по просьбе: мелкое сразу (можно вернуть), упражнения, цель, стадион, лёгкую неделю и свою пробежку — после «Принять». Чего не может — скажет и покажет, где это сделать.",
)

/**
 * A screen's hint at its first visit: what it is for and what to press, until «Понятно». «Как пользоваться» shows them
 * all again.
 */
@Composable
fun ScreenHint(key: String, text: String) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val settings by repo.store.settings.collectAsState(initial = null)
    val s = settings ?: return
    if (key in s.hintsSeen) return
    FCard(gap = 8.dp, outline = F.c.accent.copy(alpha = 0.6f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Ic.info, null, tint = F.c.accent, modifier = Modifier.padding(top = 2.dp).size(18.dp))
            Text(text, style = T.body(15.sp), modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Подробнее", { nav.push(Screen.Help) }, Modifier.weight(1f))
            PrimaryButton("Понятно", { scope.launch { repo.updateSettings { it.copy(hintsSeen = it.hintsSeen + key) } } }, Modifier.weight(1f), height = 44.dp)
        }
    }
}

/** The hints of the main screens, by key. */
object Hints {
    const val TODAY = "today"
    const val PLAN = "plan"
    const val PROGRESS = "progress"
    const val WORKOUT = "workout"
    const val RUN = "run"
    const val PEOPLE_LISTED = "people_listed"

    const val TODAY_TEXT = "Здесь — что делать сегодня. Нажми «Начать», и приложение поведёт по тренировке. Если сил нет, отметь «Так себе»: нагрузка станет меньше."
    const val PLAN_TEXT = "Твоя неделя. Нажми на день — увидишь, что там и зачем. Не успеваешь в свой день — сделай в другой, засчитается."
    const val PROGRESS_TEXT = "Как растут веса и бег, вес тела, нагрузка и мышцы за неделю — на одной странице. Нажми на упражнение — его история."
    const val WORKOUT_TEXT = "Вес и повторы уже подставлены. После подхода отметь, сколько повторов ещё оставалось («запас»), и нажми «Подход сделан». Отдых засечётся сам."
    const val PEOPLE_LISTED_TEXT = "Теперь в «Людях» видно всех, и тебя тоже: тренировки, прогресс, программу. Маршруты, возраст, рост, пульс и заметки не видит никто. Не хочешь — выключи «Показывать меня всем»."
    const val RUN_TEXT = "На стадионе жми «Круг» на старте и на каждой линии: круг засчитывается только по нажатию. Отрезок начинается с нажатия «Круг», на писке будь на линии."
}
