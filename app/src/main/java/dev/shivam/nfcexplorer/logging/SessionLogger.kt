package dev.shivam.nfcexplorer.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Append-only log of everything that happened during a session.
 *
 * Written from the tag pipeline on `Dispatchers.IO` and collected by the UI on the main
 * thread, so appends are atomic: the sequence counter is an [AtomicLong] and the list is
 * swapped through [MutableStateFlow.update]'s compare-and-set loop. Nothing is ever mutated
 * or removed (invariant I4) — each append publishes a fresh immutable list, so a collector or
 * exporter holding an earlier snapshot keeps seeing exactly what it read.
 *
 * [timeSource] is injected so tests do not depend on wall time.
 */
class SessionLogger(
    private val timeSource: () -> Long = System::currentTimeMillis,
) {

    private val backing = MutableStateFlow<List<LogEntry>>(emptyList())

    private val nextSequence = AtomicLong(0)

    val entries: StateFlow<List<LogEntry>> = backing.asStateFlow()

    fun log(
        level: LogLevel,
        category: String,
        message: String,
        payload: Map<String, String> = emptyMap(),
    ): LogEntry {
        val entry = LogEntry(
            sequence = nextSequence.getAndIncrement(),
            timestampMillis = timeSource(),
            level = level,
            category = category,
            message = message,
            payload = payload,
        )
        backing.update { current -> current + entry }
        return entry
    }

    fun debug(
        category: String,
        message: String,
        payload: Map<String, String> = emptyMap(),
    ): LogEntry = log(LogLevel.DEBUG, category, message, payload)

    fun info(
        category: String,
        message: String,
        payload: Map<String, String> = emptyMap(),
    ): LogEntry = log(LogLevel.INFO, category, message, payload)

    fun warn(
        category: String,
        message: String,
        payload: Map<String, String> = emptyMap(),
    ): LogEntry = log(LogLevel.WARN, category, message, payload)

    fun error(
        category: String,
        message: String,
        payload: Map<String, String> = emptyMap(),
    ): LogEntry = log(LogLevel.ERROR, category, message, payload)
}
