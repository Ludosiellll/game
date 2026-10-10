package forma.app.sync

import forma.app.data.DataLock
import forma.app.data.FormaDatabase
import forma.app.data.SettingsStore
import forma.app.data.SyncCursorEntity
import forma.app.data.SyncDao
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** The cloud as the sync needs it: plain JSON rows. The server limits every request to the signed-in user's rows. */
interface CloudApi {
    /** Inserts or replaces rows; [conflict] lists the key columns. */
    suspend fun upsert(table: String, conflict: String, rows: List<JsonObject>)

    /** Marks rows deleted (kept as tombstones so other phones learn about the deletion). */
    suspend fun markDeleted(table: String, keyColumn: String, keys: List<String>)

    /**
     * Rows changed after [since] (server time, null for all), oldest first by (updated_at, key), tombstones included.
     * [after] is the (updated_at, key) of the last row of the previous page: the next page starts strictly after it,
     * so rows changed meanwhile can only move to the end, never be skipped.
     */
    suspend fun changes(table: String, keyColumn: String, since: String?, after: Pair<String, String>?, limit: Int): List<JsonObject>
}

data class SyncResult(val uploaded: Int, val downloaded: Int, val removed: Int)

/** The phone's data belongs to another account: nothing was uploaded or downloaded. */
class AccountMismatchException : IllegalStateException("the phone's data belongs to another account")

/**
 * Two-way copy between the phone and the cloud. The phone is the main copy: on a normal sync its changes go up
 * (and win), then changes made on other phones come down; per-exercise progress is the exception — it comes down
 * first and the newer change wins, so an older change made offline cannot overwrite a newer one.
 *
 * The first sync of an account on this phone (a new phone, after restoring a file, after clearing the phone for
 * another account) goes the other way round: the cloud copy comes down first and merges in (newer progress wins,
 * deletions made elsewhere stay), then what only the phone had goes up. The phone remembers the account it synced
 * with and refuses another one: one person's history never ends up in someone else's account.
 */
class SyncEngine internal constructor(
    private val api: CloudApi,
    private val dao: SyncDao,
    private val tables: List<SyncTable<*>>,
) {
    constructor(api: CloudApi, db: FormaDatabase, store: SettingsStore) : this(api, db.sync(), SyncTables(db, store).all)

    suspend fun run(userId: String): SyncResult = DataLock.mutex.withLock {
        val owner = dao.cursor(ACCOUNT)
        if (owner != null && owner != userId) throw AccountMismatchException()
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
        suspend fun download(which: (SyncTable<*>) -> Boolean) {
            for (t in tables) if (which(t)) downloaded += t.pull(api, dao)
        }
        if (owner == null) {
            download { true }
            // Downloaded once: a retry after a failed upload is a normal sync, not another first one.
            dao.putCursor(SyncCursorEntity(ACCOUNT, userId))
            upload()
        } else {
            download { it.newestWins }
            upload()
            download { !it.newestWins }
        }
        SyncResult(uploaded, downloaded, removed)
    }

    /** The account this phone's data was synced with, if any. */
    suspend fun owner(): String? = dao.cursor(ACCOUNT)

    companion object {
        /** A cursor row that is not a table: the account the phone belongs to (cleared with the other cursors). */
        const val ACCOUNT = "~account"
    }
}
