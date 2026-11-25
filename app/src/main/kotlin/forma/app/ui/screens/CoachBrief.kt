package forma.app.ui.screens

import forma.app.BuildConfig
import forma.app.data.AppJson
import forma.app.data.BackupManager
import forma.app.data.CoachActions
import forma.app.data.Feel
import forma.app.data.Kind
import forma.app.data.LiftItem
import forma.app.data.LiftPlan
import forma.app.data.RunSummary
import forma.app.data.TrainingReport
import forma.app.data.TrainingRepository
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.strength.DoneSet
import forma.core.strength.Progression
import forma.core.text.Ru
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * How the app decides, said for the coach: the coach explains the app's own reasons and changes things within them. Kept
 * to what the code does ([forma.core.strength.Progression], [forma.core.run.RunPlan], the repository's rules).
 */
internal const val COACH_RULES = """## Как решает приложение
Зал:
- У упражнения подходы × диапазон повторов и «запас» — сколько повторов ещё мог бы сделать. Вес на следующую тренировку — рабочий вес упражнения в этом диапазоне; он всегда кратен шагу веса упражнения (вниз).
- В первую неделю блока запас на 1 больше, в предпоследнюю — на 1 меньше; последняя неделя блока — разгрузка (меньше подходов, вес на 10 % легче). «Так себе» сегодня — тоже облегчённая тренировка.
- Отдых между подходами и длину тренировки человек выбирает сам, по самочувствию: время между отметками подходов приложение не читает и ничего по нему не решает. Не оценивай отдых и длину тренировки и не советуй отдыхать короче или дольше, пока человек сам не спросит.
- Разминка: перед главным упражнением — лесенка к рабочему весу (с пустого грифа у штанги; 40–88 % веса, повторы 8 → 5 → 3 → 2, одного повтора в разминке нет), перед тяжёлым вторым на свежие мышцы — один подход 60 % на 6; перед упражнением на уже разогретые мышцы и на одну мышцу — не нужна. В разгрузочную неделю и в день «Так себе» разминка такая же, к более лёгкому весу.
- Последний подход каждого упражнения с весом (2+ подходов, не в разгрузку и не при подборе) — «N+»: сделать цель, потом ещё, пока идёт, и остановиться с 1 в запасе (с 2 — ноги в день тяжёлого бега и за 1–2 дня до него). Лишние повторы показывают, насколько стал сильнее.
- Получилось: новичок в базовых добавляет вес каждую тренировку (двойной шаг при 2+ лишних в «+»); остальные сначала поднимаются по диапазону повторов, потом добавляют вес шагом. 4+ лишних в «+» — сразу к весу по расчётному максимуму (не больше +10 %, новичку +15 %). Ничего лишнего в «+» — вес держится.
- Не получилось: если в подходах остановился сам с запасом — вес и цель держатся. Иначе, пока есть куда, сначала снижается цель повторов до сделанного (вес тот же); потом почти — тот же вес; явно — вес на полпути к показанному (не больше −10 % за раз); три тренировки подряд без прогресса — −10 % и с низа диапазона. Если вес уже снижали, а повторы не выросли, — дело не в весе: вес держится, цель — сделанные повторы. Никогда ниже 85 % лучшего расчётного максимума последних недель.
- Все подходы тяжелее плана — прогресс считается от самого лёгкого из них (один тяжёлый подход вес не двигает); «+» на другом весе пересчитывается через расчётный максимум. Подход на полшага легче плана (план не попадает на шаг его зала) — это план, не недобор.
- Во время тренировки: запас на 2+ больше плана — подсказка тяжелее (до +10 % от плана); если и на этом весе запас большой — ещё раз, но не больше +20 % от плана за тренировку.
- После 2+ недель без упражнения — на 5–15 % легче. Первый раз — подбор веса: подходы с 1–2 в запасе; вес — по первому подходу на каждом весе (повтор того же веса до отказа — усталость, не сила).
- Поэтому вес «меньше, чем могу» бывает: цель — не отказ, а запас; рост идёт через повторы и «+». Если человек стабильно делает больше — можно предложить рабочий вес (set_working_weight, он подтверждает) или поправить шаг под его зал (set_weight_step).
- Во время тренировки (раздел «Идёт сейчас»): в обычном подходе — цель повторов и честный запас. По запасу приложение само поправит вес следующего подхода: запас на 2+ больше плана — тяжелее, меньше — легче. Лишние повторы — только в последнем подходе «+»: в обычных они утомляют следующие подходы, а запас и так покажет силу. Вес следующего подхода человек вписывает сам в поле на экране.
- Шаг веса меняется сразу, и в идущей тренировке: кнопки «−/+» и подсказки идут по новому шагу.
- Отдых по отметкам подходов — всё время между ними: кроме отдыха там подготовка (пояс, настройка тренажёра), ожидание занятого тренажёра, отвлечения. Длинная пауза не значит, что было тяжело: выводов о восстановлении из неё не делай и отдыхать меньше не требуй. Приложение тоже не делает: паузы намного длиннее плана не меняют ни отдых, ни вес. Отдых по таймеру — ориентир, подход начинается, когда человек готов.
- Цель в зале с датой: за 9 дней до даты у упражнения цели вдвое меньше подходов, вес тот же, без «+» (подводка). В день цели оно первое — проверка максимума: разминка и 2 попытки по 1 разу. Подводка и проверка рабочий вес не меняют.
Бег:
- Темпы — от формы бега (VDOT). Пока контрольного забега не было, форма — оценка: сначала от цели (на 8 пунктов ниже её, ещё на 1–2 ниже после промахов), дальше по тренировкам: тяжёлая пробежка совсем не по силам в заданном темпе (меньше 60 % и тяжесть 8+), отрезки заметно медленнее при тяжести 8+ или два промаха отрезков подряд — оценка −1 и все темпы спокойнее; три тяжёлые в темпе с запасом или длинные отрезки с запасом — +0,5…1, не чаще раза в 2 недели. Отрезки «темп цели» идут в темпе ступени: нынешний темп забега по этой оценке минус 2,5 %, но не быстрее цели — почти на пределе, но по силам.
- Контрольный забег при цели не обязателен и сам не назначается, пока человек далеко от цели: он встаёт на тяжёлый день, когда пройдена лестница темпа цели, оценка формы в 1 пункте от цели или человек сам попросил (set_trial next). Не хочет — set_trial skip. Темп контрольного — по нынешней форме, не по цели. После засчитанного контрольного форма — по нему, следующий через 5 недель (8 при цели длиннее 3 км).
- Контрольный: форма — по всему времени; если отрезок от старта (не меньше половины и 3 минут) человек держал заметно быстрее, а потом сбавил, — по этому отрезку, но не больше чем на 3 VDOT выше всего времени; недобежанный (остановился, пошёл шагом) — оценка по этому отрезку (дошёл до финиша шагом — тоже не больше +3 к времени до финиша), форму только поднимает или ставит, полный контрольный через 2 недели; меньше половины — не засчитан. «Цель достигнута» — только по всему времени.
- Лёгкий: пробежал целиком и тяжесть до 5 — следующий на 3 мин длиннее; пробежал дольше плана — на половину лишнего (3–8 мин); тяжесть 7+ — на 5 мин короче. Потолок под цель; неделя бега не больше +10 % к прошлой; одна пробежка не длиннее 110 % самой длинной за 30 дней.
- Длинный: тяжесть до 6 и целиком — растёт на 5–10 мин; 8+ или не добежал — на 5 мин короче.
- Тяжёлые (отрезки в темпе цели, интервалы, темповый) идут по лестнице. После засчитанного контрольного: три подряд в темпе — форма +1 (не чаще раза в 4 недели; с целью до 3 км форму меняет только следующий контрольный), три неудачи — форма −1. Пока засчитанного контрольного нет, форма — оценка и меняется по тренировкам (выше), контрольный не обязателен.
- Порядок тяжёлых при цели до 3 км идёт по числу сделанных тяжёлых (пропуск только сдвигает его). Форма больше чем на 3 пункта ниже цели: интервалы через раз, между ними по очереди темп цели и темповый (ТЦ, И, Т, И; ТЦ — темп цели, И — интервалы, Т — темповый). В 3 пунктах: интервалы и темп цели по очереди, темповый 1 из 6 (ТЦ, И, ТЦ, И, ТЦ, Т). В 1 пункте или забег через 8 недель и ближе: темп цели через раз (ТЦ, Т, ТЦ, И, ТЦ, Т). Темп цели после ног переходит на следующий свежий тяжёлый день. Круги интервалов медленнее кругов темпа цели, так задумано: нужны минуты почти на пределе дыхания.
- Тяжёлая пробежка целиком (разминка, отрезки, отдых, заминка; своя от тренера, run_custom, тоже) не длиннее 110 % самой длинной за 30 дней (без пробежек за месяц — лёгкий плюс 10 мин). Длиннее — сначала короче заминка (до 5 мин), потом разминка (до 8 мин, ускорения остаются), потом меньше отрезков (не меньше 2, у темпового потом отрезки короче), иначе в этот день лёгкий бег, а тяжёлая ждёт. Контрольный и забег не урезаются. Меньше 3 пробежек за месяц: работа тяжёлой (≈10 % недели) считается от недели плана.
- Темповый: до первого контрольного темп отрезков примерный, от цели. Отрезки по времени меряются по кругам, нажатым внутри них (первый круг отрезка не считается: он не от линии). Слишком тяжело (меньше 60 % и тяжесть 8+) — следующий те же минуты кусками вдвое короче (3 × 5 → 6 × 2,5); получилось — отрезки растут к шагу плана (6 × 2,5 → 4 × 4 → 3 × 5). Свой темповый (set_tempo) — до шага плана так же. Какой темповый сейчас — в строке «Шаги лестниц».
- Ускорения: после лёгкого бега в неделю с тяжёлым бегом, 4 (6, когда бег регулярный), секунд по 10, по прямой (на маленьком стадионе прямая около 50 м, ускорение в повороте сильнее грузит одну ногу) — быстро, но расслабленно и ровно, не в полную силу: скорость и техника без усталости.
- После «Болею»: 2 пробежки лёгкие, контрольного нет неделю, первая силовая облегчённая. После перерыва больше недели первая пробежка лёгкая.
- Оценка тяжести (1–10) после тренировки решает следующую: её можно исправить."""

/**
 * Where things are done by hand, by the keys of `open_screen`: the coach points here (a button to the screen) instead of
 * making a place up. Denis, 5 November: the coach sent him to «План → день → настройка отрезков», which does not exist.
 */
internal const val COACH_MAP = """## Что где в приложении (это человек делает сам; кнопку «Открыть» на экран даёт open_screen по ключу)
- today «Сегодня»: «Начать» тренировку дня; «Как ты сегодня?» (хорошо, так себе, болею); пропущенная тренировка недели; карточка пробежки от тренера; внизу в любой день «Силовая» (любая силовая плана; «Другая силовая», когда сегодня своя) и «Свободный бег». Не успевает в свой день — пусть сделает силовую или лёгкий бег в другой день этой недели этими кнопками, дни плана не меняются: сделанное заранее засчитывается за свой день, пропущенное приложение само предложит в свободный день недели.
- plan «План»: неделя по дням (нажать день — что, зачем и почему в этот день); ниже упражнения каждой тренировки (нажать упражнение: заменить, подходы, повторы, отдых, шаг веса, первый вес тренажёра, убрать), «Добавить упражнение», «Пауза», «Идеальный план», «Мои упражнения».
- progress «Прогресс»: одна страница сверху вниз. «Сила»: изменение за 8 недель, основные упражнения (нажать — история упражнения), «Поставить цель в зале». «Бег»: лучший контрольный или средний круг в отрезках в темпе цели, шаг до контрольного; «Подробнее о беге» — прогноз, контрольные, отрезки, темпы плана. «Вес тела» (средний и последний замер). «Нагрузка» по неделям; «Подробнее о нагрузке» — тренированность, усталость, свежесть, недели. «Мышцы за 7 дней» на фигуре. Внизу выгрузка тренировок в файл.
- history «История»: все тренировки и пробежки; открыть тренировку — «Изменить оценку», поправить круги пробежки, «Удалить из истории».
- exercises «Упражнения»: поиск упражнения, как делать, своё упражнение.
- my_exercises «Мои упражнения»: что нравится и что не хочу делать, где занимаюсь.
- ideal_plan «Идеальный план»: что приложение предлагает поменять в зальном плане, по пунктам.
- friends «Люди и друзья»: чужие тренировки и программы, сообщения, взять программу друга.
- body «Вес тела»: записать вес, график.
- cloud «Облако и копия»: вход, синхронизация, копия в файл, удалить аккаунт.
- profile «Цель, дни и данные»: анкета (для чего зал и бег, дни, длина тренировки, опыт), цель по бегу, круг стадиона, вес, рост, год рождения.
- gym_goal «Цель в зале»: упражнение, вес, дата.
- settings «Оформление и звук»: оформление, голос, сигналы, вибрация, GPS, автопауза, таймер отдыха, не гасить экран, выгрузка тренировок в файл.
- calculators «Калькуляторы»: вес на 1 раз, блины, разминка, темп, прогноз, пульс.
- help «Как пользоваться»: знакомство, словарь, показать подсказки снова.
- На экране силовой тренировки: вес и повторы подхода, запас, «Подход сделан», таймер отдыха, заменить, пропустить или добавить упражнение.
- На экране пробежки: «Круг» на линии стадиона, пауза, закончить; после — оценка тяжести; «Внести пробежку без телефона».
Других экранов и настроек нет. Чего нет ни здесь, ни среди функций — того приложение не умеет: скажи это прямо и запиши пожелание (wish)."""

/**
 * The research the rules stand on (as in the project's notes), for the coach's "why". Nothing on calf work: Denis declined
 * calf raises twice («нет возвращать не надо», 24 November), and a line ending in «2–3 подхода подъёмов на носки» had the
 * coach suggest them again.
 */
internal const val COACH_RESEARCH = """## Исследования, на которых стоят правила
- Запас повторов (RIR) как мера усилия — Helms и др., 2016/2018. «N+» подход — APRE, Mann и др., 2010.
- Рост силы за ~12 недель: ≈+40 % у нетренированных, +20 % у тренированных, +10 % у продвинутых (ACSM, 2009).
- Белок: прирост мышц выходит на плато около 1,6 г/кг в сутки (до ≈2,2) — Morton и др., 2018.
- Темпы бега по VDOT — Дэниелс. 80 % тренировок у сильных бегунов — лёгкие (Seiler, 2010).
- Одна пробежка, тяжёлая тоже, целиком не длиннее 110 % самой длинной за 30 дней: это связано с меньшим числом травм от перегрузки, но не доказано, что предел их предотвращает (наблюдение 5205 бегунов, тяжёлые пробежки считались тоже; Frandsen и др., 2025).
- Интервалы около предельной аэробной скорости поднимают её лучше всего, в исследованиях их делали 2–3 раза в неделю (Milanović, 2015; Helgerud, 2007); 1500 м держится в основном на ней (Bellinger, 2021).
- Без тренировок VO2max падает ≈7 % за 2–3 недели и ≈15 % к 8–12 неделям (Coyle и др., 1984).
- 1500 м — около 84 % аэробной работы (Spencer и Gastin, 2001): база и порог важнее быстрых кругов.
- Ровная раскладка выгоднее рывка в начале на дистанциях длиннее ~2 минут (Abbiss и Laursen, 2008).
- Бег мешает силовой тем больше, чем чаще и дольше (Wilson и др., 2012)."""

private val WEEKDAY = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")

/** The summary sent with a question: today, the training as the readable report has it, and how the app works. */
suspend fun coachContext(repo: TrainingRepository, today: LocalDate = repo.today(), reportChars: Int = 28_000): String {
    val program = repo.store.programNow()
    val settings = repo.store.settingsNow()
    // The day as the Today screen has it: a pause has no sessions, «Так себе» makes them lighter, «Болею» none.
    val feel = settings.feelOn(today)
    val paused = repo.paused(settings, today)
    val light = feel == Feel.LOW
    // What of today is done or going on: the person asks «я начал?», and the plan alone does not say.
    val active = repo.db.workouts().active()
    val doneToday = repo.db.workouts().allDone().filter { it.epochDay == today.toEpochDay() }
    // A session of today done on a day before it this week (Saturday's «Низ» on Friday) counts for today, as «Сегодня»
    // says: the coach read «ещё не начата» and told to do it again (the audit of 24 November).
    val todayItem = if (paused) null else repo.weekFlow(today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)))
        .first().firstOrNull { it.date == today }
    fun ahead(s: forma.core.plan.PlannedSession) = todayItem?.doneLaterFor(s)?.takeIf { it.epochDay < today.toEpochDay() }
    fun aheadWords(w: forma.app.data.WorkoutEntity, other: Boolean) =
        " — сделано заранее, ${onDayFull(LocalDate.ofEpochDay(w.epochDay).dayOfWeek)}" + (if (other) " («${w.title}»)" else "") +
            ": засчитано за сегодня (можно ещё раз вне плана)"
    // The hard run moved on the missed «Низ» card (research D1, 25 November): not today's on its own day, today's on the
    // new one, counted for its own; said as «Сегодня» shows it, or the coach would send him to run it on the wrong day.
    val moved = if (paused) null else repo.movedRun(today)
    val movedAway = moved?.from == today.toEpochDay()
    val planned = program?.week?.on(today.dayOfWeek.value).orEmpty().let { l -> if (movedAway) l - forma.core.plan.PlannedSession(SessionKind.RUN_QUALITY) else l }
    val movedLine = moved?.let { m ->
        val from = LocalDate.ofEpochDay(m.from)
        val to = LocalDate.ofEpochDay(m.to)
        when (today) {
            from -> "- Тяжёлый бег сегодня не делать: человек выбрал перенести его ${onDayFull(to.dayOfWeek)} (после «Низа» накануне), там он засчитается за сегодня."
            to -> runCatching { repo.runFor(SessionKind.RUN_QUALITY, today, light) }.getOrNull()?.let { w ->
                "- Бег «${w.title}» — тяжёлый бег, перенесённый сюда по выбору человека, засчитается за ${onDayFull(from.dayOfWeek).substringAfter(' ')}: ${w.note}"
            }
            else -> null
        }
    }
    val todayLines = if (paused) emptyList() else listOfNotNull(movedLine?.takeIf { !movedAway }) + planned.map { s ->
        if (s.kind == SessionKind.LIFT) {
            val early = ahead(s)
            val state = when {
                active?.kind == Kind.LIFT && active.templateId == s.templateId -> " — НАЧАТА, идёт сейчас (раздел «Идёт сейчас»)"
                doneToday.any { it.kind == Kind.LIFT && it.templateId == s.templateId } -> " — сделана сегодня"
                early != null -> aheadWords(early, early.templateId != s.templateId)
                else -> " — ещё не начата"
            }
            val plan = runCatching { repo.liftPlan(s.templateId!!, today, forceDeload = light) }.getOrNull()
            val going = active?.kind == Kind.LIFT && active.templateId == s.templateId
            "- Силовая «${plan?.title ?: s.templateId}»$state" + if (going || early != null) "" else (plan?.let { p -> ": " + p.items.joinToString("; ") { "${it.name} ${it.prescription.sets}×${it.prescription.reps}" + (it.prescription.weight?.let { w -> " ${Ru.kg(w)} кг" } ?: "") } } ?: "")
        } else {
            val w = runCatching { repo.runFor(s.kind, today, light) }.getOrNull()
            val early = ahead(s)?.takeIf { doneToday.none { d -> d.kind == Kind.RUN } }
            if (early != null) "- Бег «${w?.title}»" + aheadWords(early, early.templateId != w?.type?.name)
            else "- Бег «${w?.title}»" + (if (doneToday.any { it.kind == Kind.RUN }) " — пробежка сегодня уже есть" else "") + ": ${w?.note.orEmpty()}"
        }
    }
    val easyLeft = repo.easyAfterSick(today)
    val liftAfterSick = repo.liftAfterSick(today)
    val b = BackupManager(repo).snapshot(points = false)
    val feels = TrainingReport.feelWords(settings.feels)
    val report = withContext(Dispatchers.Default) { TrainingReport.build(b, today, BuildConfig.VERSION_NAME, feels) }
    return buildString {
        // The weekday said, not left to the model: it worked Sunday 1 November out as Monday and gave Monday's run.
        val clock = if (today == repo.today()) repo.timeNow().let { ", время %d:%02d".format(it.hour, it.minute) } else ""
        appendLine("Сегодня ${WEEKDAY[today.dayOfWeek.value - 1]}, ${Ru.date(today)} ${today.year}$clock.")
        when {
            paused -> appendLine("Сегодня пауза в плане до ${Ru.date(LocalDate.ofEpochDay(settings.pauseOn(today)?.get(1) ?: today.toEpochDay()))}: тренировок по плану нет.")
            feel == Feel.SICK -> appendLine("Сегодня человек отметил «Болею»: тренироваться не нужно, приложение предлагает отдых или паузу.")
            feel == Feel.LOW -> appendLine("Сегодня самочувствие «Так себе»: тренировки дня облегчены — в силовой вдвое меньше подходов и вес на 10 % легче, бег только лёгкий (ниже уже облегчённые).")
            feel == Feel.GOOD -> appendLine("Сегодня самочувствие «Хорошо».")
            else -> {}
        }
        if (easyLeft > 0 || liftAfterSick) {
            appendLine(
                "После болезни: " + listOfNotNull(
                    if (easyLeft > 0) "ещё $easyLeft ${Ru.plural(easyLeft, "пробежка", "пробежки", "пробежек")} только лёгкие" else null,
                    if (liftAfterSick) "первая силовая облегчённая" else null,
                ).joinToString(", ") + ".",
            )
        }
        if (!paused) {
            val day = WEEKDAY[today.dayOfWeek.value - 1]
            val head = if (feel == Feel.SICK) "По плану было (человек болеет, не делает):" else "По плану сегодня ($day):"
            appendLine(if (todayLines.isEmpty()) "По плану сегодня ($day) отдых." else head + "\n" + todayLines.joinToString("\n"))
            if (movedAway) movedLine?.let { appendLine(it) }
            // A missed session the app offers today, and the coach's own run of today (run_today).
            repo.catchUp(today)?.let { c ->
                val what = when (c.session.kind) {
                    SessionKind.LIFT -> "силовая «${program?.templates?.firstOrNull { it.id == c.session.templateId }?.title ?: c.session.templateId}»"
                    SessionKind.RUN_QUALITY -> "тяжёлый бег"
                    SessionKind.RUN_LONG -> "длинный бег"
                    SessionKind.RUN_EASY -> "лёгкий бег"
                }
                appendLine(
                    "Пропущено на этой неделе: $what (было в ${WEEKDAY[c.plannedOn - 1]}). Приложение предлагает сегодня " + when {
                        c.easy -> "сделать его лёгким бегом: тяжёлым нельзя, рядом тяжёлый день."
                        // Legs owed before tomorrow's hard run (research D1, 25 November): the card's two choices in one
                        // line, so the coach neither forbids the «Низ» nor keeps tomorrow hard.
                        c.beforeHard -> "сделать её (ног не было ${Ru.days(c.legsDaysAgo ?: forma.core.plan.CatchUp.LEGS_OWED)}), а завтрашний тяжёлый бег тогда лёгкий" +
                            (if (c.moveOk) " или переносится ${onDayFull(today.plusDays(2).dayOfWeek)} и засчитается за завтра" else "") +
                            " — человек выбирает кнопкой на «Сегодня»; тяжёлый бег назавтра после «Низа» не советуй."
                        else -> "сделать его — кнопка на «Сегодня»."
                    },
                )
            }
            settings.coachRunToday?.takeIf { it.until == today.toEpochDay() }?.let {
                appendLine("Ты поставил на сегодня лёгкий бег" + (if (it.value > 0) " ${it.value} мин" else "") + " (run_today): карточка на «Сегодня».")
            }
        }
        // Freshness now, the number «Сегодня» shows: said first, so a dip's lowest or a week's below is not taken for it.
        val story = repo.loadStoryNow(today)
        story?.takeIf { it.history }?.let { st ->
            appendLine(
                "Свежесть сейчас: ${Ru.pct(st.today.formPct)} (как на экране «Сегодня»; это число на сегодня, а не вчерашнее). " +
                    "В разделе «Нагрузка и восстановление» ниже минимумы — прошлых дней.",
            )
        }
        // The line at the top of «Прогресс», in its words: only a week above both the usual and the plan's full week
        // (research spike-label, 25 November).
        story?.let { forma.core.load.LoadExplain.alert(it) }?.let { appendLine(it) }
        val now = inProgress(repo)
        if (now.isNotEmpty()) {
            appendLine()
            append(now)
        } else {
            appendLine("Сейчас тренировка не идёт (ничего не начато).")
        }
        program?.week?.let { wk ->
            val names = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
            fun days(f: (forma.core.plan.PlannedSession) -> Boolean) = wk.days.filter { d -> d.sessions.any(f) }.map { names[it.day - 1] }
            val gym = days { it.kind == SessionKind.LIFT }
            val run = days { it.isRun }
            appendLine("Неделя: " + (if (gym.isEmpty()) "без зала" else "зал — ${gym.joinToString(", ")}") + "; " + (if (run.isEmpty()) "без бега" else "бег — ${run.joinToString(", ")}") + ".")
        }
        // The block's week as «План» says it, and the next lighter week: the report gives the day the plan was made, which
        // a new layout of the days moves while the block stays (the audit of 24 November: «когда разгрузка?»).
        program?.takeIf { it.templates.isNotEmpty() }?.let { p ->
            val lift = repo.store.liftStateNow()
            cycleWords(p, today, lift.reactiveDeloadStart, lift.deloadMovedFrom)?.let { (n, words) ->
                val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                val lighter = (1..p.blockWeeks).map { monday.plusWeeks(it.toLong()) }
                    .firstOrNull { ProgramGenerator.isDeloadWeek(p, it) || lift.reactiveDeloadStart == it.toEpochDay() }
                appendLine("Цикл зала, как на «Плане»: неделя $n из ${p.blockWeeks} — $words" +
                    (lighter?.let { " Следующая лёгкая неделя — ${Ru.dates(it, it.plusDays(6))}." } ?: ""))
            }
        }
        appendLine()
        appendLine("## Цели человека — каждое изменение сверяй с ними")
        val profile = repo.store.profileNow()
        appendLine("- Бег: " + (profile?.runGoal?.let { g -> "${g.meters} м за ${Ru.clock(g.seconds)}" + (g.date?.let { ", забег ${Ru.date(LocalDate.ofEpochDay(it))}" } ?: "") }
            ?: profile?.finish?.let { "пробежать $it м без остановки, время не задано" } ?: "цели нет") + ".")
        appendLine("- Зал: " + (forma.app.data.GymGoals.words(repo) ?: "цели нет (поставить — функция set_gym_goal)."))
        appendLine()
        // What the plan is built from: the coach asks about what is missing here and records it (set_profile).
        if (profile != null) {
            appendLine("## Анкета — по ней приложение строит план (поменять — функция set_profile, человек подтверждает)")
            forma.app.data.CoachActions.profileLines(profile).forEach { appendLine("- $it") }
            appendLine()
        }
        // «Мои упражнения» and where the gym is: the plan is made of them, the coach proposes within them (set_exercise_picks).
        if (profile?.aim?.lifts == true) {
            appendLine("## Мои упражнения")
            appendLine("- Где занимается: ${profile.gear.ru}" + (if (profile.gear != forma.core.model.Gear.GYM) (if (profile.pullupBar) ", есть турник" else ", турника нет") else "") + ".")
            val liked = settings.likes.values.flatten()
            appendLine("- Нравится: " + (liked.joinToString(", ") { forma.core.model.Catalog.find(it)?.name ?: it }.ifEmpty { "не отмечено" }) + ".")
            appendLine("- Не хочет: " + (settings.dislikes.joinToString(", ") { forma.core.model.Catalog.find(it)?.name ?: it }.ifEmpty { "не отмечено" }) + ".")
            appendLine("- Отметить, что нравится или нет — функция set_exercise_picks; правила плана приложение соблюдает само.")
            appendLine()
        }
        if (settings.coachNotes.isNotEmpty()) {
            appendLine("## Что ты знаешь о человеке (запомнено)")
            settings.coachNotes.forEach { appendLine("- $it") }
            appendLine()
        }
        appendLine(exercises(repo, today))
        appendLine(running(repo, today))
        changes(repo, today).takeIf { it.isNotEmpty() }?.let { appendLine(it) }
        // The report's legend is at its end, past what the cut keeps once there are weeks of workouts: it goes first.
        val legend = report.indexOf("## Обозначения").takeIf { it >= 0 }?.let { from ->
            val to = report.indexOf("\n## ", from + 1).takeIf { it > 0 } ?: report.length
            report.substring(from, to)
        }
        legend?.let { appendLine(it.trim()); appendLine() }
        appendLine(workoutsLast(if (legend != null) report.replace(legend, "") else report).take(reportChars))
        appendLine()
        appendLine(COACH_RULES)
        appendLine()
        appendLine(COACH_RESEARCH)
        appendLine()
        appendLine("## Как устроено приложение")
        SCREENS.forEach { (name, text) -> appendLine("- $name: $text") }
        appendLine(COACH_MAP)
        appendLine("## Словарь приложения")
        GLOSSARY.forEach { (word, text) -> appendLine("- $word: $text") }
    }
}

