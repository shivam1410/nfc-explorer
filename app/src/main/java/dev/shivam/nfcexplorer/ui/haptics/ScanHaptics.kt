package dev.shivam.nfcexplorer.ui.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** What just happened to a tag, for the purpose of telling the user's hand about it. */
enum class ScanFeedback {
    /** A tag entered the field and the dump has started. */
    DETECTED,

    /** The dump finished. */
    CAPTURED,

    /** The read failed or the tag left the field. */
    FAILED,
}

/**
 * A feedback event, paired with a token that makes repeats distinguishable.
 *
 * The token matters: [ScanFeedback] alone would compare equal across two identical scans of the
 * same card, so [LaunchedEffect] would not re-fire and the second tap would feel dead. The token
 * simply has to change per event.
 */
data class ScanHapticSignal(
    val feedback: ScanFeedback,
    val token: Long,
)

/**
 * Vibrates in response to [signal].
 *
 * Uses Compose's [LocalHapticFeedback] rather than [android.os.Vibrator], which is a real
 * simplification and not just a stylistic one:
 *
 *  - **no `VIBRATE` permission** is needed, so the app's manifest stays as narrow as it is now;
 *  - **no API-level branching.** `VibratorManager` arrived in API 31 and
 *    `getSystemService(VIBRATOR_SERVICE)` is deprecated from 31, so a direct implementation on
 *    `minSdk 26` would need a version check with a deprecated branch. This has neither.
 *  - it **respects the user's system haptic settings**, which a raw vibrator call bypasses.
 *
 * Distinct effects per outcome, so a scan can be told apart from a failure without looking at the
 * screen — which is the point when the phone is held against a card.
 */
@Composable
fun ScanHapticFeedback(signal: ScanHapticSignal?) {
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(signal) {
        when (signal?.feedback) {
            // Light tick the moment the tag lands, so the tap is acknowledged immediately rather
            // than after the dump completes.
            ScanFeedback.DETECTED -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            ScanFeedback.CAPTURED -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            ScanFeedback.FAILED -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            null -> Unit
        }
    }
}
