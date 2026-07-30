package dev.shivam.nfcexplorer.logging

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors session log entries to logcat under a single tag.
 *
 * Exists so a dump can be inspected with `adb logcat -s NfcExplorer:V` during device
 * verification, before the in-app log screen is built. It only ever *copies* entries — the
 * [SessionLogger] remains the source of truth and nothing here can drop or reorder an entry.
 */
@Singleton
class SessionLogcatMirror @Inject constructor(
    private val logger: SessionLogger,
) {

    private var lastMirrored = -1L

    fun attach(scope: CoroutineScope) {
        scope.launch {
            logger.entries.collectLatest { entries ->
                // Only entries newer than the high-water mark, so a re-emitted list cannot
                // duplicate output.
                entries.filter { it.sequence > lastMirrored }.forEach { entry ->
                    write(entry)
                    lastMirrored = entry.sequence
                }
            }
        }
    }

    private fun write(entry: LogEntry) {
        val payload = if (entry.payload.isEmpty()) {
            ""
        } else {
            entry.payload.entries.joinToString(prefix = "  {", postfix = "}") { "${it.key}=${it.value}" }
        }
        val line = "[${entry.sequence}] ${entry.category}: ${entry.message}$payload"

        when (entry.level) {
            LogLevel.DEBUG -> Log.d(TAG, line)
            LogLevel.INFO -> Log.i(TAG, line)
            LogLevel.WARN -> Log.w(TAG, line)
            LogLevel.ERROR -> Log.e(TAG, line)
        }
    }

    private companion object {
        const val TAG = "NfcExplorer"
    }
}
