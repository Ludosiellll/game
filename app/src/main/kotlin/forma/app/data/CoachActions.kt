package forma.app.data

import forma.app.sync.CoachCall
import forma.core.model.Catalog
import forma.core.model.Exercise
import forma.core.model.ExerciseSearch
import forma.core.model.Experience
import forma.core.model.LoadKind
import forma.core.model.RunFocus
import forma.core.model.RunGoal
import forma.core.model.Sex
import forma.core.plan.ProgramGenerator
import forma.core.plan.SessionKind
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
        fn(RATE, "Исправить оценку тяжести последней пробежки или силовой (1–10, как в приложении). Оценка последней пробежки решает её заново: лёгкий бег длиннее или короче, шаг лестницы.",
            Param("which", "string", "last_run — последняя пробежка, last_gym — последняя силовая", values = listOf("last_run", "last_gym")), Param("rpe", "integer", "1–10"))
        fn(EASY, "Длина лёгкого бега с этого дня (минуты); дальше она меняется по правилам приложения. Не больше потолка под цель, не больше 110 % самой длинной пробежки за 30 дней и не больше +15 % (минимум +5 мин) за неделю. Второе изменение за неделю и сокращение больше чем на 10 % за две недели человек подтверждает.",
            Param("minutes", "integer", "минуты"))
        fn(STRIDES, "Сколько ускорений после лёгкого бега в дни, когда они есть в плане, на weeks недель (0 — без ускорений).",
            Param("count", "integer", "0–8 (пока бег новый — до 6)"), Param("weeks", "integer", "1–$MAX_WEEKS"))
        fn(LONG, "Последний лёгкий бег недели — длинный лёгкий, на weeks недель (0 — отменить). Длина длинного растёт по правилам приложения: до часа, в день с залом — до 45 мин.",
            Param("weeks", "integer", "0–$MAX_WEEKS"))
        fn(STEP, "Шаг веса упражнения в зале человека — только когда он говорит, как меняется вес у снаряда в его зале (гантели через 2,5 кг, тренажёр через 4 кг). По этому шагу приложение прибавляет вес. Не для того, чтобы поднять или снизить вес — для этого set_working_weight.",
            exercise, Param("step_kg", "number", "кг, 0,25–20"))
        fn(WEIGHT, "Рабочий вес упражнения на следующие тренировки, когда человек стабильно может больше или меньше, чем даёт приложение. Человек подтверждает кнопкой. Не больше 25 % за раз, тяжелее — только в пределах расчётного максимума с запасом плана, не ниже 85 % лучшего за 8 недель. Упражнение, которого ещё не было, первая тренировка подбирает сама. На вопрос «почему» сначала объясни.",
            exercise, Param("kg", "number", "кг (для упражнений с весом тела — добавочный вес)"), session)
        fn(REMEMBER, "Запомнить о человеке что-то важное надолго: нравится или нет, травмы, что есть в зале, расписание. Коротко, одной фразой.", Param("text", "string", "до $NOTE_LEN знаков"))
        fn(FORGET, "Забыть запомненное, если оно больше не верно.", Param("text", "string", "запомненная фраза или её часть"))
        fn(SETS, "Подходы упражнения во всех тренировках, где оно есть (изменение плана; человек подтверждает кнопкой).", exercise, Param("sets", "integer", "1–${TrainingRepository.MAX_SETS}"), session)
        fn(REPS, "Диапазон повторов упражнения во всех тренировках, где оно есть (изменение плана; человек подтверждает).", exercise, Param("min", "integer", "от"), Param("max", "integer", "до"), session)
        fn(REST, "Отдых между подходами упражнения во всех тренировках, где оно есть, секунды (изменение плана; человек подтверждает).", exercise,
            Param("seconds", "integer", "${TrainingRepository.MIN_REST}–${TrainingRepository.MAX_REST}"), session)
        fn(SWAP, "Заменить упражнение плана другим насовсем (изменение плана; человек подтверждает).", exercise, Param("new_exercise", "string", "название нового упражнения"))
        fn(REMOVE, "Убрать упражнение из плана насовсем (изменение плана; человек подтверждает).", exercise, session)
        fn(ADD, "Добавить упражнение в силовую тренировку насовсем (изменение плана; человек подтверждает).",
            Param("session", "string", "id или название силовой тренировки"), Param("exercise", "string", "название упражнения"))
        fn(GOAL, "Цель по бегу: дистанция и время, дата забега, если есть (человек подтверждает). Другая дистанция или время больше чем на 5 % начинают отрезки в темпе цели сначала.",
            Param("meters", "integer", "метры"), Param("time", "string", "время, например 5:30 или 21:00"),
            Param("date", "string", "дата забега ГГГГ-ММ-ДД, если есть", required = false))
        fn(LAP, "Длина круга стадиона, где человек бегает (метры), или 0 — бегает не на стадионе (человек подтверждает).", Param("meters", "integer", "100–600 или 0"))
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

    private fun days(n: Int) = "$n ${Ru.plural(n, "день", "дня", "дней")}"
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

    private fun exercise(id: String, s: Settings): Exercise = Catalog[id].let { ex -> s.steps[id]?.let { ex.copy(step = it, fineStep = null) } ?: ex }

    private fun name(id: String) = Catalog.find(id)?.name ?: id

    private fun down(w: Double, step: Double) = floor(w / step + 1e-9) * step

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

        /** What a pause or «Болею» changes besides itself, to put back exactly: the pauses, the day's mark, the block, a lighter week. */
        suspend fun before(): JsonObject {
            val now = repo.store.settingsNow()
            return args(
                "day" to today.toEpochDay(), "feel" to now.feelOn(today).name,
                "from" to now.pauseFrom, "until" to now.pauseUntil, "past" to pauses(now.pastPauses),
                "start" to repo.store.programNow()?.startEpochDay, "deload" to repo.store.liftStateNow().reactiveDeloadStart,
            )
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
                // It changes the next runs and the first gym session: the person says it is today.
                confirm("«Болею» сегодня" + (if (n > 0) " и пауза на ${days(n)}, до ${Ru.date(until)}" else "") + ". Потом первые пробежки лёгкие, первая силовая — полегче.") {
                    val was = before()
                    repo.updateSettings { it.withFeel(today, Feel.SICK) }
                    if (n > 0) repo.pause(today, until)
                    val now = repo.store.settingsNow()
                    CoachCall("undo_sick", JsonObject(was + args("setFrom" to now.pauseFrom, "setUntil" to now.pauseUntil)))
                }
            }
            NOT_SICK -> {
                if (repo.sickEnd(s, today.plusDays(1)) == null) return no("Болезни не отмечено.")
                confirm("Снять «Болею»: дни болезни станут обычным пропуском, пауза закончится.") { repo.notSick(today); null }
            }
            PAUSE -> {
                val n = a.int("days") ?: return no("Не сказано, на сколько дней.")
                if (n !in 1..MAX_PAUSE) return no("Пауза — от 1 до $MAX_PAUSE дней.")
                if (repo.paused(s, today)) return no("Пауза уже идёт.")
                val until = today.plusDays(n - 1L)
                // A week or more moves the lifting block: the person says.
                either(n < 7, "Пауза на ${days(n)}, до ${Ru.date(until)}: тренировок не ждём, пропуски не копятся." +
                    (if (n >= 7) " Силовой блок начнётся заново с недели возвращения." else "")) {
                    val was = before()
                    repo.pause(today, until)
                    CoachCall("undo_pause", JsonObject(was + args("setFrom" to today.toEpochDay(), "setUntil" to until.toEpochDay())))
                }
            }
            RESUME -> {
                if (!repo.paused(s, today)) return no("Паузы сейчас нет.")
                auto("Пауза закончена: план с сегодняшнего дня.") {
                    val was = before()
                    repo.resume(today)
                    val now = repo.store.settingsNow()
                    CoachCall("undo_resume", JsonObject(was + args("setFrom" to now.pauseFrom, "setUntil" to now.pauseUntil)))
                }
            }
            RATE -> {
                val run = when (a.str("which")) { "last_run" -> true; "last_gym" -> false; else -> return no("Не сказано, какую тренировку: последнюю пробежку или силовую.") }
                val rpe = a.int("rpe")?.takeIf { it in 1..10 } ?: return no("Оценка — от 1 до 10.")
                val w = repo.db.workouts().allDone().filter { run == null || (it.kind == Kind.RUN) == run }.maxByOrNull { it.finishedAt ?: it.startedAt }
                    ?: return no(when (run) { true -> "Пробежек ещё нет."; false -> "Силовых ещё нет."; null -> "Тренировок ещё нет." })
                if (w.rpe == rpe) return no("Оценка уже $rpe.")
                auto("Оценка «${w.title}» за ${Ru.date(LocalDate.ofEpochDay(w.epochDay))}: ${w.rpe ?: "—"} → $rpe.") {
                    repo.setRating(w.id, rpe)
                    w.rpe?.let { CoachCall("undo_rate", args("workout" to w.id, "rpe" to it, "expect" to rpe)) }
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
                // A second change in a week, or a run shorter by more than a tenth than two weeks ago, waits for the person.
                either(earlier.isEmpty() && (min > st.easyMinutes || min >= before * EASY_CUT_AUTO), "Лёгкий бег: ${st.easyMinutes} → $min мин. Дальше длина меняется по правилам приложения.") {
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
                val found = find(places(repo), q, null)
                val ex = found.firstOrNull()?.let { Catalog[it.exerciseId] } ?: catalog(q) ?: return no("Не нашёл упражнение «$q».")
                if (ex.load != LoadKind.EXTERNAL && ex.load != LoadKind.BODYWEIGHT_PLUS) return no("У «${ex.name}» нет веса.")
                val was = s.steps[ex.id] ?: ex.step
                if (abs(was - step) < 1e-9) return no("Шаг «${ex.name}» и так ${Ru.kg(step)} кг.")
                // The weights now land on the new step (down): the step must not cut them more than a weight change may.
                val weights = found.filter { it.exerciseId == ex.id }.mapNotNull { repo.db.state().slot(it.tuned.key)?.weight }.filter { it > 0 }
                val cut = weights.maxOfOrNull { (it - down(it, step)) / it } ?: 0.0
                if (cut > WEIGHT_MAX + 1e-9) {
                    val w = weights.maxBy { (it - down(it, step)) / it }
                    return no("С шагом ${Ru.kg(step)} кг вес упал бы с ${Ru.kg(w)} до ${Ru.kg(down(w, step))} кг — слишком много.")
                }
                // A step that is a large part of the weight (20 kg on 60 kg) makes every later jump that large: the person says.
                val coarse = weights.any { step > it * 0.3 }
                val sure = cut <= WEIGHT_AUTO + 1e-9 && !coarse && (weights.isNotEmpty() || step <= 5.0)
                either(sure, "Шаг веса «${ex.name}»: ${Ru.kg(was)} → ${Ru.kg(step)} кг. Вес будет меняться по ${Ru.kg(step)} кг." +
                    (if (cut > 1e-9) " Нынешний вес станет кратен шагу (вниз)." else "")) {
                    repo.setStep(ex.id, step, ex.step)
                    CoachCall("undo_step", args("exercise" to ex.id, "step" to was, "expect" to step))
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
                val w = down(kg, ex.stepAt(kg))
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
                    if (next < floor - 1e-6) return no("Ниже 85 % того, что ты поднимал последние 8 недель — так приложение вес не снижает. При боли или болезни лучше «Так себе» или пауза.")
                }
                // The reps to aim for at the new weight, as the engine sets them when it moves a weight itself.
                val reps = stat?.let { st ->
                    (OneRm.rtfAt(st.e1rm, next) - p.tuned.rir).toInt().coerceIn(maxOf(1, p.tuned.repMin - 1), maxOf(p.tuned.repMin, p.tuned.repMax - 1))
                } ?: if (old == null || w > old) p.tuned.repMin else row.reps
                // A weight always waits for «Принять»: it decides every set from then on.
                confirm("Рабочий вес «${ex.name}» (${p.tuned.repMin}–${p.tuned.repMax}): ${old?.let { Ru.kg(it) } ?: "—"} → ${Ru.kg(w)} кг, цель $reps ${Ru.plural(reps, "повтор", "повтора", "повторов")} со следующей тренировки.") {
                    val now = repo.db.state().slot(p.tuned.key)
                    repo.db.state().upsertSlot(
                        now?.copy(weight = w, exerciseId = p.tuned.exerciseId, reps = reps, stalls = 0, misses = 0, steady = 0)
                            ?: SlotStateEntity(p.tuned.key, p.tuned.exerciseId, w, reps, 0, 0, 0, repo.nowMs()),
                    )
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
                if (s.coachNotes.size >= MAX_NOTES) return no("Запомнено уже $MAX_NOTES — сначала забудь что-то ненужное («Что знает обо мне»).")
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
                        text = "«${name(p.exerciseId)}»: подходов ${cur.sets} → $n."
                        tune = { x -> Triple(n, x.tuned.repMin to x.tuned.repMax, x.tuned.restSec) }
                    }
                    REPS -> {
                        val lo = a.int("min")?.takeIf { it in 1..100 } ?: return no("Повторы — числа от 1 до 100.")
                        val hi = a.int("max")?.takeIf { it in lo..100 } ?: return no("Повторы «до» — не меньше «от».")
                        if (lo == cur.repMin && hi == cur.repMax) return no("Повторы и так $lo–$hi.")
                        field = "reps"
                        text = "«${name(p.exerciseId)}»: повторы ${cur.repMin}–${cur.repMax} → $lo–$hi. Вес в новом диапазоне приложение посчитает по расчётному максимуму."
                        tune = { x -> Triple(x.tuned.sets, lo to hi, x.tuned.restSec) }
                    }
                    else -> {
                        val sec = a.int("seconds")?.takeIf { it in TrainingRepository.MIN_REST..TrainingRepository.MAX_REST }
                            ?: return no("Отдых — от ${TrainingRepository.MIN_REST} до ${TrainingRepository.MAX_REST} секунд.")
                        if (sec == cur.restSec) return no("Отдых и так ${Ru.clock(sec)}.")
                        field = "rest"
                        text = "«${name(p.exerciseId)}»: отдых ${Ru.clock(cur.restSec)} → ${Ru.clock(sec)}."
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
                val found = find(places(repo), q, null)
                if (found.isEmpty()) return no("«$q» нет в плане.")
                val nq = a.str("new_exercise") ?: return no("Не сказано, на что заменить.")
                val to = catalog(nq) ?: return no("Не нашёл упражнение «$nq».")
                val p = found.first()
                if (to.id == p.exerciseId) return no("Это то же упражнение.")
                if (to.load == LoadKind.TIMED != (Catalog[p.exerciseId].load == LoadKind.TIMED)) return no("Упражнение на время меняется только на упражнение на время.")
                confirm("Заменить «${name(p.exerciseId)}» на «${to.name}» во всех тренировках.") {
                    if (p.added) {
                        val ts = found.filter { it.added }.map { it.templateId }
                        for (t in ts) repo.replaceAdded(t, p.exerciseId, to.id)
                        CoachCall("undo_swap_added", args("from" to to.id, "to" to p.exerciseId, "templates" to JsonArray(ts.map { JsonPrimitive(it) })))
                    } else {
                        val was = repo.store.settingsNow()
                        val old = was.swaps[p.planId]
                        repo.setSwap(p.planId, to.id)
                        // A heavy lift swapped for a single-joint one drops the person's own sets, reps and rest: kept to
                        // come back with it.
                        CoachCall(
                            "undo_swap",
                            args(
                                "plan" to p.planId, "to" to old, "expect" to to.id,
                                "sets" to was.sets[p.planId], "reps" to ints(was.reps[p.planId]), "rest" to was.rest[p.planId],
                            ),
                        )
                    }
                }
            }
            REMOVE -> {
                val q = a.str("exercise") ?: return no("Не сказано, какое упражнение.")
                val all = places(repo)
                val found = find(all, q, a.str("session"))
                if (found.isEmpty()) return no("«$q» нет в плане.")
                if (found.any { p -> all.count { it.templateId == p.templateId } <= 1 }) return no("Это последнее упражнение тренировки — его не убрать.")
                confirm("Убрать «${name(found.first().exerciseId)}» из плана" + if (found.size > 1) " (во всех тренировках, где оно есть)." else ".") {
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
                confirm("Цель: ${dist(m)} за ${Ru.clock(sec)}" + (date?.let { ", забег ${Ru.date(it)} ${it.year}" } ?: "") +
                    (og?.let { " (была ${dist(it.meters)} за ${Ru.clock(it.seconds)})" } ?: "") + ". План бега пересчитается." + shift +
                    (if (anew && og != null) " Отрезки в темпе цели начнутся с первой ступени." else "")) {
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
            else -> no("Такого тренер не умеет.")
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
                a.long("start")?.let { start -> repo.store.programNow()?.let { repo.store.setProgram(it.copy(startEpochDay = start)) } }
                repo.store.setLiftState(repo.store.liftStateNow().copy(reactiveDeloadStart = a.long("deload")))
                repo.changed()
                true
            }
            "undo_rate" -> {
                val id = a.long("workout")!!
                if (repo.db.workouts().get(id)?.rpe != a.int("expect")) false else { repo.setRating(id, a.int("rpe")!!); true }
            }
            "undo_easy" -> {
                val now = repo.store.runStateNow()
                if (now.easyMinutes != a.int("expect")) false
                else { repo.store.setRunState(now.copy(easyMinutes = a.int("minutes")!!)); repo.changed(); true }
            }
            "undo_strides", "undo_long" -> {
                val s = repo.store.settingsNow()
                val now = if (call.name == "undo_strides") s.coachStrides else s.coachLong
                if (now != term(a["expect"])) false
                else {
                    repo.updateSettings { if (call.name == "undo_strides") it.copy(coachStrides = term(a["old"])) else it.copy(coachLong = term(a["old"])) }
                    true
                }
            }
            "undo_step" -> {
                val id = a.str("exercise")!!
                val now = repo.store.settingsNow().steps[id] ?: Catalog[id].step
                if (abs(now - a.num("expect")!!) > 1e-9) false else { repo.setStep(id, a.num("step")!!, Catalog[id].step); true }
            }
            "undo_weight" -> {
                val key = a.str("key")!!
                val now = repo.db.state().slot(key) ?: return false
                // Lifted since (the weight or its reps moved on): that is the person's progress now, not the coach's.
                if (now.weight?.let { abs(it - a.num("expect")!!) > 1e-9 } != false || now.reps != a.int("reps")) false
                else {
                    val old = (a["row"] as? JsonObject)?.let { AppJson.decodeFromJsonElement(SlotStateEntity.serializer(), it) }
                    repo.db.state().upsertSlot(old?.copy(updatedAt = now.updatedAt) ?: now.copy(weight = null))
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

    private fun term(e: JsonElement?): CoachTerm? = (e as? JsonObject)?.let { AppJson.decodeFromJsonElement(CoachTerm.serializer(), it) }
}
