package forma.app.data

import kotlinx.coroutines.sync.Mutex

/**
 * One lock for the operations that rewrite the database wholesale: sync, restoring a backup, clearing the phone for
 * another account. None of them may see another one half done.
 */
object DataLock {
    val mutex = Mutex()
}
