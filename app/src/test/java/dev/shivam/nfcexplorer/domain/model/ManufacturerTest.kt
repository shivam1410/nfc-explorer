package dev.shivam.nfcexplorer.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `every registered code is a single byte`() {
        // Iterates the table's own keys. The previous version of this test asserted
        // `fromUidByte0(code).code == code`, which holds for both Known and Unknown and so
        // could never have caught an out-of-range key like 0x104 -- it passed for the wrong
        // reason. Flagged in review.
        Manufacturer.knownCodes.forEach { code ->
            assertTrue(
                code in 0x00..0xFF,
                "0x${code.toString(16)} is not addressable as a UID byte",
            )
        }
    }

    @Test
    fun `every registered code actually resolves to a named vendor`() {
        Manufacturer.knownCodes.forEach { code ->
            val manufacturer = Manufacturer.fromUidByte0(code.toByte())

            assertIs<Manufacturer.Known>(manufacturer, "0x${code.toString(16)} did not resolve")
            assertTrue(manufacturer.name.isNotBlank(), "0x${code.toString(16)} has a blank name")
            assertEquals(code, manufacturer.code)
        }
    }

    @Test
    fun `the table has no duplicate vendor names`() {
        val names = Manufacturer.knownCodes.map { code ->
            (Manufacturer.fromUidByte0(code.toByte()) as Manufacturer.Known).name
        }

        assertEquals(names.size, names.toSet().size, "duplicate vendor name in table: $names")
    }

    @Test
    fun `unsigned widening round trips for every byte value`() {
        // Named for what it actually verifies: Byte.toInt() and 0xFF masking across the whole
        // range, not the contents of the vendor table.
        (0x00..0xFF).forEach { code ->
            assertEquals(code, Manufacturer.fromUidByte0(code.toByte()).code)
        }
    }
}
