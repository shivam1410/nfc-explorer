package dev.shivam.nfcexplorer.ui.write

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.domain.model.WriteBatchResult
import dev.shivam.nfcexplorer.domain.model.WriteOutcome
import dev.shivam.nfcexplorer.ui.component.ChipTone
import dev.shivam.nfcexplorer.ui.component.SectionCard
import dev.shivam.nfcexplorer.ui.component.StatusChip
import dev.shivam.nfcexplorer.ui.theme.HexTextStyle
import dev.shivam.nfcexplorer.util.toHex

/**
 * Compose a payload, review it, arm it, then tap the tag.
 *
 * The arming step is the confirmation: a tag is only in range for a moment, so there is no way to
 * ask "are you sure?" while it is present. Everything the write will do is therefore shown *before*
 * arming, and any edit disarms.
 */
@Composable
fun WriteScreen(
    state: WriteUiState,
    encoded: List<ByteArray>?,
    onModeChange: (WriteMode) -> Unit,
    onInputChange: (String) -> Unit,
    onRangeChange: (Int, Int) -> Unit,
    onExpertModeChange: (Boolean) -> Unit,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard(title = stringResource(R.string.write_source)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WriteMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { onModeChange(mode) },
                        label = { Text(stringResource(mode.labelRes())) },
                    )
                }
            }

            if (state.mode != WriteMode.WIPE) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    label = { Text(stringResource(mode(state).labelRes())) },
                    isError = state.problem != null,
                    singleLine = false,
                    textStyle = if (state.mode == WriteMode.HEX) HexTextStyle else MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.write_wipe_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.problem?.let { problem ->
                Text(
                    text = stringResource(problem.labelRes(), state.capacityBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard(
            title = stringResource(R.string.write_target),
            subtitle = stringResource(
                R.string.write_target_summary,
                state.startPage,
                state.endPage,
                state.capacityBytes,
            ),
        ) {
            PageRangePicker(state = state, onRangeChange = onRangeChange)
        }

        if (encoded != null) {
            SectionCard(
                title = stringResource(R.string.write_preview),
                initiallyExpanded = true,
            ) {
                encoded.forEachIndexed { offset, page ->
                    Text(
                        text = "%02X  %s".format(state.startPage + offset, page.toHex()),
                        style = HexTextStyle,
                    )
                }
            }
        }

        ExpertModeCard(state = state, onExpertModeChange = onExpertModeChange)

        ArmPanel(state = state, onArm = onArm, onDisarm = onDisarm)

        state.result?.let { ResultCard(it) }

        state.failure?.let { failure ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = failure,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun PageRangePicker(state: WriteUiState, onRangeChange: (Int, Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Stepper(
            label = stringResource(R.string.write_start_page),
            value = state.startPage,
            onChange = { onRangeChange(it, maxOf(it, state.endPage)) },
        )
        Stepper(
            label = stringResource(R.string.write_end_page),
            value = state.endPage,
            onChange = { onRangeChange(minOf(state.startPage, it), it) },
        )
        if (state.startPage < WriteUiState.FIRST_USER_PAGE) {
            // Pages 0-3 are UID, lock control and OTP. Saying so here is more useful than letting
            // the guard refuse silently after a tap.
            Text(
                text = stringResource(R.string.write_range_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { onChange((value - 1).coerceAtLeast(0)) }) { Text("–") }
        Text(
            text = "%02X".format(value),
            style = HexTextStyle,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        OutlinedButton(onClick = { onChange((value + 1).coerceAtMost(WriteUiState.LAST_USER_PAGE)) }) {
            Text("+")
        }
    }
}

@Composable
private fun ExpertModeCard(state: WriteUiState, onExpertModeChange: (Boolean) -> Unit) {
    val touchesGatedPages = state.startPage <= OTP_PAGE
    if (!touchesGatedPages && !state.expertMode) return

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.write_expert_mode),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.expertMode, onCheckedChange = onExpertModeChange)
            }
            Text(
                text = stringResource(R.string.write_expert_mode_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun ArmPanel(state: WriteUiState, onArm: () -> Unit, onDisarm: () -> Unit) {
    when {
        state.isWriting -> Text(
            text = stringResource(R.string.write_in_progress),
            style = MaterialTheme.typography.titleMedium,
        )

        state.isArmed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.write_armed_hold_tag),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedButton(onClick = onDisarm) { Text(stringResource(R.string.write_cancel)) }
        }

        else -> Button(onClick = onArm, enabled = state.canArm) {
            Text(stringResource(R.string.write_arm))
        }
    }
}

@Composable
private fun ResultCard(result: WriteBatchResult) {
    SectionCard(
        title = stringResource(R.string.write_result),
        subtitle = stringResource(
            R.string.write_result_summary,
            result.writtenCount,
            result.pagesRequested,
        ),
    ) {
        StatusChip(
            text = stringResource(
                when {
                    result.allSucceeded -> R.string.write_result_complete
                    result.isPartial -> R.string.write_result_partial
                    else -> R.string.write_result_none
                },
            ),
            tone = when {
                result.allSucceeded -> ChipTone.POSITIVE
                result.isPartial -> ChipTone.CAUTION
                else -> ChipTone.NEGATIVE
            },
        )
        result.outcomes.forEach { outcome ->
            Text(
                text = outcome.describe(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = when (outcome) {
                    is WriteOutcome.Written -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

/** Technical one-liner per page. Deliberately terse — this is a diagnostic list, not prose. */
private fun WriteOutcome.describe(): String = when (this) {
    is WriteOutcome.Written ->
        "%02X  wrote %s  read back %s  %s".format(
            page,
            attempted,
            readBack ?: "unavailable",
            when (verified) {
                true -> "verified"
                false -> "DIFFERS (one-way page)"
                null -> "unverified"
            },
        )

    is WriteOutcome.Refused -> "%02X  refused: %s".format(page, decision)
    is WriteOutcome.Failed -> "%02X  failed: %s %s".format(page, exceptionName, message ?: "")
}

private const val OTP_PAGE = 3

private fun mode(state: WriteUiState): WriteMode = state.mode

private fun WriteMode.labelRes(): Int = when (this) {
    WriteMode.TEXT -> R.string.write_mode_text
    WriteMode.HEX -> R.string.write_mode_hex
    WriteMode.WIPE -> R.string.write_mode_wipe
}

private fun InputProblem.labelRes(): Int = when (this) {
    InputProblem.TOO_LONG -> R.string.write_error_too_long
    InputProblem.MALFORMED_HEX -> R.string.write_error_bad_hex
}
