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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.domain.export.ExportFormat
import dev.shivam.nfcexplorer.ui.log.ExportResult
import dev.shivam.nfcexplorer.domain.update.AppRelease
import dev.shivam.nfcexplorer.domain.update.InstallStatus
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
    onDownloadAndInstall: (AppRelease) -> Unit,
    onAllowInstalls: () -> Unit,
    onSyncNow: () -> Unit,
    exportResult: ExportResult?,
    onExport: (ExportFormat) -> Unit,
    onTogglDraftChange: (String) -> Unit,
    onSaveTogglToken: () -> Unit,
    onClearTogglToken: () -> Unit,
    onToggleTokenVisibility: () -> Unit,
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
            title = stringResource(R.string.settings_export_title),
            subtitle = stringResource(R.string.settings_export_subtitle),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onExport(ExportFormat.JSON) }) {
                    Text(stringResource(R.string.export_json))
                }
                OutlinedButton(onClick = { onExport(ExportFormat.TEXT) }) {
                    Text(stringResource(R.string.export_txt))
                }
            }
            exportResult?.let { result ->
                Text(
                    text = when (result) {
                        is ExportResult.Written -> stringResource(
                            R.string.export_written,
                            result.bytes,
                            result.format.extension,
                        )
                        is ExportResult.Failed -> stringResource(R.string.export_failed, result.reason)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (result) {
                        is ExportResult.Written -> MaterialTheme.colorScheme.primary
                        is ExportResult.Failed -> MaterialTheme.colorScheme.error
                    },
                )
            }
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
            subtitle = state.togglTokenTail
                ?.let { stringResource(R.string.settings_toggl_set, it) }
                ?: stringResource(R.string.settings_toggl_unset),
        ) {
            OutlinedTextField(
                value = state.togglDraft,
                onValueChange = onTogglDraftChange,
                label = { Text(stringResource(R.string.settings_toggl_token)) },
                // Masked, and never echoed back after saving: the stored value is not readable here.
                // Masked by default, revealable on demand: a pasted token is impossible to check
                // otherwise, and a mistyped one fails later as an opaque 403.
                visualTransformation = if (state.togglTokenVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = onToggleTokenVisibility) {
                        Icon(
                            painter = painterResource(
                                if (state.togglTokenVisible) R.drawable.ic_eye_off
                                else R.drawable.ic_eye,
                            ),
                            contentDescription = stringResource(
                                if (state.togglTokenVisible) R.string.settings_toggl_hide
                                else R.string.settings_toggl_show,
                            ),
                        )
                    }
                },
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

                    // Only offered when the release actually carries an APK; a source-only release
                    // would otherwise present a button that could not work.
                    if (update.release.apkUrl != null) {
                        Button(
                            onClick = { onDownloadAndInstall(update.release) },
                            enabled = state.install !is InstallStatus.Downloading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.settings_update_install)) }
                    }
                    TextButton(onClick = { onOpenRelease(update.release.pageUrl) }) {
                        Text(stringResource(R.string.settings_update_open))
                    }

                    when (val install = state.install) {
                        InstallStatus.Idle -> Unit
                        InstallStatus.Downloading -> Text(
                            text = stringResource(R.string.settings_install_downloading),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // Not an error: the download worked and one toggle stands in the way.
                        InstallStatus.NeedsPermission -> Column {
                            Text(
                                text = stringResource(R.string.settings_install_needs_permission),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = onAllowInstalls) {
                                Text(stringResource(R.string.settings_install_allow))
                            }
                        }
                        InstallStatus.Handed -> Text(
                            text = stringResource(R.string.settings_install_handed),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        is InstallStatus.Failed -> Text(
                            text = stringResource(R.string.settings_install_failed, install.reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
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
