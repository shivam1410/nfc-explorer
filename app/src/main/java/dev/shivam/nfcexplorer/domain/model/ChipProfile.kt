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
    /**
     * True when this geometry is known to match the physical tag.
     *
     * False means the numbers are a **safe floor**, not a measurement. Android reports
     * `TYPE_ULTRALIGHT` for MF0ICU1, Ultralight EV1 *and* NTAG213/215/216 — they share
     * ATQA `0x0044` and SAK `0x00`, and telling them apart needs `GET_VERSION`, which the
     * original Ultralight does not implement. Claiming 16 pages for an NTAG216 would
     * silently hide 852 bytes, so an unconfirmed profile is flagged as such and the UI says
     * so rather than presenting a floor as a fact.
     */
    val geometryConfirmed: Boolean,
) {
    fun supports(capability: ChipCapability): Boolean = capability in capabilities

    /**
     * Whether this app can read the tag's memory at all.
     *
     * Distinct from [geometryConfirmed], and conflating the two misled a real user. Unconfirmed
     * geometry means the memory *is* readable and the page count is a safe floor. Unreadable means the
     * tag is not in the Ultralight family, so there is no page-oriented access to it here — the UID and
     * the technology list are all there is.
     *
     * Tag actions are unaffected either way: they are keyed on the UID and never touch memory.
     */
    val hasReadableMemory: Boolean get() = pageCount > 0

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
            // A datasheet fact about this chip. Whether a given tag *is* this chip is a
            // separate question, answered by identification rather than by this constant.
            geometryConfirmed = true,
        )

        /**
         * MIFARE Ultralight C — 192 bytes in 48 pages, with 3DES authentication.
         *
         * Geometry is confirmed because the platform identifies Ultralight C by an actual
         * authentication probe rather than by ATQA/SAK alone.
         */
        val ULTRALIGHT_C = ChipProfile(
            vendor = "NXP Semiconductors",
            chipName = "MF0ICU2",
            family = "MIFARE Ultralight C",
            totalBytes = 192,
            pageCount = 48,
            pageSize = 4,
            capabilities = emptySet(),
            geometryConfirmed = true,
        )

        /**
         * The safe floor for anything exposing `MifareUltralight`: 16 pages.
         *
         * Every member of the family has at least this much memory, so a dump bounded by it
         * can never read past the end of a tag. It may well *under*-read — an NTAG216 has 231
         * pages — which is why [geometryConfirmed] is false. Phase 2 narrows this with a
         * `GET_VERSION` probe, where a NAK is itself evidence of an original Ultralight.
         */
        val ULTRALIGHT_FAMILY_MINIMUM = ChipProfile(
            vendor = "NXP Semiconductors",
            chipName = "",
            family = "MIFARE Ultralight",
            totalBytes = 64,
            pageCount = 16,
            pageSize = 4,
            capabilities = emptySet(),
            geometryConfirmed = false,
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
            geometryConfirmed = false,
        )
    }
}
