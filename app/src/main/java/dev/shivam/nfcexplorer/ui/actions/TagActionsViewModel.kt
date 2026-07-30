package dev.shivam.nfcexplorer.ui.actions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shivam.nfcexplorer.domain.action.ActionPerformer
import dev.shivam.nfcexplorer.domain.action.AppCatalog
import dev.shivam.nfcexplorer.domain.action.InstalledApp
import dev.shivam.nfcexplorer.domain.action.MediaKey
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagActionRepository
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.action.matching
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
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
    val apps: List<InstalledApp> = emptyList(),
    val appQuery: String = "",
) {
    val isEditing: Boolean get() = draft != null
    val canSave: Boolean get() = draft != null && problem == null

    /** The apps worth showing for what has been typed so far. Derived, so it cannot fall out of step. */
    val visibleApps: List<InstalledApp> get() = apps.matching(appQuery)

    /**
     * The name of an app, for showing back to the user.
     *
     * A package name is what gets *stored*; it is not what anyone wants to read. Falls back to the
     * package when the app is gone, because an assignment outlives an uninstall and showing nothing
     * would be worse than showing what it still points at.
     */
    fun labelFor(packageName: String): String =
        apps.firstOrNull { it.packageName == packageName }?.label ?: packageName
}

/** Schemes offered when editing a link. `https` first, because it should be the default. */
val LINK_SCHEMES = listOf("https://", "http://")

private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

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
    private val catalog: AppCatalog,
) : ViewModel() {

    private val backing = MutableStateFlow(TagActionsUiState())
    val state: StateFlow<TagActionsUiState> = backing.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { assignments ->
                backing.value = backing.value.copy(assignments = assignments)
            }
        }
        // Eagerly, not when the editor opens: the assignment cards name the app too, and they are on
        // screen first. Loading it later meant every card read as a raw package name until the user
        // happened to open an editor.
        loadApps()
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

    fun onAppQueryChange(query: String) {
        backing.value = backing.value.copy(appQuery = query)
    }

    /**
     * Points the draft at [app].
     *
     * Also names the assignment after it when the label is still empty: having just chosen which app to
     * open, being asked to type its name again is busywork. A label the user typed is left alone.
     */
    fun onPickApp(app: InstalledApp) {
        val draft = backing.value.draft ?: return
        val picked = draft.copy(
            packageName = app.packageName,
            label = draft.label.ifBlank { app.label },
        )
        backing.value = backing.value.copy(draft = picked, problem = problemOf(picked))
    }

    /**
     * Reads the app list once and keeps it.
     *
     * Enumerating launchable apps is hundreds of `PackageManager` round trips, and it cannot change
     * while this screen is open in front of the user.
     */
    private fun loadApps() {
        if (backing.value.apps.isNotEmpty()) return
        viewModelScope.launch {
            backing.value = backing.value.copy(apps = catalog.launchable())
        }
    }

    fun onCancel() {
        backing.value = backing.value.copy(draft = null, problem = null, message = null)
    }

    fun onDraftChange(draft: ActionDraft) {
        backing.value = backing.value.copy(draft = draft, problem = problemOf(draft))
    }

    /**
     * Switches which kind of action the draft describes.
     *
     * Seeds a blank link with `https://` rather than leaving the field empty. Every link needs a
     * scheme, so an empty field's only possible next state is the "include a scheme" error — which the
     * user then has to read and fix for no reason. An existing link is never touched.
     */
    fun onTypeChange(type: ActionType) {
        val draft = backing.value.draft ?: return
        val seeded = when {
            type == ActionType.OPEN_URI && draft.uri.isBlank() -> draft.copy(
                type = type,
                uri = LINK_SCHEMES.first(),
            )
            else -> draft.copy(type = type)
        }
        onDraftChange(seeded)
    }

    /**
     * Replaces the link's scheme, keeping everything after it.
     *
     * Learned from a `wa.me` link: `http` bounces through a redirect that can drop the `?text=`
     * payload, and retyping a whole link to change four characters is busywork.
     */
    fun onSchemeChange(scheme: String) {
        val draft = backing.value.draft ?: return
        val withoutScheme = SCHEME_PREFIX.replace(draft.uri.trim(), "")
        onDraftChange(draft.copy(uri = scheme + withoutScheme))
    }

    fun onSave() {
        val draft = backing.value.draft ?: return
        // The editor stays open on an invalid draft so the problem can be corrected in place.
        val action = draftAction(draft) ?: return
        val uid = draft.uid ?: return

        viewModelScope.launch {
            val assignment = TagAssignment(uid = uid, label = draft.label.trim(), action = action)
            report({ repository.save(assignment) }) {
                backing.value = backing.value.copy(draft = null, problem = null, message = null)
            }
        }
    }

    fun onDelete(uid: ByteBlock) {
        viewModelScope.launch {
            report({ repository.delete(uid) }) {
                // Any message on screen described the assignment that is now gone.
                backing.value = backing.value.copy(message = null)
            }
        }
    }

    /**
     * Runs a store write, reporting a failure instead of letting it escape.
     *
     * A `DataStore` write can fail on a full or unreadable disk. Unhandled, that leaves
     * `viewModelScope` with nothing to catch it and the app dies on a button press — so it is reported
     * the same way a failed action is, and the editor stays open so the save can be retried.
     *
     * [IOException] and no wider. A store that fails any other way is a bug in this app rather than a
     * condition of the device, and turning that into a line of text on screen would hide it. Nothing
     * needs to be done about [kotlinx.coroutines.CancellationException] either — it is not an
     * [IOException], so it propagates and structured concurrency still works.
     */
    private suspend fun report(write: suspend () -> Unit, onSuccess: () -> Unit) {
        try {
            write()
            onSuccess()
        } catch (failure: IOException) {
            backing.value = backing.value.copy(
                message = "${failure::class.simpleName}: ${failure.message}",
            )
        }
    }

    fun onTest(action: TagAction) {
        val message = performer.perform(action).fold(
            onSuccess = { "Action performed." },
            onFailure = { "${it::class.simpleName}: ${it.message}" },
        )
        backing.value = backing.value.copy(message = message)
    }

    /**
     * Runs whatever the open draft currently describes, without saving it.
     *
     * The useful moment to test: a wrong package name shows up immediately rather than after a tap
     * that silently does nothing. An invalid draft performs nothing rather than guessing at intent.
     */
    fun onTestDraft() {
        val action = backing.value.draft?.let(::draftAction) ?: return
        onTest(action)
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
