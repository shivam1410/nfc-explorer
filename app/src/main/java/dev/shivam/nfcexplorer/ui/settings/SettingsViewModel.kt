package dev.shivam.nfcexplorer.ui.settings

import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shivam.nfcexplorer.domain.action.ActionPerformer
import dev.shivam.nfcexplorer.domain.action.SystemGrantState
import dev.shivam.nfcexplorer.domain.action.SystemGrants
import dev.shivam.nfcexplorer.domain.action.SystemSettings
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.secret.SecretStore
import dev.shivam.nfcexplorer.domain.update.AppVersion
import dev.shivam.nfcexplorer.data.update.UpdateInstaller
import dev.shivam.nfcexplorer.domain.update.AppRelease
import dev.shivam.nfcexplorer.domain.update.InstallStatus
import dev.shivam.nfcexplorer.domain.update.InstalledVersion
import dev.shivam.nfcexplorer.domain.update.ReleaseSource
import dev.shivam.nfcexplorer.data.sync.Authorization
import dev.shivam.nfcexplorer.data.sync.AccessTokens
import dev.shivam.nfcexplorer.domain.sync.CloudSync
import dev.shivam.nfcexplorer.domain.toggl.TogglSession
import dev.shivam.nfcexplorer.domain.sync.SyncReport
import dev.shivam.nfcexplorer.domain.update.UpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Whether the saved Toggl token actually works. */
sealed interface TogglCheck {
    data object Idle : TogglCheck
    data object Checking : TogglCheck

    /** [workspaceId] was discovered from the token; the user never types it. */
    data class Connected(val name: String, val workspaceId: Long?) : TogglCheck
    data class Failed(val reason: String) : TogglCheck
}

/** Where a Drive sync has got to. */
sealed interface SyncUiState {
    data object Idle : SyncUiState
    data object Running : SyncUiState

    /**
     * Google needs the user to approve the scope. The activity launches this; nothing else can.
     */
    data class NeedsConsent(val pendingIntent: PendingIntent) : SyncUiState

    data class Done(val report: SyncReport) : SyncUiState
    data class Failed(val reason: String) : SyncUiState
}

data class SettingsUiState(
    val grants: SystemGrantState = SystemGrantState(),
    val version: String = "",
    val update: UpdateStatus = UpdateStatus.Idle,
    /**
     * Whether a Toggl token is stored — never the token itself.
     *
     * The value is deliberately not lifted into UI state. Anything in state is one careless log or
     * screenshot away from being visible, and nothing on screen needs it: "set" is the only fact the
     * user is asking about.
     */
    val togglTokenSet: Boolean = false,
    val togglDraft: String = "",
    /** Whether the token field is unmasked. Never persisted; resets with the screen. */
    val togglTokenVisible: Boolean = false,
    /**
     * Whether the token field is open.
     *
     * A stored credential needs no input box; showing an empty one invites the reading that nothing
     * is saved. The field appears when there is nothing yet, or when the user asks to replace it.
     */
    val togglEditing: Boolean = false,
    /**
     * The tail of the stored token, e.g. `1a2b`.
     *
     * Enough to recognise which token is saved without putting the secret on screen — "saved" alone
     * left no way to tell a successful save from a silently cleared field.
     */
    val togglTokenTail: String? = null,
    val sync: SyncUiState = SyncUiState.Idle,
    val togglCheck: TogglCheck = TogglCheck.Idle,
    val install: InstallStatus = InstallStatus.Idle,
)

