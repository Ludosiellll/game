package forma.app.sync

import forma.app.data.AppJson
import forma.app.data.LiftPlan
import forma.app.data.LiftSummary
import forma.core.model.Exercise
import forma.core.plan.Program
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/** The caller's own card: the name friends see, the code to share, what is shared. */
@Serializable
data class FriendMe(
    val name: String = "",
    val code: String = "",
    @SerialName("share_activity") val shareActivity: Boolean = true,
    @SerialName("share_body") val shareBody: Boolean = false,
    /** «Показывать меня всем»: in everybody's list, the progress open to anyone signed in. */
    val listed: Boolean = false,
)

/** Someone in the list of people open to all: who, how active, what they train for. */
@Serializable
data class Person(
    @SerialName("user_id") val userId: String,
    val name: String,
    val friend: Boolean = false,
    @SerialName("last_day") val lastDay: String? = null,
    /** Workouts in the last 30 days. */
    val month: Int = 0,
    val aim: JsonElement? = null,
) {
    fun aim(): forma.core.model.Aim? = aim.decode(forma.core.model.Aim.serializer())
}

/** A set of a person's finished workout, for their strength over time. */
@Serializable
data class PersonSet(val day: String, @SerialName("exercise_id") val exerciseId: String, val weight: Double, val reps: Int) {
    val date: LocalDate get() = LocalDate.parse(day.take(10))
}

/** A person's finished run, for their running over time (no track). */
@Serializable
data class PersonRun(
    val day: String,
    val type: String? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("moving_sec") val movingSec: Int? = null,
    @SerialName("avg_pace") val avgPace: Double? = null,
    @SerialName("work_pace") val workPace: Double? = null,
    val vdot: Double? = null,
) {
    val date: LocalDate get() = LocalDate.parse(day.take(10))
}

/** A message of a conversation; [fromMe]: written by the caller. */
@Serializable
data class Message(
    val id: Long,
    @SerialName("from_me") val fromMe: Boolean,
    val body: String,
    @SerialName("sent_at") val sentAt: String,
    @SerialName("read_at") val readAt: String? = null,
)

/** A conversation of the caller: the other person, the last message, how many are unread, whether one can write. */
@Serializable
data class Conversation(
    @SerialName("user_id") val userId: String,
    val name: String,
    val last: String = "",
    @SerialName("last_at") val lastAt: String? = null,
    @SerialName("from_me") val fromMe: Boolean = false,
    val unread: Int = 0,
    @SerialName("can_write") val canWrite: Boolean = true,
    val blocked: Boolean = false,
)

@Serializable
data class UnreadFrom(@SerialName("user_id") val userId: String, val name: String, val count: Int, @SerialName("last_id") val lastId: Long, val last: String? = null)

/** Unread messages to the caller: the badge and the notification. */
@Serializable
data class Unread(val count: Int = 0, val from: List<UnreadFrom> = emptyList()) {
    val lastId: Long get() = from.maxOfOrNull { it.lastId } ?: 0
}

/** A friend or a request; [state] is friend, incoming or outgoing. */
@Serializable
data class Friend(
    @SerialName("user_id") val userId: String,
    val name: String,
    val state: String,
    val since: String? = null,
    @SerialName("last_day") val lastDay: String? = null,
) {
    val accepted: Boolean get() = state == "friend"
    val incoming: Boolean get() = state == "incoming"
}

@Serializable
data class FeedRun(
    val type: String? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("moving_sec") val movingSec: Int? = null,
    @SerialName("avg_pace") val avgPace: Double? = null,
    val vdot: Double? = null,
)

@Serializable
data class FeedSet(
    @SerialName("exercise_id") val exerciseId: String,
    val position: Int,
    val weight: Double,
    val reps: Int,
)

/** A friend's finished workout. */
@Serializable
data class FeedItem(
    @SerialName("user_id") val userId: String,
    val name: String,
    @SerialName("workout_id") val workoutId: String,
    val day: String,
    val kind: String,
    val title: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("duration_sec") val durationSec: Int? = null,
    val rpe: Int? = null,
    val deload: Boolean = false,
    val plan: JsonElement? = null,
    val summary: JsonElement? = null,
    val run: FeedRun? = null,
    val sets: List<FeedSet> = emptyList(),
) {
    val date: LocalDate get() = LocalDate.parse(day.take(10))
    val isLift: Boolean get() = kind == "LIFT"

    /** The session as it was planned (exercises, sets, rep ranges): what "do the same" copies. */
    fun liftPlan(): LiftPlan? = plan.decode(LiftPlan.serializer())

    fun liftSummary(): LiftSummary? = summary.decode(LiftSummary.serializer())

    /** Best set (heaviest, then most reps) per exercise, in the order of the session. */
    fun topSets(): List<FeedSet> = sets.groupBy { it.position }.toSortedMap().values
        .map { s -> s.maxWith(compareBy<FeedSet> { it.weight }.thenBy { it.reps }) }
}

