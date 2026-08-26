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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.domain.export.ExportFormat
import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.ui.locks.LockAnalysisScreen
import dev.shivam.nfcexplorer.ui.log.SessionLogScreen
import dev.shivam.nfcexplorer.ui.log.SessionLogViewModel
import dev.shivam.nfcexplorer.ui.memory.MemoryExplorerScreen
import dev.shivam.nfcexplorer.ui.scan.ScanScreen
import dev.shivam.nfcexplorer.ui.scan.ScanUiState
import androidx.activity.ComponentActivity
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.platform.LocalContext
import dev.shivam.nfcexplorer.ui.actions.TagEditorScreen
import dev.shivam.nfcexplorer.ui.actions.TagActionsScreen
import dev.shivam.nfcexplorer.ui.discovery.DiscoveryScreen
import dev.shivam.nfcexplorer.ui.discovery.DiscoverySection
import dev.shivam.nfcexplorer.ui.settings.SettingsScreen
import dev.shivam.nfcexplorer.ui.settings.SettingsViewModel
import dev.shivam.nfcexplorer.ui.settings.SyncUiState
import dev.shivam.nfcexplorer.ui.actions.TagActionsViewModel
import dev.shivam.nfcexplorer.ui.taginfo.TagInfoScreen
import dev.shivam.nfcexplorer.ui.write.WriteScreen
import dev.shivam.nfcexplorer.ui.write.WriteViewModel

/** The add/edit page. Not a bottom-nav peer: it is pushed over one, and popped when done. */
private const val EDITOR_ROUTE = "editor"

private enum class Destination(
    val route: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    // Order is the bar order, and Actions leads deliberately: running a tag's action is what this
    // app is opened for day to day, while the inspection screens matter only with a card in hand.
    ACTIONS("actions", R.string.nav_actions, R.drawable.ic_nav_actions),
    LOG("log", R.string.nav_log, R.drawable.ic_nav_log),
    DISCOVERY("discovery", R.string.nav_discovery, R.drawable.ic_nav_tag),
    SETTINGS("settings", R.string.nav_settings, R.drawable.ic_nav_settings),
}