/**
 * The settings surface: what this app has been granted, and whether it is current.
 *
 * Both grants live here rather than only inside the action editor, because they are properties of the
 * app rather than of one action, and because a revoked permission needs somewhere to be discovered
 * that is not "the tag stopped working".
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val grants: SystemGrants,
    private val performer: ActionPerformer,
    private val releases: ReleaseSource,
    private val secrets: SecretStore,
    private val tokens: AccessTokens,
    private val cloudSync: CloudSync,
    private val installer: UpdateInstaller,
    private val toggl: TogglSession,
    installedVersion: InstalledVersion,
) : ViewModel() {

    private val backing = MutableStateFlow(SettingsUiState(version = installedVersion.name()))
    val state: StateFlow<SettingsUiState> = backing.asStateFlow()

    init {
        refreshGrants()
        refreshTogglToken()
    }

    /** Reads only whether a token exists and its last few characters, never the whole value. */
    private fun refreshTogglToken() {
        val stored = secrets.read(SecretStore.TOGGL_TOKEN)
        backing.update {
            it.copy(
                togglTokenSet = stored != null,
                togglTokenTail = stored?.takeLast(TOKEN_TAIL_LENGTH),
            )
        }
    }

    /**
     * Asks Toggl who the saved token belongs to.
     *
     * Doubles as the workspace lookup: `/me` carries the default workspace, so a working token is
     * all the configuration this needs. It also turns a typo into an error you see now rather than
     * a tag that silently does nothing at bedtime.
     */
    fun onCheckToggl() {
        if (!secrets.has(SecretStore.TOGGL_TOKEN)) return
        backing.update { it.copy(togglCheck = TogglCheck.Checking) }
        viewModelScope.launch {
            val status = toggl.account().fold(
                onSuccess = { TogglCheck.Connected(it.fullName, it.workspaceId) },
                onFailure = { TogglCheck.Failed("${it::class.simpleName}: ${it.message}") },
            )
            backing.update { it.copy(togglCheck = status) }
        }
    }

    fun onEditTogglToken() {
        backing.update { it.copy(togglEditing = true, togglDraft = "", togglTokenVisible = false) }
    }

    fun onCancelTogglEdit() {
        backing.update { it.copy(togglEditing = false, togglDraft = "", togglTokenVisible = false) }
    }

    fun onToggleTokenVisibility() {
        backing.update { it.copy(togglTokenVisible = !it.togglTokenVisible) }
    }

    fun onTogglDraftChange(value: String) {
        backing.update { it.copy(togglDraft = value) }
    }

    /** Saves the token and immediately forgets the draft, so it does not linger in UI state. */
    fun onSaveTogglToken() {
        val token = backing.value.togglDraft.trim()
        if (token.isBlank()) return
        secrets.write(SecretStore.TOGGL_TOKEN, token)
        backing.update { it.copy(togglDraft = "", togglTokenVisible = false, togglEditing = false) }
        refreshTogglToken()
        // Verify immediately: a token is worth nothing until it has answered once.
        onCheckToggl()
    }

    fun onClearTogglToken() {
        secrets.clear(SecretStore.TOGGL_TOKEN)
        // Straight into the field: clearing is almost always the first half of replacing.
        backing.update {
            it.copy(
                togglDraft = "",
                togglTokenVisible = false,
                togglEditing = true,
                togglCheck = TogglCheck.Idle,
            )
        }
        refreshTogglToken()
    }

    /** Re-read on every resume: both grants are made outside the app and revocable at any time. */
    fun refreshGrants() {
        backing.update { it.copy(grants = grants.current()) }
    }

    /**
     * Syncs, asking for consent first if the scope has never been granted.
     *
     * Consent is surfaced rather than launched: only an Activity can start the PendingIntent, and
     * Google's own screen is where that decision belongs.
     */
    fun onSyncNow() {
        backing.update { it.copy(sync = SyncUiState.Running) }
        viewModelScope.launch {
            when (val authorization = tokens.authorize()) {
                is Authorization.NeedsConsent ->
                    backing.update { it.copy(sync = SyncUiState.NeedsConsent(authorization.pendingIntent)) }

                is Authorization.Failed ->
                    backing.update { it.copy(sync = SyncUiState.Failed(authorization.reason)) }

                is Authorization.Token -> runSync()
            }
        }
    }

    /** Called after the consent screen returns, whatever the user chose. */
    fun onConsentResult(granted: Boolean) {
        if (!granted) {
            backing.update { it.copy(sync = SyncUiState.Failed("Access to Google Drive was declined")) }
            return
        }
        onSyncNow()
    }

    private suspend fun runSync() {
        val status = cloudSync.sync(System.currentTimeMillis()).fold(
            onSuccess = { SyncUiState.Done(it) },
            onFailure = { SyncUiState.Failed("${it::class.simpleName}: ${it.message}") },
        )
        backing.update { it.copy(sync = status) }
    }

    fun onOpenNotificationAccess() = openSettings(SystemSettings.openNotificationAccess())

    fun onOpenAccessibilitySettings() = openSettings(SystemSettings.openAccessibility())

    /**
     * Asks GitHub what the newest published build is.
     *
     * A failure is reported as a failure. Reporting "up to date" when the network call never landed
     * would be indistinguishable from actually being current, and would hide a real update forever.
     */
    fun onCheckForUpdates() {
        val current = backing.value.version
        backing.update { it.copy(update = UpdateStatus.Checking) }
        viewModelScope.launch {
            val status = releases.latest().fold(
                onSuccess = { release ->
                    when {
                        release == null -> UpdateStatus.UpToDate(current)
                        AppVersion.isNewer(release.tag, current) ->
                            UpdateStatus.Available(current, release)
                        else -> UpdateStatus.UpToDate(current)
                    }
                },
                onFailure = { UpdateStatus.Failed("${it::class.simpleName}: ${it.message}") },
            )
            backing.update { it.copy(update = status) }
        }
    }

    /**
     * Downloads the release APK and hands it to the system installer.
     *
     * The install itself is never silent: Android shows its own confirmation, and refuses entirely
     * unless this app has been allowed to install unknown apps. That permission is checked *after*
     * the download rather than before, so a user who grants it at the prompt does not have to
     * download twice.
     */
    fun onDownloadAndInstall(release: AppRelease) {
        val url = release.apkUrl
        if (url == null) {
            backing.update {
                it.copy(install = InstallStatus.Failed("That release has no APK attached"))
            }
            return
        }
        backing.update { it.copy(install = InstallStatus.Downloading) }
        viewModelScope.launch {
            installer.download(url, release.tag).fold(
                onSuccess = { file ->
                    if (!installer.canInstall()) {
                        backing.update { it.copy(install = InstallStatus.NeedsPermission) }
                        return@fold
                    }
                    val status = installer.install(file).fold(
                        onSuccess = { InstallStatus.Handed },
                        onFailure = { InstallStatus.Failed("${it::class.simpleName}: ${it.message}") },
                    )
                    backing.update { it.copy(install = status) }
                },
                onFailure = { failure ->
                    backing.update {
                        it.copy(
                            install = InstallStatus.Failed(
                                "${failure::class.simpleName}: ${failure.message}",
                            ),
                        )
                    }
                },
            )
        }
    }

    /** Sends the user to the screen where installing unknown apps is allowed. */
    fun onAllowInstalls() {
        viewModelScope.launch {
            runCatching { installer.unknownSourcesIntent() }
                .onSuccess { intent -> performer.perform(TagAction.SendIntent(intent.action.orEmpty(), intent.data?.toString())) }
        }
    }

    /** Opens the release page in a browser. Installing is the user's decision, not the app's. */
    fun onOpenRelease(url: String) = openSettings(TagAction.OpenUri(url))

    private companion object {
        const val TOKEN_TAIL_LENGTH = 4
    }

    private fun openSettings(action: TagAction) {
        viewModelScope.launch { performer.perform(action) }
    }
}
