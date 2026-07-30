package dev.shivam.nfcexplorer.ui.actions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.domain.action.InstalledApp
import dev.shivam.nfcexplorer.domain.action.MediaKey
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.ui.component.SectionCard
import dev.shivam.nfcexplorer.ui.theme.HexTextStyle

/**
 * Manage which tag does what.
 *
 * States the silent-on-unmapped behaviour explicitly, because a tap that deliberately does nothing is
 * indistinguishable from a broken app until you know that is the design.
 */
@Composable
fun TagActionsScreen(
    state: TagActionsUiState,
    lastScannedUid: ByteBlock?,
    onCreateFor: (ByteBlock?) -> Unit,
    onEdit: (TagAssignment) -> Unit,
    onDraftChange: (ActionDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (ByteBlock) -> Unit,
    onTest: (TagAction) -> Unit,
    onTestDraft: () -> Unit,
    onAppQueryChange: (String) -> Unit,
    onPickApp: (InstalledApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.actions_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.draft == null) {
            Button(
                onClick = { onCreateFor(lastScannedUid) },
                enabled = lastScannedUid != null,
            ) {
                Text(stringResource(R.string.actions_assign_last_scan))
            }
            if (lastScannedUid == null) {
                Text(
                    text = stringResource(R.string.actions_scan_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        } else {
            DraftEditor(
                state = state,
                draft = state.draft,
                onDraftChange = onDraftChange,
                onSave = onSave,
                onCancel = onCancel,
                onTestDraft = onTestDraft,
                onAppQueryChange = onAppQueryChange,
                onPickApp = onPickApp,
            )
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (state.assignments.isEmpty()) {
            Text(
                text = stringResource(R.string.actions_none_yet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.assignments.forEach { assignment ->
                AssignmentCard(
                    assignment = assignment,
                    onEdit = { onEdit(assignment) },
                    onDelete = { onDelete(assignment.uid) },
                    onTest = { onTest(assignment.action) },
                )
            }
        }
    }
}

@Composable
private fun AssignmentCard(
    assignment: TagAssignment,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    SectionCard(title = assignment.label, subtitle = summarise(assignment.action)) {
        Text(text = assignment.uid.toString(), style = HexTextStyle)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTest) { Text(stringResource(R.string.actions_test)) }
            OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.actions_edit)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.actions_delete)) }
        }
    }
}

