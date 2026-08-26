package dev.shivam.nfcexplorer.data.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shivam.nfcexplorer.domain.log.LogRetention
import dev.shivam.nfcexplorer.logging.LogEntry
import dev.shivam.nfcexplorer.logging.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
) {

    private val file = File(context.filesDir, FILE_NAME)

    private val backing = MutableStateFlow(read())

    /** Newest first, which is the order the screen wants and the order questions are asked in. */
    val entries: StateFlow<List<LogEntry>> = backing.asStateFlow()

    /** Adds entries, renumbered and bounded by [LogRetention]. */
    @Synchronized
    fun append(newEntries: List<LogEntry>) {
        if (newEntries.isEmpty()) return
        val merged = LogRetention.append(existing = backing.value, incoming = newEntries)
        backing.value = merged
        runCatching { file.writeText(encode(merged)) }
    }

    @Synchronized
    fun clear() {
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
        json.decodeFromString(ListSerializer(EntryDto.serializer()), file.readText())
            .map { it.toDomain() }
    }.getOrDefault(emptyList())

    private fun encode(entries: List<LogEntry>): String =
        json.encodeToString(ListSerializer(EntryDto.serializer()), entries.map(::toDto))

    private fun toDto(entry: LogEntry) = EntryDto(
        sequence = entry.sequence,
        timestampMillis = entry.timestampMillis,
        level = entry.level.name,
        category = entry.category,
        message = entry.message,
        payload = entry.payload,
    )

    private fun EntryDto.toDomain() = LogEntry(
        sequence = sequence,
        timestampMillis = timestampMillis,
        level = LogLevel.entries.firstOrNull { it.name == level } ?: LogLevel.INFO,
        category = category,
        message = message,
        payload = payload,
    )

    @Serializable
    private data class EntryDto(
        @SerialName("sequence") val sequence: Long,
        @SerialName("timestampMillis") val timestampMillis: Long,
        @SerialName("level") val level: String,
        @SerialName("category") val category: String,
        @SerialName("message") val message: String,
        @SerialName("payload") val payload: Map<String, String> = emptyMap(),
    )

    companion object {
        private const val FILE_NAME = "activity-log.json"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
