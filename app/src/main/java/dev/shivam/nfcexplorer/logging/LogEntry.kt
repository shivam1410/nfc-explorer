package dev.shivam.nfcexplorer.logging

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * One entry in the session log.
 *
 * [sequence] exists because timestamps are not a reliable ordering key: several operations in
 * a single tag session can land in the same millisecond, and the log's value depends on the
 * order being exact. It is assigned atomically on append.
 *
 * [category] and [message] are technical developer-facing text, not UI copy, so they stay
 * plain strings. [payload] carries structured detail (page indices, byte values, exception
 * class names) that the export format keeps machine-readable instead of embedding in prose.
 */
data class LogEntry(
    val sequence: Long,
    val timestampMillis: Long,
    val level: LogLevel,
    val category: String,
    val message: String,
    val payload: Map<String, String> = emptyMap(),
)
