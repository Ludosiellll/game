package forma.app.data

import forma.app.sync.CoachCall
import forma.core.model.Catalog
import forma.core.model.Exercise
import forma.core.model.ExerciseSearch
import forma.core.model.LoadKind
import forma.core.model.RunGoal
import forma.core.plan.ProgramGenerator
import forma.core.strength.OneRm
import forma.core.strength.SlotSpec
import forma.core.text.Ru
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import kotlin.math.roundToInt

/**
 * What the coach (the AI) may change, and only that: the same edits the app's own screens make, through the repository,
 * within limits checked here — never what the model says unchecked. Two kinds:
 * - [Mode.AUTO]: the day, ratings, running lengths, weight steps and working weights, what to remember — made at once,
 *   each with a way back («Вернуть»);
 * - [Mode.CONFIRM]: the gym plan (sets, reps, rest, exercises), the goal and the stadium — made when the person taps
 *   «Принять».
 * Each change made leaves its way back ([CoachLogEntry.undo]): an internal call of [undo], never offered to the model.
 */
object CoachActions {
    enum class Mode { AUTO, CONFIRM }

    /** A call of the model checked: [Ready] or [Refused]. */
    sealed interface Checked { val call: CoachCall }

    /** A change checked and ready: what it does in words, whether it waits for «Принять», and how to make it. */
    class Ready internal constructor(override val call: CoachCall, val mode: Mode, val text: String, internal val make: suspend () -> CoachCall?) : Checked {
        /** Why the coach makes it, as it said (cut to a sane length). */
        val why: String? get() = (call.args["why"] as? JsonPrimitive)?.content?.trim()?.take(400)?.takeIf { it.isNotEmpty() }
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
        fn(SICK, "Человек болеет (температура, простуда): «Болею» сегодня и пауза на pause_days дней с сегодня. После болезни первые 2 пробежки лёгкие, первая силовая облегчённая.",
            Param("pause_days", "integer", "дней паузы с сегодня, 0–$MAX_SICK_PAUSE"))
        fn(NOT_SICK, "Снять «Болею»: оказалось, не болел — дни болезни станут обычным пропуском. Человек подтверждает кнопкой.")
        fn(PAUSE, "Пауза в плане с сегодня (отпуск, поездка): тренировок не ждём, пропуски не копятся.", Param("days", "integer", "дней, 1–$MAX_PAUSE"))
        fn(RESUME, "Закончить паузу и вернуться к плану сегодня.")
        fn(RATE, "Исправить оценку тяжести последней пробежки или силовой (1–10, как в приложении). Оценка последней пробежки решает её заново: лёгкий бег длиннее или короче, шаг лестницы.",
            Param("which", "string", "last_run или last_gym; не указано — последняя тренировка", required = false, values = listOf("last_run", "last_gym")), Param("rpe", "integer", "1–10"))
        fn(EASY, "Длина лёгкого бега с этого дня (минуты); дальше она меняется по правилам приложения. Не больше потолка под цель, не больше 110 % самой длинной пробежки за 30 дней и не больше +15 % (минимум +5 мин) к нынешней за раз.",
            Param("minutes", "integer", "минуты"))
        fn(STRIDES, "Сколько ускорений после лёгкого бега в дни, когда они есть в плане, на weeks недель (0 — без ускорений).",
            Param("count", "integer", "0–8"), Param("weeks", "integer", "1–$MAX_WEEKS"))
        fn(LONG, "Последний лёгкий бег недели — длинный лёгкий, на weeks недель (0 — отменить). Длина длинного растёт по правилам приложения: до часа, после ног — до 45 мин.",
            Param("weeks", "integer", "0–$MAX_WEEKS"))
        fn(STEP, "Шаг веса упражнения в зале человека — только когда он говорит, как меняется вес у снаряда в его зале (гантели через 2,5 кг, тренажёр через 4 кг). По этому шагу приложение прибавляет вес. Не для того, чтобы поднять или снизить вес — для этого set_working_weight.",
            exercise, Param("step_kg", "number", "кг, 0,25–20"))
        fn(WEIGHT, "Рабочий вес упражнения на следующие тренировки, когда человек может больше или меньше, чем даёт приложение. За раз не больше чем ±30 % от нынешнего; новое упражнение — не выше того, что поддерживает расчётный максимум.",
            exercise, Param("kg", "number", "кг (для упражнений с весом тела — добавочный вес)"))
        fn(REMEMBER, "Запомнить о человеке что-то важное надолго: нравится или нет, травмы, что есть в зале, расписание. Коротко, одной фразой.", Param("text", "string", "до $NOTE_LEN знаков"))
        fn(FORGET, "Забыть запомненное, если оно больше не верно.", Param("text", "string", "запомненная фраза или её часть"))
        fn(SETS, "Подходы упражнения во всех тренировках (изменение плана; человек подтверждает кнопкой).", exercise, Param("sets", "integer", "1–${TrainingRepository.MAX_SETS}"), session)
        fn(REPS, "Диапазон повторов упражнения во всех тренировках (изменение плана; человек подтверждает).", exercise, Param("min", "integer", "от"), Param("max", "integer", "до"), session)
        fn(REST, "Отдых между подходами упражнения, секунды (изменение плана; человек подтверждает).", exercise,
            Param("seconds", "integer", "${TrainingRepository.MIN_REST}–${TrainingRepository.MAX_REST}"), session)
        fn(SWAP, "Заменить упражнение плана другим насовсем (изменение плана; человек подтверждает).", exercise, Param("new_exercise", "string", "название нового упражнения"))
        fn(REMOVE, "Убрать упражнение из плана насовсем (изменение плана; человек подтверждает).", exercise, session)
        fn(ADD, "Добавить упражнение в силовую тренировку насовсем (изменение плана; человек подтверждает).",
            Param("session", "string", "id или название силовой тренировки"), Param("exercise", "string", "название упражнения"))
        fn(GOAL, "Цель по бегу: дистанция и время, дата забега, если есть (человек подтверждает). Новая дистанция начинает отрезки в темпе цели сначала.",
            Param("meters", "integer", "метры"), Param("time", "string", "время, например 5:30 или 21:00"),
            Param("date", "string", "дата забега ГГГГ-ММ-ДД, если есть", required = false))
        fn(LAP, "Длина круга стадиона, где человек бегает (метры), или 0 — бегает не на стадионе (человек подтверждает).", Param("meters", "integer", "100–600 или 0"))
    }

