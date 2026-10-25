package forma.app.sync

import kotlinx.serialization.Serializable

/** One message of the talk with the coach: the person's ("user") or the model's ("model"). */
@Serializable
data class CoachTurn(val role: String, val text: String) {
    val mine: Boolean get() = role == USER

    companion object {
        const val USER = "user"
        const val MODEL = "model"
    }
}

/** Why the coach did not answer, said to the person. */
class CoachException(val reason: Reason) : Exception(reason.name) {
    enum class Reason(val text: String) {
        NOT_SIGNED_IN("Тренер работает через облако: войди в «Ещё → Облако и резервная копия»."),
        NOT_SET_UP("ИИ-тренер ещё не подключён: владельцу проекта нужно добавить ключ Gemini в Supabase."),
        BUSY("Сейчас слишком много вопросов — попробуй через минуту."),
        OFFLINE("Нет связи с интернетом."),
        FAILED("Не получилось получить ответ — попробуй ещё раз."),
    }
}
