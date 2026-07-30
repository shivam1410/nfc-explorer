package dev.shivam.nfcexplorer.ui.memory

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.domain.decoder.MemoryRenderer
import dev.shivam.nfcexplorer.domain.model.PageSnapshot
import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.ui.component.ChipTone
import dev.shivam.nfcexplorer.ui.component.HexPageRow
import dev.shivam.nfcexplorer.ui.labels.labelRes
import dev.shivam.nfcexplorer.ui.labels.tone

/** Which representation sits in the column beside the hex. */
private enum class SecondaryView(val labelRes: Int) {
    ASCII(R.string.memory_view_ascii),
    BINARY(R.string.memory_view_binary),
    DECIMAL(R.string.memory_view_decimal),
}

/**
 * The memory table.
 *
 * The whole table scrolls horizontally inside its own container, so the page body never scrolls
 * sideways however wide the binary view gets.
 */
@Composable
fun MemoryExplorerScreen(report: TagReport, modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf(SecondaryView.ASCII) }
    var expandedPage by remember { mutableStateOf<Int?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryView.entries.forEach { candidate ->
                FilterChip(
                    selected = view == candidate,
                    onClick = { view = candidate },
                    label = { Text(stringResource(candidate.labelRes)) },
                )
            }
        }

        Text(
            text = stringResource(
                R.string.memory_pages_read,
                report.memory.readableCount,
                report.memory.pages.size,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState()),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            items(report.memory.pages, key = PageSnapshot::index) { page ->
                val access = report.locks.accessFor(page.index)
                HexPageRow(
                    pageIndex = page.index,
                    hex = MemoryRenderer.hex(page),
                    secondary = when (view) {
                        SecondaryView.ASCII -> MemoryRenderer.ascii(page)
                        SecondaryView.BINARY -> MemoryRenderer.binary(page)
                        SecondaryView.DECIMAL -> MemoryRenderer.decimal(page)
                    },
                    // A page that failed to read shows why, in the error colour, instead of
                    // zeros that would be indistinguishable from real data.
                    statusText = if (page.isReadable) null else stringResource(page.status.labelRes()),
                    accessLabel = access?.verdict?.let { stringResource(it.labelRes()) }.orEmpty(),
                    accessTone = access?.verdict?.tone() ?: ChipTone.NEUTRAL,
                    expandedDetail = if (expandedPage == page.index) detailFor(page, view) else null,
                    onClick = {
                        expandedPage = if (expandedPage == page.index) null else page.index
                    },
                    modifier = Modifier.width(TABLE_WIDTH),
                )
            }
        }
    }
}

/** The two representations not currently in the secondary column, so expanding always adds. */
@Composable
private fun detailFor(page: PageSnapshot, view: SecondaryView): String? {
    if (!page.isReadable) return null
    val lines = buildList {
        if (view != SecondaryView.BINARY) MemoryRenderer.binary(page)?.let { add("bin  $it") }
        if (view != SecondaryView.DECIMAL) MemoryRenderer.decimal(page)?.let { add("dec  $it") }
        if (view != SecondaryView.ASCII) MemoryRenderer.ascii(page)?.let { add("txt  $it") }
    }
    return lines.joinToString("\n").ifEmpty { null }
}

private val TABLE_WIDTH = 560.dp
