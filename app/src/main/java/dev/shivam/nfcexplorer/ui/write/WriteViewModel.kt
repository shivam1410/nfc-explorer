package dev.shivam.nfcexplorer.ui.write

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shivam.nfcexplorer.domain.model.WriteBatchResult
import dev.shivam.nfcexplorer.domain.repository.TagHandle
import dev.shivam.nfcexplorer.domain.repository.TagRepository
import dev.shivam.nfcexplorer.domain.writer.PageEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class WriteMode { TEXT, HEX, WIPE }

/** Why the composed payload cannot be written. */
enum class InputProblem { TOO_LONG, MALFORMED_HEX }

data class WriteUiState(
    val mode: WriteMode = WriteMode.TEXT,
    val input: String = "",
    val startPage: Int = FIRST_USER_PAGE,
    val endPage: Int = LAST_USER_PAGE,
    val expertMode: Boolean = false,
    val isArmed: Boolean = false,
    val isWriting: Boolean = false,
    val problem: InputProblem? = null,
    val result: WriteBatchResult? = null,
    val failure: String? = null,
) {
    val pageCount: Int get() = (endPage - startPage + 1).coerceAtLeast(0)

    val capacityBytes: Int get() = PageEncoder.capacityBytes(pageCount)

    /**
     * Whether there is something reviewable to arm.
     *
     * An empty payload in [WriteMode.TEXT] or [WriteMode.HEX] is **not** armable. `fromText("")`
     * encodes to all-zero pages, so without this an untouched field would arm a silent wipe of the
     * whole target range. Zeroing pages is what [WriteMode.WIPE] is for, chosen deliberately.
     */
    val canArm: Boolean
        get() = problem == null &&
            pageCount > 0 &&
            !isWriting &&
            (mode == WriteMode.WIPE || input.isNotBlank())

    companion object {
        const val FIRST_USER_PAGE = 4
        const val LAST_USER_PAGE = 15
    }
}

/**
 * Composes a payload, arms it, and writes it when a tag arrives.
 *
 * The two-step arm-then-tap flow is not ceremony — it is forced by the hardware. A tag is only
 * present for a moment, so the payload has to be reviewed and confirmed *before* the tap. That makes
 * the arming step the real confirmation, which is why **any change to the payload or the target
 * range disarms**: otherwise a tap could write something different from what was reviewed.
 *
 * Expert mode lives here rather than in persisted settings, so it resets to off on every launch. A
 * capability that can permanently brick a page should not be silently armed from a previous session.
 */
@HiltViewModel
class WriteViewModel @Inject constructor(
    private val repository: TagRepository,
) : ViewModel() {

    private val backing = MutableStateFlow(WriteUiState())
    val state: StateFlow<WriteUiState> = backing.asStateFlow()

    private val backingPreview = MutableStateFlow<List<ByteArray>?>(null)
    val encodedPreview: StateFlow<List<ByteArray>?> = backingPreview.asStateFlow()

    init {
        // Populate the preview up front. It is the review step that arming confirms, so it has to be
        // on screen from the outset rather than appearing only once the user happens to edit
        // something — otherwise the screen can be armed with nothing shown.
        refresh { it }
    }

    fun onModeChange(mode: WriteMode) = update { it.copy(mode = mode) }

    fun onInputChange(input: String) = update { it.copy(input = input) }

    fun onRangeChange(startPage: Int, endPage: Int) =
        update { it.copy(startPage = startPage, endPage = endPage) }

    fun onExpertModeChange(enabled: Boolean) = update { it.copy(expertMode = enabled) }

    fun onArm() {
        val current = backing.value
        if (!current.canArm) return
        backing.value = current.copy(isArmed = true, result = null, failure = null)
    }

    fun onDisarm() {
        backing.value = backing.value.copy(isArmed = false)
    }

    fun onTagPresented(handle: TagHandle) {
        val current = backing.value
        if (!current.isArmed || current.isWriting) return

        val pages = encode(current) ?: return

        // Disarm before the write starts, so a second tap during the exchange cannot queue a repeat.
        backing.value = current.copy(isArmed = false, isWriting = true, result = null, failure = null)

        viewModelScope.launch {
            repository.writePages(
                handle = handle,
                startPage = current.startPage,
                pages = pages,
                expertMode = current.expertMode,
            )
                .onSuccess { batch ->
                    backing.value = backing.value.copy(isWriting = false, result = batch)
                }
                .onFailure { failure ->
                    backing.value = backing.value.copy(
                        isWriting = false,
                        failure = "${failure::class.simpleName}: ${failure.message}",
                    )
                }
        }
    }

    /**
     * Applies a change, re-validates, and disarms.
     *
     * Every edit routes through here so there is exactly one place where "the payload changed"
     * implies "the previous confirmation no longer applies".
     */
    private fun update(transform: (WriteUiState) -> WriteUiState) = refresh(transform)

    private fun refresh(transform: (WriteUiState) -> WriteUiState) {
        val next = transform(backing.value).copy(isArmed = false)
        val encoded = encode(next)
        backing.value = next.copy(problem = problemFor(next, encoded))
        backingPreview.value = encoded
    }

    private fun encode(state: WriteUiState): List<ByteArray>? = when (state.mode) {
        WriteMode.TEXT -> PageEncoder.fromText(state.input, state.pageCount)
        WriteMode.HEX -> PageEncoder.fromHex(state.input, state.pageCount)
        // A wipe writes zeros whatever is in the input field, so stale text must not block it.
        WriteMode.WIPE -> PageEncoder.zeros(state.pageCount)
    }

    private fun problemFor(state: WriteUiState, encoded: List<ByteArray>?): InputProblem? = when {
        encoded != null -> null
        state.mode == WriteMode.HEX -> hexProblem(state)
        else -> InputProblem.TOO_LONG
    }

    /**
     * Distinguishes unparseable hex from hex that simply does not fit, so the message names the
     * actual mistake instead of blaming length for a stray character.
     */
    private fun hexProblem(state: WriteUiState): InputProblem {
        val parsed = PageEncoder.fromHex(state.input, pageCount = Int.MAX_VALUE / 4)
        return if (parsed == null) InputProblem.MALFORMED_HEX else InputProblem.TOO_LONG
    }
}
