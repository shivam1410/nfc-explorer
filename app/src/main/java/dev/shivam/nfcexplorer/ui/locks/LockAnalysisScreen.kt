package dev.shivam.nfcexplorer.ui.locks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.domain.model.DynamicLockSupport
import dev.shivam.nfcexplorer.domain.model.LockBit
import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.ui.component.ChipTone
import dev.shivam.nfcexplorer.ui.component.KeyValueRow
import dev.shivam.nfcexplorer.ui.component.SectionCard
import dev.shivam.nfcexplorer.ui.component.StatusChip
import dev.shivam.nfcexplorer.ui.labels.labelRes
import dev.shivam.nfcexplorer.ui.labels.tone
import dev.shivam.nfcexplorer.ui.theme.HexSecondaryTextStyle
import dev.shivam.nfcexplorer.ui.theme.HexTextStyle
import dev.shivam.nfcexplorer.util.toBinary

/**
 * Lock analysis, built to teach the bit layout rather than just report a result.
 *
 * The raw bit grid comes first because understanding *which* bit closed a page is the thing a
 * developer actually wants; the per-page verdict list is the summary of it.
 */
@Composable
fun LockAnalysisScreen(report: TagReport, modifier: Modifier = Modifier) {
    val locks = report.locks

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            SectionCard(title = stringResource(R.string.section_lock_bytes)) {
                val bytes = locks.staticLockBytes
                if (bytes == null) {
                    Text(
                        text = stringResource(R.string.lock_bytes_unreadable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    KeyValueRow(
                        stringResource(R.string.label_lock0),
                        "${bytes.slice0()}   ${bytes.toByteArray()[0].toBinary()}",
                        isHex = true,
                    )
                    KeyValueRow(
                        stringResource(R.string.label_lock1),
                        "${bytes.slice1()}   ${bytes.toByteArray()[1].toBinary()}",
                        isHex = true,
                    )
                    Text(
                        text = stringResource(
                            R.string.locks_summary,
                            locks.lockedPages.size,
                            locks.writablePages.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    LockBitGrid(locks.lockBits)
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.section_page_access)) {
                report.memory.pages.forEach { page ->
                    val access = locks.accessFor(page.index)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "%02X".format(page.index),
                            style = HexTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(32.dp),
                        )
                        access?.verdict?.let { verdict ->
                            StatusChip(
                                text = stringResource(verdict.labelRes()),
                                tone = verdict.tone(),
                            )
                        }
                        access?.lockedBy?.let { bit ->
                            Text(
                                text = stringResource(R.string.verdict_locked_by, bit),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (locks.blockLockBits.isNotEmpty()) {
            item {
                SectionCard(title = stringResource(R.string.section_block_locking)) {
                    locks.blockLockBits.forEach { bit ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusChip(
                                text = bit.name,
                                tone = if (bit.isSet) ChipTone.CAUTION else ChipTone.NEUTRAL,
                            )
                            Text(
                                text = stringResource(
                                    if (bit.name == "BL_OTP") {
                                        R.string.block_lock_otp_freezes
                                    } else {
                                        R.string.block_lock_freezes
                                    },
                                    bit.freezesPages.first,
                                    bit.freezesPages.last,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.section_dynamic_lock)) {
                // Stated as an explicit absence rather than left as an empty section, so the
                // chip's limits are visible instead of looking like missing data.
                when (val support = locks.dynamicLockSupport) {
                    is DynamicLockSupport.NotSupportedByChip -> Text(
                        text = stringResource(
                            R.string.dynamic_lock_unsupported,
                            support.introducedIn,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is DynamicLockSupport.Present -> KeyValueRow(
                        stringResource(R.string.section_dynamic_lock),
                        support.bytes.toString(),
                        isHex = true,
                    )
                }
            }
        }
    }
}

/** Every `L_*` bit with its state, and whether a block-locking bit has frozen it. */
@Composable
private fun LockBitGrid(bits: List<LockBit>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        bits.forEach { bit ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StatusChip(
                    text = bit.name,
                    tone = if (bit.isSet) ChipTone.NEGATIVE else ChipTone.POSITIVE,
                )
                if (bit.isFrozen) {
                    Text(
                        text = stringResource(R.string.lock_bit_frozen),
                        style = HexSecondaryTextStyle,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

private fun dev.shivam.nfcexplorer.domain.model.ByteBlock.slice0(): String =
    "%02X".format(unsignedAt(0))

private fun dev.shivam.nfcexplorer.domain.model.ByteBlock.slice1(): String =
    "%02X".format(unsignedAt(1))
