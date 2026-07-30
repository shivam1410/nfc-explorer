package dev.shivam.nfcexplorer.ui.labels

import androidx.annotation.StringRes
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.domain.model.ChipCapability
import dev.shivam.nfcexplorer.domain.model.ReadStatus
import dev.shivam.nfcexplorer.domain.model.WriteVerdict
import dev.shivam.nfcexplorer.logging.LogLevel
import dev.shivam.nfcexplorer.ui.component.ChipTone

/**
 * Maps domain enums to user-facing wording and colour tone.
 *
 * This mapping lives in the UI layer on purpose: the domain layer carries reason *codes* so it can
 * stay free of translatable prose and of any resource dependency. This is where the two meet.
 */

@StringRes
fun WriteVerdict.labelRes(): Int = when (this) {
    WriteVerdict.WRITABLE -> R.string.verdict_writable
    WriteVerdict.PERMANENTLY_LOCKED -> R.string.verdict_locked
    WriteVerdict.OTP_ONE_WAY -> R.string.verdict_otp
    WriteVerdict.HARDWARE_READ_ONLY -> R.string.verdict_read_only
    WriteVerdict.LOCK_CONTROL -> R.string.verdict_lock_control
    WriteVerdict.UNKNOWN_LOCK_STATE -> R.string.verdict_unknown
}

fun WriteVerdict.tone(): ChipTone = when (this) {
    WriteVerdict.WRITABLE -> ChipTone.POSITIVE
    WriteVerdict.PERMANENTLY_LOCKED -> ChipTone.NEGATIVE
    // Caution rather than failure: these are writable, but only one way.
    WriteVerdict.OTP_ONE_WAY, WriteVerdict.LOCK_CONTROL -> ChipTone.CAUTION
    WriteVerdict.HARDWARE_READ_ONLY, WriteVerdict.UNKNOWN_LOCK_STATE -> ChipTone.NEUTRAL
}

@StringRes
fun ReadStatus.labelRes(): Int = when (this) {
    ReadStatus.OK -> R.string.bcc_valid
    ReadStatus.NAK_REFUSED -> R.string.read_status_nak
    ReadStatus.IO_ERROR -> R.string.read_status_io_error
    ReadStatus.TAG_LOST -> R.string.read_status_tag_lost
    ReadStatus.NOT_ATTEMPTED -> R.string.read_status_not_attempted
}

@StringRes
fun ChipCapability.labelRes(): Int = when (this) {
    ChipCapability.FAST_READ -> R.string.capability_fast_read
    ChipCapability.GET_VERSION -> R.string.capability_get_version
    ChipCapability.PWD_AUTH -> R.string.capability_pwd_auth
    ChipCapability.DYNAMIC_LOCK_BITS -> R.string.capability_dynamic_lock_bits
    ChipCapability.COUNTERS -> R.string.capability_counters
    ChipCapability.NDEF -> R.string.capability_ndef
}

fun LogLevel.tone(): ChipTone = when (this) {
    LogLevel.DEBUG -> ChipTone.NEUTRAL
    LogLevel.INFO -> ChipTone.POSITIVE
    LogLevel.WARN -> ChipTone.CAUTION
    LogLevel.ERROR -> ChipTone.NEGATIVE
}
