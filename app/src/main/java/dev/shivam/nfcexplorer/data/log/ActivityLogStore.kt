package dev.shivam.nfcexplorer.data.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * The record of taps that survives the app being closed.
 *
 * Only what a tap *did* is kept: the tag-reading chatter is diagnostic detail about one scan and is
 * worthless an hour later, while "at 15:41 this card started a Toggl timer" is the thing you come
 * back to check. Keeping everything would mean writing hundreds of lines per scan to disk for the
 * two that matter.
 *
 * Bounded to [LIMIT] entries, oldest dropped. An unbounded log on a phone eventually becomes the
 * problem it was meant to diagnose.
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

    /**
     * Adds entries, renumbering them to continue from what is already stored.
     *
     * The session log restarts its sequence at zero in every process, so a history spanning several
     * runs holds many entries numbered 0. That is not merely untidy: the sequence is the only
     * identity an entry has, and a list keyed by it crashed outright once a second session existed.
     * Renumbering on the way in keeps the identity unique for as long as the history does.
     */
    @Synchronized
    fun append(newEntries: List<LogEntry>) {
        if (newEntries.isEmpty()) return
        var next = (backing.value.maxOfOrNull { it.sequence } ?: -1L) + 1
        // Oldest first, so the numbers run in the same direction as time.
        val renumbered = newEntries.reversed().map { entry ->
            entry.copy(sequence = next++)
        }.reversed()

        val merged = (renumbered + backing.value).take(LIMIT)
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
        /** Categories worth keeping: a tap, and what it performed. */
        val PERSISTED_CATEGORIES = setOf("trigger", "action")

        private const val FILE_NAME = "activity-log.json"
        private const val LIMIT = 500

        private val json = Json { ignoreUnknownKeys = true }
    }
}
