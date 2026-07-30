package dev.shivam.nfcexplorer.domain.model

/**
 * Result of verifying one of the UID check bytes.
 *
 * Both values are kept so the UI can show *computed vs stored* rather than a bare
 * pass/fail — when a card misbehaves, the difference is the diagnostic.
 */
data class BccCheck(
    val label: String,
    val stored: Byte,
    val computed: Byte,
) {
    val isValid: Boolean get() = stored == computed
}

/**
 * Everything the anticollision phase and page 0–2 reveal about a tag's identity.
 *
 * [atqa] and [sak] are null when the tag was not reached over NfcA, the [BccCheck] fields
 * are null when the pages carrying them could not be read, and [cascadeLevels] is null when
 * the UID length is not one the standard defines. Null means "not established", which the UI
 * must render distinctly from a zero value.
 */
data class TagIdentity(
    val uid: ByteBlock,
    val atqa: ByteBlock?,
    val sak: Short?,
    val manufacturer: Manufacturer,
    val cascadeLevels: Int?,
    val bcc0: BccCheck?,
    val bcc1: BccCheck?,
) {
    val uidLength: Int get() = uid.size
}