/**
 * The gym session going on now, as its screen has it: each exercise's sets done, the one now, the next set the app
 * gives. Empty when none is. The coach answers «какой сейчас подход» from it and changes it with the workout_* levers.
 */
internal suspend fun inProgress(repo: TrainingRepository): String = buildString {
    val w = repo.db.workouts().active()?.takeIf { it.kind == Kind.LIFT } ?: return@buildString
    val plan = repo.planOf(w) ?: return@buildString
    val sets = repo.db.sets().forWorkout(w.id).sortedBy { it.doneAt }
    val settings = repo.store.settingsNow()
    val bw = repo.store.profileNow()?.bodyWeightKg ?: 75.0
    val zone = java.time.ZoneId.systemDefault()
    val start = java.time.Instant.ofEpochMilli(w.startedAt).atZone(zone)
    val minutes = ((repo.nowMs() - w.startedAt) / 60_000).coerceAtLeast(0)
    val started = if (start.toLocalDate() == repo.today()) "%02d:%02d".format(start.hour, start.minute) else "${Ru.date(start.toLocalDate())}, %02d:%02d".format(start.hour, start.minute)
    appendLine("## Идёт сейчас: силовая «${plan.title}»")
    appendLine(
        "Начата в $started ($minutes мин назад)" + (if (plan.deload) ", облегчённая" else "") + ". Подходы человек отмечает на экране тренировки, " +
            "вес следующего подхода вписывает там же; оценку всей тренировки ставит кнопкой «Закончить». Отметка «сейчас» у упражнения — куда экран переходит сам; " +
            "человек мог открыть другое упражнение — если он называет его, говори о нём. Менять её: функции workout_*.",
    )
    val by = sets.groupBy { it.position }
    fun count(i: LiftItem) = by[i.position]?.size ?: 0
    // Now, as the screen goes by itself: the exercise of the last set while it has sets left, else the next one with
    // sets left after it in the day's order (then from the start). The person may have opened another one.
    val last = sets.lastOrNull()
    val lastItem = plan.items.firstOrNull { it.position == last?.position }
    val current = when {
        lastItem == null -> plan.items.firstOrNull { count(it) < it.prescription.sets }
        count(lastItem) < lastItem.prescription.sets -> lastItem
        else -> plan.items.indexOf(lastItem).let { i -> plan.items.drop(i + 1) + plan.items.take(i) }.firstOrNull { count(it) < it.prescription.sets }
    }
    for (i in plan.items) {
        val ex = settings.onSteps(forma.core.model.Catalog[i.exerciseId])
        val plate = ex.load == forma.core.model.LoadKind.BODYWEIGHT_PLUS
        fun kg(x: Double) = when {
            plate && x < 0 -> "помощь ${Ru.kg(-x)} кг"
            plate -> "+${Ru.kg(x)} кг"
            else -> "${Ru.kg(x)} кг" + if (ex.perHand) " (каждая гантель)" else ""
        }
        val p = i.prescription
        val planned = when (p.phase) {
            forma.core.strength.Phase.TEST -> "проверка максимума цели: попытки по 1 разу — " + p.attempts.orEmpty().joinToString(", потом ") { kg(it) }
            else -> "${p.sets} × ${p.reps}" + (p.weight?.let { " по ${kg(it)}" } ?: if (p.calibrate) " (вес подбирается)" else "") +
                ", запас ${p.rir}" + (if (p.top) ", последний подход «${p.reps}+»" else "") +
                (if (p.phase == forma.core.strength.Phase.TAPER) " (подводка к цели)" else "") + ", отдых ${Ru.clock(i.slot.restSec)}"
        }
        val done = by[i.position].orEmpty()
        val doneText = if (done.isEmpty()) "ещё не начато" else
            "сделано ${done.size} из ${p.sets}: " + done.joinToString(", ") { "${kg(it.weight)} × ${it.reps}" + (it.rir?.let { r -> " (запас $r)" } ?: "") }
        val mark = when {
            i === current -> " ← СЕЙЧАС"
            done.size >= p.sets -> " — готово"
            else -> ""
        }
        appendLine("- ${i.name} [${i.exerciseId}]: план $planned; $doneText$mark")
        if (i === current && done.isNotEmpty()) {
            // As the gym screen works the next set out: by the sets as rated (not through the person's correction).
            val ns = Progression.nextSet(ex, p, done.map { DoneSet(it.weight, it.reps, it.rir) }, bw)
            ns?.let { appendLine("  Подход ${done.size + 1}: приложение даёт ${kg(it.weight)} × ${it.reps}" + (if (it.plus) "+" else "") + ".") }
        }
    }
    val left = plan.items.count { count(it) < it.prescription.sets }
    appendLine(if (left == 0) "Все подходы сделаны — осталось нажать «Закончить» и оценить тренировку." else "Упражнений с подходами впереди: $left.")
}

