package dev.shivam.nfcexplorer.ui.taginfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.domain.model.BccCheck
import dev.shivam.nfcexplorer.domain.model.ChipCapability
import dev.shivam.nfcexplorer.domain.model.Manufacturer
import dev.shivam.nfcexplorer.domain.model.NfcTechnology
import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.ui.component.ChipTone
import dev.shivam.nfcexplorer.ui.component.KeyValueRow
import dev.shivam.nfcexplorer.ui.component.SectionCard
import dev.shivam.nfcexplorer.ui.component.StatusChip
import dev.shivam.nfcexplorer.ui.labels.labelRes
import dev.shivam.nfcexplorer.ui.theme.HexSecondaryTextStyle
import dev.shivam.nfcexplorer.util.toHex

@Composable
fun TagInfoScreen(report: TagReport, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item { IdentitySection(report) }
        item { ChipSection(report) }
        item { CapabilitiesSection(report) }
        item { TechnologiesSection(report) }
    }
}

@Composable
private fun IdentitySection(report: TagReport) {
    val identity = report.identity
    SectionCard(title = stringResource(R.string.section_identity)) {
        KeyValueRow(stringResource(R.string.label_uid), identity.uid.toString(), isHex = true)
        KeyValueRow(
            stringResource(R.string.label_uid_length),
            stringResource(R.string.value_bytes, identity.uidLength),
        )
        KeyValueRow(
            stringResource(R.string.label_cascade_levels),
            identity.cascadeLevels?.toString() ?: stringResource(R.string.value_unknown),
        )
        KeyValueRow(stringResource(R.string.label_manufacturer), identity.manufacturer.display())
        KeyValueRow(
            stringResource(R.string.label_atqa),
            identity.atqa?.toString() ?: stringResource(R.string.value_not_established),
            isHex = identity.atqa != null,
        )
        KeyValueRow(
            stringResource(R.string.label_sak),
            identity.sak?.let { "%02X".format(it.toInt() and 0xFF) }
                ?: stringResource(R.string.value_not_established),
            isHex = identity.sak != null,
        )
        BccRow(stringResource(R.string.label_bcc0), identity.bcc0)
        BccRow(stringResource(R.string.label_bcc1), identity.bcc1)
    }
}

/**
 * Shows computed *and* stored values, not just pass/fail.
 *
 * When a card misbehaves the difference between the two is the diagnostic, so it stays on screen
 * rather than being reduced to a tick.
 */
@Composable
private fun BccRow(label: String, check: BccCheck?) {
    if (check == null) {
        KeyValueRow(label, stringResource(R.string.value_not_established))
        return
    }
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusChip(
            text = stringResource(
                if (check.isValid) R.string.bcc_valid else R.string.bcc_mismatch,
            ),
            tone = if (check.isValid) ChipTone.POSITIVE else ChipTone.NEGATIVE,
        )
        Text(
            text = "$label  " + stringResource(
                R.string.bcc_detail,
                check.stored.toHex(),
                check.computed.toHex(),
            ),
            style = HexSecondaryTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChipSection(report: TagReport) {
    val chip = report.chip
    SectionCard(title = stringResource(R.string.section_chip)) {
        KeyValueRow(stringResource(R.string.label_vendor), chip.vendor.ifEmpty { "—" })
        KeyValueRow(
            stringResource(R.string.label_chip),
            chip.chipName.ifEmpty { stringResource(R.string.value_not_confirmed) },
        )
        KeyValueRow(stringResource(R.string.label_family), chip.family.ifEmpty { "—" })
        KeyValueRow(
            stringResource(R.string.label_total_bytes),
            stringResource(R.string.value_bytes, chip.totalBytes),
        )
        KeyValueRow(
            stringResource(R.string.label_page_count),
            stringResource(R.string.value_pages_of_size, chip.pageCount, chip.pageSize),
        )
        StatusChip(
            text = stringResource(
                if (chip.geometryConfirmed) {
                    R.string.geometry_confirmed
                } else {
                    R.string.geometry_unconfirmed
                },
            ),
            tone = if (chip.geometryConfirmed) ChipTone.POSITIVE else ChipTone.CAUTION,
        )
        if (!chip.geometryConfirmed) {
            // Explaining *why* the number is a floor is the informative part; a bare
            // "unconfirmed" badge would just look like a defect.
            Text(
                text = stringResource(R.string.geometry_unconfirmed_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Every capability is listed, present or not.
 *
 * An absent capability is the informative case for this chip family — it is precisely why other
 * apps can do so little with the tag — so it is rendered as a quiet outlined chip rather than
 * omitted.
 */
@Composable
private fun CapabilitiesSection(report: TagReport) {
    SectionCard(title = stringResource(R.string.section_capabilities)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChipCapability.entries.forEach { capability ->
                val supported = report.chip.supports(capability)
                StatusChip(
                    text = stringResource(capability.labelRes()),
                    tone = if (supported) ChipTone.POSITIVE else ChipTone.NEUTRAL,
                )
            }
        }
        Text(
            text = stringResource(R.string.capability_absent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun TechnologiesSection(report: TagReport) {
    SectionCard(title = stringResource(R.string.section_technologies)) {
        report.technologies.available.forEach { tech ->
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                Text(
                    text = NfcTechnology.shortName(tech.name),
                    style = MaterialTheme.typography.bodyLarge,
                )
                tech.maxTransceiveLength?.let {
                    KeyValueRow(
                        stringResource(R.string.label_max_transceive),
                        stringResource(R.string.value_bytes, it),
                    )
                }
                tech.timeoutMillis?.let {
                    KeyValueRow(
                        stringResource(R.string.label_timeout),
                        stringResource(R.string.value_millis, it),
                    )
                }
                tech.extras.forEach { (key, value) ->
                    KeyValueRow(key, value, isHex = key == "atqa" || key == "sak")
                }
            }
        }
    }
}

@Composable
private fun Manufacturer.display(): String = when (this) {
    is Manufacturer.Known -> "$name (0x%02X)".format(code)
    is Manufacturer.Unknown -> stringResource(R.string.value_unknown) + " (0x%02X)".format(code)
}
