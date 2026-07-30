package dev.shivam.nfcexplorer.ui.log

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shivam.nfcexplorer.logging.LogEntry
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Exposes the process-wide session log to the UI.
 *
 * A pass-through by design: the logger is already the single append-only source of truth, and
 * copying or re-deriving its entries here would only create a second version that could drift.
 */
@HiltViewModel
class SessionLogViewModel @Inject constructor(
    logger: SessionLogger,
) : ViewModel() {
    val entries: StateFlow<List<LogEntry>> = logger.entries
}
