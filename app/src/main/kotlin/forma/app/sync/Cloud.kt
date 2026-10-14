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
)

class NotSignedInException : IllegalStateException("not signed in")

/**
 * The cloud copy in Supabase: sign-in with a one-time code from email, and the sync between the phone
 * and the project's tables. Everything keeps working without it; the sync catches up when online.
 */
class Cloud(private val context: Context, private val repo: TrainingRepository) {
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
        prefs.edit().remove(KEY_SIGNED_IN).remove(KEY_LAST_SYNC).remove(KEY_LAST_ERROR).apply()
        _state.update { it.copy(email = null, lastSyncAt = null, lastError = null, otherAccount = false) }
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
     */
    suspend fun deleteAccount() {
        val c = client ?: throw IllegalStateException("cloud is not configured")
        currentUser(c) ?: throw NotSignedInException()
        c.postgrest.rpc("delete_my_account", JsonObject(emptyMap()))
        forma.app.data.DataLock.mutex.withLock { repo.db.sync().reset() }
        prefs.edit().remove(KEY_LAST_EMAIL).apply()
        signOut()
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
        private const val WORK_SOON = "forma-sync-soon"
        private const val WORK_PERIODIC = "forma-sync-periodic"

        /** Human text for a failed sync. */
        fun describe(e: Throwable): String = when (e) {
            is NotSignedInException -> "Нужно войти заново."
        is AccountMismatchException -> "На телефоне данные другого аккаунта — синхронизация остановлена."
            is HttpRequestException, is HttpRequestTimeoutException, is IOException -> "Нет связи с сервером. Синхронизация повторится сама, когда появится интернет."
            is RestException -> when {
                e.statusCode == 401 || e.statusCode == 403 -> "Сервер не принял вход. Выйди и войди снова."
                e.statusCode >= 500 -> "Сервер Supabase не отвечает. Возможно, бесплатный проект уснул после недели без запросов: открой его в панели Supabase."
                else -> "Ошибка сервера: ${e.error}"
            }
            else -> e.message ?: e::class.simpleName ?: "Неизвестная ошибка"
        }

        /** Human text for a failed account deletion: a server without the function says so plainly. */
        fun describeDelete(e: Throwable): String = when {
            e is RestException && (e.statusCode == 404 || e.error.contains("PGRST202") || e.error.contains("delete_my_account")) ->
                "Удаление аккаунта ещё не включено на сервере: нужно применить миграцию delete_account в проекте Supabase. Ничего не удалено."
            else -> "Аккаунт не удалён. " + describe(e)
        }

        /** Human text for a failed sign-in or sign-up. */
        fun describeSignIn(e: Throwable): String = when {
            e is AuthRestException -> when (e.error) {
                "invalid_credentials" -> "Неверная почта или пароль."
                "email_exists", "user_already_exists" -> "Аккаунт с этой почтой уже есть — войди."
                "weak_password" -> "Пароль слишком простой: нужно минимум 8 символов, лучше с цифрами."
                "email_not_confirmed" ->
                    "Почта ещё не подтверждена: открой письмо от Supabase и нажми ссылку. Или выключи подтверждение почты в настройках проекта."
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
