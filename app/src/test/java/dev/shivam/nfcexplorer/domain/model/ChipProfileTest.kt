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
    }
}
