package forma.app.data

import forma.app.sync.CoachCall
import forma.core.model.Aim
import forma.core.model.Catalog
import forma.core.model.Emphasis
import forma.core.model.Exercise
import forma.core.model.ExerciseSearch
import forma.core.model.Experience
import forma.core.model.Gear
import forma.core.model.LiftFocus
import forma.core.model.LiftGoal
import forma.core.model.LoadKind
import forma.core.model.Profile
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.RunLevel
import forma.core.model.Sex
import forma.core.plan.GoalRisk
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
import forma.core.run.RunState
import forma.core.strength.GymGoal
import forma.core.strength.OneRm
import forma.core.strength.Progression
import forma.core.strength.SlotSpec
import forma.core.text.Ru
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the coach (the AI) may change, and only that: the same edits the app's own screens make, through the repository,
 * within limits checked here — never what the model says unchecked. Two kinds:
 * - [Mode.AUTO]: the day, ratings, running lengths, weight steps and working weights (a little), what to remember — made
 *   at once, each with a way back («Вернуть»);
 * - [Mode.CONFIRM]: the gym plan (sets, reps, rest, exercises), the goal, the stadium and anything bigger — made when the
 *   person taps «Принять».
 * Each change made leaves its way back ([CoachLogEntry.undo]): an internal call of [undo], never offered to the model. A
 * way back puts back exactly what was there, and is refused once the same thing has moved on since (the person's own
 * progress or edits are never undone with it).
 */
object CoachActions {
    enum class Mode { AUTO, CONFIRM }

    /** A call of the model checked: [Ready] or [Refused]. */
    sealed interface Checked { val call: CoachCall }

    /** A change checked and ready: what it does in words, whether it waits for «Принять», and how to make it. */
    class Ready internal constructor(override val call: CoachCall, val mode: Mode, val text: String, internal val make: suspend () -> CoachCall?) : Checked {
        /** Why the coach makes it, as it said (cut to a sane length). */
        val why: String? get() = (call.args["why"] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content?.trim()?.take(400)?.takeIf { it.isNotEmpty() }
    }

    /** A change that cannot be made, and why — said to the person and to the model. */
    class Refused(override val call: CoachCall, val why: String) : Checked

    const val FEEL = "set_today_feel"
    const val SICK = "mark_sick"
    const val NOT_SICK = "not_sick"
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val RATE = "rate_workout"
    const val EASY = "set_easy_run_minutes"
    const val RUN_TODAY = "run_today"
    const val TEMPO = "set_tempo"
    const val TRIAL = "set_trial"
    const val RUN_PACE = "set_run_pace"
    const val RUN_STEP = "set_run_step"
    const val WISH = "wish"
    const val APP = "set_app_setting"
    const val BODY_WEIGHT = "log_body_weight"
    const val OPEN = "open_screen"
    const val LIGHTER = "set_lighter_week"
    const val ORDER = "move_exercise"
    const val EASE_DAY = "ease_run_day"
    const val RUN_CUSTOM = "run_custom"

    /** The screens [OPEN] can show, by their key: the name said to the person. */
    val SCREENS = linkedMapOf(
        "today" to "Сегодня", "plan" to "План", "progress" to "Прогресс", "history" to "История", "exercises" to "Упражнения",
        "my_exercises" to "Мои упражнения", "ideal_plan" to "Идеальный план", "friends" to "Люди и друзья", "body" to "Вес тела",
        "cloud" to "Облако и копия", "profile" to "Цель, дни и данные", "gym_goal" to "Цель в зале",
        "settings" to "Оформление и звук", "calculators" to "Калькуляторы", "help" to "Как пользоваться",
    )
    private val APP_FLAGS = linkedMapOf(
        "voice" to "Голосовые подсказки", "beeps" to "Звуковые сигналы", "vibration" to "Вибрация", "gps" to "GPS по умолчанию",
        "auto_rest" to "Таймер отдыха", "keep_screen_on" to "Не гасить экран", "auto_pause" to "Автопауза на остановках",
    )
    private const val MAX_WISHES = 40
    const val STRIDES = "set_strides"
    const val LONG = "long_easy_run"
    const val STEP = "set_weight_step"
    const val WEIGHT = "set_working_weight"
    const val REMEMBER = "remember"
    const val FORGET = "forget"
    const val SETS = "set_sets"
    const val REPS = "set_reps"
    const val REST = "set_rest"
    const val SWAP = "swap_exercise"
    const val REMOVE = "remove_exercise"
    const val ADD = "add_exercise"
    const val GOAL = "set_run_goal"
    const val LAP = "set_stadium_lap"
    const val WEEK = "set_week"
    const val GYM_GOAL = "set_gym_goal"
    const val NOW_SWAP = "workout_swap"
    const val NOW_REMOVE = "workout_remove"
    const val NOW_ADD = "workout_add"
    const val NOW_SETS = "workout_sets"
    const val PICKS = "set_exercise_picks"
    const val LAPS = "fix_run_laps"
    const val PROFILE = "set_profile"

    private const val MAX_SICK_PAUSE = 14
    private const val MAX_PAUSE = 21
    private const val MAX_WEEKS = 8
    private const val MAX_NOTES = 30
    private const val NOTE_LEN = 200
    /** Changes the coach makes by itself in a day; past them every change waits for «Принять». */
    const val AUTO_A_DAY = 6
    /** A working weight changed by more than this share waits for «Принять»; by more than [WEIGHT_MAX], not at all. */
    private const val WEIGHT_AUTO = 0.10
    private const val WEIGHT_MAX = 0.25
    /** A shorter easy run than this share of the minutes before the coach's changes of two weeks waits for «Принять». */
    private const val EASY_CUT_AUTO = 0.90