/**
 * Bottom-nav shell over the four destinations.
 *
 * Actions, Log and Settings are single screens; Discovery groups the four tag-inspection surfaces
 * behind a secondary tab row, so the bar stays at four entries rather than six.
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
    /**
     * Changes once per completed scan.
     *
     * [lastReport] cannot serve as the trigger: it is a conflated `StateFlow` of a data class, so
     * re-tapping the same card produces an equal value and emits nothing. The scan surface already
     * solved this for haptics with a monotonic token; the add flow reuses it.
     */
    scanToken: Long,
    writeViewModel: WriteViewModel,
    onOpenNfcSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // Activity-scoped rather than per-route: the list and the editor are two routes over one piece
    // of state, and a route-scoped view model would drop the draft on the way between them.
    val actionsOwner = LocalContext.current as ComponentActivity
    val actionsViewModel: TagActionsViewModel = hiltViewModel(actionsOwner)
    val actionsState by actionsViewModel.state.collectAsStateWithLifecycle()

    fun openEditor() = navController.navigate(EDITOR_ROUTE) { launchSingleTop = true }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            // Actions only: the other tabs are for reading a card, not binding one.
            if (currentDestination?.hierarchy?.any { it.route == Destination.ACTIONS.route } == true) {
                FloatingActionButton(onClick = {
                    actionsViewModel.onStartAddFlow()
                    openEditor()
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.actions_add_title),
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            // The editor is pushed over a tab, and restoreState would bring it back
                            // with that tab. Tapping a tab should land on the tab, so the editor is
                            // popped first and its draft abandoned.
                            if (navController.popBackStack(EDITOR_ROUTE, inclusive = true)) {
                                actionsViewModel.onLeaveAddFlow()
                            }
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
            startDestination = Destination.ACTIONS.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(Destination.DISCOVERY.route) {
                DiscoveryScreen { section ->
                    when (section) {
                        DiscoverySection.TAG -> WithReport(lastReport, state, onOpenNfcSettings) {
                            TagInfoScreen(it)
                        }
                        DiscoverySection.MEMORY -> WithReport(lastReport, state, onOpenNfcSettings) {
                            MemoryExplorerScreen(it)
                        }
                        DiscoverySection.LOCKS -> WithReport(lastReport, state, onOpenNfcSettings) {
                            LockAnalysisScreen(it)
                        }
                        DiscoverySection.WRITE -> {
                            val writeState by writeViewModel.state.collectAsStateWithLifecycle()
                            val preview by writeViewModel.encodedPreview.collectAsStateWithLifecycle()

                            // The view model is Activity-scoped while this tab is not, so it has to
                            // be told when its screen comes and goes. Leaving disarms: an arm is a
                            // confirmation of the preview shown here, and the tag router dispatches
                            // taps from every tab. Now driven by tab selection rather than route
                            // changes, which is the same guarantee -- the composable leaves
                            // composition either way.
                            DisposableEffect(Unit) {
                                writeViewModel.onScreenEntered()
                                onDispose { writeViewModel.onScreenLeft() }
                            }
                            WriteScreen(
                                state = writeState,
                                encoded = preview,
                                onModeChange = writeViewModel::onModeChange,
                                onInputChange = writeViewModel::onInputChange,
                                onRangeChange = writeViewModel::onRangeChange,
                                onExpertModeChange = writeViewModel::onExpertModeChange,
                                onArm = writeViewModel::onArm,
                                onDisarm = writeViewModel::onDisarm,
                            )
                        }
                    }
                }
            }
            composable(Destination.SETTINGS.route) {
                val settingsViewModel: SettingsViewModel = hiltViewModel()
                val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
                LifecycleResumeEffect(settingsViewModel) {
                    settingsViewModel.refreshGrants()
                    onPauseOrDispose { }
                }
                // Google's consent screen is a PendingIntent, so it goes through the intent-sender
                // contract rather than a plain activity launch.
                val consentLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult(),
                ) { result ->
                    settingsViewModel.onConsentResult(result.resultCode == android.app.Activity.RESULT_OK)
                }
                val pendingConsent = (settingsState.sync as? SyncUiState.NeedsConsent)?.pendingIntent
                LaunchedEffect(pendingConsent) {
                    pendingConsent?.let {
                        consentLauncher.launch(IntentSenderRequest.Builder(it).build())
                    }
                }

                // Exporting lives here now rather than on the log itself. One launcher per format:
                // CreateDocument fixes its MIME type at construction.
                val logViewModel: SessionLogViewModel = hiltViewModel()
                val exportResult by logViewModel.exportResult.collectAsStateWithLifecycle()
                val jsonLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument(ExportFormat.JSON.mimeType),
                ) { uri ->
                    uri?.let {
                        logViewModel.export(it, ExportFormat.JSON, lastReport, System.currentTimeMillis())
                    }
                }
                val textLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument(ExportFormat.TEXT.mimeType),
                ) { uri ->
                    uri?.let {
                        logViewModel.export(it, ExportFormat.TEXT, lastReport, System.currentTimeMillis())
                    }
                }

                SettingsScreen(
                    state = settingsState,
                    exportResult = exportResult,
                    onExport = { format ->
                        val name = logViewModel.suggestedFileName(
                            report = lastReport,
                            format = format,
                            nowMillis = System.currentTimeMillis(),
                        )
                        when (format) {
                            ExportFormat.JSON -> jsonLauncher.launch(name)
                            ExportFormat.TEXT -> textLauncher.launch(name)
                        }
                    },
                    onOpenNotificationAccess = settingsViewModel::onOpenNotificationAccess,
                    onOpenAccessibilitySettings = settingsViewModel::onOpenAccessibilitySettings,
                    onCheckForUpdates = settingsViewModel::onCheckForUpdates,
                    onOpenRelease = settingsViewModel::onOpenRelease,
                    onDownloadAndInstall = settingsViewModel::onDownloadAndInstall,
                    onAllowInstalls = settingsViewModel::onAllowInstalls,
                    onSyncNow = settingsViewModel::onSyncNow,
                    onTogglDraftChange = settingsViewModel::onTogglDraftChange,
                    onSaveTogglToken = settingsViewModel::onSaveTogglToken,
                    onClearTogglToken = settingsViewModel::onClearTogglToken,
                    onToggleTokenVisibility = settingsViewModel::onToggleTokenVisibility,
                    onEditTogglToken = settingsViewModel::onEditTogglToken,
                    onCancelTogglEdit = settingsViewModel::onCancelTogglEdit,
                    onCheckToggl = settingsViewModel::onCheckToggl,
                )
            }
            composable(Destination.ACTIONS.route) {
                TagActionsScreen(
                    state = actionsState,
                    onEdit = { assignment ->
                        actionsViewModel.onEdit(assignment)
                        openEditor()
                    },
                    onDelete = actionsViewModel::onDelete,
                    onTest = actionsViewModel::onTest,
                )
            }
            composable(EDITOR_ROUTE) {
                // Only scans that happen *after* this page opens count.
                //
                // LaunchedEffect runs on first composition whether or not its key changed, so keying
                // on the token alone consumed whatever tag was last scanned the moment the page
                // appeared -- cancelling and reopening landed straight back on the same tag instead
                // of waiting for a tap. Remembering the token at entry is what makes "new scan"
                // mean new.
                val tokenAtEntry = rememberSaveable { scanToken }
                LaunchedEffect(scanToken) {
                    if (scanToken != tokenAtEntry) {
                        lastReport?.identity?.uid?.let(actionsViewModel::onTagScanned)
                    }
                }

                TagEditorScreen(
                    state = actionsState,
                    onDraftChange = actionsViewModel::onDraftChange,
                    onSave = {
                        actionsViewModel.onSave()
                        navController.popBackStack()
                    },
                    onCancel = {
                        actionsViewModel.onLeaveAddFlow()
                        navController.popBackStack()
                    },
                    onTestDraft = actionsViewModel::onTestDraft,
                    onAppQueryChange = actionsViewModel::onAppQueryChange,
                    onPickApp = actionsViewModel::onPickApp,
                    onTypeChange = actionsViewModel::onTypeChange,
                    onSchemeChange = actionsViewModel::onSchemeChange,
                    onToggleTogglTag = actionsViewModel::onToggleTogglTag,
                    onEditScanned = actionsViewModel::onEditScannedTag,
                    onScanAnother = actionsViewModel::onStartAddFlow,
                )
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
