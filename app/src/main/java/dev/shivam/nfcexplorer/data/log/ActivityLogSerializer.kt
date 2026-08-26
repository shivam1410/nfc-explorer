package dev.shivam.nfcexplorer.data.log

import dev.shivam.nfcexplorer.logging.LogEntry
import dev.shivam.nfcexplorer.logging.LogLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The wire and disk format for kept log entries.
 *
 * One format, because the file on this phone and the document in the user's Drive hold the same
 * thing and are now read by each other: what is uploaded here has to be decodable by the same app
 * after a reinstall, when nothing but the document survives. They were separate near-identical
 * definitions before restore existed, which was fine only while nothing ever read the upload back.
 *
 * Unknown keys are ignored, so a document written by a newer version stays readable by an older one.
 * The alternative is a phone that refuses its own history because a field it does not care about
 * appeared beside it.
 */
object ActivityLogSerializer {

    fun encode(entries: List<LogEntry>): String =
        json.encodeToString(ListSerializer(EntryDto.serializer()), entries.map(::toDto))

    /** Throws when [text] is not a log document; callers decide whether that is fatal. */
    fun decode(text: String): List<LogEntry> =
        json.decodeFromString(ListSerializer(EntryDto.serializer()), text).map { it.toDomain() }

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
        // An unrecognised level degrades to INFO rather than failing the whole document: one
        // unreadable field is a poor reason to hand back nothing.
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

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
