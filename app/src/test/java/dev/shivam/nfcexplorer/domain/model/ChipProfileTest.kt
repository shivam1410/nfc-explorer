package dev.shivam.nfcexplorer.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChipProfileTest {

    @Test
    fun `MF0ICU1 geometry matches the datasheet`() {
        val profile = ChipProfile.MF0ICU1

        assertEquals("MF0ICU1", profile.chipName)
        assertEquals("MIFARE Ultralight", profile.family)
        assertEquals("NXP Semiconductors", profile.vendor)
        assertEquals(64, profile.totalBytes)
        assertEquals(16, profile.pageCount)
        assertEquals(4, profile.pageSize)
        // Geometry must be self-consistent: the read pipeline strides by pageSize over
        // pageCount and would run off the end of memory if these disagreed.
        assertEquals(profile.totalBytes, profile.pageCount * profile.pageSize)
        // A datasheet fact about this specific chip.
        assertTrue(profile.geometryConfirmed)
    }

    @Test
    fun `the family floor is flagged as unconfirmed geometry`() {
        val floor = ChipProfile.ULTRALIGHT_FAMILY_MINIMUM

        // It is a safe lower bound, not a measurement: an NTAG216 has 231 pages and reports
        // the same ATQA and SAK. Presenting the floor as fact would hide 852 bytes.
        assertFalse(floor.geometryConfirmed)
        assertEquals(16, floor.pageCount)
        assertEquals("", floor.chipName)
        assertEquals("MIFARE Ultralight", floor.family)
    }

    @Test
    fun `Ultralight C geometry is confirmed and self consistent`() {
        val profile = ChipProfile.ULTRALIGHT_C

        assertTrue(profile.geometryConfirmed)
        assertEquals(48, profile.pageCount)
        assertEquals(profile.totalBytes, profile.pageCount * profile.pageSize)
    }

    @Test
    fun `MF0ICU1 advertises no optional capability`() {
        // Empty on purpose. This chip predates GET_VERSION, FAST_READ, password auth,
        // dynamic lock bits and counters, and saying so is the informative part.
        assertTrue(ChipProfile.MF0ICU1.capabilities.isEmpty())

        ChipCapability.entries.forEach { capability ->
            assertFalse(
                ChipProfile.MF0ICU1.supports(capability),
                "MF0ICU1 must not claim $capability",
            )
        }
    }

    @Test
    fun `supports reports true only for a capability actually present`() {
        val profile = ChipProfile.MF0ICU1.copy(
            capabilities = setOf(ChipCapability.FAST_READ, ChipCapability.GET_VERSION),
        )

        assertTrue(profile.supports(ChipCapability.FAST_READ))
        assertTrue(profile.supports(ChipCapability.GET_VERSION))
        assertFalse(profile.supports(ChipCapability.PWD_AUTH))
    }

    @Test
    fun `the unidentified profile has zero geometry so no page can be dumped from it`() {
        // Zeroed rather than defaulted to Ultralight's layout: a caller must not be able to
        // mistake an unknown chip for a 16-page tag and start reading addresses blind.
        val profile = ChipProfile.UNIDENTIFIED

        assertEquals(0, profile.pageCount)
        assertEquals(0, profile.pageSize)
        assertEquals(0, profile.totalBytes)
        assertTrue(profile.capabilities.isEmpty())
        assertFalse(profile.geometryConfirmed)
    }
}

class ChipProfileReadabilityTest {

    @Test
    fun `an identified chip has readable memory`() {
        assertTrue(ChipProfile.MF0ICU1.hasReadableMemory)
    }

    @Test
    fun `an unidentified chip has no readable memory`() {
        // What a tag outside the Ultralight family produces: the UID and technologies are known, the
        // memory is not reachable at all. Distinct from "geometry unconfirmed", which means the memory
        // IS readable and the page count is a floor.
        assertFalse(ChipProfile.UNIDENTIFIED.hasReadableMemory)
    }

    @Test
    fun `unreadable is not the same as unconfirmed`() {
        // The distinction the UI was missing. ULTRALIGHT_FAMILY_MINIMUM is what a real hotel card gets:
        // the page count is a floor rather than a measurement, yet every page still reads. UNIDENTIFIED
        // means no page reads at all. Conflating them made the app explain a GET_VERSION quirk to
        // someone holding a card from a completely different chip family.
        val floor = ChipProfile.ULTRALIGHT_FAMILY_MINIMUM

        assertFalse(floor.geometryConfirmed, "a floor is not a measurement")
        assertTrue(floor.hasReadableMemory, "but its pages still read")
        assertFalse(ChipProfile.UNIDENTIFIED.hasReadableMemory)
    }
}
