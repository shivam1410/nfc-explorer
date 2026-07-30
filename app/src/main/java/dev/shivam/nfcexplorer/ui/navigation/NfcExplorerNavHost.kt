package dev.shivam.nfcexplorer.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.ui.locks.LockAnalysisScreen
import dev.shivam.nfcexplorer.ui.log.SessionLogScreen
import dev.shivam.nfcexplorer.ui.log.SessionLogViewModel
import dev.shivam.nfcexplorer.ui.memory.MemoryExplorerScreen
import dev.shivam.nfcexplorer.ui.scan.ScanScreen
import dev.shivam.nfcexplorer.ui.scan.ScanUiState
import dev.shivam.nfcexplorer.ui.taginfo.TagInfoScreen

private enum class Destination(
    val route: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    TAG("tag", R.string.nav_tag, R.drawable.ic_nav_tag),
    MEMORY("memory", R.string.nav_memory, R.drawable.ic_nav_memory),
    LOCKS("locks", R.string.nav_locks, R.drawable.ic_nav_lock),
    LOG("log", R.string.nav_log, R.drawable.ic_nav_log),
}

/**
 * Bottom-nav shell over the four destinations.
 *
 * The three tag screens render [lastReport] rather than reading from [state], so moving between
 * them never forces a re-tap and a failed re-scan does not blank a dump mid-read. Until the first
 * successful scan they fall back to [ScanScreen], which is where the waiting and error surfaces
 * live.
 */
@Composable
fun NfcExplorerNavHost(
    state: ScanUiState,
    lastReport: TagReport?,
    onOpenNfcSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Single instance per destination and no growing back stack:
                                // these are peers the user moves between repeatedly.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                painter = painterResource(destination.iconRes),
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.TAG.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(Destination.TAG.route) {
                WithReport(lastReport, state, onOpenNfcSettings) { report ->
                    TagInfoScreen(report)
                }
            }
            composable(Destination.MEMORY.route) {
                WithReport(lastReport, state, onOpenNfcSettings) { report ->
                    MemoryExplorerScreen(report)
                }
            }
            composable(Destination.LOCKS.route) {
                WithReport(lastReport, state, onOpenNfcSettings) { report ->
                    LockAnalysisScreen(report)
                }
            }
            composable(Destination.LOG.route) {
                val viewModel: SessionLogViewModel = hiltViewModel()
                val entries by viewModel.entries.collectAsStateWithLifecycle()
                SessionLogScreen(entries = entries)
            }
        }
    }
}

/**
 * Renders [content] once a report exists, otherwise the scan surface.
 *
 * Keeps every tag screen from repeating the same null check and the same empty state.
 */
@Composable
private fun WithReport(
    report: TagReport?,
    state: ScanUiState,
    onOpenNfcSettings: () -> Unit,
    content: @Composable (TagReport) -> Unit,
) {
    if (report != null) {
        content(report)
    } else {
        ScanScreen(state = state, onOpenNfcSettings = onOpenNfcSettings)
    }
}