/** The gym plan's exercises with what the functions need: ids, numbers now, the next weight, the step, the estimate. */
private suspend fun exercises(repo: TrainingRepository, today: LocalDate): String = buildString {
    val program = repo.store.programNow() ?: return@buildString
    if (program.templates.isEmpty()) return@buildString
    // The sessions as «Прогресс» reads them, for «на 1 раз» below.
    val rows = repo.setRows().first()
    val bw = repo.bodyWeightLookup().first()
    appendLine("## Упражнения в плане (id — для функций)")
    for (t in program.templates) {
        val plan = runCatching { repo.liftPlan(t.id, today) }.getOrNull() ?: continue
        appendLine("### «${t.title}» (id ${t.id})" + if (plan.deload) " — сейчас облегчённая" else "")
        for (i in plan.items) {
            val p = i.prescription
            val stat = repo.db.state().stat(i.exerciseId)
            // The working weight the levers reason from (set_working_weight compares with it): in a lighter week, after
            // being sick or on a «Так себе» day the session's weight is less, and that is not the weight to raise or cut.
            val work = repo.db.state().slot(i.slot.key)?.weight
            val ex = forma.core.model.Catalog.find(i.exerciseId)
            // A plate on pull-ups is on top of the body; the estimate is of the whole load: said so, or 0 vs 85 kg reads as room.
            val plate = ex?.load == forma.core.model.LoadKind.BODYWEIGHT_PLUS
            val each = if (ex?.perHand == true) " (каждая гантель)" else ""
            // One number for «на 1 раз»: the one «Прогресс» and the exercise's page show. Asked «какой у меня максимум», the
            // coach gave the engine's smoothed estimate, a few kilos behind and shown nowhere; that one is named beside it
            // where it differs, as the weights and set_working_weight are worked out from it.
            val shown = ex?.let { forma.core.analytics.Stats.oneRmNow(forma.core.analytics.Stats.e1rmSeries(rows, it, bw), today.toEpochDay(), stat?.e1rm) } ?: stat?.e1rm
            val parts = listOfNotNull(
                "${p.sets} × ${i.slot.repMin}–${i.slot.repMax}" + (if (p.reps != i.slot.repMin) " (цель сейчас ${p.reps})" else ""),
                "запас ${p.rir}",
                when {
                    p.calibrate -> "вес ещё подбирается"
                    p.weight != null && plate -> if (p.weight!! < 0) "помощь ${Ru.kg(-p.weight!!)} кг" else "следующий вес +${Ru.kg(p.weight!!)} кг к весу тела"
                    p.weight != null -> "следующий вес ${Ru.kg(p.weight!!)} кг$each"
                    else -> null
                },
                work?.takeIf { w -> p.weight != null && kotlin.math.abs(w - p.weight!!) > 1e-6 }?.let { w ->
                    "рабочий вес ${if (plate && w > 0) "+" else ""}${Ru.kg(w)} кг$each (" + when {
                        p.phase == forma.core.strength.Phase.TEST -> "сегодня вместо подходов проверка максимума"
                        p.weight!! < w -> "на этой тренировке легче"
                        else -> "на этой тренировке тяжелее"
                    } + ")"
                },
                if (p.weight != null || p.calibrate) "шаг ${Ru.kg(i.step)} кг$each" + (if (i.stepFrom > 0) " от ${Ru.kg(i.stepFrom)} кг (вес только ${Ru.kg(i.stepFrom)}, ${Ru.kg(i.stepFrom + i.step)}, ${Ru.kg(i.stepFrom + 2 * i.step)} …)" else "") else null,
                "отдых ${Ru.clock(i.slot.restSec)}",
                shown?.let { y ->
                    "расчётный максимум ${Ru.kg((y * 2).roundToInt() / 2.0)} кг" + (if (plate) " (вместе с весом тела)" else each) +
                        (stat?.e1rm?.takeIf { x -> kotlin.math.abs(x - y) >= 0.5 }?.let { x -> ", веса приложение считает от сглаженного ${Ru.kg(x)} кг" } ?: "")
                },
                if (i.addedForGood) "добавлено человеком" else null,
            )
            appendLine("- ${i.name} [${i.exerciseId}]: ${parts.joinToString(", ")}")
        }
    }
}

