package dev.shivam.nfcexplorer.ui.actions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shivam.nfcexplorer.domain.action.ActionPerformer
import dev.shivam.nfcexplorer.domain.action.AppCatalog
import dev.shivam.nfcexplorer.domain.action.InstalledApp
import dev.shivam.nfcexplorer.domain.action.MediaKey
import dev.shivam.nfcexplorer.domain.action.SleepCycle
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagActionRepository
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.toggl.TogglSession
import dev.shivam.nfcexplorer.domain.action.matching
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Which kind of action the editor is composing. */
enum class ActionType { LAUNCH_APP, OPEN_URI, SEND_INTENT, MEDIA, SLEEP_CYCLE, TOGGL, WHATSAPP }

/**
 * Where the add-a-tag flow has got to.
 *
 * Null means the flow is not running, which is different from [WaitingForTag]: the editor can also be
 * opened from the assignment list, and that path never waits for a scan.
 */
sealed interface AddTagState {
    data object WaitingForTag : AddTagState

    /** The scanned tag already does something. Offering to edit beats silently overwriting it. */
    data class AlreadyAssigned(val assignment: TagAssignment) : AddTagState
}

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
    /** Toggl workspace, as typed. A raw string because a half-typed number is not a Long. */
    val phoneNumber: String = "",
    val messageText: String = "",
    val togglDescription: String = "",
    /** One tag name, chosen from the workspace or typed. Empty means no tag. */
    val togglTag: String = "",
    val autoSend: Boolean = false,
    val isExisting: Boolean = false,
    /**
     * Whether the user has edited anything yet.
     *
     * Problems are computed from the moment a draft opens -- an empty label is genuinely missing --
     * but showing them before the user has typed a character is scolding someone for not having
     * done something they have not had a chance to do. So the problem is known immediately and
     * *displayed* only once they have engaged, or once they try to save.
     */
    val touched: Boolean = false,
)

