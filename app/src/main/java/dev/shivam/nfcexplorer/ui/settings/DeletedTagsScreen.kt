package dev.shivam.nfcexplorer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.ui.component.SectionCard

/**
 * Tags that were deleted, and the way back.
 *
 * Its own page rather than a card in settings: the list is unbounded and mostly empty, so inline it
 * was either dead space or a section that quietly grew taller than everything around it.
 *
 * Restoring needs no card. The tombstone kept the UID, label and action -- which is everything an
 * assignment is -- so this is the only route back that does not require the physical tag.
 */
@Composable
fun DeletedTagsScreen(
    deleted: List<TagAssignment>,
    onRestore: (ByteBlock) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (deleted.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_deleted_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        SectionCard(
            title = stringResource(R.string.settings_deleted_title),
            subtitle = stringResource(R.string.settings_deleted_subtitle),
            collapsible = false,
        ) {
            deleted.forEach { assignment ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = assignment.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = assignment.uid.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onRestore(assignment.uid) }) {
                        Text(stringResource(R.string.settings_deleted_restore))
                    }
                }
            }
        }
    }
}
