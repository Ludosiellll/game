package forma.app.sync

import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.SyncDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** The cloud as the sync needs it: plain JSON rows. The server limits every request to the signed-in user's rows. */
interface CloudApi {
    /** Inserts or replaces rows; [conflict] lists the key columns. */
    suspend fun upsert(table: String, conflict: String, rows: List<JsonObject>)

    /** Marks rows deleted (kept as tombstones so other phones learn about the deletion). */
    suspend fun markDeleted(table: String, keyColumn: String, keys: List<String>)

    /** Rows changed after [since] (server time, null for all), oldest first, tombstones included. */
    suspend fun changes(table: String, keyColumn: String, since: String?, offset: Int, limit: Int): List<JsonObject>
}

data class SyncResult(val uploaded: Int, val downloaded: Int, val removed: Int)

/**
 * Two-way copy between the phone and the cloud. The phone is the main copy: on a normal sync its
 * changes go up first (and win), then changes made on other phones come down. The first sync of an
 * account on this phone goes the other way round, so a new or reset phone gets the cloud copy back
 * before anything is uploaded.
 */
class SyncEngine internal constructor(
    private val api: CloudApi,
    private val dao: SyncDao,
    private val tables: List<SyncTable<*>>,
) {
    constructor(api: CloudApi, db: FormaDatabase, store: SettingsStore) : this(api, db.sync(), SyncTables(db, store).all)

    private val mutex = Mutex()

    suspend fun run(userId: String, firstTime: Boolean): SyncResult = mutex.withLock {
        var uploaded = 0
        var downloaded = 0
        var removed = 0
        suspend fun upload() {
            for (t in tables) {
                val (u, r) = t.push(api, dao, userId)
                uploaded += u
                removed += r
            }
        }
        suspend fun download() {
            for (t in tables) downloaded += t.pull(api, dao)
        }
        if (firstTime) {
            dao.reset()
            download()
            upload()
        } else {
            upload()
            download()
        }
        SyncResult(uploaded, downloaded, removed)
    }
}
