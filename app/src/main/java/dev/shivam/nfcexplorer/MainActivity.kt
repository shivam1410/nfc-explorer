package dev.shivam.nfcexplorer

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.shivam.nfcexplorer.data.nfc.NfcAvailability
import dev.shivam.nfcexplorer.data.nfc.NfcReaderModeController
import dev.shivam.nfcexplorer.logging.SessionLogcatMirror
import dev.shivam.nfcexplorer.ui.haptics.ScanHapticFeedback
import dev.shivam.nfcexplorer.ui.navigation.NfcExplorerNavHost
import dev.shivam.nfcexplorer.ui.scan.ScanCapability
import dev.shivam.nfcexplorer.ui.scan.ScanViewModel
import dev.shivam.nfcexplorer.ui.theme.NfcExplorerTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single activity host.
 *
 * Owns only what genuinely belongs to the platform: reader mode's lifecycle binding and the
 * settings deep link. All scan state lives in [ScanViewModel], so it survives configuration
 * changes — a rotation mid-session must not discard a dump.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var readerMode: NfcReaderModeController

    @Inject lateinit var logcatMirror: SessionLogcatMirror

    private val viewModel: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        logcatMirror.attach(lifecycleScope)
        collectTags()

        setContent {
            NfcExplorerTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val report by viewModel.lastReport.collectAsStateWithLifecycle()
                val haptic by viewModel.hapticSignal.collectAsStateWithLifecycle()

                ScanHapticFeedback(haptic)

                NfcExplorerNavHost(
                    state = state,
                    lastReport = report,
                    onOpenNfcSettings = ::openNfcSettings,
                )
            }
        }
    }

    /**
     * Reader mode runs only while resumed, so the app releases the NFC hardware whenever it is not
     * in front of the user. Availability is re-checked on every resume because the user may have
     * toggled NFC while away.
     */
    private fun collectTags() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.onCapabilityResolved(
                    when (readerMode.availability(this@MainActivity)) {
                        NfcAvailability.Available -> ScanCapability.AVAILABLE
                        NfcAvailability.Disabled -> ScanCapability.DISABLED
                        NfcAvailability.Unsupported -> ScanCapability.UNSUPPORTED
                    },
                )

                readerMode.tagHandles(this@MainActivity).collect(viewModel::onTagDiscovered)
            }
        }
    }

    private fun openNfcSettings() {
        startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
    }
}
