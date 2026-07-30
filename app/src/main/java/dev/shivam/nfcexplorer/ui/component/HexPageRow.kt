package dev.shivam.nfcexplorer.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.ui.theme.HexSecondaryTextStyle
import dev.shivam.nfcexplorer.ui.theme.HexTextStyle
import dev.shivam.nfcexplorer.ui.theme.PageIndexTextStyle

/**
 * One page of tag memory, as a table row.
 *
 * [hex] is null for a page that could not be read; the caller passes the status wording as
 * [statusText] instead. That split is what makes it impossible to render an unread page as
 * `00 00 00 00`, which would be indistinguishable from a page of genuine zeros.
 *
 * Rows are at least 48 dp so they remain a usable touch target despite looking dense.
 */
@Composable
fun HexPageRow(
    pageIndex: Int,
    hex: String?,
    secondary: String?,
    accessLabel: String,
    accessTone: ChipTone,
    modifier: Modifier = Modifier,
    statusText: String? = null,
    expandedDetail: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val hexColor: Color = if (hex == null) scheme.error else scheme.onSurface
    val pageLabel = "%02X".format(pageIndex)

    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clearAndSetSemantics {
                    contentDescription = describe(pageLabel, hex, statusText, accessLabel)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pageLabel,
                style = PageIndexTextStyle,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.width(PAGE_COLUMN_WIDTH),
            )
            Text(
                text = hex ?: (statusText ?: ""),
                style = HexTextStyle,
                color = hexColor,
                modifier = Modifier.width(HEX_COLUMN_WIDTH),
            )
            // The access verdict sits before the secondary column, not after it. It decides whether
            // the user may write this page, so it has to stay on screen without horizontal
            // scrolling — the binary view is wide enough to push a trailing chip out of sight.
            StatusChip(text = accessLabel, tone = accessTone)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = secondary.orEmpty(),
                style = HexSecondaryTextStyle,
                color = scheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = expandedDetail != null) {
            Text(
                text = expandedDetail.orEmpty(),
                style = HexSecondaryTextStyle,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = PAGE_COLUMN_WIDTH, bottom = 8.dp),
            )
        }
    }
}

/** Column widths, shared with [HexPageHeader] so the header lines up with the rows exactly. */
val PAGE_COLUMN_WIDTH = 46.dp
val HEX_COLUMN_WIDTH = 128.dp

/**
 * Column captions for the memory table.
 *
 * Without these the leading two-digit column reads as data rather than as an address, which is a
 * genuine ambiguity in a table where every cell is hex.
 *
 * Only PAGE and HEX are captioned. The access chip between HEX and the secondary column has a
 * variable width, so no fixed header offset could line up with what follows it — and the view
 * selector directly above the table already names the secondary column.
 */
@Composable
fun HexPageHeader(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.memory_column_page),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(PAGE_COLUMN_WIDTH),
        )
        Text(
            text = stringResource(R.string.memory_column_hex),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Reads as "page 0 4, bytes 0 4, A 2, 5 5, 7 1, writable" rather than letting the screen reader
 * guess at hex pairs.
 */
private fun describe(
    pageLabel: String,
    hex: String?,
    statusText: String?,
    accessLabel: String,
): String {
    val spokenPage = pageLabel.toCharArray().joinToString(" ")
    val body = when {
        hex != null -> "bytes " + spellOutHex("", hex).removePrefix(": ")
        statusText != null -> statusText
        else -> ""
    }
    return "page $spokenPage, $body, $accessLabel"
}
