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
)

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
) {
    fun program(): Program? = program.decode(Program.serializer())

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

    override suspend fun progress(userId: String): FriendProgress =
        AppJson.decodeFromString(FriendProgress.serializer(), call("friend_progress", buildJsonObject { put("p_user", userId) }))
}

/** Human text for a failed friends call. */
fun describeFriends(e: Throwable): String = when {
    e is NotSignedInException -> "Войди в облако — друзья работают через него."
    e is io.github.jan.supabase.exceptions.RestException && (e.statusCode == 404 || e.error.contains("PGRST202")) ->
        "Друзья ещё не включены на сервере: нужно применить миграцию friends в проекте Supabase."
    e is io.github.jan.supabase.exceptions.RestException && e.error.contains("not friends") -> "Вы больше не друзья."
    else -> Cloud.describe(e)
}
