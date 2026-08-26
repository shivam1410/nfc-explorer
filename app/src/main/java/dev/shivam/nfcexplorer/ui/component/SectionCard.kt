package dev.shivam.nfcexplorer.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.shivam.nfcexplorer.R

/**
 * A titled card, optionally expandable.
 *
 * The header row is the whole clickable area and is at least 48 dp tall, so it stays a comfortable
 * touch target despite the dense content these cards usually hold. Descendants are merged for
 * accessibility so a screen reader announces "title, collapsed" as one control rather than reading
 * the title and the chevron separately.
 *
 * [collapsible] exists for sections that are always worth reading. Collapsing earns its place on the
 * tag screens, where a memory dump is long and mostly skimmed; it does not on a settings section of
 * three controls, where a chevron only adds a way to hide the thing the user came to find.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /**
     * Drawn before the title. A slot rather than a drawable id, because some cards front a real app
     * icon loaded from the package manager rather than one of this app's vectors.
     */
    icon: (@Composable () -> Unit)? = null,
    initiallyExpanded: Boolean = true,
    collapsible: Boolean = true,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val isOpen = expanded || !collapsible
    val chevronRotation by animateFloatAsState(
        targetValue = if (isOpen) 180f else 0f,
        label = "sectionChevron",
    )
    val toggleLabel = stringResource(
        if (expanded) R.string.action_collapse else R.string.action_expand,
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    // A fixed card has no toggle, so the header is not a control and must not
                    // advertise itself as one to a screen reader.
                    .then(
                        if (collapsible) {
                            Modifier
                                .clickable(onClickLabel = toggleLabel) { expanded = !expanded }
                                .semantics(mergeDescendants = true) {}
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    icon()
                    Spacer(Modifier.size(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (collapsible) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                    // Null: the header row already carries the action and its label, so a
                    // description here would make the screen reader announce it twice.
                        contentDescription = null,
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(chevronRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = isOpen,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    content()
                }
            }
        }
    }
}