data class TagActionsUiState(
    val assignments: List<TagAssignment> = emptyList(),
    val draft: ActionDraft? = null,
    val problem: DraftProblem? = null,
    val message: String? = null,
    val apps: List<InstalledApp> = emptyList(),
    val appQuery: String = "",
    val addTag: AddTagState? = null,
    /**
     * Tag names offered by the workspace.
     *
     * Empty until fetched, and empty is not an error: a new account has no tags, and the free-text
     * field still works, so nothing here blocks saving.
     */
    val togglTagOptions: List<String> = emptyList(),
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
    private val toggl: TogglSession,
) : ViewModel() {

    /**
     * Always mutated through [update], never `value = value.copy(...)`.
     *
     * Several things write here from their own coroutines — the assignment stream, the app catalog, a
     * store write — and a plain read-modify-write loses whichever change arrived while it was
     * suspended. That is not theoretical: it silently emptied the assignment list on screen. [update]
     * is a compare-and-set loop, so a concurrent change cannot be overwritten by a stale copy.
     */
    private val backing = MutableStateFlow(TagActionsUiState())
    val state: StateFlow<TagActionsUiState> = backing.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { assignments ->
                backing.update { it.copy(assignments = assignments) }
            }
        }
        // Eagerly, not when the editor opens: the assignment cards name the app too, and they are on
        // screen first. Loading it later meant every card read as a raw package name until the user
        // happened to open an editor.
        loadApps()
    }

    /** Enters the add flow: clear any open draft and wait for a tap. */
    fun onStartAddFlow() {
        backing.update {
            it.copy(addTag = AddTagState.WaitingForTag, draft = null, problem = null, message = null)
        }
    }

    /**
     * Handles the tag tapped while the add flow is waiting.
     *
     * Ignored unless the flow is actually waiting, because the reader stays live for the whole app:
     * without the guard, a second tap while the user is mid-edit would throw away what they had typed.
     */
    fun onTagScanned(uid: ByteBlock) {
        if (backing.value.addTag !is AddTagState.WaitingForTag) return
        viewModelScope.launch {
            val existing = repository.find(uid)
            if (existing != null) {
                backing.update { it.copy(addTag = AddTagState.AlreadyAssigned(existing)) }
            } else {
                backing.update { it.copy(addTag = null) }
                onCreateFor(uid)
            }
        }
    }

    /** Opens the existing assignment in the same editor rather than a second, divergent one. */
    fun onEditScannedTag(assignment: TagAssignment) {
        backing.update { it.copy(addTag = null) }
        onEdit(assignment)
    }

    /** Leaving the flow abandons whatever it was holding, including an unsaved draft. */
    fun onLeaveAddFlow() {
        backing.update { it.copy(addTag = null, draft = null, problem = null, message = null) }
    }

    fun onCreateFor(uid: ByteBlock?) {
        if (uid == null) {
            // Nothing scanned yet, so there is no tag to bind an action to.
            backing.update { it.copy(draft = null, problem = DraftProblem.NO_TAG) }
            return
        }
        val draft = ActionDraft(uid = uid)
        backing.update { it.copy(draft = draft, problem = problemOf(draft), message = null) }
    }

    fun onEdit(assignment: TagAssignment) {
        if (assignment.action is TagAction.TogglToggle) loadTogglTags()
        val draft = assignment.toDraft()
        backing.update { it.copy(draft = draft, problem = problemOf(draft), message = null) }
    }

    fun onAppQueryChange(query: String) {
        backing.update { it.copy(appQuery = query) }
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
        // The search box shows what was chosen rather than the half-typed query that found it: an
        // empty-looking field above "Launch app" reads as nothing being selected.
        backing.update { it.copy(draft = picked, problem = problemOf(picked), appQuery = app.label) }
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
            // Read first, publish second. `backing.value.copy(apps = catalog.launchable())` reads the
            // state *before* suspending, so the write lands hundreds of milliseconds later carrying a
            // snapshot from before the assignments arrived — and every assignment disappears from the
            // screen while sitting safely on disk.
            val apps = catalog.launchable()
            backing.update { it.copy(apps = apps) }
        }
    }

    fun onCancel() {
        backing.update { it.copy(draft = null, problem = null, message = null) }
    }

    fun onDraftChange(draft: ActionDraft) {
        val edited = draft.copy(touched = true)
        backing.update { it.copy(draft = edited, problem = problemOf(edited)) }
    }

    /**
     * Switches which kind of action the draft describes.
     *
     * Seeds a blank link with `https://` rather than leaving the field empty. Every link needs a
     * scheme, so an empty field's only possible next state is the "include a scheme" error — which the
     * user then has to read and fix for no reason. An existing link is never touched.
     */
    /**
     * Fetches the workspace's tags once, when they first become relevant.
     *
     * Not at construction: it is a network call, and most tags are not Toggl tags. A failure is
     * swallowed on purpose -- the picker is a convenience over a field that still works by hand, so
     * an offline phone should lose the shortcut, not the ability to save.
     */
    private fun loadTogglTags() {
        if (backing.value.togglTagOptions.isNotEmpty()) return
        viewModelScope.launch {
            toggl.tags().onSuccess { names ->
                backing.update { it.copy(togglTagOptions = names) }
            }
        }
    }

    fun onTypeChange(type: ActionType) {
        val draft = backing.value.draft ?: return
        if (type == ActionType.TOGGL) loadTogglTags()
        val seeded = when {
            type == ActionType.OPEN_URI && draft.uri.isBlank() -> draft.copy(
                type = type,
                uri = LINK_SCHEMES.first(),
            )
            else -> draft.copy(type = type)
        }
        // Deliberately not onDraftChange: choosing what kind of action this is does not count as
        // having filled the form in, so it must not start showing errors about empty fields.
        backing.update { it.copy(draft = seeded, problem = problemOf(seeded)) }
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

    /** Chooses the tag, or clears it when the same one is picked again. */
    fun onSelectTogglTag(name: String) {
        val draft = backing.value.draft ?: return
        onDraftChange(draft.copy(togglTag = if (draft.togglTag == name) "" else name))
    }

    fun onSave() {
        val draft = backing.value.draft ?: return
        // The editor stays open on an invalid draft so the problem can be corrected in place --
        // and trying to save is the moment the problem becomes worth stating.
        val action = draftAction(draft) ?: run {
            backing.update { it.copy(draft = draft.copy(touched = true)) }
            return
        }
        val uid = draft.uid ?: return

        viewModelScope.launch {
            // Stamped here rather than in the store, because this is the moment a human changed
            // something. Without it every assignment carries zero and the cloud merge can never
            // tell which side is newer.
            val assignment = TagAssignment(
                uid = uid,
                label = draft.label.trim(),
                action = action,
                updatedAtMillis = System.currentTimeMillis(),
            )
            report({ repository.save(assignment) }) {
                backing.update { it.copy(draft = null, problem = null, message = null) }
            }
        }
    }

    fun onDelete(uid: ByteBlock) {
        viewModelScope.launch {
            report({ repository.delete(uid) }) {
                // Any message on screen described the assignment that is now gone.
                backing.update { it.copy(message = null) }
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
            backing.update { it.copy(message = "${failure::class.simpleName}: ${failure.message}") }
        }
    }

    /**
     * Runs [action] and reports what happened.
     *
     * Launched rather than run inline because performing is now suspending: a gesture takes about a
     * second and a multi-step action waits between steps.
     */
    fun onTest(action: TagAction) {
        viewModelScope.launch {
            val message = performer.perform(action).fold(
                onSuccess = { "Action performed." },
                onFailure = { "${it::class.simpleName}: ${it.message}" },
            )
            backing.update { it.copy(message = message) }
        }
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
                // A preset: no fields to fill in, so nothing here can be half-typed.
                ActionType.SLEEP_CYCLE -> SleepCycle.toggle()
                // The tag's label doubles as the Toggl entry description: naming the tag "Deep work"
                // and then typing "Deep work" again into a second field is busywork.
                ActionType.WHATSAPP -> TagAction.WhatsAppMessage(
                    phoneNumber = draft.phoneNumber.trim(),
                    message = draft.messageText.trim(),
                    autoSend = draft.autoSend,
                )
                ActionType.TOGGL -> TagAction.TogglToggle(
                    // Falls back to the tag's own label, so a description is genuinely optional
                    // rather than a field you must fill to save.
                    description = draft.togglDescription.trim().ifBlank { draft.label.trim() },
                    // A list because Toggl's model is a list, holding at most the one tag chosen.
                    tags = listOfNotNull(draft.togglTag.trim().ifBlank { null }),
                )
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
        draft.type == ActionType.WHATSAPP && draft.phoneNumber.none(Char::isDigit) ->
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
        // Gestures and composites are only ever produced by a preset, and the editor offers no fields
        // for them. Editing one and saving rebuilds the preset from scratch, which is the honest
        // behaviour: there is nothing here the user could have adjusted.
        is TagAction.WhatsAppMessage -> ActionDraft(
            uid = uid,
            label = label,
            type = ActionType.WHATSAPP,
            phoneNumber = current.phoneNumber,
            messageText = current.message,
            autoSend = current.autoSend,
            isExisting = true,
        )
        is TagAction.TogglToggle -> ActionDraft(
            uid = uid,
            label = label,
            type = ActionType.TOGGL,
            togglDescription = current.description,
            togglTag = current.tags.firstOrNull().orEmpty(),
            isExisting = true,
        )
        is TagAction.DragGesture,
        is TagAction.TapNode,
        is TagAction.Steps,
        is TagAction.WhileNotificationShowing,
        -> ActionDraft(
            uid = uid,
            label = label,
            type = ActionType.SLEEP_CYCLE,
            isExisting = true,
        )
    }
}
