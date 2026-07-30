package dev.shivam.nfcexplorer.domain.decoder

import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.ChipProfile
import dev.shivam.nfcexplorer.domain.model.NfcTechnology
import dev.shivam.nfcexplorer.domain.model.TagTechnologies
import dev.shivam.nfcexplorer.domain.model.TechnologyInfo
import dev.shivam.nfcexplorer.domain.model.UltralightVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The resolver decides how many pages the dump will attempt, so over-claiming geometry means
 * reading past the end of a tag and under-claiming means silently hiding memory. It is
 * deliberately conservative: it reports a safe floor and flags it as unconfirmed rather than
 * guessing a specific chip.
 */
class ChipProfileResolverTest {

    private fun technologies(vararg names: String) =
        TagTechnologies(names.map { TechnologyInfo(name = it) })

    private val ultralightTechs = technologies(
        NfcTechnology.NFC_A,
        NfcTechnology.MIFARE_ULTRALIGHT,
        NfcTechnology.NDEF_FORMATABLE,
    )

    // --- Ultralight family ---

    @Test
    fun `an Ultralight tag resolves to the family floor, not to a specific chip`() {
        val profile = ChipProfileResolver.resolve(
            technologies = ultralightTechs,
            variant = UltralightVariant.ULTRALIGHT,
            atqa = ByteBlock.ofInts(0x44, 0x00),
            sak = 0x00,
            uidLength = 7,
        )

        assertEquals("MIFARE Ultralight", profile.family)
        assertEquals(16, profile.pageCount)
        assertEquals(4, profile.pageSize)
        // MF0ICU1, Ultralight EV1 and every NTAG21x are indistinguishable here, so naming one
        // would be a guess presented as a fact.
        assertEquals("", profile.chipName)
        assertFalse(profile.geometryConfirmed)
    }

    @Test
    fun `the family floor never over-reads the smallest member of the family`() {
        val profile = ChipProfileResolver.resolve(
            technologies = ultralightTechs,
            variant = UltralightVariant.ULTRALIGHT,
        )

        // 16 pages is what the original Ultralight has; nothing in the family has fewer, so a
        // dump bounded by this cannot run past the end of any tag.
        assertTrue(profile.pageCount <= ChipProfile.MF0ICU1.pageCount)
        assertEquals(profile.totalBytes, profile.pageCount * profile.pageSize)
    }

    @Test
    fun `an unclassifiable Ultralight still resolves to the family floor`() {
        val profile = ChipProfileResolver.resolve(
            technologies = ultralightTechs,
            variant = UltralightVariant.UNKNOWN,
        )

        // The tag exposes MifareUltralight, so page reads will work; 16 pages stays safe.
        assertEquals(16, profile.pageCount)
        assertFalse(profile.geometryConfirmed)
    }

    @Test
    fun `Ultralight C resolves to confirmed 48 page geometry`() {
        val profile = ChipProfileResolver.resolve(
            technologies = ultralightTechs,
            variant = UltralightVariant.ULTRALIGHT_C,
            atqa = ByteBlock.ofInts(0x44, 0x00),
            sak = 0x00,
            uidLength = 7,
        )

        assertEquals("MIFARE Ultralight C", profile.family)
        assertEquals(48, profile.pageCount)
        assertEquals(192, profile.totalBytes)
        // The platform identifies Ultralight C by an authentication probe, not by ATQA alone,
        // so this geometry is measured rather than assumed.
        assertTrue(profile.geometryConfirmed)
    }

    @Test
    fun `no Ultralight profile claims a capability this family predates`() {
        val profile = ChipProfileResolver.resolve(
            technologies = ultralightTechs,
            variant = UltralightVariant.ULTRALIGHT,
        )

        assertTrue(profile.capabilities.isEmpty())
    }

    // --- Not an Ultralight ---

    @Test
    fun `a tag without MifareUltralight is unidentified rather than assumed`() {
        val profile = ChipProfileResolver.resolve(
            technologies = technologies(NfcTechnology.NFC_A, NfcTechnology.MIFARE_CLASSIC),
            variant = UltralightVariant.UNKNOWN,
        )

        assertEquals(ChipProfile.UNIDENTIFIED, profile)
        // Zero geometry, so the read pipeline attempts nothing rather than reading blind.
        assertEquals(0, profile.pageCount)
    }

    @Test
    fun `an IsoDep only tag is unidentified in Phase 1`() {
        val profile = ChipProfileResolver.resolve(
            technologies = technologies(NfcTechnology.NFC_A, NfcTechnology.ISO_DEP),
        )

        assertEquals(ChipProfile.UNIDENTIFIED, profile)
    }

    @Test
    fun `an empty technology list is unidentified`() {
        assertEquals(
            ChipProfile.UNIDENTIFIED,
            ChipProfileResolver.resolve(TagTechnologies.EMPTY),
        )
    }

    @Test
    fun `a variant hint without the Ultralight technology is not trusted`() {
        // The technology list is the authority. A stale or wrong variant hint must not
        // conjure Ultralight geometry for a tag that never advertised it.
        val profile = ChipProfileResolver.resolve(
            technologies = technologies(NfcTechnology.NFC_F),
            variant = UltralightVariant.ULTRALIGHT_C,
        )

        assertEquals(ChipProfile.UNIDENTIFIED, profile)
    }

    // --- Vendor ---

    @Test
    fun `the Ultralight family is attributed to NXP`() {
        val profile = ChipProfileResolver.resolve(
            technologies = ultralightTechs,
            variant = UltralightVariant.ULTRALIGHT,
        )

        assertEquals("NXP Semiconductors", profile.vendor)
    }
}
