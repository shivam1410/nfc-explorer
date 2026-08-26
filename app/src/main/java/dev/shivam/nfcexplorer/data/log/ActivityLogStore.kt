package dev.shivam.nfcexplorer.data.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shivam.nfcexplorer.domain.log.ActivityLog
import dev.shivam.nfcexplorer.domain.log.LogRetention
import dev.shivam.nfcexplorer.logging.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The record that survives the app being closed.
 *
 * Everything the Log tab can show is kept here: the taps, and the scan detail behind them. What is
 * not shown -- sync chatter, export bookkeeping -- is left to the session log and goes with the
 * process, because it explains a failure while it is happening and is noise by the next morning.
 *
 * What is kept and how much of it is [LogRetention]'s decision, not this class's. This one owns the
 * file; that one owns the policy, and is pure so the bounds can be swept by tests.
 *
 * The whole file is rewritten on each append rather than appended to. At a handful of entries per
 * tap that is cheap, and it keeps the file always-valid JSON: a half-written trailing line would
 * make the entire history unreadable, which is the one outcome worse than not persisting at all.
 */
@Singleton
class ActivityLogStore @Inject constructor(
    @ApplicationContext context: Context,
) : ActivityLog {

    private val file = File(context.filesDir, FILE_NAME)

    private val backing = MutableStateFlow(read())

    /** Newest first, which is the order the screen wants and the order questions are asked in. */
    override val entries: StateFlow<List<LogEntry>> = backing.asStateFlow()

    /** Adds entries, renumbered and bounded by [LogRetention]. */
    @Synchronized
    override fun append(newEntries: List<LogEntry>) {
        if (newEntries.isEmpty()) return
        val merged = LogRetention.append(existing = backing.value, incoming = newEntries)
        backing.value = merged
        runCatching { file.writeText(ActivityLogSerializer.encode(merged)) }
    }

    /**
     * Folds a history recovered from the cloud into what is held, returning how many were new.
     *
     * Separate from [append] because the two differ in where the entries belong in time. Appended
     * entries are always the newest; recovered ones are usually older than everything here, and
     * have to be interleaved rather than stacked on top.
     */
    @Synchronized
    override fun restore(recovered: List<LogEntry>): Int {
        if (recovered.isEmpty()) return 0
        val before = backing.value
        val merged = LogRetention.restore(local = before, recovered = recovered)
        if (merged.size == before.size && merged == before) return 0

        backing.value = merged
        runCatching { file.writeText(ActivityLogSerializer.encode(merged)) }
        return merged.size - before.size
    }

    @Synchronized
    override fun clear() {
        backing.value = emptyList()
        runCatching { file.delete() }
    }

    /**
     * Reads the stored history, or nothing.
     *
     * A corrupt file degrades to an empty history rather than throwing: this is constructed on the
     * way to showing a screen, and losing old logs is a far smaller harm than failing to start.
     */
    private fun read(): List<LogEntry> = runCatching {
        if (!file.exists()) return emptyList()
        // Normalised on the way in, so a file written before the numbering rule existed is repaired
        // rather than carried forward with its duplicates.
        LogRetention.normalise(ActivityLogSerializer.decode(file.readText()))
    }.getOrDefault(emptyList())

    private companion object {
        const val FILE_NAME = "activity-log.json"
    }
}
