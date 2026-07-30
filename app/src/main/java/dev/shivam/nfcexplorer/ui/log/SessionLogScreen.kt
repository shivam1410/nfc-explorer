package dev.shivam.nfcexplorer.ui.log

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.domain.export.ExportFormat
import dev.shivam.nfcexplorer.logging.LogEntry
import dev.shivam.nfcexplorer.logging.LogLevel
import dev.shivam.nfcexplorer.ui.component.StatusChip
import dev.shivam.nfcexplorer.ui.labels.tone
import dev.shivam.nfcexplorer.ui.theme.HexSecondaryTextStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The session log, newest first.
 *
 * Reverse-chronological because the interesting entry after a scan is the last one. Filtering is by
 * minimum level rather than exact level, so selecting WARN also shows the ERROR that followed it —
 * hiding a consequence while showing its cause would be worse than no filter.
 */
@Composable
fun SessionLogScreen(
    entries: List<LogEntry>,
    exportResult: ExportResult?,
    onExport: (ExportFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    var minimumLevel by remember { mutableStateOf<LogLevel?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }

    val visible = remember(entries, minimumLevel) {
        val threshold = minimumLevel
        entries
            .filter { threshold == null || it.level.ordinal >= threshold.ordinal }
            .sortedByDescending { it.sequence }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = minimumLevel == null,
                onClick = { minimumLevel = null },
                label = { Text(stringResource(R.string.log_filter_all)) },
            )
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = minimumLevel == level,
                    onClick = { minimumLevel = level },
                    label = { Text(level.name) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                    is ExportResult.Written ->
                        stringResource(R.string.export_written, result.bytes, result.format.extension)
                    is ExportResult.Failed -> stringResource(R.string.export_failed, result.reason)
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (result) {
                    is ExportResult.Written -> MaterialTheme.colorScheme.primary
                    is ExportResult.Failed -> MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        Text(
            text = stringResource(R.string.log_entry_count, visible.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        if (visible.isEmpty()) {
            Text(
                text = stringResource(R.string.log_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(visible, key = LogEntry::sequence) { entry ->
                LogRow(
                    entry = entry,
                    isExpanded = expanded == entry.sequence,
                    onToggle = {
                        expanded = if (expanded == entry.sequence) null else entry.sequence
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, isExpanded: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = TIME_FORMAT.format(Date(entry.timestampMillis)),
                style = HexSecondaryTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusChip(text = entry.level.name, tone = entry.level.tone())
            Text(
                text = entry.category,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = entry.message, style = MaterialTheme.typography.bodyMedium)

        if (isExpanded && entry.payload.isNotEmpty()) {
            entry.payload.forEach { (key, value) ->
                Text(
                    text = "$key = $value",
                    style = HexSecondaryTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Millisecond precision: several tag operations land inside the same second. */
private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