    private class Param(val name: String, val type: String, val text: String, val required: Boolean = true, val values: List<String>? = null)

    /** Every change says why, in the person's terms: the card shows it (the model often calls without any text). */
    // A getter: [declarations] above is built before a stored value here would be.
    private val WHY get() = Param("why", "string", "почему это меняешь — коротко и просто, для человека, по его данным и правилам приложения")

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.replace(',', '.').trim().toDoubleOrNull() }
    private fun JsonObject.int(key: String): Int? = num(key)?.takeIf { abs(it - it.roundToInt()) < 1e-6 }?.roundToInt()
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
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

    /** The places of [query] (an id or a name) in the plan, in [session] when given; empty — not in the plan. */
    private fun find(places: List<Place>, query: String, session: String?): List<Place> {
        val inSession = session?.let { q -> places.filter { it.templateId == q || norm(it.title) == norm(q) }.ifEmpty { null } } ?: places
        val q = norm(query)
        val exact = inSession.filter { it.exerciseId == query || it.planId == query || norm(Catalog[it.exerciseId].name) == q }
        if (exact.isNotEmpty()) return exact
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

    /** [call] checked against the limits and the data now: ready to be made (or to be accepted), or refused with the reason. */
    suspend fun prepare(repo: TrainingRepository, call: CoachCall, today: LocalDate = repo.today()): Checked {
        val a = call.args
        fun no(why: String) = Refused(call, why)
        val log = repo.store.coachLogNow()
        val zone = java.time.ZoneId.systemDefault()
        fun dayOf(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        // Many changes in one day is the model running away: past a few, each waits for the person.
        val crowded = log.count { !it.undone && dayOf(it.at) == today } >= AUTO_A_DAY
        fun auto(text: String, make: suspend () -> CoachCall?) = Ready(call, if (crowded) Mode.CONFIRM else Mode.AUTO, text, make)
        fun confirm(text: String, make: suspend () -> CoachCall?) = Ready(call, Mode.CONFIRM, text, make)
        val s = repo.store.settingsNow()
        return when (call.name) {
            FEEL -> {
                val feel = when (a.str("feel")) { "good" -> Feel.GOOD; "low" -> Feel.LOW; "none" -> Feel.UNSET; else -> return no("Непонятно, какое самочувствие.") }
                val was = s.feelOn(today)
                if (was == Feel.SICK) return no("Сегодня отмечено «Болею» — сначала его снять.")
                if (was == feel) return no("Так уже отмечено.")
                val text = when (feel) {
                    Feel.LOW -> "Сегодня «Так себе»: в силовой меньше подходов и вес легче на 10 %, бег только лёгкий."
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
                val until = today.plusDays(n - 1L)
                auto("«Болею» сегодня" + (if (n > 0) " и пауза на ${days(n)}, до ${Ru.date(until)}" else "") + ". Потом первые пробежки лёгкие, первая силовая — полегче.") {
                    repo.updateSettings { it.withFeel(today, Feel.SICK) }
                    if (n > 0) repo.pause(today, until)
                    CoachCall("undo_sick", args("day" to today.toEpochDay()))
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
                auto("Пауза на ${days(n)}, до ${Ru.date(until)}: тренировок не ждём, пропуски не копятся.") {
                    repo.pause(today, until)
                    CoachCall("undo_pause", args("day" to today.toEpochDay()))
                }
            }
            RESUME -> {
                if (!repo.paused(s, today)) return no("Паузы сейчас нет.")
                val until = s.pauseUntil ?: return no("Паузы сейчас нет.")
                auto("Пауза закончена: план с сегодняшнего дня.") {
                    repo.resume(today)
                    CoachCall("undo_resume", args("day" to today.toEpochDay(), "until" to until))
                }
            }
            RATE -> {
                val run = when (a.str("which")) { "last_run" -> true; "last_gym" -> false; else -> null }
                val rpe = a.int("rpe")?.takeIf { it in 1..10 } ?: return no("Оценка — от 1 до 10.")
                val w = repo.db.workouts().allDone().filter { run == null || (it.kind == Kind.RUN) == run }.maxByOrNull { it.finishedAt ?: it.startedAt }
                    ?: return no(when (run) { true -> "Пробежек ещё нет."; false -> "Силовых ещё нет."; null -> "Тренировок ещё нет." })
                if (w.rpe == rpe) return no("Оценка уже $rpe.")
                auto("Оценка «${w.title}» за ${Ru.date(LocalDate.ofEpochDay(w.epochDay))}: ${w.rpe ?: "—"} → $rpe.") {
                    repo.setRating(w.id, rpe)
                    w.rpe?.let { CoachCall("undo_rate", args("workout" to w.id, "rpe" to it)) }
                }
            }
            EASY -> {
                val min = a.int("minutes") ?: return no("Не сказано, сколько минут.")
                val program = repo.store.programNow() ?: return no("Нет плана.")
                val st = repo.store.runStateNow()
                if (!st.canRunContinuously) return no("Пока бег чередуется с ходьбой: длину ведёт лестница приложения.")
                val middle = repo.store.profileNow()?.runGoal?.middleDistance == true
                val cap = ProgramGenerator.runCaps(program, middle).easyMax
                val longest = st.longestRecent(today.toEpochDay())
                val up = maxOf(st.easyMinutes + 5, (st.easyMinutes * 1.15).toInt())
                val limit = listOfNotNull(cap, longest?.let { (it * 1.10).toInt().coerceAtLeast(st.easyMinutes) }, up).min()
                when {
                    min == st.easyMinutes -> return no("Лёгкий бег и так $min мин.")
                    min < 15 -> return no("Лёгкий бег — не короче 15 минут.")
                    min > limit -> return no("Сейчас можно до $limit мин: " + when (limit) {
                        cap -> "это потолок лёгкого бега под твою цель."
                        up -> "больше чем на 15 % за раз не прибавляем — так меньше риск травм."
                        else -> "не длиннее 110 % самой длинной пробежки за месяц."
                    })
                }
                auto("Лёгкий бег: ${st.easyMinutes} → $min мин. Дальше длина меняется по правилам приложения.") {
                    val now = repo.store.runStateNow()
                    repo.store.setRunState(now.copy(easyMinutes = min))
                    repo.changed()
                    CoachCall("undo_easy", args("minutes" to now.easyMinutes, "expect" to min))
                }
            }
            STRIDES -> {
                val n = a.int("count")?.takeIf { it in 0..8 } ?: return no("Ускорений — от 0 до 8.")
                val w = a.int("weeks")?.takeIf { it in 1..MAX_WEEKS } ?: return no("Срок — от 1 до $MAX_WEEKS недель.")
                val until = today.plusWeeks(w.toLong()).minusDays(1)
                auto((if (n == 0) "Без ускорений" else "$n ${Ru.plural(n, "ускорение", "ускорения", "ускорений")} после лёгкого бега") +
                    " на ${weeks(w)}, до ${Ru.date(until)}, в дни, когда они есть в плане.") {
                    val old = repo.store.settingsNow().coachStrides
                    repo.updateSettings { it.copy(coachStrides = CoachTerm(n, until.toEpochDay())) }
                    CoachCall("undo_strides", args("old" to old?.let { AppJson.encodeToJsonElement(CoachTerm.serializer(), it) }))
                }
            }
            LONG -> {
                val w = a.int("weeks")?.takeIf { it in 0..MAX_WEEKS } ?: return no("Срок — от 0 до $MAX_WEEKS недель.")
                val program = repo.store.programNow() ?: return no("Нет плана.")
                if (program.week.days.none { d -> d.sessions.any { it.kind == forma.core.plan.SessionKind.RUN_EASY } }) return no("В неделе нет лёгкого бега.")
                if (w == 0 && s.coachLong == null) return no("Длинного бега от тренера и так нет.")
                val until = today.plusWeeks(w.toLong()).minusDays(1)
                auto(if (w == 0) "Длинный лёгкий бег от тренера отменён." else "Последний лёгкий бег недели — длинный, на ${weeks(w)}, до ${Ru.date(until)}. После ног — до 45 мин.") {
                    val old = repo.store.settingsNow().coachLong
                    repo.updateSettings { it.copy(coachLong = if (w == 0) null else CoachTerm(1, until.toEpochDay())) }
                    CoachCall("undo_long", args("old" to old?.let { AppJson.encodeToJsonElement(CoachTerm.serializer(), it) }))
                }
            }
            STEP -> {
                val q = a.str("exercise") ?: return no("Не сказано, какое упражнение.")
                val step = a.num("step_kg")?.takeIf { it in 0.25..20.0 } ?: return no("Шаг — от 0,25 до 20 кг.")
                val ex = find(places(repo), q, null).firstOrNull()?.let { Catalog[it.exerciseId] } ?: catalog(q) ?: return no("Не нашёл упражнение «$q».")
                if (ex.load != LoadKind.EXTERNAL && ex.load != LoadKind.BODYWEIGHT_PLUS) return no("У «${ex.name}» нет веса.")
                val was = s.steps[ex.id] ?: ex.step
                if (abs(was - step) < 1e-9) return no("Шаг «${ex.name}» и так ${Ru.kg(step)} кг.")
                // A step that is a large part of the weight itself (10 kg on 12 kg raises) is likely a slip: the person says.
                val weight = find(places(repo), q, null).mapNotNull { repo.db.state().slot(it.tuned.key)?.weight }.maxOrNull()
                val odd = weight != null && weight > 0 && step > weight * 0.3
                (if (odd) ::confirm else ::auto)("Шаг веса «${ex.name}»: ${Ru.kg(was)} → ${Ru.kg(step)} кг. Вес будет меняться по ${Ru.kg(step)} кг.") {
                    repo.setStep(ex.id, step, ex.step)
                    CoachCall("undo_step", args("exercise" to ex.id, "step" to was))
                }
            }
            WEIGHT -> {
                val q = a.str("exercise") ?: return no("Не сказано, какое упражнение.")
                val kg = a.num("kg") ?: return no("Не сказан вес.")
                val found = find(places(repo), q, null)
                if (found.isEmpty()) return no("«$q» нет в плане.")
                val ex = exercise(found.first().exerciseId, s)
                if (ex.load == LoadKind.TIMED) return no("У «${ex.name}» нет рабочего веса.")
                val step = ex.stepAt(kg)
                val w = floor(kg / step + 1e-9) * step
                val keys = found.map { it.tuned.key }.distinct()
                val rows = keys.associateWith { repo.db.state().slot(it) }
                val old = rows.values.mapNotNull { it?.weight }.maxOrNull()
                val bodyWeight = repo.store.profileNow()?.bodyWeightKg ?: 75.0
                val stat = repo.db.state().stat(ex.id)
                var big = old == null
                if (old != null) {
                    val base = forma.core.strength.Progression.effectiveLoad(ex, old, bodyWeight)
                    val next = forma.core.strength.Progression.effectiveLoad(ex, w, bodyWeight)
                    if (base > 0 && abs(next - base) > base * WEIGHT_MAX + 1e-6) return no("За раз — не больше чем на 25 % от нынешних ${Ru.kg(old)} кг.")
                    big = base <= 0 || abs(next - base) > base * WEIGHT_AUTO + 1e-6
                } else if (stat != null) {
                    val reps = found.first().tuned.repMin
                    val max = OneRm.loadFor(stat.e1rm, reps, 0) * 1.05
                    if (forma.core.strength.Progression.effectiveLoad(ex, w, bodyWeight) > max) return no("Выше, чем поддерживает расчётный максимум (${Ru.kg(stat.e1rm)} кг).")
                }
                if (old != null && abs(old - w) < 1e-9) return no("Рабочий вес «${ex.name}» и так ${Ru.kg(w)} кг.")
                // The same exercise moved by the coach this week already: a second move waits for the person.
                val again = log.any { e ->
                    !e.undone && e.undo?.name == "undo_weight" && today.toEpochDay() - dayOf(e.at).toEpochDay() < 7 &&
                        (e.undo.args["rows"] as? JsonArray).orEmpty().any { r -> (r as? JsonObject)?.str("key") in keys }
                }
                (if (big || again) ::confirm else ::auto)("Рабочий вес «${ex.name}»: ${old?.let { Ru.kg(it) } ?: "—"} → ${Ru.kg(w)} кг со следующей тренировки.") {
                    val before = keys.associateWith { repo.db.state().slot(it) }
                    for (p in found.distinctBy { it.tuned.key }) {
                        val row = before[p.tuned.key]
                        repo.db.state().upsertSlot(
                            row?.copy(weight = w, exerciseId = p.tuned.exerciseId)
                                ?: SlotStateEntity(p.tuned.key, p.tuned.exerciseId, w, p.tuned.repMin, 0, 0, 0, repo.nowMs()),
                        )
                    }
                    repo.changed()
                    CoachCall(
                        "undo_weight",
                        args("expect" to w, "rows" to JsonArray(before.map { (k, r) -> args("key" to k, "row" to r?.let { AppJson.encodeToJsonElement(SlotStateEntity.serializer(), it) }) })),
                    )
                }
            }
            REMEMBER -> {
                val text = a.str("text")?.take(NOTE_LEN) ?: return no("Нечего запомнить.")
                if (s.coachNotes.any { norm(it) == norm(text) }) return no("Это уже запомнено.")
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
                val found = find(places(repo), q, a.str("session")).distinctBy { it.key }
                if (found.isEmpty()) return no("«$q» нет в плане.")
                val p = found.first()
                val cur = p.tuned
                val text: String
                val tune: (Place) -> Triple<Int, Pair<Int, Int>, Int>
                when (call.name) {
                    SETS -> {
                        val n = a.int("sets")?.takeIf { it in 1..TrainingRepository.MAX_SETS } ?: return no("Подходов — от 1 до ${TrainingRepository.MAX_SETS}.")
                        if (n == cur.sets) return no("Подходов и так $n.")
                        text = "«${name(p.exerciseId)}»: подходов ${cur.sets} → $n."
                        tune = { x -> Triple(n, x.tuned.repMin to x.tuned.repMax, x.tuned.restSec) }
                    }
                    REPS -> {
                        val lo = a.int("min")?.takeIf { it in 1..100 } ?: return no("Повторы — числа от 1 до 100.")
                        val hi = a.int("max")?.takeIf { it in lo..100 } ?: return no("Повторы «до» — не меньше «от».")
                        if (lo == cur.repMin && hi == cur.repMax) return no("Повторы и так $lo–$hi.")
                        text = "«${name(p.exerciseId)}»: повторы ${cur.repMin}–${cur.repMax} → $lo–$hi. Вес в новом диапазоне приложение посчитает по расчётному максимуму."
                        tune = { x -> Triple(x.tuned.sets, lo to hi, x.tuned.restSec) }
                    }
                    else -> {
                        val sec = a.int("seconds")?.takeIf { it in TrainingRepository.MIN_REST..TrainingRepository.MAX_REST }
                            ?: return no("Отдых — от ${TrainingRepository.MIN_REST} до ${TrainingRepository.MAX_REST} секунд.")
                        if (sec == cur.restSec) return no("Отдых и так ${Ru.clock(sec)}.")
                        text = "«${name(p.exerciseId)}»: отдых ${Ru.clock(cur.restSec)} → ${Ru.clock(sec)}."
                        tune = { x -> Triple(x.tuned.sets, x.tuned.repMin to x.tuned.repMax, sec) }
                    }
                }
                confirm(text + if (found.size > 1) " Во всех тренировках, где оно есть." else "") {
                    val before = repo.store.settingsNow()
                    for (x in found) {
                        val (n, r, rest) = tune(x)
                        repo.tunePlan(x.key, x.planned, n, r, rest)
                    }
                    CoachCall("undo_tune", JsonObject(mapOf("keys" to JsonArray(found.map { x ->
                        args("key" to x.key, "sets" to before.sets[x.key], "reps" to before.reps[x.key]?.let { r -> JsonArray(r.map { JsonPrimitive(it) }) }, "rest" to before.rest[x.key])
                    }))))
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
                        for (x in found.filter { it.added }) repo.replaceAdded(x.templateId, x.exerciseId, to.id)
                        CoachCall("undo_swap_added", args("from" to to.id, "to" to p.exerciseId, "templates" to JsonArray(found.filter { it.added }.map { JsonPrimitive(it.templateId) })))
                    } else {
                        val old = repo.store.settingsNow().swaps[p.planId]
                        repo.setSwap(p.planId, to.id)
                        CoachCall("undo_swap", args("plan" to p.planId, "to" to old))
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
                    for (p in found) if (p.added) repo.removeAdded(p.templateId, p.exerciseId) else repo.setRemoved(p.planId, true)
                    CoachCall("undo_remove", JsonObject(mapOf("places" to JsonArray(found.map { args("template" to it.templateId, "id" to it.planId, "added" to it.added) }))))
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
                val m = a.int("meters")?.takeIf { it in 400..42195 } ?: return no("Дистанция — от 400 м до марафона.")
                val sec = a.str("time")?.let(::seconds) ?: return no("Время — как 5:30 или 1:45:00.")
                val pace = sec / (m / 1000.0)
                if (pace !in 150.0..600.0) return no("Это темп ${Ru.clock(pace.roundToInt())} на км — так не бывает. Проверь время.")
                val date = a.str("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return no("Дата — как 2026-11-20.") }
                if (date != null && (date.isBefore(today) || date.isAfter(today.plusYears(1)))) return no("Дата забега — от сегодня до года вперёд.")
                val goal = RunGoal(m, sec, date?.toEpochDay())
                if (goal == profile.runGoal) return no("Цель и так такая.")
                confirm("Цель: ${dist(m)} за ${Ru.clock(sec)}" + (date?.let { ", забег ${Ru.date(it)} ${it.year}" } ?: "") +
                    (profile.runGoal?.let { " (была ${dist(it.meters)} за ${Ru.clock(it.seconds)})" } ?: "") + ". План бега пересчитается.") {
                    val old = repo.store.profileNow()?.runGoal
                    repo.store.profileNow()?.let { repo.updateProfile(it.copy(runGoal = goal)) }
                    CoachCall("undo_goal", args("goal" to old?.let { AppJson.encodeToJsonElement(RunGoal.serializer(), it) }))
                }
            }
            LAP -> {
                val profile = repo.store.profileNow() ?: return no("Нет анкеты.")
                val m = a.int("meters")?.takeIf { it == 0 || it in 100..600 } ?: return no("Круг — от 100 до 600 м (0 — не на стадионе).")
                val lap = m.takeIf { it > 0 }
                if (lap == profile.lapMeters) return no("Круг и так " + (lap?.let { "$it м." } ?: "не задан."))
                confirm(if (lap == null) "Бегаешь не на стадионе: тренировки по времени и GPS, без кругов." else
                    "Круг стадиона: ${profile.lapMeters?.let { "$it → " } ?: ""}$lap м. Отрезки и время круга пересчитаются.") {
                    val old = repo.store.profileNow()?.lapMeters
                    repo.store.profileNow()?.let { repo.updateProfile(it.copy(lapMeters = lap)) }
                    CoachCall("undo_lap", args("meters" to (old ?: 0)))
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
        if (n.drop(1).any { it >= 60 }) return null
        return n.fold(0) { acc, x -> acc * 60 + x }.takeIf { it > 0 }
    }

    /** Makes [ready] (accepted, or made at once) and logs it with its way back. Returns the log entry. */
    suspend fun make(repo: TrainingRepository, ready: Ready): CoachLogEntry {
        val undo = ready.make()
        val entry = CoachLogEntry(repo.nowMs(), repo.nowMs(), ready.text, undo, why = ready.why)
        repo.store.editCoachLog { it + entry }
        return entry
    }

    /**
     * Takes the change of [entry] back. False when it cannot be any more: the same thing changed since (a run moved the
     * easy run's length on, the weight was lifted since), or it was taken back already.
     */
    suspend fun undo(repo: TrainingRepository, entry: CoachLogEntry): Boolean {
        val call = entry.undo ?: return false
        if (repo.store.coachLogNow().firstOrNull { it.id == entry.id }?.undone != false) return false
        val a = call.args
        val done: Boolean = when (call.name) {
            "undo_feel" -> {
                val day = LocalDate.ofEpochDay(a.num("day")!!.toLong())
                val expect = Feel.valueOf(a.str("expect")!!)
                if (repo.store.settingsNow().feelOn(day) != expect) false
                else { repo.updateSettings { it.withFeel(day, Feel.valueOf(a.str("feel")!!)) }; true }
            }
            "undo_sick" -> {
                val day = LocalDate.ofEpochDay(a.num("day")!!.toLong())
                val s = repo.store.settingsNow()
                if (repo.sickEnd(s, day.plusDays(1)) == null) false else { repo.notSick(repo.today()); true }
            }
            "undo_pause" -> if (!repo.paused(repo.store.settingsNow(), repo.today())) false else { repo.resume(repo.today()); true }
            "undo_resume" -> {
                val until = LocalDate.ofEpochDay(a.num("until")!!.toLong())
                if (until.isBefore(repo.today()) || repo.paused(repo.store.settingsNow(), repo.today())) false else { repo.pause(repo.today(), until); true }
            }
            "undo_rate" -> { repo.setRating(a.num("workout")!!.toLong(), a.int("rpe")!!); true }
            "undo_easy" -> {
                val now = repo.store.runStateNow()
                if (now.easyMinutes != a.int("expect")) false
                else { repo.store.setRunState(now.copy(easyMinutes = a.int("minutes")!!)); repo.changed(); true }
            }
            "undo_strides" -> { repo.updateSettings { it.copy(coachStrides = term(a["old"])) }; true }
            "undo_long" -> { repo.updateSettings { it.copy(coachLong = term(a["old"])) }; true }
            "undo_step" -> {
                val id = a.str("exercise")!!
                repo.setStep(id, a.num("step")!!, Catalog[id].step); true
            }
            "undo_weight" -> {
                val expect = a.num("expect")!!
                val rows = (a["rows"] as JsonArray).map { it as JsonObject }
                // Lifted since: the weight is the person's progress now, not the coach's.
                if (rows.any { r -> repo.db.state().slot(r.str("key")!!)?.weight?.let { abs(it - expect) > 1e-9 } == true }) false
                else {
                    for (r in rows) {
                        val key = r.str("key")!!
                        val old = (r["row"] as? JsonObject)?.let { AppJson.decodeFromJsonElement(SlotStateEntity.serializer(), it) }
                        val now = repo.db.state().slot(key) ?: continue
                        repo.db.state().upsertSlot(old ?: now.copy(weight = null))
                    }
                    repo.changed(); true
                }
            }
            "undo_remember" -> { val t = a.str("text")!!; repo.updateSettings { it.copy(coachNotes = it.coachNotes - t) }; true }
            "undo_forget" -> { val t = a.str("text")!!; repo.updateSettings { it.copy(coachNotes = (it.coachNotes + t).takeLast(MAX_NOTES)) }; true }
            "undo_tune" -> {
                val keys = (a["keys"] as JsonArray).map { it as JsonObject }
                repo.updateSettings { s ->
                    keys.fold(s) { acc, k ->
                        val key = k.str("key")!!
                        val reps = (k["reps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                        acc.copy(
                            sets = k.int("sets")?.let { acc.sets + (key to it) } ?: (acc.sets - key),
                            reps = reps?.let { acc.reps + (key to it) } ?: (acc.reps - key),
                            rest = k.int("rest")?.let { acc.rest + (key to it) } ?: (acc.rest - key),
                        )
                    }
                }
                true
            }
            "undo_swap" -> { repo.setSwap(a.str("plan")!!, a.str("to")); true }
            "undo_swap_added" -> {
                for (t in (a["templates"] as JsonArray)) repo.replaceAdded((t as JsonPrimitive).content, a.str("from")!!, a.str("to")!!)
                true
            }
            "undo_remove" -> {
                for (p in (a["places"] as JsonArray).map { it as JsonObject }) {
                    if ((p["added"] as JsonPrimitive).content == "true") repo.addToPlan(p.str("template")!!, p.str("id")!!) else repo.setRemoved(p.str("id")!!, false)
                }
                true
            }
            "undo_add" -> { repo.removeAdded(a.str("template")!!, a.str("id")!!); true }
            "undo_goal" -> {
                val old = (a["goal"] as? JsonObject)?.let { AppJson.decodeFromJsonElement(RunGoal.serializer(), it) }
                repo.store.profileNow()?.let { repo.updateProfile(it.copy(runGoal = old)) }; true
            }
            "undo_lap" -> {
                val m = a.int("meters")?.takeIf { it > 0 }
                repo.store.profileNow()?.let { repo.updateProfile(it.copy(lapMeters = m)) }; true
            }
            else -> false
        }
        if (done) repo.store.editCoachLog { log -> log.map { if (it.id == entry.id) it.copy(undone = true) else it } }
        return done
    }

    private fun term(e: JsonElement?): CoachTerm? = (e as? JsonObject)?.let { AppJson.decodeFromJsonElement(CoachTerm.serializer(), it) }
}
