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
            NewThing(Ic.up, false, "Вес подбирается точнее", "Сделал подходы тяжелее плана — в следующий раз вес будет от них. Если на поднятом весе снова большой запас, подсказка поднимет ещё раз. А долгие паузы (ждал тренажёр, искал пояс) не считаются усталостью."),
            NewThing(Ic.dumbbell, false, "Перед днём цели — подводка", "За 9 дней до даты цели у этого упражнения вдвое меньше подходов при том же весе. В сам день — разминка и две попытки на 1 раз, и проверка не откатывает твой рабочий вес."),
        ),
    ),
    Release(
        3, "27 октября",
        listOf(
            NewThing(Ic.up, false, "Обновления прямо в приложении", "Когда выйдет новая версия, при запуске появится окно «Вышла новая версия». Нажми «Обновить» — она скачается и поставится сама. Первый раз телефон попросит разрешить установку из «Формы»."),
        ),
    ),
    Release(
        4, "27 октября",
        listOf(
            NewThing(Ic.dumbbell, false, "Новое упражнение — с разминкой", "Перед новым основным упражнением теперь есть разминка до подсказанного веса. Новичку в первый раз — два подхода с запасом в 3 повтора, без тяжёлых и непривычных упражнений в первые недели."),
            NewThing(Ic.run, true, "«Пробежать 5 км» — без времени", "Не знаешь, за сколько хочешь пробежать, — оставь время пустым. Цель — пробежать дистанцию без остановки, в «Прогрессе» видно, сколько осталось."),
            NewThing(Ic.up, false, "Обновления на Xiaomi, Redmi и POCO", "Телефон не даёт приложению ставить обновление само, поэтому после «Обновить» откроется установщик телефона — нажми в нём «Обновить»."),
        ),
    ),
    Release(
        5, "27 октября",
        listOf(
            NewThing(Ic.dumbbell, false, "Мои упражнения", "«План → Мои упражнения»: отметь, какие упражнения нравятся и какие не хочешь делать. План подстроится — понравившиеся встанут на места своего движения, а основа останется. Можно попросить и тренера."),
            NewThing(Ic.info, false, "Как делать каждое упражнение", "У каждого упражнения есть короткая подсказка: на тренировке нажми «Как делать», она есть и в библиотеке."),
            NewThing(Ic.home, false, "Зал или дом", "В профиле выбери, где занимаешься: зал, дома с гантелями или дома без снарядов. План соберётся из того, что есть."),
        ),
    ),
    Release(
        6, "28 октября",
        listOf(
            NewThing(Ic.dumbbell, false, "Подтягивания без лишней помощи", "Кто подтягивается сам 5 раз и больше, больше не получит «помощь … кг»: подходы — со своим весом, повторы растут, потом добавится вес. Помощь (гравитрон или резинка) — только тем, кому она правда нужна, и приложение подскажет, чем её заменить."),
            NewThing(Ic.up, false, "Веса после прошлых версий пересчитаны", "Если в последней тренировке упражнения все подходы были тяжелее плана, а старая версия это не засчитала, план теперь от сделанного веса. Только вверх, и только там, где вес после той тренировки никто не менял."),
            NewThing(Ic.chat, false, "Тренер называет свежесть как на экране", "Тренер берёт свежесть «на сейчас» — то же число, что на «Сегодня», а не вчерашнее."),
        ),
    ),
    Release(
        7, "28 октября",
        listOf(
            NewThing(Ic.run, true, "Круг стадиона — как на самом деле", "Если круг по GPS заметно короче, чем в настройках, пробежка считается по нему: контрольный, темп отрезков, разбор, дистанция. На «Сегодня» приложение предложит поставить настоящую длину круга."),
            NewThing(Ic.chart, true, "Прошлые пробежки — по новой длине круга", "Поменял длину круга — прошлые пробежки можно пересчитать одной кнопкой: дистанция и темп в истории станут настоящими, последняя пробежка решится заново."),
            NewThing(Ic.timer, true, "Круг отрезка можно поправить", "Случайно нажал «Круг» посреди отрезка — в истории впиши настоящее время этого круга, и пробежка пересчитается."),
            NewThing(Ic.calendar, true, "Лёгкий бег не урезается", "В неделе с двумя тяжёлыми тренировками лёгкий бег больше не режется до 15 минут: считается только бег, без разминки и отдыха."),
            NewThing(Ic.info, true, "Исправленная оценка пересчитывает пробежку", "Поменял оценку старой лёгкой или длинной пробежки — план пересчитает её, если после неё ещё не было пробежки такого же типа."),
        ),
    ),
    Release(
        8, "28 октября",
        listOf(
            NewThing(Ic.run, true, "Контрольный засчитывается, даже если сбавил", "Форма — лучшее из двух: по всему времени или по куску от старта, который ты держал в темпе (не меньше половины забега и 3 минут). Сбавил во второй половине или пошёл шагом — забег не пропадает."),
            NewThing(Ic.calendar, true, "Не добежал — полный контрольный через 2 недели", "Остановился или пошёл шагом: форма считается по тому, что держал от старта, и только растёт, не падает. Меньше половины — не засчитан, и приложение скажет почему."),
            NewThing(Ic.timer, true, "Ускорения в разминке — по таймеру", "Перед быстрой тренировкой и контрольным: 4 ускорения по 15 секунд, между ними 30 секунд трусцы. На экране видно, какое ускорение и сколько осталось, голос и сигнал — на старте и в конце. GPS меряет темп каждого, в разборе — насколько они быстрее трусцы."),
        ),
    ),
    Release(
        9, "28 октября",
        listOf(
            NewThing(Ic.timer, true, "Случайное «Круг» не испортит контрольный", "Задел кнопку посреди круга — это не круг: забег не закончится раньше и время останется верным. Нажатие на линии после этого даёт кругу его настоящее время."),
            NewThing(Ic.run, true, "Контрольный считается честнее", "Сбавил к концу — форма по всему времени, а кусок, который держал от старта, добавляет к ней не больше 3. Дошёл шагом — так же. Остановился — оценка, как будто добежал в темпе последнего круга, и полный контрольный через 2 недели."),
            NewThing(Ic.dumbbell, false, "План в зале, который можно сделать", "Программа больше не ставит вес и повторы, которые по твоей же тренировке не поднять. Подходы тяжелее плана засчитываются, а сделанный план не откатывается назад."),
            NewThing(Ic.info, false, "Если тяжело — план подстроится", "Не добираешь повторы несколько тренировок подряд — программа снизит вес или повторы, а не будет держать прежний план до лучшего дня."),
        ),
    ),
    Release(
        10, "28 октября",
        listOf(
            NewThing(Ic.chat, false, "Тренер дожидается ответа", "Когда тренер думает дольше 10 секунд, телефон больше не обрывает вопрос и не пишет «Нет связи с интернетом»: ответ ждётся до полутора минут. Если не успел — так и скажет, и вопрос можно задать ещё раз."),
        ),
    ),
    Release(
        11, "29 октября",
        listOf(
            NewThing(Ic.timer, true, "Контрольный не закончится раньше", "Прошёл круг шагом, а на следующем случайно задел «Круг» — это не круг: круг на четверть быстрее твоего лучшего в этом забеге не засчитывается."),
            NewThing(Ic.run, true, "Форма растёт — будет контрольный", "Три тяжёлые тренировки подряд даются легче, чем задуманы, или быстрее плана — следующая тяжёлая станет контрольным (не раньше чем через 4 недели после прошлого)."),
            NewThing(Ic.dumbbell, false, "Лёгкие гантели без качелей", "На гантель, с которой уже не получилось, план вернёт тебя по сделанным повторам: не будет «тяжелее — недобор — назад» месяцами."),
            NewThing(Ic.up, false, "Помощь в подтягиваниях уходит быстрее", "Если подтягиваешься легче, чем в плане, помощь снимается сразу на сколько можно, а не по 2,5 кг за тренировку."),
        ),
    ),
    Release(
        12, "30 октября",
        listOf(
            NewThing(Ic.timer, true, "Круг — только твоё нажатие", "GPS больше сам не засчитывает круги и старт отрезка и не отбрасывает нажатие «не на линии». Жми «Круг» на старте и на каждой линии — засчитается каждое нажатие. GPS по-прежнему меряет темп, дистанцию и длину круга."),
            NewThing(Ic.chat, false, "Тренер исправит круги", "Напиши, например: «пробежал 3 круга, а нажал один раз» или «нажал лишний раз» — тренер разделит или склеит круги, после твоего «Принять». Вернуть можно одной кнопкой."),
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
