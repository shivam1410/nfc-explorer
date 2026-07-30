package dev.shivam.nfcexplorer.fake

import dev.shivam.nfcexplorer.domain.transport.TagFieldLostException
import dev.shivam.nfcexplorer.domain.transport.TagNakException
import dev.shivam.nfcexplorer.domain.transport.TagNotConnectedException
import dev.shivam.nfcexplorer.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies the fake itself.
 *
 * Every other Phase 1 test trusts this fake to behave like a real MF0ICU1. If the fake is
 * permissive where the chip is strict, those tests pass while the real tag fails — so the
 * fake gets tested first and directly.
 */
class FakeUltralightTransportTest {

    private fun connected(
        memory: ByteArray = Mf0icu1Fixtures.blank(),
        failFromPage: Int? = null,
        nakPages: Set<Int> = emptySet(),
    ) = FakeUltralightTransport(memory, failFromPage, nakPages).apply { connect() }

    // --- READ wrap-around: the behaviour that silently corrupts naive dumps ---

    @Test
    fun `readPages returns four pages`() {
        val transport = connected(Mf0icu1Fixtures.hotelCardLike())

        val bytes = transport.readPages(4)

        assertEquals(16, bytes.size)
        assertEquals("5A 11 03 7C 00 1E 44 90 21 08 14 06 00 00 00 00", bytes.toHex())
    }

    @Test
    fun `readPages wraps past the last page`() {
        val transport = connected()

        val bytes = transport.readPages(14)

        // Pages 14, 15, then 0 and 1 again - not pages 16 and 17, which do not exist.
        val page0 = transport.page(0)
        val page1 = transport.page(1)
        assertContentEquals(page0, bytes.copyOfRange(8, 12))
        assertContentEquals(page1, bytes.copyOfRange(12, 16))
    }

    @Test
    fun `readPages rejects an out of range page`() {
        val transport = connected()

        assertFailsWith<TagNakException> { transport.readPages(16) }
        assertFailsWith<TagNakException> { transport.readPages(-1) }
    }

    @Test
    fun `readPages fails from the configured page when the tag is pulled away`() {
        val transport = connected(failFromPage = 8)

        transport.readPages(4)

        assertFailsWith<TagFieldLostException> { transport.readPages(8) }
    }

    @Test
    fun `readPages naks when any page in the window refuses`() {
        val transport = connected(nakPages = setOf(6))

        // Window 4..7 covers the refusing page, so the whole command fails.
        assertFailsWith<TagNakException> { transport.readPages(4) }
        // Window 8..11 does not.
        transport.readPages(8)
    }

    // --- Write semantics ---

    @Test
    fun `writePage stores a user page`() {
        val transport = connected()
        val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

        transport.writePage(7, payload)

        assertContentEquals(payload, transport.page(7))
    }

    @Test
    fun `writePage refuses the UID pages`() {
        val transport = connected()
        val before = transport.snapshot()

        assertFailsWith<TagNakException> { transport.writePage(0, ByteArray(4)) }
        assertFailsWith<TagNakException> { transport.writePage(1, ByteArray(4)) }

        assertContentEquals(before, transport.snapshot())
    }

    @Test
    fun `writePage refuses a locked page`() {
        val transport = connected(Mf0icu1Fixtures.hotelCardLike())
        val before = transport.page(7)

        assertFailsWith<TagNakException> { transport.writePage(7, ByteArray(4)) }

        assertContentEquals(before, transport.page(7))
    }

    @Test
    fun `writePage ors OTP bits and never clears them`() {
        val transport = connected()

        transport.writePage(3, byteArrayOf(0x0F, 0x00, 0x00, 0x00))
        transport.writePage(3, byteArrayOf(0xF0.toByte(), 0x00, 0x00, 0x00))
        // Attempting to clear the low nibble must have no effect.
        transport.writePage(3, byteArrayOf(0x00, 0x00, 0x00, 0x00))

        assertEquals("FF 00 00 00", transport.page(3).toHex())
    }

    @Test
    fun `writePage ors lock bytes and leaves BCC1 and the internal byte alone`() {
        val transport = connected()
        val originalBcc1 = transport.page(2)[0]

        transport.writePage(2, byteArrayOf(0x00, 0x00, 0x10, 0x00)) // set L_4
        transport.writePage(2, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x20, 0x00)) // set L_5

        val page2 = transport.page(2)
        assertEquals(originalBcc1, page2[0])
        assertEquals(Mf0icu1Fixtures.INTERNAL_BYTE, page2[1])
        assertEquals(0x30, page2[2].toInt() and 0xFF) // L_4 | L_5
    }

    @Test
    fun `setting a lock bit makes that page reject writes`() {
        val transport = connected()
        transport.writePage(9, byteArrayOf(0x01, 0x02, 0x03, 0x04))

        transport.writePage(2, byteArrayOf(0x00, 0x00, 0x00, 0x02)) // L_9

        assertFailsWith<TagNakException> { transport.writePage(9, ByteArray(4)) }
        assertEquals("01 02 03 04", transport.page(9).toHex())
    }

    @Test
    fun `writePage rejects a payload that is not one page wide`() {
        val transport = connected()

        assertFailsWith<TagNakException> { transport.writePage(5, byteArrayOf(0x01, 0x02, 0x03)) }
    }

    // --- Bookkeeping used by later tests ---

    @Test
    fun `writes records every attempt including rejected ones`() {
        val transport = connected(Mf0icu1Fixtures.hotelCardLike())

        runCatching { transport.writePage(7, byteArrayOf(0x01, 0x02, 0x03, 0x04)) }

        assertEquals(1, transport.writes.size)
        assertEquals(7, transport.writes.single().page)
    }

    @Test
    fun `snapshot cannot be used to mutate the fake`() {
        val transport = connected()

        transport.snapshot()[20] = 0x7F

        assertEquals(0, transport.page(5)[0].toInt())
    }

    @Test
    fun `exchanges before connect and after close are rejected`() {
        val transport = FakeUltralightTransport(Mf0icu1Fixtures.blank())

        assertFailsWith<TagNotConnectedException> { transport.readPages(0) }

        transport.connect()
        transport.readPages(0)
        transport.close()

        assertFailsWith<TagNotConnectedException> { transport.readPages(0) }
        assertTrue(transport.isClosed)
    }

    // --- Fixture integrity ---

    @Test
    fun `fixtures compute check bytes that match their own UID`() {
        val transport = connected()
        val uid = Mf0icu1Fixtures.SAMPLE_UID

        assertEquals(Mf0icu1Fixtures.bcc0(uid), transport.page(0)[3])
        assertEquals(Mf0icu1Fixtures.bcc1(uid), transport.page(2)[0])
        // Guards against a fixture that silently agrees with a wrong formula.
        assertEquals(0x7B, transport.page(0)[3].toInt() and 0xFF)
        assertEquals(0xAF, transport.page(2)[0].toInt() and 0xFF)
    }
}
