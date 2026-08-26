package dev.shivam.nfcexplorer.data.log

import dev.shivam.nfcexplorer.domain.log.ActivityLog
import dev.shivam.nfcexplorer.domain.log.LogRetention
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies tap activity out of the in-memory session log and onto disk.
 *
 * A listener rather than a second logging call at every site: everything that already logs keeps
 * logging once, and what gets persisted is decided in one place. Nothing that writes a log line has
 * to know that persistence exists.
 */
@Singleton
class ActivityLogRecorder @Inject constructor(
    private val logger: SessionLogger,
    private val store: ActivityLog,
) {

    /**
     * Starts watching. Called once, from the application.
     *
     * Tracks how much of the session log has already been persisted rather than diffing lists: the
     * session log is append-only, so the count is a complete description of what is new.
     */
    fun attach(scope: CoroutineScope) {
        var persisted = 0
        scope.launch {
            // drop(1) skips the empty initial value, which has nothing new in it by definition.
            logger.entries.drop(1).collect { all ->
                if (all.size <= persisted) return@collect
                val fresh = all.subList(persisted, all.size)
                    .filter { LogRetention.retains(it.category) }
                persisted = all.size
                store.append(fresh.reversed())
            }
        }
    }
}
