package dev.shivam.nfcexplorer.ui.actions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shivam.nfcexplorer.domain.action.ActionPerformer
import dev.shivam.nfcexplorer.domain.action.MediaKey
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagActionRepository
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which kind of action the editor is composing. */
enum class ActionType { LAUNCH_APP, OPEN_URI, SEND_INTENT, MEDIA }

/** Why the draft cannot be saved. */
enum class DraftProblem { NO_TAG, BLANK_LABEL, MISSING_TARGET, INVALID_URI }

/**
 * The editor's in-progress fields.
 *
 * Raw strings, because a half-typed value has to be representable and the domain types cannot hold
 * one — they validate in `init`. The draft is the mutable staging area; [TagAction] is the validated
 * result.
 */
data class ActionDraft(
    val uid: ByteBlock? = null,
    val label: String = "",
    val type: ActionType = ActionType.LAUNCH_APP,
    val packageName: String = "",
    val uri: String = "",
    val intentAction: String = "",
    val mediaKey: MediaKey = MediaKey.PLAY_PAUSE,
    val isExisting: Boolean = false,
)

data class TagActionsUiState(
    val assignments: List<TagAssignment> = emptyList(),
    val draft: ActionDraft? = null,
    val problem: DraftProblem? = null,
    val message: String? = null,
) {
    val isEditing: Boolean get() = draft != null
    val canSave: Boolean get() = draft != null && problem == null
}

/**
 * Manages tag-to-action assignments.
 *
 * Validation is delegated to the domain types rather than reimplemented: [draftAction] tries to
 * construct the real [TagAction] and treats a thrown `require` as "not valid yet". That keeps one set
 * of rules, so a URI accepted by the editor is exactly a URI the dispatcher can use — no chance of
 * the two drifting apart and letting an unusable action be saved.
 */
@HiltViewModel
class TagActionsViewModel @Inject constructor(
    private val repository: TagActionRepository,
    private val performer: ActionPerformer,
) : ViewModel() {

    private val backing = MutableStateFlow(TagActionsUiState())
    val state: StateFlow<TagActionsUiState> = backing.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { assignments ->
                backing.value = backing.value.copy(assignments = assignments)
            }
        }
    }

    fun onCreateFor(uid: ByteBlock?) {
        if (uid == null) {
            // Nothing scanned yet, so there is no tag to bind an action to.
            backing.value = backing.value.copy(draft = null, problem = DraftProblem.NO_TAG)
            return
        }
        val draft = ActionDraft(uid = uid)
        backing.value = backing.value.copy(draft = draft, problem = problemOf(draft), message = null)
    }

    fun onEdit(assignment: TagAssignment) {
        val draft = assignment.toDraft()
        backing.value = backing.value.copy(draft = draft, problem = problemOf(draft), message = null)
    }

    fun onCancel() {
        backing.value = backing.value.copy(draft = null, problem = null)
    }

    fun onDraftChange(draft: ActionDraft) {
        backing.value = backing.value.copy(draft = draft, problem = problemOf(draft))
    }

    fun onSave() {
        val draft = backing.value.draft ?: return
        // The editor stays open on an invalid draft so the problem can be corrected in place.
        val action = draftAction(draft) ?: return
        val uid = draft.uid ?: return

        viewModelScope.launch {
            repository.save(TagAssignment(uid = uid, label = draft.label.trim(), action = action))
            backing.value = backing.value.copy(draft = null, problem = null)
        }
    }

    fun onDelete(uid: ByteBlock) {
        viewModelScope.launch { repository.delete(uid) }
    }

    fun onTest(action: TagAction) {
        val message = performer.perform(action).fold(
            onSuccess = { "Action performed." },
            onFailure = { "${it::class.simpleName}: ${it.message}" },
        )
        backing.value = backing.value.copy(message = message)
    }

    /**
     * The action the draft describes, or null when it is not valid yet.
     *
     * Construction is wrapped because the domain types validate in `init`, and here a violation means
     * "keep typing" rather than "crash".
     */
    fun draftAction(draft: ActionDraft): TagAction? {
        if (draft.uid == null || draft.label.isBlank()) return null
        return runCatching {
            when (draft.type) {
                ActionType.LAUNCH_APP -> TagAction.LaunchApp(draft.packageName.trim())
                ActionType.OPEN_URI -> TagAction.OpenUri(draft.uri.trim())
                ActionType.SEND_INTENT -> TagAction.SendIntent(
                    action = draft.intentAction.trim(),
                    uri = draft.uri.trim().ifBlank { null },
                )
                ActionType.MEDIA -> TagAction.MediaCommand(draft.mediaKey)
            }
        }.getOrNull()
    }

    /**
     * Names the *specific* problem so the editor can say which field is wrong. A single "invalid"
     * flag would leave the user guessing between a blank label and a bad URI.
     */
    private fun problemOf(draft: ActionDraft): DraftProblem? = when {
        draft.uid == null -> DraftProblem.NO_TAG
        draft.label.isBlank() -> DraftProblem.BLANK_LABEL
        draft.type == ActionType.LAUNCH_APP && draft.packageName.isBlank() ->
            DraftProblem.MISSING_TARGET
        draft.type == ActionType.OPEN_URI && draft.uri.isBlank() -> DraftProblem.MISSING_TARGET
        draft.type == ActionType.SEND_INTENT && draft.intentAction.isBlank() ->
            DraftProblem.MISSING_TARGET
        draftAction(draft) == null -> DraftProblem.INVALID_URI
        else -> null
    }

    private fun TagAssignment.toDraft(): ActionDraft = when (val current = action) {
        is TagAction.LaunchApp -> ActionDraft(
            uid = uid,
            label = label,
            type = ActionType.LAUNCH_APP,
            packageName = current.packageName,
            isExisting = true,
        )
        is TagAction.OpenUri -> ActionDraft(
            uid = uid,
            label = label,
            type = ActionType.OPEN_URI,
            uri = current.uri,
            isExisting = true,
        )
        is TagAction.SendIntent -> ActionDraft(
            uid = uid,
            label = label,
            type = ActionType.SEND_INTENT,
            intentAction = current.action,
            uri = current.uri.orEmpty(),
            isExisting = true,
        )
        is TagAction.MediaCommand -> ActionDraft(
            uid = uid,
            label = label,
            type = ActionType.MEDIA,
            mediaKey = current.key,
            isExisting = true,
        )
    }
}
