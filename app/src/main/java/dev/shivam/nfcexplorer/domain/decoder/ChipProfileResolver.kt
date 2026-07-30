package dev.shivam.nfcexplorer.domain.decoder

import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.ChipProfile
import dev.shivam.nfcexplorer.domain.model.NfcTechnology
import dev.shivam.nfcexplorer.domain.model.TagTechnologies
import dev.shivam.nfcexplorer.domain.model.UltralightVariant

/**
 * Infers a chip profile from what the tag advertised.
 *
 * Deliberately conservative, because this decides how many pages the dump attempts.
 * Over-claiming reads past the end of a tag; under-claiming hides memory. So it reports a
 * **safe floor** and marks it unconfirmed rather than naming a chip it cannot prove:
 * MF0ICU1, Ultralight EV1 and every NTAG21x share ATQA `0x0044` and SAK `0x00`, and telling
 * them apart requires `GET_VERSION`, which the original Ultralight does not implement.
 *
 * The technology list is the authority. A variant hint is only honoured when the tag actually
 * advertises `MifareUltralight`, so a stale hint cannot conjure geometry for a tag that never
 * claimed it.
 */
object ChipProfileResolver {

    /**
     * @param atqa and [sak] are accepted for corroboration and future fingerprinting. They are
     *   not used to select geometry, because every Ultralight-family member reports the same
     *   values — trusting them would produce exactly the false confidence this resolver avoids.
     */
    fun resolve(
        technologies: TagTechnologies,
        variant: UltralightVariant = UltralightVariant.UNKNOWN,
        @Suppress("UNUSED_PARAMETER") atqa: ByteBlock? = null,
        @Suppress("UNUSED_PARAMETER") sak: Short? = null,
        @Suppress("UNUSED_PARAMETER") uidLength: Int = 0,
    ): ChipProfile {
        if (!technologies.has(NfcTechnology.MIFARE_ULTRALIGHT)) {
            return ChipProfile.UNIDENTIFIED
        }

        return when (variant) {
            UltralightVariant.ULTRALIGHT_C -> ChipProfile.ULTRALIGHT_C

            // Both cases land on the family floor. ULTRALIGHT means "somewhere in this family",
            // and UNKNOWN means the platform could not even say that much — in either case 16
            // pages is readable on anything exposing MifareUltralight.
            UltralightVariant.ULTRALIGHT,
            UltralightVariant.UNKNOWN,
            -> ChipProfile.ULTRALIGHT_FAMILY_MINIMUM
        }
    }
}
