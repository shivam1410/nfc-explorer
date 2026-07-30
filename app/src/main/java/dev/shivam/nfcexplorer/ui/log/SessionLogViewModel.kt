package dev.shivam.nfcexplorer.ui.log

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shivam.nfcexplorer.data.export.SafDocumentWriter
import dev.shivam.nfcexplorer.di.IoDispatcher
import dev.shivam.nfcexplorer.domain.export.ExportFormat
import dev.shivam.nfcexplorer.domain.export.JsonSessionExporter
import dev.shivam.nfcexplorer.domain.export.SessionExporter
import dev.shivam.nfcexplorer.domain.export.TextSessionExporter
import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.logging.LogEntry
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Outcome of the most recent export attempt. */
sealed interface ExportResult {
    data class Written(val bytes: Int, val format: ExportFormat) : ExportResult
    data class Failed(val reason: String) : ExportResult
}

/**
 * Exposes the session log and exports it.
 *
 * The log itself is a straight pass-through: [SessionLogger] is already the single append-only source
 * of truth, and copying its entries here would create a second version that could drift.
 */
@HiltViewModel
class SessionLogViewModel @Inject constructor(
    private val logger: SessionLogger,
    private val documentWriter: SafDocumentWriter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val entries: StateFlow<List<LogEntry>> = logger.entries

    private val backingExport = MutableStateFlow<ExportResult?>(null)
    val exportResult: StateFlow<ExportResult?> = backingExport.asStateFlow()

    /**
     * Suggested filename, including the tag UID when one was captured.
     *
     * The UID makes files self-identifying, which matters as soon as there is more than one card in
     * a folder. Spaces are stripped so the name survives every filesystem.
     */
    fun suggestedFileName(report: TagReport?, format: ExportFormat, nowMillis: Long): String {
        val uid = report?.identity?.uid?.toString()?.replace(" ", "")?.lowercase()
        return listOfNotNull("nfc-session", uid, nowMillis.toString())
            .joinToString("-") + "." + format.extension
    }

    fun export(uri: Uri, format: ExportFormat, report: TagReport?, nowMillis: Long) {
        viewModelScope.launch {
            val exporter: SessionExporter = when (format) {
                ExportFormat.JSON -> JsonSessionExporter
                ExportFormat.TEXT -> TextSessionExporter
            }

            // Snapshot the log before serialising, so a scan landing mid-export cannot produce a
            // file whose entries disagree with its own count.
            val snapshot = entries.value

            val outcome = withContext(ioDispatcher) {
                val body = exporter.export(report, snapshot, nowMillis)
                documentWriter.write(uri, body)
            }

            backingExport.value = outcome.fold(
                onSuccess = { ExportResult.Written(it, format) },
                onFailure = { ExportResult.Failed("${it::class.simpleName}: ${it.message}") },
            )

            logger.info(
                category = "export",
                message = "session exported",
                payload = mapOf(
                    "format" to format.name,
                    "result" to backingExport.value.toString(),
                ),
            )
        }
    }
}
