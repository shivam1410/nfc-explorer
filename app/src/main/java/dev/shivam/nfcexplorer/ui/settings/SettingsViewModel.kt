package dev.shivam.nfcexplorer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shivam.nfcexplorer.domain.action.ActionPerformer
import dev.shivam.nfcexplorer.domain.action.SystemGrantState
import dev.shivam.nfcexplorer.domain.action.SystemGrants
import dev.shivam.nfcexplorer.domain.action.SystemSettings
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.update.AppVersion
import dev.shivam.nfcexplorer.domain.update.InstalledVersion
import dev.shivam.nfcexplorer.domain.update.ReleaseSource
import dev.shivam.nfcexplorer.domain.update.UpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val grants: SystemGrantState = SystemGrantState(),
    val version: String = "",
    val update: UpdateStatus = UpdateStatus.Idle,
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
    installedVersion: InstalledVersion,
) : ViewModel() {

    private val backing = MutableStateFlow(SettingsUiState(version = installedVersion.name()))
    val state: StateFlow<SettingsUiState> = backing.asStateFlow()

    init {
        refreshGrants()
    }

    /** Re-read on every resume: both grants are made outside the app and revocable at any time. */
    fun refreshGrants() {
        backing.update { it.copy(grants = grants.current()) }
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
