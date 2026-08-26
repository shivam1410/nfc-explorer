package dev.shivam.nfcexplorer.ui.discovery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Hosts the tag-inspection screens behind a secondary tab row.
 *
 * The selected tab is [rememberSaveable] so switching to Actions and back does not silently reset
 * the user to Tag — losing your place in a memory dump you are part-way through reading is exactly
 * the kind of small betrayal that makes a tool annoying.
 */
@Composable
fun DiscoveryScreen(
    modifier: Modifier = Modifier,
    content: @Composable (DiscoverySection) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(DiscoverySection.TAG) }

    Column(modifier = modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = selected.ordinal, edgePadding = 0.dp) {
            DiscoverySection.entries.forEach { section ->
                Tab(
                    selected = section == selected,
                    onClick = { selected = section },
                    text = { Text(stringResource(section.labelRes)) },
                )
            }
        }
        content(selected)
    }
}
