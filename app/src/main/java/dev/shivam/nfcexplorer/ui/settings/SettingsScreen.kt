package dev.shivam.nfcexplorer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.domain.update.UpdateStatus
import dev.shivam.nfcexplorer.ui.component.SectionCard

/**
 * Permissions and version.
 *
 * States every permission in words and offers the way to change it, because the alternative — a tag
 * that quietly does nothing — is indistinguishable from a broken app.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onOpenRelease: (String) -> Unit,
    onSyncNow: () -> Unit,
    onTogglDraftChange: (String) -> Unit,
    onSaveTogglToken: () -> Unit,
    onClearTogglToken: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard(
            title = stringResource(R.string.settings_permissions_title),
            subtitle = stringResource(R.string.settings_permissions_subtitle),
        ) {
            GrantRow(
                granted = state.grants.notificationAccess,
                labelRes = R.string.actions_grant_notifications,
                onOpen = onOpenNotificationAccess,
            )
            GrantRow(
                granted = state.grants.gestureService,
                labelRes = R.string.actions_grant_accessibility,
                onOpen = onOpenAccessibilitySettings,
            )
        }

        SectionCard(
            title = stringResource(R.string.settings_sync_title),
            subtitle = stringResource(R.string.settings_sync_subtitle),
        ) {
            Button(
                onClick = onSyncNow,
                enabled = state.sync !is SyncUiState.Running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_sync_now)) }

            when (val sync = state.sync) {
                SyncUiState.Idle -> Unit
                SyncUiState.Running -> Text(
                    text = stringResource(R.string.settings_sync_running),
                    style = MaterialTheme.typography.bodySmall,
                )
                // The consent screen is launched by the activity; nothing to show but why we paused.
                is SyncUiState.NeedsConsent -> Text(
                    text = stringResource(R.string.settings_sync_consent),
                    style = MaterialTheme.typography.bodySmall,
                )
                is SyncUiState.Failed -> Text(
                    text = stringResource(R.string.settings_sync_failed, sync.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                is SyncUiState.Done -> Text(
                    // Says what moved rather than just "done": a sync that silently did nothing and
                    // a sync that pulled four tags should not read identically.
                    text = if (sync.report.quiet) {
                        stringResource(R.string.settings_sync_quiet)
                    } else {
                        stringResource(
                            R.string.settings_sync_done,
                            sync.report.pulled,
                            sync.report.pushed,
                            sync.report.logsUploaded,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionCard(
            title = stringResource(R.string.settings_toggl_title),
            subtitle = stringResource(
                if (state.togglTokenSet) R.string.settings_toggl_set
                else R.string.settings_toggl_unset,
            ),
        ) {
            OutlinedTextField(
                value = state.togglDraft,
                onValueChange = onTogglDraftChange,
                label = { Text(stringResource(R.string.settings_toggl_token)) },
                // Masked, and never echoed back after saving: the stored value is not readable here.
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSaveTogglToken,
                    enabled = state.togglDraft.isNotBlank(),
                ) { Text(stringResource(R.string.settings_toggl_save)) }
                TextButton(
                    onClick = onClearTogglToken,
                    enabled = state.togglTokenSet,
                ) { Text(stringResource(R.string.settings_toggl_clear)) }
            }
            Text(
                text = stringResource(R.string.settings_toggl_explainer),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard(
            title = stringResource(R.string.settings_about_title),
            subtitle = stringResource(R.string.settings_version, state.version),
        ) {
            Button(onClick = onCheckForUpdates, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_check_updates))
            }

            when (val update = state.update) {
                UpdateStatus.Idle -> Unit
                UpdateStatus.Checking -> Text(
                    text = stringResource(R.string.settings_update_checking),
                    style = MaterialTheme.typography.bodySmall,
                )
                is UpdateStatus.UpToDate -> Text(
                    text = stringResource(R.string.settings_update_current, update.current),
                    style = MaterialTheme.typography.bodySmall,
                )
                // Named as a failure rather than folded into "up to date": a check that never
                // reached GitHub proves nothing about whether an update exists.
                is UpdateStatus.Failed -> Text(
                    text = stringResource(R.string.settings_update_failed, update.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                is UpdateStatus.Available -> Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.settings_update_available,
                            update.release.name,
                            update.current,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { onOpenRelease(update.release.pageUrl) }) {
                        Text(stringResource(R.string.settings_update_open))
                    }
                }
            }
        }
    }
}

/** One permission, its state in words, and the way to change it. */
@Composable
private fun GrantRow(granted: Boolean, labelRes: Int, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                if (granted) R.string.actions_grant_on else R.string.actions_grant_off,
            ),
            style = MaterialTheme.typography.labelSmall,
        )
        TextButton(onClick = onOpen) {
            Text(
                stringResource(
                    if (granted) R.string.actions_grant_review else R.string.actions_grant_open,
                ),
            )
        }
    }
}
