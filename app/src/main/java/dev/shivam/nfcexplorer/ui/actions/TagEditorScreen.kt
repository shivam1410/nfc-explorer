package dev.shivam.nfcexplorer.ui.actions

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import dev.shivam.nfcexplorer.ui.scan.ScanPulse
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.domain.action.InstalledApp
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.ui.component.SectionCard

/**
 * The page for adding or editing one tag's action.
 *
 * One page serves both, reached from the + button and from a card's Edit. A second editor for the
 * add path would drift from this one the first time either changed, and the two are the same job:
 * name a tag and choose what it does.
 *
 * Three states, in the order the add flow moves through them: wait for a tap, report that the tag is
 * already spoken for, or edit. Arriving from Edit skips straight to the third.
 */
@Composable
fun TagEditorScreen(
    state: TagActionsUiState,
    onDraftChange: (ActionDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onTestDraft: () -> Unit,
    onAppQueryChange: (String) -> Unit,
    onPickApp: (InstalledApp) -> Unit,
    onTypeChange: (ActionType) -> Unit,
    onSchemeChange: (String) -> Unit,
    onEditScanned: (TagAssignment) -> Unit,
    onScanAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft

    // The waiting state is not a form, so it does not live in the form's scrolling column: it takes
    // the whole page and centres in it, which is what makes it read as a prompt rather than as the
    // first item of a list.
    if (state.addTag is AddTagState.WaitingForTag) {
        WaitingForTag(modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            state.addTag is AddTagState.AlreadyAssigned -> AlreadyAssigned(
                assignment = state.addTag.assignment,
                onEdit = onEditScanned,
                onScanAnother = onScanAnother,
                onCancel = onCancel,
            )

            draft != null -> DraftEditor(
                state = state,
                draft = draft,
                onDraftChange = onDraftChange,
                onSave = onSave,
                onCancel = onCancel,
                onTestDraft = onTestDraft,
                onAppQueryChange = onAppQueryChange,
                onPickApp = onPickApp,
                onTypeChange = onTypeChange,
                onSchemeChange = onSchemeChange,
            )

            // Reached only if the flow was left in an impossible state, e.g. a process death that
            // restored the route but not the draft. Saying so beats a blank page.
            else -> Text(
                text = stringResource(R.string.actions_editor_gone),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The waiting state: the same pulse the scan screen uses, centred, with one line saying why.
 *
 * Deliberately identical to Discovery's "ready to scan" -- same rings, same contactless glyph. The
 * two screens ask for the same physical act, so looking different taught the user nothing and made
 * the add page read as a lesser thing.
 */
@Composable
private fun WaitingForTag(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Box so the glyph sits inside the rings rather than below them.
        Box(contentAlignment = Alignment.Center) {
            ScanPulse()
        }
        Spacer(Modifier.size(28.dp))
        Text(
            text = stringResource(R.string.actions_add_subtitle),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.actions_add_waiting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The tag already does something.
 *
 * Offering to edit rather than silently starting a fresh assignment: overwriting a working tag
 * because it happened to be the one in reach is not a mistake worth making quietly.
 */
@Composable
private fun AlreadyAssigned(
    assignment: TagAssignment,
    onEdit: (TagAssignment) -> Unit,
    onScanAnother: () -> Unit,
    onCancel: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.actions_add_taken_title),
        subtitle = assignment.uid.toString(),
    ) {
        Text(
            text = stringResource(R.string.actions_add_taken, assignment.label),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = { onEdit(assignment) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.actions_add_edit_existing))
        }
        // Two ways out, because they are different intentions: try a different card, or abandon
        // the whole flow. Collapsing them into one button made "cancel" ambiguous.
        TextButton(onClick = onScanAnother) {
            Text(stringResource(R.string.actions_add_scan_another))
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.actions_add_cancel))
        }
    }
}
