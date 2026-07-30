package dev.shivam.nfcexplorer.ui.scan

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.shivam.nfcexplorer.R

/**
 * The waiting / reading / failed surface.
 *
 * Shown only when there is nothing to display yet. Once a report exists the user is on the Tag,
 * Memory, Locks or Log screens, which keep showing the last dump even while a new scan is in
 * flight — a half-read dump is more useful than a spinner.
 */
@Composable
fun ScanScreen(
    state: ScanUiState,
    onOpenNfcSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            ScanUiState.Starting -> Text(stringResource(R.string.scan_starting))

            ScanUiState.Unsupported -> Message(
                title = stringResource(R.string.scan_unsupported_title),
                body = stringResource(R.string.scan_unsupported_body),
            )

            ScanUiState.Disabled -> {
                Message(
                    title = stringResource(R.string.scan_disabled_title),
                    body = stringResource(R.string.scan_disabled_body),
                )
                Button(onClick = onOpenNfcSettings) {
                    Text(stringResource(R.string.action_open_nfc_settings))
                }
            }

            ScanUiState.WaitingForTag -> {
                ScanPulse()
                Message(
                    title = stringResource(R.string.scan_waiting_title),
                    body = stringResource(R.string.scan_waiting_body),
                )
            }

            ScanUiState.Reading -> {
                ScanPulse()
                Text(stringResource(R.string.scan_captured))
            }

            is ScanUiState.Captured -> Text(stringResource(R.string.scan_captured))

            is ScanUiState.Failed -> Message(
                title = stringResource(R.string.scan_failed_title),
                body = "${state.exceptionName}: ${state.message ?: ""}",
            )
        }
    }
}

@Composable
private fun Message(title: String, body: String) {
    Text(text = title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * Slow concentric pulse behind the contactless glyph.
 *
 * The one piece of decorative motion in the app, and it earns its place: it is the only signal
 * that the app is actively listening, since NFC gives no other feedback until a tag arrives.
 */
@Composable
private fun ScanPulse() {
    val transition = rememberInfiniteTransition(label = "scanPulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanPulseProgress",
    )
    val color = MaterialTheme.colorScheme.primary

    Canvas(modifier = Modifier.size(140.dp)) {
        val maxRadius = size.minDimension / 2f
        // Two rings offset by half a cycle so the pulse reads as continuous.
        listOf(progress, (progress + 0.5f) % 1f).forEach { phase ->
            drawCircle(
                color = color,
                radius = maxRadius * phase,
                alpha = (1f - phase).coerceIn(0f, 1f) * 0.5f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
            )
        }
    }
    Icon(
        painter = painterResource(R.drawable.ic_nav_tag),
        contentDescription = null,
        modifier = Modifier.size(40.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
}