/** Running now: the lengths, their caps, the fitness, the goal and the runs of the week ahead. */
private suspend fun running(repo: TrainingRepository, today: LocalDate): String = buildString {
    val program = repo.store.programNow() ?: return@buildString
    if (program.week.days.none { d -> d.sessions.any { it.isRun } }) return@buildString
    val profile = repo.store.profileNow()
    val saved = repo.runStateToGo(profile?.runGoal)
    // As «Сегодня» and the plan go by it: after more than a week without running the runs are shorter, the fitness and the
    // paces lower. The brief gave the numbers from before the break, its own list of runs the shorter ones.
    val off = forma.core.run.RunPlan.breakDays(saved, today.toEpochDay())
    val st = forma.core.run.RunPlan.current(saved, profile?.runGoal, today.toEpochDay())
    val caps = ProgramGenerator.runCaps(program, profile?.runGoal?.middleDistance == true)
    appendLine("## Бег сейчас")
    off?.let { appendLine("- После перерыва ${Ru.days(it)} без бега: длины, форма и темпы ниже уже с учётом перерыва, как на «Сегодня»; первая пробежка лёгкая.") }
    appendLine(
        "- Лёгкий бег ${st.easyMinutes} мин (потолок ${caps.easyMax}), длинный ${st.longMinutes} мин (потолок ${caps.longMax})" +
            (st.longestRecent(today.toEpochDay() + 1)?.let { ", самая длинная за 30 дней $it мин" } ?: "") + ".",
    )
    // The next easy run as «Сегодня» will start it: the week's +10 % and a day with the gym cut it, and the coach promised
    // 40 minutes of a run that started at 15 (the audit of 24 November).
    forma.app.data.CoachActions.nextEasy(repo, today)?.let { e ->
        appendLine(
            "- Ближайший лёгкий бег ${Ru.date(e.day)}: ${e.minutes} мин" + (e.why?.let { " ($it)" } ?: "") +
                (e.weekCap?.let { "; недельный предел (+10 % к прошлым неделям) в этот день — до $it мин" } ?: "") + ".",
        )
    }
    forma.core.run.RunPlan.estimated(st, profile?.runGoal).let { known ->
        known.fitness?.let { v ->
            appendLine(if (known.estimated) "- Форма бега (VDOT) ≈ ${Ru.one(v)} — оценка по цели и по тому, как идут пробежки: контрольного забега не было, и он не обязателен. От неё все темпы."
            else "- Форма бега (VDOT) ${Ru.one(v)} (по контрольному забегу).")
        }
    }
    profile?.runGoal?.takeIf { it.middleDistance }?.let { g ->
        val step = forma.core.run.RunPlan.stepPace(forma.core.run.RunPlan.estimated(st, g), g)
        val lap = profile.lapMeters
        appendLine(
            "- Отрезки к цели (Темп цели): " + (lap?.let { "круг за ${Ru.one(forma.core.run.Track.lapSec(step, it))} с, " } ?: "") + "${Ru.pace(step)} на км" +
                (if (step <= g.pace + 0.5) " — темп цели" else "") +
                (forma.core.run.RunPlan.heldRepPace(st)?.let { " (по тому, что человек держал в отрезках такой длины или длиннее с запасом; не медленнее этого, не быстрее цели)" } ?: " (по форме)") + ".",
        )
    }
    // The day the plan and «Сегодня» put the trial on ([TrainingRepository.trialDay]), not the flag: after a try that did
    // not count the flag stays while no trial comes for two weeks, and a trial due by time comes without it.
    val trialOn = repo.trialDay(today)
    val putOff = st.noTrialUntil?.takeIf { today.toEpochDay() < it }
    appendLine("- Контрольный забег: " + when {
        trialOn != null -> "${WEEKDAY[trialOn.dayOfWeek.value - 1]}, ${Ru.date(trialOn)} (так в плане; set_trial skip — отложить)."
        putOff != null -> "отложен человеком до ${Ru.date(LocalDate.ofEpochDay(putOff))}."
        st.testSoon -> "поставлен, но пока его не будет: ${repo.trialWait(today)} (set_trial skip — отложить)."
        else -> "не назначен (set_trial next — поставить, если человек хочет)."
    })
    profile?.runGoal?.let { g -> appendLine("- Цель: ${g.meters} м за ${Ru.clock(g.seconds)}" + (g.date?.let { ", забег ${Ru.date(LocalDate.ofEpochDay(it))}" } ?: "") + ".") }
    profile?.finish?.let { appendLine("- Цель: пробежать $it м без остановки (время не задано).") }
    appendLine("- " + (profile?.lapMeters?.let { "Бегает на стадионе, круг $it м." } ?: "Бегает не на стадионе (по GPS)."))
    appendLine("- Ближайшие пробежки:")
    // The hard run moved on the missed «Низ» card: on its new day, not on its own.
    val moved = repo.movedRun(today)
    for (d in 0L..6L) {
        val date = today.plusDays(d)
        if (repo.paused(repo.store.settingsNow(), date)) continue
        val e = date.toEpochDay()
        val runs = listOfNotNull(forma.core.plan.PlannedSession(SessionKind.RUN_QUALITY).takeIf { moved?.to == e }) +
            program.week.on(date.dayOfWeek.value).filter { it.isRun && !(moved?.from == e && it.kind == SessionKind.RUN_QUALITY) }
        runs.forEach { s ->
            runCatching { repo.runFor(s.kind, date) }.getOrNull()?.let { appendLine("  - ${Ru.date(date)}: ${it.title}") }
        }
    }
}

