package dev.shivam.nfcexplorer.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagTechnologiesTest {

    private val ultralight = TagTechnologies(
        available = listOf(
            TechnologyInfo(
                name = "android.nfc.tech.NfcA",
                maxTransceiveLength = 253,
                timeoutMillis = 618,
                extras = mapOf("atqa" to "44 00", "sak" to "00"),
            ),
            TechnologyInfo(name = "android.nfc.tech.MifareUltralight", maxTransceiveLength = 253),
            TechnologyInfo(name = "android.nfc.tech.NdefFormatable"),
        ),
    )

    @Test
    fun `has finds an advertised technology`() {
        assertTrue(ultralight.has("android.nfc.tech.MifareUltralight"))
        assertFalse(ultralight.has("android.nfc.tech.IsoDep"))
    }

    @Test
    fun `names preserve the order the platform reported`() {
        // Order carries information: the platform lists the primary technology first.
        assertEquals(
            listOf(
                "android.nfc.tech.NfcA",
                "android.nfc.tech.MifareUltralight",
                "android.nfc.tech.NdefFormatable",
            ),
            ultralight.names,
        )
    }

    @Test
    fun `a technology that exposes no transceive length or timeout reports null`() {
        val formatable = ultralight.available.last()

        // Only some technologies expose these; inventing a number would misreport the tag.
        assertNull(formatable.maxTransceiveLength)
        assertNull(formatable.timeoutMillis)
        assertTrue(formatable.extras.isEmpty())
    }

    @Test
    fun `extras carry protocol values verbatim`() {
        val nfcA = ultralight.available.first()

        assertEquals("44 00", nfcA.extras["atqa"])
        assertEquals("00", nfcA.extras["sak"])
    }

    @Test
    fun `the empty inventory has no technologies`() {
        assertTrue(TagTechnologies.EMPTY.available.isEmpty())
        assertTrue(TagTechnologies.EMPTY.names.isEmpty())
        assertFalse(TagTechnologies.EMPTY.has("android.nfc.tech.NfcA"))
    }
}
