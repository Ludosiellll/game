package forma.app

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import forma.app.data.AppJson
import forma.app.data.BackupManager
import forma.app.data.FormaDatabase
import forma.app.data.Settings
import forma.app.data.SettingsStore
import forma.app.data.TrainingRepository
import forma.app.sync.Cloud
import forma.app.sync.FakeCloud
import forma.app.sync.SyncEngine
import forma.app.sync.canonicalText
import forma.app.ui.screens.offerUpdate
import forma.app.update.Update
import forma.app.update.Updater
import forma.core.model.Experience
import forma.core.model.Goal
import forma.core.model.Profile
import forma.core.model.RunLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The data and the app's shell, from the audit of 24 November (group G-data-shell): settings an update cannot read, an
 * account deletion without an answer, the backup file and the main thread, and the update window.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AuditFixDataShellTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val user = "8f0c7c2e-5a40-4a8e-9a43-1f7d2a9c0b11"

    private val profile = Profile(
        goal = Goal.HYBRID, experience = Experience.NOVICE, runLevel = RunLevel.BASE,
        trainingDays = setOf(1, 3, 5, 6), bodyWeightKg = 80.0,
    )

    @Before fun setUp() {
        // A run left by a screen test in this process would count as one going on.
        forma.app.run.RunSession.clear()
        Updater.reset()
    }

    @After fun tearDown() = Updater.reset()

    /** R3: settings this version cannot read stay as stored (the defaults live in memory) and never reach the cloud as defaults. */
    @Test fun settingsThisVersionCannotReadAreKeptAndTheCloudNeverGetsTheDefaults() = runBlocking<Unit> {
        // «sets» as words: a type this version does not read. The person's swap and note are in the same text.
        val old = """{"look":"carbon","voice":false,"swaps":{"bench":"db_bench"},"notes":{"db_bench":"сиденье 4"},"sets":{"bench":"три"}}"""
        val store = storeWith("audit-r3-${System.nanoTime()}", "settings" to old)
        val db = FormaDatabase.create(ctx, inMemory = true)
        val repo = TrainingRepository(db, store)
        try {
            // Usable: the defaults, in memory.
            assertEquals(Settings(), store.settingsNow())
            // What every start writes (MainActivity, FormaApp) and a change made on a screen.
            repo.updateSettings(forma.app.ui.theme.Looks::moved)
            repo.moveRest()
            repo.reviewWeights(notify = false)
            repo.updateSettings { it.copy(voice = true, newsSeen = 99, tourSeen = true) }
            assertEquals(old, store.syncDocs()["settings"], "the stored text is not written over")
            assertTrue(ShadowLog.getLogsForTag(SettingsStore.LOG_TAG).isNotEmpty(), "the refusal is in the log")
            // Still usable in this run: a window closed («Что нового», the look round the app) stays closed.
            assertEquals(99, store.settingsNow().newsSeen)
            assertTrue(store.settingsNow().tourSeen)
            // The first sync of the phone takes the text as it was to the cloud, not the defaults.
            val cloud = FakeCloud(user)
            SyncEngine(cloud, db, store).run(user)
            val up = cloud.changes("app_state", "key", null, null, 100).single { it["key"]?.jsonPrimitive?.content == "settings" }
            assertEquals(canonicalText(AppJson.parseToJsonElement(old)), canonicalText(up.getValue("value")))
        } finally {
            db.close()
        }
    }

    /** DS-03: «Удалить аккаунт» without an answer keeps the account mark, so the next sync rolls nothing back. */
    @Test fun aDeletionWithoutAnAnswerRollsNothingBackOnTheNextSync() = runBlocking<Unit> {
        val cloud = FakeCloud(user)
        val a = Phone("audit-ds03-${System.nanoTime()}", 1_760_000_000_000L)
        try {
            a.liftSession(LocalDate.of(2026, 10, 5))
            a.clock += 2 * 86_400_000
            a.liftSession(LocalDate.of(2026, 10, 7))
            a.sync(cloud)
            // Offline: the voice turned off, the first session deleted.
            a.repo.updateSettings { it.copy(voice = false) }
            a.repo.deleteWorkout(a.db.workouts().allDone().minBy { it.startedAt }.id)
            // «Удалить навсегда» gets no answer.
            Cloud.afterUnansweredDeletion(a.db)
            assertEquals(1, a.db.sync().notUploaded(), "a replace with another account's data still warns")
            assertEquals(user, SyncEngine(cloud, a.db, a.store).owner(), "the phone still knows whose data it is")
            a.sync(cloud)
            assertFalse(a.store.settingsNow().voice, "the settings changed offline stay")
            assertEquals(1, a.db.workouts().allDone().size, "the deleted workout does not come back")
            assertEquals(1, cloud.live("workouts"), "and it is deleted in the cloud too")
        } finally {
            a.db.close()
        }
    }

    /** DS-08: the backup file (every GPS point of every run) is written and read off the main thread. */
    @Test fun theBackupFileIsWrittenAndReadOffTheMainThread() = runBlocking<Unit> {
        val main = Looper.getMainLooper().thread
        assertSame(main, Thread.currentThread(), "the test runs where the screen's scope does")
        val a = Phone("audit-ds08-${System.nanoTime()}", 1_760_000_000_000L)
        try {
            a.liftSession(LocalDate.of(2026, 10, 5))
            val uri = Uri.parse("content://forma.test/backup.json")
            val resolver = shadowOf(ctx.contentResolver)
            val writes = mutableSetOf<Thread>()
            val out = object : ByteArrayOutputStream() {
                override fun write(b: Int) {
                    writes += Thread.currentThread()
                    super.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    writes += Thread.currentThread()
                    super.write(b, off, len)
                }
            }
            resolver.registerOutputStreamSupplier(uri) { out }
            BackupManager(a.repo).export(ctx, uri)
            assertTrue(writes.isNotEmpty())
            assertFalse(main in writes, "written on the main thread")

            val bytes = out.toByteArray()
            val reads = mutableSetOf<Thread>()
            resolver.registerInputStreamSupplier(uri) {
                object : ByteArrayInputStream(bytes) {
                    override fun read(): Int {
                        reads += Thread.currentThread()
                        return super.read()
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        reads += Thread.currentThread()
                        return super.read(b, off, len)
                    }
                }
            }
            val back = BackupManager(a.repo).read(ctx, uri)
            assertTrue(reads.isNotEmpty())
            assertFalse(main in reads, "read on the main thread")
            assertEquals(1, back.workouts.size)
            assertEquals(a.db.sync().doneSets().size, back.sets.size)
        } finally {
            a.db.close()
        }
    }

    /** SHELL-3: after «Позже», «Проверить обновления» brings back an update downloaded, failed or waiting for the permission. */
    @Test fun aCheckByHandBringsBackAnUpdateAlreadyOnItsWay() = runBlocking<Unit> {
        val u = Update(Updater.installed + 10, emptyList(), "https://x/a.apk", 1)
        val held = listOf(
            Updater.State.Ready(u),
            Updater.State.Failed(u, "Не получилось скачать обновление. Проверь интернет и попробуй ещё раз."),
            Updater.State.NeedsPermission(u),
        )
        for (s in held) {
            Updater.reset()
            Updater.state.value = s
            Updater.later.value = true
            assertEquals(Updater.Checked.NEWER, Updater.check(force = true) { release(u.versionCode) }, "$s")
            assertFalse(Updater.later.value, "$s: shown again after «Позже»")
            assertTrue(Updater.asked.value, "$s")
            assertEquals(s, Updater.state.value, "the update stays where it was")
            assertTrue(offerUpdate(true, true, false, Updater.later.value, false, false, Updater.asked.value, Updater.state.value), "$s")
        }
    }

    /** SHELL-5: an install the system never answers does not keep «Ставлю обновление…» on screen with no way out. */
    @Test fun anInstallWithoutAnAnswerOffersThePhonesInstaller() = runBlocking<Unit> {
        val u = Update(Updater.installed + 1, emptyList(), "https://x/a.apk", 1)
        org.robolectric.shadows.ShadowBuild.setManufacturer("Google")
        org.robolectric.shadows.ShadowBuild.setBrand("google")
        // The session goes to the system; no answer ever comes back.
        Updater.installer = { _, _, _ -> }
        Updater.installWait = 50
        Updater.installChecked(ctx, u, apkInCache())
        assertIs<Updater.State.Installing>(Updater.state.value)
        val after = withTimeout(5_000) { Updater.state.first { it !is Updater.State.Installing } }
        val f = assertIs<Updater.State.Failed>(after)
        assertTrue(f.system, "the phone's installer is offered")
    }

    // ---------------- helpers ----------------

    /** A store whose file holds [docs] as another version of the app left them, before the store is first opened. */
    private fun storeWith(name: String, vararg docs: Pair<String, String>): SettingsStore {
        val file = ctx.preferencesDataStoreFile(name)
        file.parentFile?.mkdirs()
        val prefs = mutablePreferencesOf().apply { docs.forEach { (k, v) -> this[stringPreferencesKey(k)] = v } }
        runBlocking { file.outputStream().use { PreferencesFileSerializer.writeTo(prefs, it) } }
        return SettingsStore(ctx, name)
    }

    private fun release(code: Long) = """
        {"tag_name":"v$code","draft":false,"prerelease":false,"body":"- Обновления прямо в приложении.",
         "assets":[{"name":"forma.apk","size":4135261,"browser_download_url":"https://github.com/Ludosiellll/game/releases/download/v$code/forma.apk"}]}
    """.trimIndent()

    private fun apkInCache(): File = File(ctx.cacheDir, "updates").apply { mkdirs() }.let { File(it, "forma-test.apk").apply { writeText("apk") } }

    /** One phone of the account, as in [forma.app.sync.SyncEngineTest]. */
    private inner class Phone(name: String, var clock: Long) {
        val db = FormaDatabase.create(ctx, inMemory = true)
        val store = SettingsStore(ctx, name).also { runBlocking { it.clear() } }
        val repo = TrainingRepository(db, store, now = { clock })

        suspend fun sync(cloud: FakeCloud) = SyncEngine(cloud, db, store).run(user)

        suspend fun liftSession(day: LocalDate) {
            if (!repo.isOnboarded()) repo.completeOnboarding(profile)
            val tpl = store.programNow()!!.templates.first().id
            val id = repo.startLift(tpl, day)
            val plan = repo.planOf(db.workouts().get(id)!!)!!
            for (item in plan.items.take(3)) {
                val w = item.prescription.weight ?: 20.0
                repeat(2) {
                    clock += 120_000
                    repo.logSet(id, item, w, item.prescription.reps, 2)
                }
            }
            clock += 30 * 60_000
            repo.finishLift(id, 7)
        }
    }
}