/**
 * The report with its list of workouts (newest first) moved to the end: the cut of a long history then drops the oldest
 * workouts, not what follows the list (the body weight over time).
 */
internal fun workoutsLast(report: String): String {
    val from = report.indexOf("## Тренировки (сначала новые)").takeIf { it >= 0 } ?: return report
    val to = report.indexOf("\n## ", from + 1).takeIf { it > 0 } ?: return report
    return report.substring(0, from) + report.substring(to + 1).trimEnd() + "\n\n" + report.substring(from, to + 1)
}

/** The coach's own changes in force and the last ones made, so it neither repeats nor forgets them. */
private suspend fun changes(repo: TrainingRepository, today: LocalDate): String = buildString {
    val s = repo.store.settingsNow()
    val terms = listOfNotNull(
        s.coachStrides?.takeIf { it.on(today) }?.let { "ускорений после лёгкого — ${it.value}, до ${Ru.date(LocalDate.ofEpochDay(it.until))}" },
        s.coachLong?.takeIf { it.on(today) }?.let { "последний лёгкий бег недели — длинный, до ${Ru.date(LocalDate.ofEpochDay(it.until))}" },
        s.coachRun?.takeIf { it.day == today.toEpochDay() }?.let { "на сегодня своя пробежка (run_custom): «${it.workout.title.substringAfter(" · ")}»" },
        repo.movedRun(today)?.let { m ->
            "тяжёлый бег ${Ru.date(LocalDate.ofEpochDay(m.from))} перенесён на ${Ru.date(LocalDate.ofEpochDay(m.to))} (выбор человека на карточке пропущенного «Низа»), засчитается за свой день"
        },
    ) + s.easyRunDays.filterValues { it >= today.toEpochDay() }.map { (d, until) ->
        "${listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")[d - 1]}: вместо тяжёлого бега лёгкий, до ${Ru.date(LocalDate.ofEpochDay(until))} (ease_run_day)"
    } + s.order.map { (t, ids) ->
        "свой порядок упражнений в «${repo.store.programNow()?.templates?.firstOrNull { it.id == t }?.title ?: t}»: ${ids.joinToString(", ")} (move_exercise … reset — вернуть)"
    } + (if (s.coachWishes.isEmpty()) emptyList() else listOf("пожелания разработчику уже записаны: " + s.coachWishes.takeLast(8).joinToString("; ") { it.substringAfter(": ") }))
    val log = repo.store.coachLogNow().takeLast(12)
    if (terms.isEmpty() && log.isEmpty()) return@buildString
    appendLine("## Твои изменения")
    terms.forEach { appendLine("- Действует: $it.") }
    val zone = java.time.ZoneId.systemDefault()
    log.forEach { e ->
        val day = java.time.Instant.ofEpochMilli(e.at).atZone(zone).toLocalDate()
        appendLine("- ${Ru.date(day)}: ${e.text}" + if (e.undone) " (человек вернул как было)" else "")
    }
}

