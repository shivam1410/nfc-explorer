package dev.shivam.nfcexplorer.domain.model

/**
 * A capability a chip may or may not have.
 *
 * Modelled explicitly so the UI can state "not supported by this chip" instead of leaving
 * a field blank. On MF0ICU1 every one of these is absent, and saying so is informative —
 * it is exactly why other apps fail to do more with the tag.
 */
enum class ChipCapability {
    FAST_READ,
    GET_VERSION,
    PWD_AUTH,
    DYNAMIC_LOCK_BITS,
    COUNTERS,
    NDEF,
}

/**
 * Chip geometry and feature set. Geometry drives the read pipeline, so it must be right
 * before any page is fetched.
 */
data class ChipProfile(
    val vendor: String,
    val chipName: String,
    val family: String,
    val totalBytes: Int,
    val pageCount: Int,
    val pageSize: Int,
    val capabilities: Set<ChipCapability>,
) {
    fun supports(capability: ChipCapability): Boolean = capability in capabilities

    companion object {
        /**
         * The original MIFARE Ultralight. `capabilities` is empty on purpose: no
         * GET_VERSION, no FAST_READ, no password auth, and no dynamic lock bits.
         * See `docs/mf0icu1-reference.md`.
         */
        val MF0ICU1 = ChipProfile(
            vendor = "NXP Semiconductors",
            chipName = "MF0ICU1",
            family = "MIFARE Ultralight",
            totalBytes = 64,
            pageCount = 16,
            pageSize = 4,
            capabilities = emptySet(),
        )

        /**
         * Fallback for a tag whose chip could not be pinned down. Geometry is zeroed so a
         * caller cannot mistake it for a readable layout and start dumping pages.
         */
        val UNIDENTIFIED = ChipProfile(
            vendor = "",
            chipName = "",
            family = "",
            totalBytes = 0,
            pageCount = 0,
            pageSize = 0,
            capabilities = emptySet(),
        )
    }
}
