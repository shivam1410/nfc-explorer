package dev.shivam.nfcexplorer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.shivam.nfcexplorer.data.nfc.NfcAvailability
import dev.shivam.nfcexplorer.data.nfc.NfcReaderModeController
import dev.shivam.nfcexplorer.domain.decoder.MemoryRenderer
import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.domain.repository.TagRepository
import dev.shivam.nfcexplorer.logging.SessionLogcatMirror
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phase 2 device-proof harness.
 *
 * Deliberately minimal and **temporary**: it exists so Task 2.5 can prove the read pipeline
 * against a real tag before any UI work starts. Task 3.2 replaces it with a proper
 * `ScanViewModel` plus the navigation shell, and this plain-text dump goes away.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var readerMode: NfcReaderModeController

    @Inject lateinit var repository: TagRepository

    @Inject lateinit var logcatMirror: SessionLogcatMirror

    private val probeState = MutableStateFlow<ProbeState>(ProbeState.Starting)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        logcatMirror.attach(lifecycleScope)

        when (readerMode.availability(this)) {
            NfcAvailability.Unsupported -> probeState.value = ProbeState.Unsupported
            NfcAvailability.Disabled -> probeState.value = ProbeState.Disabled
            NfcAvailability.Available -> collectTags()
        }

        setContent {
            MaterialTheme {
                Scaffold { innerPadding ->
                    val state by probeState.collectAsStateWithLifecycle()
                    ProbeContent(
                        state = state,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                    )
                }
            }
        }
    }

    /**
     * Reader mode is active only while resumed, so the app releases the NFC hardware whenever it
     * is not in front of the user.
     */
    private fun collectTags() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                probeState.value = ProbeState.WaitingForTag
                readerMode.tagHandles(this@MainActivity).collect { handle ->
                    probeState.value = ProbeState.Reading
                    repository.read(handle)
                        .onSuccess { probeState.value = ProbeState.Captured(it) }
                        .onFailure { probeState.value = ProbeState.Failed(it) }
                }
            }
        }
    }

    private sealed interface ProbeState {
        data object Starting : ProbeState
        data object Unsupported : ProbeState
        data object Disabled : ProbeState
        data object WaitingForTag : ProbeState
        data object Reading : ProbeState
        data class Captured(val report: TagReport) : ProbeState
        data class Failed(val cause: Throwable) : ProbeState
    }

    @Composable
    private fun ProbeContent(state: ProbeState, modifier: Modifier = Modifier) {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("NFC Explorer", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = when (state) {
                    ProbeState.Starting -> "Starting…"
                    ProbeState.Unsupported -> "This device has no NFC hardware."
                    ProbeState.Disabled -> "NFC is switched off. Enable it in system settings."
                    ProbeState.WaitingForTag -> "Hold a tag against the back of the device."
                    ProbeState.Reading -> "Reading…"
                    is ProbeState.Captured -> "Tag captured."
                    is ProbeState.Failed ->
                        "Read failed: ${state.cause::class.simpleName}: ${state.cause.message}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            if (state is ProbeState.Captured) {
                Text(
                    text = summarise(state.report),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    private fun summarise(report: TagReport): String = buildString {
        val chip = report.chip
        appendLine()
        appendLine("UID          ${report.identity.uid}")
        appendLine("UID length   ${report.identity.uidLength} bytes")
        appendLine("Cascade      ${report.identity.cascadeLevels ?: "unknown"}")
        appendLine("Manufacturer ${report.identity.manufacturer}")
        appendLine("ATQA         ${report.identity.atqa ?: "not established"}")
        appendLine("SAK          ${report.identity.sak?.toString(16)?.uppercase() ?: "not established"}")
        appendLine("BCC0         ${bcc(report.identity.bcc0)}")
        appendLine("BCC1         ${bcc(report.identity.bcc1)}")
        appendLine()
        appendLine("Family       ${chip.family.ifEmpty { "unidentified" }}")
        appendLine("Chip         ${chip.chipName.ifEmpty { "not confirmed" }}")
        appendLine("Geometry     ${chip.pageCount} pages x ${chip.pageSize}B" +
            if (chip.geometryConfirmed) " (confirmed)" else " (floor, unconfirmed)")
        appendLine()
        appendLine("Technologies")
        report.technologies.available.forEach { tech ->
            appendLine("  ${tech.name.substringAfterLast('.')}" +
                (tech.maxTransceiveLength?.let { "  maxTx=$it" } ?: "") +
                (tech.timeoutMillis?.let { "  timeout=${it}ms" } ?: "") +
                if (tech.extras.isEmpty()) "" else "  ${tech.extras}")
        }
        appendLine()
        appendLine("Memory  ${report.memory.readableCount}/${report.memory.pages.size} pages read")
        report.memory.pages.forEach { page ->
            val hex = MemoryRenderer.hex(page) ?: "-- -- -- --  (${page.status})"
            val ascii = MemoryRenderer.ascii(page) ?: ""
            val access = report.locks.accessFor(page.index)
            appendLine(
                "  %02X  %-14s %-4s %s".format(
                    page.index,
                    hex,
                    ascii,
                    access?.verdict?.name?.lowercase()?.replace('_', ' ') ?: "",
                ),
            )
        }
        appendLine()
        appendLine("Lock bytes   ${report.locks.staticLockBytes ?: "unreadable"}")
        appendLine("Locked pages ${report.locks.lockedPages.ifEmpty { "none" }}")
        appendLine("Writable     ${report.locks.writablePages.ifEmpty { "none" }}")
        appendLine("Dynamic lock ${report.locks.dynamicLockSupport}")
    }

    private fun bcc(check: dev.shivam.nfcexplorer.domain.model.BccCheck?): String = when (check) {
        null -> "not established"
        else -> "stored=%02X computed=%02X %s".format(
            check.stored,
            check.computed,
            if (check.isValid) "VALID" else "MISMATCH",
        )
    }
}