/** One finished workout for the coach's review: what was planned and done, the app's own debrief. */
suspend fun coachWorkoutFacts(repo: TrainingRepository, workoutId: Long): String? {
    val w = repo.db.workouts().get(workoutId) ?: return null
    val sets = repo.db.sets().forWorkout(workoutId)
    val run = if (w.kind == Kind.RUN) repo.db.runs().get(workoutId) else null
    val day = LocalDate.ofEpochDay(w.epochDay)
    return buildString {
        appendLine("## Разбираемая тренировка: ${Ru.date(day)} ${day.year} — ${w.title}")
        append(TrainingReport.workout(w, sets, run))
        if (run != null) {
            val plan = runCatching { AppJson.decodeFromString(forma.core.run.RunWorkout.serializer(), run.planJson) }.getOrNull()
            val summary = w.summaryJson?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() }
            if (plan != null) {
                val trial = summary?.outcomes?.filterIsInstance<forma.core.run.RunOutcome.TestTime>()?.firstOrNull()?.let { it.meters to it.seconds }
                val reached = if (trial != null) summary?.outcomes?.any { it is forma.core.run.RunOutcome.GoalReached } == true else null
                val held = summary?.outcomes?.filterIsInstance<forma.core.run.RunOutcome.TestFromPart>()?.firstOrNull()?.let { it.meters to it.seconds }
                val profile = repo.store.profileNow()
                // The rating read on the runner's own scale, as «История» shows it and the plan read it.
                val own = summary?.outcomes?.filterIsInstance<forma.core.run.RunOutcome.OwnScale>()?.firstOrNull()?.offset ?: 0
                val items = forma.core.run.RunReview.of(
                    forma.core.run.RunReview.Run(
                        plan, summary?.laps.orEmpty(), w.rpe, run.movingSec, run.avgPace, run.workPace, trial, run.completed, reached, held, summary?.strides, summary?.jog,
                        ownScale = own,
                    ),
                    profile?.runGoal,
                )
                appendLine("Разбор приложения:")
                items.forEach { appendLine("- ${it.title}: ${it.text}") }
                appendLine("Как бежать было по плану: ${plan.note}")
            }
        }
    }
}

/** The question the review card asks about a workout. */
internal fun reviewQuestion(title: String, day: LocalDate) =
    "Разбери мою тренировку ${Ru.date(day)} «$title»: что получилось, что не так и что сделать в следующий раз. Коротко, по моим цифрам."

/** The functions the coach is offered with a question. */
internal val coachTools get() = CoachActions.declarations
