package forma.app.sync

import forma.app.data.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One message of the talk with the coach: the person's ("user") or the model's ("model"). */
@Serializable
data class CoachTurn(val role: String, val text: String) {
    val mine: Boolean get() = role == USER

    companion object {
        const val USER = "user"
        const val MODEL = "model"
    }
}

/** A change the coach chose to make (a function it called): its name and arguments as the model gave them. */
@Serializable
data class CoachCall(val name: String, val args: JsonObject = JsonObject(emptyMap()))

/** The coach's answer: what it says, and the changes it chose (checked and made by the app, never by the model). */
data class CoachReply(val text: String, val calls: List<CoachCall> = emptyList()) {
    companion object {
        /** The server's answer ([status], [body]) as a reply; a [CoachException] with the reason when there is none. */
        fun parse(status: Int, body: String): CoachReply {
            val json = runCatching { AppJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val text = (json?.get("text") as? JsonPrimitive)?.content?.trim().orEmpty()
            val calls = (json?.get("calls") as? JsonArray).orEmpty().mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                val name = (o["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CoachCall(name, o["args"] as? JsonObject ?: JsonObject(emptyMap()))
            }
            if (status == 200 && (text.isNotEmpty() || calls.isNotEmpty())) return CoachReply(text, calls)
            val error = (json?.get("error") as? JsonPrimitive)?.content
            throw CoachException(
                when {
                    status == 401 -> CoachException.Reason.NOT_SIGNED_IN
                    // Not deployed yet, or deployed without the key.
                    status == 404 || error == "no_key" -> CoachException.Reason.NOT_SET_UP
                    error == "busy" -> CoachException.Reason.BUSY
                    else -> CoachException.Reason.FAILED
                },
            )
        }
    }
}

/** Who answers the coach's questions: the cloud function in the app, a stand-in in tests. */
interface CoachApi {
    /** [tools]: the changes the coach may make (function declarations); empty — it only talks. */
    suspend fun askCoach(turns: List<CoachTurn>, context: String, tools: JsonArray = JsonArray(emptyList())): CoachReply
}

/** Why the coach did not answer, said to the person. */
class CoachException(val reason: Reason) : Exception(reason.name) {
    enum class Reason(val text: String) {
        NOT_SIGNED_IN("Тренер работает через облако: войди в «Ещё → Облако и резервная копия»."),
        NOT_SET_UP("ИИ-тренер ещё не подключён: владельцу проекта нужно добавить ключ Gemini в Supabase."),
        BUSY("Сейчас слишком много вопросов — попробуй через минуту."),
        OFFLINE("Нет связи с интернетом."),
        SLOW("Тренер не успел ответить — спроси ещё раз."),
        FAILED("Не получилось получить ответ — попробуй ещё раз."),
    }

    companion object {
        /**
         * Why a call to the coach failed on the way: an answer that took too long is not "no internet" (the connection was
         * there, the model was slow), a network that is not there is.
         */
        fun reason(e: Throwable): Reason = when (e) {
            is java.net.SocketTimeoutException, is io.ktor.client.plugins.HttpRequestTimeoutException -> Reason.SLOW
            is java.io.IOException -> Reason.OFFLINE
            else -> Reason.FAILED
        }
    }
}
