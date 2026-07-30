package dev.shivam.nfcexplorer.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the "absence is explicit" invariants the whole app is built on. Added after review
 * flagged these as logic-bearing but untested — if the [PageSnapshot] status/bytes agreement
 * or [MemoryDump.readableBytes] ordering regressed, nothing would have caught it.
 */
class MemoryDumpTest {

    private fun block(vararg values: Int) = ByteBlock.ofInts(*values)

    // --- PageSnapshot: status and bytes can never disagree ---

    @Test
    fun `a readable page carries its bytes`() {
        val page = PageSnapshot.ok(4, block(0x01, 0x02, 0x03, 0x04))

        assertTrue(page.isReadable)
        assertEquals(ReadStatus.OK, page.status)
        assertEquals(block(0x01, 0x02, 0x03, 0x04), page.bytes)
    }

    @Test
    fun `a failed page carries no bytes`() {
        val page = PageSnapshot.failed(9, ReadStatus.NAK_REFUSED, detail = "TagNakException")

        assertFalse(page.isReadable)
        assertNull(page.bytes)
        assertEquals("TagNakException", page.detail)
    }

    @Test
    fun `OK status without bytes is rejected`() {
        // The pairing is what makes rendering an unread page as zeros structurally impossible.
        assertFailsWith<IllegalArgumentException> {
            PageSnapshot(index = 4, bytes = null, status = ReadStatus.OK)
        }
    }

    @Test
    fun `a failure status carrying bytes is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PageSnapshot(index = 4, bytes = block(0x00), status = ReadStatus.IO_ERROR)
        }
    }

    @Test
    fun `the failed factory refuses to build an OK page`() {
        assertFailsWith<IllegalArgumentException> {
            PageSnapshot.failed(4, ReadStatus.OK)
        }
    }

    @Test
    fun `a negative page index is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PageSnapshot.ok(-1, block(0x00, 0x00, 0x00, 0x00))
        }
    }

    // --- MemoryDump ordering, enforced rather than assumed ---

    @Test
    fun `out of order pages are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            MemoryDump(
                pages = listOf(
                    PageSnapshot.ok(1, block(0x11, 0x11, 0x11, 0x11)),
                    PageSnapshot.ok(0, block(0x00, 0x00, 0x00, 0x00)),
                ),
                pageSize = 4,
            )
        }
    }

    @Test
    fun `duplicate page indices are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            MemoryDump(
                pages = listOf(
                    PageSnapshot.ok(0, block(0x00, 0x00, 0x00, 0x00)),
                    PageSnapshot.ok(0, block(0x11, 0x11, 0x11, 0x11)),
                ),
                pageSize = 4,
            )
        }
    }

    @Test
    fun `a page whose width disagrees with pageSize is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            MemoryDump(pages = listOf(PageSnapshot.ok(0, block(0x00, 0x01))), pageSize = 4)
        }
    }

    // --- Aggregates ---

    @Test
    fun `a complete dump reports every page readable`() {
        val dump = dumpOf(readable = 0..3, failed = IntRange.EMPTY)

        assertEquals(4, dump.readableCount)
        assertTrue(dump.isComplete)
    }

    @Test
    fun `a partial dump is not complete but keeps every page listed`() {
        val dump = dumpOf(readable = 0..1, failed = 2..3)

        assertEquals(2, dump.readableCount)
        assertFalse(dump.isComplete)
        // All four pages remain present, so a partial read is visible as partial rather than
        // as a short list that looks like a smaller tag.
        assertEquals(4, dump.pages.size)
        assertEquals(listOf(0, 1, 2, 3), dump.pages.map { it.index })
    }

    @Test
    fun `an empty dump is not complete`() {
        assertFalse(MemoryDump.EMPTY.isComplete)
        assertEquals(0, MemoryDump.EMPTY.readableCount)
    }

    @Test
    fun `page lookup finds by index and returns null when absent`() {
        val dump = dumpOf(readable = 0..1, failed = IntRange.EMPTY)

        assertEquals(0, dump.page(0)?.index)
        assertNull(dump.page(7))
    }

    // --- readableBytes ---

    @Test
    fun `readableBytes concatenates readable pages in page order`() {
        val dump = MemoryDump(
            pages = listOf(
                PageSnapshot.ok(0, block(0x00, 0x01, 0x02, 0x03)),
                PageSnapshot.ok(1, block(0x10, 0x11, 0x12, 0x13)),
            ),
            pageSize = 4,
        )

        assertEquals(block(0x00, 0x01, 0x02, 0x03, 0x10, 0x11, 0x12, 0x13), dump.readableBytes())
    }

    @Test
    fun `readableBytes skips unreadable pages without leaving zero padding`() {
        val dump = MemoryDump(
            pages = listOf(
                PageSnapshot.ok(0, block(0x00, 0x01, 0x02, 0x03)),
                PageSnapshot.failed(1, ReadStatus.NAK_REFUSED),
                PageSnapshot.ok(2, block(0x20, 0x21, 0x22, 0x23)),
            ),
            pageSize = 4,
        )

        // 8 bytes, not 12: a skipped page must not appear as four zero bytes.
        val bytes = dump.readableBytes()
        assertEquals(8, bytes.size)
        assertEquals(block(0x00, 0x01, 0x02, 0x03, 0x20, 0x21, 0x22, 0x23), bytes)
    }

    @Test
    fun `readableBytes of a fully failed dump is empty`() {
        val dump = dumpOf(readable = IntRange.EMPTY, failed = 0..3)

        assertTrue(dump.readableBytes().isEmpty)
    }

    private fun dumpOf(readable: IntRange, failed: IntRange): MemoryDump {
        val pages = buildList {
            readable.forEach { index -> add(PageSnapshot.ok(index, block(index, index, index, index))) }
            failed.forEach { index -> add(PageSnapshot.failed(index, ReadStatus.NOT_ATTEMPTED)) }
        }.sortedBy { it.index }
        return MemoryDump(pages, pageSize = 4)
    }
}