@Composable
private fun DraftEditor(
    state: TagActionsUiState,
    draft: ActionDraft,
    onDraftChange: (ActionDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onTestDraft: () -> Unit,
    onAppQueryChange: (String) -> Unit,
    onPickApp: (InstalledApp) -> Unit,
) {
    SectionCard(
        title = stringResource(
            if (draft.isExisting) R.string.actions_edit_title else R.string.actions_new_title,
        ),
        subtitle = draft.uid?.toString(),
    ) {
        OutlinedTextField(
            value = draft.label,
            onValueChange = { onDraftChange(draft.copy(label = it)) },
            label = { Text(stringResource(R.string.actions_label)) },
            isError = state.problem == DraftProblem.BLANK_LABEL,
            modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            ActionType.entries.forEach { type ->
                FilterChip(
                    selected = draft.type == type,
                    onClick = { onDraftChange(draft.copy(type = type)) },
                    label = { Text(stringResource(type.labelRes())) },
                )
            }
        }

        when (draft.type) {
            ActionType.LAUNCH_APP -> AppPicker(
                state = state,
                chosen = draft.packageName,
                onQueryChange = onAppQueryChange,
                onPick = onPickApp,
            )

            ActionType.OPEN_URI -> OutlinedTextField(
                value = draft.uri,
                onValueChange = { onDraftChange(draft.copy(uri = it)) },
                label = { Text(stringResource(R.string.actions_uri)) },
                isError = state.problem == DraftProblem.MISSING_TARGET ||
                    state.problem == DraftProblem.INVALID_URI,
                modifier = Modifier.fillMaxWidth(),
            )

            ActionType.SEND_INTENT -> {
                OutlinedTextField(
                    value = draft.intentAction,
                    onValueChange = { onDraftChange(draft.copy(intentAction = it)) },
                    label = { Text(stringResource(R.string.actions_intent_action)) },
                    isError = state.problem == DraftProblem.MISSING_TARGET,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.uri,
                    onValueChange = { onDraftChange(draft.copy(uri = it)) },
                    label = { Text(stringResource(R.string.actions_uri_optional)) },
                    isError = state.problem == DraftProblem.INVALID_URI,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ActionType.MEDIA -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MediaKey.entries.forEach { key ->
                    FilterChip(
                        selected = draft.mediaKey == key,
                        onClick = { onDraftChange(draft.copy(mediaKey = key)) },
                        label = { Text(stringResource(key.labelRes())) },
                    )
                }
            }
        }

        state.problem?.let { problem ->
            Text(
                text = stringResource(problem.labelRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Button(onClick = onSave, enabled = state.canSave) {
                Text(stringResource(R.string.actions_save))
            }
            // Try it before committing: enabled on the same condition as save, since a testable
            // draft and a saveable one are the same thing.
            OutlinedButton(onClick = onTestDraft, enabled = state.canSave) {
                Text(stringResource(R.string.actions_test))
            }
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.actions_cancel)) }
        }
    }
}

/**
 * Choose an installed app instead of typing its package name.
 *
 * The editor originally took the package as free text, which meant knowing that YouTube Music is
 * `com.google.android.apps.youtube.music` — it mirrored the shape of the domain type rather than
 * anything a person knows. Picking from the launchable apps also makes an unlaunchable target
 * unreachable rather than merely discouraged.
 *
 * The list is capped rather than scrolled: this sits inside the screen's own `verticalScroll`, where a
 * lazy list has unbounded height and would crash. The cap is stated rather than silent, because a list
 * that quietly stops short reads as "your app is not installed".
 */
@Composable
private fun AppPicker(
    state: TagActionsUiState,
    chosen: String,
    onQueryChange: (String) -> Unit,
    onPick: (InstalledApp) -> Unit,
) {
    OutlinedTextField(
        value = state.appQuery,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.actions_app_search)) },
        // Not marked as an error when no app is chosen yet: what is missing is the selection, not the
        // query. Painting the search box red reads as "that search is invalid" and sends the user to
        // fix the one thing that was fine. The problem line below already says what is needed.
        modifier = Modifier.fillMaxWidth(),
    )

    if (chosen.isNotBlank()) {
        Text(
            text = stringResource(R.string.actions_app_chosen, chosen),
            style = HexTextStyle,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    val matches = state.visibleApps
    when {
        state.apps.isEmpty() -> Text(
            text = stringResource(R.string.actions_app_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        matches.isEmpty() -> Text(
            text = stringResource(R.string.actions_app_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )

        else -> {
            matches.take(APP_LIST_LIMIT).forEach { app ->
                AppRow(app = app, isChosen = app.packageName == chosen, onPick = onPick)
            }
            if (matches.size > APP_LIST_LIMIT) {
                Text(
                    text = stringResource(
                        R.string.actions_app_capped,
                        APP_LIST_LIMIT,
                        matches.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, isChosen: Boolean, onPick: (InstalledApp) -> Unit) {
    HorizontalDivider()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(app) }
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isChosen) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        // Kept visible: it is what actually gets stored, and two apps can share a label.
        Text(
            text = app.packageName,
            style = HexTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How many matches to show before asking for a narrower search. */
private const val APP_LIST_LIMIT = 8

@Composable
private fun summarise(action: TagAction): String = when (action) {
    is TagAction.LaunchApp -> stringResource(R.string.actions_summary_launch, action.packageName)
    is TagAction.OpenUri -> stringResource(R.string.actions_summary_uri, action.uri)
    is TagAction.SendIntent -> stringResource(R.string.actions_summary_intent, action.action)
    is TagAction.MediaCommand ->
        stringResource(R.string.actions_summary_media, stringResource(action.key.labelRes()))
}

private fun ActionType.labelRes(): Int = when (this) {
    ActionType.LAUNCH_APP -> R.string.actions_type_launch
    ActionType.OPEN_URI -> R.string.actions_type_uri
    ActionType.SEND_INTENT -> R.string.actions_type_intent
    ActionType.MEDIA -> R.string.actions_type_media
}

private fun MediaKey.labelRes(): Int = when (this) {
    MediaKey.PLAY_PAUSE -> R.string.actions_media_play_pause
    MediaKey.NEXT -> R.string.actions_media_next
    MediaKey.PREVIOUS -> R.string.actions_media_previous
}

private fun DraftProblem.labelRes(): Int = when (this) {
    DraftProblem.NO_TAG -> R.string.actions_problem_no_tag
    DraftProblem.BLANK_LABEL -> R.string.actions_problem_blank_label
    DraftProblem.MISSING_TARGET -> R.string.actions_problem_missing_target
    DraftProblem.INVALID_URI -> R.string.actions_problem_invalid_uri
}
