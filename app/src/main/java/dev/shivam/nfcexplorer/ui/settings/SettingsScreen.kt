package dev.shivam.nfcexplorer.ui.settings

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
import dev.shivam.nfcexplorer.ui.component.FieldShape
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
    onEditTogglToken: () -> Unit,
    onCancelTogglEdit: () -> Unit,
    onCheckToggl: () -> Unit,
    onOpenDeleted: () -> Unit,
    onRanToneChosen: (String?) -> Unit,
    onFailedToneChosen: (String?) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onToastsChange: (Boolean) -> Unit,
    onPreviewRan: (String) -> Unit,
    onPreviewFailed: (String) -> Unit,
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
            collapsible = false,
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

        TapFeedbackSection(
            state = state,
            onRanToneChosen = onRanToneChosen,
            onFailedToneChosen = onFailedToneChosen,
            onVolumeChange = onVolumeChange,
            onToastsChange = onToastsChange,
            onPreviewRan = onPreviewRan,
            onPreviewFailed = onPreviewFailed,
        )

        // Only when there is something to restore, and the card is the control: a section that
        // exists to be opened does not need a button inside it saying so.
        if (state.deleted.isNotEmpty()) {
            SectionCard(
                title = stringResource(R.string.settings_deleted_title),
                subtitle = stringResource(R.string.settings_deleted_count, state.deleted.size),
                collapsible = false,
                onClick = onOpenDeleted,
            )
        }

        SectionCard(
            title = stringResource(R.string.settings_export_title),
            subtitle = stringResource(R.string.settings_export_subtitle),
            collapsible = false,
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
            collapsible = false,
        ) {
            // Stated before the button, because "which direction does this go" is the question
            // people hesitate over, and the answer only appeared afterwards in the result line.
            Text(
                text = stringResource(R.string.settings_sync_direction),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.lastSyncedAtMillis
                    ?.let { stringResource(R.string.settings_sync_last, LAST_SYNC_FORMAT.format(java.util.Date(it))) }
                    ?: stringResource(R.string.settings_sync_never),
                style = MaterialTheme.typography.bodySmall,
            )
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
                            sync.report.logsRestored,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionCard(
            title = stringResource(R.string.settings_toggl_title),
            collapsible = false,
        ) {
            Text(
                text = state.togglTokenTail
                    ?.let { stringResource(R.string.settings_toggl_set, it) }
                    ?: stringResource(R.string.settings_toggl_unset),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.togglTokenSet) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            // A stored token gets no input box. An empty field next to "saved" reads as a
            // contradiction, and the common case here is looking, not changing.
            if (state.togglTokenSet && !state.togglEditing) {
                when (val check = state.togglCheck) {
                    TogglCheck.Idle -> Unit
                    TogglCheck.Checking -> Text(
                        text = stringResource(R.string.settings_toggl_checking),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is TogglCheck.Connected -> Text(
                        text = stringResource(R.string.settings_toggl_connected, check.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is TogglCheck.Failed -> Text(
                        text = stringResource(R.string.settings_toggl_check_failed, check.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCheckToggl) {
                        Text(stringResource(R.string.settings_toggl_check))
                    }
                    TextButton(onClick = onEditTogglToken) {
                        Text(stringResource(R.string.settings_toggl_edit))
                    }
                    TextButton(onClick = onClearTogglToken) {
                        Text(stringResource(R.string.settings_toggl_clear))
                    }
                }
            } else {
                OutlinedTextField(
                    value = state.togglDraft,
                    onValueChange = onTogglDraftChange,
                    label = { Text(stringResource(R.string.settings_toggl_token)) },
                    // Masked by default, revealable on demand: a pasted token cannot be checked
                    // otherwise, and a mistyped one surfaces much later as an opaque 403.
                    visualTransformation = if (state.togglTokenVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = onToggleTokenVisibility,
                            enabled = state.togglDraft.isNotEmpty(),
                        ) {
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
                    shape = FieldShape,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSaveTogglToken,
                        enabled = state.togglDraft.isNotBlank(),
                    ) { Text(stringResource(R.string.settings_toggl_save)) }
                    if (state.togglEditing) {
                        TextButton(onClick = onCancelTogglEdit) {
                            Text(stringResource(R.string.settings_toggl_cancel))
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.settings_toggl_explainer),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard(
            title = stringResource(R.string.settings_about_title),
            subtitle = stringResource(R.string.settings_version, state.version),
            collapsible = false,
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

/** Date and time, no seconds: sync is not an operation anyone times to the second. */
private val LAST_SYNC_FORMAT =
    java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())

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

/**
 * Tap feedback: two tones, a volume, and whether a tap names itself on screen.
 *
 * The subtitle says, in words, that Android's own discovery beep is not this app's. Without it the
 * whole section reads as broken the first time someone sets a quiet tone, taps a card, and still
 * hears the loud one — which is the exact complaint that produced this feature.
 */
@Composable
private fun TapFeedbackSection(
    state: SettingsUiState,
    onRanToneChosen: (String?) -> Unit,
    onFailedToneChosen: (String?) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onToastsChange: (Boolean) -> Unit,
    onPreviewRan: (String) -> Unit,
    onPreviewFailed: (String) -> Unit,
) {
    val previewLabel = stringResource(R.string.settings_feedback_preview_label)

    SectionCard(
        title = stringResource(R.string.settings_feedback_title),
        subtitle = stringResource(R.string.settings_feedback_subtitle),
        collapsible = false,
    ) {
        ToneRow(
            labelRes = R.string.settings_feedback_ran,
            tone = state.ranTone,
            onChosen = onRanToneChosen,
            onPreview = { onPreviewRan(previewLabel) },
        )
        ToneRow(
            labelRes = R.string.settings_feedback_failed,
            tone = state.failedTone,
            onChosen = onFailedToneChosen,
            onPreview = { onPreviewFailed(previewLabel) },
        )

        VolumeRow(percent = state.volumePercent, onVolumeChange = onVolumeChange)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.settings_feedback_toast),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.toastsEnabled, onCheckedChange = onToastsChange)
        }
    }
}

/**
 * One tone: what it is now, a way to change it, and a way to hear it.
 *
 * Preview earns its place next to the volume slider. Judging a notification tone by name is
 * guesswork, and the alternative way to hear it is to go and find a card.
 */
@Composable
private fun ToneRow(
    @androidx.annotation.StringRes labelRes: Int,
    tone: String?,
    onChosen: (String?) -> Unit,
    onPreview: () -> Unit,
) {
    val title = stringResource(labelRes)
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // A cancelled picker must not clear the tone. Only RESULT_OK carries a decision, and within
        // it a null URI is the user choosing Silent — which is why null is passed straight through
        // rather than being treated as "nothing came back".
        if (result.resultCode == Activity.RESULT_OK) {
            onChosen(pickedTone(result.data)?.toString())
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodySmall)
            Text(
                text = toneTitle(tone),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Nothing to preview when the tone is Silent, and a button that is guaranteed to do nothing
        // is worse than no button.
        if (tone != null) {
            TextButton(onClick = onPreview) {
                Text(stringResource(R.string.settings_feedback_preview))
            }
        }
        TextButton(onClick = { picker.launch(tonePickerIntent(tone, title)) }) {
            Text(stringResource(R.string.settings_feedback_choose))
        }
    }
}

/**
 * The tone's name as the system knows it, "Silent", or a warning that it has gone.
 *
 * The three cases are kept apart deliberately. A stored tone whose file has since been deleted, or
 * which lived on media that is no longer mounted, resolves to no title — and reporting that as
 * "Silent" would tell the user no sound is set when one is, sending them away from the setting that
 * needs their attention. The announcer logs the same failure when it tries to play it, but only
 * during the session it happens in; this row is where someone actually looks.
 *
 * Resolving a title is a media-store lookup, so it is remembered against the URI rather than run on
 * every recomposition.
 */
@Composable
private fun toneTitle(tone: String?): String {
    val context = LocalContext.current
    if (tone == null) return stringResource(R.string.settings_feedback_silent)
    val resolved = remember(tone) { resolveToneTitle(context, tone) }
    return resolved ?: stringResource(R.string.settings_feedback_tone_missing)
}

/**
 * Android's own notification-tone picker, opened on whatever is currently chosen.
 *
 * `SHOW_SILENT` is what makes "no sound" reachable, and it is the default for both tones.
 * `SHOW_DEFAULT` is off deliberately: "Default notification sound" is a moving target that follows
 * a system setting, and a tap tone that changes when an unrelated setting does would be baffling.
 */
private fun tonePickerIntent(tone: String?, title: String) =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, tone?.let(Uri::parse))
    }

/** The typed accessor arrived in API 33; `minSdk` is 26, so the deprecated form is still needed. */
private fun pickedTone(data: Intent?): Uri? = when {
    data == null -> null
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
    else -> {
        @Suppress("DEPRECATION")
        data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
    }
}

/**
 * The tone volume, written when the drag ends rather than while it moves.
 *
 * The slider holds its own position mid-drag: routing every frame through the view model would be
 * a preference write per pixel, and the store clamps and reads back on each one. On release the
 * local position is dropped and the stored value takes over again, so what is on screen is always
 * what was actually saved.
 *
 * This is the app's tone only. Android's discovery beep follows the device notification volume and
 * this slider cannot reach it.
 */
@Composable
private fun VolumeRow(percent: Int, onVolumeChange: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: percent.toFloat()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.settings_feedback_volume),
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onVolumeChange(it.toInt()) }
                dragging = null
            },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.settings_feedback_volume_value, shown.toInt()),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * The stored tone's display name, or null when the URI no longer resolves.
 *
 * The open is the load-bearing half. `RingtoneManager.getRingtone(...).getTitle(...)` does not
 * return null for a tone whose media row has been deleted — it falls back to the URI's last path
 * segment, so a deleted tone rendered as a bare id like "38". Asking the content resolver whether
 * the URI can actually be opened is the same question `MediaPlayer` will ask a moment later, which
 * is exactly why it is the right one to ask here.
 */
private fun resolveToneTitle(context: android.content.Context, tone: String): String? {
    val uri = Uri.parse(tone)
    val opens = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.also { it.close() } != null
    }.getOrDefault(false)
    if (!opens) return null
    return runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()
}
