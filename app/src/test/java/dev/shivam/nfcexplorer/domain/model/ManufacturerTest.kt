package dev.shivam.nfcexplorer.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Characterisation tests for the ISO/IEC 7816-6 vendor lookup.
 *
 * Written after the implementation rather than before it: the table landed with the domain
 * models in Task 1.1, so there was no RED state to observe. Recorded as a deliberate
 * deviation from the RED -> GREEN discipline the other decoders follow.
 */
class ManufacturerTest {

    @Test
    fun `0x04 is NXP, the vendor behind MIFARE Ultralight`() {
        val manufacturer = Manufacturer.fromUidByte0(0x04)

        assertEquals(Manufacturer.Known(0x04, "NXP Semiconductors"), manufacturer)
    }

    @Test
    fun `a registered code resolves to its vendor name`() {
        assertEquals(
            Manufacturer.Known(0x02, "STMicroelectronics"),
            Manufacturer.fromUidByte0(0x02),
        )
        assertEquals(
            Manufacturer.Known(0x16, "EM Microelectronic-Marin"),
            Manufacturer.fromUidByte0(0x16),
        )
    }

    @Test
    fun `an unregistered code keeps its raw value instead of guessing`() {
        val manufacturer = Manufacturer.fromUidByte0(0x7E)

        assertEquals(Manufacturer.Unknown(0x7E), manufacturer)
        assertEquals(0x7E, manufacturer.code)
    }

    @Test
    fun `high bytes are read unsigned`() {
        // 0xF3 is negative as a Kotlin Byte; the code must still report 243, not -13.
        val manufacturer = Manufacturer.fromUidByte0(0xF3.toByte())

        assertEquals(0xF3, manufacturer.code)
        assertTrue(manufacturer is Manufacturer.Unknown)
    }

    @Test
    fun `every table entry is a plausible manufacturer code`() {
        // Codes are single bytes; a typo like 0x104 would otherwise sit unnoticed.
        (0x00..0xFF).forEach { code ->
            val manufacturer = Manufacturer.fromUidByte0(code.toByte())
            assertEquals(code, manufacturer.code)
        }
    }
}
