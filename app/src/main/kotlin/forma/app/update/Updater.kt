package forma.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import forma.app.BuildConfig
import forma.app.data.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A version of the app published for everybody: its build number, what is new in it (plain words), where its APK is. */
data class Update(val versionCode: Long, val notes: List<String>, val apkUrl: String, val size: Long)

/**
 * The app updating itself. A checked build (all the tests, the stable key) is published as the latest release of the
 * app's public repository; on start the app asks for it, and when it is newer than the one installed, offers it. «Обновить»
 * downloads it, makes sure it is this app signed with the same key, and installs it through the system installer.
 *
 * Android asks the person once to allow installs from the app, and on the first update to confirm. From Android 12 on, an
 * app updating itself may skip the confirmation ([PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED], with the
 * `UPDATE_PACKAGES_WITHOUT_USER_ACTION` permission): later updates go on one tap.
 */
object Updater {
    /** The latest release of the repository the builds are published to (no key: the repository is public). */
    const val LATEST = "https://api.github.com/repos/Ludosiellll/game/releases/latest"

    /** Where an update stands, for the screen. */
    sealed interface State {
        data object Idle : State
        data class Available(val update: Update) : State
        /** The person must allow installs from the app in the system's settings first. */
        data class NeedsPermission(val update: Update) : State
        data class Downloading(val update: Update, val done: Float) : State
        data class Installing(val update: Update) : State
        data class Failed(val update: Update, val why: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    /** «Позже» for this run of the app: not offered again until it is opened anew. */
    val later = MutableStateFlow(false)

    /** Asked for by hand («Проверить обновления»): offered even with a gym session left open (its sets are saved). */
    val asked = MutableStateFlow(false)

    /** The download and the install go on whoever watches: a turned screen or a closed window does not cut them. */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    private var checked = false

    /** The installed build. */
    val installed: Long get() = BuildConfig.VERSION_CODE.toLong()

    /**
     * The release in GitHub's answer [json] when it is a build with an APK: its number from the tag ("v431601"), what is
     * new from its text (one thing a line), the APK's address. Null for a draft, a pre-release or a release without one.
     */
    fun parse(json: String): Update? {
        val o = runCatching { AppJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        if (o.flag("draft") || o.flag("prerelease")) return null
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val code = Regex("""\d+""").find(tag)?.value?.toLongOrNull() ?: return null
        val asset = runCatching { o["assets"]!!.jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
            .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true } ?: return null
        val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") } ?: return null
        val size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L
        return Update(code, notesOf(o["body"]?.jsonPrimitive?.contentOrNull.orEmpty()), url, size)
    }

    private fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true

    /** The release's text as things said: one a line, list marks and markdown emphasis taken off, empty lines dropped. */
    fun notesOf(body: String): List<String> = body.lines()
        .map { it.trim().removePrefix("- ").removePrefix("* ").removePrefix("• ").replace("**", "").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .take(12)

    /** [u] when it is newer than [installed]; null otherwise (the same build, an older one, or none). */
    fun newer(u: Update?, installed: Long = this.installed): Update? = u?.takeIf { it.versionCode > installed }

    /**
     * Asks once a run of the app (or when [force]d — «Проверить обновления»): a newer build becomes [State.Available].
     * No network, a limit hit, an odd answer: nothing is said, it is asked again next time.
     */
    suspend fun check(force: Boolean = false, fetch: suspend () -> String? = { get(LATEST) }) {
        if (checked && !force) return
        checked = true
        if (state.value !is State.Idle && state.value !is State.Available) return
        val u = newer(fetch()?.let(::parse))
        if (u != null) {
            if (force) {
                later.value = false
                asked.value = true
            }
            state.value = State.Available(u)
        } else if (state.value is State.Available) state.value = State.Idle
    }

    /** GET [url] as text; null when it cannot be read. */
    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Forma-app")
            try {
                if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /** The person may install from the app (Android asks it once, in the system's settings). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** «Обновить» from the screen: [update] on the app's own scope, once at a time. */
    fun start(context: Context, u: Update) {
        if (state.value is State.Downloading || state.value is State.Installing) return
        val app = context.applicationContext
        scope.launch { update(app, u) }
    }

    /**
     * «Обновить»: downloaded, checked and installed; what goes on is in [state]. Without the permission to install from the
     * app, [State.NeedsPermission] — the screen sends the person to the settings and calls this again on return.
     */
    suspend fun update(context: Context, u: Update) {
        if (!canInstall(context)) {
            state.value = State.NeedsPermission(u)
            return
        }
        state.value = State.Downloading(u, 0f)
        val file = runCatching { download(context, u) { state.value = State.Downloading(u, it) } }.getOrElse {
            state.value = State.Failed(u, "Не получилось скачать обновление. Проверь интернет и попробуй ещё раз.")
            return
        }
        problem(context, file, u)?.let {
            file.delete()
            state.value = State.Failed(u, it)
            return
        }
        state.value = State.Installing(u)
        runCatching { install(context, file) }.onFailure {
            state.value = State.Failed(u, "Не получилось начать установку. Попробуй ещё раз.")
        }
    }

    /** The APK into the app's cache (older ones taken away), reporting the share done. */
    private suspend fun download(context: Context, u: Update, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "forma-${u.versionCode}.apk")
        val c = URL(u.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Forma-app")
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            val total = c.contentLengthLong.takeIf { it > 0 } ?: u.size
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) progress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        file
    }

    /**
     * What is wrong with the downloaded [file] for [u], or null: it must be this app, newer than the one installed, and
     * signed with the same key (Android refuses another key anyway; said here plainly before trying).
     */
    fun problem(context: Context, file: File, u: Update): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val apk = pm.getPackageArchiveInfo(file.path, flags) ?: return "Файл обновления повреждён. Попробуй ещё раз."
        if (apk.packageName != context.packageName) return "Это не обновление «Формы»."
        if (PackageInfoCompat.getLongVersionCode(apk) <= installed) return "Эта версия не новее установленной."
        val mine = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()
        val theirs = certs(apk)
        val ours = mine?.let(::certs)
        // An archive whose signature cannot be read here (some Android versions): the system checks it on install.
        if (theirs.isNotEmpty() && !ours.isNullOrEmpty() && theirs != ours) return "Обновление подписано другим ключом — ставить его нельзя."
        return null
    }

    /** SHA-256 of each signing certificate of [p]. */
    private fun certs(p: PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /**
     * Into the system installer as a session: the result comes to [UpdateReceiver] (the confirmation to show, or how it
     * ended). Android 12+: no confirmation needed when the app updates itself.
     */
    fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("forma.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_RESULT)
            // Mutable: the installer adds the status and the confirmation to it.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /** Back to before a check (tests). */
    internal fun reset() {
        checked = false
        later.value = false
        asked.value = false
        state.value = State.Idle
    }
}
