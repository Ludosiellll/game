package forma.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import forma.app.BuildConfig
import forma.app.data.TrainingRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** What the cloud screens show. */
data class CloudState(
    /** This build has a Supabase project to talk to. */
    val configured: Boolean,
    /** The stored session has been checked. */
    val ready: Boolean = false,
    val email: String? = null,
    val busy: Boolean = false,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    /** The phone's data belongs to another account ([previousEmail]): the sync waits for a decision. */
    val otherAccount: Boolean = false,
    val previousEmail: String? = null,
    /** The account is being deleted: no sync meanwhile. */
    val deleting: Boolean = false,
)

class NotSignedInException : IllegalStateException("not signed in")

/**
 * The cloud copy in Supabase: sign-in with a one-time code from email, and the sync between the phone
 * and the project's tables. Everything keeps working without it; the sync catches up when online.
 */
class Cloud(private val context: Context, private val repo: TrainingRepository) : CoachApi {
    val configured: Boolean = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_KEY.isNotBlank()

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(
        CloudState(
            configured = configured,
            lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 },
            lastError = prefs.getString(KEY_LAST_ERROR, null),
        ),
    )
    val state: StateFlow<CloudState> = _state.asStateFlow()

    private val client: SupabaseClient? = if (!configured) null else createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
        httpEngine = OkHttp.create()
        requestTimeout = 30.seconds
        install(Auth) {
            sessionManager = PrefsSessionManager(context)
            codeVerifierCache = MemoryCodeVerifierCache()
            // Background sync must find the session even when the app is not in the foreground.
            enableLifecycleCallbacks = false
        }
        install(Postgrest)
    }

    private val engine = client?.let { SyncEngine(SupabaseApi(it), repo.db, repo.store) }

    /** Friends through the server functions; null when this build has no cloud. */
    val friends: FriendsApi? = client?.let { c -> SupabaseFriendsApi(c) { currentUser(c) ?: throw NotSignedInException() } }

    init {
        val c = client
        if (c == null) {
            _state.update { it.copy(ready = true) }
        } else {
            scope.launch {
                c.auth.sessionStatus.collect { st ->
                    _state.update {
                        it.copy(
                            ready = st !is SessionStatus.Initializing,
                            email = (st as? SessionStatus.Authenticated)?.session?.user?.email ?: if (st is SessionStatus.NotAuthenticated) null else it.email,
                        )
                    }
                }
            }
        }
    }

    val signedIn: Boolean get() = prefs.getBoolean(KEY_SIGNED_IN, false)

    /** Set while the account is being deleted: a sync then would upload the phone's data to it again. */
    @Volatile private var deleting = false

    /** What friends see has been explained on the friends screen (once for the account signed in, not on every visit). */
    var friendsExplained: Boolean
        get() = prefs.getBoolean(KEY_FRIENDS_EXPLAINED, false)
        set(value) { prefs.edit().putBoolean(KEY_FRIENDS_EXPLAINED, value).apply() }

    /**
     * Creates an account. Returns false when the project still requires email confirmation:
     * then the user confirms by the link in the email and signs in afterwards.
     */
    suspend fun signUp(email: String, password: String): Boolean {
        val c = client ?: throw IllegalStateException("cloud is not configured")
        c.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        if (c.auth.currentSessionOrNull() == null) return false
        onSignedIn()
        return true
    }

    suspend fun signIn(email: String, password: String) {
        val c = client ?: throw IllegalStateException("cloud is not configured")
        c.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        onSignedIn()
    }

    /**
     * Whether this is the account's first sync on this phone is decided by the sync itself (it remembers the account
     * the data belongs to): signing in again to the same account simply carries on.
     */
    private fun onSignedIn() {
        prefs.edit().putBoolean(KEY_SIGNED_IN, true).apply()
        _state.update { it.copy(otherAccount = false) }
        schedulePeriodic()
    }

    /**
     * Signs out on this phone. Data on the phone and in the cloud stays, and so does what the cloud has seen:
     * signing in again continues where it was instead of taking the older cloud copy over newer work.
     */
    suspend fun signOut() {
        val c = client ?: return
        runCatching { c.auth.signOut() }.onFailure { c.auth.clearSession() }
        prefs.edit().remove(KEY_SIGNED_IN).remove(KEY_LAST_SYNC).remove(KEY_LAST_ERROR).remove(KEY_FRIENDS_EXPLAINED).apply()
        _state.update { it.copy(email = null, lastSyncAt = null, lastError = null, otherAccount = false) }
        cancelSync()
    }

    private fun cancelSync() {
        runCatching {
            WorkManager.getInstance(context).run {
                cancelUniqueWork(WORK_SOON)
                cancelUniqueWork(WORK_PERIODIC)
            }
        }
    }

    /** Uploads and downloads changes now. */
    suspend fun syncNow(): Result<SyncResult> {
        val c = client ?: return Result.failure(IllegalStateException("cloud is not configured"))
        val engine = engine ?: return Result.failure(IllegalStateException("cloud is not configured"))
        // During the deletion the account is as good as gone: nothing to retry either.
        if (deleting) return Result.failure(NotSignedInException())
        _state.update { it.copy(busy = true) }
        return try {
            val user = currentUser(c) ?: throw NotSignedInException()
            val result = withContext(Dispatchers.IO) { engine.run(user.id) }
            // Own exercises may have come from another phone, and a plan laid out by an older version of the app.
            repo.refreshCustom()
            repo.refreshPlan()
            val now = System.currentTimeMillis()
            prefs.edit().putString(KEY_LAST_EMAIL, user.email).putBoolean(KEY_SIGNED_IN, true)
                .putLong(KEY_LAST_SYNC, now).remove(KEY_LAST_ERROR).apply()
            _state.update { it.copy(lastSyncAt = now, lastError = null, otherAccount = false) }
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountMismatchException) {
            _state.update { it.copy(otherAccount = true, previousEmail = prefs.getString(KEY_LAST_EMAIL, null)) }
            Result.failure(e)
        } catch (e: Exception) {
            if (e !is NotSignedInException) {
                val message = describe(e)
                prefs.edit().putString(KEY_LAST_ERROR, message).apply()
                _state.update { it.copy(lastError = message) }
            }
            Result.failure(e)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    /**
     * The phone holds another account's data: it is removed from the phone (that account's cloud copy stays), and
     * this account's copy comes down instead.
     */
    suspend fun replaceWithThisAccount(): Result<SyncResult> {
        forma.app.data.DataLock.mutex.withLock { repo.clearPhone() }
        forma.app.run.RunSession.clear()
        _state.update { it.copy(otherAccount = false) }
        return syncNow()
    }

    /** The id of the account signed in on this phone, from the stored session (no network); null when signed out. */
    suspend fun accountId(): String? {
        val c = client ?: return null
        return runCatching {
            c.auth.awaitInitialization()
            c.auth.currentSessionOrNull()?.user?.id
        }.getOrNull()
    }

    /**
     * Deletes the account and everything of it in the cloud (workouts, progress, documents, friends) by the server
     * function of the delete_account migration, then signs out. The data on this phone stays and is no one's account's
     * any more: signing in to another account later uploads it there.
     * No sync meanwhile: the background ones are cancelled and new ones refused before the call, and one going on
     * finishes first (the lock). Not deleted (or not known whether), the background sync comes back.
     */
    // Not cancelled with the screen it was asked on: once the request is out, the phone's side must be finished too.
    suspend fun deleteAccount() = withContext(kotlinx.coroutines.NonCancellable) {
        val c = client ?: throw IllegalStateException("cloud is not configured")
        currentUser(c) ?: throw NotSignedInException()
        deleting = true
        _state.update { it.copy(deleting = true) }
        try {
            cancelSync()
            try {
                forma.app.data.DataLock.mutex.withLock { c.postgrest.rpc("delete_my_account", JsonObject(emptyMap())) }
            } catch (e: Exception) {
                // No answer may still mean deleted: nothing on the phone counts as uploaded any more, so a later "replace
                // with this account's data" warns about every workout instead of wiping them silently.
                forma.app.data.DataLock.mutex.withLock { repo.db.sync().reset() }
                schedulePeriodic()
                syncSoon()
                throw e
            }
            prefs.edit().remove(KEY_LAST_EMAIL).apply()
            signOut()
            // After the sign-out: a sync that slipped in has finished by now (the lock), and what it saw is forgotten.
            forma.app.data.DataLock.mutex.withLock { repo.db.sync().reset() }
        } finally {
            deleting = false
            _state.update { it.copy(deleting = false) }
        }
    }

    /** Plain HTTP for the coach function: the key to the model stays on the server, the user's token goes with the call. */
    private val http by lazy { io.ktor.client.HttpClient(OkHttp) { install(io.ktor.client.plugins.HttpTimeout) { requestTimeoutMillis = 60_000 } } }

    /**
     * The coach's answer to the last of [turns] (the person's), with [context] — the summary of the person's training and
     * of the app — from the `coach` function, which asks Gemini. Throws [CoachException] with the reason it did not answer.
     */
    override suspend fun askCoach(turns: List<CoachTurn>, context: String, tools: kotlinx.serialization.json.JsonArray): CoachReply {
        val c = client ?: throw CoachException(CoachException.Reason.NOT_SET_UP)
        // The session refreshed on the way: offline, or signed out elsewhere (the refresh refused), said as such.
        val user = try {
            if (signedIn) currentUser(c) else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CoachException(
                when (e) {
                    is IOException, is HttpRequestException, is HttpRequestTimeoutException -> CoachException.Reason.OFFLINE
                    is RestException, is NoSessionFoundException -> CoachException.Reason.NOT_SIGNED_IN
                    else -> CoachException.Reason.FAILED
                },
            )
        }
        if (user == null) throw CoachException(CoachException.Reason.NOT_SIGNED_IN)
        val token = c.auth.currentAccessTokenOrNull() ?: throw CoachException(CoachException.Reason.NOT_SIGNED_IN)
        val body = kotlinx.serialization.json.buildJsonObject {
            put("context", kotlinx.serialization.json.JsonPrimitive(context))
            put("messages", forma.app.data.AppJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(CoachTurn.serializer()), turns))
            if (tools.isNotEmpty()) put("tools", tools)
        }
        val res = try {
            http.post("${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/coach") {
                header("Authorization", "Bearer $token")
                header("apikey", BuildConfig.SUPABASE_KEY)
                contentType(io.ktor.http.ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CoachException(if (e is java.io.IOException || e is HttpRequestTimeoutException) CoachException.Reason.OFFLINE else CoachException.Reason.FAILED)
        }
        return CoachReply.parse(res.status.value, res.bodyAsText())
    }

    /** The signed-in user with a fresh access token, or null. */
    private suspend fun currentUser(c: SupabaseClient): UserInfo? {
        val auth = c.auth
        auth.awaitInitialization()
        var session = auth.currentSessionOrNull()
        if (session == null) {
            auth.loadFromStorage()
            session = auth.currentSessionOrNull() ?: return null
        }
        if (session.expiresAt < Clock.System.now() + 60.seconds) {
            auth.refreshCurrentSession()
            session = auth.currentSessionOrNull() ?: return null
        }
        return session.user ?: auth.retrieveUserForCurrentSession(updateSession = true)
    }

    /** Sync a few seconds after a change, once the phone is online. */
    fun syncSoon() {
        if (!configured || !signedIn) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online())
            .setInitialDelay(5, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Appended, not dropped, while a sync runs: a change made during it must go up too.
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(WORK_SOON, ExistingWorkPolicy.APPEND_OR_REPLACE, request) }
    }

    /** A sync twice a day in the background, so other phones' changes arrive and the project stays awake. */
    fun schedulePeriodic() {
        if (!configured || !signedIn) return
        val request = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS).setConstraints(online()).build()
        runCatching { WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request) }
    }

    private fun online() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    companion object {
        private const val PREFS = "forma_sync"
        private const val KEY_SIGNED_IN = "signed_in"
        private const val KEY_LAST_EMAIL = "last_email"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_FRIENDS_EXPLAINED = "friends_explained"
        private const val WORK_SOON = "forma-sync-soon"
        private const val WORK_PERIODIC = "forma-sync-periodic"

        /** Human text for a failed sync. */
        fun describe(e: Throwable): String = when (e) {
            is NotSignedInException -> "Нужно войти заново."
        is AccountMismatchException -> "На телефоне данные другого аккаунта — синхронизация остановлена."
            is HttpRequestException, is HttpRequestTimeoutException, is IOException -> "Нет связи с сервером. Синхронизация повторится сама, когда появится интернет."
            is RestException -> when {
                e.statusCode == 401 || e.statusCode == 403 -> "Сервер не принял вход. Выйди и войди снова."
                e.statusCode >= 500 -> "Сервер Supabase не отвечает. Бесплатный проект засыпает, если им не пользовались неделю. Открой его в панели Supabase, и он проснётся."
                else -> "Ошибка сервера: ${e.error}"
            }
            else -> e.message ?: e::class.simpleName ?: "Неизвестная ошибка"
        }

        /**
         * Human text for a failed account deletion: a server without the function says so plainly. Without an answer
         * (offline, a timeout) the account may be deleted already or not: it is tried again by hand, never by itself.
         */
        fun describeDelete(e: Throwable): String = when {
            e is RestException && (e.statusCode == 404 || e.error.contains("PGRST202") || e.error.contains("delete_my_account")) ->
                "Ничего не удалено: на сервере ещё нет удаления аккаунта. Нужно применить миграцию delete_account в проекте Supabase."
            e is HttpRequestException || e is HttpRequestTimeoutException || e is IOException ->
                "Сервер не ответил, поэтому неясно, удалён ли аккаунт. Попробуй удалить ещё раз, когда появится интернет. Нажать снова безопасно."
            else -> "Аккаунт не удалён. " + describe(e)
        }

        /** Human text for a failed sign-in or sign-up. */
        fun describeSignIn(e: Throwable): String = when {
            e is AuthRestException -> when (e.error) {
                "invalid_credentials" -> "Неверная почта или пароль."
                "email_exists", "user_already_exists" -> "Аккаунт с этой почтой уже есть — войди."
                "weak_password" -> "Пароль слишком простой. Нужно минимум 8 символов, лучше с цифрами."
                "email_not_confirmed" ->
                    "Почта ещё не подтверждена. Открой письмо от Supabase и нажми на ссылку. Или выключи подтверждение почты в настройках проекта."
                "email_address_not_authorized" ->
                    "Supabase не может отправить письмо на эту почту. В проекте нужно выключить подтверждение почты: Authentication → Sign In / Providers → Email → Confirm email."
                "over_email_send_rate_limit", "over_request_rate_limit" -> "Слишком много попыток подряд. Подожди немного и попробуй снова."
                "email_address_invalid", "validation_failed" -> "Проверь адрес почты."
                "signup_disabled" -> "Регистрация новых аккаунтов в проекте выключена."
                else -> "Не получилось: ${e.error}"
            }
            else -> describe(e)
        }
    }
}

