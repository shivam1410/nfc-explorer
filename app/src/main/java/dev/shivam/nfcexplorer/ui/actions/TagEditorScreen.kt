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
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val draft = state.draft
        when {
            state.addTag is AddTagState.WaitingForTag -> WaitingForTag()

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
 * The waiting state: rings pulsing outward from a centre, and one line saying why.
 *
 * Animated rather than a static card on purpose. This screen is asking the user to do something
 * physical with their hands, and a motionless panel of text reads as a finished page rather than as
 * a prompt. Movement is what says "the app is listening, now tap".
 *
 * Three rings staggered across one cycle, each expanding and fading. Kept cheap: a single Canvas and
 * three float animations, no recomposition per frame beyond the draw.
 */
@Composable
private fun WaitingForTag() {
    val transition = rememberInfiniteTransition(label = "waiting")
    val phases = List(RING_COUNT) { index ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(RING_CYCLE_MILLIS, easing = LinearEasing),
                // Staggered so the rings chase each other rather than pulsing as one blob.
                initialStartOffset = StartOffset(index * RING_CYCLE_MILLIS / RING_COUNT),
            ),
            label = "ring$index",
        )
    }

    val ringColour = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Canvas(modifier = Modifier.size(RING_FIELD)) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension / 2f

            phases.forEach { phase ->
                val progress = phase.value
                drawCircle(
                    color = ringColour,
                    radius = maxRadius * progress,
                    center = centre,
                    // Fades as it grows, so the outermost ring dissolves rather than clipping.
                    alpha = (1f - progress).coerceIn(0f, 1f) * RING_MAX_ALPHA,
                    style = Stroke(width = RING_STROKE.toPx()),
                )
            }
            drawCircle(color = ringColour, radius = CORE_RADIUS.toPx(), center = centre)
        }

        Text(
            text = stringResource(R.string.actions_add_subtitle),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.actions_add_waiting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val RING_COUNT = 3
private const val RING_CYCLE_MILLIS = 1_800
private const val RING_MAX_ALPHA = 0.55f
private val RING_FIELD = 176.dp
private val RING_STROKE = 2.dp
private val CORE_RADIUS = 6.dp

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
