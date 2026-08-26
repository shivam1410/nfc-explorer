package dev.shivam.nfcexplorer.domain.log

import dev.shivam.nfcexplorer.logging.LogEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * The kept log, as the rest of the app needs it.
 *
 * An interface so a sync can be tested against a list rather than a filesystem. The implementation
 * needs a `Context` for `filesDir`, which put every decision built on top of it -- what gets
 * uploaded, what comes back, what is pruned -- out of reach of a JVM test and left them verified
 * only by hand on a phone.
 */
interface ActivityLog {

    /** Newest first, which is the order the screen wants and the order questions are asked in. */
    val entries: StateFlow<List<LogEntry>>

    /** Adds entries, which are always newer than what is held. */
    fun append(newEntries: List<LogEntry>)

    /** Folds in a history recovered from the cloud, returning how many were new. */
    fun restore(recovered: List<LogEntry>): Int

    /** Forgets everything kept. */
    fun clear()
}
