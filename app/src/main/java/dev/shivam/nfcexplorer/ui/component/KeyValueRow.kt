package dev.shivam.nfcexplorer.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.shivam.nfcexplorer.ui.theme.HexTextStyle

private val LABEL_WIDTH = 132.dp

/**
 * A labelled value.
 *
 * When [isHex] is set the value renders monospaced and its spoken form is spelled out. Screen
 * readers mangle raw hex — `04 A2` gets announced as a word or a decimal number — so the
 * accessibility text separates the characters deliberately.
 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isHex: Boolean = false,
    valueStyle: TextStyle? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(LABEL_WIDTH),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = valueStyle ?: if (isHex) HexTextStyle else LocalTextStyle.current,
            color = valueColor,
            modifier = if (isHex) {
                Modifier.clearAndSetSemantics { contentDescription = spellOutHex(label, value) }
            } else {
                Modifier
            },
        )
    }
}

/**
 * `"UID", "04 A2"` -> `"UID: 0 4, A 2"` so digits are read individually rather than as words.
 */
internal fun spellOutHex(label: String, value: String): String {
    val spoken = value
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(", ") { byte -> byte.toCharArray().joinToString(" ") }
    return "$label: $spoken"
}