    /** The functions the model is offered (Gemini function declarations, `parameters` as JSON Schema). */
    val declarations: JsonArray = buildJsonArray {
        fun fn(name: String, description: String, vararg params: Param) = add(
            buildJsonObject {
                put("name", name)
                put("description", description)
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        (params.toList() + WHY).forEach { p ->
                            putJsonObject(p.name) {
                                put("type", p.type)
                                put("description", p.text)
                                p.values?.let { v -> putJsonArray("enum") { v.forEach { add(it) } } }
                            }
                        }
                    }
                    putJsonArray("required") { (params.toList() + WHY).filter { it.required }.forEach { add(it.name) } }
                }
            },
        )
        val exercise = Param("exercise", "string", "id упражнения из раздела «Упражнения в плане» (или его название)")
        val session = Param("session", "string", "id или название силовой тренировки, если упражнение в нескольких; иначе не указывай", required = false)
        fn(FEEL, "Самочувствие сегодня, как кнопки «Как ты сегодня?»: low — «Так себе» (сегодня в силовой меньше подходов и вес легче на 10 %, бег только лёгкий), good — обычная тренировка, none — снять отметку.",
            Param("feel", "string", "good, low или none", values = listOf("good", "low", "none")))
        fn(SICK, "Человек болеет СЕЙЧАС (температура, простуда): «Болею» сегодня и пауза на pause_days дней с сегодня. После болезни первые 2 пробежки лёгкие, первая силовая облегчённая. О прошлом («болел на прошлой неделе») ничего не отмечай. Человек подтверждает кнопкой.",
            Param("pause_days", "integer", "дней паузы с сегодня, 0–$MAX_SICK_PAUSE"))
        fn(NOT_SICK, "Снять «Болею»: оказалось, не болел — дни болезни станут обычным пропуском. Человек подтверждает кнопкой.")
        fn(PAUSE, "Пауза в плане с сегодня (отпуск, поездка, не может тренироваться): тренировок не ждём, пропуски не копятся. Только с сегодня, о прошлом не отмечай. 7 дней и больше человек подтверждает.", Param("days", "integer", "дней, 1–$MAX_PAUSE"))
        fn(RESUME, "Закончить паузу и вернуться к плану сегодня.")
        fn(RATE, "Исправить оценку тяжести последней законченной пробежки или силовой (1–10, как в приложении) — только когда человек говорит об оценке той тренировки. Не для подходов идущей сейчас тренировки. Оценка последней пробежки решает её заново: лёгкий бег длиннее или короче, шаг лестницы.",
            Param("which", "string", "last_run — последняя пробежка, last_gym — последняя силовая", values = listOf("last_run", "last_gym")), Param("rpe", "integer", "1–10"))
        fn(LAPS, "Исправить круги прошлой пробежки на стадионе, когда человек говорит, что с «Круг» вышло не так: забыл нажать — один круг на самом деле был несколькими («пробежал 3 круга, а нажал один раз» — split на 3); нажал лишний раз — два круга были одним (merge); круга не было (случайное нажатие вне отрезков — remove); круг шёл другое время (time). Номер круга — по порядку в строке «Круги» этой пробежки в данных, с 1. В отрезке круг делится, только пока в нём меньше кругов, чем по плану. Человек подтверждает кнопкой; последняя пробежка по исправленным кругам отрезков решится заново.",
            Param("action", "string", "split — разделить круг на parts кругов, merge — склеить круг со следующим, remove — убрать круг, time — время круга seconds", values = listOf("split", "merge", "remove", "time")),
            Param("lap", "integer", "номер круга по порядку в строке «Круги» пробежки, с 1"),
            Param("parts", "integer", "для split: сколько кругов это было на самом деле, 2–6", required = false),
            Param("seconds", "number", "для time: время круга, секунды", required = false),
            Param("day", "string", "дата пробежки ГГГГ-ММ-ДД; не указывай — последняя пробежка", required = false))
        fn(EASY, "Длина лёгкого бега с этого дня (минуты); дальше она меняется по правилам приложения. Не больше потолка под цель, не больше 110 % самой длинной пробежки за 30 дней и не больше +15 % (минимум +5 мин) за неделю. Второе изменение за неделю и сокращение больше чем на 10 % за две недели человек подтверждает.",
            Param("minutes", "integer", "минуты"))
        fn(RUN_TODAY, "Лёгкая пробежка сегодня вне плана: человек хочет пробежать сегодня (пропустил бег, свободный день). На экране «Сегодня» появится карточка с кнопкой «Начать». Только лёгкая: тяжёлый бег рядом с другим тяжёлым днём вреден, а пропущенный тяжёлый, когда он подходит, приложение само предлагает сделать сегодня. Не в день, где бег уже есть.",
            Param("minutes", "integer", "минуты, 15–60; не сказано — как лёгкий бег по плану", required = false))
        fn(TEMPO, "Темповый бег человека: сколько отрезков, по сколько минут и сколько отдыха трусцой между ними — когда он просит другой темповый или темповый был не по силам («5 минут подряд тяжело, хочу по 2,5 с минутой отдыха»). Темп отрезков задаёт приложение, ты его не меняешь. Отрезок 1,5–20 мин (шаг 0,5), отдых 0,5–3 мин, всего в темпе не меньше 6 мин и не больше, чем сейчас по плану, плюс 15 %. Получится хорошо — приложение само поведёт отрезки к своему шагу; слишком тяжело — само сделает их короче. reset: true — вернуть темповый приложения. Человек подтверждает кнопкой.",
            Param("reps", "integer", "отрезков, 1–10", required = false),
            Param("work_min", "number", "минут в отрезке, 1,5–20", required = false),
            Param("rest_min", "number", "минут отдыха трусцой между отрезками, 0,5–3", required = false),
            Param("reset", "boolean", "true — вернуть темповый приложения", required = false))
        fn(TRIAL, "Контрольный забег: поставить на следующий тяжёлый день (next) — когда человек сам хочет проверить форму; или отложить на 4 недели (skip) — когда он не хочет или не готов («зачем контрольный, я не пробегу»). При цели контрольный не обязателен: форму приложение оценивает по тренировкам. Темп контрольного — по нынешней форме, не по цели.",
            Param("when", "string", "next — поставить, skip — отложить на 4 недели", values = listOf("next", "skip")))
        fn(RUN_PACE, "Темпы бега спокойнее или быстрее — когда человек говорит, что бег по темпу слишком тяжёлый или слишком лёгкий. Сдвигает форму, от которой идут ВСЕ темпы (лёгкий, темповый, интервалы, отрезки, контрольный): один шаг — примерно 2 % темпа, около 1–1,5 с на круге 200 м. easier приложение делает сразу, один раз за ответ (второй такой вызов в том же ответе не делается); harder человек подтверждает. Одна тяжёлая тренировка — не повод делать быстрее.",
            Param("change", "string", "easier — спокойнее, harder — быстрее", values = listOf("easier", "harder")),
            Param("steps", "integer", "1 или 2; не сказано — 1", required = false))
        fn(RUN_STEP, "Шаг тяжёлой пробежки легче или тяжелее на одну ступень лестницы приложения: race_pace — отрезки в темпе цели, intervals — интервалы, tempo — темповый (свой темповый меняется через set_tempo). Когда человек говорит, что отрезки слишком длинные, их слишком много или наоборот мало. easier — сразу, для каждой пробежки один раз за ответ; harder — человек подтверждает. Темп при этом не меняется (для темпа — set_run_pace).",
            Param("kind", "string", "race_pace, intervals или tempo", values = listOf("race_pace", "intervals", "tempo")),
            Param("change", "string", "easier — на ступень легче, harder — на ступень тяжелее", values = listOf("easier", "harder")))
        fn(WISH, "Записать пожелание разработчику приложения: то, что человек попросил, а ты сделать не смог (нет такой функции, приложение этого не умеет). Одной фразой — что он хотел получить. Сначала всё равно помоги тем, что есть.",
            Param("text", "string", "что человек хотел, до $NOTE_LEN знаков"))
        fn(APP, "Настройка приложения (как в «Ещё → Оформление и звук»): voice — голос на пробежке, beeps — звуковые сигналы, vibration — вибрация, gps — GPS на улице по умолчанию, auto_rest — таймер отдыха сам после подхода, keep_screen_on — не гасить экран на тренировке, auto_pause — автопауза на остановках (value: on или off); look — оформление (value: ${forma.app.ui.theme.Looks.all.joinToString(", ") { "${it.id} — ${it.name}" }}).",
            Param("key", "string", "какая настройка", values = APP_FLAGS.keys.toList() + "look"),
            Param("value", "string", "on или off; для look — id оформления"))
        fn(BODY_WEIGHT, "Записать вес тела человека сегодня (кг), когда он его называет: попадёт в график «Вес тела» и в расчёты упражнений с весом тела.",
            Param("kg", "number", "кг, 30–250"))
        fn(OPEN, "Показать человеку кнопку «Открыть» на экран приложения — когда что-то делается только руками или он спрашивает, где это найти. Только экраны из списка: других в приложении нет.",
            Param("screen", "string", SCREENS.entries.joinToString(", ") { "${it.key} — «${it.value}»" }, values = SCREENS.keys.toList()))
        fn(LIGHTER, "Разгрузочная (лёгкая) неделя — в зале вдвое меньше подходов и вес на 10 % легче, бег короче: this — сделать лёгкой эту неделю (устал, не восстановился, просит разгрузку), next — следующую, skip — эта неделя лёгкая по плану, а человек её не хочет: блок начинается заново, лёгкая придёт в его конце. Человек подтверждает кнопкой.",
            Param("when", "string", "this — эта неделя, next — следующая, skip — отменить лёгкую на этой неделе", values = listOf("this", "next", "skip")))
        fn(ORDER, "Порядок упражнений в силовой тренировке: поставить упражнение первым, последним или на место N (изменение плана; человек подтверждает). Сам порядок приложение строит так: главные упражнения первыми, пока силы свежие, одиночные — в конце; reset — вернуть порядок приложения.",
            exercise, Param("place", "string", "first — первым, last — последним, номер места (1, 2, 3 …) или reset — порядок приложения"), session)
        fn(EASE_DAY, "В этот день недели вместо тяжёлого бега — лёгкий, на weeks недель (0 — вернуть тяжёлый). Когда тяжёлых пробежек слишком много, ноги не успевают восстановиться, болят голени — или человек хочет один тяжёлый бег в неделю. При боли — на 1–2 недели, потом спроси снова. До 2 недель делается сразу, можно вернуть; дольше, или когда при цели по бегу в неделе не остаётся тяжёлого бега, — человек подтверждает.",
            Param("day", "string", "день недели: пн, вт, ср, чт, пт, сб или вс"), Param("weeks", "integer", "0–$MAX_WEEKS"))
        fn(RUN_CUSTOM, "Своя пробежка на СЕГОДНЯ, какой нет в плане: reps отрезков по work_sec секунд (или work_m метров) в темпе pace, между ними rest_sec трусцой; до и после — лёгкий бег. Примеры: «8 по 200 быстро через минуту» — reps 8, work_m 200, pace fast, rest_sec 60; «20 минут спокойно» — reps 1, work_sec 1200, pace easy; «фартлек 6 по 2 минуты» — reps 6, work_sec 120, pace interval, rest_sec 120. Темп отрезков даёт приложение по форме человека; на стадионе отрезки станут целыми кругами. Заменяет сегодняшнюю пробежку плана, засчитывается, шаги плана не двигает. Тяжёлую человек подтверждает; тяжёлая не ставится рядом с другим тяжёлым бегом и в день ног, целиком — до 110 % самой длинной за 30 дней (длиннее урежется). Для постоянных изменений — set_tempo, set_run_step, set_easy_run_minutes.",
            Param("reps", "integer", "сколько отрезков, 1–20 (1 — один непрерывный бег)"),
            Param("work_sec", "integer", "секунд в отрезке, 20–3600; или укажи work_m", required = false),
            Param("work_m", "integer", "метров в отрезке, 100–10000; или укажи work_sec", required = false),
            Param("pace", "string", "easy — лёгкий, tempo — темповый (тяжело, но держишь), interval — интервальный, race — темп забега на цель, fast — быстрый (короткие отрезки), free — по ощущениям", values = listOf("easy", "tempo", "interval", "race", "fast", "free")),
            Param("rest_sec", "integer", "отдых между отрезками, секунды, 0–600; не сказано — 90", required = false),
            Param("warmup_min", "integer", "разминка лёгким бегом, минут, 0–20; не сказано — 10 (для лёгкого бега 0)", required = false),
            Param("cooldown_min", "integer", "заминка, минут, 0–15; не сказано — 5 (для лёгкого бега 0)", required = false))
        fn(STRIDES, "Сколько ускорений после лёгкого бега в дни, когда они есть в плане, на weeks недель (0 — без ускорений).",
            Param("count", "integer", "0–8 (пока бег новый — до 6)"), Param("weeks", "integer", "1–$MAX_WEEKS"))
        fn(LONG, "Последний лёгкий бег недели — длинный лёгкий, на weeks недель (0 — отменить). Длина длинного растёт по правилам приложения: до часа, в день с залом — до 45 мин.",
            Param("weeks", "integer", "0–$MAX_WEEKS"))
        fn(STEP, "Шаг веса упражнения в зале человека — только когда он говорит, как меняется вес у снаряда в его зале (гантели через 2,5 кг, тренажёр через 4 кг). Тренажёр, где вес идёт не с нуля (9, 14, 19, 24 кг — шаг 5 от 9), — step_kg 5 и from_kg 9. По этому шагу приложение прибавляет вес — сразу, и в идущей тренировке. Не для того, чтобы поднять или снизить вес — для этого set_working_weight.",
            exercise, Param("step_kg", "number", "кг, 0,25–20"),
            Param("from_kg", "number", "первый (самый лёгкий) вес тренажёра, если вес идёт не кратно шагу: 9 для 9, 14, 19 …; 0 — кратно шагу. Не сказано — как было", required = false))
        fn(WEIGHT, "Рабочий вес упражнения на следующие тренировки, когда человек стабильно может больше или меньше, чем даёт приложение. Человек подтверждает кнопкой. Не больше 25 % за раз, тяжелее — только в пределах расчётного максимума с запасом плана, не ниже 85 % лучшего за 8 недель. Упражнение, которого ещё не было, первая тренировка подбирает сама. На вопрос «почему» сначала объясни.",
            exercise, Param("kg", "number", "кг (для упражнений с весом тела — добавочный вес)"), session)
        fn(REMEMBER, "Запомнить о человеке что-то важное надолго: нравится или нет, травмы, что есть в зале, расписание. Коротко, одной фразой.", Param("text", "string", "до $NOTE_LEN знаков"))
        fn(FORGET, "Забыть запомненное, если оно больше не верно.", Param("text", "string", "запомненная фраза или её часть"))
        fn(SETS, "Подходы упражнения во всех тренировках, где оно есть (изменение плана; человек подтверждает кнопкой).", exercise, Param("sets", "integer", "1–${TrainingRepository.MAX_SETS}"), session)
        fn(REPS, "Диапазон повторов упражнения во всех тренировках, где оно есть (изменение плана; человек подтверждает).", exercise, Param("min", "integer", "от"), Param("max", "integer", "до"), session)
        fn(REST, "Отдых между подходами упражнения во всех тренировках, где оно есть, секунды (изменение плана; человек подтверждает).", exercise,
            Param("seconds", "integer", "${TrainingRepository.MIN_REST}–${TrainingRepository.MAX_REST}"), session)
        fn(SWAP, "Заменить упражнение плана другим насовсем (изменение плана; человек подтверждает).", exercise, Param("new_exercise", "string", "название нового упражнения"))
        fn(PICKS, "«Мои упражнения»: какие упражнения человеку нравятся и какие он не хочет делать (по движениям: присед, наклон, жимы, тяги…). Понравившиеся встают на места своего движения, «не хочу» заменяются похожими. Основа плана, упражнение цели в зале и при цели «Сила» штанга в главных остаются — приложение само соблюдает правила и в карточке скажет, что поменяется и что оставит. Человек подтверждает.",
            Param("like", "string", "упражнения, которые нравятся, через запятую (id или названия)", required = false),
            Param("dislike", "string", "упражнения, которые не хочет делать, через запятую", required = false),
            Param("clear", "string", "упражнения, с которых снять отметку, через запятую", required = false))
        fn(REMOVE, "Убрать упражнение из плана насовсем (изменение плана; человек подтверждает).", exercise, session)
        fn(ADD, "Добавить упражнение в силовую тренировку насовсем (изменение плана; человек подтверждает).",
            Param("session", "string", "id или название силовой тренировки"), Param("exercise", "string", "название упражнения"))
        fn(GOAL, "Цель по бегу: дистанция и время, дата забега, если есть (человек подтверждает). Другая дистанция или время больше чем на 5 % начинают отрезки в темпе цели сначала.",
            Param("meters", "integer", "метры"), Param("time", "string", "время, например 5:30 или 21:00"),
            Param("date", "string", "дата забега ГГГГ-ММ-ДД, если есть", required = false))
        fn(LAP, "Длина круга стадиона, где человек бегает (метры), или 0 — бегает не на стадионе (человек подтверждает).", Param("meters", "integer", "100–600 или 0"))
        fn(WEEK, "Что в неделе и в какие дни: зал и бег, только зал или только бег; дни зала и дни бега (человек подтверждает). Цели, прогресс в упражнениях и форма бега остаются. Что изменение стоит целям, приложение скажет в карточке.",
            Param("sides", "string", "both — зал и бег, gym — только зал, run — только бег; если не меняется — не указывай", required = false, values = listOf("both", "gym", "run")),
            Param("gym_days", "string", "дни зала через запятую: пн, вт, ср, чт, пт, сб, вс; если не меняются — не указывай", required = false),
            Param("run_days", "string", "дни бега так же; если не меняются — не указывай", required = false))
        fn(PROFILE, "Анкета, по которой приложение само строит план (раздел «Анкета»): для чего зал и бег и что главнее, дни, длина тренировки, где занимается, опыт, как бегает сейчас, вес тела, рост, год рождения, пол, лучший забег, дистанция без остановки. «Составь план», «хочу 4 дня», «я вешу 82» — сначала спроси, чего не хватает, ответов не придумывай; потом вызови с тем, что сказано, остального не указывай. План пересчитается по правилам приложения, прогресс в упражнениях и форма бега останутся; человек подтверждает. Цели — set_run_goal и set_gym_goal, дни зала и бега по отдельности — set_week.",
            Param("lift_focus", "string", "зал: strength — сила, powerbuilding — сила и масса, muscle — мышцы, general — тонус, none — без зала", required = false, values = LIFT_KEYS.keys.toList()),
            Param("run_focus", "string", "бег: health — для здоровья, endurance — выносливость, speed — скорость, none — без бега", required = false, values = RUN_KEYS.keys.toList()),
            Param("priority", "string", "что главнее, когда есть и зал, и бег: gym, balanced (поровну) или run", required = false, values = PRIORITY_KEYS.keys.toList()),
            Param("days", "string", "дни тренировок через запятую: пн, вт, ср, чт, пт, сб, вс — зал и бег по ним разложит приложение", required = false),
            Param("double_days", "boolean", "зал и бег в один день можно", required = false),
            Param("session_minutes", "integer", "сколько минут на тренировку, 30–120", required = false),
            Param("gear", "string", "где занимается: gym — зал, dumbbells — дома с гантелями и скамьёй, body — дома без снарядов", required = false, values = GEAR_KEYS.keys.toList()),
            Param("pullup_bar", "boolean", "дома есть турник", required = false),
            Param("experience", "string", "опыт в зале: novice — меньше полугода, intermediate — полгода–два года, advanced — больше двух лет", required = false, values = EXPERIENCE_KEYS.keys.toList()),
            Param("run_level", "string", "бег сейчас: none — не пробежит 20 мин подряд, base — пробежит 20–30 мин, trained — бегает регулярно, с быстрыми отрезками", required = false, values = LEVEL_KEYS.keys.toList()),
            Param("body_weight", "number", "вес тела, кг", required = false),
            Param("height", "integer", "рост, см", required = false),
            Param("birth_year", "integer", "год рождения", required = false),
            Param("sex", "string", "male или female", required = false, values = SEX_KEYS.keys.toList()),
            Param("race_meters", "integer", "лучший недавний забег или контрольный: дистанция, м (0 — убрать)", required = false),
            Param("race_time", "string", "его время, как 6:05 или 24:30", required = false),
            Param("finish_meters", "integer", "пробежать без остановки, без времени: дистанция, м (0 — убрать)", required = false))
        fn(GYM_GOAL, "Цель в зале: упражнение из плана на несколько мышц с весом (жим, присед, тяга), вес на один раз или на reps раз, дата — если есть (человек подтверждает). С plan по умолчанию план подстроится: тяжёлый день 3–5 повторов, второй день 6–10. Вес 0 — снять цель. Насколько цель реальна, приложение скажет в карточке; нереальную к дате — предложи ближнюю.",
            Param("exercise", "string", "id или название упражнения из плана (для снятия цели не нужно)", required = false), Param("kg", "number", "кг на один раз (или на reps раз); 0 — снять цель"),
            Param("reps", "integer", "на сколько повторов, 1–12; по умолчанию 1", required = false), Param("date", "string", "ГГГГ-ММ-ДД, если есть дата", required = false),
            Param("plan", "boolean", "подстроить план под цель; по умолчанию да", required = false))
        val now = Param("exercise", "string", "id или название упражнения из раздела «Идёт сейчас»")
        fn(NOW_SWAP, "Только силовая, которая идёт сейчас (раздел «Идёт сейчас»): заменить упражнение на сегодня, пока по нему нет подходов (тренажёр занят, неудобно, болит). План не меняется.",
            now, Param("new_exercise", "string", "название упражнения на замену"))
        fn(NOW_REMOVE, "Только силовая, которая идёт сейчас: убрать упражнение из сегодняшней тренировки, пока по нему нет подходов (нет времени, болит). План не меняется.", now)
        fn(NOW_ADD, "Только силовая, которая идёт сейчас: добавить упражнение в конец сегодняшней тренировки. План не меняется.",
            Param("exercise", "string", "название упражнения"))
        fn(NOW_SETS, "Только силовая, которая идёт сейчас: сколько подходов упражнения сегодня — меньше (устал, мало времени) или больше (хочется добрать). Не меньше уже сделанных. План не меняется.",
            now, Param("sets", "integer", "1–${TrainingRepository.MAX_SETS}"))
    }

    private class Param(val name: String, val type: String, val text: String, val required: Boolean = true, val values: List<String>? = null)

    /** Every change says why, in the person's terms: the card shows it (the model often calls without any text). */
    // A getter: [declarations] above is built before a stored value here would be.
    private val WHY get() = Param("why", "string", "почему это меняешь — коротко и просто, для человека, по его данным и правилам приложения")

    /** A number from the model; not one (text, NaN, infinity) — null. */
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
        ?.let { it.doubleOrNull ?: it.content.replace(',', '.').trim().toDoubleOrNull() }?.takeIf { it.isFinite() && abs(it) < 1e6 }
    private fun JsonObject.int(key: String): Int? = num(key)?.takeIf { abs(it - it.roundToInt()) < 1e-6 }?.roundToInt()
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }
    private fun args(vararg pairs: Pair<String, Any?>) = JsonObject(
        pairs.filter { it.second != null }.associate { (k, v) ->
            k to when (v) {
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is JsonElement -> v
                else -> JsonPrimitive(v.toString())
            }
        },
    )
    private fun ints(xs: List<Int>?) = xs?.let { JsonArray(it.map { n -> JsonPrimitive(n) }) }
    private fun JsonObject.ints(key: String): List<Int>? = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
    private fun pauses(p: List<List<Long>>) = JsonArray(p.map { r -> JsonArray(r.map { JsonPrimitive(it) }) })
    private fun JsonObject.pauses(key: String): List<List<Long>> =
        (this[key] as? JsonArray).orEmpty().map { r -> (r as JsonArray).mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() } }
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toLongOrNull()
    private val lapList = kotlinx.serialization.builtins.ListSerializer(forma.core.run.Lap.serializer())
    private fun lapsJson(laps: List<forma.core.run.Lap>): JsonElement = AppJson.encodeToJsonElement(lapList, laps)
    private fun lapsOf(e: JsonElement?): List<forma.core.run.Lap> = e?.let { AppJson.decodeFromJsonElement(lapList, it) }.orEmpty()

    private fun days(n: Int) = "$n ${Ru.plural(n, "день", "дня", "дней")}"

    // The words of [PROFILE] as the model sends them. Getters: [declarations] is built before stored values here would be.
    private val LIFT_KEYS get() = mapOf("strength" to LiftFocus.STRENGTH, "powerbuilding" to LiftFocus.POWERBUILDING, "muscle" to LiftFocus.MUSCLE, "general" to LiftFocus.GENERAL, "none" to LiftFocus.NONE)
    private val RUN_KEYS get() = mapOf("health" to RunFocus.HEALTH, "endurance" to RunFocus.ENDURANCE, "speed" to RunFocus.SPEED, "none" to RunFocus.NONE)
    private val PRIORITY_KEYS get() = mapOf("gym" to Emphasis.GYM, "balanced" to Emphasis.BALANCED, "run" to Emphasis.RUN)
    private val GEAR_KEYS get() = mapOf("gym" to Gear.GYM, "dumbbells" to Gear.DUMBBELLS, "body" to Gear.BODY)
    private val EXPERIENCE_KEYS get() = mapOf("novice" to Experience.NOVICE, "intermediate" to Experience.INTERMEDIATE, "advanced" to Experience.ADVANCED)
    private val LEVEL_KEYS get() = mapOf("none" to RunLevel.NONE, "base" to RunLevel.BASE, "trained" to RunLevel.TRAINED)
    private val SEX_KEYS get() = mapOf("male" to Sex.MALE, "female" to Sex.FEMALE)

    /** The aim in the app's words (the chips of «Цель, дни и данные»). */
    private fun aimWords(aim: Aim): String {
        val a = aim.normalized()
        val lift = when (a.lift) {
            LiftFocus.STRENGTH -> "сила"; LiftFocus.POWERBUILDING -> "сила и масса"; LiftFocus.MUSCLE -> "мышцы"; LiftFocus.GENERAL -> "тонус"; LiftFocus.NONE -> "без зала"
        }
        val run = when (a.run) { RunFocus.HEALTH -> "для здоровья"; RunFocus.ENDURANCE -> "выносливость"; RunFocus.SPEED -> "скорость"; RunFocus.NONE -> "без бега" }
        val first = when (a.emphasis) { Emphasis.GYM -> "главное — зал"; Emphasis.BALANCED -> "зал и бег поровну"; Emphasis.RUN -> "главное — бег" }
        return "зал — $lift, бег — $run" + if (a.lifts && a.runs) ", $first" else ""
    }

    /**
     * A field of the profile the coach may set ([PROFILE]): what it is called, its value in words (the card says
     * «было → станет», the brief shows it, and a way back checks it has not moved since) and how it is put on a profile.
     */
    private class Field(val key: String, val label: String, val words: (Profile) -> String, val put: (Profile, Profile) -> Profile)

    private val FIELDS: List<Field> get() = listOf(
        Field("aim", "для чего", { aimWords(it.aim) }) { p, f -> p.copy(aim = f.aim, goal = f.goal, keptAim = f.keptAim) },
        Field("days", "дни", { p -> if (p.ownWeek) "зал — ${dayList(p.gymDays!!)}, бег — ${dayList(p.runDays!!)}" else dayList(p.trainingDays) }) { p, f ->
            p.copy(trainingDays = f.trainingDays, gymDays = f.gymDays, runDays = f.runDays)
        },
        Field("double", "зал и бег в один день", { if (it.doubleDays) "можно" else "нет" }) { p, f -> p.copy(doubleDays = f.doubleDays) },
        Field("minutes", "тренировка", { "до ${it.sessionMinutes} мин" }) { p, f -> p.copy(sessionMinutes = f.sessionMinutes) },
        Field("gear", "где занимается", { p -> p.gear.ru + if (p.gear == Gear.GYM) "" else if (p.pullupBar) ", есть турник" else ", без турника" }) { p, f ->
            p.copy(gear = f.gear, pullupBar = f.pullupBar)
        },
        Field("experience", "опыт в зале", { p ->
            when (p.experience) { Experience.NOVICE -> "новичок"; Experience.INTERMEDIATE -> "средний"; Experience.ADVANCED -> "опытный" }
        }) { p, f -> p.copy(experience = f.experience) },
        Field("level", "бег сейчас", { p ->
            when (p.runLevel) { RunLevel.NONE -> "пока не пробегает 20 минут подряд"; RunLevel.BASE -> "пробегает 20–30 минут"; RunLevel.TRAINED -> "бегает регулярно, с быстрыми отрезками" }
        }) { p, f -> p.copy(runLevel = f.runLevel) },
        Field("weight", "вес тела", { "${Ru.kg(it.bodyWeightKg)} кг" }) { p, f -> p.copy(bodyWeightKg = f.bodyWeightKg) },
        Field("height", "рост", { p -> p.heightCm?.let { "$it см" } ?: "не указан" }) { p, f -> p.copy(heightCm = f.heightCm) },
        Field("born", "год рождения", { "${it.birthYear}" }) { p, f -> p.copy(birthYear = f.birthYear) },
        Field("sex", "пол", { if (it.sex == Sex.FEMALE) "женский" else "мужской" }) { p, f -> p.copy(sex = f.sex) },
        Field("race", "лучший забег", { p ->
            val m = p.raceMeters
            val t = p.raceSeconds
            if (m == null || t == null) "нет" else "${dist(m.roundToInt())} за ${Ru.clock(t.roundToInt())}"
        }) { p, f -> p.copy(raceMeters = f.raceMeters, raceSeconds = f.raceSeconds) },
        Field("finish", "пробежать без остановки", { p -> p.finishMeters?.let { dist(it) } ?: "нет" }) { p, f -> p.copy(finishMeters = f.finishMeters) },
    )

    /** What the week is laid out by: a change of any of these builds the plan anew. */
    private val SHAPE = setOf("aim", "days", "double", "minutes", "gear", "experience")

    /** The person's own numbers: a friend's program takes them as they are (its shape is the friend's). */
    private val BODY = setOf("weight", "height", "born", "sex")

    /** The profile as the coach sees it (the brief's «Анкета»), a line a field. */
    fun profileLines(p: Profile): List<String> = FIELDS.map { "${it.label.replaceFirstChar(Char::titlecase)}: ${it.words(p)}" }

    /** [next] saved: the plan built anew from it — on a friend's program only the profile (its body's numbers). */
    private suspend fun saveProfile(repo: TrainingRepository, next: Profile, was: Profile) {
        if (repo.store.programNow()?.copiedFrom != null) {
            repo.store.setProfile(next)
            if (next.bodyWeightKg != was.bodyWeightKg) repo.logBodyWeight(next.bodyWeightKg) else repo.changed()
        } else {
            repo.updateProfile(next)
        }
    }

    private val DAY_NAMES = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

    /** A day of the week after «в» ("во вторник"), by its ISO number less one. */
    private val ON_DAY = listOf("в понедельник", "во вторник", "в среду", "в четверг", "в пятницу", "в субботу", "в воскресенье")

    /** Starts of the days' names, short and full ("чт", "четверг"). */
    private val DAY_STARTS = listOf("пн" to 1, "пон" to 1, "вт" to 2, "ср" to 3, "чт" to 4, "чет" to 4, "пт" to 5, "пят" to 5, "сб" to 6, "суб" to 6, "вс" to 7, "вос" to 7)

    /** "вт, чт, сб" (or "2,4,6", or "вторник и четверг") as ISO days; an empty text is no day; null when a word is no day. */
    internal fun weekDays(text: String): Set<Int>? {
        val words = text.lowercase().split(',', ' ', ';', '/').map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() && it != "и" }
        return words.map { w ->
            w.toIntOrNull()?.takeIf { it in 1..7 } ?: DAY_STARTS.firstOrNull { w.startsWith(it.first) }?.second ?: return null
        }.toSet()
    }

    private fun dayList(days: Collection<Int>) = days.sorted().joinToString(", ") { DAY_NAMES[it - 1] }

    /** "Важно: …" after a change's words when it costs a goal ([GoalRisk]); the card says it whatever the model does. */
    private fun withRisk(text: String, risk: String?) = if (risk == null) text else "$text Важно: $risk"

    /** The goal in the gym as the undo keeps it. */
    private fun goalJson(g: LiftGoal?) = g?.let { AppJson.encodeToJsonElement(LiftGoal.serializer(), it) }

    /** The same goal (the start of its way aside). */
    private fun sameGoal(a: LiftGoal?, b: LiftGoal?) = a?.copy(fromE1rm = null, setDay = null) == b?.copy(fromE1rm = null, setDay = null)
    private fun weeks(n: Int) = "$n ${Ru.plural(n, "неделю", "недели", "недель")}"

    /** One exercise of the gym plan as the plan screen has it: where, the plan's and the done exercise, its numbers' key. */
    class Place(
        val templateId: String,
        val title: String,
        val planId: String,
        /** Where its sets, reps and rest are kept: the plan's exercise id, or [TrainingRepository.addedKey] for one added. */
        val key: String,
        val exerciseId: String,
        val added: Boolean,
        /** As the plan has it, before the person's own sets, reps and rest. */
        val planned: SlotSpec,
        /** As done now (with the person's own numbers and the swap). */
        val tuned: SlotSpec,
    )

    /** Every exercise of the plan now, by session. */
    suspend fun places(repo: TrainingRepository): List<Place> {
        val program = repo.store.programNow() ?: return emptyList()
        val s = repo.store.settingsNow()
        return program.templates.flatMap { t ->
            val own = t.slots.filter { it.exerciseId !in s.removed }.map { spec ->
                val tuned = TrainingRepository.tuned(spec, s)
                Place(t.id, t.title, spec.exerciseId, spec.exerciseId, tuned.exerciseId, false, TrainingRepository.plannedFor(spec, s), tuned)
            }
            val added = repo.addedTo(t.id, s).map { id ->
                val key = TrainingRepository.addedKey(t.id, id)
                val planned = TrainingRepository.addedSpec(id)
                Place(t.id, t.title, id, key, id, true, planned, TrainingRepository.overrides(planned, s, key))
            }
            own + added
        }
    }

    private fun norm(x: String) = ExerciseSearch.normalize(x)

    /**
     * The places of [query] in the plan, in [session] when given; empty — not in the plan. The exercise done now (its id or
     * name) first; the plan's own id under a swap only when nothing done now is called so.
     */
    private fun find(places: List<Place>, query: String, session: String?): List<Place> {
        val inSession = session?.let { q -> places.filter { it.templateId == q || norm(it.title) == norm(q) }.ifEmpty { null } } ?: places
        val q = norm(query)
        inSession.filter { it.exerciseId == query || norm(Catalog[it.exerciseId].name) == q }.ifEmpty { null }?.let { return it }
        inSession.filter { it.planId == query }.ifEmpty { null }?.let { return it }
        // A name said a little differently: the one exercise of the plan the search puts first.
        val scored = inSession.map { it to ExerciseSearch.score(Catalog[it.exerciseId], query) }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        val top = scored.filter { it.second == best }.map { it.first }
        return if (top.map { it.exerciseId }.distinct().size == 1) top else emptyList()
    }

    /** An exercise of the catalog by id or name (the search's first, when one is clearly first). */
    private fun catalog(query: String): Exercise? {
        Catalog.find(query)?.takeIf { !it.archived }?.let { return it }
        val scored = Catalog.all.map { it to ExerciseSearch.score(it, query) }.filter { it.second > 0 }.sortedByDescending { it.second }
        val first = scored.firstOrNull() ?: return null
        return if (scored.getOrNull(1)?.second == first.second && norm(first.first.name) != norm(query)) null else first.first
    }

    private fun exercise(id: String, s: Settings): Exercise = s.onSteps(Catalog[id])

    private fun name(id: String) = Catalog.find(id)?.name ?: id

    private fun down(w: Double, step: Double) = floor(w / step + 1e-9) * step

    /** An easy run as «Сегодня» will start it ([easyOn]): its day, its minutes, why fewer than asked, the week's cap on that day. */
    class EasyAhead(val day: LocalDate, val minutes: Int, val why: String?, val weekCap: Int?)

    /**
     * The easy run of [day] as «Сегодня» will start it ([TrainingRepository.runFor]) with [minutes] (null: the plan's own
     * length), with the plan's caps — the week's running +10 % on the weeks before, a day with the gym, a lighter week, a
     * break — and why it is shorter. The coach's card said «Лёгкий бег: 40 → 45 мин» and its summary «40 мин» while
     * «Сегодня» started 15 (the audit of 24 November). Null when the day has another run than an easy one (a long run).
     */
    internal suspend fun easyOn(repo: TrainingRepository, day: LocalDate, minutes: Int? = null): EasyAhead? {
        val program = repo.store.programNow() ?: return null
        val w = runCatching { repo.runFor(SessionKind.RUN_EASY, day, minutes = minutes) }.getOrNull()
            ?.takeIf { it.type == forma.core.run.RunType.EASY } ?: return null
        val real = w.workSeconds / 60
        val profile = repo.store.profileNow()
        val saved = repo.runStateToGo(profile?.runGoal)
        val week = forma.core.run.RunPlan.weekCap(saved, day.toEpochDay())
        val gym = program.week.on(day.dayOfWeek.value).filter { it.kind == SessionKind.LIFT }
        val legs = gym.any { g -> program.templates.firstOrNull { it.id == g.templateId }?.legs == true }
        val why = when {
            real >= (minutes ?: saved.easyMinutes) -> null
            forma.core.run.RunPlan.breakDays(saved, day.toEpochDay()) != null -> "после перерыва короче"
            week != null && week <= real -> "неделя бега растёт не больше чем на 10 %"
            gym.isNotEmpty() && profile?.doubleDays != true && real <= 30 -> "в день с залом до 30 мин"
            legs && real <= TrainingRepository.AFTER_LEGS_EASY -> "в день ног до ${TrainingRepository.AFTER_LEGS_EASY} мин"
            ProgramGenerator.isRunCutbackWeek(program, day) -> "неделя лёгкая"
            else -> "по правилам плана на этот день"
        }
        return EasyAhead(day, real, why, week)
    }

    /** The plan's next easy run from [from] on, within a week and on a day with no run done yet, as [easyOn] gives it. */
    internal suspend fun nextEasy(repo: TrainingRepository, from: LocalDate, minutes: Int? = null): EasyAhead? {
        val program = repo.store.programNow() ?: return null
        val s = repo.store.settingsNow()
        val ran = repo.db.workouts().allDone().filter { it.kind == Kind.RUN }.map { it.epochDay }.toSet()
        return (0L..6L).map { from.plusDays(it) }.firstNotNullOfOrNull { d ->
            if (repo.paused(s, d) || d.toEpochDay() in ran || program.week.on(d.dayOfWeek.value).none { it.kind == SessionKind.RUN_EASY }) null
            else easyOn(repo, d, minutes)
        }
    }

    /** "Сегодня", "В субботу": the day of [day] seen from [today], to start a sentence. */
    private fun dayWords(day: LocalDate, today: LocalDate) =
        if (day == today) "Сегодня" else ON_DAY[day.dayOfWeek.value - 1].replaceFirstChar { it.uppercase() }

    /** [call] checked against the limits and the data now: ready to be made (or to be accepted), or refused with the reason. */
    suspend fun prepare(repo: TrainingRepository, call: CoachCall, today: LocalDate = repo.today()): Checked {
        val a = call.args
        fun no(why: String) = Refused(call, why)
        val log = repo.store.coachLogNow()
        val zone = java.time.ZoneId.systemDefault()
        fun dayOf(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        fun recent(undo: String, days: Long = 7) = log.filter { e ->
            !e.undone && e.undo?.name == undo && today.toEpochDay() - dayOf(e.at).toEpochDay() in 0 until days
        }
        // Many changes in one day is the model running away: past a few, each waits for the person.
        val crowded = log.count { !it.undone && dayOf(it.at) == today } >= AUTO_A_DAY
        fun auto(text: String, make: suspend () -> CoachCall?) = Ready(call, if (crowded) Mode.CONFIRM else Mode.AUTO, text, make)
        fun confirm(text: String, make: suspend () -> CoachCall?) = Ready(call, Mode.CONFIRM, text, make)
        fun either(sure: Boolean, text: String, make: suspend () -> CoachCall?) = if (sure) auto(text, make) else confirm(text, make)
        val s = repo.store.settingsNow()

        /** The week [next] lays out, in words, and what it costs the goals of [profile] (null: nothing). */
        fun weekOf(profile: Profile, next: Profile): Pair<String, String?> {
            val week = ProgramGenerator.generate(next, today)
            val liftDays = week.week.days.filter { d -> d.sessions.any { it.kind == SessionKind.LIFT } }.map { it.day }
            val runDays = week.week.days.filter { d -> d.sessions.any { it.isRun } }.map { it.day }
            val goal = profile.liftGoal
            val goalDays = goal?.let { g ->
                week.week.days.count { d ->
                    d.sessions.any { ps ->
                        val t = week.templates.firstOrNull { it.id == ps.templateId } ?: return@any false
                        t.slots.any { TrainingRepository.tuned(it, s).exerciseId == g.exerciseId } || g.exerciseId in s.added[t.id].orEmpty()
                    }
                }
            }
            val risk = GoalRisk.week(profile.runGoal, goal, goal?.let { name(it.exerciseId) }, runDays.size, liftDays.size, goalDays)
            val text = "Неделя: " + listOf(
                if (liftDays.isNotEmpty()) "зал — ${dayList(liftDays)}" else "без зала",
                if (runDays.isNotEmpty()) "бег — ${dayList(runDays)}" else "без бега",
            ).joinToString("; ") + ". План пересчитается, прогресс в упражнениях и форма бега останутся."
            return text to risk
        }

        /** What a pause or «Болею» changes besides itself, to put back exactly: the pauses, the day's mark, the block, a lighter week. */
        suspend fun before(): JsonObject {
            val now = repo.store.settingsNow()
            val lift = repo.store.liftStateNow()
            return args(
                "day" to today.toEpochDay(), "feel" to now.feelOn(today).name,
                "from" to now.pauseFrom, "until" to now.pauseUntil, "past" to pauses(now.pastPauses),
                "start" to repo.store.programNow()?.startEpochDay, "deload" to lift.reactiveDeloadStart, "asked" to lift.askedDeloadStart,
            )
        }

        /**
         * The block and its lighter weeks as a pause or «Болею» left them: «Вернуть» puts each back only while it is still
         * so — a lighter week set since (asked of the coach, the app's own) or a block moved on since stays.
         */
        suspend fun leftNow(): JsonObject {
            val lift = repo.store.liftStateNow()
            return args("start" to repo.store.programNow()?.startEpochDay, "deload" to lift.reactiveDeloadStart, "asked" to lift.askedDeloadStart)
        }

        return when (call.name) {
            FEEL -> {
                val feel = when (a.str("feel")) { "good" -> Feel.GOOD; "low" -> Feel.LOW; "none" -> Feel.UNSET; else -> return no("Непонятно, какое самочувствие.") }
                val was = s.feelOn(today)
                if (was == Feel.SICK) return no("Сегодня отмечено «Болею» — сначала его снять.")
                if (was == feel) return no("Так уже отмечено.")
                val running = repo.db.workouts().active()?.kind == Kind.LIFT
                val text = when (feel) {
                    Feel.LOW -> if (running) "Сегодня «Так себе»: начатая силовая не изменится, бег сегодня только лёгкий."
                        else "Сегодня «Так себе»: в силовой меньше подходов и вес легче на 10 %, бег только лёгкий."
                    Feel.GOOD -> "Сегодня обычная тренировка (самочувствие «Хорошо»)."
                    else -> "Самочувствие на сегодня снято."
                }
                auto(text) {
                    repo.updateSettings { it.withFeel(today, feel) }
                    CoachCall("undo_feel", args("day" to today.toEpochDay(), "feel" to was.name, "expect" to feel.name))
                }
            }
            SICK -> {
                val n = a.int("pause_days") ?: 0
                if (n !in 0..MAX_SICK_PAUSE) return no("Пауза при болезни — от 0 до $MAX_SICK_PAUSE дней.")
                if (s.feelOn(today) == Feel.SICK) return no("«Болею» сегодня уже отмечено.")
                val until = today.plusDays(n - 1L)
                // In a pause already (a holiday): the day is marked and the pause goes on, longer if the illness asks — never a
                // second pause inside it, whose end the cards would say while the holiday still held the days after it.
                val held = s.pauseOn(today)
                val longer = held != null && n > 0 && until.toEpochDay() > held[1]
                val pauseWords = when {
                    held == null -> if (n > 0) " и пауза на ${days(n)}, до ${Ru.date(until)}" else ""
                    longer -> ", пауза продлится до ${Ru.date(until)}"
                    else -> ", пауза и так до ${Ru.date(LocalDate.ofEpochDay(held[1]))}"
                }
                // It changes the next runs and the first gym session: the person says it is today.
                confirm("«Болею» сегодня$pauseWords. Потом первые пробежки лёгкие, первая силовая — полегче.") {
                    val was = before()
                    repo.updateSettings { it.withFeel(today, Feel.SICK) }
                    if (held == null) {
                        if (n > 0) repo.pause(today, until)
                    } else if (longer) repo.pause(LocalDate.ofEpochDay(held[0]), until)
                    val now = repo.store.settingsNow()
                    CoachCall("undo_sick", JsonObject(was + args("setFrom" to now.pauseFrom, "setUntil" to now.pauseUntil, "left" to leftNow())))
                }
            }
            NOT_SICK -> {
                if (repo.sickEnd(s, today.plusDays(1)) == null) return no("Болезни не отмечено.")
                // A holiday the illness fell into goes on: only the sickness's own pause ends.
                confirm("Снять «Болею»: дни болезни станут обычным пропуском" + (if (repo.sickPauseOn(s, today)) ", пауза закончится." else ".")) { repo.notSick(today); null }
            }
            PAUSE -> {
                val n = a.int("days") ?: return no("Не сказано, на сколько дней.")
                if (n !in 1..MAX_PAUSE) return no("Пауза — от 1 до $MAX_PAUSE дней.")
                if (repo.paused(s, today)) return no("Пауза уже идёт.")
                val until = today.plusDays(n - 1L)
                val prof = repo.store.profileNow()
                val risk = GoalRisk.pause(
                    n, prof?.runGoal, prof?.runGoal?.date?.let { it - today.toEpochDay() },
                    prof?.liftGoal, prof?.liftGoal?.date?.let { it - today.toEpochDay() }, prof?.liftGoal?.let { name(it.exerciseId) },
                )
                // A week or more moves the lifting block: the person says; and so when it costs a goal.
                either(n < 7 && risk == null, withRisk("Пауза на ${days(n)}, до ${Ru.date(until)}: тренировок не ждём, пропуски не копятся." +
                    (if (n >= 7) " Силовой блок начнётся заново с недели возвращения." else ""), risk)) {
                    val was = before()
                    repo.pause(today, until)
                    CoachCall("undo_pause", JsonObject(was + args("setFrom" to today.toEpochDay(), "setUntil" to until.toEpochDay(), "left" to leftNow())))
                }
            }
            RESUME -> {
                if (!repo.paused(s, today)) return no("Паузы сейчас нет.")
                auto("Пауза закончена: план с сегодняшнего дня.") {
                    val was = before()
                    repo.resume(today)
                    val now = repo.store.settingsNow()
                    CoachCall("undo_resume", JsonObject(was + args("setFrom" to now.pauseFrom, "setUntil" to now.pauseUntil, "left" to leftNow())))
                }
            }
            RATE -> {
                val run = when (a.str("which")) { "last_run" -> true; "last_gym" -> false; else -> return no("Не сказано, какую тренировку: последнюю пробежку или силовую.") }
                val rpe = a.int("rpe")?.takeIf { it in 1..10 } ?: return no("Оценка — от 1 до 10.")
                val w = repo.db.workouts().allDone().filter { run == null || (it.kind == Kind.RUN) == run }.maxByOrNull { it.finishedAt ?: it.startedAt }
                    ?: return no(when (run) { true -> "Пробежек ещё нет."; false -> "Силовых ещё нет."; null -> "Тренировок ещё нет." })
                if (w.rpe == rpe) return no("Оценка уже $rpe.")
                // Said of a set now, read as a past workout: a session going on, or one days ago, waits for the person.
                val going = repo.db.workouts().active()?.takeIf { !run }?.let { repo.planOf(it)?.title ?: it.title }
                val old = today.toEpochDay() - w.epochDay > 1
                val about = when {
                    going != null -> " Это прошлая силовая, не идущая сейчас «$going» — её оценивают кнопкой «Закончить»."
                    old -> " Это тренировка не сегодня и не вчера."
                    else -> ""
                }
                either(going == null && !old, "Оценка «${w.title}» за ${Ru.date(LocalDate.ofEpochDay(w.epochDay))}: ${w.rpe ?: "—"} → $rpe.$about") {
                    repo.setRating(w.id, rpe)
                    w.rpe?.let { CoachCall("undo_rate", args("workout" to w.id, "uid" to w.uid, "rpe" to it, "expect" to rpe)) }
                }
            }
            LAPS -> {
                val day = a.str("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return no("Дата пробежки — в виде ГГГГ-ММ-ДД.") }
                val runs = repo.db.workouts().allDone().filter { it.kind == Kind.RUN && (day == null || it.epochDay == day.toEpochDay()) }
                val w = runs.maxByOrNull { it.finishedAt ?: it.startedAt } ?: return no(if (day != null) "Пробежки ${Ru.date(day)} нет." else "Пробежек ещё нет.")
                val laps = w.summaryJson?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() }?.laps.orEmpty()
                if (laps.isEmpty()) return no("В пробежке «${w.title}» нет кругов.")
                val n = a.int("lap") ?: return no("Не сказано, какой круг.")
                val l = laps.getOrNull(n - 1) ?: return no("В пробежке ${laps.size} ${Ru.plural(laps.size, "круг", "круга", "кругов")}, круга $n нет.")
                val sec = { x: Double -> "${Ru.one(x)} с" }
                val (fixed, what) = when (a.str("action")) {
                    "split" -> {
                        val parts = a.int("parts") ?: 2
                        if (parts !in 2..6) return no("Разделить можно на 2–6 кругов.")
                        forma.core.run.LapEdit.split(laps, n - 1, parts) to "круг $n (${sec(l.seconds)}) — это $parts ${Ru.plural(parts, "круг", "круга", "кругов")} по ${sec(l.seconds / parts)}."
                    }
                    "merge" -> forma.core.run.LapEdit.merge(laps, n - 1) to
                        "круги $n и ${n + 1} (${sec(l.seconds)} и ${laps.getOrNull(n)?.let { sec(it.seconds) } ?: "—"}) — один круг, ${sec(l.seconds + (laps.getOrNull(n)?.seconds ?: 0.0))}."
                    "remove" -> forma.core.run.LapEdit.remove(laps, n - 1) to "круг $n (${sec(l.seconds)}) — не круг, убрать."
                    "time" -> {
                        val t = a.num("seconds") ?: return no("Не сказано время круга.")
                        forma.core.run.LapEdit.setTime(laps, n - 1, t) to "круг $n: ${sec(l.seconds)} → ${sec(t)}."
                    }
                    else -> return no("Непонятно, что сделать с кругом: разделить, склеить, убрать или поставить время.")
                }
                if (fixed == laps) return no(when (a.str("action")) {
                    "split" -> if (l.rep != null) "В отрезке уже столько кругов, сколько по плану (или это половина круга на старте): делить нечего." else "Этот круг не разделить."
                    "merge" -> "Склеить можно только с соседним кругом того же отрезка (или оба вне отрезков)."
                    "remove" -> "Круг отрезка не убрать — его время пропадёт. Можно склеить с соседним или поставить время."
                    else -> "Время круга — от 5 секунд до часа."
                })
                val reps = laps.any { it.rep != null } && fixed.filter { it.rep != null } != laps.filter { it.rep != null }
                confirm("Пробежка «${w.title}» ${Ru.date(LocalDate.ofEpochDay(w.epochDay))}: $what" +
                    if (reps) " Если после неё ещё не было пробежек, она решится заново по этим кругам." else "") {
                    repo.editRunLaps(w.id, fixed)
                    CoachCall("undo_laps", args("workout" to w.id, "uid" to w.uid, "laps" to lapsJson(laps), "expect" to lapsJson(fixed)))
                }
            }
            EASY -> {
                val min = a.int("minutes") ?: return no("Не сказано, сколько минут.")
                val program = repo.store.programNow() ?: return no("Нет плана.")
                val st = repo.store.runStateNow()
                if (!st.canRunContinuously) return no("Пока бег чередуется с ходьбой: длину ведёт лестница приложения.")
                val middle = repo.store.profileNow()?.runGoal?.middleDistance == true
                val cap = ProgramGenerator.runCaps(program, middle).easyMax
                // Today's run counts (the next easy run is after it), as the plan counts it and as the summary says.
                val longest = st.longestRecent(today.toEpochDay() + 1)
                // The week's growth counts from before the coach's first change this week: calls in a row do not add up.
                val earlier = recent("undo_easy")
                val base = earlier.minByOrNull { it.at }?.undo?.args?.int("minutes") ?: st.easyMinutes
                // Cuts too, over two weeks: a cut a week, each small, would halve the running with nobody asked.
                val before = recent("undo_easy", 14).minByOrNull { it.at }?.undo?.args?.int("minutes") ?: st.easyMinutes
                val up = maxOf(base + 5, (base * 1.15).toInt())
                val limit = listOfNotNull(cap, longest?.let { (it * 1.10).toInt().coerceAtLeast(st.easyMinutes) }, up).min()
                when {
                    min == st.easyMinutes -> return no("Лёгкий бег и так $min мин.")
                    min < 15 -> return no("Лёгкий бег — не короче 15 минут.")
                    min > limit -> return no("Сейчас можно до $limit мин: " + when (limit) {
                        cap -> "это потолок лёгкого бега под твою цель."
                        up -> "больше чем на 15 % за неделю не прибавляем — так меньше риск травм."
                        else -> "не длиннее 110 % самой длинной пробежки за месяц."
                    })
                }
                // What «Сегодня» will start next: the plan's caps (the week's +10 %, a day with the gym) may hold the next easy
                // run below the new length, and the card says so instead of promising it.
                val next = nextEasy(repo, today, min)?.takeIf { it.why != null }
                // A second change in a week, or a run shorter by more than a tenth than two weeks ago, waits for the person.
                either(earlier.isEmpty() && (min > st.easyMinutes || min >= before * EASY_CUT_AUTO), "Лёгкий бег: ${st.easyMinutes} → $min мин." +
                    (next?.let { " ${dayWords(it.day, today)} будет ${it.minutes} мин: ${it.why}." } ?: "") + " Дальше длина меняется по правилам приложения.") {
                    val now = repo.store.runStateNow()
                    repo.store.setRunState(now.copy(easyMinutes = min))
                    repo.changed()
                    CoachCall("undo_easy", args("minutes" to now.easyMinutes, "expect" to min))
                }
            }
            STRIDES -> {
                // Run/walk has neither strides nor a long run: a change would be "made" and do nothing until it ends.
                if (!repo.store.runStateNow().canRunContinuously) return no("Пока бег чередуется с ходьбой: ускорений нет, их добавит лестница приложения.")
                // Up to two more than the plan gives (4 while running is new, 6 later).
                val most = if (repo.store.runStateNow().runs < 8) 6 else 8
                val n = a.int("count")?.takeIf { it in 0..most } ?: return no("Ускорений — от 0 до $most.")
                val w = a.int("weeks")?.takeIf { it in 1..MAX_WEEKS } ?: return no("Срок — от 1 до $MAX_WEEKS недель.")
                val until = today.plusWeeks(w.toLong()).minusDays(1)
                auto((if (n == 0) "Без ускорений" else "$n ${Ru.plural(n, "ускорение", "ускорения", "ускорений")} после лёгкого бега") +
                    " на ${weeks(w)}, до ${Ru.date(until)}, в дни, когда они есть в плане.") {
                    val old = repo.store.settingsNow().coachStrides
                    val term = CoachTerm(n, until.toEpochDay())
                    repo.updateSettings { it.copy(coachStrides = term) }
                    CoachCall("undo_strides", args("old" to old?.let { AppJson.encodeToJsonElement(CoachTerm.serializer(), it) }, "expect" to AppJson.encodeToJsonElement(CoachTerm.serializer(), term)))
                }
            }
            TEMPO -> {
                val st = repo.store.runStateNow()
                if (!st.canRunContinuously) return no("Пока бег чередуется с ходьбой: темповых ещё нет, их добавит лестница приложения.")
                val now = forma.core.run.RunPlan.tempoNow(st)
                val step = forma.core.run.RunPlan.tempoNow(st.copy(tempoOwn = null))
                val reset = a["reset"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content?.lowercase() } in setOf("true", "да")
                if (reset) {
                    if (st.tempoOwn == null) return no("Темповый и так по плану приложения: ${tempoText(now)}.")
                    return confirm("Темповый снова по плану приложения: ${tempoText(now)} → ${tempoText(step)}.") { setTempo(repo, null) }
                }
                val reps = a.int("reps") ?: return no("Не сказано, сколько отрезков (целое число).")
                val work = a.num("work_min") ?: return no("Не сказано, сколько минут в отрезке.")
                if (reps !in 1..10) return no("Отрезков — от 1 до 10.")
                if (work < 1.5 - 1e-9 || work > 20 + 1e-9) return no("Отрезок — от 1,5 до 20 минут.")
                val rest = if (reps == 1) 0.0 else a.num("rest_min") ?: return no("Не сказан отдых между отрезками.")
                if (reps > 1 && (rest < 0.5 - 1e-9 || rest > 3 + 1e-9)) return no("Отдых между отрезками — от 0,5 до 3 минут.")
                val shape = forma.core.run.TempoShape(reps, (work * 2).roundToInt() * 30, (rest * 2).roundToInt() * 30)
                // No more at tempo than the plan's step or the run now, by more than 15 %: the minutes at threshold are the load.
                val most = (maxOf(now.total, step.total) * 1.15).toInt()
                if (shape.total < 6 * 60) return no("Всего в темпе меньше 6 минут — это уже не темповый.")
                if (shape.total > most) return no("Всего в темпе ${mins(shape.total)} мин, а можно до ${mins(most / 30 * 30)}: сейчас по плану ${mins(maxOf(now.total, step.total))} мин в темпе, больше чем на 15 % сразу не прибавляем.")
                if (shape == now) return no("Темповый и так ${tempoText(now)}.")
                confirm("Темповый: ${tempoText(now)} → ${tempoText(shape)}. Темп отрезков даёт приложение. " +
                    (if (shape == step) "Это шаг плана." else "Получится хорошо — приложение само поведёт к шагу плана (${tempoText(step)}); слишком тяжело — сделает отрезки короче.")) { setTempo(repo, shape) }
            }
            TRIAL -> {
                val st = repo.store.runStateNow()
                if (!st.canRunContinuously) return no("Пока бег чередуется с ходьбой: контрольного нет, его время придёт после 30 минут бега без остановки.")
                when (a.str("when")) {
                    "next" -> {
                        // What the plan shows ([TrainingRepository.trialDay]), not the flag: a trial due by time has none,
                        // and one asked for waits two weeks after a try (the audit of 24 November).
                        repo.trialDay(today)?.let { return no("Контрольный и так стоит: ${ON_DAY[it.dayOfWeek.value - 1]}, ${Ru.date(it)}.") }
                        if (st.testSoon && st.noTrialUntil == null) return no("Контрольный уже поставлен, но пока его не будет: ${repo.trialWait(today)}.")
                        if (repo.store.programNow()?.week?.days?.none { d -> d.sessions.any { it.kind == SessionKind.RUN_QUALITY } } != false) {
                            return no("В неделе нет тяжёлого бега: контрольный ставится на его день. Сначала добавь тяжёлую пробежку (set_week).")
                        }
                        auto("Контрольный забег — в следующий тяжёлый день со свежими ногами (не раньше чем через две недели после прошлой попытки). Темп — по твоей нынешней форме, не по цели.") {
                            val now = repo.store.runStateNow()
                            repo.store.setRunState(now.copy(testSoon = true, noTrialUntil = null))
                            repo.changed()
                            CoachCall("undo_trial", args("soon" to now.testSoon, "until" to now.noTrialUntil, "expect_soon" to true))
                        }
                    }
                    "skip" -> {
                        val until = today.plusWeeks(4)
                        if (!st.testSoon && st.noTrialUntil?.let { it >= until.toEpochDay() - 7 } == true) return no("Контрольный и так отложен до ${Ru.date(LocalDate.ofEpochDay(st.noTrialUntil!!))}.")
                        auto("Контрольного не будет до ${Ru.date(until)}: в тяжёлые дни — обычные тренировки. Форму приложение оценивает по тому, как идут пробежки.") {
                            val now = repo.store.runStateNow()
                            repo.store.setRunState(now.copy(testSoon = false, noTrialUntil = until.toEpochDay()))
                            repo.changed()
                            CoachCall("undo_trial", args("soon" to now.testSoon, "until" to now.noTrialUntil, "expect_soon" to false, "expect_until" to until.toEpochDay()))
                        }
                    }
                    else -> return no("Не сказано: поставить контрольный (next) или отложить (skip).")
                }
            }
            RUN_PACE -> {
                val up = when (a.str("change")) { "easier" -> false; "harder" -> true; else -> return no("Не сказано: спокойнее (easier) или быстрее (harder).") }
                val steps = (a["steps"]?.let { a.int("steps") ?: return no("Шагов — 1 или 2.") } ?: 1).takeIf { it in 1..2 } ?: return no("Шагов — 1 или 2.")
                val goal = repo.store.profileNow()?.runGoal
                val st = forma.core.run.RunPlan.estimated(repo.store.runStateNow(), goal)
                val from = st.fitness ?: return no("Темпов пока нет: они появятся после первых пробежек по плану" + (if (goal == null) " или с целью по бегу." else "."))
                val top = goal?.let { forma.core.run.Vdot.fromRace(it.meters.toDouble(), it.seconds.toDouble()) } ?: forma.core.run.RunPlan.MAX_VDOT
                val to = (from + if (up) steps else -steps).coerceIn(forma.core.run.RunPlan.MIN_VDOT, maxOf(forma.core.run.RunPlan.MIN_VDOT, top)).let { (it * 2).roundToInt() / 2.0 }
                if (to == from) return no(if (up) "Быстрее некуда: темпы уже по цели." else "Спокойнее некуда.")
                // A week between changes made faster: one good day is no reason, and each step is felt in every run.
                if (up && recent("undo_run_pace").isNotEmpty()) return no("Темпы уже меняли на этой неделе. Быстрее — не чаще раза в неделю.")
                val a0 = forma.core.run.PaceTable.forVdot(from)
                val a1 = forma.core.run.PaceTable.forVdot(to)
                val text = (if (up) "Темпы бега быстрее" else "Темпы бега спокойнее") +
                    ": лёгкий ${Ru.pace(a0.easy.fast.toDouble())}–${Ru.pace(a0.easy.slow.toDouble())} → ${Ru.pace(a1.easy.fast.toDouble())}–${Ru.pace(a1.easy.slow.toDouble())} /км, " +
                    "темповый ${Ru.pace(a0.threshold.toDouble())} → ${Ru.pace(a1.threshold.toDouble())} /км. Так же сдвинутся интервалы и отрезки. " +
                    "Дальше темпы сами подстраиваются по тому, как идут пробежки."
                either(!up, text) {
                    val now = forma.core.run.RunPlan.estimated(repo.runStateToGo(goal), goal)
                    // Easier: the race-pace reps too, by the same steps (each about 2.5 % on the pace held in them) — those
                    // held in longer reps as well.
                    val ease = 1 + forma.core.run.RunPlan.STEP_AHEAD * steps
                    val rep = now.goalRepPace?.let { if (up) it else it * ease }
                    val reps = if (up) now.goalRepPaces else now.goalRepPaces.mapValues { (_, p) -> p * ease }
                    val next = (if (now.estimated) now.copy(estVdot = to) else now.copy(vdot = to)).copy(goalRepPace = rep, goalRepPaces = reps)
                    repo.store.setRunState(next)
                    repo.changed()
                    CoachCall(
                        "undo_run_pace",
                        args(
                            "est" to now.estimated, "old" to from, "expect" to to, "rep" to now.goalRepPace,
                            "reps" to JsonObject(now.goalRepPaces.entries.associate { (k, p) -> k.toString() to JsonPrimitive(p) }),
                        ),
                    )
                }
            }
            RUN_STEP -> {
                val kind = a.str("kind")
                val up = when (a.str("change")) { "easier" -> false; "harder" -> true; else -> return no("Не сказано: легче (easier) или тяжелее (harder).") }
                val profile = repo.store.profileNow()
                val goal = profile?.runGoal
                val lap = profile?.lapMeters
                val st = forma.core.run.RunPlan.estimated(repo.store.runStateNow(), goal)
                if (!st.canRunContinuously) return no("Пока бег чередуется с ходьбой: тяжёлых пробежек ещё нет.")
                fun onLap(w: forma.core.run.RunWorkout) = if (lap != null) forma.core.run.RunPlan.onTrack(w, lap) else w
                val (cur, size, title) = when (kind) {
                    "race_pace" -> {
                        if (goal?.middleDistance != true) return no("Отрезки в темпе цели есть только с целью до 3 км.")
                        Triple(st.goalStage, forma.core.run.RunPlan.goalLadderSize) { i: Int -> forma.core.run.RunPlan.goalPace(st.copy(goalStage = i), goal, lap).title }
                    }
                    "intervals" -> Triple(st.intervalStage, forma.core.run.RunPlan.intervalLadderSize) { i: Int -> onLap(forma.core.run.RunPlan.intervals(st.copy(intervalStage = i))).title }
                    "tempo" -> {
                        if (st.tempoOwn != null) return no("Темповый сейчас свой (${tempoText(st.tempoOwn!!)}): он меняется через set_tempo.")
                        Triple(st.tempoStage, forma.core.run.RunPlan.tempoLadderSize) { i: Int -> onLap(forma.core.run.RunPlan.tempo(st.copy(tempoStage = i))).title }
                    }
                    else -> return no("Какая пробежка: race_pace, intervals или tempo.")
                }
                val next = cur + if (up) 1 else -1
                if (next < 0) return no("Это и так первая ступень. Если тяжело — сделай темпы спокойнее (set_run_pace easier)" + (if (kind == "tempo") " или короче отрезки (set_tempo)." else "."))
                if (next >= size) return no("Это и так последняя ступень.")
                either(!up, "«${title(cur)}» → «${title(next)}». Дальше ступени снова меняются по тому, как идут пробежки.") {
                    val now = repo.store.runStateNow()
                    repo.store.setRunState(
                        when (kind) {
                            "race_pace" -> now.copy(goalStage = next, goalFails = 0)
                            "intervals" -> now.copy(intervalStage = next, intervalFails = 0)
                            else -> now.copy(tempoStage = next, tempoFails = 0)
                        },
                    )
                    repo.changed()
                    CoachCall("undo_run_step", args("kind" to kind, "old" to cur, "expect" to next))
                }
            }
            WISH -> {
                val text = a.str("text")?.take(NOTE_LEN) ?: return no("Не сказано, что записать.")
                if (s.coachWishes.any { norm(it.substringAfter(": ")) == norm(text) }) return no("Это пожелание уже записано.")
                val line = "$today: $text"
                auto("Записал пожелание для разработчика: «$text».") {
                    repo.updateSettings { it.copy(coachWishes = (it.coachWishes + line).takeLast(MAX_WISHES)) }
                    CoachCall("undo_wish", args("text" to line))
                }
            }
            APP -> {
                val key = a.str("key")?.lowercase() ?: return no("Не сказано, какая настройка.")
                val value = a.str("value")?.lowercase() ?: return no("Не сказано значение.")
                if (key == "look") {
                    val look = forma.app.ui.theme.Looks.all.firstOrNull { it.id == value || norm(it.name) == norm(value) }
                        ?: return no("Оформление — одно из: ${forma.app.ui.theme.Looks.all.joinToString(", ") { it.id }}.")
                    if (s.look == look.id) return no("Оформление и так «${look.name}».")
                    return auto("Оформление: «${look.name}».") {
                        val old = repo.store.settingsNow().look
                        repo.updateSettings { it.copy(look = look.id) }
                        CoachCall("undo_app", args("key" to "look", "old" to old, "expect" to look.id))
                    }
                }
                val name = APP_FLAGS[key] ?: return no("Такой настройки нет. Есть: ${APP_FLAGS.keys.joinToString(", ")}, look.")
                val on = when (value) { "on", "true", "да", "вкл" -> true; "off", "false", "нет", "выкл" -> false; else -> return no("Значение — on или off.") }
                if (flag(s, key) == on) return no("«$name» и так ${if (on) "включено" else "выключено"}.")
                auto("$name: ${if (on) "включено" else "выключено"}.") {
                    repo.updateSettings { setFlag(it, key, on) }
                    CoachCall("undo_app", args("key" to key, "old" to (!on).toString(), "expect" to on.toString()))
                }
            }
            BODY_WEIGHT -> {
                val kg = a.num("kg")?.takeIf { it in 30.0..250.0 } ?: return no("Вес тела — от 30 до 250 кг.")
                val old = repo.store.profileNow()?.bodyWeightKg ?: return no("Нет анкеты.")
                val w = (kg * 10).roundToInt() / 10.0
                // A typo (83 for 73) would move every body-weight exercise: a change of more than a tenth waits for the person.
                either(abs(w - old) <= old * 0.10, "Вес тела сегодня: ${Ru.kg(w)} кг" + (if (abs(w - old) > 1e-9) " (было ${Ru.kg(old)})." else ".")) {
                    // The day's own weigh-in, to put back on that day (none: taken away): «Вернуть» on a later day kept the
                    // coach's entry and added the old weight as a weigh-in of the day of the undo (the audit of 24 November).
                    val day = repo.today().toEpochDay()
                    val was = repo.db.state().bodyWeights().firstOrNull { it.epochDay == day }?.kg
                    repo.logBodyWeight(w)
                    CoachCall("undo_body", args("old" to old, "expect" to w, "day" to day, "was" to was))
                }
            }
            OPEN -> {
                val key = a.str("screen")?.lowercase() ?: return no("Не сказано, какой экран.")
                val name = SCREENS[key] ?: return no("Такого экрана нет. Есть: ${SCREENS.keys.joinToString(", ")}.")
                auto("«$name»") { null }
            }
            LIGHTER -> {
                val program = repo.store.programNow() ?: return no("Нет плана.")
                val state = repo.store.liftStateNow()
                val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                fun reactiveOn(m: LocalDate) = state.reactiveDeloadStart == m.toEpochDay()
                fun askedOn(m: LocalDate) = state.askedDeloadStart == m.toEpochDay()
                fun lighter(p: forma.core.plan.Program, m: LocalDate) = ProgramGenerator.isDeloadWeek(p, m) || reactiveOn(m) || askedOn(m)
                fun week(m: LocalDate) = "${Ru.date(m)} – ${Ru.date(m.plusDays(6))}"
                fun nextLighter(p: forma.core.plan.Program, from: LocalDate) = (0..12).map { from.plusWeeks(it.toLong()) }.firstOrNull { ProgramGenerator.isDeloadWeek(p, it) }
                // A novice has no blocks: no new block to speak of after the lighter week, and none starting again.
                val blocks = program.blockWeeks > 0
                // [asked]: the week asked for, kept as asked ([LiftState.askedDeloadStart]) — the app does not move it on as
                // unearned, and without blocks the gym is lighter that week by it; null on skip.
                val (next, text, asked) = when (a.str("when")) {
                    "this", "next" -> {
                        val m = if (a.str("when") == "this") monday else monday.plusWeeks(1)
                        if (lighter(program, m)) return no((if (m == monday) "Эта неделя" else "Следующая неделя") + " и так лёгкая.")
                        val p = ProgramGenerator.lighterWeekAt(program, m)
                        Triple(p, "Лёгкая неделя — ${week(m)}: в зале вдвое меньше подходов и вес на 10 % легче, бег короче." + (
                            if (!blocks) "" else " Потом начнётся новый блок" + (nextLighter(p, m.plusWeeks(1))?.let { ", следующая лёгкая — ${week(it)}." } ?: ".")
                            ), m)
                    }
                    "skip" -> {
                        if (!lighter(program, monday)) return no("Эта неделя и так не лёгкая" + (nextLighter(program, monday)?.let { ": лёгкая по плану — ${week(it)}." } ?: "."))
                        // The block starts again with this week: the lighter week comes at its end.
                        val p = program.copy(startEpochDay = monday.toEpochDay())
                        Triple(p, "Эта неделя — обычная, без разгрузки." + (
                            if (!blocks) "" else " Блок начинается заново" + (nextLighter(p, monday)?.let { ": лёгкая неделя — ${week(it)}." } ?: ".")
                            ), null)
                    }
                    else -> return no("Не сказано: this, next или skip.")
                }
                confirm(text) {
                    val p0 = repo.store.programNow()!!
                    val l0 = repo.store.liftStateNow()
                    repo.store.setProgram(p0.copy(startEpochDay = next.startEpochDay))
                    // Asked for the week the app had moved its lighter week from (the block mostly missed): it is lighter after
                    // all, nothing «сдвинулась» from it. Skipped: neither the app's own lighter week of this week nor one asked
                    // for stays.
                    val l1 = if (asked != null) l0.copy(
                        askedDeloadStart = asked.toEpochDay(), deloadMovedFrom = l0.deloadMovedFrom.takeIf { it != asked.toEpochDay() },
                    ) else l0.copy(
                        reactiveDeloadStart = l0.reactiveDeloadStart.takeIf { it != monday.toEpochDay() },
                        askedDeloadStart = l0.askedDeloadStart.takeIf { it != monday.toEpochDay() },
                    )
                    if (l1 != l0) repo.store.setLiftState(l1)
                    repo.changed()
                    // "asked" and "moved" always there (null too): «Вернуть» puts them back as they were, also when there was none.
                    CoachCall(
                        "undo_lighter",
                        args(
                            "start" to p0.startEpochDay, "reactive" to l0.reactiveDeloadStart, "expect" to next.startEpochDay,
                            "asked" to (l0.askedDeloadStart?.let { JsonPrimitive(it) } ?: JsonNull),
                            "moved" to (l0.deloadMovedFrom?.let { JsonPrimitive(it) } ?: JsonNull),
                        ),
                    )
                }
            }
            ORDER -> {
                val q = a.str("exercise") ?: return no("Не сказано, какое упражнение.")
                val place = a.str("place")?.lowercase() ?: return no("Не сказано, на какое место.")
                val found = find(places(repo), q, a.str("session")).distinctBy { it.templateId to it.planId }
                if (found.isEmpty()) return no("«$q» нет в плане.")
                if (found.map { it.templateId }.distinct().size > 1) return no("«${name(found.first().exerciseId)}» есть в нескольких тренировках (${found.joinToString(", ") { it.title }}): скажи, в какой (session).")
                val at = found.first()
                val now = repo.liftPlan(at.templateId, today).items.map { it.planId ?: it.exerciseId }
                val own = s.order[at.templateId]
                if (place == "reset") {
                    if (own == null) return no("В «${at.title}» и так порядок приложения.")
                    return confirm("«${at.title}»: порядок упражнений снова как ставит приложение (главные первыми).") {
                        val old = repo.store.settingsNow().order[at.templateId]
                        repo.updateSettings { it.copy(order = it.order - at.templateId) }
                        CoachCall("undo_order", args("template" to at.templateId, "old" to old?.let { l -> JsonArray(l.map { JsonPrimitive(it) }) }))
                    }
                }
                val from = now.indexOf(at.planId).takeIf { it >= 0 } ?: return no("«${name(at.exerciseId)}» сейчас нет в тренировке «${at.title}».")
                val to = when (place) {
                    "first" -> 0
                    "last" -> now.lastIndex
                    else -> place.toIntOrNull()?.takeIf { it in 1..now.size }?.minus(1) ?: return no("Место — first, last или номер от 1 до ${now.size}.")
                }
                if (to == from) return no("«${name(at.exerciseId)}» и так на этом месте.")
                val order = now.toMutableList().also { it.add(to, it.removeAt(from)) }
                val names = order.map { id -> places(repo).firstOrNull { it.templateId == at.templateId && it.planId == id }?.let { name(it.exerciseId) } ?: name(id) }
                confirm("«${at.title}»: «${name(at.exerciseId)}» — ${if (to == 0) "первым" else if (to == now.lastIndex) "последним" else "на место ${to + 1}"}. Порядок: " +
                    names.mapIndexed { i, n -> "${i + 1}) $n" }.joinToString(", ") + ". Разминка подстроится под новый порядок.") {
                    val old = repo.store.settingsNow().order[at.templateId]
                    repo.updateSettings { it.copy(order = it.order + (at.templateId to order)) }
                    CoachCall("undo_order", args(
                        "template" to at.templateId, "old" to old?.let { l -> JsonArray(l.map { JsonPrimitive(it) }) }, "expect" to JsonArray(order.map { JsonPrimitive(it) }),
                    ))
                }
            }
            EASE_DAY -> {
                val day = a.str("day")?.let { weekDays(it) }?.singleOrNull() ?: return no("День недели — один: пн, вт, ср, чт, пт, сб или вс.")
                val w = a.int("weeks")?.takeIf { it in 0..MAX_WEEKS } ?: return no("Срок — от 0 до $MAX_WEEKS недель.")
                val program = repo.store.programNow() ?: return no("Нет плана.")
                val dayName = listOf("понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье")[day - 1]
                if (program.week.on(day).none { it.kind == SessionKind.RUN_QUALITY }) return no("В $dayName тяжёлого бега по плану нет.")
                val was = s.easyRunDays[day]?.takeIf { it >= today.toEpochDay() }
                if (w == 0 && was == null) return no("В $dayName тяжёлый бег и так по плану.")
                val until = today.plusWeeks(w.toLong()).minusDays(1)
                // Two weeks or less of one hard day is made at once (sore shins: an easy week or two). Longer, or no hard run
                // left in the week with a running goal, waits for «Принять» with the cost said: a change that costs a goal
                // waits (27 October), and two calls of 8 weeks took all of Denis's hard running unasked.
                val goal = repo.store.profileNow()?.runGoal?.let { "${it.meters} м за ${Ru.clock(it.seconds)}" }
                val hardLeft = program.week.days.any { d ->
                    d.day != day && d.sessions.any { it.kind == SessionKind.RUN_QUALITY } && s.easyRunDays[d.day]?.let { it >= today.toEpochDay() } != true
                }
                val risk = when {
                    w == 0 -> null
                    goal != null && !hardLeft -> "В неделе не останется тяжёлого бега, к $goal не приблизиться."
                    w > 2 -> "$w ${Ru.plural(w, "неделя", "недели", "недель")} в этот день без тяжёлого бега, " +
                        (goal?.let { "к $it медленнее." } ?: "форма бега будет расти медленнее.")
                    else -> null
                }
                either(risk == null, withRisk(if (w == 0) "В $dayName снова тяжёлый бег по плану." else "В $dayName вместо тяжёлого бега — лёгкий, на ${weeks(w)}, до ${Ru.date(until)}. Потом тяжёлый вернётся сам.", risk)) {
                    val old = repo.store.settingsNow().easyRunDays[day]
                    repo.updateSettings { if (w == 0) it.copy(easyRunDays = it.easyRunDays - day) else it.copy(easyRunDays = it.easyRunDays + (day to until.toEpochDay())) }
                    CoachCall("undo_ease_day", args("day" to day, "old" to old, "expect" to if (w == 0) null else until.toEpochDay()))
                }
            }
            RUN_CUSTOM -> {
                val pace = forma.core.run.CustomRun.Pace.entries.firstOrNull { it.name.lowercase() == a.str("pace")?.lowercase() }
                    ?: return no("Темп — easy, tempo, interval, race, fast или free.")
                val reps = a.int("reps")?.takeIf { it in 1..20 } ?: return no("Отрезков — от 1 до 20.")
                val sec = a["work_sec"]?.let { a.int("work_sec")?.takeIf { v -> v in 20..3600 } ?: return no("Отрезок — от 20 секунд до часа.") }
                val meters = a["work_m"]?.let { a.int("work_m")?.takeIf { v -> v in 100..10000 } ?: return no("Отрезок — от 100 м до 10 км.") }
                if (sec == null && meters == null) return no("Не сказана длина отрезка: секунды (work_sec) или метры (work_m).")
                val easy = pace == forma.core.run.CustomRun.Pace.EASY
                val rest = a["rest_sec"]?.let { a.int("rest_sec")?.takeIf { v -> v in 0..600 } ?: return no("Отдых — от 0 до 600 секунд.") } ?: if (reps > 1) 90 else 0
                val warm = a["warmup_min"]?.let { a.int("warmup_min")?.takeIf { v -> v in 0..20 } ?: return no("Разминка — от 0 до 20 минут.") } ?: if (easy && reps == 1) 0 else 10
                val cool = a["cooldown_min"]?.let { a.int("cooldown_min")?.takeIf { v -> v in 0..15 } ?: return no("Заминка — от 0 до 15 минут.") } ?: if (easy && reps == 1) 0 else 5
                val profile = repo.store.profileNow()
                val goal = profile?.runGoal
                val program = repo.store.programNow() ?: return no("Нет плана.")
                val st = forma.core.run.RunPlan.current(repo.store.runStateNow(), goal, today.toEpochDay())
                if (!st.canRunContinuously) return no("Пока бег чередуется с ходьбой: пробежки ведёт лестница приложения.")
                if (repo.paused(s, today)) return no("Сейчас пауза в плане.")
                if (s.feelOn(today) == forma.app.data.Feel.SICK) return no("Сегодня отмечено «Болею»: бегать не нужно.")
                if (repo.db.workouts().allDone().any { it.kind == Kind.RUN && it.epochDay == today.toEpochDay() }) return no("Сегодня пробежка уже сделана.")
                val spec = forma.core.run.CustomRun(reps, if (meters != null) null else sec, meters, pace, rest, warm, cool)
                val each = forma.core.run.RunPlan.customRepSeconds(st, goal, spec)
                    ?: return no("Темпа пока нет, поэтому метры не перевести во время: задай отрезок в секундах (work_sec).")
                val work = reps * each
                val planned = program.week.on(today.dayOfWeek.value)
                val hard = pace.hard && !(pace == forma.core.run.CustomRun.Pace.FREE && reps == 1)
                if (hard) {
                    // The minutes of hard running in one session: what the plan's own hardest sessions hold, no more.
                    val most = when (pace) {
                        forma.core.run.CustomRun.Pace.TEMPO -> 25 * 60
                        forma.core.run.CustomRun.Pace.INTERVAL, forma.core.run.CustomRun.Pace.FREE -> 16 * 60
                        else -> 10 * 60
                    }
                    if (work > most) return no("В таком темпе за одну пробежку — не больше ${most / 60} минут работы, а здесь ${(work + 30) / 60}. Сделай меньше отрезков или короче.")
                    if (st.returnEasy > 0 || forma.core.run.RunPlan.breakDays(repo.store.runStateNow(), today.toEpochDay()) != null) return no("После перерыва первые пробежки — лёгкие: тяжёлую пока не ставим.")
                    if (repo.easyAfterSick(today) > 0) return no("После болезни первые пробежки — лёгкие.")
                    if (s.feelOn(today) == forma.app.data.Feel.LOW) return no("Сегодня отмечено «Так себе»: бег только лёгкий.")
                    if (planned.any { it.kind == SessionKind.LIFT && program.templates.firstOrNull { t -> t.id == it.templateId }?.legs == true }) {
                        return no("Сегодня в зале ноги: тяжёлый бег в такой день не ставим.")
                    }
                    val own = planned.any { it.kind == SessionKind.RUN_QUALITY }
                    fun hardOn(d: LocalDate) = program.week.on(d.dayOfWeek.value).any { it.kind == SessionKind.RUN_QUALITY } && repo.easedUntil(s, d) == null && !repo.paused(s, d)
                    val doneHard = repo.db.workouts().allDone().any { w -> w.kind == Kind.RUN && w.epochDay == today.toEpochDay() - 1 && (w.rpe ?: 0) >= 7 }
                    if (!own && (hardOn(today.plusDays(1)) || hardOn(today.minusDays(1)) || doneHard)) {
                        return no("Рядом (вчера или завтра) уже стоит тяжёлый бег: два тяжёлых дня подряд не ставим. Могу поставить лёгкую пробежку (pace easy) или эту — в день тяжёлого бега по плану.")
                    }
                } else {
                    val cap = ProgramGenerator.runCaps(program, goal?.middleDistance == true).easyMax
                    val longest = st.longestRecent(today.toEpochDay() + 1)
                    val limit = listOfNotNull(cap, longest?.let { (it * 1.10).toInt().coerceAtLeast(st.easyMinutes) }, 60).min()
                    if (work > limit * 60) return no("Лёгкий бег сегодня — до $limit мин: " + if (limit == cap) "это потолок под твою цель." else "не длиннее 110 % самой длинной пробежки за месяц.")
                    if (work < 10 * 60) return no("Лёгкий бег — не короче 10 минут.")
                }
                val lap = profile?.lapMeters
                // A hard one no longer as a whole than one run may be, as the plan's hard sessions (MD-3, approved on 25
                // November): the cool-down shorter first, then the warm-up, then fewer reps (2 at least) — or refused.
                val capText = forma.core.run.RunPlan.singleRunCap(st, today.toEpochDay())?.let { forma.core.run.RunPlan.capWords(st, today.toEpochDay(), it) }
                val use = if (!hard) spec else forma.core.run.RunPlan.customWithinCap(st, goal, spec, today.toEpochDay(), lap)
                    ?: return no(
                        "Эта не входит даже с 2 отрезками и короткой разминкой" + (capText?.let { ": $it" } ?: "") + ". " +
                            "Могу поставить лёгкую пробежку (pace easy).",
                    )
                val w = forma.core.run.RunPlan.custom(st, goal, use) ?: return no("Не получилось собрать пробежку.")
                val shown = if (lap != null) forma.core.run.RunPlan.onTrack(w, lap) else w
                val instead = planned.firstOrNull { it.isRun }?.let { repo.runFor(it.kind, today).title }
                val paceText = shown.segments.firstOrNull { it.kind == forma.core.run.SegmentKind.WORK || it.kind == forma.core.run.SegmentKind.RUN }?.let { seg ->
                    seg.lapSec?.let { "круг за ${Ru.lap(it)}" } ?: seg.pace?.let { "${Ru.pace(it.fast.toDouble())}–${Ru.pace(it.slow.toDouble())} /км" }
                }
                val warmUsed = use.warmupMin
                val coolUsed = use.cooldownMin
                val text = "Пробежка на сегодня: «${shown.title.substringAfter(" · ")}»" +
                    (if (warmUsed > 0 || coolUsed > 0) " (разминка $warmUsed мин, заминка $coolUsed мин" + (if (use.reps > 1 && rest > 0) ", отдых ${Ru.clock(rest)}" else "") + ")" else "") +
                    ", ${pace.ru}" + (paceText?.let { ": $it" } ?: "") + ". " +
                    (if (use != spec && capText != null) "$capText, поэтому она короче. " else "") +
                    (instead?.let { "Вместо «$it» по плану. " } ?: "Вне плана. ") + "Шаги плана она не двигает. На экране «Сегодня» — «Начать»."
                either(!hard, text) {
                    val old = repo.store.settingsNow().coachRun
                    val run = CoachRun(today.toEpochDay(), w)
                    repo.updateSettings { it.copy(coachRun = run) }
                    CoachCall("undo_run_custom", args(
                        "old" to old?.let { AppJson.encodeToJsonElement(CoachRun.serializer(), it) }, "expect" to AppJson.encodeToJsonElement(CoachRun.serializer(), run),
                    ))
                }
            }
            RUN_TODAY -> {
                val min = a["minutes"]?.let { a.int("minutes") ?: return no("Минуты — целое число.") }
                val program = repo.store.programNow() ?: return no("Нет плана.")
                val st = repo.store.runStateNow()
                if (!st.canRunContinuously) return no("Пока бег чередуется с ходьбой: пробежки ведёт лестница приложения.")
                if (repo.paused(s, today)) return no("Сейчас пауза в плане.")
                if (s.feelOn(today) == forma.app.data.Feel.SICK) return no("Сегодня отмечено «Болею»: бегать не нужно.")
                if (program.week.on(today.dayOfWeek.value).any { it.isRun }) return no("Сегодня по плану уже есть пробежка — она на экране «Сегодня».")
                if (repo.db.workouts().allDone().any { it.kind == Kind.RUN && it.epochDay == today.toEpochDay() }) return no("Сегодня пробежка уже сделана.")
                if (s.coachRunToday?.until == today.toEpochDay() && (s.coachRunToday?.value ?: 0) == (min ?: 0)) return no("Эта пробежка на сегодня уже стоит.")
                val middle = repo.store.profileNow()?.runGoal?.middleDistance == true
                val cap = ProgramGenerator.runCaps(program, middle).easyMax
                val longest = st.longestRecent(today.toEpochDay() + 1)
                val limit = listOfNotNull(cap, longest?.let { (it * 1.10).toInt().coerceAtLeast(st.easyMinutes) }, 60).min()
                if (min != null && min < 15) return no("Пробежка — не короче 15 минут.")
                if (min != null && min > limit) return no("Сегодня можно до $limit мин: " + if (limit == cap) "это потолок лёгкого бега под твою цель." else "не длиннее 110 % самой длинной пробежки за месяц.")
                // The run «Сегодня» will start (its card asks [TrainingRepository.runFor] with these minutes): the week's +10 %
                // and a day with the gym cut it, and the card said the minutes asked (the audit of 24 November).
                val real = easyOn(repo, today, min)
                auto("На сегодня лёгкий бег ${real?.minutes ?: min ?: st.easyMinutes} мин" + (real?.why?.let { " ($it)" } ?: "") +
                    ": на экране «Сегодня» — «Начать». В разговорном темпе, без ускорений.") {
                    val old = repo.store.settingsNow().coachRunToday
                    val term = CoachTerm(min ?: 0, today.toEpochDay())
                    repo.updateSettings { it.copy(coachRunToday = term) }
                    CoachCall("undo_run_today", args("old" to old?.let { AppJson.encodeToJsonElement(CoachTerm.serializer(), it) }, "expect" to AppJson.encodeToJsonElement(CoachTerm.serializer(), term)))
                }
            }
            LONG -> {
                val w = a.int("weeks")?.takeIf { it in 0..MAX_WEEKS } ?: return no("Срок — от 0 до $MAX_WEEKS недель.")
                val program = repo.store.programNow() ?: return no("Нет плана.")
                if (!repo.store.runStateNow().canRunContinuously) return no("Пока бег чередуется с ходьбой: длину ведёт лестница приложения.")
                if (program.week.days.any { d -> d.sessions.any { it.kind == SessionKind.RUN_LONG } }) return no("Длинный бег в плане уже есть.")
                if (program.week.days.none { d -> d.sessions.any { it.kind == SessionKind.RUN_EASY } }) return no("В неделе нет лёгкого бега.")
                if (w == 0 && s.coachLong == null) return no("Длинного бега от тренера и так нет.")
                val until = today.plusWeeks(w.toLong()).minusDays(1)
                auto(if (w == 0) "Длинный лёгкий бег от тренера отменён." else "Последний лёгкий бег недели — длинный, на ${weeks(w)}, до ${Ru.date(until)}. В день с залом — до 45 мин.") {
                    val old = repo.store.settingsNow().coachLong
                    val term = if (w == 0) null else CoachTerm(1, until.toEpochDay())
                    repo.updateSettings { it.copy(coachLong = term) }
                    CoachCall("undo_long", args("old" to old?.let { AppJson.encodeToJsonElement(CoachTerm.serializer(), it) }, "expect" to term?.let { AppJson.encodeToJsonElement(CoachTerm.serializer(), it) }))
                }
            }
            STEP -> {
                val q = a.str("exercise") ?: return no("Не сказано, какое упражнение.")
                val step = a.num("step_kg")?.takeIf { it in 0.25..20.0 } ?: return no("Шаг — от 0,25 до 20 кг.")
                val fromSaid = a["from_kg"]?.let { a.num("from_kg")?.takeIf { it in 0.0..300.0 } ?: return no("Первый вес тренажёра — от 0 до 300 кг.") }
                val found = find(places(repo), q, null)
                val ex = found.firstOrNull()?.let { Catalog[it.exerciseId] } ?: catalog(q) ?: return no("Не нашёл упражнение «$q».")
                if (ex.load != LoadKind.EXTERNAL && ex.load != LoadKind.BODYWEIGHT_PLUS) return no("У «${ex.name}» нет веса.")
                val was = s.steps[ex.id] ?: ex.step
                val wasFrom = s.stepsFrom[ex.id] ?: 0.0
                val from = fromSaid ?: wasFrom
                if (abs(was - step) < 1e-9 && abs(wasFrom - from) < 1e-9) {
                    return no("Шаг «${ex.name}» и так ${Ru.kg(step)} кг" + (if (from > 0) " от ${Ru.kg(from)} кг." else "."))
                }
                // The weights now land on the new steps: down, or up to a first weight above them (30 kg on a stack from 45
                // is 45). Either way no more than a weight change may move them (set_working_weight: 25 % at most, past
                // 10 % the person says): a first weight above the working one raised it by half at once, unsaid.
                fun on(w: Double) = forma.core.floorTo(w, step, from)
                val weights = found.filter { it.exerciseId == ex.id }.mapNotNull { repo.db.state().slot(it.tuned.key)?.weight }.filter { it > 0 }
                val cut = weights.maxOfOrNull { (it - on(it)) / it } ?: 0.0
                if (cut > WEIGHT_MAX + 1e-9) {
                    val w = weights.maxBy { (it - on(it)) / it }
                    return no("С шагом ${Ru.kg(step)} кг вес упал бы с ${Ru.kg(w)} до ${Ru.kg(on(w))} кг — слишком много.")
                }
                val rise = weights.maxOfOrNull { (on(it) - it) / it } ?: 0.0
                val risen = weights.maxByOrNull { (on(it) - it) / it }?.takeIf { rise > 1e-9 }
                if (risen != null && rise > WEIGHT_MAX + 1e-9) {
                    return no("С первым весом ${Ru.kg(from)} кг вес вырос бы с ${Ru.kg(risen)} кг до ${Ru.kg(on(risen))} кг — больше 25 % за раз.")
                }
                // A step that is a large part of the weight (20 kg on 60 kg) makes every later jump that large: the person says.
                val coarse = weights.any { step > it * 0.3 }
                val sure = max(cut, rise) <= WEIGHT_AUTO + 1e-9 && !coarse && (weights.isNotEmpty() || step <= 5.0)
                val stack = if (from > 0) listOf(0, 1, 2, 3).joinToString(", ") { Ru.kg(from + it * step) } + " … кг" else null
                either(sure, "Шаг веса «${ex.name}»: " +
                    (if (stack != null) "вес будет только $stack (шаг ${Ru.kg(step)} кг от ${Ru.kg(from)})."
                    else "${Ru.kg(was)} → ${Ru.kg(step)} кг. Вес будет меняться по ${Ru.kg(step)} кг.") +
                    (if (cut > 1e-9) " Нынешний вес встанет на ближний вес тренажёра (вниз)." else "") +
                    (risen?.let { " Вес вырастет с ${Ru.kg(it)} кг до ${Ru.kg(on(it))} кг: легче на этом тренажёре нет." } ?: "")) {
                    repo.setStep(ex.id, step, ex.step, from)
                    CoachCall("undo_step", args("exercise" to ex.id, "step" to was, "from" to wasFrom, "expect" to step, "expect_from" to from))
                }
            }
            WEIGHT -> {
                val q = a.str("exercise") ?: return no("Не сказано, какое упражнение.")
                val kg = a.num("kg") ?: return no("Не сказан вес.")
                val found = find(places(repo), q, a.str("session"))
                if (found.isEmpty()) return no("«$q» нет в плане.")
                // One weight is one rep range of one exercise: the same exercise in 4–6 and in 10–15 is two weights.
                val byKey = found.distinctBy { it.tuned.key }
                if (byKey.size > 1) return no(
                    "«${name(found.first().exerciseId)}» есть в разных тренировках с разными повторами (" +
                        byKey.joinToString("; ") { "${it.title}: ${it.tuned.repMin}–${it.tuned.repMax}" } + "). Скажи, в какой (session).",
                )
                val p = byKey.single()
                if (repo.db.workouts().active()?.kind == Kind.LIFT) return no("Сейчас идёт силовая — вес поменяю после неё.")
                val ex = exercise(p.exerciseId, s)
                if (ex.load == LoadKind.TIMED) return no("У «${ex.name}» нет рабочего веса.")
                val bodyWeight = repo.store.profileNow()?.bodyWeightKg ?: 75.0
                if (kg < 0 && !ex.assisted) return no("Вес не может быть меньше нуля.")
                if (ex.assisted && kg < -Progression.maxAssist(ex, bodyWeight)) return no("Такой помощи тренажёр не даёт.")
                // On this gym's weights: a stack from 9 by 5 takes 19, not 20.
                val w = if (kg > 0 && ex.stepFrom > 0) ex.floorW(kg) else down(kg, ex.stepAt(kg))
                val row = repo.db.state().slot(p.tuned.key)
                val old = row?.weight
                val stat = repo.db.state().stat(ex.id)
                val next = Progression.effectiveLoad(ex, w, bodyWeight)
                if (old != null) {
                    if (abs(old - w) < 1e-9) return no("Рабочий вес «${ex.name}» и так ${Ru.kg(w)} кг.")
                    // With extra weight on the body (pull-ups with a plate) the move is the plate's, as the engine's jumps are.
                    val plate = ex.load == LoadKind.BODYWEIGHT_PLUS && old > 0
                    val base = if (plate) old else Progression.effectiveLoad(ex, old, bodyWeight)
                    val to = if (plate) w else next
                    if (base > 0 && abs(to - base) > base * WEIGHT_MAX + 1e-6) return no("За раз — не больше чем на 25 % от нынешних ${Ru.kg(old)} кг.")
                } else if (stat != null) {
                    // A first weight in this range: as heavy as the estimate allows with the plan's reserve, not to failure.
                    val max = OneRm.loadFor(stat.e1rm, p.tuned.repMin, p.tuned.rir) * 1.05
                    if (next > max) return no("Выше, чем позволяет расчётный максимум (${Ru.kg(stat.e1rm)} кг) с запасом ${p.tuned.rir}.")
                } else {
                    // Never done: the first session finds the weight (warm-up sets up to it). A told "I used to lift 150" is
                    // no number to start at: past half again the app's own guess, the session finds it.
                    val profile = repo.store.profileNow()
                    val trained = when (profile?.experience) { Experience.ADVANCED -> 1.7; Experience.INTERMEDIATE -> 1.35; else -> 1.0 }
                    val own = profile?.let { repo.startFactor(it, trained) } ?: 1.0
                    val hint = profile?.let { Progression.startHint(ex, it.bodyWeightKg, it.sex == Sex.FEMALE, trained * own) } ?: 0.0
                    val most = max(hint * 1.5, hint + 2 * ex.stepAt(max(hint, 1.0)))
                    if (w > most + 1e-6) return no("«${ex.name}» ещё не делал: первая тренировка сама подберёт вес разминочными подходами. Сейчас поставить можно до ${Ru.kg(down(most, ex.stepAt(most)))} кг.")
                }
                // A heavier weight must be within reach, as the engine judges its own jumps: the estimate gives at least the
                // range's bottom less one rep with the plan's reserve. "Цель 4 повтора" at a weight the estimate gives 1 for
                // would be failed sets, then cuts, for weeks.
                if (stat != null && (old == null || w > old)) {
                    val can = OneRm.rtfAt(stat.e1rm, next) - p.tuned.rir
                    if (can < p.tuned.repMin - 1) return no(
                        "На ${Ru.kg(w)} кг по расчётному максимуму (${Ru.kg(stat.e1rm)} кг) выйдет меньше ${p.tuned.repMin - 1} " +
                            "${Ru.plural(p.tuned.repMin - 1, "повтора", "повторов", "повторов")} с запасом ${p.tuned.rir}. Максимум растёт от сделанных подходов: " +
                            "добери повторы на нынешнем весе, и приложение прибавит само.",
                    )
                }
                // Never below what the engine itself allows: 85 % of what the best of the last 8 weeks supports — a first
                // weight in a new range too (it would then crawl back a step a session).
                val best = repo.recentBest(ex.id, today.toEpochDay(), -1L)
                if (best != null && (old == null || w < old)) {
                    val floor = OneRm.loadFor(best, row?.reps ?: p.tuned.repMin, p.tuned.rir) * 0.85
                    if (next < floor - 1e-6) return no("Ниже 85 % веса последних 8 недель: так приложение вес не снижает. При боли или болезни лучше «Так себе» или пауза.")
                }
                // The reps to aim for at the new weight, as the engine sets them when it moves a weight itself.
                val reps = stat?.let { st ->
                    (OneRm.rtfAt(st.e1rm, next) - p.tuned.rir).toInt().coerceIn(maxOf(1, p.tuned.repMin - 1), maxOf(p.tuned.repMin, p.tuned.repMax - 1))
                } ?: if (old == null || w > old) p.tuned.repMin else row.reps
                // A weight always waits for «Принять»: it decides every set from then on.
                val goal = repo.store.profileNow()?.liftGoal?.takeIf { it.exerciseId == ex.id }
                confirm(withRisk("Рабочий вес «${ex.name}» (${p.tuned.repMin}–${p.tuned.repMax}): ${old?.let { Ru.kg(it) } ?: "—"} → ${Ru.kg(w)} кг, цель $reps ${Ru.plural(reps, "повтор", "повтора", "повторов")} со следующей тренировки.",
                    goal?.let { g -> old?.let { GoalRisk.liftDown(g, ex.name, it, w) } })) {
                    val now = repo.db.state().slot(p.tuned.key)
                    repo.db.state().upsertSlot(
                        now?.copy(weight = w, exerciseId = p.tuned.exerciseId, reps = reps, stalls = 0, misses = 0, steady = 0)
                            ?: SlotStateEntity(p.tuned.key, p.tuned.exerciseId, w, reps, 0, 0, 0, repo.nowMs()),
                    )
                    // Help chosen (a sore shoulder, a band for more reps) stays: sets done without it do not take it away.
                    repo.store.editSettings { it.copy(chosenHelp = if (w < 0) it.chosenHelp + p.tuned.key else it.chosenHelp - p.tuned.key) }
                    repo.changed()
                    CoachCall(
                        "undo_weight",
                        args("key" to p.tuned.key, "row" to now?.let { AppJson.encodeToJsonElement(SlotStateEntity.serializer(), it) }, "expect" to w, "reps" to reps),
                    )
                }
            }
            REMEMBER -> {
                val text = a.str("text")?.take(NOTE_LEN) ?: return no("Нечего запомнить.")
                if (s.coachNotes.any { norm(it) == norm(text) }) return no("Это уже запомнено.")
                if (s.coachNotes.size >= MAX_NOTES) return no("Запомнено уже $MAX_NOTES — сначала забудь что-то ненужное («Что помнит»).")
                auto("Запомнил: «$text».") {
                    repo.updateSettings { it.copy(coachNotes = (it.coachNotes + text).takeLast(MAX_NOTES)) }
                    CoachCall("undo_remember", args("text" to text))
                }
            }
            FORGET -> {
                val q = a.str("text") ?: return no("Не сказано, что забыть.")
                val note = s.coachNotes.firstOrNull { norm(it) == norm(q) } ?: s.coachNotes.filter { norm(it).contains(norm(q)) }.singleOrNull()
                    ?: return no("Не нашёл такого среди запомненного.")
                auto("Забыл: «$note».") {
                    repo.updateSettings { it.copy(coachNotes = it.coachNotes - note) }
                    CoachCall("undo_forget", args("text" to note))
                }
            }
            SETS, REPS, REST -> {
                val q = a.str("exercise") ?: return no("Не сказано, какое упражнение.")
                val all = places(repo)
                val found = find(all, q, a.str("session")).distinctBy { it.key }
                if (found.isEmpty()) return no("«$q» нет в плане.")
                val p = found.first()
                val cur = p.tuned
                // The numbers of a plan exercise are kept under its id: they go with it to every session that has it.
                val everywhere = found.sumOf { f -> all.count { it.key == f.key } } > 1
                val field: String
                val text: String
                val tune: (Place) -> Triple<Int, Pair<Int, Int>, Int>
                when (call.name) {
                    SETS -> {
                        val n = a.int("sets")?.takeIf { it in 1..TrainingRepository.MAX_SETS } ?: return no("Подходов — от 1 до ${TrainingRepository.MAX_SETS}.")
                        if (n == cur.sets) return no("Подходов и так $n.")
                        field = "sets"
                        val goal = repo.store.profileNow()?.liftGoal?.takeIf { it.exerciseId == p.exerciseId }
                        text = withRisk("«${name(p.exerciseId)}»: подходов ${cur.sets} → $n.", goal?.let { GoalRisk.liftCut(it, name(p.exerciseId), cur.sets, n) })
                        tune = { x -> Triple(n, x.tuned.repMin to x.tuned.repMax, x.tuned.restSec) }
                    }
                    REPS -> {
                        val lo = a.int("min")?.takeIf { it in 1..100 } ?: return no("Повторы — числа от 1 до 100.")
                        val hi = a.int("max")?.takeIf { it in lo..100 } ?: return no("Повторы «до» — не меньше «от».")
                        if (lo == cur.repMin && hi == cur.repMax) return no("Повторы и так $lo–$hi.")
                        field = "reps"
                        val goal = repo.store.profileNow()?.liftGoal?.takeIf { it.exerciseId == p.exerciseId }
                        // Said when no heavy work of the goal's lift is left: its lighter day moving to more reps is not.
                        val lowest = all.filter { it.exerciseId == p.exerciseId }.minOfOrNull { x -> if (found.any { it.key == x.key }) lo else x.tuned.repMin } ?: lo
                        text = withRisk("«${name(p.exerciseId)}»: повторы ${cur.repMin}–${cur.repMax} → $lo–$hi. Вес в новом диапазоне приложение посчитает по расчётному максимуму.",
                            goal?.let { GoalRisk.liftReps(it, name(p.exerciseId), lowest) })
                        tune = { x -> Triple(x.tuned.sets, lo to hi, x.tuned.restSec) }
                    }
                    else -> {
                        val sec = a.int("seconds")?.takeIf { it in TrainingRepository.MIN_REST..TrainingRepository.MAX_REST }
                            ?: return no("Отдых — от ${TrainingRepository.MIN_REST} до ${TrainingRepository.MAX_REST} секунд.")
                        if (sec == cur.restSec) return no("Отдых и так ${Ru.clock(sec)}.")
                        field = "rest"
                        val goal = repo.store.profileNow()?.liftGoal?.takeIf { it.exerciseId == p.exerciseId }
                        text = withRisk("«${name(p.exerciseId)}»: отдых ${Ru.clock(cur.restSec)} → ${Ru.clock(sec)}.", goal?.let { GoalRisk.liftRest(it, name(p.exerciseId), sec) })
                        tune = { x -> Triple(x.tuned.sets, x.tuned.repMin to x.tuned.repMax, sec) }
                    }
                }
                confirm(text + if (everywhere) " Во всех тренировках, где оно есть." else "") {
                    val was = repo.store.settingsNow()
                    for (x in found) {
                        val (n, r, rest) = tune(x)
                        repo.tunePlan(x.key, x.planned, n, r, rest)
                    }
                    val now = repo.store.settingsNow()
                    // Only the field changed goes back, and only while it is as the coach left it (no own number when the
                    // coach's was the plan's).
                    CoachCall("undo_tune", args("field" to field, "keys" to JsonArray(found.map { x ->
                        args("key" to x.key, "old" to tuneOf(was, field, x.key), "now" to tuneOf(now, field, x.key))
                    })))
                }
            }
            SWAP -> {
                val q = a.str("exercise") ?: return no("Не сказано, какое упражнение.")
                val all = places(repo)
                val found = find(all, q, null)
                if (found.isEmpty()) return no("«$q» нет в плане.")
                val nq = a.str("new_exercise") ?: return no("Не сказано, на что заменить.")
                val to = catalog(nq) ?: return no("Не нашёл упражнение «$nq».")
                val p = found.first()
                if (to.id == p.exerciseId) return no("Это то же упражнение.")
                if (to.load == LoadKind.TIMED != (Catalog[p.exerciseId].load == LoadKind.TIMED)) return no("Упражнение на время меняется только на упражнение на время.")
                // Every place of the exercise, as the card says: the plan's own (by the plan's exercise) and the ones added
                // to a day — half a swap would leave the heavy day on one and the light day on the other.
                val same = all.filter { it.exerciseId == p.exerciseId }
                val plans = same.filter { !it.added }.map { it.planId }.distinct()
                val days = same.filter { it.added }.map { it.templateId }.distinct()
                val goal = repo.store.profileNow()?.liftGoal?.takeIf { it.exerciseId == p.exerciseId }
                confirm(withRisk("Заменить «${name(p.exerciseId)}» на «${to.name}» во всех тренировках.", goal?.let { GoalRisk.liftGone(it, name(p.exerciseId), 0, to.name) })) {
                    val was = repo.store.settingsNow()
                    for (pid in plans) repo.setSwap(pid, to.id)
                    for (t in days) repo.replaceAdded(t, p.exerciseId, to.id)
                    // A heavy lift swapped for a single-joint one drops the person's own sets, reps and rest: kept to come
                    // back with it.
                    CoachCall(
                        "undo_swap_all",
                        args(
                            "plans" to JsonArray(plans.map { pid ->
                                args("plan" to pid, "to" to was.swaps[pid], "sets" to was.sets[pid], "reps" to ints(was.reps[pid]), "rest" to was.rest[pid])
                            }),
                            "templates" to JsonArray(days.map { JsonPrimitive(it) }), "from" to to.id, "back" to p.exerciseId,
                        ),
                    )
                }
            }
            PICKS -> {
                // Not said: none; a name not found: null (refused).
                fun list(key: String): List<Exercise>? = a.str(key).orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                    .map { q -> catalog(q) ?: return null }
                val like = list("like") ?: return no("Не нашёл одно из упражнений «${a.str("like")}».")
                val dislike = list("dislike") ?: return no("Не нашёл одно из упражнений «${a.str("dislike")}».")
                val clear = list("clear") ?: return no("Не нашёл одно из упражнений «${a.str("clear")}».")
                if (like.isEmpty() && dislike.isEmpty() && clear.isEmpty()) return no("Не сказано, что нравится, а что нет.")
                val (_, rules) = repo.pickGroups() ?: return no("Плана зала нет.")
                like.firstNotNullOfOrNull { ex -> forma.core.plan.Picks.unavailable(ex, rules)?.let { "«${ex.name}» пока нельзя: $it." } }?.let { return no(it) }
                val likes = s.likes.mapNotNull { (k, v) -> runCatching { forma.core.model.Pattern.valueOf(k) }.getOrNull()?.let { it to v.toMutableList() } }.toMap().toMutableMap()
                val dislikes = s.dislikes.toMutableSet()
                val drop = (clear + like + dislike).map { it.id }.toSet()
                likes.values.forEach { it.removeAll(drop) }
                dislikes.removeAll(drop)
                like.forEach { likes.getOrPut(it.pattern) { mutableListOf() } += it.id }
                dislikes += dislike.map { it.id }
                val newLikes = likes.filterValues { it.isNotEmpty() }
                val r = repo.previewPicks(newLikes, dislikes)
                val changes = r.swaps.filter { (pid, to) -> (s.swaps[pid] ?: pid) != to }
                val text = buildString {
                    append("Мои упражнения: ")
                    append(listOfNotNull(
                        like.takeIf { it.isNotEmpty() }?.let { l -> "нравится — " + l.joinToString(", ") { it.name } },
                        dislike.takeIf { it.isNotEmpty() }?.let { l -> "не хочу — " + l.joinToString(", ") { it.name } },
                        clear.takeIf { it.isNotEmpty() }?.let { l -> "снять отметку — " + l.joinToString(", ") { it.name } },
                    ).joinToString("; "))
                    append(". ")
                    append(
                        if (changes.isEmpty()) "План от этого не меняется."
                        else "В плане: " + changes.entries.joinToString("; ") { (pid, to) -> "«${name(s.swaps[pid] ?: pid)}» → «${name(to)}»" } + ".",
                    )
                    r.notes.forEach { append(" ").append(it) }
                    // A liked movement the plan has no place for: kept, said so.
                    val moves = repo.pickGroups()?.first.orEmpty().map { it.pattern }.toSet()
                    like.filter { it.pattern !in moves }.forEach { append(" «${it.name}»: такого движения в плане сейчас нет — отметка останется, встанет, если появится.") }
                }
                confirm(text) {
                    val was = repo.store.settingsNow()
                    val done = repo.setPicks(newLikes, dislikes)
                    CoachCall(
                        "undo_picks",
                        args(
                            "likes" to JsonObject(was.likes.mapValues { (_, v) -> JsonArray(v.map { JsonPrimitive(it) }) }),
                            "dislikes" to JsonArray(was.dislikes.map { JsonPrimitive(it) }),
                            "plans" to JsonArray(done.swaps.map { (pid, to) ->
                                args(
                                    "plan" to pid, "now" to to, "to" to was.swaps[pid], "pick" to (pid in was.pickSwaps),
                                    "sets" to was.sets[pid], "reps" to ints(was.reps[pid]), "rest" to was.rest[pid],
                                )
                            }),
                        ),
                    )
                }
            }
            REMOVE -> {
                val q = a.str("exercise") ?: return no("Не сказано, какое упражнение.")
                val all = places(repo)
                val found = find(all, q, a.str("session"))
                if (found.isEmpty()) return no("«$q» нет в плане.")
                if (found.any { p -> all.count { it.templateId == p.templateId } <= 1 }) return no("Это последнее упражнение тренировки — его не убрать.")
                val goal = repo.store.profileNow()?.liftGoal?.takeIf { it.exerciseId == found.first().exerciseId }
                val rest = goal?.let { g -> all.filter { x -> x.exerciseId == g.exerciseId && found.none { it.templateId == x.templateId && it.key == x.key } } }.orEmpty()
                // The last calf work out of a runner's plan is said too (the calves are the push-off of every stride).
                val calf = { id: String -> Catalog[id].pattern == forma.core.model.Pattern.CALF }
                val calvesLeft = all.count { x -> calf(x.exerciseId) && found.none { it.templateId == x.templateId && it.key == x.key } }
                val risk = goal?.let { g ->
                    GoalRisk.liftGone(g, name(g.exerciseId), rest.size) ?: GoalRisk.liftHeavyGone(g, name(g.exerciseId), rest.map { it.tuned.repMin to it.tuned.repMax })
                } ?: if (calf(found.first().exerciseId) && calvesLeft == 0) GoalRisk.calvesGone(repo.store.profileNow()?.runGoal) else null
                val where = when {
                    found.size > 1 -> "из плана (во всех тренировках, где оно есть)."
                    a.str("session") != null && all.count { it.exerciseId == found.first().exerciseId } > 1 -> "из «${found.first().title}»."
                    else -> "из плана."
                }
                confirm(withRisk("Убрать «${name(found.first().exerciseId)}» $where", risk)) {
                    val was = repo.store.settingsNow()
                    for (p in found) if (p.added) repo.removeAdded(p.templateId, p.exerciseId) else repo.setRemoved(p.planId, true)
                    // An added one's own sets, reps and rest go with it: kept to come back.
                    CoachCall("undo_remove", JsonObject(mapOf("places" to JsonArray(found.map {
                        args(
                            "template" to it.templateId, "id" to it.planId, "added" to it.added,
                            "sets" to was.sets[it.key], "reps" to ints(was.reps[it.key]), "rest" to was.rest[it.key],
                        )
                    }))))
                }
            }
            ADD -> {
                val sq = a.str("session") ?: return no("Не сказано, в какую тренировку.")
                val program = repo.store.programNow() ?: return no("Нет плана.")
                val t = program.templates.firstOrNull { it.id == sq || norm(it.title) == norm(sq) } ?: return no("Нет силовой «$sq».")
                val eq = a.str("exercise") ?: return no("Не сказано, какое упражнение.")
                val ex = catalog(eq) ?: return no("Не нашёл упражнение «$eq».")
                if (places(repo).any { it.templateId == t.id && it.exerciseId == ex.id }) return no("«${ex.name}» уже есть в «${t.title}».")
                val spec = TrainingRepository.addedSpec(ex.id)
                confirm("Добавить «${ex.name}» в «${t.title}»: ${spec.sets} × ${spec.repMin}–${spec.repMax}, после упражнений плана.") {
                    repo.addToPlan(t.id, ex.id)
                    CoachCall("undo_add", args("template" to t.id, "id" to ex.id))
                }
            }
            GOAL -> {
                val profile = repo.store.profileNow() ?: return no("Нет анкеты.")
                if (repo.store.programNow()?.copiedFrom != null) return no("Сейчас программа друга: цель меняется в «Ещё → Цель, дни и данные».")
                val m = a.int("meters")?.takeIf { it in 400..42195 } ?: return no("Дистанция — от 400 м до марафона.")
                val sec = a.str("time")?.let(::seconds) ?: return no("Время — как 5:30 или 1:45:00.")
                val pace = sec / (m / 1000.0)
                if (pace !in 150.0..600.0) return no("Это темп ${Ru.clock(pace.roundToInt())} на км — так не бывает. Проверь время.")
                val date = a.str("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return no("Дата — как 2026-11-20.") }
                if (date != null && (date.isBefore(today) || date.isAfter(today.plusYears(1)))) return no("Дата забега — от сегодня до года вперёд.")
                val goal = RunGoal(m, sec, date?.toEpochDay())
                val og = profile.runGoal
                if (goal == og) return no("Цель и так такая.")
                // As TrainingRepository.updateProfile judges it: another distance or a time 5 % off starts the ladder anew.
                val anew = og == null || og.meters != m || abs(sec - og.seconds) > og.seconds * 0.05
                // A race up to 3 km plans the running for speed, a longer one for endurance: the week itself changes.
                val aim = repo.store.programNow()?.aim
                val was = aim?.let { ProgramGenerator.planAim(it, og?.middleDistance == true) }
                val will = aim?.let { ProgramGenerator.planAim(it, goal.middleDistance) }
                val shift = when {
                    was == null || will == null || was.run == will.run -> ""
                    will.run == RunFocus.SPEED -> " Забег до 3 км: бег станет скоростным — вместо длинного бега второй интенсивный, лёгкий бег до ${ProgramGenerator.caps(will).easyMax} мин."
                    else -> " Забег длиннее 3 км: вернётся длинный бег (до ${ProgramGenerator.caps(will).longMax} мин), интенсивных станет меньше."
                }
                // A week without running (or one run for a middle distance) does not train the goal: said, not promised.
                val runs = repo.store.programNow()?.week?.days?.count { d -> d.sessions.any { it.isRun } } ?: 0
                val risk = GoalRisk.runGoalWithoutRuns(goal, runs)
                confirm(withRisk("Цель: ${dist(m)} за ${Ru.clock(sec)}" + (date?.let { ", забег ${Ru.date(it)} ${it.year}" } ?: "") +
                    (og?.let { " (была ${dist(it.meters)} за ${Ru.clock(it.seconds)})" } ?: "") + "." + (if (runs > 0) " План бега пересчитается." + shift else "") +
                    (if (anew && og != null && runs > 0) " Отрезки в темпе цели начнутся с первой ступени." else ""), risk)) {
                    val st = repo.store.runStateNow()
                    repo.store.profileNow()?.let { repo.updateProfile(it.copy(runGoal = goal)) }
                    CoachCall(
                        "undo_goal",
                        args(
                            "goal" to og?.let { AppJson.encodeToJsonElement(RunGoal.serializer(), it) }, "expect" to AppJson.encodeToJsonElement(RunGoal.serializer(), goal),
                            "stage" to st.goalStage, "fails" to st.goalFails, "soon" to st.testSoon,
                        ),
                    )
                }
            }
            LAP -> {
                val profile = repo.store.profileNow() ?: return no("Нет анкеты.")
                val m = a.int("meters")?.takeIf { it == 0 || it in 100..600 } ?: return no("Круг — от 100 до 600 м (0 — не на стадионе).")
                val lap = m.takeIf { it > 0 }
                if (lap == profile.lapMeters) return no("Круг и так " + (lap?.let { "$it м." } ?: "не задан."))
                confirm(if (lap == null) "Бегаешь не на стадионе: тренировки по времени и GPS, без кругов." else
                    "Круг стадиона: ${profile.lapMeters?.let { "$it → " } ?: ""}$lap м. Отрезки и время круга пересчитаются.") {
                    // The lap is read when a run is planned: the program stays as it is (a friend's one too).
                    val old = repo.store.profileNow()!!.lapMeters
                    repo.store.setProfile(repo.store.profileNow()!!.copy(lapMeters = lap))
                    repo.changed()
                    CoachCall("undo_lap", args("meters" to (old ?: 0), "expect" to (lap ?: 0)))
                }
            }
            WEEK -> {
                val profile = repo.store.profileNow() ?: return no("Нет анкеты.")
                val program = repo.store.programNow() ?: return no("Нет плана.")
                if (program.copiedFrom != null) return no("Сейчас программа друга: дни меняются в «Ещё → Цель, дни и данные».")
                val sides = a.str("sides")?.lowercase()
                if (sides != null && sides !in setOf("both", "gym", "run")) return no("Что будет в неделе: both, gym или run.")
                // An empty text is "not said" (a model fills optional strings with ""): a side goes only with sides.
                val gymAsked = (call.args["gym_days"] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.content.isNotBlank() }?.let { weekDays(it.content) ?: return no("Дни зала — как «вт, чт, сб».") }
                val runAsked = (call.args["run_days"] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.content.isNotBlank() }?.let { weekDays(it.content) ?: return no("Дни бега — как «пн, чт, сб».") }
                val aim0 = profile.aim
                // An empty list of days is that side gone; a side asked for without days keeps the days it has.
                val lifts = when (sides) { "gym", "both" -> true; "run" -> false; else -> gymAsked?.isNotEmpty() ?: aim0.lifts }
                val runs = when (sides) { "run", "both" -> true; "gym" -> false; else -> runAsked?.isNotEmpty() ?: aim0.runs }
                if (!lifts && !runs) return no("В неделе должно остаться хоть что-то: зал или бег.")
                // A side coming back comes with what it was for and the priority it had («поровну» stays «поровну»).
                val kept = profile.keptAim
                val lift = if (!lifts) LiftFocus.NONE else aim0.lift.takeIf { it != LiftFocus.NONE } ?: kept?.lift?.takeIf { it != LiftFocus.NONE } ?: LiftFocus.POWERBUILDING
                val run = if (!runs) RunFocus.NONE else aim0.run.takeIf { it != RunFocus.NONE } ?: kept?.run?.takeIf { it != RunFocus.NONE }
                    ?: if (profile.runGoal != null) RunFocus.ENDURANCE else RunFocus.HEALTH
                val both = lifts && runs
                val emphasis = if (both && !(aim0.lifts && aim0.runs)) kept?.emphasis ?: Emphasis.BALANCED else aim0.emphasis
                val aim = Aim(lift, run, emphasis).normalized()
                val planGym = program.week.days.filter { d -> d.sessions.any { it.kind == SessionKind.LIFT } }.map { it.day }.toSet()
                val planRun = program.week.days.filter { d -> d.sessions.any { it.isRun } }.map { it.day }.toSet()
                val own = gymAsked != null || runAsked != null || profile.ownWeek
                val next = if (!own) profile.copy(aim = aim, goal = aim.nearestGoal()) else {
                    val gym = if (!lifts) emptySet() else gymAsked?.takeIf { it.isNotEmpty() } ?: profile.gymDays?.takeIf { it.isNotEmpty() } ?: planGym
                    val runD = if (!runs) emptySet() else runAsked?.takeIf { it.isNotEmpty() } ?: profile.runDays?.takeIf { it.isNotEmpty() } ?: planRun
                    if (lifts && gym.isEmpty()) return no("На какие дни поставить зал?")
                    if (runs && runD.isEmpty()) return no("На какие дни поставить бег?")
                    profile.copy(
                        aim = aim, goal = aim.nearestGoal(), gymDays = gym, runDays = runD, trainingDays = gym + runD,
                        doubleDays = (gym intersect runD).isNotEmpty(),
                    )
                }
                if (next == profile) return no("Неделя и так такая.")
                val (text, risk) = weekOf(profile, next)
                confirm(withRisk(text, risk)) {
                    val old = repo.store.profileNow()!!
                    repo.updateProfile(next)
                    CoachCall("undo_week", args("profile" to AppJson.encodeToJsonElement(Profile.serializer(), old), "expect" to AppJson.encodeToJsonElement(Profile.serializer(), next)))
                }
            }
            PROFILE -> {
                val profile = repo.store.profileNow() ?: return no("Нет анкеты.")
                val program = repo.store.programNow() ?: return no("Нет плана.")
                val unclear = mutableListOf<String>()
                fun <E> choice(key: String, of: Map<String, E>): E? = a.str(key)?.lowercase()?.let { v -> of[v] ?: null.also { unclear += key } }
                fun flag(key: String): Boolean? = a[key]?.let { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content?.lowercase() }?.let { v ->
                    when (v) { "true", "да" -> true; "false", "нет" -> false; else -> null.also { unclear += key } }
                }
                val lift = choice("lift_focus", LIFT_KEYS)
                val run = choice("run_focus", RUN_KEYS)
                val priority = choice("priority", PRIORITY_KEYS)
                val gear = choice("gear", GEAR_KEYS)
                val experience = choice("experience", EXPERIENCE_KEYS)
                val level = choice("run_level", LEVEL_KEYS)
                val sex = choice("sex", SEX_KEYS)
                val double = flag("double_days")
                val bar = flag("pullup_bar")
                if (unclear.isNotEmpty()) return no("Не понял, что значит ${unclear.joinToString(", ")}: возьми слово из списка функции.")
                // An empty text or a zero is "not said": a model fills optional fields so (a weight of 0 is no weight).
                val days = a.str("days")?.let { t -> weekDays(t)?.takeIf { it.isNotEmpty() } ?: return no("Дни — как «пн, ср, пт», хотя бы один.") }
                val minutes = a.int("session_minutes")?.takeIf { it != 0 }?.also { if (it !in 30..120) return no("Тренировка — от 30 до 120 минут.") }
                val weight = a.num("body_weight")?.takeIf { it != 0.0 }?.also { if (it !in 30.0..250.0) return no("Вес тела — от 30 до 250 кг.") }
                val height = a.int("height")?.takeIf { it != 0 }?.also { if (it !in 120..230) return no("Рост — от 120 до 230 см.") }
                val born = a.int("birth_year")?.takeIf { it != 0 }?.also { if (it !in today.year - 90..today.year - 12) return no("Год рождения — от ${today.year - 90} до ${today.year - 12}.") }
                val raceM = a.int("race_meters")?.also { if (it != 0 && it !in 400..42195) return no("Забег — от 400 м до марафона (0 — убрать).") }
                val raceSec = if (raceM == null || raceM == 0) null else {
                    val t = a.str("race_time")?.let(::seconds) ?: return no("Время забега — как 6:05 или 24:30.")
                    if (t / (raceM / 1000.0) !in 150.0..600.0) return no("Это темп ${Ru.clock((t / (raceM / 1000.0)).roundToInt())} на км — так не бывает. Проверь время.")
                    t
                }
                val finish = a.int("finish_meters")?.also { if (it != 0 && it !in 400..42195) return no("Без остановки — от 400 м до марафона (0 — убрать).") }

                val aim0 = profile.aim.normalized()
                val liftF = lift ?: aim0.lift
                val runF = run ?: aim0.run
                if (liftF == LiftFocus.NONE && runF == RunFocus.NONE) return no("В неделе должно остаться хоть что-то: зал или бег.")
                val aim = Aim(liftF, runF, priority ?: aim0.emphasis).normalized()
                var next = profile.copy(aim = aim, goal = aim.nearestGoal())
                // Days said: the app lays the week out over them. A side added or dropped with the person's own week: over
                // the days that week had, laid out anew (each side's own days — set_week).
                val sides = aim.lifts != aim0.lifts || aim.runs != aim0.runs
                if (days != null) next = next.copy(trainingDays = days, gymDays = null, runDays = null)
                else if (sides && profile.ownWeek) next = next.copy(trainingDays = profile.gymDays!! + profile.runDays!!, gymDays = null, runDays = null)
                double?.let { next = next.copy(doubleDays = it) }
                minutes?.let { next = next.copy(sessionMinutes = it) }
                gear?.let { next = next.copy(gear = it) }
                bar?.let { next = next.copy(pullupBar = it) }
                experience?.let { next = next.copy(experience = it) }
                level?.let { next = next.copy(runLevel = it) }
                weight?.let { next = next.copy(bodyWeightKg = it) }
                height?.let { next = next.copy(heightCm = it) }
                born?.let { next = next.copy(birthYear = it) }
                sex?.let { next = next.copy(sex = it) }
                raceM?.let { next = next.copy(raceMeters = raceSec?.let { _ -> it.toDouble() }, raceSeconds = raceSec?.toDouble()) }
                finish?.let { next = next.copy(finishMeters = it.takeIf { m -> m > 0 }) }

                val changed = FIELDS.filter { it.words(profile) != it.words(next) }
                if (changed.isEmpty()) return no("Анкета и так такая.")
                if (program.copiedFrom != null && changed.any { it.key !in BODY }) {
                    return no("Сейчас программа друга: для чего, дни, длина тренировки, опыт и бег меняются в «Ещё → Цель, дни и данные». Вес, рост, год рождения и пол — можно.")
                }
                val shape = changed.any { it.key in SHAPE }
                val (week, risk) = if (shape) weekOf(profile, next) else null to null
                val paces = if (!shape && changed.any { it.key == "race" || it.key == "level" }) " Темпы бега пересчитаются." else ""
                val text = changed.joinToString(" ") { "${it.label.replaceFirstChar(Char::titlecase)}: ${it.words(profile)} → ${it.words(next)}." } +
                    (week?.let { " $it" } ?: paces)
                confirm(withRisk(text, risk)) {
                    val old = repo.store.profileNow()!!
                    val st = repo.store.runStateNow()
                    // Only the fields said, on the profile as it is now: what else moved since stays.
                    saveProfile(repo, changed.fold(old) { p, f -> f.put(p, next) }, old)
                    CoachCall(
                        "undo_profile",
                        args(
                            "profile" to AppJson.encodeToJsonElement(Profile.serializer(), old),
                            "expect" to AppJson.encodeToJsonElement(Profile.serializer(), repo.store.profileNow()!!),
                            "fields" to JsonArray(changed.map { JsonPrimitive(it.key) }),
                            "run" to AppJson.encodeToJsonElement(RunState.serializer(), st), "runs" to repo.store.runStateNow().runs,
                        ),
                    )
                }
            }
            GYM_GOAL -> {
                val profile = repo.store.profileNow() ?: return no("Нет анкеты.")
                val program = repo.store.programNow() ?: return no("Нет плана.")
                val kg = a.num("kg") ?: return no("Не сказано, какой вес.")
                val old = profile.liftGoal
                val had = s.goalPlan != null
                if (kg <= 0.0) {
                    old ?: return no("Цели в зале нет.")
                    return confirm("Снять цель в зале (${name(old.exerciseId)} ${Ru.kg(old.kg)} кг)." +
                        if (had) " План, подстроенный под неё, вернётся как был — где его с тех пор не меняли." else "") {
                        repo.setLiftGoal(null, adjust = false)
                        CoachCall("undo_gym_goal", args("goal" to goalJson(old), "plan" to had))
                    }
                }
                val q = a.str("exercise") ?: old?.exerciseId ?: return no("Не сказано, какое упражнение.")
                if (program.templates.isEmpty() || program.week.days.none { d -> d.sessions.any { it.kind == SessionKind.LIFT } }) {
                    return no("Сейчас в неделе нет зала: цель в зале не на чем тренировать. Сначала вернуть зал в неделю (set_week), потом цель.")
                }
                val all = places(repo)
                // The lift asked for itself, not one like it: a goal on the barbell bench is not one on the machine press.
                val asked = catalog(q)
                val found = find(all, q, null).filter { asked == null || it.exerciseId == asked.id }
                if (found.isEmpty()) {
                    val like = asked?.let { a0 -> all.firstOrNull { p -> Catalog[p.exerciseId].primary.firstOrNull() == a0.primary.firstOrNull() && GymGoals.suits(p.exerciseId) } }
                    return no("«${asked?.name ?: q}» нет в плане" + (like?.let { " (там «${name(it.exerciseId)}»)" } ?: "") +
                        ": поставить цель на то, что в плане, или сначала заменить им упражнение — потом цель.")
                }
                val exId = found.first().exerciseId
                if (!GymGoals.suits(exId)) return no("Цель в зале — на упражнение на несколько мышц с весом: штанга, гантели, тренажёр. «${name(exId)}» не подходит.")
                if (kg > 500) return no("Слишком большой вес.")
                val reps = a.int("reps") ?: 1
                if (reps !in 1..12) return no("Повторы — от 1 до 12.")
                // On the lift's steps in this gym: a weight that cannot be put on the bar is no goal to test.
                val kgOnStep = exercise(found.first().exerciseId, s).let { e -> kotlin.math.round(kg / e.stepAt(kg)) * e.stepAt(kg) }.takeIf { it > 0 } ?: kg
                val date = a.str("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return no("Дата — как 2026-12-20.") }
                if (date != null && (!date.isAfter(today) || date.isAfter(today.plusYears(2)))) return no("Дата — после сегодня и не дальше двух лет.")
                val plan = a.str("plan") != "false"
                val goal = LiftGoal(exId, kgOnStep, reps, date?.toEpochDay())
                if (sameGoal(old, goal) && (had || !plan)) return no("Цель и так такая.")
                val path = GymGoals.path(repo, goal)
                val ex = exercise(exId, s)
                val done = path?.current?.takeIf { path.verdict == GymGoal.Verdict.DONE }
                if (done != null) {
                    // In the goal's own reps: «100 на 5» done is followed by a weight for 5, not a one-rep max.
                    val higher = floor(OneRm.loadFor(done * 1.05, reps) / ex.step) * ex.step
                    val each = if (reps > 1) " на $reps ${Ru.plural(reps, "раз", "раза", "раз")}" else ""
                    return no("По последним подходам максимум ≈ ${Ru.kg(floor(done / ex.step) * ex.step)} кг на один раз — эта цель уже по силам. Поставь выше, например ${Ru.kg(higher)} кг$each.")
                }
                val proposal = if (plan) GymGoals.propose(all, program, profile.experience, exId) else null
                val words = GymGoals.words(repo, goal)
                val head = "Цель в зале: ${name(exId)} ${Ru.kg(kgOnStep)} кг" + (if (abs(kgOnStep - kg) > 1e-9) " (${Ru.kg(kg)} — на шаг веса в твоём зале)" else "") +
                    (if (reps > 1) " на $reps ${Ru.plural(reps, "раз", "раза", "раз")}" else "") +
                    (date?.let { " к ${Ru.date(it)} ${it.year}" } ?: "") + (old?.let { " (была ${name(it.exerciseId)} ${Ru.kg(it.kg)} кг)" } ?: "") + "."
                // The plan made for the old goal on another lift goes back either way: said, not hidden behind «не меняю».
                val oldPlan = s.goalPlan?.takeIf { it.exerciseId != exId }?.let { " Подстройка плана под «${name(it.exerciseId)}» вернётся как была." } ?: ""
                val planText = (proposal?.let { " В плане: " + it.lines.joinToString(" ") } ?: " План под новую цель не меняю.") + oldPlan
                confirm(head + planText + (words?.let { " $it" } ?: "")) {
                    repo.setLiftGoal(goal, plan)
                    CoachCall("undo_gym_goal", args("goal" to goalJson(old), "plan" to had, "expect" to goalJson(goal)))
                }
            }
            NOW_SWAP, NOW_REMOVE, NOW_ADD, NOW_SETS -> now(repo, call, ::no, ::auto, ::confirm)
            else -> no("Такого тренер не умеет.")
        }
    }

    /**
     * The session going on now, changed for today only: an exercise swapped, taken out or added, its sets more or fewer.
     * The plan stays. Made at once (each with «Вернуть»), but what costs the goal in the gym waits for «Принять».
     */
    private suspend fun now(
        repo: TrainingRepository, call: CoachCall, no: (String) -> Refused,
        auto: (String, suspend () -> CoachCall?) -> Ready, confirm: (String, suspend () -> CoachCall?) -> Ready,
    ): Checked {
        val a = call.args
        val w = repo.db.workouts().active()?.takeIf { it.kind == Kind.LIFT } ?: return no("Сейчас силовая не идёт: это меняется только в начатой тренировке.")
        val plan = repo.planOf(w) ?: return no("Не получилось прочитать начатую тренировку.")
        val done = repo.db.sets().forWorkout(w.id).groupBy { it.position }
        val goal = repo.store.profileNow()?.liftGoal
        fun item(q: String): LiftItem? {
            plan.items.firstOrNull { it.exerciseId == q || norm(it.name) == norm(q) }?.let { return it }
            catalog(q)?.let { c -> plan.items.firstOrNull { it.exerciseId == c.id } }?.let { return it }
            val scored = plan.items.map { it to ExerciseSearch.score(Catalog[it.exerciseId], q) }.filter { it.second > 0 }
            val best = scored.maxOfOrNull { it.second } ?: return null
            return scored.filter { it.second == best }.singleOrNull()?.first
        }
        fun json(i: LiftItem) = AppJson.encodeToJsonElement(LiftItem.serializer(), i)
        fun goalRisk(i: LiftItem) = goal?.takeIf { it.exerciseId == i.exerciseId }?.let {
            "«${i.name}» — упражнение цели в зале (${Ru.kg(it.kg)} кг): без его подходов сегодня к ней на тренировку дальше. Если болит — правильно убрать; если просто нет времени — лучше убрать другое."
        }
        return when (call.name) {
            NOW_ADD -> {
                val q = a.str("exercise") ?: return no("Не сказано, какое упражнение.")
                val ex = catalog(q) ?: return no("Не нашёл упражнение «$q».")
                if (plan.items.any { it.exerciseId == ex.id }) return no("«${ex.name}» уже есть в этой тренировке.")
                val spec = TrainingRepository.addedSpec(ex.id)
                auto("Сегодня в «${plan.title}» добавлено «${ex.name}»: ${spec.sets} × ${spec.repMin}–${spec.repMax}, в конце. План не меняется.") {
                    check(repo.addToWorkout(w.id, ex.id, forever = false)) { "not added" }
                    val at = repo.db.workouts().get(w.id)?.let { repo.planOf(it) }?.items?.lastOrNull { it.exerciseId == ex.id }?.position
                    CoachCall("undo_now_add", args("workout" to w.id, "position" to at, "id" to ex.id))
                }
            }
            NOW_SWAP -> {
                val q = a.str("exercise") ?: return no("Не сказано, какое упражнение.")
                val it0 = item(q) ?: return no("«$q» нет в начатой тренировке.")
                if (done[it0.position] != null) return no("По «${it0.name}» уже есть подходы — заменить можно только до первого подхода.")
                val nq = a.str("new_exercise") ?: return no("Не сказано, на что заменить.")
                val to = catalog(nq) ?: return no("Не нашёл упражнение «$nq».")
                if (to.id == it0.exerciseId) return no("Это то же упражнение.")
                if (to.load == LoadKind.TIMED != (Catalog[it0.exerciseId].load == LoadKind.TIMED)) return no("Упражнение на время меняется только на упражнение на время.")
                if (plan.items.any { it.exerciseId == to.id }) return no("«${to.name}» уже есть в этой тренировке.")
                val risk = goalRisk(it0)
                val text = withRisk("Сегодня вместо «${it0.name}» — «${to.name}». План не меняется.", risk)
                val make: suspend () -> CoachCall? = {
                    check(repo.swapExercise(w.id, it0.position, to.id, forever = false)) { "not swapped" }
                    CoachCall("undo_now_swap", args("workout" to w.id, "position" to it0.position, "item" to json(it0), "expect" to to.id))
                }
                if (risk == null) auto(text, make) else confirm(text, make)
            }
            NOW_REMOVE -> {
                val q = a.str("exercise") ?: return no("Не сказано, какое упражнение.")
                val it0 = item(q) ?: return no("«$q» нет в начатой тренировке.")
                if (done[it0.position] != null) return no("По «${it0.name}» уже есть подходы — убрать нельзя, но можно не делать остальные: закончи тренировку без них.")
                if (plan.items.size <= 1) return no("Это последнее упражнение тренировки.")
                val risk = goalRisk(it0)
                val text = withRisk("Сегодня без «${it0.name}». План не меняется.", risk)
                val make: suspend () -> CoachCall? = {
                    val index = plan.items.indexOfFirst { it.position == it0.position }
                    check(repo.removeFromWorkout(w.id, it0.position, forever = false)) { "not removed" }
                    CoachCall("undo_now_remove", args("workout" to w.id, "index" to index, "item" to json(it0)))
                }
                if (risk == null) auto(text, make) else confirm(text, make)
            }
            else -> {
                val q = a.str("exercise") ?: return no("Не сказано, какое упражнение.")
                val it0 = item(q) ?: return no("«$q» нет в начатой тренировке.")
                if (it0.prescription.phase == forma.core.strength.Phase.TEST) return no("Это проверка максимума: попытки не меняю.")
                val n = a.int("sets")?.takeIf { it in 1..TrainingRepository.MAX_SETS } ?: return no("Подходов — от 1 до ${TrainingRepository.MAX_SETS}.")
                val had = done[it0.position]?.size ?: 0
                if (n < had) return no("По «${it0.name}» уже сделано $had — меньше не поставить.")
                val was = it0.prescription.sets
                if (n == was) return no("Подходов и так $n.")
                val risk = if (n < was) goal?.takeIf { it.exerciseId == it0.exerciseId }?.let { GoalRisk.liftCut(it, it0.name, was, n) } else null
                val text = withRisk("Сегодня «${it0.name}»: подходов $was → $n. План не меняется.", risk)
                val make: suspend () -> CoachCall? = {
                    val cur = repo.db.workouts().get(w.id)?.let { repo.planOf(it) }
                    if (cur != null) repo.replacePlan(w.id, cur.copy(items = cur.items.map { x -> if (x.position == it0.position) x.copy(prescription = x.prescription.copy(sets = n)) else x }))
                    CoachCall("undo_now_sets", args("workout" to w.id, "position" to it0.position, "sets" to was, "expect" to n))
                }
                if (risk == null) auto(text, make) else confirm(text, make)
            }
        }
    }

    private fun dist(m: Int) = if (m % 1000 == 0) "${m / 1000} км" else if (m > 3000) "${Ru.one(m / 1000.0)} км" else "$m м"

    /** "5:30", "21:05", "1:45:00" or seconds → seconds; null when not a time. */
    internal fun seconds(t: String): Int? {
        val parts = t.trim().split(':')
        if (parts.size == 1) return parts[0].toIntOrNull()?.takeIf { it > 0 }
        if (parts.size > 3 || parts.drop(1).any { it.length != 2 }) return null
        val n = parts.map { it.toIntOrNull() ?: return null }
        if (n.any { it < 0 } || n.drop(1).any { it >= 60 }) return null
        return n.fold(0) { acc, x -> acc * 60 + x }.takeIf { it > 0 }
    }

    /** Makes [ready] (accepted, or made at once) and logs it with its way back. Returns the log entry. */
    suspend fun make(repo: TrainingRepository, ready: Ready): CoachLogEntry {
        val undo = ready.make()
        // Ids are «Вернуть»'s handle: two changes in one millisecond must not share one.
        var entry = CoachLogEntry(repo.nowMs(), repo.nowMs(), ready.text, undo, why = ready.why)
        repo.store.editCoachLog { log ->
            entry = entry.copy(id = maxOf(entry.id, (log.maxOfOrNull { it.id } ?: 0L) + 1))
            log + entry
        }
        return entry
    }

    /**
     * Takes the change of [entry] back. False when it cannot be any more: the same thing changed since (a run moved the
     * easy run's length on, the weight was lifted since, the person edited it), or it was taken back already.
     */
    suspend fun undo(repo: TrainingRepository, entry: CoachLogEntry): Boolean {
        val call = entry.undo ?: return false
        if (repo.store.coachLogNow().firstOrNull { it.id == entry.id }?.undone != false) return false
        val done = runCatching { undoOf(repo, call) }.getOrDefault(false)
        if (done) repo.store.editCoachLog { log -> log.map { if (it.id == entry.id) it.copy(undone = true) else it } }
        return done
    }

    /**
     * The workout a change was made to: by its global id where the change kept it — the log syncs since 23 November, and
     * on another phone the workouts come from the cloud under other local ids (one of them may be this one's); not found
     * there, nothing is taken back. Changes made before that kept the local id alone.
     */
    private suspend fun workoutOf(repo: TrainingRepository, a: JsonObject): WorkoutEntity? {
        val uid = a.str("uid") ?: return a.long("workout")?.let { repo.db.workouts().get(it) }
        return repo.db.sync().workoutByUid(uid)
    }

    private suspend fun undoOf(repo: TrainingRepository, call: CoachCall): Boolean {
        val a = call.args
        return when (call.name) {
            "undo_feel" -> {
                val day = LocalDate.ofEpochDay(a.long("day")!!)
                if (repo.store.settingsNow().feelOn(day).name != a.str("expect")) false
                else { repo.updateSettings { it.withFeel(day, Feel.valueOf(a.str("feel")!!)) }; true }
            }
            // A pause or «Болею» back exactly as before: the day's mark, the pauses, the block and a lighter week the
            // pause moved — only while the pause is the one set then.
            "undo_sick", "undo_pause", "undo_resume" -> {
                val s = repo.store.settingsNow()
                val day = LocalDate.ofEpochDay(a.long("day")!!)
                if (s.pauseFrom != a.long("setFrom") || s.pauseUntil != a.long("setUntil")) return false
                if (call.name == "undo_sick" && s.feelOn(day) != Feel.SICK) return false
                repo.updateSettings {
                    it.withFeel(day, Feel.valueOf(a.str("feel")!!)).copy(pauseFrom = a.long("from"), pauseUntil = a.long("until"), pastPauses = a.pauses("past"))
                }
                // The block and its lighter weeks go back only while they are as the change left them: a lighter week set
                // since (asked of the coach, the app's own) or a block moved on since stays. A change logged before this was
                // kept puts them back as it always did, and leaves the week asked for (it never knew it) as it is.
                val left = a["left"] as? JsonObject
                fun still(key: String, now: Long?) = left == null || left.long(key) == now
                a.long("start")?.let { start -> repo.store.programNow()?.let { if (still("start", it.startEpochDay)) repo.store.setProgram(it.copy(startEpochDay = start)) } }
                val lift = repo.store.liftStateNow()
                repo.store.setLiftState(
                    lift.copy(
                        reactiveDeloadStart = if (still("deload", lift.reactiveDeloadStart)) a.long("deload") else lift.reactiveDeloadStart,
                        askedDeloadStart = if (left != null && still("asked", lift.askedDeloadStart)) a.long("asked") else lift.askedDeloadStart,
                    ),
                )
                repo.changed()
                true
            }
            "undo_rate" -> {
                val w = workoutOf(repo, a) ?: return false
                if (w.rpe != a.int("expect")) false else { repo.setRating(w.id, a.int("rpe")!!); true }
            }
            "undo_lighter" -> {
                val p = repo.store.programNow() ?: return false
                val start = (a["start"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return false
                if (p.startEpochDay != (a["expect"] as? JsonPrimitive)?.content?.toLongOrNull()) false
                else {
                    repo.store.setProgram(p.copy(startEpochDay = start))
                    (a["reactive"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { r -> repo.store.setLiftState(repo.store.liftStateNow().copy(reactiveDeloadStart = r)) }
                    // The week asked for and the moved lighter week as they were (none too); a change logged before they were
                    // kept leaves them as they are.
                    if ("asked" in a) repo.store.setLiftState(repo.store.liftStateNow().copy(askedDeloadStart = a.long("asked")))
                    if ("moved" in a) repo.store.setLiftState(repo.store.liftStateNow().copy(deloadMovedFrom = a.long("moved")))
                    repo.changed()
                    true
                }
            }
            "undo_order" -> {
                val t = a.str("template") ?: return false
                fun list(key: String) = (a[key] as? JsonArray)?.map { (it as JsonPrimitive).content }
                val now = repo.store.settingsNow().order[t]
                if (now != list("expect")) false
                else { repo.updateSettings { s -> list("old")?.let { s.copy(order = s.order + (t to it)) } ?: s.copy(order = s.order - t) }; true }
            }
            "undo_ease_day" -> {
                val day = a.int("day") ?: return false
                val expect = (a["expect"] as? JsonPrimitive)?.content?.toLongOrNull()
                val old = (a["old"] as? JsonPrimitive)?.content?.toLongOrNull()
                if (repo.store.settingsNow().easyRunDays[day] != expect) false
                else { repo.updateSettings { s -> if (old == null) s.copy(easyRunDays = s.easyRunDays - day) else s.copy(easyRunDays = s.easyRunDays + (day to old)) }; true }
            }
            "undo_run_custom" -> {
                fun run(key: String) = (a[key] as? JsonObject)?.let { AppJson.decodeFromJsonElement(CoachRun.serializer(), it) }
                if (repo.store.settingsNow().coachRun != run("expect")) false
                else { repo.updateSettings { it.copy(coachRun = run("old")) }; true }
            }
            "undo_run_step" -> {
                val now = repo.store.runStateNow()
                val old = a.int("old")
                val expect = a.int("expect")
                val cur = when (a.str("kind")) { "race_pace" -> now.goalStage; "intervals" -> now.intervalStage; else -> now.tempoStage }
                if (old == null || cur != expect) false
                else {
                    repo.store.setRunState(when (a.str("kind")) { "race_pace" -> now.copy(goalStage = old); "intervals" -> now.copy(intervalStage = old); else -> now.copy(tempoStage = old) })
                    repo.changed()
                    true
                }
            }
            "undo_wish" -> {
                val line = a.str("text")
                if (line == null || line !in repo.store.settingsNow().coachWishes) false
                else { repo.updateSettings { it.copy(coachWishes = it.coachWishes - line) }; true }
            }
            "undo_app" -> {
                val key = a.str("key") ?: return false
                val s = repo.store.settingsNow()
                if (key == "look") {
                    if (s.look != a.str("expect")) false else { repo.updateSettings { it.copy(look = a.str("old") ?: it.look) }; true }
                } else {
                    if (flag(s, key) != (a.str("expect") == "true")) false else { repo.updateSettings { setFlag(it, key, a.str("old") == "true") }; true }
                }
            }
            "undo_body" -> {
                val old = a.num("old") ?: return false
                if (repo.store.profileNow()?.bodyWeightKg != a.num("expect")) return false
                // Made before the day was kept: taken back as such a change was then.
                val day = a.long("day") ?: run { repo.logBodyWeight(old); return true }
                // The weigh-in of that day put right since: it stays.
                if (repo.db.state().bodyWeights().firstOrNull { it.epochDay == day }?.kg != a.num("expect")) return false
                // That day's weigh-in as it was before the coach's (none: no entry), and the profile's weight back.
                val was = a.num("was")
                if (was != null) repo.db.state().upsertBodyWeight(BodyWeightEntity(day, was)) else repo.db.state().deleteBodyWeight(day)
                repo.store.profileNow()?.let { repo.store.setProfile(it.copy(bodyWeightKg = old)) }
                repo.changed()
                true
            }
            "undo_trial" -> {
                val now = repo.store.runStateNow()
                val expectUntil = (a["expect_until"] as? JsonPrimitive)?.content?.toLongOrNull()
                if (now.testSoon != (a.str("expect_soon") == "true") || now.noTrialUntil != expectUntil) false
                else {
                    repo.store.setRunState(now.copy(testSoon = a.str("soon") == "true", noTrialUntil = (a["until"] as? JsonPrimitive)?.content?.toLongOrNull()))
                    repo.changed()
                    true
                }
            }
            "undo_run_pace" -> {
                val now = repo.store.runStateNow()
                val est = a.str("est") == "true"
                val cur = if (est) now.estVdot else now.vdot
                val old = a.num("old")
                if (old == null || cur != a.num("expect") || est != (now.vdot == null)) false
                else {
                    val back = if (est) now.copy(estVdot = old) else now.copy(vdot = old)
                    // The race-pace reps' pace as it was, when the change moved it (those of longer reps too, kept since).
                    val reps = (a["reps"] as? JsonObject)?.entries?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { length -> (v as? JsonPrimitive)?.doubleOrNull?.let { length to it } } }?.toMap()
                    val withRep = if (a.containsKey("rep")) back.copy(goalRepPace = a.num("rep")) else back
                    repo.store.setRunState(if (reps != null) withRep.copy(goalRepPaces = reps) else withRep)
                    repo.changed()
                    true
                }
            }
            "undo_tempo" -> {
                val now = repo.store.runStateNow()
                if (now.tempoOwn != tempoShape(a["expect"])) false
                else { repo.store.setRunState(now.copy(tempoOwn = tempoShape(a["old"]))); repo.changed(); true }
            }
            "undo_easy" -> {
                val now = repo.store.runStateNow()
                if (now.easyMinutes != a.int("expect")) false
                else { repo.store.setRunState(now.copy(easyMinutes = a.int("minutes")!!)); repo.changed(); true }
            }
            "undo_strides", "undo_long", "undo_run_today" -> {
                val s = repo.store.settingsNow()
                val now = when (call.name) { "undo_strides" -> s.coachStrides; "undo_long" -> s.coachLong; else -> s.coachRunToday }
                if (now != term(a["expect"])) false
                else {
                    repo.updateSettings {
                        when (call.name) {
                            "undo_strides" -> it.copy(coachStrides = term(a["old"]))
                            "undo_long" -> it.copy(coachLong = term(a["old"]))
                            else -> it.copy(coachRunToday = term(a["old"]))
                        }
                    }
                    true
                }
            }
            "undo_step" -> {
                val id = a.str("exercise")!!
                val st = repo.store.settingsNow()
                val now = st.steps[id] ?: Catalog[id].step
                val nowFrom = st.stepsFrom[id] ?: 0.0
                if (abs(now - a.num("expect")!!) > 1e-9 || abs(nowFrom - (a.num("expect_from") ?: 0.0)) > 1e-9) false
                else { repo.setStep(id, a.num("step")!!, Catalog[id].step, a.num("from") ?: 0.0); true }
            }
            "undo_weight" -> {
                val key = a.str("key")!!
                val now = repo.db.state().slot(key) ?: return false
                // Lifted since (the weight or its reps moved on): that is the person's progress now, not the coach's.
                if (now.weight?.let { abs(it - a.num("expect")!!) > 1e-9 } != false || now.reps != a.int("reps")) false
                else {
                    val old = (a["row"] as? JsonObject)?.let { AppJson.decodeFromJsonElement(SlotStateEntity.serializer(), it) }
                    repo.db.state().upsertSlot(old?.copy(updatedAt = now.updatedAt) ?: now.copy(weight = null))
                    repo.store.editSettings { it.copy(chosenHelp = it.chosenHelp - key) }
                    repo.changed()
                    true
                }
            }
            "undo_remember" -> { val t = a.str("text")!!; repo.updateSettings { it.copy(coachNotes = it.coachNotes - t) }; true }
            "undo_forget" -> { val t = a.str("text")!!; repo.updateSettings { it.copy(coachNotes = (it.coachNotes + t).takeLast(MAX_NOTES)) }; true }
            "undo_tune" -> {
                val field = a.str("field")!!
                val keys = (a["keys"] as JsonArray).map { it as JsonObject }
                val s = repo.store.settingsNow()
                if (keys.any { tuneOf(s, field, it.str("key")!!)?.toString() != it["now"]?.toString() }) false
                else {
                    repo.updateSettings { st ->
                        keys.fold(st) { acc, k ->
                            val key = k.str("key")!!
                            when (field) {
                                "sets" -> acc.copy(sets = k.int("old")?.let { acc.sets + (key to it) } ?: (acc.sets - key))
                                "reps" -> acc.copy(reps = k.ints("old")?.let { acc.reps + (key to it) } ?: (acc.reps - key))
                                else -> acc.copy(rest = k.int("old")?.let { acc.rest + (key to it) } ?: (acc.rest - key))
                            }
                        }
                    }
                    true
                }
            }
            "undo_week" -> {
                val old = AppJson.decodeFromJsonElement(Profile.serializer(), a["profile"]!!)
                val expect = AppJson.decodeFromJsonElement(Profile.serializer(), a["expect"]!!)
                val now = repo.store.profileNow() ?: return false
                // Only while the week is as the coach left it; what else changed in the profile since stays.
                fun shape(p: Profile) = listOf(p.aim, p.gymDays, p.runDays, p.trainingDays, p.doubleDays)
                if (shape(now) != shape(expect)) false
                else {
                    repo.updateProfile(now.copy(aim = old.aim, goal = old.goal, gymDays = old.gymDays, runDays = old.runDays, trainingDays = old.trainingDays, doubleDays = old.doubleDays))
                    true
                }
            }
            "undo_profile" -> {
                val old = AppJson.decodeFromJsonElement(Profile.serializer(), a["profile"]!!)
                val expect = AppJson.decodeFromJsonElement(Profile.serializer(), a["expect"]!!)
                val keys = (a["fields"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                val fields = FIELDS.filter { it.key in keys }
                val now = repo.store.profileNow() ?: return false
                // Only while what it set is as it left it; what else changed in the profile since stays.
                if (fields.isEmpty() || fields.any { it.words(now) != it.words(expect) }) false
                else {
                    saveProfile(repo, fields.fold(now) { p, f -> f.put(p, old) }, now)
                    // The running as it was, when the race or the level moved it and nothing was run since.
                    if (keys.any { it == "race" || it == "level" } && repo.store.runStateNow().runs == a.int("runs")) {
                        (a["run"] as? JsonObject)?.let { repo.store.setRunState(AppJson.decodeFromJsonElement(RunState.serializer(), it)) }
                    }
                    true
                }
            }
            "undo_gym_goal" -> {
                val old = (a["goal"] as? JsonObject)?.let { AppJson.decodeFromJsonElement(LiftGoal.serializer(), it) }
                val expect = (a["expect"] as? JsonObject)?.let { AppJson.decodeFromJsonElement(LiftGoal.serializer(), it) }
                if (!sameGoal(repo.store.profileNow()?.liftGoal, expect)) false
                else {
                    // The plan as it was with the old goal: made for it again, or none (the new goal's taken back).
                    val had = a.str("plan") == "true"
                    repo.setLiftGoal(old, adjust = had, keepPlan = false)
                    true
                }
            }
            "undo_swap" -> {
                val plan = a.str("plan")!!
                if (repo.store.settingsNow().swaps[plan] != a.str("expect")) false
                else {
                    repo.setSwap(plan, a.str("to"))
                    val sets = a.int("sets")
                    val reps = a.ints("reps")
                    val rest = a.int("rest")
                    if (sets != null || reps != null || rest != null) {
                        repo.updateSettings { st ->
                            st.copy(
                                sets = sets?.let { st.sets + (plan to it) } ?: st.sets,
                                reps = reps?.let { st.reps + (plan to it) } ?: st.reps,
                                rest = rest?.let { st.rest + (plan to it) } ?: st.rest,
                            )
                        }
                    }
                    true
                }
            }
            "undo_now_add", "undo_now_swap", "undo_now_remove", "undo_now_sets" -> {
                // Only in the same session, still going, and only where no set was done since.
                val w = repo.db.workouts().get(a.long("workout") ?: return false)?.takeIf { it.status == Status.ACTIVE } ?: return false
                val plan = repo.planOf(w) ?: return false
                val sets = repo.db.sets().forWorkout(w.id)
                val pos = a.int("position")
                when (call.name) {
                    "undo_now_add" -> {
                        val at = pos ?: return false
                        if (plan.items.none { it.position == at && it.exerciseId == a.str("id") } || sets.any { it.position == at }) false
                        else repo.removeFromWorkout(w.id, at, forever = false)
                    }
                    "undo_now_swap" -> {
                        val old = AppJson.decodeFromJsonElement(LiftItem.serializer(), a["item"]!!)
                        if (plan.items.none { it.position == old.position && it.exerciseId == a.str("expect") } || sets.any { it.position == old.position }) false
                        else { repo.replacePlan(w.id, plan.copy(items = plan.items.map { if (it.position == old.position) old else it })); true }
                    }
                    "undo_now_remove" -> {
                        val old = AppJson.decodeFromJsonElement(LiftItem.serializer(), a["item"]!!)
                        if (plan.items.any { it.position == old.position }) false
                        else {
                            val items = plan.items.toMutableList().apply { add((a.int("index") ?: size).coerceIn(0, size), old) }
                            repo.replacePlan(w.id, plan.copy(items = items))
                            true
                        }
                    }
                    else -> {
                        val at = pos ?: return false
                        val item = plan.items.firstOrNull { it.position == at } ?: return false
                        val back = a.int("sets") ?: return false
                        if (item.prescription.sets != a.int("expect") || sets.count { it.position == at } > back) false
                        else { repo.replacePlan(w.id, plan.copy(items = plan.items.map { if (it.position == at) it.copy(prescription = it.prescription.copy(sets = back)) else it })); true }
                    }
                }
            }
            "undo_swap_all" -> {
                val from = a.str("from")!!
                val back = a.str("back")!!
                val plans = (a["plans"] as JsonArray).map { it as JsonObject }
                val ts = (a["templates"] as JsonArray).map { (it as JsonPrimitive).content }
                val s = repo.store.settingsNow()
                // Only while every place is as the coach left it.
                if (plans.any { (s.swaps[it.str("plan")!!] ?: it.str("plan")) != from } || ts.any { from !in s.added[it].orEmpty() }) false
                else {
                    for (p in plans) {
                        val plan = p.str("plan")!!
                        repo.setSwap(plan, p.str("to"))
                        val sets = p.int("sets")
                        val reps = p.ints("reps")
                        val rest = p.int("rest")
                        if (sets != null || reps != null || rest != null) {
                            repo.updateSettings { st ->
                                st.copy(
                                    sets = sets?.let { st.sets + (plan to it) } ?: st.sets,
                                    reps = reps?.let { st.reps + (plan to it) } ?: st.reps,
                                    rest = rest?.let { st.rest + (plan to it) } ?: st.rest,
                                )
                            }
                        }
                    }
                    for (t in ts) repo.replaceAdded(t, from, back)
                    true
                }
            }
            "undo_picks" -> {
                val plans = (a["plans"] as JsonArray).map { it as JsonObject }
                val s = repo.store.settingsNow()
                // Only while every place is as the picks left it.
                if (plans.any { p -> (s.swaps[p.str("plan")!!] ?: p.str("plan")) != p.str("now") }) false
                else {
                    for (p in plans) {
                        val plan = p.str("plan")!!
                        repo.setSwap(plan, p.str("to"))
                        val sets = p.int("sets")
                        val reps = p.ints("reps")
                        val rest = p.int("rest")
                        val pick = (p["pick"] as? JsonPrimitive)?.content == "true"
                        repo.updateSettings { st ->
                            st.copy(
                                sets = sets?.let { st.sets + (plan to it) } ?: st.sets,
                                reps = reps?.let { st.reps + (plan to it) } ?: st.reps,
                                rest = rest?.let { st.rest + (plan to it) } ?: st.rest,
                                pickSwaps = if (pick) st.pickSwaps + plan else st.pickSwaps - plan,
                            )
                        }
                    }
                    val likes = (a["likes"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as JsonArray).map { (it as JsonPrimitive).content } }
                    val dislikes = (a["dislikes"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }.toSet()
                    repo.updateSettings { it.copy(likes = likes, dislikes = dislikes) }
                    true
                }
            }
            "undo_swap_added" -> {
                val ts = (a["templates"] as JsonArray).map { (it as JsonPrimitive).content }
                val s = repo.store.settingsNow()
                if (ts.any { a.str("from")!! !in s.added[it].orEmpty() }) false
                else { for (t in ts) repo.replaceAdded(t, a.str("from")!!, a.str("to")!!); true }
            }
            "undo_remove" -> {
                val ps = (a["places"] as JsonArray).map { it as JsonObject }
                val s = repo.store.settingsNow()
                if (ps.any { p -> if (p.str("added") == "true") p.str("id")!! in s.added[p.str("template")!!].orEmpty() else p.str("id")!! !in s.removed }) false
                else {
                    for (p in ps) {
                        val t = p.str("template")!!
                        val id = p.str("id")!!
                        if (p.str("added") == "true") {
                            repo.addToPlan(t, id)
                            val key = TrainingRepository.addedKey(t, id)
                            repo.updateSettings { st ->
                                st.copy(
                                    sets = p.int("sets")?.let { st.sets + (key to it) } ?: st.sets,
                                    reps = p.ints("reps")?.let { st.reps + (key to it) } ?: st.reps,
                                    rest = p.int("rest")?.let { st.rest + (key to it) } ?: st.rest,
                                )
                            }
                        } else repo.setRemoved(id, false)
                    }
                    true
                }
            }
            "undo_add" -> {
                val t = a.str("template")!!
                val id = a.str("id")!!
                if (id !in repo.store.settingsNow().added[t].orEmpty()) false else { repo.removeAdded(t, id); true }
            }
            "undo_goal" -> {
                val profile = repo.store.profileNow() ?: return false
                val expect = AppJson.decodeFromJsonElement(RunGoal.serializer(), a["expect"]!!)
                if (profile.runGoal != expect) false
                else {
                    val old = (a["goal"] as? JsonObject)?.let { AppJson.decodeFromJsonElement(RunGoal.serializer(), it) }
                    repo.updateProfile(profile.copy(runGoal = old))
                    // The race-pace ladder where it was: going back to the old goal is not a new one.
                    val st = repo.store.runStateNow()
                    repo.store.setRunState(st.copy(goalStage = a.int("stage") ?: st.goalStage, goalFails = a.int("fails") ?: st.goalFails, testSoon = a.str("soon") == "true"))
                    repo.changed()
                    true
                }
            }
            "undo_laps" -> {
                val w = workoutOf(repo, a) ?: return false
                val now = w.summaryJson?.let { runCatching { AppJson.decodeFromString(RunSummary.serializer(), it) }.getOrNull() }?.laps
                // The laps as the coach left them, or put right since by hand: then they stay.
                if (now != lapsOf(a["expect"])) false else { repo.editRunLaps(w.id, lapsOf(a["laps"])); true }
            }
            "undo_lap" -> {
                val profile = repo.store.profileNow() ?: return false
                if ((profile.lapMeters ?: 0) != a.int("expect")) false
                else { repo.store.setProfile(profile.copy(lapMeters = a.int("meters")?.takeIf { it > 0 })); repo.changed(); true }
            }
            else -> false
        }
    }

    /** The person's own sets, reps or rest of [key] in [s] as JSON; null — the plan's own. */
    private fun tuneOf(s: Settings, field: String, key: String): JsonElement? = when (field) {
        "sets" -> s.sets[key]?.let { JsonPrimitive(it) }
        "reps" -> ints(s.reps[key])
        else -> s.rest[key]?.let { JsonPrimitive(it) }
    }

    private fun flag(s: Settings, key: String): Boolean? = when (key) {
        "voice" -> s.voice; "beeps" -> s.beeps; "vibration" -> s.vibration; "gps" -> s.gps
        "auto_rest" -> s.autoRest; "keep_screen_on" -> s.keepScreenOn; "auto_pause" -> s.autoPause
        else -> null
    }

    private fun setFlag(s: Settings, key: String, on: Boolean): Settings = when (key) {
        "voice" -> s.copy(voice = on); "beeps" -> s.copy(beeps = on); "vibration" -> s.copy(vibration = on); "gps" -> s.copy(gps = on)
        "auto_rest" -> s.copy(autoRest = on); "keep_screen_on" -> s.copy(keepScreenOn = on); "auto_pause" -> s.copy(autoPause = on)
        else -> s
    }

    private fun tempoShape(e: JsonElement?): forma.core.run.TempoShape? =
        (e as? JsonObject)?.let { AppJson.decodeFromJsonElement(forma.core.run.TempoShape.serializer(), it) }

    /** «2,5» minutes, «5». */
    private fun mins(sec: Int): String = if (sec % 60 == 0) "${sec / 60}" else Ru.one(sec / 60.0)

    /** «6 × 2,5 мин, отдых 1 мин», «20 мин». */
    fun tempoText(t: forma.core.run.TempoShape): String =
        if (t.reps == 1) "${mins(t.workSec)} мин" else "${t.reps} × ${mins(t.workSec)} мин, отдых ${mins(t.restSec)} мин"

    /** Sets the tempo run's own reps (null — the plan's step) and returns its undo. */
    private suspend fun setTempo(repo: TrainingRepository, shape: forma.core.run.TempoShape?): CoachCall {
        val now = repo.store.runStateNow()
        repo.store.setRunState(now.copy(tempoOwn = shape, tempoFails = 0))
        repo.changed()
        return CoachCall("undo_tempo", args(
            "old" to now.tempoOwn?.let { AppJson.encodeToJsonElement(forma.core.run.TempoShape.serializer(), it) },
            "expect" to shape?.let { AppJson.encodeToJsonElement(forma.core.run.TempoShape.serializer(), it) },
        ))
    }

    private fun term(e: JsonElement?): CoachTerm? = (e as? JsonObject)?.let { AppJson.decodeFromJsonElement(CoachTerm.serializer(), it) }
}
