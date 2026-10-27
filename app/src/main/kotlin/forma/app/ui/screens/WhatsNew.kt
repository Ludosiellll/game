package forma.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import forma.app.ui.components.Hairline
import forma.app.ui.components.PrimaryButton
import forma.app.ui.theme.F
import forma.app.ui.theme.Ic
import forma.app.ui.theme.T

/** One thing new in a version, said plainly: what changed for the person, not how it works inside. */
data class NewThing(val icon: ImageVector, val run: Boolean, val title: String, val text: String)

/** A version as the person gets it: its place in [RELEASES], the day it came out and what is new in it. */
data class Release(val id: Int, val date: String, val things: List<NewThing>)

/**
 * What each update brought, oldest first. A new APK with something the person will notice adds a release here with the
 * next id: after the update the window shows it once ([WhatsNewDialog]); «Ещё → Как пользоваться» shows the last one again.
 */
val RELEASES = listOf(
    Release(
        1, "27 октября",
        listOf(
            NewThing(Ic.run, true, "Круги на стадионе считает GPS", "Встань на линию и беги: старт, каждый круг и конец отрезка засчитаются сами. Жать «Круг» можно, но не обязательно."),
            NewThing(Ic.timer, true, "Отрезок ждёт тебя", "После отдыха отсчёт сам не пойдёт. Отрезок начнётся, когда ты побежишь или нажмёшь «Круг»."),
            NewThing(Ic.lock, false, "Лишние нажатия не считаются", "Кнопка громкости теперь только меняет громкость. «Круг» за пару секунд или нажатие далеко от линии не засчитаются, а «Пропустить» нужно удерживать."),
            NewThing(Ic.trash, false, "Лишний круг можно убрать", "Если раньше засчиталось случайное нажатие, открой пробежку в истории, нажми на круг и выбери «Это был не круг — убрать»."),
            NewThing(Ic.pin, true, "GPS включается сам", "На стадионе GPS всегда включён. Если геолокация в телефоне выключена, приложение попросит её включить."),
            NewThing(Ic.chart, true, "Настоящая длина круга", "После пробежки на стадионе приложение покажет, сколько метров твой круг на самом деле, и предложит поставить это число."),
            NewThing(Ic.chat, false, "Тренер стал аккуратнее", "Он не поменяет план сильнее, чем можно, а любое его изменение можно отменить."),
        ),
    ),
    Release(
        2, "27 октября",
        listOf(
            NewThing(Ic.trophy, false, "Цель в зале", "Поставь вес, к которому идёшь, например «пожать 100». Приложение покажет, когда примерно его возьмёшь, и по твоему согласию подстроит план: тяжёлый день и второй день с этим упражнением."),
            NewThing(Ic.calendar, false, "Тренер меняет дни недели", "Попроси: «бег во вторник и четверг» или «только зал». Поменяет после твоего «Принять», вернуть можно одной кнопкой."),
            NewThing(Ic.info, false, "Тренер предупреждает", "Если просьба мешает твоей цели — например, убрать бег перед забегом, — он скажет, чем это грозит, и предложит вариант лучше. Решаешь всё равно ты."),
            NewThing(Ic.chat, false, "Тренер видит начатую тренировку", "Спроси прямо в зале: «какой сейчас подход?», «тренажёр занят — замени», «сегодня на подход меньше». Меняется только сегодняшняя тренировка, план остаётся."),
            NewThing(Ic.scale, false, "Шаг веса — как в твоём зале", "Подсказка «дальше — столько-то кг» и кнопки «−/+» идут по твоему шагу (например, 4 кг на тренажёре). Новый шаг работает сразу, даже посреди тренировки."),
            NewThing(Ic.dumbbell, false, "Перед днём цели — подводка", "За 9 дней до даты цели у этого упражнения вдвое меньше подходов при том же весе. В сам день — разминка и две попытки на 1 раз, и проверка не откатывает твой рабочий вес."),
        ),
    ),
)

/** The releases after [seen] (the last one shown), newest first; at most three, an old phone's long gap stays short. */
fun unseenReleases(seen: Int): List<Release> = RELEASES.filter { it.id > seen }.reversed().take(3)

/** After an update: what is new, one card over the app, closed with «Понятно» (or back, or a tap beside it). */
@Composable
fun WhatsNewDialog(releases: List<Release>, onClose: () -> Unit) {
    if (releases.isEmpty()) return
    val tall = LocalConfiguration.current.screenHeightDp
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp).widthIn(max = 480.dp).fillMaxWidth()
                .heightIn(max = (tall * 0.88f).dp)
                .clip(RoundedCornerShape(F.shape.radius))
                .background(F.c.surface),
        ) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("ОБНОВЛЕНИЕ", style = T.body(12.sp, FontWeight.Bold).copy(color = F.c.accent, letterSpacing = 2.sp))
                Text("Что нового", style = T.title(26.sp))
                releases.forEachIndexed { k, r ->
                    if (k > 0) Hairline()
                    if (releases.size > 1) Text(r.date, style = T.secondary(14.sp))
                    r.things.forEach { NewThingRow(it) }
                }
            }
            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)) {
                PrimaryButton("Понятно", onClose, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun NewThingRow(t: NewThing) {
    val color = if (t.run) F.c.endurance else F.c.accent
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(t.icon, null, tint = color, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(t.title, style = T.body(16.sp, FontWeight.Bold))
            Text(t.text, style = T.secondary(15.sp))
        }
    }
}
