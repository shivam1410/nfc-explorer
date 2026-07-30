package dev.shivam.nfcexplorer.domain.decoder

import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.Manufacturer
import dev.shivam.nfcexplorer.fake.Mf0icu1Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UidDecoderTest {

    private val sampleUid = ByteBlock.copyOf(Mf0icu1Fixtures.SAMPLE_UID)

    // --- Cascade levels ---

    @Test
    fun `cascade levels follow UID length`() {
        assertEquals(1, UidDecoder.cascadeLevels(4))
        assertEquals(2, UidDecoder.cascadeLevels(7))
        assertEquals(3, UidDecoder.cascadeLevels(10))
    }

    @Test
    fun `cascade levels are unknown for a non standard UID length`() {
        assertNull(UidDecoder.cascadeLevels(5))
        assertNull(UidDecoder.cascadeLevels(0))
    }

    // --- Check bytes ---

    @Test
    fun `BCC0 is the cascade tag xored with the first three UID bytes`() {
        // 0x88 xor 0x04 xor 0xA2 xor 0x55 = 0x7B
        assertEquals(0x7B, UidDecoder.computeBcc0(sampleUid)?.toInt()?.and(0xFF))
    }

    @Test
    fun `BCC1 is the xor of the last four UID bytes`() {
        // 0x71 xor 0x18 xor 0x39 xor 0xFF = 0xAF
        assertEquals(0xAF, UidDecoder.computeBcc1(sampleUid)?.toInt()?.and(0xFF))
    }

    @Test
    fun `check bytes are undefined for a four byte UID`() {
        val shortUid = ByteBlock.ofInts(0x04, 0x11, 0x22, 0x33)

        // A single-size UID carries no BCC in page 0; claiming one would be invention.
        assertNull(UidDecoder.computeBcc0(shortUid))
        assertNull(UidDecoder.computeBcc1(shortUid))
    }

    // --- identify() ---

    @Test
    fun `identify reports both check bytes as valid for a well formed tag`() {
        val identity = UidDecoder.identify(
            uid = sampleUid,
            storedBcc0 = Mf0icu1Fixtures.bcc0(Mf0icu1Fixtures.SAMPLE_UID),
            storedBcc1 = Mf0icu1Fixtures.bcc1(Mf0icu1Fixtures.SAMPLE_UID),
        )

        assertTrue(requireNotNull(identity.bcc0) { "bcc0 not computed" }.isValid)
        assertTrue(requireNotNull(identity.bcc1) { "bcc1 not computed" }.isValid)
        assertEquals(7, identity.uidLength)
        assertEquals(2, identity.cascadeLevels)
        assertEquals(Manufacturer.Known(0x04, "NXP Semiconductors"), identity.manufacturer)
    }

    @Test
    fun `identify reports a corrupted check byte and keeps both values`() {
        val identity = UidDecoder.identify(
            uid = sampleUid,
            storedBcc0 = 0x00,
            storedBcc1 = Mf0icu1Fixtures.bcc1(Mf0icu1Fixtures.SAMPLE_UID),
        )

        val bcc0 = requireNotNull(identity.bcc0) { "bcc0 not computed" }
        assertFalse(bcc0.isValid)
        // Both sides retained: the difference is the diagnostic, not just the failure.
        assertEquals(0x00, bcc0.stored.toInt() and 0xFF)
        assertEquals(0x7B, bcc0.computed.toInt() and 0xFF)
        assertTrue(requireNotNull(identity.bcc1) { "bcc1 not computed" }.isValid)
    }

    @Test
    fun `identify leaves check bytes null when the pages holding them were not read`() {
        val identity = UidDecoder.identify(uid = sampleUid)

        // Null means "not established" and must stay distinguishable from "invalid".
        assertNull(identity.bcc0)
        assertNull(identity.bcc1)
    }

    @Test
    fun `identify preserves ATQA and SAK when the tag supplied them`() {
        val identity = UidDecoder.identify(
            uid = sampleUid,
            atqa = ByteBlock.ofInts(0x44, 0x00),
            sak = 0x00,
        )

        assertEquals(ByteBlock.ofInts(0x44, 0x00), identity.atqa)
        assertEquals(0x00, identity.sak)
    }

    @Test
    fun `identify reports an unregistered manufacturer code without guessing`() {
        val identity = UidDecoder.identify(uid = ByteBlock.ofInts(0xF3, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66))

        assertEquals(Manufacturer.Unknown(0xF3), identity.manufacturer)
    }
}
