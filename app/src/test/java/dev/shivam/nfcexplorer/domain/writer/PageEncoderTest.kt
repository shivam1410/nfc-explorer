package dev.shivam.nfcexplorer.domain.writer

import dev.shivam.nfcexplorer.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageEncoderTest {

    private fun List<ByteArray>.hex() = joinToString(" | ") { it.toHex() }

    @Test
    fun `capacity is four bytes per page`() {
        assertEquals(48, PageEncoder.capacityBytes(12))
        assertEquals(0, PageEncoder.capacityBytes(0))
    }

    // --- Text ---

    @Test
    fun `text is encoded and zero padded to fill every page`() {
        // 'H' = 0x48, 'i' = 0x69
        val pages = PageEncoder.fromText("Hi", pageCount = 2)

        assertEquals("48 69 00 00 | 00 00 00 00", pages?.hex())
    }

    @Test
    fun `text that exactly fills the pages needs no padding`() {
        val pages = PageEncoder.fromText("ABCD", pageCount = 1)

        assertEquals("41 42 43 44", pages?.hex())
    }

    @Test
    fun `every returned page is exactly one page wide`() {
        val pages = PageEncoder.fromText("hello world", pageCount = 12)

        assertEquals(12, pages?.size)
        assertTrue(pages?.all { it.size == 4 } == true)
    }

    @Test
    fun `text longer than the capacity is rejected rather than silently truncated`() {
        // 5 bytes into 1 page. Truncating would write something the user did not intend.
        assertNull(PageEncoder.fromText("ABCDE", pageCount = 1))
    }

    @Test
    fun `empty text produces all zeros`() {
        assertEquals("00 00 00 00", PageEncoder.fromText("", pageCount = 1)?.hex())
    }

    @Test
    fun `text capacity is measured in bytes, not characters`() {
        // 'é' is two bytes in UTF-8, so three of them will not fit in one page.
        assertNull(PageEncoder.fromText("ééé", pageCount = 1))
        assertEquals("C3 A9 C3 A9", PageEncoder.fromText("éé", pageCount = 1)?.hex())
    }

    // --- Hex ---

    @Test
    fun `hex is parsed into pages`() {
        assertEquals("DE AD BE EF", PageEncoder.fromHex("DEADBEEF", pageCount = 1)?.hex())
    }

    @Test
    fun `hex tolerates spaces and colons`() {
        assertEquals("DE AD BE EF", PageEncoder.fromHex("DE:AD BE:EF", pageCount = 1)?.hex())
        assertEquals("01 02 03 04", PageEncoder.fromHex("01 02 03 04", pageCount = 1)?.hex())
    }

    @Test
    fun `hex is zero padded to fill the pages`() {
        assertEquals("AA BB 00 00 | 00 00 00 00", PageEncoder.fromHex("AABB", pageCount = 2)?.hex())
    }

    @Test
    fun `hex is case insensitive`() {
        assertEquals("DE AD BE EF", PageEncoder.fromHex("deadbeef", pageCount = 1)?.hex())
    }

    @Test
    fun `an odd number of hex digits is rejected`() {
        // "ABC" is ambiguous: 0A BC or AB C0? Refuse rather than guess.
        assertNull(PageEncoder.fromHex("ABC", pageCount = 1))
    }

    @Test
    fun `non hex characters are rejected`() {
        assertNull(PageEncoder.fromHex("ZZ", pageCount = 1))
        assertNull(PageEncoder.fromHex("DEADBEEG", pageCount = 1))
    }

    @Test
    fun `hex longer than the capacity is rejected`() {
        assertNull(PageEncoder.fromHex("DEADBEEF11", pageCount = 1))
    }

    @Test
    fun `empty hex produces all zeros`() {
        assertEquals("00 00 00 00", PageEncoder.fromHex("", pageCount = 1)?.hex())
    }

    // --- Wipe ---

    @Test
    fun `zeros produces the requested number of blank pages`() {
        val pages = PageEncoder.zeros(3)

        assertEquals(3, pages.size)
        assertEquals("00 00 00 00 | 00 00 00 00 | 00 00 00 00", pages.hex())
    }

    @Test
    fun `zeros of no pages is empty`() {
        assertTrue(PageEncoder.zeros(0).isEmpty())
    }

    // --- Independence ---

    @Test
    fun `returned pages do not share a backing array`() {
        // A caller mutating one page must not alter another, which a naive fill could allow.
        val pages = PageEncoder.zeros(3)

        pages[0][0] = 0x7F

        assertEquals("00 00 00 00", pages[1].toHex())
        assertEquals("00 00 00 00", pages[2].toHex())
    }
}
