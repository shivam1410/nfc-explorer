package dev.shivam.nfcexplorer.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.domain.repository.TagHandle
import dev.shivam.nfcexplorer.domain.repository.TagRepository
import dev.shivam.nfcexplorer.ui.haptics.ScanFeedback
import dev.shivam.nfcexplorer.ui.haptics.ScanHapticSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Whether the device can scan, established before any tag arrives. */
enum class ScanCapability { AVAILABLE, DISABLED, UNSUPPORTED }

/**
 * What the scan surface is doing.
 *
 * [Failed] carries the exception's class name and message rather than the throwable itself: the UI
 * shows a named diagnostic, never a stack trace, and a UI state holding a live exception invites
 * exactly that.
 */
sealed interface ScanUiState {
    data object Starting : ScanUiState
    data object Unsupported : ScanUiState
    data object Disabled : ScanUiState
    data object WaitingForTag : ScanUiState
    data object Reading : ScanUiState
    data class Captured(val report: TagReport) : ScanUiState
    data class Failed(val exceptionName: String, val message: String?) : ScanUiState
}

/**
 * Owns the scan lifecycle and the session's most recent report.
 *
 * [lastReport] is kept separately from [state] on purpose. A fumbled second tap produces
 * [ScanUiState.Failed], and if the report lived only inside [ScanUiState.Captured] that failure
 * would blank a dump the user was still reading. Keeping them apart means a failure reports itself
 * without destroying evidence.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val repository: TagRepository,
) : ViewModel() {

    private val backingState = MutableStateFlow<ScanUiState>(ScanUiState.Starting)
    val state: StateFlow<ScanUiState> = backingState.asStateFlow()

    private val backingHaptic = MutableStateFlow<ScanHapticSignal?>(null)
    val hapticSignal: StateFlow<ScanHapticSignal?> = backingHaptic.asStateFlow()

    private val backingReport = MutableStateFlow<TagReport?>(null)
    val lastReport: StateFlow<TagReport?> = backingReport.asStateFlow()

    /**
     * Monotonic, so two identical scans still yield distinct signals. Without it a repeat tap
     * would produce an equal [ScanHapticSignal], `LaunchedEffect` would not re-fire, and the tap
     * would feel dead.
     */
    private var hapticToken = 0L

    fun onCapabilityResolved(capability: ScanCapability) {
        backingState.value = when (capability) {
            ScanCapability.AVAILABLE -> ScanUiState.WaitingForTag
            ScanCapability.DISABLED -> ScanUiState.Disabled
            ScanCapability.UNSUPPORTED -> ScanUiState.Unsupported
        }
    }

    fun onTagDiscovered(handle: TagHandle) {
        // Reader mode should not be running in these states, but a tag arriving anyway must not
        // start work the device cannot complete.
        if (!canScan()) return

        backingState.value = ScanUiState.Reading
        buzz(ScanFeedback.DETECTED)

        viewModelScope.launch {
            repository.read(handle)
                .onSuccess { report ->
                    backingReport.value = report
                    backingState.value = ScanUiState.Captured(report)
                    buzz(ScanFeedback.CAPTURED)
                }
                .onFailure { failure ->
                    backingState.value = ScanUiState.Failed(
                        exceptionName = failure::class.simpleName ?: "Throwable",
                        message = failure.message,
                    )
                    buzz(ScanFeedback.FAILED)
                }
        }
    }

    private fun canScan(): Boolean = when (backingState.value) {
        ScanUiState.Unsupported, ScanUiState.Disabled, ScanUiState.Starting -> false
        ScanUiState.WaitingForTag,
        ScanUiState.Reading,
        is ScanUiState.Captured,
        is ScanUiState.Failed,
        -> true
    }

    private fun buzz(feedback: ScanFeedback) {
        backingHaptic.value = ScanHapticSignal(feedback, token = hapticToken++)
    }
}