@Serializable
data class FriendStat(@SerialName("exercise_id") val exerciseId: String, val e1rm: Double, val best: Double)

@Serializable
data class FriendWeek(val week: String, val lifts: Int, val runs: Int)

@Serializable
data class FriendBody(val day: String, val kg: Double)

/** One friend's progress, as far as they share it. */
@Serializable
data class FriendProgress(
    val name: String,
    val shared: Boolean,
    val stats: List<FriendStat> = emptyList(),
    val vdot: Double? = null,
    val weeks: List<FriendWeek> = emptyList(),
    val program: JsonElement? = null,
    val custom: JsonElement? = null,
    val body: List<FriendBody>? = null,
    val listed: Boolean = false,
    val friend: Boolean = true,
    @SerialName("can_write") val canWrite: Boolean = false,
    /** A year of sets and runs, for the charts (person_progress). */
    val sets: List<PersonSet> = emptyList(),
    val runs: List<PersonRun> = emptyList(),
    @SerialName("run_goal") val runGoal: JsonElement? = null,
    @SerialName("lift_goal") val liftGoal: JsonElement? = null,
) {
    fun program(): Program? = program.decode(Program.serializer())

    fun runGoal(): forma.core.model.RunGoal? = runGoal.decode(forma.core.model.RunGoal.serializer())

    fun liftGoal(): forma.core.model.LiftGoal? = liftGoal.decode(forma.core.model.LiftGoal.serializer())

    fun customExercises(): List<Exercise> = custom.decode(ListSerializer(Exercise.serializer())).orEmpty()
}

private fun <T> JsonElement?.decode(s: kotlinx.serialization.KSerializer<T>): T? =
    if (this == null || this is JsonNull) null else runCatching { AppJson.decodeFromJsonElement(s, this) }.getOrNull()

enum class AddResult { REQUESTED, ACCEPTED, ALREADY, NOT_FOUND, SELF }

/** Friends through the server functions of the friends migration in supabase/migrations. */
interface FriendsApi {
    suspend fun me(name: String? = null): FriendMe
    suspend fun setSharing(activity: Boolean, body: Boolean): FriendMe
    suspend fun add(code: String): AddResult
    suspend fun answer(userId: String, accept: Boolean)
    suspend fun remove(userId: String)
    suspend fun list(): List<Friend>
    suspend fun feed(before: String? = null, limit: Int = 30, userId: String? = null): List<FeedItem>
    suspend fun progress(userId: String): FriendProgress

    // People open to all and messages (the people migration). Defaults for fakes that do not need them.
    suspend fun setListed(listed: Boolean): FriendMe = throw UnsupportedOperationException()
    suspend fun people(): List<Person> = throw UnsupportedOperationException()
    suspend fun personFeed(userId: String, before: String? = null, limit: Int = 30): List<FeedItem> = throw UnsupportedOperationException()
    suspend fun send(userId: String, body: String): Message = throw UnsupportedOperationException()
    suspend fun conversations(): List<Conversation> = throw UnsupportedOperationException()
    suspend fun messages(userId: String, before: Long? = null, limit: Int = 50): List<Message> = throw UnsupportedOperationException()
    suspend fun markRead(userId: String): Int = throw UnsupportedOperationException()
    suspend fun unread(): Unread = throw UnsupportedOperationException()
    suspend fun block(userId: String, block: Boolean): Unit = throw UnsupportedOperationException()
    /** A complaint about [userId] in a few words: read by the app's owner, never shown back. */
    suspend fun report(userId: String, reason: String): Unit = throw UnsupportedOperationException()
}

/** [ensureSession] makes sure a signed-in session with a fresh token is there before each call. */
class SupabaseFriendsApi(private val client: SupabaseClient, private val ensureSession: suspend () -> Unit) : FriendsApi {
    private suspend fun call(function: String, params: JsonObject = JsonObject(emptyMap())): String {
        ensureSession()
        return client.postgrest.rpc(function, params).data
    }

    override suspend fun me(name: String?): FriendMe =
        AppJson.decodeFromString(FriendMe.serializer(), call("my_profile", buildJsonObject { if (name != null) put("p_name", name) }))

    override suspend fun setSharing(activity: Boolean, body: Boolean): FriendMe = AppJson.decodeFromString(
        FriendMe.serializer(),
        call("set_sharing", buildJsonObject { put("p_activity", activity); put("p_body", body) }),
    )

    override suspend fun add(code: String): AddResult {
        val r = AppJson.decodeFromString(String.serializer(), call("add_friend", buildJsonObject { put("p_code", code) }))
        return AddResult.entries.firstOrNull { it.name.equals(r, ignoreCase = true) } ?: AddResult.NOT_FOUND
    }

