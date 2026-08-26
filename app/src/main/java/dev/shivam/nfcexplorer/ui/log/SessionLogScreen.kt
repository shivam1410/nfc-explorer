package dev.shivam.nfcexplorer.ui.log

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
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
    activityEntries: List<LogEntry>,
    onClearActivity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var minimumLevel by remember { mutableStateOf<LogLevel?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var scope by remember { mutableStateOf(LogScope.ACTIVITY) }

    val visible = remember(entries, activityEntries, minimumLevel, scope) {
        val threshold = minimumLevel
        // Activity comes from the persisted store, so it survives the app closing -- which is the
        // whole point of it. The other scopes are this process's log and cannot outlive it.
        val source = if (scope == LogScope.ACTIVITY) activityEntries else entries
        source
            .filter { threshold == null || it.level.ordinal >= threshold.ordinal }
            .filter { scope.admits(it.category) }
            .sortedByDescending { it.timestampMillis }
    }

    // Grouped by day rather than shown as one stream: a session log spans several days once the app
    // has been left running, and "15:41:24" alone does not say which one.
    val byDay = remember(visible) { visible.groupBy { DAY_FORMAT.format(Date(it.timestampMillis)) } }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.log_entry_count, visible.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )

            if (scope == LogScope.ACTIVITY && visible.isNotEmpty()) {
                TextButton(onClick = onClearActivity) {
                    Text(stringResource(R.string.log_clear))
                }
            }

            // Both filters in one menu on the right. As two rows of chips they took a third of the
            // screen to answer a question asked once a session, above the entries they were
            // filtering.
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                TextButton(onClick = { menuOpen = true }) {
                    // Named for what the control does, not for the value it currently holds: the
                    // chosen scope is already ticked inside the menu.
                    Text(stringResource(R.string.log_filter_label))
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = stringResource(R.string.log_filter_label),
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    Text(
                        text = stringResource(R.string.log_filter_show),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    LogScope.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(option.labelRes)) },
                            trailingIcon = { if (scope == option) SelectedMark() },
                            onClick = {
                                scope = option
                                menuOpen = false
                            },
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        text = stringResource(R.string.log_filter_level),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.log_filter_all)) },
                        trailingIcon = { if (minimumLevel == null) SelectedMark() },
                        onClick = {
                            minimumLevel = null
                            menuOpen = false
                        },
                    )
                    LogLevel.entries.forEach { level ->
                        DropdownMenuItem(
                            text = { Text(level.name) },
                            trailingIcon = { if (minimumLevel == level) SelectedMark() },
                            onClick = {
                                minimumLevel = level
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }

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
            byDay.forEach { (day, dayEntries) ->
                item(key = "day-$day") {
                    Text(
                        text = day,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                items(dayEntries, key = LogEntry::sequence) { entry ->
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
}

/** A tick beside the chosen option, so the menu shows state rather than only offering choices. */
@Composable
private fun SelectedMark() {
    Text("\u2713", style = MaterialTheme.typography.bodyMedium)
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
/**
 * Which slice of the log is on screen.
 *
 * A category filter rather than a search box: the categories are fixed and few, and the question
 * being asked is almost always "what did my tap do", not "find this string".
 */
private enum class LogScope(@StringRes val labelRes: Int, val categories: Set<String>?) {
    /** Taps and what they performed. */
    ACTIVITY(R.string.log_scope_activity, setOf("trigger", "action")),

    /** Reading the card itself: identity, memory, lock bits. */
    SCANNING(R.string.log_scope_scanning, setOf("session", "read", "write")),

    /** Null means no filtering, including categories added later. */
    EVERYTHING(R.string.log_scope_all, null);

    fun admits(category: String): Boolean = categories?.contains(category) ?: true
}

/** Seconds are enough: the sequence number already orders entries within the same second. */
private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)

private val DAY_FORMAT = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
