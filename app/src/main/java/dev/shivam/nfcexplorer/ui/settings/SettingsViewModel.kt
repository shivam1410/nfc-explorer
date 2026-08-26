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
import dev.shivam.nfcexplorer.domain.update.InstalledVersion
import dev.shivam.nfcexplorer.domain.update.ReleaseSource
import dev.shivam.nfcexplorer.data.sync.Authorization
import dev.shivam.nfcexplorer.data.sync.CloudSyncService
import dev.shivam.nfcexplorer.data.sync.GoogleAccessTokens
import dev.shivam.nfcexplorer.domain.sync.SyncReport
import dev.shivam.nfcexplorer.domain.update.UpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    val sync: SyncUiState = SyncUiState.Idle,
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
    private val tokens: GoogleAccessTokens,
    private val cloudSync: CloudSyncService,
    installedVersion: InstalledVersion,
) : ViewModel() {

    private val backing = MutableStateFlow(SettingsUiState(version = installedVersion.name()))
    val state: StateFlow<SettingsUiState> = backing.asStateFlow()

    init {
        refreshGrants()
        backing.update { it.copy(togglTokenSet = secrets.has(SecretStore.TOGGL_TOKEN)) }
    }

    fun onTogglDraftChange(value: String) {
        backing.update { it.copy(togglDraft = value) }
    }

    /** Saves the token and immediately forgets the draft, so it does not linger in UI state. */
    fun onSaveTogglToken() {
        val token = backing.value.togglDraft.trim()
        if (token.isBlank()) return
        secrets.write(SecretStore.TOGGL_TOKEN, token)
        backing.update { it.copy(togglDraft = "", togglTokenSet = true) }
    }

    fun onClearTogglToken() {
        secrets.clear(SecretStore.TOGGL_TOKEN)
        backing.update { it.copy(togglDraft = "", togglTokenSet = false) }
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

    /** Opens the release page in a browser. Installing is the user's decision, not the app's. */
    fun onOpenRelease(url: String) = openSettings(TagAction.OpenUri(url))

    private fun openSettings(action: TagAction) {
        viewModelScope.launch { performer.perform(action) }
    }
}