    override suspend fun answer(userId: String, accept: Boolean) {
        call("answer_friend", buildJsonObject { put("p_user", userId); put("p_accept", accept) })
    }

    override suspend fun remove(userId: String) {
        call("remove_friend", buildJsonObject { put("p_user", userId) })
    }

    override suspend fun list(): List<Friend> = AppJson.decodeFromString(ListSerializer(Friend.serializer()), call("friends"))

    override suspend fun feed(before: String?, limit: Int, userId: String?): List<FeedItem> = AppJson.decodeFromString(
        ListSerializer(FeedItem.serializer()),
        call("friend_feed", buildJsonObject {
            if (before != null) put("p_before", before)
            put("p_limit", limit)
            if (userId != null) put("p_user", userId)
        }),
    )

    // A friend's or a listed person's page: person_progress checks either.
    override suspend fun progress(userId: String): FriendProgress =
        AppJson.decodeFromString(FriendProgress.serializer(), call("person_progress", buildJsonObject { put("p_user", userId) }))

    override suspend fun setListed(listed: Boolean): FriendMe =
        AppJson.decodeFromString(FriendMe.serializer(), call("set_listed", buildJsonObject { put("p_listed", listed) }))

    override suspend fun people(): List<Person> = AppJson.decodeFromString(ListSerializer(Person.serializer()), call("people"))

    override suspend fun personFeed(userId: String, before: String?, limit: Int): List<FeedItem> = AppJson.decodeFromString(
        ListSerializer(FeedItem.serializer()),
        call("person_feed", buildJsonObject {
            put("p_user", userId)
            if (before != null) put("p_before", before)
            put("p_limit", limit)
        }),
    )

    override suspend fun send(userId: String, body: String): Message = AppJson.decodeFromString(
        Message.serializer(), call("send_message", buildJsonObject { put("p_to", userId); put("p_body", body) }),
    )

    override suspend fun conversations(): List<Conversation> = AppJson.decodeFromString(ListSerializer(Conversation.serializer()), call("conversations"))

    override suspend fun messages(userId: String, before: Long?, limit: Int): List<Message> = AppJson.decodeFromString(
        ListSerializer(Message.serializer()),
        call("messages_with", buildJsonObject {
            put("p_user", userId)
            if (before != null) put("p_before", before)
            put("p_limit", limit)
        }),
    )

    override suspend fun markRead(userId: String): Int = call("mark_read", buildJsonObject { put("p_user", userId) }).trim().toIntOrNull() ?: 0

    override suspend fun unread(): Unread = AppJson.decodeFromString(Unread.serializer(), call("unread"))

    override suspend fun block(userId: String, block: Boolean) {
        call("block_user", buildJsonObject { put("p_user", userId); put("p_block", block) })
    }

    override suspend fun report(userId: String, reason: String) {
        call("report_user", buildJsonObject { put("p_user", userId); put("p_reason", reason) })
    }
}

/** Human text for a failed friends call. */
fun describeFriends(e: Throwable): String = when {
    e is NotSignedInException -> "Войди в облако — друзья работают через него."
    e is io.github.jan.supabase.exceptions.RestException && (e.statusCode == 404 || e.error.contains("PGRST202")) ->
        "Друзья ещё не включены на сервере: нужно применить миграцию friends в проекте Supabase."
    e is io.github.jan.supabase.exceptions.RestException && e.error.contains("not friends") -> "Вы больше не друзья."
    e is io.github.jan.supabase.exceptions.RestException && e.error.contains("not visible") -> "Этот человек больше не открыт для всех."
    e is io.github.jan.supabase.exceptions.RestException && e.error.contains("cannot write") -> "Написать нельзя: человек закрыл профиль или заблокировал переписку."
    e is io.github.jan.supabase.exceptions.RestException && e.error.contains("too many messages") -> "Слишком много сообщений подряд. Подожди минуту."
    e is io.github.jan.supabase.exceptions.RestException && e.error.contains("bad message") -> "Сообщение пустое или длиннее 1000 знаков."
    e is io.github.jan.supabase.exceptions.RestException && e.error.contains("too many reports") -> "Сегодня жалоб уже много. Если нужно, заблокируй человека."
    e is io.github.jan.supabase.exceptions.RestException && e.error.contains("bad reason") -> "Напиши коротко, что случилось (до 500 знаков)."
    e is io.github.jan.supabase.exceptions.HttpRequestException || e is io.ktor.client.plugins.HttpRequestTimeoutException || e is java.io.IOException ->
        "Нет связи с сервером: ничего не изменилось. Проверь интернет и попробуй ещё раз."
    else -> Cloud.describe(e)
}