/** Session tokens in a private file that is excluded from Android backups (see backup rules). */
private class PrefsSessionManager(context: Context) : SessionManager {
    private val prefs = context.getSharedPreferences("forma_auth", Context.MODE_PRIVATE)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun saveSession(session: UserSession) {
        prefs.edit().putString(KEY, json.encodeToString(UserSession.serializer(), session)).apply()
    }

    override suspend fun loadSession(): UserSession {
        val text = prefs.getString(KEY, null) ?: throw NoSessionFoundException()
        return json.decodeFromString(UserSession.serializer(), text)
    }

    override suspend fun deleteSession() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "session"
    }
}

/** The sync's view of Supabase: PostgREST tables filtered by row level security. */
private class SupabaseApi(private val client: SupabaseClient) : CloudApi {
    override suspend fun upsert(table: String, conflict: String, rows: List<JsonObject>) {
        if (rows.isEmpty()) return
        client.from(table).upsert(JsonArray(rows)) { onConflict = conflict }
    }

    override suspend fun markDeleted(table: String, keyColumn: String, keys: List<String>) {
        if (keys.isEmpty()) return
        client.from(table).update(buildJsonObject { put("deleted", true) }) {
            filter { isIn(keyColumn, keys) }
        }
    }

    override suspend fun changes(table: String, keyColumn: String, since: String?, after: Pair<String, String>?, limit: Int): List<JsonObject> =
        client.from(table).select {
            filter {
                if (after != null) {
                    // (updated_at, key) > (t, k): the next rows in order, whatever changed on the earlier pages.
                    gte("updated_at", after.first)
                    or {
                        gt("updated_at", after.first)
                        gt(keyColumn, after.second)
                    }
                } else if (since != null) {
                    gt("updated_at", since)
                }
            }
            order("updated_at", Order.ASCENDING)
            order(keyColumn, Order.ASCENDING)
            range(0L, (limit - 1).toLong())
        }.decodeAs<JsonArray>().map { it.jsonObject }
}
