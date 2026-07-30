package dev.shivam.nfcexplorer.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A compact status label.
 *
 * [NEUTRAL] renders outlined rather than filled, so "not supported by this chip" reads as an
 * absence instead of competing with real values for attention. The text always states the status,
 * so colour is never the only signal.
 */
@Composable
fun StatusChip(
    text: String,
    tone: ChipTone,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val container: Color
    val content: Color

    when (tone) {
        ChipTone.POSITIVE -> {
            container = scheme.primaryContainer
            content = scheme.onPrimaryContainer
        }
        ChipTone.NEGATIVE -> {
            container = scheme.errorContainer
            content = scheme.onErrorContainer
        }
        ChipTone.CAUTION -> {
            container = scheme.tertiaryContainer
            content = scheme.onTertiaryContainer
        }
        ChipTone.NEUTRAL -> {
            container = Color.Transparent
            content = scheme.onSurfaceVariant
        }
    }

    val shape = MaterialTheme.shapes.small
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = modifier
            .clip(shape)
            .then(
                if (tone == ChipTone.NEUTRAL) {
                    Modifier.border(1.dp, scheme.outlineVariant, shape)
                } else {
                    Modifier.background(container)
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
